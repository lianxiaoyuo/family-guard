package com.familyguard.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Durable encrypted SOS queue, preserving a stable ID across network retries. */
final class SosOutbox {
    private static final String KEY="sos_outbox_enc";
    private static final int MAX_PENDING=32;
    static final class Entry {
        final String id;
        final long time;
        Entry(String id,long time){this.id=id;this.time=time;}
    }
    private SosOutbox(){}
    private static JSONArray load(Context c) throws Exception {
        String encrypted=Prefs.get(c,KEY);
        return encrypted.isEmpty()?new JSONArray():new JSONArray(Crypto.decrypt(encrypted));
    }
    private static void commit(Context c,JSONArray entries) throws Exception {
        String encrypted=Crypto.encrypt(entries.toString());
        if(!Prefs.store(c).edit().putString(KEY,encrypted).commit())
            throw new IllegalStateException("failed to persist SOS outbox");
    }
    static synchronized Entry enqueue(Context c) throws Exception {
        JSONArray items=load(c);
        if(items.length()>=MAX_PENDING)throw new IllegalStateException("SOS queue is full; check network");
        Entry e=new Entry(UUID.randomUUID().toString(),System.currentTimeMillis());
        items.put(new JSONObject().put("id",e.id).put("time",e.time));
        commit(c,items);
        return e;
    }
    static synchronized Entry oldest(Context c) throws Exception {
        JSONArray items=load(c);
        if(items.length()==0)return null;
        JSONObject o=items.getJSONObject(0);
        return new Entry(o.getString("id"),o.getLong("time"));
    }
    static synchronized void ack(Context c,String id) throws Exception {
        JSONArray items=load(c),remaining=new JSONArray();
        for(int i=0;i<items.length();i++){
            JSONObject entry=items.getJSONObject(i);
            if(!id.equals(entry.optString("id")))remaining.put(entry);
        }
        if(remaining.length()!=items.length())commit(c,remaining);
    }
    static synchronized void clear(Context c){Prefs.store(c).edit().remove(KEY).commit();}
}
