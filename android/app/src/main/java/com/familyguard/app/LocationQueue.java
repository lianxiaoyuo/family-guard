package com.familyguard.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** WAL queue with Keystore-encrypted position payloads. All calls from the service IO executor. */
final class LocationQueue extends SQLiteOpenHelper {
    LocationQueue(Context c){super(c,"pending_points.db",null,3);setWriteAheadLoggingEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE pending(id TEXT PRIMARY KEY, encrypted TEXT NOT NULL, deviceTime INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX pending_time ON pending(deviceTime)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldV,int newV){
        if(oldV==1){
            db.execSQL("ALTER TABLE pending RENAME TO legacy_pending");
            db.execSQL("DROP INDEX IF EXISTS pending_time");
            onCreate(db);
            try(Cursor c=db.rawQuery("SELECT id,lat,lon,accuracy,speed,deviceTime FROM legacy_pending",null)){
                while(c.moveToNext()){
                    JSONObject p=point(c.getString(0),c.getDouble(1),c.getDouble(2),c.getDouble(3),c.getDouble(4),c.getLong(5));
                    db.execSQL("INSERT OR IGNORE INTO pending VALUES(?,?,?)",new Object[]{c.getString(0),Crypto.encrypt(p.toString()),c.getLong(5)});
                }
            }catch(Exception ex){throw new IllegalStateException("Cannot securely migrate pending locations",ex);}
            db.execSQL("DROP TABLE legacy_pending");
        }else if(oldV==2){
            // V2 already stores encrypted payloads; keep them during the V3 upgrade.
        }else{throw new IllegalStateException("Unsupported queue schema");}
    }
    private static JSONObject point(String id,double lat,double lon,double accuracy,double speed,long ts) throws Exception {
        return new JSONObject().put("id",id).put("lat",lat).put("lon",lon).put("accuracy",accuracy).put("speed",speed).put("deviceTime",ts);
    }
    /** Preserve an encrypted, bounded trajectory while offline (up to 1200 points). */
    synchronized void add(String id,double lat,double lon,float accuracy,float speed,long ts,String provider,String coordType) throws Exception {
        JSONObject p=point(id,lat,lon,accuracy,speed,ts).put("provider",provider).put("coordType",coordType);
        String cipher=Crypto.encrypt(p.toString());
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("INSERT OR IGNORE INTO pending VALUES(?,?,?)",new Object[]{id,cipher,ts});
            // Bound storage even if a phone stays offline for hours or days.
            db.execSQL("DELETE FROM pending WHERE deviceTime < ?",new Object[]{System.currentTimeMillis()-6L*24*60*60*1000});
            db.execSQL("DELETE FROM pending WHERE id NOT IN (SELECT id FROM pending ORDER BY deviceTime DESC, rowid DESC LIMIT 1200)");
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    synchronized JSONObject latest() throws Exception {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,encrypted FROM pending ORDER BY deviceTime DESC LIMIT 1",null)) {
            if (!c.moveToFirst()) return null;
            JSONObject p=new JSONObject(Crypto.decrypt(c.getString(1)));
            if (!c.getString(0).equals(p.optString("id"))) throw new IllegalStateException("queue item ID mismatch");
            return p;
        }
    }
    synchronized Batch next() throws Exception {
        // Never allow a week-old offline record to block every new upload.
        getWritableDatabase().execSQL("DELETE FROM pending WHERE deviceTime < ?",new Object[]{System.currentTimeMillis()-6L*24*60*60*1000});
        JSONArray a=new JSONArray();List<String> ids=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,encrypted FROM pending ORDER BY deviceTime ASC LIMIT 50",null)){
            while(c.moveToNext()){
                // Corrupt or undecryptable data is not silently acknowledged or replaced.
                JSONObject p=new JSONObject(Crypto.decrypt(c.getString(1)));
                if(!c.getString(0).equals(p.optString("id")))throw new IllegalStateException("queue item ID mismatch");
                a.put(p);ids.add(c.getString(0));
            }
        }
        return new Batch(a,ids);
    }
    synchronized void ack(List<String> ids){
        if(ids.isEmpty())return;
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{for(String id:ids)db.execSQL("DELETE FROM pending WHERE id=?",new Object[]{id});db.setTransactionSuccessful();}
        finally{db.endTransaction();}
    }
    synchronized void clear(){getWritableDatabase().delete("pending",null,null);}
    static final class Batch {
        final JSONArray points; final List<String> ids;
        Batch(JSONArray points,List<String> ids){this.points=points;this.ids=ids;}
    }
}
