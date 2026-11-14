package com.familyguard.app;

import android.content.Context;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.maps.MapsInitializer;

/** Consent for processing location/device signals by AMap. Never initialize SDK before consent. */
final class AmapPrivacy {
    private AmapPrivacy() {}
    static boolean available() { return BuildConfig.AMAP_KEY != null && !BuildConfig.AMAP_KEY.isEmpty(); }
    static boolean agreed(Context c) { return Prefs.store(c).getBoolean("amap_privacy_agreed", false); }
    static void setAgreed(Context c, boolean ok) { Prefs.store(c).edit().putBoolean("amap_privacy_agreed", ok).commit(); }
    static void initialize(Context c) {
        if (!available() || !agreed(c)) throw new IllegalStateException("高德 Key 或隐私授权未配置");
        AMapLocationClient.updatePrivacyShow(c, true, true);
        AMapLocationClient.updatePrivacyAgree(c, true);
        MapsInitializer.updatePrivacyShow(c, true, true);
        MapsInitializer.updatePrivacyAgree(c, true);
    }
}
