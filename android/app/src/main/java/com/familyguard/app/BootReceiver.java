package com.familyguard.app;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!"child".equals(Prefs.role(context)) || !Prefs.isSharing(context)) return;
        // Android 14+ location-type foreground services started from background
        // require ACCESS_BACKGROUND_LOCATION. Never start without the permission.
        if (Build.VERSION.SDK_INT >= 29 && context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w("FamilyGuard", "Boot recovery requires allow-all-the-time location permission");
            return;
        }
        try {
            ContextCompatShim.start(context);
        } catch (RuntimeException ex) { Log.e("FamilyGuard", "Boot restart not permitted by OS",ex); }
    }
}
