package com.familyguard.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.provider.MediaStore;
import android.media.MediaRecorder;
import androidx.core.content.FileProvider;
import java.io.*;
import android.view.*;
import android.widget.*;
import com.amap.api.maps.AMap;
import com.amap.api.maps.MapView;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.Circle;
import com.amap.api.maps.model.CircleOptions;
import com.amap.api.maps.model.Polyline;
import com.amap.api.maps.model.PolylineOptions;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout body;
    private EditText serverField,setupTokenField,codeField,nameField,recoveryField;
    private TextView parentStatus, childStatus, pairText, guardStatus, mediaStatus;
    private EditText fenceLatField,fenceLonField,fenceRadiusField;
    private String shownMediaRequest="", activeMediaID="", activeMediaKind="", lastGuardEvent="";
    private File captureFile;
    private MediaRecorder audioRecorder;
    private long recordingStarted;
    private Button stopAudioButton;
    private boolean mediaPolling=false;
    private long lastGuardLoad=0;
    private final Runnable childMediaPoll=new Runnable(){@Override public void run(){
        if(started && "child".equals(Prefs.role(MainActivity.this))) {
            fetchPendingMedia();handler.postDelayed(this,6000);
        }
    }};

    private JSONObject latest;
    private String lastRenderedPointId="";
    private MapView mapView;
    private AMap map;
    private Marker marker;
    private Circle accuracyCircle;
    private final List<Polyline> routeLines=new ArrayList<>();
    private Bundle pendingMapState;
    private TextView historyStatus;
    private boolean mapCentered=false, mapReady=false;
    private boolean started=false,loading=false;
    private final Runnable poll=new Runnable(){@Override public void run(){
        if(started && "parent".equals(Prefs.role(MainActivity.this))) {
            fetchLatest();handler.postDelayed(this,AccuracyPolicy.PARENT_REFRESH_MS);
        }
    }};
    int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    @Override public void onCreate(Bundle b){super.onCreate(b);pendingMapState=b;getWindow().setStatusBarColor(Color.rgb(248,250,254));getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);render();}
    @Override protected void onResume(){super.onResume();started=true;if(mapView!=null)mapView.onResume();handler.removeCallbacks(poll);handler.post(poll);
        handler.removeCallbacks(childMediaPoll);handler.post(childMediaPoll);}
    @Override protected void onPause(){started=false;handler.removeCallbacks(poll);handler.removeCallbacks(childMediaPoll);if(mapView!=null)mapView.onPause();super.onPause();}
    @Override public void onLowMemory(){super.onLowMemory();if(mapView!=null)mapView.onLowMemory();}
    @Override protected void onDestroy(){network.shutdownNow();stopAudio(false);releaseMap();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle state){
        if(mapView!=null)mapView.onSaveInstanceState(state);
        super.onSaveInstanceState(state);
    }
    private void releaseMap(){
        clearRouteLines();
        if(mapView!=null){try{mapView.onPause();mapView.onDestroy();}catch(Exception ignored){}mapView=null;}
        map=null;marker=null;accuracyCircle=null;mapCentered=false;mapReady=false;lastRenderedPointId="";
    }
    private void clearRouteLines(){
        for(Polyline line:routeLines){try{line.remove();}catch(RuntimeException ignored){}}
        routeLines.clear();
    }
    private GradientDrawable bg(int color,int stroke){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(14));if(stroke!=0)d.setStroke(dp(1),stroke);return d;
    }
    private void add(View v){body.addView(v);}
    private TextView text(String value,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(null,Typeface.BOLD);
        t.setPadding(0,dp(4),0,dp(6));return t;
    }
    private void line(String value){add(text(value,14,Color.rgb(91,104,127),false));}
    private void title(String s){TextView t=text(s,22,Color.rgb(22,34,65),true);t.setPadding(0,dp(16),0,dp(14));add(t);}
    private Button btn(String label,boolean primary,Runnable cb){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(15);b.setTypeface(null,Typeface.BOLD);
        b.setTextColor(primary?Color.WHITE:Color.rgb(36,81,173));b.setBackground(bg(primary?Color.rgb(37,105,240):Color.rgb(233,240,254),0));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.bottomMargin=dp(10);b.setLayoutParams(p);
        b.setOnClickListener(v->cb.run());add(b);return b;
    }
    private EditText input(String hint,String value){
        EditText x=new EditText(this);x.setSingleLine(true);x.setTextSize(15);x.setTextColor(Color.rgb(36,45,65));x.setHint(hint);x.setText(value);
        x.setPadding(dp(14),0,dp(14),0);x.setBackground(bg(Color.WHITE,Color.rgb(221,228,238)));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(50));lp.bottomMargin=dp(12);x.setLayoutParams(lp);add(x);return x;
    }
    private void show(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private void render(){
        guardStatus=null;mediaStatus=null;fenceLatField=null;fenceLonField=null;fenceRadiusField=null;
        releaseMap();
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(248,250,254));
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(14),dp(20),dp(40));scroll.addView(body);setContentView(scroll);
        add(text("家庭守护  /  Family Guard",14,Color.rgb(37,105,240),true));
        if(Prefs.role(this).isEmpty())setup();
        else if("parent".equals(Prefs.role(this)))parent();
        else child();
    }
    private void setup(){
        title("家庭安全守护 · V6");
        line("首次配对需明确启用持续位置共享；之后位置与电子围栏自动工作。远程录音/拍摄请求需要本机确认，不能通过安装来代替系统许可。");
        title("第一步 · 配置服务器");
        serverField=input("https://你的域名（必须 HTTPS）",Prefs.get(this,"server"));
        setupTokenField=input("首次部署密钥（仅创建家庭时使用）", "");
        setupTokenField.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        line("首次创建家庭时，输入服务器部署脚本生成的一次性初始化密钥；不会储存在手机上。已创建家庭的设备不再需要它。");
        line(BuildConfig.DEBUG?"Debug APK 可用局域网 HTTP 测试；正式版必须 HTTPS。":"正式版本仅支持 HTTPS 服务器。");
        title("第二步 · 选择当前手机角色");
        btn("我是家长 · 创建家庭并获取配对码",true,()->{
            if(!saveServer())return;
            final String initialSetupKey=setupTokenField.getText().toString().trim();
            request(()->Api.call(this,"POST","/v1/families",new JSONObject(),initialSetupKey),r->{
                Prefs.setIdentity(this,"parent",r.getString("token"));render();
                saveDisplayedPairCode(r.optString("pairCode"));
                showRecoveryKey(r.getString("recoveryKey"));
            });
        });
        line("孩子端请先从家长端获取配对码。");
        codeField=input("输入家长提供的 8 位配对码","");
        nameField=input("孩子昵称（例如：小宝）","");
        title("已有家庭 · 恢复家长访问");
        line("仅使用创建家庭或上次恢复时保存的 64 位恢复密钥。恢复后旧家长令牌和旧恢复密钥立即失效。");
        recoveryField=input("家长恢复密钥（64 位）","");
        recoveryField.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        btn("恢复家长账号",false,()->{
            if(!saveServer())return;
            String key=recoveryField.getText().toString().trim();
            if(!key.matches("[0-9a-fA-F]{64}")){show("请输入完整的 64 位恢复密钥");return;}
            request(()->Api.call(this,"POST","/v1/recover",new JSONObject().put("recoveryKey",key),null),r->{
                Prefs.setIdentity(this,"parent",r.getString("token"));render();showRecoveryKey(r.getString("recoveryKey"));
            });
        });
        btn("我是孩子 · 授权配对",false,()->{
            if(!saveServer())return;
            String code=codeField.getText().toString().trim().toUpperCase(Locale.ROOT),name=nameField.getText().toString().trim();
            if(code.length()!=8||name.isEmpty()){show("请输入 8 位配对码和昵称");return;}
            request(()->Api.call(this,"POST","/v1/join",new JSONObject().put("code",code).put("name",name),null),r->{
                Prefs.setIdentity(this,"child",r.getString("token"));render();
            });
        });
    }
    private void saveDisplayedPairCode(String code){
        long until=System.currentTimeMillis()+600000L;
        Prefs.store(this).edit().putString("pair_code",code).putLong("pair_expiry",until).apply();
        if(pairText!=null)pairText.setText("配对码："+code+"（10 分钟有效）");
    }
    private String displayedPairState(){
        String code=Prefs.get(this,"pair_code");
        if(!code.isEmpty()&&System.currentTimeMillis()<Prefs.store(this).getLong("pair_expiry",0))
            return "配对码："+code+"（有效期内）";
        return "尚未绑定孩子，请生成配对码";
    }
    private void showRecoveryKey(String key){
        TextView field=new TextView(this);field.setText(key);field.setTextIsSelectable(true);field.setTextSize(15);
        field.setPadding(dp(18),dp(14),dp(18),dp(14));
        new AlertDialog.Builder(this).setTitle("请妥善保存一次性恢复密钥")
                .setMessage("密钥只展示这一次。它可重置家长账号访问权，任何获得密钥的人都可能接管家庭定位。请单独安全保存，不要分享给其他人。")
                .setView(field).setCancelable(false)
                .setPositiveButton("已安全保存",(d,w)->{})
                .setNeutralButton("复制",(d,w)->{
                    android.content.ClipboardManager cb=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    cb.setPrimaryClip(ClipData.newPlainText("FamilyGuard recovery key",key));
                    show("已复制，请粘贴到受保护的位置并清除剪贴板");
                }).show();
    }
    private boolean saveServer(){
        String s=serverField.getText().toString().trim().replaceAll("/+$","");
        if(!validateServer(s))return false;
        Prefs.put(this,"server",s);return true;
    }
    private boolean validateServer(String s){
        try { ServerAddress.normalize(s, BuildConfig.DEBUG); return true; }
        catch (IllegalArgumentException ex) { show(ex.getMessage()); return false; }
    }
    private void editServer(){
        EditText field=new EditText(this);field.setSingleLine(true);field.setText(Prefs.get(this,"server"));
        field.setSelectAllOnFocus(true);field.setPadding(dp(20),dp(12),dp(20),dp(12));
        new AlertDialog.Builder(this).setTitle("服务器地址")
            .setMessage("修改地址不会迁移家庭数据。只有迁移原服务器数据后，原配对凭证才可继续使用。")
            .setView(field).setNegativeButton("取消",null)
            .setPositiveButton("保存",(d,w)->{String s=field.getText().toString().trim().replaceAll("/+$","");
                if(validateServer(s)){Prefs.put(this,"server",s);loading=false;render();}
            }).show();
    }
    private interface Job {JSONObject execute() throws Exception;}
    private interface Done {void accept(JSONObject r) throws Exception;}
    private void request(Job job,Done done){
        network.execute(()->{
            try{JSONObject r=job.execute();runOnUiThread(()->{
                try{done.accept(r);}catch(Exception ex){show("解析失败："+ex.getMessage());}
            });}catch(Exception ex){runOnUiThread(()->show("请求失败："+ex.getMessage()));}
        });
    }
    private void askAmapConsent(Runnable accepted){askAmapConsent(accepted,null);}
    private void askAmapConsent(Runnable accepted,Runnable declined){
        if(!AmapPrivacy.available()){show("还没有配置高德 Android Key，当前可以使用原生卫星定位");return;}
        if(AmapPrivacy.agreed(this)){accepted.run();return;}
        new AlertDialog.Builder(this)
          .setTitle("启用高德地图与融合定位")
          .setMessage("高德地图 SDK 会为地图展示与定位处理坐标、Wi-Fi/基站信息、网络及设备相关信息。详情：https://lbs.amap.com/pages/privacy/。不同意仍可使用原生 GNSS，但不能使用内嵌高德地图。")
          .setNegativeButton("不同意",(d,w)->{
              if(declined!=null)declined.run();
              else show("未启用高德 SDK，仍可使用原生 GNSS");
          })
          .setPositiveButton("同意并启用",(d,w)->{
              AmapPrivacy.setAgreed(this,true);
              Prefs.store(this).edit().remove("amap_privacy_declined").apply();
              accepted.run();
          }).show();
    }
    /** Parent role uses the same AMap Android-platform Key as child-side AMap fusion location. */
    private void installLiveMap(){
        if(!AmapPrivacy.available()){
            line("尚未配置高德 Android Key，家长端地图暂不可用；孩子端仍可使用原生 GNSS。");
            return;
        }
        if(!AmapPrivacy.agreed(this)){
            line("首次显示高德地图前，需要阅读并同意高德 SDK 隐私政策。");
            btn("同意高德隐私政策并加载地图",false,()->askAmapConsent(()->{
                try{AmapPrivacy.initialize(this);render();}
                catch(RuntimeException ex){show("高德隐私初始化失败："+ex.getMessage());}
            }));
            return;
        }
        try{
            // Both MapsInitializer and AMapLocationClient privacy interfaces must be updated
            // before creating the map or location client.
            AmapPrivacy.initialize(this);
            mapView=new MapView(this);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(390));
            lp.bottomMargin=dp(12);
            mapView.setLayoutParams(lp);
            body.addView(mapView);
            Bundle state=pendingMapState;pendingMapState=null;
            mapView.onCreate(state);
            if(started)mapView.onResume();
            map=mapView.getMap();
            map.setMapType(AMap.MAP_TYPE_NORMAL);
            map.setTrafficEnabled(false);
            map.setOnMapLoadedListener(()->android.util.Log.i("FamilyGuard.AMap","AMap map tiles loaded"));
            map.getUiSettings().setZoomControlsEnabled(true);
            map.getUiSettings().setCompassEnabled(true);
            map.getUiSettings().setScaleControlsEnabled(true);
            mapReady=true;
            if(latest!=null){showLatestOnMap(latest,0);lastRenderedPointId=latest.optString("id");}
        }catch(Exception|LinkageError ex){
            android.util.Log.e("FamilyGuard","AMap map initialization failed",ex);
            releaseMap();
            line("高德地图初始化失败；仍可查看经纬度并打开外部地图。请检查 Android Key、包名、SHA1、网络和 SDK 日志。");
        }
    }
    /** AMap map coordinates are GCJ-02. Convert native GNSS WGS-84 points before rendering. */
    private LatLng toAmapPoint(double lat,double lon,String coordType){
        if("wgs84".equals(coordType)){
            double[] gcj=CoordinateReference.wgsToGcj(lat,lon);
            return new LatLng(gcj[0],gcj[1]);
        }
        return new LatLng(lat,lon);
    }
    private void showLatestOnMap(JSONObject p,long ageMs){
        if(map==null||!mapReady||p==null)return;
        double lat=p.optDouble("lat",Double.NaN),lon=p.optDouble("lon",Double.NaN);
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)return;
        String type=p.optString("coordType","wgs84");
        if(!"gcj02".equals(type)&&!"wgs84".equals(type))return;
        LatLng point=toAmapPoint(lat,lon,type);
        if(marker==null)marker=map.addMarker(new MarkerOptions().position(point).title("孩子最新位置").snippet("定位来源："+p.optString("provider","未知")));
        else{marker.setPosition(point);marker.setSnippet("定位来源："+p.optString("provider","未知"));}
        double radius=Math.min(3000d,Math.max(1d,p.optDouble("accuracy",100d)));
        if(accuracyCircle==null){
            accuracyCircle=map.addCircle(new CircleOptions().center(point).radius(radius)
                    .strokeWidth(2f).strokeColor(Color.rgb(40,116,240))
                    .fillColor(Color.argb(38,40,116,240)).zIndex(5f));
        }else{accuracyCircle.setCenter(point);accuracyCircle.setRadius(radius);}
        if(!mapCentered){map.moveCamera(CameraUpdateFactory.newLatLngZoom(point,17f));mapCentered=true;}
        else map.animateCamera(CameraUpdateFactory.newLatLng(point));
    }
    private void addHistorySegment(List<LatLng> segment){
        if(map==null||segment==null||segment.size()<2)return;
        routeLines.add(map.addPolyline(new PolylineOptions().addAll(segment).width(6f)
                .color(Color.rgb(5,153,122)).geodesic(true).zIndex(4f)));
    }
    private void parent(){
        title("实时定位 · 家长端");
        line("服务器："+Prefs.get(this,"server"));
        pairText=text("正在连接…",16,Color.rgb(38,62,103),true);add(pairText);
        installLiveMap();
        line("家长端地图和孩子端融合定位均通过高德 Android SDK 提供；地图显示坐标统一转换为高德 GCJ-02 坐标系。实际加载状态以 SDK 回调为准。");
        parentStatus=text("正在等待最新位置…",16,Color.rgb(32,51,83),true);add(parentStatus);
        btn("在高德查看当前坐标",false,this::openMap);
        title("轨迹回放");
        line("手动加载历史轨迹；绿色线条显示经系统采集并成功上传的坐标，不表示手机离线时有实时数据。");
        historyStatus=text("尚未加载历史轨迹",14,Color.rgb(40,64,90),false);add(historyStatus);
        btn("查看最近 1 小时轨迹",false,()->loadHistory(1));
        btn("查看最近 6 小时轨迹",false,()->loadHistory(6));
        btn("查看最近 24 小时轨迹",false,()->loadHistory(24));
        btn("修改服务器地址",false,this::editServer);
        btn("生成孩子端配对码",false,()->request(()->Api.call(this,"POST","/v1/pair",new JSONObject(),Prefs.token(this)),r->saveDisplayedPairCode(r.optString("pairCode"))));
        title("电子围栏 / SOS / 媒体请求");
        line("围栏使用 WGS84 经纬度。请输入学校、家等安全区域中心；以当前孩子位置作为中心也可。进出提醒需连续可信坐标确认。") ;
        fenceLatField=input("中心纬度，例如 39.912","");
        fenceLonField=input("中心经度，例如 116.397","");
        fenceRadiusField=input("围栏半径（米，80–2000）","150");
        btn("使用孩子最新位置作为围栏中心",false,()->{
            if(latest==null){show("尚无最新位置");return;}
            double lat=latest.optDouble("lat",Double.NaN),lon=latest.optDouble("lon",Double.NaN);
            if("gcj02".equals(latest.optString("coordType"))){double[] v=CoordinateReference.gcjToWgs(lat,lon);lat=v[0];lon=v[1];}
            fenceLatField.setText(String.format(Locale.US,"%.7f",lat));
            fenceLonField.setText(String.format(Locale.US,"%.7f",lon));
        });
        btn("设置电子围栏",true,()->configureFence(true));
        btn("关闭电子围栏",false,()->configureFence(false));
        guardStatus=text("等待电子围栏和求助信息…",14,Color.rgb(40,64,90),false);add(guardStatus);
        line("监护请求只会通知孩子确认，不会静默开启摄像头或麦克风。媒体默认在服务器保留最长 24 小时。") ;
        btn("请求孩子拍照",false,()->askMedia("photo"));
        btn("请求孩子录音",false,()->askMedia("audio"));
        btn("请求孩子录像（最长 30 秒）",false,()->askMedia("video"));
        mediaStatus=text("尚无媒体",14,Color.rgb(40,64,90),false);add(mediaStatus);
        btn("查看最新照片 / 播放音频视频",false,this::downloadLatestMedia);
        fetchLatest();loadGuard();loadMediaList();
    }
    private void fetchLatest(){
        if(!"parent".equals(Prefs.role(this))||loading)return;
        loading=true;
        network.execute(()->{
            try {JSONObject result=Api.call(this,"GET","/v1/latest",null,Prefs.token(this));
                runOnUiThread(()->{
                    latest=result.optJSONObject("point");
                    long serverNow=result.optLong("serverTime",System.currentTimeMillis());
                    if(latest==null){
                        if(marker!=null){marker.remove();marker=null;}
                        if(accuracyCircle!=null){accuracyCircle.remove();accuracyCircle=null;}
                        lastRenderedPointId="";mapCentered=false;
                    }
                    boolean paired=result.optBoolean("paired",false);
                    if(pairText!=null){
                        if(paired){Prefs.store(this).edit().remove("pair_code").remove("pair_expiry").apply();}
                        pairText.setText(paired?"已绑定："+result.optString("childName"):displayedPairState());
                    }
                    if(parentStatus!=null){
                        long seen=result.optLong("lastSeen",0),age=Math.max(0,serverNow-seen);
                        String state=seen==0?"尚未收到设备心跳":age<150000?"网络在线（最近 150 秒有心跳）":"网络离线（最近没有心跳）";
                        if(latest==null)parentStatus.setText("网络状态："+state+"\n暂无位置记录");
                        else {
                            long ageOfFix=Math.max(0,Math.max(serverNow-latest.optLong("receivedAt",serverNow),
                                    serverNow-latest.optLong("deviceTime",0)));
                            String freshness=ageOfFix<=10000?"实时（约 "+(ageOfFix/1000)+" 秒延迟）":ageOfFix<=30000?"延迟（约 "+(ageOfFix/1000)+" 秒）":"已过期（约 "+(ageOfFix/1000)+" 秒前），请勿视为实时";
                            double err=latest.optDouble("accuracy",9999);
                            String quality=err<=5?"优秀":err<=15?"良好":err<=50?"一般":"较差 / 室内可能漂移";
                            String source="gps".equals(latest.optString("provider"))?"GNSS 卫星":
                                    "amap".equals(latest.optString("provider"))?"高德融合定位":
                                    "fused".equals(latest.optString("provider"))?"Android 系统融合":"网络备用 / 未知";
                            String gnssDetail=latest.optInt("satellitesUsed",0)>0
                                    ?"\n参与解算卫星："+latest.optInt("satellitesUsed")+" 颗，平均 C/N0："+
                                    String.format(Locale.US,"%.1f",latest.optDouble("cn0DbHz",0))+" dB-Hz（仅作诊断）":"";
                            parentStatus.setText("定位精度：约 ±"+String.format(Locale.CHINA,"%.1f",err)+" 米（"+quality+"）\n"
                                    +"来源："+source+gnssDetail+"\n位置状态："+freshness+"\n"
                                    +"最后更新："+time(latest.optLong("deviceTime"))+"\n"
                                    +"经纬度："+String.format(Locale.US,"%.7f, %.7f",latest.optDouble("lat"),latest.optDouble("lon"))
                                    +"\n连接："+state+"\n电量："+
                                    (result.optInt("battery",-1)>=0?result.optInt("battery")+"%":"未知")
                                    +"\n精度为系统估算的约 68% 置信半径，不是保证误差。");
                        }
                    }
                    if(latest!=null){
                        String id=latest.optString("id");
                        if(!id.equals(lastRenderedPointId)){
                            if(mapReady) {
                                try {
                                    showLatestOnMap(latest,Math.max(0,serverNow-latest.optLong("receivedAt",serverNow)));
                                    lastRenderedPointId=id;
                                } catch (RuntimeException ex) {
                                    android.util.Log.w("FamilyGuard", "Map render error; coordinate text remains available", ex);
                                }
                            }
                        }
                    }
                    loading=false;
                    if(System.currentTimeMillis()-lastGuardLoad>7000){lastGuardLoad=System.currentTimeMillis();loadGuard();loadMediaList();}
                });
            }catch(Exception ex){runOnUiThread(()->{loading=false;if(parentStatus!=null)parentStatus.setText("无法连接服务器："+ex.getMessage());});}
        });
    }
    private void loadHistory(int hours){
        request(()->Api.call(this,"GET","/v1/history?hours="+hours,null,Prefs.token(this)),r->{
            JSONArray items=r.optJSONArray("points");
            if(items==null)items=new JSONArray();
            if(historyStatus!=null)historyStatus.setText("最近 "+hours+" 小时历史坐标："+items.length()+" 个采样点");
            if(map==null||!mapReady)return;
            clearRouteLines();
            try{
                List<LatLng> segment=new ArrayList<>();
                long lastTs=0;
                for(int i=0;i<items.length();i++){
                    JSONObject point=items.optJSONObject(i);
                    if(point==null)continue;
                    double lat=point.optDouble("lat",Double.NaN),lon=point.optDouble("lon",Double.NaN);
                    if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)continue;
                    String coordType=point.optString("coordType","wgs84");
                    if(!"gcj02".equals(coordType)&&!"wgs84".equals(coordType))continue;
                    long ts=point.optLong("deviceTime",0);
                    if(lastTs>0&&ts-lastTs>600000L){addHistorySegment(segment);segment=new ArrayList<>();}
                    segment.add(toAmapPoint(lat,lon,coordType));
                    lastTs=ts;
                }
                addHistorySegment(segment);
                if(!segment.isEmpty()&&map!=null&&routeLines.isEmpty())show("历史点不足两个，暂时无法绘制轨迹线");
            }catch(RuntimeException ex){show("轨迹渲染失败，数据仍已载入："+ex.getMessage());}
        });
    }
    private String time(long millis){if(millis==0)return "未知";return new SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(new Date(millis));}
    private void openMap(){
        if(latest==null){show("还没有位置记录");return;}
        double lat=latest.optDouble("lat"),lon=latest.optDouble("lon");
        String coords="gcj02".equals(latest.optString("coordType"))?"gaode":"wgs84";
        String url=String.format(Locale.US,"https://uri.amap.com/marker?position=%.7f,%.7f&name=%%E5%%AE%%B6%%E5%%BA%%AD%%E5%%AE%%88%%E6%%8A%%A4&coordinate=%s&callnative=1",lon,lat,coords);
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
        catch(Exception ex){show("无法打开地图："+ex.getMessage());}
    }
    private void child(){
        title("孩子端 · 家庭位置共享");
        line("服务器："+Prefs.get(this,"server"));
        btn("修改服务器地址",false,this::editServer);
        line("使用 Android GNSS（GPS/北斗等，依设备支持）、系统融合/网络定位备用；配置并授权高德 Android Key 后，同时启用高德融合定位。位置来源与精度以设备实际回报为准。");
        childStatus=text("当前："+(Prefs.isSharing(this)?"已启动共享":"未共享位置"),16,Color.rgb(40,64,90),true);add(childStatus);
        btn("开始持续共享位置",true,this::beginTracking);
        if(AmapPrivacy.available()&&!AmapPrivacy.agreed(this)){
            btn("可选：启用高德融合定位",false,()->askAmapConsent(()->{
                if(Prefs.isSharing(this)){
                    try{ContextCompatShim.start(this);}catch(RuntimeException ex){show("高德启用后服务刷新失败："+ex.getMessage());}
                }
                show("高德融合定位已授权，原生 GNSS 仍然保留");
            }));
        }
        title("紧急求助 / 媒体确认");
        btn("SOS · 一键发送求助（无需再次确认）",true,this::sendSOS);
        btn("SOS + 自动启动可见录音（最长 30 秒）",true,()->{
            // Pressing this visible button explicitly requests the microphone.
            // The server never initiates it, and recording is not started from a background notification.
            if(!sendSOS())return;
            request(()->Api.call(this,"POST","/v1/media/self",
                new JSONObject().put("kind","audio"),Prefs.token(this)),r->beginMedia(r.getString("id"),"audio"));
        });
        line("首次使用时可授予必要权限；之后位置和电子围栏自动工作。录音与拍摄仍需打开孩子端确认，录像为最长 30 秒片段，并非后台隐蔽直播。") ;
        btn("检查是否有来自家长的媒体请求",false,this::fetchPendingMedia);
        title("主动发送安全信息");
        line("以下操作由持机人主动启动，录音与录像过程可见，不会在锁屏后台静默使用摄像头或麦克风。");
        btn("主动拍照并发送给家长",false,()->startSelfMedia("photo"));
        btn("主动录音 30 秒并发送",false,()->startSelfMedia("audio"));
        btn("主动录像 30 秒并发送",false,()->startSelfMedia("video"));
        btn("立即停止共享位置",false,()->{
            Prefs.setSharing(this,false);
            startService(new Intent(this,TrackingService.class).setAction(TrackingService.STOP));
            if(childStatus!=null)childStatus.setText("当前：已停止共享位置");show("已停止，未上传的缓存位置也会清除");
        });
        btn("打开后台定位 / 始终允许权限设置",false,()->{
            Intent i=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()));startActivity(i);
        });
        btn("检查系统定位开关",false,()->startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        line("重要：请在系统设置中选择“始终允许”位置权限，并允许自启动、关闭电池限制。Android 系统可能阻止部分后台恢复场景。");
        line("服务器保存最新位置和最近 30 天轨迹；断网时最多缓存 1200 个加密定位点，恢复网络后补传。你可以随时停止共享。");
    }
    private boolean sendSOS(){
        if(!Prefs.isSharing(this)){show("请先开启位置守护，再使用 SOS 功能");return false;}
        try{
            SosOutbox.enqueue(this);
            ContextCompatShim.start(this);
            show("SOS 已安全保存，联网后会自动重试发送；尚未确认家长收到。紧急时请同时拨打电话求救。");
            return true;
        }catch(Exception e){show("SOS 保存失败，请立即使用电话求助："+e.getMessage());return false;}
    }
    private void beginTracking(){
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED ||
           checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},200);return;
        }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},201);return;
        }
        if(AmapPrivacy.available()&&!AmapPrivacy.agreed(this)
                && !Prefs.store(this).getBoolean("amap_privacy_declined",false)){
            askAmapConsent(this::beginTracking,()->{
                // Refusing the third-party SDK must not disable Android's native GNSS.
                Prefs.store(this).edit().putBoolean("amap_privacy_declined",true).apply();
                beginTracking();
            });
            return;
        }
        Prefs.setSharing(this,true);
        try{ContextCompatShim.start(this);if(childStatus!=null)childStatus.setText("当前：已请求启动位置共享（通知栏可停止）");}
        catch(RuntimeException ex){Prefs.setSharing(this,false);show("启动失败："+ex.getMessage());}
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==201 && (results.length==0||results[0]!=PackageManager.PERMISSION_GRANTED)){
            show("请允许通知权限以显示明确的位置共享状态，未开始追踪");return;
        }
        if(requestCode==301){
            if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startAudio();
            else{activeMediaID="";activeMediaKind="";show("没有录音权限，录音未启动");}
            return;
        }
        if(requestCode==200||requestCode==201){
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED &&
               checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED)beginTracking();
            else show("未授予精确定位权限，无法开始共享");
        }
    }
    private void configureFence(boolean enabled){
        try{
            double lat=enabled?Double.parseDouble(fenceLatField.getText().toString().trim()):0;
            double lon=enabled?Double.parseDouble(fenceLonField.getText().toString().trim()):0;
            double radius=enabled?Double.parseDouble(fenceRadiusField.getText().toString().trim()):150;
            if(enabled && (!Double.isFinite(lat)||!Double.isFinite(lon)||lat<-90||lat>90||lon<-180||lon>180||radius<80||radius>2000)){
                show("请输入有效坐标，围栏半径 80–2000 米");return;
            }
            request(()->Api.call(this,"POST","/v1/fence",new JSONObject().put("enabled",enabled)
                        .put("lat",lat).put("lon",lon).put("radius",radius),Prefs.token(this)),r->{show(enabled?"电子围栏已开启":"电子围栏已关闭");loadGuard();});
        }catch(NumberFormatException ex){show("请输入数字经纬度和半径");}
    }
    private void loadGuard(){
        if(!started||!"parent".equals(Prefs.role(this)))return;
        request(()->Api.call(this,"GET","/v1/guard",null,Prefs.token(this)),r->{
            JSONObject f=r.optJSONObject("fence");
            org.json.JSONArray events=r.optJSONArray("events");
            StringBuilder b=new StringBuilder(f==null?"电子围栏：未开启":"电子围栏：已开启，半径 "+f.optInt("radius")+" 米");
            if(events!=null && events.length()>0){
                JSONObject last=events.optJSONObject(events.length()-1);
                if(last!=null){b.append("\n最新提醒：").append(last.optString("message")).append(" · ").append(time(last.optLong("time")));
                    String id=last.optString("id");
                    if(!lastGuardEvent.isEmpty()&&!id.equals(lastGuardEvent))show("家庭提醒："+last.optString("message"));
                    lastGuardEvent=id;
                }
            }
            if(guardStatus!=null)guardStatus.setText(b.toString());
        });
    }
    private void askMedia(String kind){
        String label="photo".equals(kind)?"拍照":"audio".equals(kind)?"录音":"录像";
        new AlertDialog.Builder(this).setTitle("请求孩子"+label+"？")
            .setMessage("孩子手机必须主动确认；不会秘密拍摄或录音。上传成功后家长可查看 24 小时。")
            .setNegativeButton("取消",null)
            .setPositiveButton("发送请求",(d,w)->request(()->Api.call(this,"POST","/v1/media/request",
                new JSONObject().put("kind",kind),Prefs.token(this)),r->{show("请求已发送，等待孩子确认");loadMediaList();})).show();
    }
    private void loadMediaList(){
        if(!started||!"parent".equals(Prefs.role(this)))return;
        request(()->Api.call(this,"GET","/v1/media/list",null,Prefs.token(this)),r->{
            org.json.JSONArray entries=r.optJSONArray("media");
            if(entries==null||entries.length()==0){if(mediaStatus!=null)mediaStatus.setText("尚无媒体请求");return;}
            JSONObject item=entries.optJSONObject(entries.length()-1);
            if(item!=null&&mediaStatus!=null)mediaStatus.setText("最新媒体："+item.optString("kind")+" / "+item.optString("status")+
                "，时间 "+time(item.optLong("createdAt")));
        });
    }
    private void downloadLatestMedia(){
        network.execute(()->{
            try{
                JSONObject r=Api.call(this,"GET","/v1/media/list",null,Prefs.token(this));
                org.json.JSONArray list=r.optJSONArray("media");JSONObject chosen=null;
                if(list!=null)for(int i=list.length()-1;i>=0;i--){JSONObject item=list.optJSONObject(i);
                    if(item!=null&&"ready".equals(item.optString("status"))){chosen=item;break;}}
                if(chosen==null){runOnUiThread(()->show("还没有孩子确认并上传的媒体"));return;}
                String id=chosen.getString("id"),mime=chosen.optString("mime");
                String ext="image/jpeg".equals(mime)?".jpg":"audio/mp4".equals(mime)?".m4a":"video/3gpp".equals(mime)?".3gp":".mp4";
                File folder=new File(getCacheDir(),"received");if(!folder.exists()&&!folder.mkdirs())throw new IOException("无法创建缓存目录");
                File file=new File(folder,id+ext);
                Api.download(this,id,file);
                runOnUiThread(()->{
                    try{
                        Uri uri=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",file);
                        Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(view,"查看孩子确认发送的媒体"));
                    }catch(Exception ex){show("没有适合播放该媒体的应用："+ex.getMessage());}
                });
            }catch(Exception ex){runOnUiThread(()->show("获取媒体失败："+ex.getMessage()));}
        });
    }
    private void fetchPendingMedia(){
        if(mediaPolling||!started||!"child".equals(Prefs.role(this)))return;
        mediaPolling=true;
        network.execute(()->{
            try{
                JSONObject r=Api.call(this,"GET","/v1/media/pending",null,Prefs.token(this));
                JSONObject item=r.optJSONObject("request");
                runOnUiThread(()->{mediaPolling=false;if(!started||item==null)return;
                    String id=item.optString("id");if(id.isEmpty()||id.equals(shownMediaRequest)||!activeMediaID.isEmpty())return;
                    shownMediaRequest=id;
                    String kind=item.optString("kind");
                    String label="photo".equals(kind)?"拍照":"audio".equals(kind)?"录音":"录像";
                    new AlertDialog.Builder(this).setTitle("家长请求"+label)
                      .setMessage("是否现在由你亲自启动"+label+"？未确认不会使用摄像头或麦克风。")
                      .setNegativeButton("拒绝",(d,w)->request(()->Api.call(this,"POST","/v1/media/reject",
                          new JSONObject().put("id",id),Prefs.token(this)),x->show("已拒绝媒体请求")))
                      .setPositiveButton("我同意，立即开启",(d,w)->beginMedia(id,kind)).show();
                });
            }catch(Exception ex){runOnUiThread(()->mediaPolling=false);}
        });
    }
    private void startSelfMedia(String kind){
        if(!"child".equals(Prefs.role(this))||!activeMediaID.isEmpty()){show("当前有未完成的采集，请先完成");return;}
        String label="photo".equals(kind)?"拍照":"audio".equals(kind)?"录音":"录像";
        new AlertDialog.Builder(this).setTitle("主动"+label+"并分享？")
          .setMessage("只有你主动确认后才会打开相机或麦克风。上传后家长可以在 24 小时内查看。")
          .setNegativeButton("取消",null)
          .setPositiveButton("我同意",(d,w)->request(()->Api.call(this,"POST","/v1/media/self",
              new JSONObject().put("kind",kind),Prefs.token(this)),r->beginMedia(r.getString("id"),kind)))
          .show();
    }
    private void beginMedia(String id,String kind){
        activeMediaID=id;activeMediaKind=kind;
        if("audio".equals(kind)){startAudioWithPermission();return;}
        try{
            File folder=new File(getCacheDir(),"capture");if(!folder.exists()&&!folder.mkdirs())throw new IOException("无法创建拍摄缓存目录");
            captureFile=new File(folder,id+("photo".equals(kind)?".jpg":".mp4"));
            Uri uri=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",captureFile);
            Intent intent=new Intent("photo".equals(kind)?MediaStore.ACTION_IMAGE_CAPTURE:MediaStore.ACTION_VIDEO_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT,uri);
            if("video".equals(kind)){
                intent.putExtra(MediaStore.EXTRA_DURATION_LIMIT,30);
                intent.putExtra(MediaStore.EXTRA_SIZE_LIMIT,25L*1024*1024);
            }
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent,"photo".equals(kind)?501:502);
        }catch(Exception ex){activeMediaID="";activeMediaKind="";show("无法打开相机："+ex.getMessage());}
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=501&&requestCode!=502)return;
        final String id=activeMediaID,kind=activeMediaKind;
        activeMediaID="";activeMediaKind="";
        if(resultCode!=RESULT_OK||captureFile==null){show("已取消拍摄，不会上传");return;}
        final File output=captureFile;
        final Uri returned=data==null?null:data.getData();
        network.execute(()->{
            try{
                if((!output.exists()||output.length()<128)&&returned!=null){
                    try(InputStream in=getContentResolver().openInputStream(returned);OutputStream out=new FileOutputStream(output)){
                        if(in==null)throw new IOException("摄像头未返回文件");
                        byte[] b=new byte[8192];int n;long total=0;
                        while((n=in.read(b))!=-1){total+=n;if(total>25L*1024*1024)throw new IOException("超过 25MB，不能上传");out.write(b,0,n);}
                    }
                }
                Api.upload(this,id,"photo".equals(kind)?"image/jpeg":"video/mp4",output);
                runOnUiThread(()->show("拍摄文件已发送给家长"));
            }catch(Exception ex){runOnUiThread(()->show("拍摄上传失败："+ex.getMessage()));}
            finally{output.delete();}
        });
    }
    private void startAudioWithPermission(){
        if(Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},301);return;
        }
        startAudio();
    }
    private void startAudio(){
        try{
            File folder=new File(getCacheDir(),"capture");if(!folder.exists()&&!folder.mkdirs())throw new IOException("无法创建录音目录");
            captureFile=new File(folder,activeMediaID+".m4a");
            audioRecorder=new MediaRecorder();
            audioRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            audioRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            audioRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            audioRecorder.setAudioEncodingBitRate(64000);
            audioRecorder.setAudioSamplingRate(44100);
            audioRecorder.setOutputFile(captureFile.getAbsolutePath());
            audioRecorder.prepare();audioRecorder.start();recordingStarted=SystemClock.elapsedRealtime();
            show("录音已启动，屏幕上点击停止并发送，最长 30 秒");
            stopAudioButton=btn("停止录音并发送",true,()->stopAudio(true));
            handler.postDelayed(()->{if(audioRecorder!=null)stopAudio(true);},30000);
        }catch(Exception ex){stopAudio(false);show("录音启动失败："+ex.getMessage());}
    }
    private void stopAudio(boolean send){
        MediaRecorder r=audioRecorder;audioRecorder=null;
        if(r==null)return;
        try {r.stop();}catch(RuntimeException ex){send=false;}
        try{r.reset();r.release();}catch(RuntimeException ignored){}
        if(stopAudioButton!=null){stopAudioButton.setEnabled(false);stopAudioButton.setVisibility(View.GONE);stopAudioButton=null;}
        final File output=captureFile;final String id=activeMediaID;
        activeMediaID="";activeMediaKind="";
        if(output==null)return;
        if(!send||SystemClock.elapsedRealtime()-recordingStarted<700){output.delete();show("录音已取消");return;}
        network.execute(()->{
            try{Api.upload(this,id,"audio/mp4",output);runOnUiThread(()->show("录音已发送给家长"));}
            catch(Exception ex){runOnUiThread(()->show("录音上传失败："+ex.getMessage()));}
            finally{output.delete();}
        });
    }
    private void confirmLogout(){
        new AlertDialog.Builder(this).setTitle("退出当前设备账号？").setMessage("退出后只能使用预先保存的恢复密钥找回家庭，密钥丢失将无法恢复。")
            .setNegativeButton("取消",null).setPositiveButton("退出",(a,b)->{
                if("child".equals(Prefs.role(this))){Prefs.setSharing(this,false);stopService(new Intent(this,TrackingService.class));
                    new Thread(()->{LocationQueue q=new LocationQueue(getApplicationContext());q.clear();q.close();}).start();}
                SosOutbox.clear(this);Prefs.clearIdentity(this);render();
            }).show();
    }
}
