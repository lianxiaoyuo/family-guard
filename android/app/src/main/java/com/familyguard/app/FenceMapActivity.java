package com.familyguard.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.model.Circle;
import com.amap.api.maps.model.CircleOptions;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.Polygon;
import com.amap.api.maps.model.PolygonOptions;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Parent-managed safe area: adjustable circle or custom polygon drawn on the AMap. */
public final class FenceMapActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<LatLng> vertices=new ArrayList<>();
    private final List<Marker> vertexMarkers=new ArrayList<>();
    private MapView mapView;
    private AMap map;
    private String childQuery(){
        String id=getIntent().getStringExtra("childId");
        return id==null||id.isEmpty()?"":"?childId="+Uri.encode(id);
    }
    private Circle circle;
    private Polygon polygon;
    private Marker centerMarker;
    private LatLng center;
    private TextView instruction, radiusLabel;
    private LinearLayout radiusPanel, editPanel;
    // FG_V7_FENCE_UI_PATCH: visual refresh only, existing map/data/event behavior preserved.
    private Button circleMode, polygonMode, undo, clear, save;
    private SeekBar radiusSlider;
    private int radiusMeters=200;
    private boolean polygonSelected=false, busy=false, editorTouched=false;

    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable background(int fill,int border){
        GradientDrawable d=new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(18));
        if(border!=0)d.setStroke(dp(1),border);return d;
    }
    private TextView text(String value,int size,boolean strong){
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size);
        t.setTextColor(Color.rgb(26,45,70));
        if(strong)t.setTypeface(null,Typeface.BOLD);
        return t;
    }
    private Button button(String value){
        Button b=new Button(this);
        b.setText(value);b.setAllCaps(false);b.setTextSize(13);
        b.setTypeface(null,Typeface.BOLD);
        b.setPadding(dp(8),0,dp(8),0);
        b.setBackground(background(Color.WHITE,Color.rgb(224,233,246)));
        b.setTextColor(Color.rgb(28,73,142));
        b.setElevation(dp(1));return b;
    }
    private void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_LONG).show();}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        if(!"parent".equals(Prefs.role(this))){finish();return;}
        if(!AmapPrivacy.available()||!AmapPrivacy.agreed(this)){
            toast("请先在家长端启用高德地图");finish();return;
        }
        try{AmapPrivacy.initialize(this);}
        catch(RuntimeException ex){toast("高德地图初始化失败");finish();return;}

        getWindow().setStatusBarColor(Color.rgb(248,250,254));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        LinearLayout page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16),dp(11),dp(16),dp(14));
        page.setBackgroundColor(Color.rgb(248,250,254));
        setContentView(page);

        LinearLayout header=new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(header,new LinearLayout.LayoutParams(-1,dp(42)));
        String childName=getIntent().getStringExtra("childName");
        TextView title=text(childName==null||childName.isEmpty()?"电子围栏":childName+" · 电子围栏",21,true);
        title.setTextColor(Color.rgb(22,45,81));
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView badge=text("家长管理",11,true);
        badge.setPadding(dp(12),dp(6),dp(12),dp(6));
        badge.setTextColor(Color.rgb(34,98,177));
        badge.setBackground(background(Color.rgb(231,242,255),0));
        header.addView(badge);
        TextView lead=text("在地图上直接划定孩子的安全范围",13,false);
        lead.setTextColor(Color.rgb(103,120,143));
        LinearLayout.LayoutParams leadLp=new LinearLayout.LayoutParams(-1,-2);
        leadLp.bottomMargin=dp(11);page.addView(lead,leadLp);

        // Compact segmented selector; no coordinate text fields.
        LinearLayout modes=new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setPadding(dp(4),dp(4),dp(4),dp(4));
        modes.setBackground(background(Color.rgb(232,240,251),0));
        LinearLayout.LayoutParams modesLp=new LinearLayout.LayoutParams(-1,dp(55));
        modesLp.bottomMargin=dp(10);page.addView(modes,modesLp);
        circleMode=button("◯  圆形围栏");polygonMode=button("⬡  多边形围栏");
        LinearLayout.LayoutParams m1=new LinearLayout.LayoutParams(0,-1,1);
        m1.rightMargin=dp(4);modes.addView(circleMode,m1);
        modes.addView(polygonMode,new LinearLayout.LayoutParams(0,-1,1));
        circleMode.setOnClickListener(v->{editorTouched=true;switchMode(false);});
        polygonMode.setOnClickListener(v->{editorTouched=true;switchMode(true);});

        instruction=text("",12,false);
        instruction.setTextColor(Color.rgb(70,92,121));
        instruction.setPadding(dp(13),dp(10),dp(13),dp(10));
        instruction.setBackground(background(Color.rgb(236,245,255),Color.rgb(221,233,249)));
        LinearLayout.LayoutParams guideLp=new LinearLayout.LayoutParams(-1,-2);
        guideLp.bottomMargin=dp(10);page.addView(instruction,guideLp);

        // The map receives all gestures and remains the primary focus of the screen.
        FrameLayout mapPanel=new FrameLayout(this);
        mapPanel.setBackground(background(Color.WHITE,Color.rgb(224,233,246)));
        mapPanel.setElevation(dp(2));
        mapPanel.setClipToOutline(true);
        LinearLayout.LayoutParams mapLp=new LinearLayout.LayoutParams(-1,0,1f);
        mapLp.bottomMargin=dp(11);mapPanel.setMinimumHeight(dp(200));
        page.addView(mapPanel,mapLp);
        mapView=new MapView(this);
        mapPanel.addView(mapView,new FrameLayout.LayoutParams(-1,-1));
        mapView.onCreate(state);map=mapView.getMap();
        map.getUiSettings().setAllGesturesEnabled(true);
        map.getUiSettings().setZoomControlsEnabled(true);
        map.getUiSettings().setScaleControlsEnabled(true);
        map.setOnMapClickListener(this::tapMap);
        map.setOnMarkerDragListener(new AMap.OnMarkerDragListener(){
            @Override public void onMarkerDragStart(Marker m){}
            @Override public void onMarkerDrag(Marker m){refreshFromDrag(m);}
            @Override public void onMarkerDragEnd(Marker m){refreshFromDrag(m);}
        });

        radiusPanel=new LinearLayout(this);
        radiusPanel.setOrientation(LinearLayout.VERTICAL);
        radiusPanel.setPadding(dp(12),dp(10),dp(12),dp(4));
        radiusPanel.setBackground(background(Color.rgb(246,250,255),Color.rgb(220,232,249)));
        LinearLayout.LayoutParams radiusLp=new LinearLayout.LayoutParams(-1,-2);
        radiusLp.bottomMargin=dp(10);page.addView(radiusPanel,radiusLp);
        radiusLabel=text("范围半径 · 200 米",14,true);
        radiusLabel.setTextColor(Color.rgb(33,74,130));radiusPanel.addView(radiusLabel);
        radiusSlider=new SeekBar(this);radiusSlider.setMax(192);radiusSlider.setProgress(12);
        radiusSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar s,int progress,boolean fromUser){
                radiusMeters=80+progress*10;
                radiusLabel.setText(String.format(Locale.CHINA,"范围半径 · %d 米",radiusMeters));
                if(circle!=null)circle.setRadius(radiusMeters);
            }
            @Override public void onStartTrackingTouch(SeekBar s){}
            @Override public void onStopTrackingTouch(SeekBar s){}
        });
        radiusPanel.addView(radiusSlider);

        editPanel=new LinearLayout(this);
        editPanel.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams editLp=new LinearLayout.LayoutParams(-1,dp(46));
        editLp.bottomMargin=dp(10);page.addView(editPanel,editLp);
        undo=button("↶  撤销顶点");clear=button("⌫  重新绘制");
        LinearLayout.LayoutParams e1=new LinearLayout.LayoutParams(0,-1,1);
        e1.rightMargin=dp(8);editPanel.addView(undo,e1);
        editPanel.addView(clear,new LinearLayout.LayoutParams(0,-1,1));
        undo.setOnClickListener(v->{editorTouched=true;if(vertices.size()>0){vertices.remove(vertices.size()-1);redrawPolygon();}});
        clear.setOnClickListener(v->{editorTouched=true;vertices.clear();redrawPolygon();});

        LinearLayout actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(actions,new LinearLayout.LayoutParams(-1,dp(53)));
        Button back=button("返回");Button remove=button("关闭围栏");
        save=button("保存并启用");
        LinearLayout.LayoutParams backLp=new LinearLayout.LayoutParams(0,-1,0.8f);
        backLp.rightMargin=dp(7);actions.addView(back,backLp);
        LinearLayout.LayoutParams removeLp=new LinearLayout.LayoutParams(0,-1,1.12f);
        removeLp.rightMargin=dp(7);actions.addView(remove,removeLp);
        actions.addView(save,new LinearLayout.LayoutParams(0,-1,1.5f));
        remove.setTextColor(Color.rgb(160,68,68));
        save.setTextColor(Color.WHITE);
        save.setBackground(background(Color.rgb(36,106,213),0));
        save.setElevation(dp(3));
        back.setOnClickListener(v->finish());
        remove.setOnClickListener(v->new AlertDialog.Builder(this)
                .setTitle("关闭安全区域？")
                .setMessage("关闭后将不再根据这块区域发送进出提醒。")
                .setNegativeButton("取消",null)
                .setPositiveButton("确认关闭",(d,w)->submit(false)).show());
        save.setOnClickListener(v->submit(true));
        switchMode(false);

        double lat=getIntent().getDoubleExtra("lat",Double.NaN);
        double lon=getIntent().getDoubleExtra("lon",Double.NaN);
        if(Double.isFinite(lat)&&Double.isFinite(lon)&&Math.abs(lat)<=90&&Math.abs(lon)<=180){
            String type=getIntent().getStringExtra("coordType");
            if("wgs84".equals(type)){
                double[] gcj=CoordinateReference.wgsToGcj(lat,lon);lat=gcj[0];lon=gcj[1];
            }else if(!"gcj02".equals(type)){lat=Double.NaN;}
            if(Double.isFinite(lat)){
                LatLng latest=new LatLng(lat,lon);
                chooseCenter(latest);map.moveCamera(CameraUpdateFactory.newLatLngZoom(latest,16));
            }
        }
        loadExistingFence();
    }
    // Parent can open the editor and modify the previously saved safety boundary.
    private void loadExistingFence(){
        io.execute(()->{
            try{
                JSONObject resp=Api.call(this,"GET","/v1/guard"+childQuery(),null,Prefs.token(this));
                JSONObject f=resp.optJSONObject("fence");
                if(f==null)return;
                main.post(()->{
                    if(editorTouched || busy)return;
                    String shape=f.optString("shape","circle");
                    if("polygon".equals(shape)){
                        JSONArray pts=f.optJSONArray("points");if(pts==null)return;
                        List<LatLng> v=new ArrayList<>();
                        for(int i=0;i<pts.length();i++){
                            JSONObject p=pts.optJSONObject(i);if(p==null)continue;
                            double[] converted=CoordinateReference.wgsToGcj(p.optDouble("lat"),p.optDouble("lon"));
                            if(Double.isFinite(converted[0])&&Double.isFinite(converted[1]))v.add(new LatLng(converted[0],converted[1]));
                        }
                        if(v.size()<3 || v.size()>32)return;
                        vertices.clear();vertices.addAll(v);switchMode(true);redrawPolygon();
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(v.get(0),15f));
                    }else{
                        double lat=f.optDouble("lat",Double.NaN),lon=f.optDouble("lon",Double.NaN);
                        if(!Double.isFinite(lat)||!Double.isFinite(lon))return;
                        double[] gcj=CoordinateReference.wgsToGcj(lat,lon);
                        int radius=f.optInt("radius",200);
                        radiusSlider.setProgress(Math.max(0,Math.min(192,(radius-80)/10)));
                        switchMode(false);chooseCenter(new LatLng(gcj[0],gcj[1]));
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(gcj[0],gcj[1]),16f));
                    }
                });
            }catch(Exception ignored){ /* Empty/offline fences can still be created. */ }
        });
    }
    private void switchMode(boolean poly){
        polygonSelected=poly;
        circleMode.setBackground(background(poly?Color.rgb(233,241,251):Color.rgb(37,105,220),0));
        circleMode.setTextColor(poly?Color.rgb(25,81,160):Color.WHITE);
        polygonMode.setBackground(background(poly?Color.rgb(37,105,220):Color.rgb(233,241,251),0));
        polygonMode.setTextColor(poly?Color.WHITE:Color.rgb(25,81,160));
        radiusPanel.setVisibility(poly?View.GONE:View.VISIBLE);
        editPanel.setVisibility(poly?View.VISIBLE:View.GONE);
        if(circle!=null)circle.setVisible(!poly);
        if(centerMarker!=null)centerMarker.setVisible(!poly);
        if(polygon!=null)polygon.setVisible(poly);
        for(Marker m:vertexMarkers)m.setVisible(poly);
        updateInstruction();
    }
    private void updateInstruction(){
        if(polygonSelected){
            instruction.setText("点地图添加边界点（3–32 个），拖动顶点微调。已绘制 "+vertices.size()+" 个点。");
        } else instruction.setText("点地图确定圆心，拖动圆点微调；使用下方滑块调整半径。");
    }
    private void tapMap(LatLng point){
        if(point==null)return;
        editorTouched=true;
        if(polygonSelected){
            if(vertices.size()>=32){toast("最多允许 32 个边界点");return;}
            vertices.add(point);redrawPolygon();
        } else chooseCenter(point);
    }
    private void chooseCenter(LatLng point){
        center=point;
        if(centerMarker==null)centerMarker=map.addMarker(new MarkerOptions().position(point).title("安全区域中心").draggable(true));
        else centerMarker.setPosition(point);
        if(circle==null)circle=map.addCircle(new CircleOptions().center(point).radius(radiusMeters)
                .strokeWidth(dp(2)).strokeColor(Color.rgb(32,99,214))
                .fillColor(Color.argb(45,32,99,214)));
        else {circle.setCenter(point);circle.setRadius(radiusMeters);}
        circle.setVisible(!polygonSelected);centerMarker.setVisible(!polygonSelected);
        updateInstruction();
    }
    private void refreshFromDrag(Marker marker){
        editorTouched=true;
        Object value=marker.getObject();
        if(value instanceof Integer && polygonSelected){
            int index=((Integer)value).intValue();
            if(index>=0&&index<vertices.size()){
                vertices.set(index,marker.getPosition());updatePolygonShape();
            }
        } else if(marker==centerMarker && !polygonSelected){
            center=marker.getPosition();if(circle!=null)circle.setCenter(center);
        }
    }
    private void redrawPolygon(){
        for(Marker m:vertexMarkers)m.remove();vertexMarkers.clear();
        for(int i=0;i<vertices.size();i++){
            Marker m=map.addMarker(new MarkerOptions().position(vertices.get(i))
                    .title("边界点 "+(i+1)).draggable(true));
            m.setObject(Integer.valueOf(i));vertexMarkers.add(m);
        }
        updatePolygonShape();updateInstruction();
    }
    private void updatePolygonShape(){
        if(polygon!=null){polygon.remove();polygon=null;}
        if(vertices.size()>=3){
            polygon=map.addPolygon(new PolygonOptions().addAll(vertices)
                    .strokeWidth(dp(3)).strokeColor(Color.rgb(30,106,223))
                    .fillColor(Color.argb(60,29,106,222)));
            polygon.setVisible(polygonSelected);
        }
    }
    private void submit(boolean enabled){
        if(busy)return;
        if(enabled && !polygonSelected && center==null){toast("请先点选安全区域中心");return;}
        if(enabled && polygonSelected && vertices.size()<3){toast("自定义围栏至少需要 3 个边界点");return;}
        busy=true;save.setEnabled(false);
        JSONObject data=new JSONObject();
        try{
            data.put("enabled",enabled);
            if(enabled && polygonSelected){
                data.put("shape","polygon");
                JSONArray points=new JSONArray();
                for(LatLng p:vertices){
                    // Convert SDK GCJ-02 to server WGS84 before storing any vertex.
                    double[] wgs=CoordinateReference.gcjToWgs(p.latitude,p.longitude);
                    points.put(new JSONObject().put("lat",wgs[0]).put("lon",wgs[1]));
                }
                data.put("points",points);
            }else if(enabled){
                double[] wgs=CoordinateReference.gcjToWgs(center.latitude,center.longitude);
                data.put("shape","circle");data.put("lat",wgs[0]).put("lon",wgs[1]);
                data.put("radius",radiusMeters);
            }
        }catch(Exception ex){busy=false;save.setEnabled(true);toast("区域数据无效");return;}
        io.execute(()->{
            try{
                Api.call(this,"POST","/v1/fence"+childQuery(),data,Prefs.token(this));
                main.post(()->{toast(enabled?"安全区域已保存，进出提醒已启用":"安全区域已关闭");finish();});
            }catch(Exception ex){
                main.post(()->{busy=false;save.setEnabled(true);toast("保存失败："+ex.getMessage());});
            }
        });
    }
    @Override protected void onResume(){super.onResume();if(mapView!=null)mapView.onResume();}
    @Override protected void onPause(){if(mapView!=null)mapView.onPause();super.onPause();}
    @Override public void onLowMemory(){super.onLowMemory();if(mapView!=null)mapView.onLowMemory();}
    @Override protected void onSaveInstanceState(Bundle s){if(mapView!=null)mapView.onSaveInstanceState(s);super.onSaveInstanceState(s);}
    @Override protected void onDestroy(){if(mapView!=null)mapView.onDestroy();io.shutdownNow();super.onDestroy();}
}
