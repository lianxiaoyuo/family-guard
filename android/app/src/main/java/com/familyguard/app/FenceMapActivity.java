package com.familyguard.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
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
    private Circle circle;
    private Polygon polygon;
    private Marker centerMarker;
    private LatLng center;
    private TextView instruction, radiusLabel;
    private Button circleMode, polygonMode, undo, clear, save;
    private SeekBar radiusSlider;
    private int radiusMeters=200;
    private boolean polygonSelected=false, busy=false, editorTouched=false;

    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable background(int fill,int border){
        GradientDrawable d=new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(16));
        if(border!=0)d.setStroke(dp(1),border);return d;
    }
    private TextView text(String value,int size,boolean strong){
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size);
        t.setTextColor(Color.rgb(26,45,70));
        if(strong)t.setTypeface(null,Typeface.BOLD);
        return t;
    }
    private Button button(String value){
        Button b=new Button(this);b.setText(value);b.setAllCaps(false);b.setTextSize(14);
        b.setBackground(background(Color.rgb(233,241,251),0));
        b.setTextColor(Color.rgb(25,81,160));return b;
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

        LinearLayout page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(14),dp(12),dp(14),dp(12));
        page.setBackgroundColor(Color.rgb(246,249,254));setContentView(page);

        TextView title=text("创建安全区域",23,true);page.addView(title);
        TextView lead=text("在地图上设定家、学校或其他安全范围，无需输入经纬度。",13,false);
        lead.setTextColor(Color.rgb(98,113,136));lead.setPadding(0,dp(5),0,dp(10));page.addView(lead);

        LinearLayout modes=new LinearLayout(this);modes.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(modes);
        circleMode=button("圆形区域");polygonMode=button("自定义画线");
        LinearLayout.LayoutParams m1=new LinearLayout.LayoutParams(0,dp(45),1);m1.rightMargin=dp(6);
        modes.addView(circleMode,m1);
        modes.addView(polygonMode,new LinearLayout.LayoutParams(0,dp(45),1));
        circleMode.setOnClickListener(v->{editorTouched=true;switchMode(false);});
        polygonMode.setOnClickListener(v->{editorTouched=true;switchMode(true);});

        instruction=text("",14,false);instruction.setPadding(0,dp(9),0,dp(9));page.addView(instruction);

        mapView=new MapView(this);
        LinearLayout.LayoutParams mapLp=new LinearLayout.LayoutParams(-1,0,1f);
        mapLp.bottomMargin=dp(10);page.addView(mapView,mapLp);
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

        radiusLabel=text("半径 200 米",15,true);page.addView(radiusLabel);
        radiusSlider=new SeekBar(this);radiusSlider.setMax(192);radiusSlider.setProgress(12);
        radiusSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar s,int progress,boolean fromUser){
                radiusMeters=80+progress*10;radiusLabel.setText(String.format(Locale.CHINA,"半径 %d 米",radiusMeters));
                if(circle!=null)circle.setRadius(radiusMeters);
            }
            @Override public void onStartTrackingTouch(SeekBar s){}
            @Override public void onStopTrackingTouch(SeekBar s){}
        });
        page.addView(radiusSlider);

        LinearLayout edits=new LinearLayout(this);edits.setOrientation(LinearLayout.HORIZONTAL);page.addView(edits);
        undo=button("撤销上一个点");clear=button("清空重画");
        LinearLayout.LayoutParams e1=new LinearLayout.LayoutParams(0,dp(46),1);e1.rightMargin=dp(6);
        edits.addView(undo,e1);edits.addView(clear,new LinearLayout.LayoutParams(0,dp(46),1));
        undo.setOnClickListener(v->{editorTouched=true;if(vertices.size()>0){vertices.remove(vertices.size()-1);redrawPolygon();}});
        clear.setOnClickListener(v->{editorTouched=true;vertices.clear();redrawPolygon();});

        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);page.addView(actions);
        Button back=button("返回");Button remove=button("关闭围栏");
        LinearLayout.LayoutParams a1=new LinearLayout.LayoutParams(0,dp(48),1);a1.rightMargin=dp(6);
        actions.addView(back,a1);actions.addView(remove,new LinearLayout.LayoutParams(0,dp(48),1));
        back.setOnClickListener(v->finish());
        remove.setOnClickListener(v->new AlertDialog.Builder(this)
                .setTitle("关闭安全区域？").setMessage("关闭后不会再收到此区域的进入或离开提醒。")
                .setNegativeButton("取消",null).setPositiveButton("确认关闭",(d,w)->submit(false)).show());
        save=button("保存安全区域");
        save.setTextColor(Color.WHITE);save.setTypeface(null,Typeface.BOLD);
        save.setBackground(background(Color.rgb(32,99,214),0));
        LinearLayout.LayoutParams saveLp=new LinearLayout.LayoutParams(-1,dp(55));saveLp.topMargin=dp(8);
        page.addView(save,saveLp);save.setOnClickListener(v->submit(true));
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
                JSONObject resp=Api.call(this,"GET","/v1/guard",null,Prefs.token(this));
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
        radiusLabel.setVisibility(poly?View.GONE:View.VISIBLE);
        radiusSlider.setVisibility(poly?View.GONE:View.VISIBLE);
        undo.setVisibility(poly?View.VISIBLE:View.GONE);
        clear.setVisibility(poly?View.VISIBLE:View.GONE);
        if(circle!=null)circle.setVisible(!poly);
        if(centerMarker!=null)centerMarker.setVisible(!poly);
        if(polygon!=null)polygon.setVisible(poly);
        for(Marker m:vertexMarkers)m.setVisible(poly);
        updateInstruction();
    }
    private void updateInstruction(){
        if(polygonSelected){
            instruction.setText("沿区域边界依次点击地图添加顶点（至少 3 个，最多 32 个）。蓝色线自动闭合；可拖动顶点微调。已选 "+vertices.size()+" 个点。");
        } else instruction.setText("点击地图选择中心，再滑动调整圆形半径。可以双指缩放、单指移动地图。");
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
                Api.call(this,"POST","/v1/fence",data,Prefs.token(this));
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
