package com.familyguard.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;

/** Optional fused GPS/Wi-Fi/cellular location, in addition to Android raw GNSS. */
final class AmapTracker {
    interface Callback { void onLocation(AMapLocation location); }
    private AMapLocationClient client;
    private boolean loggedFirstFix;

    void start(Context context, Callback listener) {
        if (client != null || !AmapPrivacy.available() || !AmapPrivacy.agreed(context)) return;
        try {
            AmapPrivacy.initialize(context);
            Log.i("FamilyGuard.AMap", "Creating AMap location client; Android Key is injected through Manifest metadata.");
            AMapLocationClient c = new AMapLocationClient(context.getApplicationContext());
            AMapLocationClientOption opts = new AMapLocationClientOption();
            opts.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
            opts.setInterval(1000L);
            opts.setOnceLocation(false);
            opts.setNeedAddress(false);
            opts.setMockEnable(false);
            Handler main = new Handler(Looper.getMainLooper());
            c.setLocationListener(new AMapLocationListener() {
                @Override public void onLocationChanged(AMapLocation loc) {
                    if (loc != null && loc.getErrorCode() == 0) {
                        if(!loggedFirstFix){
                            loggedFirstFix=true;
                            Log.i("FamilyGuard.AMap", "AMap location SDK returned a valid fix; accuracy="+loc.getAccuracy()+"m, type="+loc.getLocationType());
                        }
                        main.post(() -> listener.onLocation(loc));
                    } else if (loc != null) Log.w("FamilyGuard.AMap", "AMap location failed; errorCode=" + loc.getErrorCode()+", info="+loc.getErrorInfo());
                }
            });
            c.setLocationOption(opts);
            client = c;
            c.startLocation();
        } catch (Exception | LinkageError e) {
            Log.e("FamilyGuard", "高德融合定位不可用，保留原生 GNSS", e);
            close();
        }
    }
    void close() {
        AMapLocationClient c = client;
        client = null;
        if (c != null) {
            try { c.stopLocation(); c.onDestroy(); } catch (RuntimeException ignored) {}
        }
        loggedFirstFix=false;
    }
}
