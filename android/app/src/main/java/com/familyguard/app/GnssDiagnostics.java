package com.familyguard.app;

import android.location.GnssStatus;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

/** Satellite signal diagnostics do not increase accuracy and are not a rejection criterion. */
final class GnssDiagnostics {
    private volatile int used;
    private volatile float avgCn0;
    private volatile long sampledAt;
    private boolean registered;

    private final GnssStatus.Callback callback=new GnssStatus.Callback(){
        @Override public void onSatelliteStatusChanged(GnssStatus s){
            int count=0; float total=0;
            for(int i=0;i<s.getSatelliteCount();i++){
                if(s.usedInFix(i)){
                    count++;
                    total+=Math.max(0,s.getCn0DbHz(i));
                }
            }
            used=count;
            avgCn0=count>0?total/count:0;
            sampledAt=SystemClock.elapsedRealtime();
        }
    };
    void start(LocationManager manager,Handler main){
        if(manager==null)return;
        try {
            if(Build.VERSION.SDK_INT>=30){
                registered=manager.registerGnssStatusCallback(main::post, callback);
            }else{
                registered=manager.registerGnssStatusCallback(callback,main);
            }
        }catch(RuntimeException ex){
            Log.w("FamilyGuard","卫星状态不可用，仅影响诊断信息",ex);
        }
    }
    int usedInFix(){return SystemClock.elapsedRealtime()-sampledAt<=10000L?used:0;}
    float averageCn0(){return usedInFix()>0?avgCn0:0f;}
    void stop(LocationManager manager){
        if(registered && manager!=null){
            try{manager.unregisterGnssStatusCallback(callback);}catch(RuntimeException ignored){}
            registered=false;
        }
    }
}
