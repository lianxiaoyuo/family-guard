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
    private EditText setupTokenField,codeField,nameField,recoveryField;
    private String setupMode="";
    private TextView parentStatus, childStatus, pairText, guardStatus, mediaStatus;
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
    private String latestDiagnostics="";
    private boolean mapCentered=false, mapReady=false, followChild=true;
    private boolean started=false,loading=false;
    private final Runnable poll=new Runnable(){@Override public void run(){
        if(started && "parent".equals(Prefs.role(MainActivity.this))) {
            fetchLatest();handler.postDelayed(this,AccuracyPolicy.PARENT_REFRESH_MS);
        }
    }};
    int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        pendingMapState=b;
        getWindow().setStatusBarColor(Color.rgb(248,250,254));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        render();
        UpdateManager.check(this,false);
        maybePromptSafetyNotifications();
    }
    @Override protected void onResume(){super.onResume();started=true;if(mapView!=null)mapView.onResume();handler.removeCallbacks(poll);handler.post(poll);
        handler.removeCallbacks(childMediaPoll);handler.post(childMediaPoll);
        if("parent".equals(Prefs.role(this)))handler.post(this::maybePromptSafetyNotifications);
    }
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
        map=null;marker=null;accuracyCircle=null;mapCentered=false;mapReady=false;followChild=true;lastRenderedPointId="";
    }
    private void clearRouteLines(){
        for(Polyline line:routeLines){try{line.remove();}catch(RuntimeException ignored){}}
        routeLines.clear();
    }
    private GradientDrawable bg(int color,int stroke){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(18));
        if(stroke!=0)d.setStroke(dp(1),stroke);return d;
    }
    private void cardTitle(String heading,String summary){
        TextView t=text(heading,19,Color.rgb(20,46,83),true);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.topMargin=dp(14);lp.bottomMargin=dp(4);
        t.setLayoutParams(lp);t.setPadding(dp(12),dp(14),dp(12),dp(12));
        t.setBackground(bg(Color.rgb(231,241,252),0));add(t);
        if(summary!=null&&!summary.isEmpty())line(summary);
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
        b.setTextColor(primary?Color.WHITE:Color.rgb(23,73,148));
        b.setBackground(bg(primary?Color.rgb(30,97,210):Color.WHITE,primary?0:Color.rgb(222,232,246)));
        b.setElevation(dp(primary?3:1));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(52));p.bottomMargin=dp(10);b.setLayoutParams(p);
        b.setOnClickListener(v->cb.run());add(b);return b;
    }
    private EditText input(String hint,String value){
        EditText x=new EditText(this);x.setSingleLine(true);x.setTextSize(15);x.setTextColor(Color.rgb(36,45,65));x.setHint(hint);x.setText(value);
        x.setPadding(dp(14),0,dp(14),0);x.setBackground(bg(Color.WHITE,Color.rgb(221,228,238)));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(50));lp.bottomMargin=dp(12);x.setLayoutParams(lp);add(x);return x;
    }
    private void show(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private void render(){
        guardStatus=null;mediaStatus=null;
        releaseMap();
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(248,250,254));
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(14),dp(20),dp(40));scroll.addView(body);setContentView(scroll);
        TextView brand=text("●  FAMILYGUARD  ·  家庭守护",13,Color.rgb(37,105,240),true);
        brand.setPadding(dp(4),dp(8),dp(4),dp(12));add(brand);
        if(Prefs.role(this).isEmpty())setup();
        else if("parent".equals(Prefs.role(this)))parent();
        else child();
    }
    private void setup(){
        title("家庭安全守护");
        line("家庭服务器已自动配置，无需填写网址。首次使用请根据手机身份选择入口。共享位置需在孩子手机上明确授权，可以随时停止。" );
        if ("parent".equals(setupMode)) {
            setupParent();
        } else if ("child".equals(setupMode)) {
            setupChild();
        } else {
            title("请选择这部手机的身份");
            btn("我是家长 · 创建或恢复家庭",true,()->{setupMode="parent";render();});
            btn("我是孩子 · 输入配对码",false,()->{setupMode="child";render();});
        }
    }
    private void setupParent(){
        title("家长端 · 首次创建家庭");
        line("首次创建需要部署时生成的初始化密钥。此密钥只在创建家庭时使用，不保存在 App 内。" );
        setupTokenField=input("首次部署初始化密钥", "");
        setupTokenField.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        btn("创建家庭并获取配对码",true,()->{
            final String initialSetupKey=setupTokenField.getText().toString().trim();
            if(initialSetupKey.isEmpty()){show("请输入初始化密钥");return;}
            request(()->Api.call(this,"POST","/v1/families",new JSONObject(),initialSetupKey),r->{
                Prefs.setIdentity(this,"parent",r.getString("token"));render();
                saveDisplayedPairCode(r.optString("pairCode"));
                showRecoveryKey(r.getString("recoveryKey"));
            });
        });
        title("已有家庭 · 恢复家长访问");
        line("只有在家长手机遗失或更换、无法访问原账号时才使用恢复密钥。恢复后旧家长令牌会失效。" );
        recoveryField=input("64 位家长恢复密钥","");
        recoveryField.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        btn("恢复家长账号",false,()->{
            String key=recoveryField.getText().toString().trim();
            if(!key.matches("[0-9a-fA-F]{64}")){show("请输入完整的 64 位恢复密钥");return;}
            request(()->Api.call(this,"POST","/v1/recover",new JSONObject().put("recoveryKey",key),null),r->{
                Prefs.setIdentity(this,"parent",r.getString("token"));render();showRecoveryKey(r.getString("recoveryKey"));
            });
        });
        btn("返回选择身份",false,()->{setupMode="";render();});
    }
    private void setupChild(){
        title("孩子端 · 授权加入家庭");
        line("请从家长手机获取 8 位配对码。配对码有效期 10 分钟；孩子端无需部署密钥。" );
        codeField=input("家长提供的 8 位配对码","");
        nameField=input("孩子昵称","");
        line("点击加入表示你同意在本机开启持续位置共享；首次还需授予系统定位和通知权限。共享期间有常驻通知，可随时停止。拍照、录音、录像不会因配对而自动授权。");
        btn("同意持续共享位置并加入家庭",true,()->{
            String code=codeField.getText().toString().trim().toUpperCase(Locale.ROOT);
            String name=nameField.getText().toString().trim();
            if(code.length()!=8||name.isEmpty()){show("请输入 8 位配对码和孩子昵称");return;}
            request(()->Api.call(this,"POST","/v1/join",new JSONObject().put("code",code).put("name",name),null),r->{
                Prefs.setIdentity(this,"child",r.getString("token"));
                render();
                // The join button explicitly authorizes ongoing location sharing;
                // Android OS permissions are still required and never bypassed.
                beginTracking();
            });
        });
        btn("返回选择身份",false,()->{setupMode="";render();});
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
    private interface Job {JSONObject execute() throws Exception;}
    private interface Done {void accept(JSONObject r) throws Exception;}
    private void request(Job job,Done done){
        network.execute(()->{
            try{JSONObject r=job.execute();runOnUiThread(()->{
                try{done.accept(r);}catch(Exception ex){show("解析失败："+ex.getMessage());}
            });}catch(Exception ex){runOnUiThread(()->show("请求失败："+ex.getMessage()));}
        });
    }
    // App defaults safety events to enabled, but Android 13+ owns the final permission.
    // Ask only once during onboarding; after denial, Settings remains available.
    private void maybePromptSafetyNotifications(){
        if(!"parent".equals(Prefs.role(this)) || Build.VERSION.SDK_INT<33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)return;
        if(Prefs.store(this).getBoolean("safety_notification_prompted",false))return;
        Prefs.store(this).edit().putBoolean("safety_notification_prompted",true).apply();
        new AlertDialog.Builder(this)
                .setTitle("开启孩子安全提醒")
                .setMessage("为及时提醒 SOS 和进出安全区，建议允许通知。Android 系统需要你确认一次授权；拒绝后可随时在系统设置开启。")
                .setPositiveButton("允许通知",(d,w)->requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},401))
                .setNegativeButton("暂不允许",null).show();
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
            mapView=new MapView(this){
                @Override public boolean dispatchTouchEvent(MotionEvent event){
                    // Called before the SDK's nested map renderer processes gestures.
                    if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
                        if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
                        followChild=false;
                    }else if(event.getActionMasked()==MotionEvent.ACTION_UP ||
                            event.getActionMasked()==MotionEvent.ACTION_CANCEL){
                        if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    return super.dispatchTouchEvent(event);
                }
            };
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(460));
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
            map.getUiSettings().setZoomGesturesEnabled(true);
            map.getUiSettings().setScrollGesturesEnabled(true);
            map.getUiSettings().setRotateGesturesEnabled(true);
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
    private String locationSourceLabel(String provider){
        if("gps".equals(provider))return "GNSS 卫星定位（可能包含北斗、GPS 等）";
        if("amap".equals(provider))return "高德融合定位";
        if("fused".equals(provider))return "Android 系统融合定位";
        if("network".equals(provider))return "网络定位";
        return "未识别";
    }
    private void showLatestOnMap(JSONObject p,long ageMs){
        if(map==null||!mapReady||p==null)return;
        double lat=p.optDouble("lat",Double.NaN),lon=p.optDouble("lon",Double.NaN);
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)return;
        String type=p.optString("coordType","wgs84");
        if(!"gcj02".equals(type)&&!"wgs84".equals(type))return;
        LatLng point=toAmapPoint(lat,lon,type);
        if(marker==null)marker=map.addMarker(new MarkerOptions().position(point).title("孩子最新位置").snippet("定位来源："+locationSourceLabel(p.optString("provider",""))));
        else{marker.setPosition(point);marker.setSnippet("定位来源："+locationSourceLabel(p.optString("provider","")));}
        double radius=Math.min(3000d,Math.max(1d,p.optDouble("accuracy",100d)));
        if(accuracyCircle==null){
            accuracyCircle=map.addCircle(new CircleOptions().center(point).radius(radius)
                    .strokeWidth(2f).strokeColor(Color.rgb(40,116,240))
                    .fillColor(Color.argb(38,40,116,240)).zIndex(5f));
        }else{accuracyCircle.setCenter(point);accuracyCircle.setRadius(radius);}
        if(!mapCentered){map.moveCamera(CameraUpdateFactory.newLatLngZoom(point,17f));mapCentered=true;}
        else if(followChild) map.animateCamera(CameraUpdateFactory.newLatLng(point));
    }
    private void addHistorySegment(List<LatLng> segment){
        if(map==null||segment==null||segment.size()<2)return;
        routeLines.add(map.addPolyline(new PolylineOptions().addAll(segment).width(6f)
                .color(Color.rgb(5,153,122)).geodesic(true).zIndex(4f)));
    }
    private void parent(){
        title("孩子的位置");
        pairText=text("正在连接孩子设备…",17,Color.rgb(30,69,129),true);add(pairText);
        // The live map is the primary control, not a section buried below diagnostics.
        installLiveMap();
        btn("◎ 回到孩子最新位置",false,()->{
            followChild=true;
            if(map!=null && latest!=null){
                String type=latest.optString("coordType","wgs84");
                if("wgs84".equals(type)||"gcj02".equals(type)){
                    double lat=latest.optDouble("lat",Double.NaN), lon=latest.optDouble("lon",Double.NaN);
                    if(Double.isFinite(lat)&&Double.isFinite(lon)){
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(toAmapPoint(lat,lon,type),17f));
                        mapCentered=true;
                    }
                }
            }
        });
        line("拖动或双指缩放地图后，会暂停自动居中；点击上方按钮继续跟随。");
        parentStatus=text("正在同步孩子位置…",15,Color.rgb(33,62,96),false);add(parentStatus);
        btn("查看定位技术详情",false,()->new AlertDialog.Builder(this)
                .setTitle("定位数据与来源")
                .setMessage(latestDiagnostics.isEmpty()?"还没有收到有效位置":latestDiagnostics)
                .setPositiveButton("知道了",null).show());

        cardTitle("快捷操作","实时地图是首页；更多操作在对应功能里完成。");
        btn("查看历史轨迹",false,this::showHistoryPicker);
        historyStatus=text("",13,Color.rgb(65,87,118),false);add(historyStatus);
        btn("设置安全区域（地图选择）",true,this::openFenceEditor);
        guardStatus=text("安全区域：正在加载…",14,Color.rgb(60,76,102),false);add(guardStatus);
        btn("照片 · 录音 · 视频协助",false,this::showAssistancePicker);
        mediaStatus=text("",13,Color.rgb(65,87,118),false);add(mediaStatus);
        btn("查看孩子发来的照片或录音",false,this::downloadLatestMedia);

        cardTitle("家庭管理","日常不需要这些操作。");
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            btn("开启安全通知（SOS / 进出围栏）",false,()->requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},401));
        }
        btn("生成新的孩子配对码",false,()->request(()->Api.call(this,"POST","/v1/pair",new JSONObject(),Prefs.token(this)),
                result->saveDisplayedPairCode(result.optString("pairCode"))));
        btn("检查 App 更新",false,()->UpdateManager.check(this,true));
        btn("打开高德查看孩子位置",false,this::openMap);
        fetchLatest();loadGuard();loadMediaList();
    }
    private void showHistoryPicker(){
        new AlertDialog.Builder(this).setTitle("查看历史轨迹")
                .setItems(new String[]{"最近 1 小时","最近 6 小时","最近 24 小时"},
                        (d,which)->loadHistory(new int[]{1,6,24}[which]))
                .setNegativeButton("取消",null).show();
    }
    private void showAssistancePicker(){
        new AlertDialog.Builder(this).setTitle("请求孩子协助")
                .setMessage("孩子手机会收到请求，确认后才能使用相机或麦克风。")
                .setItems(new String[]{"请孩子拍照","请孩子录音","请孩子录像（30 秒）"},
                        (d,which)->askMedia(new String[]{"photo","audio","video"}[which]))
                .setNegativeButton("取消",null).show();
    }
    private void openFenceEditor(){
        if(!AmapPrivacy.available()){
            show("当前 APK 未配置高德地图，请重新构建已配置 Key 的安装包");return;
        }
        if(!AmapPrivacy.agreed(this)){
            askAmapConsent(this::openFenceEditor);return;
        }
        Intent i=new Intent(this,FenceMapActivity.class);
        if(latest!=null){
            double lat=latest.optDouble("lat",Double.NaN);
            double lon=latest.optDouble("lon",Double.NaN);
            if(Double.isFinite(lat)&&Double.isFinite(lon)&&Math.abs(lat)<=90&&Math.abs(lon)<=180){
                i.putExtra("lat",lat);i.putExtra("lon",lon);
                i.putExtra("coordType",latest.optString("coordType","wgs84"));
            }
        }
        startActivity(i);
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
                            int battery=result.optInt("battery",-1);
                            latestDiagnostics="时间："+time(latest.optLong("deviceTime"))+"\n来源："+
                                    locationSourceLabel(latest.optString("provider",""))+"\n精度：±"+
                                    String.format(Locale.CHINA,"%.1f",err)+" 米\n原始坐标："+
                                    String.format(Locale.US,"%.7f, %.7f",latest.optDouble("lat"),latest.optDouble("lon"))+
                                    "\n卫星参与解算："+latest.optInt("satellitesUsed",0)+" 颗";
                            parentStatus.setText("位置："+freshness+"  ·  精度约 ±"+
                                    String.format(Locale.CHINA,"%.0f",err)+" 米（"+quality+"）\n"+
                                    "来源："+locationSourceLabel(latest.optString("provider",""))+"\n"+
                                    "设备："+state+(battery>=0?"  ·  电量 "+battery+"%":""));
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
        title("孩子端 · 安全守护");
        childStatus=text(Prefs.isSharing(this)?"位置共享已开启（可随时停止）":"位置共享尚未开启",18,
                Color.rgb(28,68,122),true);add(childStatus);
        line("配对时已经确认持续共享。正常情况下无需重复启动；如果系统权限被关闭，可在这里重新开启。");
        if(!Prefs.isSharing(this))btn("开启位置共享",true,this::beginTracking);
        btn("SOS · 一键求助",true,this::sendSOS);
        line("求助先保存到本机，再发送给家庭服务器。网络断开时将自动重试，但不能代替报警电话。");
        btn("查看家长协助请求",false,this::fetchPendingMedia);
        btn("主动发送照片 / 录音 / 视频",false,this::showSelfMediaPicker);
        cardTitle("设备设置","系统可能限制后台运行，开启相应权限可以提高可靠性。");
        btn("检查后台定位权限",false,()->{
            Intent i=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()));
            startActivity(i);
        });
        btn("检查 App 更新",false,()->UpdateManager.check(this,true));
        if(Prefs.isSharing(this))btn("暂停位置共享",false,()->{
            new AlertDialog.Builder(this).setTitle("暂停位置共享？")
                    .setMessage("暂停后，家长只能看到最后一次位置，直到你重新开启。")
                    .setNegativeButton("取消",null)
                    .setPositiveButton("确认暂停",(d,w)->{
                        Prefs.setSharing(this,false);
                        startService(new Intent(this,TrackingService.class).setAction(TrackingService.STOP));
                        render();
                    }).show();
        });
    }
    private void showSelfMediaPicker(){
        new AlertDialog.Builder(this).setTitle("主动给家长发送")
                .setItems(new String[]{"拍照","录音 30 秒","录像 30 秒"},
                        (d,which)->startSelfMedia(new String[]{"photo","audio","video"}[which]))
                .setNegativeButton("取消",null).show();
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
    private void loadGuard(){
        if(!started||!"parent".equals(Prefs.role(this)))return;
        request(()->Api.call(this,"GET","/v1/guard",null,Prefs.token(this)),r->{
            JSONObject f=r.optJSONObject("fence");
            org.json.JSONArray events=r.optJSONArray("events");
            StringBuilder b=new StringBuilder(f==null?"电子围栏：未开启":
                    "polygon".equals(f.optString("shape"))?"电子围栏：自定义地图区域已开启":
                            "电子围栏：已开启，半径 "+f.optInt("radius")+" 米");
            if(events!=null && events.length()>0){
                JSONObject last=events.optJSONObject(events.length()-1);
                if(last!=null){b.append("\n最新提醒：").append(last.optString("message")).append(" · ").append(time(last.optLong("time")));
                    String id=last.optString("id");
                    ParentAlertNotifier.handle(this,last);
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
