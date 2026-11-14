package com.familyguard.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.*;
import android.os.*;
import android.util.Log;
import com.amap.api.location.AMapLocation;
import com.amap.api.maps.CoordinateConverter;
import com.amap.api.maps.model.LatLng;
import org.json.JSONObject;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** User-controlled foreground location sharing, raw multi-band phone GNSS + optional AMap fusion. */
public class TrackingService extends Service {
    static final String START="com.familyguard.START", STOP="com.familyguard.STOP", SOS="com.familyguard.SOS";
    private static final String CHANNEL="location_sharing";
    private static final String MEDIA_CHANNEL="guardian_media_requests";
    private static final int NOTIFY_ID=3001, MEDIA_NOTIFY_ID=3003;
    // Separate disk and network executors: a slow HTTP request cannot block GNSS persistence.
    private final ExecutorService storageIo=Executors.newSingleThreadExecutor();
    private final ExecutorService uploadIo=Executors.newSingleThreadExecutor();
    // An emergency signal must not wait behind a slow GNSS upload.
    private final ExecutorService emergencyIo=Executors.newSingleThreadExecutor();
    private final ExecutorService mediaIo=Executors.newSingleThreadExecutor();
    private final AtomicBoolean checkingMedia=new AtomicBoolean(false);
    private final AtomicBoolean sendingSOS=new AtomicBoolean(false);
    private String lastMediaRequestId="";
    private long lastMediaCheck=0L;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean uploading=new AtomicBoolean(false);
    private final AtomicBoolean saving=new AtomicBoolean(false);
    private final AtomicReference<PositionSnapshot> pendingPosition=new AtomicReference<>();

    /** Coalesces a high-rate GNSS stream to the newest unsaved point, even on slow networks. */
    private static final class PositionSnapshot {
        final String id, provider, coordType;
        final double latitude, longitude;
        final float accuracy, speed;
        final long time;
        PositionSnapshot(Location fix, String type, long time) {
            this.id=UUID.randomUUID().toString();
            this.provider=fix.getProvider(); this.coordType=type;
            this.latitude=fix.getLatitude();this.longitude=fix.getLongitude();
            this.accuracy=fix.getAccuracy();this.speed=fix.hasSpeed()?Math.max(0,fix.getSpeed()):0f;
            this.time=time;
        }
    }
    private final AmapTracker amap=new AmapTracker();
    private LocationManager locations;
    private LocationQueue queue;
    private Location best;
    private String bestCoordType="wgs84";
    private long lastQueueAt=0,lastHeartbeat=0,lastNotificationAt=0;
    private volatile boolean closed=false;
    private boolean gpsRegistered=false,networkRegistered=false,fusedRegistered=false;
    private final GnssDiagnostics gnss=new GnssDiagnostics();
    private String display="正在搜索卫星与高精度定位";

    private final LocationListener gpsListener=new LocationListener(){
        @Override public void onLocationChanged(Location l){acceptFix(l,"wgs84");}
    };
    private final LocationListener networkListener=new LocationListener(){
        @Override public void onLocationChanged(Location l){acceptFix(l,"wgs84");}
    };
    private final LocationListener fusedListener=new LocationListener(){
        @Override public void onLocationChanged(Location l){acceptFix(l,"wgs84");}
    };
    private final Runnable tick=new Runnable(){@Override public void run(){
        if(closed||!Prefs.isSharing(TrackingService.this))return;
        try{subscribe();}catch(SecurityException|IllegalStateException e){
            Log.w("FamilyGuard","等待定位权限或系统定位开启",e);
        }
        flushAsync();
        flushSosAsync();
        checkMediaRequestAsync();
        main.postDelayed(this,3000L);
    }};

    @Override public void onCreate(){
        super.onCreate();
        queue=new LocationQueue(this);
        locations=(LocationManager)getSystemService(LOCATION_SERVICE);
        gnss.start(locations,main);
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"家庭位置共享",NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(MEDIA_CHANNEL,"监护媒体请求",NotificationManager.IMPORTANCE_DEFAULT));
    }
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,TrackingService.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent sos=PendingIntent.getService(this,2,new Intent(this,TrackingService.class).setAction(SOS),
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("正在共享实时位置")
                .setContentText(display)
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_dialog_alert,"SOS 求助",sos).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"停止共享",stop).build())
                .build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null && STOP.equals(intent.getAction())) {stopSharing(false);return START_NOT_STICKY;}
        boolean emergency=intent!=null && SOS.equals(intent.getAction());
        if(closed||!"child".equals(Prefs.role(this))||!Prefs.isSharing(this)||Prefs.token(this).isEmpty()){
            stopSelf();return START_NOT_STICKY;
        }
        try {
            if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFY_ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(NOTIFY_ID,notification());
            subscribe();
            main.removeCallbacks(tick);main.post(tick);
            if(emergency)sendSosFromNotification();
            return START_STICKY;
        }catch(SecurityException|IllegalArgumentException|IllegalStateException e){
            Log.e("FamilyGuard","定位服务启动失败",e);
            Prefs.setSharing(this,false);stopSelf();return START_NOT_STICKY;
        }
    }
    /** Location sharing must already be explicitly enabled. The SOS action is one tap from
     * the ongoing system notification; it never accesses the microphone/camera. */
    private void sendSosFromNotification(){
        try { SosOutbox.enqueue(this);flushSosAsync(); }
        catch(Exception e){Log.e("FamilyGuard","无法持久保存 SOS，请尝试电话求助",e);}
    }
    /** Crash-safe SOS outbox, retries every 3 seconds after network recovery. */
    private void flushSosAsync(){
        if(closed||!Prefs.isSharing(this)||!sendingSOS.compareAndSet(false,true))return;
        try { emergencyIo.execute(()->{
            try {
                SosOutbox.Entry entry=SosOutbox.oldest(this);
                if(entry==null)return;
                if(System.currentTimeMillis()-entry.time>7L*24*60*60*1000){
                    Log.e("FamilyGuard","SOS 超过服务器七天补报有效期，已标记为过期");
                    SosOutbox.ack(this,entry.id);
                    return;
                }
                Api.call(this,"POST","/v1/sos",new JSONObject()
                        .put("requestId",entry.id).put("occurredAt",entry.time),Prefs.token(this));
                SosOutbox.ack(this,entry.id);
                Log.i("FamilyGuard","SOS acknowledged by server");
            } catch(Api.ApiException e){
                if(e.revoked())main.post(()->stopSharing(true));
                else Log.w("FamilyGuard","SOS server error, retry later",e);
            } catch(Exception e){Log.w("FamilyGuard","SOS queued for retry",e);}
            finally {sendingSOS.set(false);}
        }); } catch(RuntimeException e){sendingSOS.set(false);Log.e("FamilyGuard","SOS executor stopped",e);}
    }
    /** Prompt via visible notification even if the child app UI is closed. Only
     * MainActivity, after an explicit tap, can launch microphone or camera. */
    private void checkMediaRequestAsync(){
        long now=SystemClock.elapsedRealtime();
        if(now-lastMediaCheck<9000 || !checkingMedia.compareAndSet(false,true))return;
        lastMediaCheck=now;
        try {
            mediaIo.execute(()->{
                try{
                    JSONObject r=Api.call(this,"GET","/v1/media/pending",null,Prefs.token(this));
                    JSONObject item=r.optJSONObject("request");
                    if(item==null)return;
                    String id=item.optString("id"), kind=item.optString("kind");
                    if(id.isEmpty()||id.equals(lastMediaRequestId))return;
                    String label="photo".equals(kind)?"拍照":"audio".equals(kind)?"录音":"录像";
                    lastMediaRequestId=id;
                    PendingIntent open=PendingIntent.getActivity(this,3,new Intent(this,MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                    Notification n=new Notification.Builder(this,MEDIA_CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("家长请求"+label)
                        .setContentText("打开应用确认。不会自动开启摄像头或麦克风。")
                        .setContentIntent(open).setAutoCancel(true).build();
                    ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(MEDIA_NOTIFY_ID,n);
                } catch(Exception e) {Log.w("FamilyGuard","媒体请求检查失败",e);}
                finally{checkingMedia.set(false);}
            });
        } catch(RuntimeException e){checkingMedia.set(false);}
    }
    private void subscribe(){
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("需要精确定位授权");
        if(locations==null)throw new IllegalStateException("系统 LocationManager 不可用");
        boolean gps=enabled(LocationManager.GPS_PROVIDER);
        boolean network=enabled(LocationManager.NETWORK_PROVIDER);
        // Android 12+ can expose system fused GNSS/Wi-Fi/cellular directly, with no
        // Google Play Services dependency and no AMap Key. May be absent on some ROMs.
        boolean fused=Build.VERSION.SDK_INT>=31 && enabled(LocationManager.FUSED_PROVIDER);
        if(!gps&&gpsRegistered){locations.removeUpdates(gpsListener);gpsRegistered=false;}
        if(!network&&networkRegistered){locations.removeUpdates(networkListener);networkRegistered=false;}
        if(!fused&&fusedRegistered){locations.removeUpdates(fusedListener);fusedRegistered=false;}
        if(gps&&!gpsRegistered){
            try{
                requestFast(LocationManager.GPS_PROVIDER,1000L,gpsListener);
                gpsRegistered=true;
                useFreshCache(LocationManager.GPS_PROVIDER);
            }catch(IllegalArgumentException|SecurityException e){Log.w("FamilyGuard","原生 GNSS 暂不可用",e);}
        }
        if(fused&&!fusedRegistered){
            try{
                requestFast(LocationManager.FUSED_PROVIDER,1000L,fusedListener);
                fusedRegistered=true;
                useFreshCache(LocationManager.FUSED_PROVIDER);
            }catch(IllegalArgumentException|SecurityException e){Log.w("FamilyGuard","系统融合定位不可用",e);}
        }
        if(network&&!networkRegistered){
            try{
                requestFast(LocationManager.NETWORK_PROVIDER,2000L,networkListener);
                networkRegistered=true;
                useFreshCache(LocationManager.NETWORK_PROVIDER);
            }catch(IllegalArgumentException|SecurityException e){Log.w("FamilyGuard","系统网络定位不可用",e);}
        }
        if(AmapPrivacy.available()&&AmapPrivacy.agreed(this))amap.start(this,this::acceptAmap);
    }
    /** Explicit quality and no batching on Android 12+, compatibility fallback on older OS. */
    private void requestFast(String provider,long intervalMs,LocationListener listener){
        if(Build.VERSION.SDK_INT>=31){
            LocationRequest request=new LocationRequest.Builder(intervalMs)
                    .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateIntervalMillis(Math.min(500L,intervalMs))
                    .setMinUpdateDistanceMeters(0f)
                    .setMaxUpdateDelayMillis(0L).build();
            locations.requestLocationUpdates(provider,request,getMainExecutor(),listener);
        }else{
            locations.requestLocationUpdates(provider,intervalMs,0f,listener,Looper.getMainLooper());
        }
    }
    private boolean enabled(String provider){
        try{return locations.isProviderEnabled(provider);}
        catch(IllegalArgumentException|SecurityException e){return false;}
    }
    private void useFreshCache(String provider){
        if(best!=null)return;
        try {
            Location cached=locations.getLastKnownLocation(provider);
            if(cached!=null)acceptFix(cached,"wgs84"); // age and accuracy validated by acceptFix
        }catch(SecurityException|IllegalArgumentException e){Log.w("FamilyGuard","无法读取最近系统定位",e);}
    }
    private void acceptAmap(AMapLocation a){
        if(a==null||a.getErrorCode()!=0||!a.hasAccuracy())return;
        String coords=a.getCoordType();
        String type=AMapLocation.COORD_TYPE_WGS84.equals(coords)?"wgs84":
                AMapLocation.COORD_TYPE_GCJ02.equals(coords)?"gcj02":null;
        if(type==null)return; // Never guess a coordinate reference system.
        long ageMs=Math.max(0L,System.currentTimeMillis()-a.getTime());
        if(ageMs>AccuracyPolicy.MAX_FIX_AGE_MS)return;
        Location l=new Location("amap");
        l.setLatitude(a.getLatitude());l.setLongitude(a.getLongitude());
        l.setAccuracy(a.getAccuracy());l.setTime(a.getTime());
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos()-ageMs*1000000L);
        if(a.hasSpeed())l.setSpeed(a.getSpeed());
        if(Build.VERSION.SDK_INT>=31 && a.isMock())return;
        acceptFix(l,type);
    }
    private void acceptFix(Location point,String coordType){
        if(closed||!Prefs.isSharing(this)||point==null||!point.hasAccuracy())return;
        if(Build.VERSION.SDK_INT>=31?point.isMock():point.isFromMockProvider())return;
        long fixMs=point.getElapsedRealtimeNanos()/1000000L;
        long ageMs=SystemClock.elapsedRealtime()-fixMs;
        if(!AccuracyPolicy.valid(point.getLatitude(),point.getLongitude(),point.getAccuracy(),ageMs))return;
        if(!"wgs84".equals(coordType)&&!"gcj02".equals(coordType))return;
        if(best!=null){
            long prior=best.getElapsedRealtimeNanos()/1000000L;
            if(!AccuracyPolicy.accept(point.getProvider(),point.getAccuracy(),fixMs,
                    best.getProvider(),best.getAccuracy(),prior))return;
            // Normalize cross-provider WGS84/GCJ-02 coordinates before jump detection.
            // Never treat the raw difference in the two coordinate systems as real travel.
            if(fixMs>prior&&fixMs-prior<12000L){
                float distance=comparableDistance(best,bestCoordType,point,coordType);
                // Avoid accepting a one-second GPS multipath teleport as real movement.
                if(Float.isFinite(distance)&&!AccuracyPolicy.plausible(distance,
                        point.getAccuracy(),best.getAccuracy(),fixMs-prior,
                        point.hasSpeed()?point.getSpeed():0f,
                        best.hasSpeed()?best.getSpeed():0f))return;
            }
        }
        best=new Location(point);bestCoordType=coordType;
        display=String.format(Locale.CHINA,"%s · 约 ±%.1f 米",
                "amap".equals(point.getProvider())?"高德融合":
                LocationManager.GPS_PROVIDER.equals(point.getProvider())?"GNSS":
                "fused".equals(point.getProvider())?"系统融合":"网络备用",point.getAccuracy());
        long notificationNow=SystemClock.elapsedRealtime();
        if(notificationNow-lastNotificationAt>=3000L){
            lastNotificationAt=notificationNow;
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFY_ID,notification());
        }
        long now=SystemClock.elapsedRealtime();
        if(now-lastQueueAt<AccuracyPolicy.SEND_EVERY_MS)return;
        lastQueueAt=now;
        final long fixTime=System.currentTimeMillis()-ageMs;
        pendingPosition.set(new PositionSnapshot(point,coordType,fixTime));
        scheduleSave();
    }
    private void scheduleSave(){
        if(closed||!saving.compareAndSet(false,true))return;
        try{storageIo.execute(()->{
            try{
                // The newest snapshot goes to bounded encrypted SQLite; no unbounded executor
                // backlog while one HTTP upload is waiting for network timeouts.
                PositionSnapshot p=pendingPosition.getAndSet(null);
                if(p!=null&&Prefs.isSharing(this)){
                    queue.add(p.id,p.latitude,p.longitude,p.accuracy,p.speed,
                            p.time,p.provider,p.coordType);
                }
            }catch(Exception e){Log.e("FamilyGuard","最新位置持久化失败",e);}
            finally{
                saving.set(false);
                if(pendingPosition.get()!=null&&!closed)scheduleSave();
                flushAsync();
            }
        });}catch(RuntimeException e){saving.set(false);Log.w("FamilyGuard","定位缓存线程已停止",e);}
    }
    /** Compute distance only in one coordinate reference system; skip checks if conversion fails. */
    private float comparableDistance(Location old,String oldType,Location current,String currentType){
        if(oldType.equals(currentType))return old.distanceTo(current);
        if(!AmapPrivacy.available()||!AmapPrivacy.agreed(this))return Float.NaN;
        try {
            Location raw="wgs84".equals(oldType)?old:current;
            Location fused="gcj02".equals(oldType)?old:current;
            CoordinateConverter converter=new CoordinateConverter(this);
            converter.from(CoordinateConverter.CoordType.GPS);
            converter.coord(new LatLng(raw.getLatitude(),raw.getLongitude()));
            LatLng converted=converter.convert();
            if(converted==null||!Double.isFinite(converted.latitude)||!Double.isFinite(converted.longitude))return Float.NaN;
            Location convertedLocation=new Location("converted-gcj02");
            convertedLocation.setLatitude(converted.latitude);
            convertedLocation.setLongitude(converted.longitude);
            return convertedLocation.distanceTo(fused);
        }catch(RuntimeException|LinkageError ex){
            Log.w("FamilyGuard","无法转换混合来源坐标，跳过本次空间跳变检查",ex);
            return Float.NaN;
        }
    }
    private int battery(){
        Intent b=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(b==null)return -1;
        int level=b.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=b.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
        return level>=0&&scale>0?(int)(level*100f/scale):-1;
    }
    private void flushAsync(){
        if(closed||!uploading.compareAndSet(false,true))return;
        try{uploadIo.execute(()->{
            try {
                if(!Prefs.isSharing(this))return;
                LocationQueue.Batch batch=queue.next();
                if(!batch.ids.isEmpty()){
                    // Publish the newest point first, so an offline history backlog does not
                    // delay the parent's live position by many upload cycles.
                    JSONObject newest=queue.latest();
                    if(newest!=null && System.currentTimeMillis()-newest.optLong("deviceTime",0)<=60000L){
                        int used=gnss.usedInFix();
                        if("gps".equals(newest.optString("provider")) && used>0){
                            newest.put("satellitesUsed",used);
                            newest.put("cn0DbHz",gnss.averageCn0());
                        }
                        Api.call(this,"POST","/v1/position",new JSONObject()
                                .put("point",newest).put("battery",battery()),Prefs.token(this));
                    }
                    // Preserve the actual track with original timestamps and point IDs.
                    // The server only evaluates real-time fence events from fresh points.
                    Api.call(this,"POST","/v1/points",new JSONObject()
                            .put("points",batch.points).put("battery",battery()),Prefs.token(this));
                    queue.ack(batch.ids); // only after server successfully confirms durable receipt
                    lastHeartbeat=System.currentTimeMillis();
                }else if(System.currentTimeMillis()-lastHeartbeat>30000L){
                    Api.call(this,"POST","/v1/heartbeat",new JSONObject().put("battery",battery()),Prefs.token(this));
                    lastHeartbeat=System.currentTimeMillis();
                }
            }catch(Api.ApiException e){
                if(e.revoked())main.post(()->stopSharing(true));
                else Log.w("FamilyGuard","上传被拒绝："+e.getMessage());
            }catch(Exception e){Log.w("FamilyGuard","网络暂不可用，保留最新坐标："+e.getMessage());}
            finally{uploading.set(false);}
        });}catch(RuntimeException e){uploading.set(false);}
    }
    private void stopSharing(boolean revoked){
        if(closed)return;
        Prefs.setSharing(this,false);
        pendingPosition.set(null);
        if(revoked){SosOutbox.clear(this);Prefs.clearIdentity(this);}
        try{storageIo.execute(()->{try{queue.clear();}catch(Exception e){Log.e("FamilyGuard","清除缓存失败",e);}});}
        catch(RuntimeException e){Log.w("FamilyGuard","无法清理缓存",e);}
        stopSelf();
    }
    @Override public void onDestroy(){
        closed=true;main.removeCallbacks(tick);
        amap.close();
        if(locations!=null){
            if(gpsRegistered)locations.removeUpdates(gpsListener);
            if(networkRegistered)locations.removeUpdates(networkListener);
            if(fusedRegistered)locations.removeUpdates(fusedListener);
        }
        gnss.stop(locations);
        // The database close is queued after all pending writes, without waiting on HTTP.
        try{storageIo.execute(()->queue.close());}catch(RuntimeException ignored){}
        storageIo.shutdown();
        uploadIo.shutdownNow();
        emergencyIo.shutdown();
        mediaIo.shutdownNow();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
