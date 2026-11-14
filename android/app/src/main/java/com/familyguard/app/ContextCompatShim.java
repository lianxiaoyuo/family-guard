package com.familyguard.app;
import android.content.Context;
import android.content.Intent;
public final class ContextCompatShim {
    private ContextCompatShim() {}
    static void start(Context c) { c.startForegroundService(new Intent(c, TrackingService.class).setAction(TrackingService.START)); }
}
