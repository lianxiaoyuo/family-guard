package com.familyguard.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
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
import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.geocoder.GeocodeSearch;
import com.amap.api.services.geocoder.GeocodeResult;
import com.amap.api.services.geocoder.RegeocodeQuery;
import com.amap.api.services.geocoder.RegeocodeResult;
import android.location.Location;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import com.amap.api.maps.model.LatLngBounds;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.BitmapDescriptor;
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
    private EditText codeField,nameField,recoveryField;
    private String setupMode="";
    private volatile String selectedChildId="";
    private String selectedChildName="";
    private JSONArray familyChildren=new JSONArray();
    private LinearLayout childTabs;
    private final Map<String,Marker> familyMarkers=new HashMap<>();
    private final Map<String,BitmapDescriptor> childDotIcons=new HashMap<>();
    private final Map<String,String> markerVisualStates=new HashMap<>();
    private long lastChildrenFetch=0L;
    private long lastFamilyEventFetch=0L;
    private boolean familyEventsBusy=false;
    private boolean childrenBusy=false;
    private TextView parentStatus, childStatus, pairText, guardStatus, mediaStatus;
    private String shownMediaRequest="", activeMediaID="", activeMediaKind="", lastGuardEvent="";
    private boolean parentPaired=false;
    private String parentChildName="";
    private long parentLastSeen=0L;
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
    // Native view overlay, unlike AMap InfoWindow snapshots: accessible and clickable.
    private FrameLayout mapFrame;
    private LinearLayout locationOverlay;
    private String locationOverlayChildId="";
    private TextView overlayTitle,overlayChip,overlayAddress,overlayTime,
            overlayAccuracy,overlayBattery,overlaySource;
    private int selectedBattery=-1;
    private AMap map;
    private Marker marker;
    private Circle accuracyCircle;
    private final List<Polyline> routeLines=new ArrayList<>();
    private Bundle pendingMapState;
    private TextView historyStatus;
    private TextView addressStatus;
    // FG_V7_DASHBOARD_ADDRESS_PATCH: address belongs to a *location*, never to an unrelated device fix.
    private GeocodeSearch addressSearch;
    private LatLng addressedPoint;
    private long addressAttemptElapsed = -120000L;
    private String resolvedAddress = "正在解析附近地址…";
    private boolean addressBusy = false;
    private String latestDiagnostics="";
    private boolean mapCentered=false, mapReady=false, followChild=true;
    private boolean started=false,loading=false;
    private final Runnable poll=new Runnable(){@Override public void run(){
        if(started && "parent".equals(Prefs.role(MainActivity.this))) {
            fetchLatest();loadFamilyEvents();handler.postDelayed(this,AccuracyPolicy.PARENT_REFRESH_MS);
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
        map=null;mapFrame=null;locationOverlay=null;locationOverlayChildId="";
        marker=null;familyMarkers.clear();childDotIcons.clear();markerVisualStates.clear();accuracyCircle=null;mapCentered=false;mapReady=false;followChild=true;lastRenderedPointId="";
    }
    private void clearRouteLines(){
        for(Polyline line:routeLines){try{line.remove();}catch(RuntimeException ignored){}}
        routeLines.clear();
    }
    private GradientDrawable bg(int color,int stroke){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(18));
        if(stroke!=0)d.setStroke(dp(1),stroke);return d;
    }
    private GradientDrawable roundBg(int color,int border,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));
        if(border!=0)d.setStroke(dp(1),border);return d;
    }
    private GradientDrawable gradientBg(int top,int bottom,int radius){
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{top,bottom});
        d.setCornerRadius(dp(radius));return d;
    }
    private TextView smallLabel(String value){
        TextView t=text(value,11,Color.rgb(104,122,147),false);
        t.setPadding(0,0,0,dp(3));return t;
    }
    private void cardTitle(String heading,String summary){
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams box=new LinearLayout.LayoutParams(-1,-2);
        box.topMargin=dp(17);box.bottomMargin=dp(11);column.setLayoutParams(box);
        LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        View accent=new View(this);accent.setBackground(roundBg(Color.rgb(39,112,222),0,2));
        LinearLayout.LayoutParams lineLp=new LinearLayout.LayoutParams(dp(4),dp(20));lineLp.rightMargin=dp(10);
        top.addView(accent,lineLp);
        TextView title=text(heading,17,Color.rgb(27,49,83),true);title.setPadding(0,0,0,0);
        top.addView(title);column.addView(top);
        if(summary!=null&&!summary.isEmpty()){
            TextView sub=text(summary,12,Color.rgb(110,125,149),false);
            sub.setPadding(dp(14),dp(5),0,0);column.addView(sub);
        }
        add(column);
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
        b.setBackground(roundBg(primary?Color.rgb(35,104,216):Color.WHITE,
                primary?0:Color.rgb(225,234,245),15));
        b.setElevation(dp(primary?3:1));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(50));p.bottomMargin=dp(9);b.setLayoutParams(p);
        b.setOnClickListener(v->cb.run());add(b);return b;
    }
    /** Two-column touch targets; only the parent dashboard uses these tiles. */
    private LinearLayout actionTile(String symbol,String name,String desc,boolean featured,Runnable action){
        LinearLayout tile=new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(12),dp(10),dp(10),dp(9));
        tile.setBackground(roundBg(featured?Color.rgb(237,245,255):Color.WHITE,
                featured?Color.rgb(203,223,250):Color.rgb(228,235,246),16));
        tile.setElevation(dp(1));
        tile.setClickable(true);tile.setFocusable(true);
        LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView iconView=text(symbol,17,Color.rgb(38,104,202),true);
        iconView.setGravity(Gravity.CENTER);iconView.setPadding(0,0,0,0);
        iconView.setBackground(roundBg(Color.rgb(223,237,255),0,10));
        LinearLayout.LayoutParams iconLp=new LinearLayout.LayoutParams(dp(32),dp(32));iconLp.rightMargin=dp(8);
        top.addView(iconView,iconLp);
        TextView heading=text(name,14,Color.rgb(25,52,90),true);
        heading.setPadding(0,0,0,0);heading.setSingleLine(true);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(heading,new LinearLayout.LayoutParams(0,-2,1));tile.addView(top);
        TextView detail=text(desc,11,Color.rgb(108,125,149),false);
        detail.setPadding(0,dp(7),0,0);
        detail.setMaxLines(1);detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tile.addView(detail);
        tile.setContentDescription(name+"，"+desc);
        tile.setOnClickListener(v->action.run());return tile;
    }
    private void actionRow(String sym1,String name1,String desc1,boolean featured1,Runnable action1,
                           String sym2,String name2,String desc2,boolean featured2,Runnable action2){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp=new LinearLayout.LayoutParams(-1,dp(92));rowLp.bottomMargin=dp(9);
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-1,1);
        left.rightMargin=dp(10);
        row.addView(actionTile(sym1,name1,desc1,featured1,action1),left);
        row.addView(actionTile(sym2,name2,desc2,featured2,action2),
                new LinearLayout.LayoutParams(0,-1,1));
        row.setLayoutParams(rowLp);add(row);
    }
    /** A compact, real Android View over the map (InfoWindow itself is a static bitmap). */
    private JSONObject childRow(String id){
        for(int i=0;i<familyChildren.length();i++){
            JSONObject child=familyChildren.optJSONObject(i);
            if(child!=null && id.equals(child.optString("childId")))return child;
        }
        return null;
    }
    private JSONObject pointForChild(String id){
        JSONObject row=childRow(id);
        if(id.equals(selectedChildId) && latest!=null)return latest;
        return row==null?null:row.optJSONObject("point");
    }
    private void hideLocationOverlay(){
        locationOverlayChildId="";
        if(locationOverlay!=null && mapFrame!=null)mapFrame.removeView(locationOverlay);
        locationOverlay=null;
        overlayTitle=null;overlayChip=null;overlayAddress=null;
        overlayTime=null;overlayAccuracy=null;overlayBattery=null;overlaySource=null;
    }
    private void showLocationOverlay(String childId){
        if(mapFrame==null||childId==null||childId.isEmpty())return;
        if(!childId.equals(locationOverlayChildId))hideLocationOverlay();
        locationOverlayChildId=childId;
        refreshSelectedInfoWindow();
    }
    private LinearLayout statCell(String label,String value,int type){
        LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(9),dp(8),dp(6),dp(7));
        cell.setBackground(roundBg(Color.rgb(242,247,253),0,10));
        cell.addView(smallLabel(label));
        TextView v=text(value,12,Color.rgb(29,57,96),true);
        v.setSingleLine(true);v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setPadding(0,0,0,0);cell.addView(v);
        if(type==0)overlayTime=v;
        else if(type==1)overlayAccuracy=v;
        else overlayBattery=v;
        return cell;
    }
    private String compactTime(long millis){
        return millis>0?new SimpleDateFormat("HH:mm:ss",Locale.CHINA).format(new Date(millis)):"暂无";
    }
    private String compactSource(String provider){
        if("gps".equals(provider))return "卫星 GNSS";
        if("amap".equals(provider))return "高德融合";
        if("fused".equals(provider))return "系统融合";
        if("network".equals(provider))return "网络定位";
        return "来源未知";
    }
    /** All actions are native click targets: no unclickable controls inside AMap InfoWindow. */
    private void refreshSelectedInfoWindow(){
        if(mapFrame==null||locationOverlayChildId.isEmpty())return;
        final String id=locationOverlayChildId;
        JSONObject child=childRow(id),fix=pointForChild(id);
        String name=child==null?selectedChildName:child.optString("childName","孩子");
        if(name==null||name.trim().isEmpty())name="孩子";
        long at=fix==null?0:fix.optLong("deviceTime",0);
        long received=fix==null?0:fix.optLong("receivedAt",0);
        long age=at>0?Math.max(System.currentTimeMillis()-at,received>0?System.currentTimeMillis()-received:0):Long.MAX_VALUE;
        boolean stale=age>60000L;
        int battery=id.equals(selectedChildId)&&selectedBattery>=0?selectedBattery:
                (child==null?-1:child.optInt("battery",-1));
        double accuracy=fix==null?Double.NaN:fix.optDouble("accuracy",Double.NaN);
        String accuracyText=Double.isFinite(accuracy)&&accuracy>=0?
                String.format(Locale.CHINA,"±%.0fm",accuracy):"未知";
        String source=fix==null?"暂无定位":compactSource(fix.optString("provider",""));
        String addr=fix==null?"等待孩子设备上传位置":id.equals(selectedChildId)?resolvedAddress:"切换后同步地址…";
        if(locationOverlay!=null){
            overlayTitle.setText(name);
            overlayChip.setText(stale?"历史位置":"最新位置");
            overlayChip.setTextColor(stale?Color.rgb(139,92,43):Color.rgb(19,119,103));
            overlayChip.setBackground(roundBg(stale?Color.rgb(255,241,221):Color.rgb(221,247,239),0,9));
            overlayAddress.setText(addr);
            overlayTime.setText(compactTime(at));
            overlayAccuracy.setText(accuracyText);
            overlayBattery.setText(battery>=0?battery+"%":"未知");
            String coord=fix==null?"暂无坐标":String.format(Locale.US,"%.5f, %.5f",
                    fix.optDouble("lat"),fix.optDouble("lon"));
            overlaySource.setText(source+"  ·  "+coord);
            return;
        }
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13),dp(12),dp(13),dp(11));
        card.setBackground(gradientBg(Color.rgb(253,254,255),Color.rgb(247,250,255),20));
        card.setElevation(dp(10));card.setFocusable(true);
        int width=Math.min(dp(340),getResources().getDisplayMetrics().widthPixels-dp(62));
        FrameLayout.LayoutParams frameLp=new FrameLayout.LayoutParams(width,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        frameLp.bottomMargin=dp(13);
        mapFrame.addView(card,frameLp);locationOverlay=card;

        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        View childDot=new View(this);childDot.setBackground(roundBg(childColor(id),0,8));
        LinearLayout.LayoutParams dotLp=new LinearLayout.LayoutParams(dp(11),dp(11));dotLp.rightMargin=dp(9);
        header.addView(childDot,dotLp);
        TextView title=text(name,16,Color.rgb(24,48,81),true);
        overlayTitle=title;
        title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(0,0,dp(6),0);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView chip=text(stale?"历史位置":"最新位置",10,
                stale?Color.rgb(139,92,43):Color.rgb(19,119,103),true);
        overlayChip=chip;
        chip.setPadding(dp(8),dp(5),dp(8),dp(5));
        chip.setBackground(roundBg(stale?Color.rgb(255,241,221):Color.rgb(221,247,239),0,9));
        header.addView(chip);
        TextView close=text("×",23,Color.rgb(105,122,147),false);
        close.setGravity(Gravity.CENTER);close.setPadding(0,0,0,0);
        LinearLayout.LayoutParams closeLp=new LinearLayout.LayoutParams(dp(35),dp(38));
        closeLp.leftMargin=dp(2);header.addView(close,closeLp);
        close.setContentDescription("关闭位置详情");close.setOnClickListener(v->hideLocationOverlay());
        card.addView(header);

        LinearLayout divider=new LinearLayout(this);
        divider.setBackgroundColor(Color.rgb(232,238,247));
        LinearLayout.LayoutParams dividerLp=new LinearLayout.LayoutParams(-1,dp(1));
        dividerLp.topMargin=dp(6);dividerLp.bottomMargin=dp(9);card.addView(divider,dividerLp);

        card.addView(smallLabel("附近地址"));
        TextView address=text(addr,13,Color.rgb(32,60,95),true);
        overlayAddress=address;
        address.setPadding(0,0,0,dp(8));address.setMaxLines(2);
        address.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(address);

        LinearLayout stats=new LinearLayout(this);stats.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams stat1=new LinearLayout.LayoutParams(0,-2,1);
        stat1.rightMargin=dp(5);stats.addView(statCell("定位时间",compactTime(at),0),stat1);
        LinearLayout.LayoutParams stat2=new LinearLayout.LayoutParams(0,-2,1);
        stat2.rightMargin=dp(5);stats.addView(statCell("定位精度",accuracyText,1),stat2);
        stats.addView(statCell("手机电量",battery>=0?battery+"%":"未知",2),new LinearLayout.LayoutParams(0,-2,1));
        card.addView(stats);

        LinearLayout footer=new LinearLayout(this);footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams footerLp=new LinearLayout.LayoutParams(-1,dp(38));
        footerLp.topMargin=dp(5);card.addView(footer,footerLp);
        String coord=fix==null?"暂无坐标":String.format(Locale.US,"%.5f, %.5f",
                fix.optDouble("lat"),fix.optDouble("lon"));
        TextView detail=text(source+"  ·  "+coord,10,Color.rgb(108,123,147),false);
        overlaySource=detail;
        detail.setSingleLine(true);detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        detail.setPadding(0,0,dp(4),0);footer.addView(detail,new LinearLayout.LayoutParams(0,-2,1));
        TextView go=text("高德查看 ›",12,Color.rgb(34,101,205),true);
        go.setGravity(Gravity.CENTER);go.setPadding(dp(7),dp(7),dp(3),dp(7));
        go.setContentDescription("在高德地图查看"+name+"的位置");
        go.setOnClickListener(v->openMapForChild(id));footer.addView(go);
    }
    private void updateAddressDetails(){
        if(addressStatus!=null)addressStatus.setText("⌖  "+resolvedAddress);
        if(marker!=null)marker.setSnippet(resolvedAddress);
        refreshSelectedInfoWindow();
    }
    /** Switching children while a previous geocode is in flight must not strand the new address. */
    private void retryMissingAddressForSelectedChild(){
        if(!mapReady||latest==null||addressBusy||addressedPoint!=null)return;
        double lat=latest.optDouble("lat",Double.NaN),lon=latest.optDouble("lon",Double.NaN);
        String type=latest.optString("coordType","wgs84");
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180||
                !("gcj02".equals(type)||"wgs84".equals(type)))return;
        resolvePointAddress(toAmapPoint(lat,lon,type));
    }
    /** Throttled GCJ-02 reverse geocoding; errors are shown as errors, never fabricated addresses. */
    private void resolvePointAddress(LatLng point){
        if(point==null||!AmapPrivacy.agreed(this))return;
        float[] metres=new float[1];
        if(addressedPoint!=null){
            Location.distanceBetween(addressedPoint.latitude,addressedPoint.longitude,
                    point.latitude,point.longitude,metres);
            if(metres[0]<75f)return; // Keep address for small GNSS drift; don't exhaust quota.
        }
        if(addressBusy||SystemClock.elapsedRealtime()-addressAttemptElapsed<12000L)return;
        final LatLng queryPoint=point;
        addressAttemptElapsed=SystemClock.elapsedRealtime();
        addressedPoint=queryPoint;
        addressBusy=true;
        resolvedAddress="正在解析附近地址…";
        updateAddressDetails();
        try{
            if(addressSearch==null){
                addressSearch=new GeocodeSearch(this);
                addressSearch.setOnGeocodeSearchListener(new GeocodeSearch.OnGeocodeSearchListener(){
                    @Override public void onRegeocodeSearched(RegeocodeResult result,int code){
                        runOnUiThread(()->{
                            addressBusy=false;
                            if(addressedPoint==null||latest==null)return;
                            LatLng current=toAmapPoint(latest.optDouble("lat"),latest.optDouble("lon"),
                                    latest.optString("coordType","wgs84"));
                            float[] difference=new float[1];
                            Location.distanceBetween(addressedPoint.latitude,addressedPoint.longitude,
                                    current.latitude,current.longitude,difference);
                            if(difference[0]>=150f){
                                addressedPoint=null;
                                resolvedAddress="位置已变化，等待更新地址…";
                                updateAddressDetails();return;
                            }
                            String found=(code==1000 && result!=null && result.getRegeocodeAddress()!=null)
                                    ?result.getRegeocodeAddress().getFormatAddress():null;
                            resolvedAddress=found!=null&&!found.trim().isEmpty()?found.trim()
                                    :"暂时无法解析详细地址，可查看坐标";
                            if(found==null||found.trim().isEmpty())addressedPoint=null;
                            updateAddressDetails();
                        });
                    }
                    @Override public void onGeocodeSearched(GeocodeResult r,int code){}
                });
            }
            addressSearch.getFromLocationAsyn(new RegeocodeQuery(
                    new LatLonPoint(queryPoint.latitude,queryPoint.longitude),200,GeocodeSearch.AMAP));
        }catch(Exception ex){
            addressBusy=false;
            addressedPoint=null;
            resolvedAddress="地址解析失败，可查看坐标";
            updateAddressDetails();
            android.util.Log.w("FamilyGuard","Reverse geocode unavailable",ex);
        }
    }
    private EditText input(String hint,String value){
        EditText x=new EditText(this);x.setSingleLine(true);x.setTextSize(15);x.setTextColor(Color.rgb(36,45,65));x.setHint(hint);x.setText(value);
        x.setPadding(dp(14),0,dp(14),0);x.setBackground(bg(Color.WHITE,Color.rgb(221,228,238)));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(50));lp.bottomMargin=dp(12);x.setLayoutParams(lp);add(x);return x;
    }
    private void show(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private void render(){
        guardStatus=null;mediaStatus=null;addressStatus=null;
        releaseMap();
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(248,250,254));
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(14),dp(20),dp(40));scroll.addView(body);setContentView(scroll);
        TextView brand=text("●  FAMILYGUARD  ·  独立家庭守护",13,Color.rgb(37,105,240),true);
        brand.setPadding(dp(4),dp(8),dp(4),dp(12));add(brand);
        if(Prefs.role(this).isEmpty())setup();
        else if("parent".equals(Prefs.role(this)))parent();
        else child();
    }
    private void setup(){
        title("家庭安全守护");
        line("同一 APK，家庭之间完全独立。家长不用手机号、邮箱或邀请码；孩子通过配对码加入。请妥善保存恢复密钥，遗失后无法找回家庭。 ");
        if("create".equals(setupMode)){
            setupCreateFamily();
        }else if("recover".equals(setupMode)){
            setupRecoverFamily();
        }else if("child".equals(setupMode)){
            setupChild();
        }else if("guardian".equals(setupMode)){
            setupGuardian();
        }else{
            title("请选择这部手机的用途");
            btn("我是家长 · 免费创建家庭",true,()->{setupMode="create";render();});
            btn("我是孩子 · 输入配对码",false,()->{setupMode="child";render();});
            btn("已有家庭 · 使用恢复密钥",false,()->{setupMode="recover";render();});
            btn("我是其他监护人 · 加入家庭",false,()->{setupMode="guardian";render();});
        }
    }
    private void setupCreateFamily(){
        title("创建独立家庭 · 无需注册");
        line("创建后自动生成家庭身份和 64 位恢复密钥；与你以外的家庭严格隔离。不需要邮件、短信或平台邀请码。");
        EditText familyName=input("家庭名称（可选）","我的家庭");
        btn("立即创建我的家庭",true,()->{
            String name=familyName.getText().toString().trim();
            if(name.isEmpty())name="我的家庭";
            if(name.codePointCount(0,name.length())>32){show("家庭名称最多 32 字");return;}
            final String finalName=name;
            request(()->Api.call(this,"GET","/v1/registration/status",null,null),status->{
                if(!status.optBoolean("available",false)){
                    show("服务器尚未开放免注册创建家庭。原有家庭仍可用恢复密钥登录。");
                    return;
                }
                new AlertDialog.Builder(this).setTitle("确认创建家庭")
                    .setMessage("无需账号，但恢复密钥是换机找回家庭的唯一凭证。请确保能够安全保存。")
                    .setNegativeButton("返回",null)
                    .setPositiveButton("确认创建",(d,w)->request(
                        ()->Api.call(this,"POST","/v1/families",new JSONObject().put("name",finalName),null),result->{
                            Prefs.setIdentity(this,"parent",result.getString("token"));
                            render();
                            saveDisplayedPairCode(result.optString("pairCode"));
                            showRecoveryKey(result.getString("recoveryKey"));
                        })).show();
            });
        });
        btn("返回",false,()->{setupMode="";render();});
    }
    private void setupRecoverFamily(){
        title("恢复已有家庭");
        line("在新家长手机输入最初保存的 64 位恢复密钥。恢复成功会让旧家长设备的管理员令牌失效；已加入的孩子不会被删除。 ");
        recoveryField=input("64 位恢复密钥","");
        recoveryField.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        btn("恢复我的家庭",true,()->{
            String key=recoveryField.getText().toString().trim();
            if(!key.matches("[0-9a-fA-F]{64}")){show("请输入完整的64位恢复密钥");return;}
            new AlertDialog.Builder(this).setTitle("确认换机恢复")
                .setMessage("继续后旧家长设备的登录令牌会失效，并生成新的恢复密钥，必须重新保存。")
                .setNegativeButton("取消",null)
                .setPositiveButton("确认恢复",(d,w)->request(
                    ()->Api.call(this,"POST","/v1/recover",new JSONObject().put("recoveryKey",key),null),r->{
                        Prefs.setIdentity(this,"parent",r.getString("token"));
                        render();showRecoveryKey(r.getString("recoveryKey"));
                    })).show();
        });
        btn("返回",false,()->{setupMode="";render();});
    }
    private void setupGuardian(){
        title("加入现有家庭 · 家长或监护人");
        line("由家庭管理员发送监护人邀请码。加入后可以查看该家庭的孩子位置，无需新建家庭。");
        EditText invite=input("监护人邀请码（24位）","");
        EditText name=input("你的称呼","");
        btn("加入家庭",true,()->{
            String code=invite.getText().toString().trim();String guardian=name.getText().toString().trim();
            if(!code.matches("[0-9a-fA-F]{24}")||guardian.isEmpty()){show("请输入24位邀请码和称呼");return;}
            request(()->Api.call(this,"POST","/v1/members/join",
                    new JSONObject().put("code",code).put("name",guardian),null),r->{
                Prefs.setIdentity(this,"parent",r.getString("token"));render();
                show("已加入家庭："+r.optString("familyName","我的家庭"));
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
            mapFrame=new FrameLayout(this);
            mapFrame.setBackground(roundBg(Color.WHITE,Color.rgb(227,234,246),19));
            mapFrame.setElevation(dp(2));
            mapFrame.setClipToOutline(true);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(460));
            lp.bottomMargin=dp(12);
            body.addView(mapFrame,lp);
            mapFrame.addView(mapView,new FrameLayout.LayoutParams(-1,-1));
            Bundle state=pendingMapState;pendingMapState=null;
            mapView.onCreate(state);
            if(started)mapView.onResume();
            map=mapView.getMap();
            map.setMapType(AMap.MAP_TYPE_NORMAL);
            map.setTrafficEnabled(false);
            // Native overlay is compact and interactive, unlike SDK InfoWindow snapshots.
            map.setOnMarkerClickListener(m->{
                Object tag=m.getObject();
                String id=tag instanceof String?(String)tag:"";
                if(id.isEmpty())return true;
                if(id.equals(locationOverlayChildId)){hideLocationOverlay();return true;}
                if(!id.equals(selectedChildId))selectChild(id);
                showLocationOverlay(id);
                return true;
            });
            map.setOnMapClickListener(p->hideLocationOverlay());
            map.setOnMapLoadedListener(()->android.util.Log.i("FamilyGuard.AMap","AMap map tiles loaded"));
            map.getUiSettings().setZoomControlsEnabled(false);
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
        Marker selected=familyMarkers.get(selectedChildId);
        if(selected!=null){
            marker=selected;marker.setPosition(point);marker.setSnippet(resolvedAddress);
        }else if(marker==null || (marker.getObject() instanceof String &&
                !selectedChildId.equals(marker.getObject()))){
            marker=map.addMarker(new MarkerOptions().position(point)
                    .title(selectedChildName.isEmpty()?"孩子":selectedChildName)
                    .icon(childDotIcon(selectedChildId,true)).anchor(0.5f,0.5f));
            if(!selectedChildId.isEmpty()){
                marker.setObject(selectedChildId);familyMarkers.put(selectedChildId,marker);
                markerVisualStates.put(selectedChildId,childColor(selectedChildId)+":selected");
            }
        }else{marker.setPosition(point);marker.setSnippet(resolvedAddress);}
        resolvePointAddress(point);
        // Keep the map visually clean: accuracy stays visible in the detail card,
        // not as a huge blue translucent circle around the child.
        if(accuracyCircle!=null){accuracyCircle.remove();accuracyCircle=null;}
        if(!mapCentered){map.moveCamera(CameraUpdateFactory.newLatLngZoom(point,17f));mapCentered=true;}
        else if(followChild) map.animateCamera(CameraUpdateFactory.newLatLng(point));
    }

    private void addHistorySegment(List<LatLng> segment){
        if(map==null||segment==null||segment.size()<2)return;
        routeLines.add(map.addPolyline(new PolylineOptions().addAll(segment).width(6f)
                .color(Color.rgb(5,153,122)).geodesic(true).zIndex(4f)));
    }
    private String childPath(String path){
        String id=selectedChildId;
        return id.isEmpty()?path:path+(path.contains("?")?"&":"?")+"childId="+Uri.encode(id);
    }
    private void selectChild(String id){
        if(id==null||id.isEmpty())return;
        final String previous=selectedChildId;
        selectedChildId=id;
        Marker old=familyMarkers.get(previous);
        if(old!=null && !previous.equals(id))styleChildMarker(old,previous,false);
        Marker current=familyMarkers.get(id);
        if(current!=null)styleChildMarker(current,id,true);
        marker=current;
        lastRenderedPointId="";latest=null;mapCentered=false;
        addressedPoint=null;addressAttemptElapsed=-120000L;
        resolvedAddress="正在解析附近地址…";updateAddressDetails();
        if(accuracyCircle!=null){accuracyCircle.remove();accuracyCircle=null;}
        rebuildChildTabs();
        if(!locationOverlayChildId.isEmpty()){
            hideLocationOverlay();locationOverlayChildId=id;refreshSelectedInfoWindow();
        }
        fetchLatest();loadGuard();loadMediaList();
    }
    /** Plain colored dot + text, without the old oval/pill background around a child's name. */
    private void rebuildChildTabs(){
        if(childTabs==null)return;
        childTabs.removeAllViews();
        for(int i=0;i<familyChildren.length();i++){
            JSONObject child=familyChildren.optJSONObject(i);if(child==null)continue;
            String id=child.optString("childId"), name=child.optString("childName","孩子");
            boolean selected=id.equals(selectedChildId);
            LinearLayout item=new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setMinimumHeight(dp(46));
            item.setPadding(dp(8),0,dp(8),0);
            View dot=new View(this);
            GradientDrawable circle=new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(childColor(id));
            dot.setBackground(circle);
            item.addView(dot,new LinearLayout.LayoutParams(dp(10),dp(10)));
            TextView label=text(name,14,selected?childColor(id):Color.rgb(59,80,111),selected);
            label.setPadding(dp(7),0,dp(5),0);
            if(selected)label.setPaintFlags(label.getPaintFlags()|Paint.UNDERLINE_TEXT_FLAG);
            item.addView(label);
            item.setClickable(true);
            item.setFocusable(true);
            item.setContentDescription("查看孩子："+name);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(46));
            lp.rightMargin=dp(10);
            childTabs.addView(item,lp);
            item.setOnClickListener(v->selectChild(id));
        }
    }
    private void showAllChildren(){
        if(map==null||familyMarkers.isEmpty()){show("暂无孩子位置");return;}
        LatLngBounds.Builder bounds=new LatLngBounds.Builder();int found=0;Marker single=null;
        for(Marker m:familyMarkers.values()){bounds.include(m.getPosition());found++;single=m;}
        try{
            followChild=false;
            if(found==1 && single!=null)map.animateCamera(CameraUpdateFactory.newLatLngZoom(single.getPosition(),16f));
            else map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(),dp(70)));
        }catch(RuntimeException ex){show("地图调整失败，请稍后重试");}
    }
    /** Stable, accessible color per family device; nothing but circles on the map. */
    private static final int[] CHILD_DOT_COLORS={
            Color.rgb(36,112,217),Color.rgb(0,151,130),Color.rgb(159,80,201),
            Color.rgb(220,110,36),Color.rgb(215,70,114),Color.rgb(91,139,45),
            Color.rgb(185,137,27),Color.rgb(19,151,184)
    };
    private int childColor(String id){
        for(int i=0;i<familyChildren.length();i++){
            JSONObject c=familyChildren.optJSONObject(i);
            if(c!=null && id.equals(c.optString("childId")))return CHILD_DOT_COLORS[i%CHILD_DOT_COLORS.length];
        }
        return CHILD_DOT_COLORS[(id==null?0:(id.hashCode()&0x7fffffff))%CHILD_DOT_COLORS.length];
    }
    /** 40dp bitmap is easy to tap; the visible center is a clean ~20dp colored dot. */
    private BitmapDescriptor childDotIcon(String id,boolean selected){
        String key=id+":"+childColor(id)+":"+(selected?"selected":"normal");
        BitmapDescriptor cached=childDotIcons.get(key);
        if(cached!=null)return cached;
        int px=dp(40);
        Bitmap image=Bitmap.createBitmap(px,px,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(image);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        float center=px/2f;
        if(selected){
            paint.setColor(Color.argb(55,36,112,217));
            canvas.drawCircle(center,center,dp(18),paint);
        }
        paint.setColor(Color.argb(45,23,45,78));
        canvas.drawCircle(center,center+dp(1),dp(13),paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(center,center,dp(13),paint);
        paint.setColor(childColor(id));
        canvas.drawCircle(center,center,dp(10),paint);
        if(selected){
            paint.setColor(Color.WHITE);
            canvas.drawCircle(center,center,dp(2),paint);
        }
        BitmapDescriptor icon=BitmapDescriptorFactory.fromBitmap(image);
        childDotIcons.put(key,icon);
        return icon;
    }
    private void styleChildMarker(Marker target,String id,boolean selected){
        String desired=childColor(id)+":"+(selected?"selected":"normal");
        if(!desired.equals(markerVisualStates.get(id))){
            target.setIcon(childDotIcon(id,selected));
            markerVisualStates.put(id,desired);
        }
    }
    private void fetchChildren(){
        if(childrenBusy||!started||!"parent".equals(Prefs.role(this))||System.currentTimeMillis()-lastChildrenFetch<4000L)return;
        childrenBusy=true;lastChildrenFetch=System.currentTimeMillis();
        network.execute(()->{
            try{
                JSONObject result=Api.call(this,"GET","/v1/children",null,Prefs.token(this));
                runOnUiThread(()->{
                    childrenBusy=false;
                    familyChildren=result.optJSONArray("children");if(familyChildren==null)familyChildren=new JSONArray();
                    String existing=selectedChildId;
                    boolean exists=false;
                    for(int i=0;i<familyChildren.length();i++){
                        JSONObject child=familyChildren.optJSONObject(i);
                        if(child!=null && existing.equals(child.optString("childId"))){exists=true;break;}
                    }
                    if(!exists){
                        if(marker!=null && !familyMarkers.containsValue(marker)){marker.remove();marker=null;}
                        if(familyChildren.length()>0)selectedChildId=familyChildren.optJSONObject(0).optString("childId");
                        else selectedChildId=result.optString("familyId","");
                        lastRenderedPointId="";
                    }
                    if(map!=null){
                        Set<String> live=new HashSet<>();
                        for(int i=0;i<familyChildren.length();i++){
                            JSONObject child=familyChildren.optJSONObject(i);if(child==null)continue;
                            String id=child.optString("childId"),name=child.optString("childName","孩子");
                            JSONObject fix=child.optJSONObject("point");
                            if(id.isEmpty()||fix==null)continue;
                            double lat=fix.optDouble("lat",Double.NaN),lon=fix.optDouble("lon",Double.NaN);
                            String type=fix.optString("coordType","wgs84");
                            if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180||
                                    !("gcj02".equals(type)||"wgs84".equals(type)))continue;
                            live.add(id);
                            LatLng p=toAmapPoint(lat,lon,type);
                            Marker childMarker=familyMarkers.get(id);
                            if(childMarker==null){
                                childMarker=map.addMarker(new MarkerOptions().position(p).title(name)
                                    .icon(childDotIcon(id,id.equals(selectedChildId))).anchor(0.5f,0.5f));
                                childMarker.setObject(id);familyMarkers.put(id,childMarker);
                                markerVisualStates.put(id,childColor(id)+":"+
                                        (id.equals(selectedChildId)?"selected":"normal"));
                            }else{
                                childMarker.setPosition(p);childMarker.setTitle(name);
                                styleChildMarker(childMarker,id,id.equals(selectedChildId));
                            }
                            childMarker.setAlpha(System.currentTimeMillis()-fix.optLong("deviceTime",0)>60000L?0.55f:1f);
                        }
                        for(String id:new ArrayList<>(familyMarkers.keySet()))if(!live.contains(id)){
                            Marker stale=familyMarkers.remove(id);if(stale!=null)stale.remove();
                            markerVisualStates.remove(id);
                            if(id.equals(selectedChildId))marker=null;
                        }
                        marker=familyMarkers.get(selectedChildId);
                    }
                    rebuildChildTabs();
                    if(!locationOverlayChildId.isEmpty())refreshSelectedInfoWindow();
                    if(!exists)fetchLatest();
                });
            }catch(Exception e){runOnUiThread(()->{childrenBusy=false;if(childTabs!=null&&childTabs.getChildCount()==0)show("读取家庭设备列表失败："+e.getMessage());});}
        });
    }
    private void parent(){
        title("家庭守护");
        pairText=text("●  正在连接家庭设备…",13,Color.rgb(47,106,174),true);
        pairText.setPadding(dp(12),dp(10),dp(12),dp(10));
        pairText.setBackground(roundBg(Color.rgb(235,245,255),0,12));
        LinearLayout.LayoutParams pairLp=new LinearLayout.LayoutParams(-1,-2);
        pairLp.bottomMargin=dp(9);pairText.setLayoutParams(pairLp);add(pairText);
        HorizontalScrollView tabs=new HorizontalScrollView(this);tabs.setHorizontalScrollBarEnabled(false);
        childTabs=new LinearLayout(this);childTabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.addView(childTabs);LinearLayout.LayoutParams tabLp=new LinearLayout.LayoutParams(-1,dp(54));add(tabs);
        rebuildChildTabs();
        installLiveMap();
        LinearLayout locationCard=new LinearLayout(this);
        locationCard.setOrientation(LinearLayout.VERTICAL);
        locationCard.setPadding(dp(15),dp(12),dp(15),dp(13));
        locationCard.setBackground(gradientBg(Color.rgb(238,246,255),Color.WHITE,17));
        locationCard.setElevation(dp(1));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(13);
        locationCard.setLayoutParams(lp);
        addressStatus=text("⌖  "+resolvedAddress,15,Color.rgb(26,64,117),true);
        locationCard.addView(addressStatus);
        parentStatus=text("正在同步孩子位置…",13,Color.rgb(95,111,134),false);
        locationCard.addView(parentStatus);
        add(locationCard);
        actionRow("◎","回到位置","重新跟随孩子",true,()->{
                    followChild=true;
                    if(map!=null && latest!=null){
                        String type=latest.optString("coordType","wgs84");
                        if("wgs84".equals(type)||"gcj02".equals(type)){
                            double lat=latest.optDouble("lat",Double.NaN),lon=latest.optDouble("lon",Double.NaN);
                            if(Double.isFinite(lat)&&Double.isFinite(lon)){
                                map.animateCamera(CameraUpdateFactory.newLatLngZoom(toAmapPoint(lat,lon,type),17f));
                                mapCentered=true;
                            }
                        }
                    }
                },
                "◉","定位详情","精度、卫星与坐标",false,()->new AlertDialog.Builder(this)
                    .setTitle("定位数据与来源")
                    .setMessage("附近地址："+resolvedAddress+"\n"+
                            (latestDiagnostics.isEmpty()?"还没有收到有效位置":latestDiagnostics))
                    .setNeutralButton("外部地图",(d,w)->openMap())
                    .setPositiveButton("知道了",null).show());
        // The map overlay itself explains the position; no duplicate instruction strip.
        actionRow("▧","查看所有孩子","地图框选全部孩子",true,this::showAllChildren,
                "♧","设备管理","添加孩子及设备操作",false,this::showDeviceManagement);
        cardTitle("常用功能","");
        actionRow("⌁","历史轨迹","最近 1 / 6 / 24 小时",false,this::showHistoryPicker,
                "⬡","电子围栏","绘制与编辑安全区域",true,this::openFenceEditor);
        historyStatus=text("",12,Color.rgb(92,110,137),false);add(historyStatus);
        guardStatus=text("正在读取安全区域…",12,Color.rgb(92,110,137),false);add(guardStatus);
        TextView eventLink=text("查看全部安全事件  ›",14,Color.rgb(37,105,198),true);
        eventLink.setPadding(dp(8),dp(8),dp(8),dp(12));
        eventLink.setOnClickListener(v->showGuardEventHistory());add(eventLink);
        actionRow("▣","照片及音视频","发送协助请求",false,this::showAssistancePicker,
                "▤","查看媒体","已发送的照片与录音",false,this::downloadLatestMedia);
        mediaStatus=text("",12,Color.rgb(92,110,137),false);add(mediaStatus);
        cardTitle("家庭设置","");
        actionRow("♙","家庭成员","邀请监护人加入",false,this::showFamilyManagement,
                "↻","检查更新","安装最新版本",false,()->UpdateManager.check(this,true));
        actionRow("🔑","恢复密钥管理","仅创建家庭的家长可更换",false,this::rotateRecoveryKey,
                "♢","通知设置","检查 SOS 通知权限",false,()->{
                    if(Build.VERSION.SDK_INT>=33 &&
                       checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},401);
                    else startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:"+getPackageName())));
                });
        TextView note=text("安全提示：恢复密钥请单独保存；其他家庭可直接创建，无需平台邀请码。",12,
                Color.rgb(110,125,146),false);
        note.setPadding(dp(7),dp(8),dp(7),dp(7));add(note);
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
        i.putExtra("childId",selectedChildId);
        i.putExtra("childName",selectedChildName);
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
        fetchChildren();
        loading=true;
        final String target=selectedChildId;
        final String url=childPath("/v1/latest");
        network.execute(()->{
            try {JSONObject result=Api.call(this,"GET",url,null,Prefs.token(this));
                runOnUiThread(()->{
                    if(!target.equals(selectedChildId)){loading=false;return;}
                    latest=result.optJSONObject("point");
                    selectedBattery=result.optInt("battery",-1);
                    long serverNow=result.optLong("serverTime",System.currentTimeMillis());
                    if(latest==null){
                        // Other children's markers stay on the map even if this device has no fix.
                        marker=familyMarkers.get(selectedChildId);
                        if(accuracyCircle!=null){accuracyCircle.remove();accuracyCircle=null;}
                        lastRenderedPointId="";mapCentered=false;
                        addressedPoint=null;resolvedAddress="暂无有效位置";updateAddressDetails();
                    }
                    boolean paired=result.optBoolean("paired",false);
                    parentPaired=paired;
                    parentChildName=result.optString("childName","");
                    selectedChildName=parentChildName;
                    parentLastSeen=result.optLong("lastSeen",0L);
                    if(pairText!=null){
                        if(paired){Prefs.store(this).edit().remove("pair_code").remove("pair_expiry").apply();}
                        pairText.setText(paired?"当前查看："+result.optString("childName"):displayedPairState());
                    }
                    if(parentStatus!=null){
                        long seen=result.optLong("lastSeen",0),age=Math.max(0,serverNow-seen);
                        String state=seen==0?"尚未收到设备心跳":age<150000?"网络在线（最近 150 秒有心跳）":"网络离线（最近没有心跳）";
                        if(latest==null)parentStatus.setText("网络状态："+state+"\n暂无位置记录");
                        else {
                            long ageOfFix=Math.max(0,Math.max(serverNow-latest.optLong("receivedAt",serverNow),
                                    serverNow-latest.optLong("deviceTime",0)));
                            String freshness=ageOfFix<=10000?"实时（约 "+(ageOfFix/1000)+" 秒延迟）":ageOfFix<=30000?"延迟（约 "+(ageOfFix/1000)+" 秒）":
                                    "最后已知位置（"+(ageOfFix/60000)+" 分钟前），非实时";
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
                        // A marker must become visibly stale even when no new GPS point arrives.
                        long positionAge=Math.max(0,serverNow-latest.optLong("deviceTime",0));
                        if(marker!=null){
                            marker.setTitle(positionAge>60000L?"最后已知位置（非实时）":"孩子最新位置");
                            marker.setAlpha(positionAge>60000L?0.55f:1.0f);
                        }
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
                    retryMissingAddressForSelectedChild();
                    refreshSelectedInfoWindow();
                    loading=false;
                    if(System.currentTimeMillis()-lastGuardLoad>7000){lastGuardLoad=System.currentTimeMillis();loadGuard();loadMediaList();}
                });
            }catch(Exception ex){runOnUiThread(()->{loading=false;if(parentStatus!=null)parentStatus.setText("无法连接服务器："+ex.getMessage());});}
        });
    }
    private void loadHistory(int hours){
        final String url=childPath("/v1/history?hours="+hours);
        request(()->Api.call(this,"GET",url,null,Prefs.token(this)),r->{
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
    private void openMapForChild(String childId){
        JSONObject fix=pointForChild(childId);
        if(fix==null){show("还没有位置记录");return;}
        double lat=fix.optDouble("lat",Double.NaN),lon=fix.optDouble("lon",Double.NaN);
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180){
            show("位置坐标无效");return;
        }
        String coords="gcj02".equals(fix.optString("coordType"))?"gaode":"wgs84";
        JSONObject row=childRow(childId);
        String name=row==null?"家庭守护":row.optString("childName","家庭守护");
        String url=String.format(Locale.US,
                "https://uri.amap.com/marker?position=%.7f,%.7f&name=%s&coordinate=%s&callnative=1",
                lon,lat,Uri.encode(name),coords);
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
        catch(Exception ex){show("无法打开地图："+ex.getMessage());}
    }
    private void openMap(){openMapForChild(selectedChildId);}
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
    private void showDeviceManagement(){
        request(()->Api.call(this,"GET","/v1/children",null,Prefs.token(this)),r->{
            JSONArray children=r.optJSONArray("children");
            List<String> options=new ArrayList<>();List<String> ids=new ArrayList<>();
            if(children!=null)for(int i=0;i<children.length();i++){
                JSONObject c=children.optJSONObject(i);if(c==null)continue;
                options.add("查看 " + c.optString("childName","孩子") + (c.optString("childId").equals(selectedChildId)?"  ✓":""));
                ids.add(c.optString("childId"));
            }
            options.add("＋ 添加孩子设备（最多8台）");
            new AlertDialog.Builder(this).setTitle("家庭设备管理 · "+ids.size()+" 台")
                    .setItems(options.toArray(new String[0]),(d,which)->{
                        if(which<ids.size()){selectChild(ids.get(which));return;}
                        request(()->Api.call(this,"POST","/v1/pair",new JSONObject(),Prefs.token(this)),result->{
                            String code=result.optString("pairCode");saveDisplayedPairCode(code);
                            new AlertDialog.Builder(this).setTitle("新孩子的配对码")
                                .setMessage("请在新孩子手机输入："+code+"\n10分钟内有效，加入后会自动显示在家庭地图上。")
                                .setPositiveButton("知道了",null).show();
                        });
                    }).setNegativeButton("取消",null).show();
        });
    }
    private void rotateRecoveryKey(){
        new AlertDialog.Builder(this).setTitle("更换家庭恢复密钥")
            .setMessage("新密钥生成后旧密钥立即失效。只有家庭创建者能操作；务必另行备份新密钥。")
            .setNegativeButton("取消",null)
            .setPositiveButton("生成新密钥",(d,w)->request(
                ()->Api.call(this,"POST","/v1/recovery-key",new JSONObject(),Prefs.token(this)),r->{
                    showRecoveryKey(r.getString("recoveryKey"));
                })).show();
    }
    private void showFamilyManagement(){
        request(()->Api.call(this,"GET","/v1/members",null,Prefs.token(this)),r->{
            JSONArray members=r.optJSONArray("members");boolean owner=r.optBoolean("owner",false);
            StringBuilder list=new StringBuilder("家庭："+r.optString("familyName","我的家庭")+"\n家庭管理员：1人");
            if(members!=null)for(int i=0;i<members.length();i++){
                JSONObject m=members.optJSONObject(i);if(m!=null)list.append("\n监护人：").append(m.optString("name"));
            }
            AlertDialog.Builder dlg=new AlertDialog.Builder(this).setTitle("家庭成员")
                .setMessage(list.toString()).setNegativeButton("关闭",null);
            if(owner)dlg.setPositiveButton("邀请监护人",(d,w)->{
                request(()->Api.call(this,"POST","/v1/members/invite",new JSONObject(),Prefs.token(this)),inv->{
                    String code=inv.optString("inviteCode");
                    new AlertDialog.Builder(this).setTitle("监护人邀请码")
                        .setMessage("10分钟有效，发给需要加入的家长：\n"+code)
                        .setPositiveButton("复制",(dd,ww)->{
                            ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                                .setPrimaryClip(ClipData.newPlainText("监护人邀请码",code));
                        }).setNegativeButton("关闭",null).show();
                });
            });
            dlg.show();
        });
    }
    private void acknowledgeGuardEvent(String id){acknowledgeGuardEvent(id,selectedChildId);}
    private void acknowledgeGuardEvent(String id,String childId){
        final String url="/v1/guard/ack"+(childId==null||childId.isEmpty()?"":"?childId="+Uri.encode(childId));
        request(()->Api.call(this,"POST",url,new JSONObject().put("id",id),Prefs.token(this)),
            r->{show("已确认知晓安全事件");loadGuard();});
    }
    private void showGuardEventHistory(){
        request(()->Api.call(this,"GET","/v1/guard/events",null,Prefs.token(this)),r->{
            JSONArray all=r.optJSONArray("events");
            if(all==null||all.length()==0){show("暂无安全事件");return;}
            int n=Math.min(40,all.length());
            String[] lines=new String[n];JSONObject[] items=new JSONObject[n];String[] childIDs=new String[n];
            for(int i=0;i<n;i++){
                JSONObject entry=all.optJSONObject(i);if(entry==null){lines[i]="未知记录";continue;}
                JSONObject event=entry.optJSONObject("event");items[i]=event;
                childIDs[i]=entry.optString("childId","");
                if(event==null){lines[i]="未知记录";continue;}
                String kind=event.optString("kind");
                String status=GuardEventPolicy.isAlertKind(kind)?
                    (event.optLong("acknowledgedAt")>0?"[已确认] ":"[待确认] "):"";
                lines[i]=status+entry.optString("childName","孩子")+" · "+time(event.optLong("time"))+"\n"+
                    event.optString("message",kind);
            }
            new AlertDialog.Builder(this).setTitle("全部孩子的安全事件 · 最近 "+n+" 条")
                    .setItems(lines,(d,which)->{
                        JSONObject event=items[which];if(event==null)return;
                        AlertDialog.Builder detail=new AlertDialog.Builder(this).setTitle("安全事件详情")
                            .setMessage(lines[which]);
                        if(GuardEventPolicy.isAlertKind(event.optString("kind"))&&event.optLong("acknowledgedAt")==0){
                            detail.setNegativeButton("稍后处理",null)
                                .setPositiveButton("确认已知晓",(dd,ww)->acknowledgeGuardEvent(event.optString("id"),childIDs[which]));
                        }else detail.setPositiveButton("返回",null);
                        detail.show();
                    }).setNegativeButton("关闭",null).show();
        });
    }
    private void loadFamilyEvents(){
        if(!started||!"parent".equals(Prefs.role(this))||familyEventsBusy||
                System.currentTimeMillis()-lastFamilyEventFetch<5000L)return;
        lastFamilyEventFetch=System.currentTimeMillis();familyEventsBusy=true;
        network.execute(()->{
            try{
                JSONObject result=Api.call(this,"GET","/v1/guard/events",null,Prefs.token(this));
                runOnUiThread(()->{
                    familyEventsBusy=false;
                    JSONArray all=result.optJSONArray("events");if(all==null)return;
                    List<JSONObject> entries=new ArrayList<>();
                    for(int i=0;i<all.length();i++){
                        JSONObject entry=all.optJSONObject(i);if(entry==null)continue;
                        JSONObject event=entry.optJSONObject("event");
                        if(event!=null && event.optLong("acknowledgedAt",0)==0)entries.add(entry);
                    }
                    entries.sort((a,b)->{
                        JSONObject x=a.optJSONObject("event"),y=b.optJSONObject("event");
                        int diff=Integer.compare(GuardEventPolicy.priority(y==null?"":y.optString("kind")),
                                GuardEventPolicy.priority(x==null?"":x.optString("kind")));
                        return diff!=0?diff:Long.compare(y==null?0:y.optLong("time"),x==null?0:x.optLong("time"));
                    });
                    boolean dialogReserved=false;
                    for(JSONObject entry:entries){
                        JSONObject raw=entry.optJSONObject("event");if(raw==null)continue;
                        String childId=entry.optString("childId"),childName=entry.optString("childName","孩子");
                        try{
                            JSONObject event=new JSONObject(raw.toString());
                            event.put("message","【"+childName+"】"+raw.optString("message","安全事件"));
                            boolean allowed=!dialogReserved && isWindowFocusedForAlert();
                            boolean presented=ParentAlertNotifier.handle(this,event,allowed,
                                    id->acknowledgeGuardEvent(id,childId));
                            if(allowed&&presented)dialogReserved=true;
                        }catch(org.json.JSONException e){android.util.Log.w("FamilyGuard","Cannot parse family event",e);}
                    }
                });
            }catch(Exception e){runOnUiThread(()->{familyEventsBusy=false;android.util.Log.w("FamilyGuard","Family event refresh failed",e);});}
        });
    }
    private boolean isWindowFocusedForAlert(){return !isFinishing() && hasWindowFocus();}
    private void loadGuard(){
        if(!started||!"parent".equals(Prefs.role(this)))return;
        final String url=childPath("/v1/guard");
        final String target=selectedChildId;
        request(()->Api.call(this,"GET",url,null,Prefs.token(this)),r->{
            if(!target.equals(selectedChildId))return;
            JSONObject f=r.optJSONObject("fence");
            org.json.JSONArray events=r.optJSONArray("events");
            StringBuilder b=new StringBuilder(f==null?"电子围栏：未开启":
                    "polygon".equals(f.optString("shape"))?"电子围栏：自定义地图区域已开启":
                            "电子围栏：已开启，半径 "+f.optInt("radius")+" 米");
            if(events!=null && events.length()>0){
                JSONObject last=events.optJSONObject(events.length()-1);
                if(last!=null){b.append("\n最新提醒：").append(last.optString("message")).append(" · ").append(time(last.optLong("time")));
                    String id=last.optString("id");
                    lastGuardEvent=id;
                }
            }
            // New V7.3: alerts are aggregated across ALL children by loadFamilyEvents().
            if(!r.optBoolean("pushConfigured",false))
                b.append("\n后台推送：尚未配置。家长手机锁屏时不保证收到提醒，请勿仅依赖本 App 求助。");
            else b.append("\n后台通知网关：已配置（是否送达仍需真机验证）");
            if(guardStatus!=null)guardStatus.setText(b.toString());
        });
    }
    private void askMedia(String kind){
        String label="photo".equals(kind)?"拍照":"audio".equals(kind)?"录音":"录像";
        final String url=childPath("/v1/media/request");
        new AlertDialog.Builder(this).setTitle("请求孩子"+label+"？")
            .setMessage("孩子手机必须主动确认；不会秘密拍摄或录音。上传成功后家长可查看 24 小时。")
            .setNegativeButton("取消",null)
            .setPositiveButton("发送请求",(d,w)->request(()->Api.call(this,"POST",url,
                new JSONObject().put("kind",kind),Prefs.token(this)),r->{show("请求已发送，等待孩子确认");loadMediaList();})).show();
    }
    private void loadMediaList(){
        if(!started||!"parent".equals(Prefs.role(this)))return;
        final String target=selectedChildId;
        final String url=childPath("/v1/media/list");
        request(()->Api.call(this,"GET",url,null,Prefs.token(this)),r->{
            if(!target.equals(selectedChildId))return;
            org.json.JSONArray entries=r.optJSONArray("media");
            if(entries==null||entries.length()==0){if(mediaStatus!=null)mediaStatus.setText("尚无媒体请求");return;}
            JSONObject item=entries.optJSONObject(entries.length()-1);
            if(item!=null&&mediaStatus!=null)mediaStatus.setText("最新媒体："+item.optString("kind")+" / "+item.optString("status")+
                "，时间 "+time(item.optLong("createdAt")));
        });
    }
    private void downloadLatestMedia(){
        final String target=selectedChildId;
        final String url=childPath("/v1/media/list");
        network.execute(()->{
            try{
                JSONObject r=Api.call(this,"GET",url,null,Prefs.token(this));
                org.json.JSONArray list=r.optJSONArray("media");JSONObject chosen=null;
                if(list!=null)for(int i=list.length()-1;i>=0;i--){JSONObject item=list.optJSONObject(i);
                    if(item!=null&&"ready".equals(item.optString("status"))){chosen=item;break;}}
                if(chosen==null){runOnUiThread(()->show("还没有孩子确认并上传的媒体"));return;}
                String id=chosen.getString("id"),mime=chosen.optString("mime");
                String ext="image/jpeg".equals(mime)?".jpg":"audio/mp4".equals(mime)?".m4a":"video/3gpp".equals(mime)?".3gp":".mp4";
                File folder=new File(getCacheDir(),"received");if(!folder.exists()&&!folder.mkdirs())throw new IOException("无法创建缓存目录");
                File file=new File(folder,id+ext);
                Api.download(this,id,file,target);
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
