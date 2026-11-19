package com.familyguard.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.os.Build;
import org.json.JSONObject;

/** Foreground parent alerts. Background push still requires a separately configured gateway. */
final class ParentAlertNotifier {
    private static final String CHANNEL="parent_safety_events";
    private static final String SEEN_KEY="guard_seen_v71";
    private ParentAlertNotifier(){}

    /** @return whether this is a new actionable event. The caller allows one dialog per poll. */
    static boolean handle(Activity parent,JSONObject event,boolean allowDialog,java.util.function.Consumer<String> acknowledge){
        if(event==null||!"parent".equals(Prefs.role(parent)))return false;
        String id=event.optString("id","");
        String kind=event.optString("kind","");
        if(event.optLong("acknowledgedAt",0)>0)return false;
        if(!id.matches("[0-9a-fA-F]{16}") || !GuardEventPolicy.shouldPresent(kind,
                event.optLong("time",0),System.currentTimeMillis()))return false;
        String ids=Prefs.get(parent,SEEN_KEY);
        if(GuardEventPolicy.alreadySeen(ids,id))return false;
        boolean canNotify=Build.VERSION.SDK_INT<33 ||
                parent.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
        boolean canDialog=allowDialog && !parent.isFinishing() && parent.hasWindowFocus();
        if(!canNotify && !canDialog)return false; // Preserve unread events when notifications are denied.
        // Save before displaying to avoid repeated rings when reopening the screen.
        if(!Prefs.store(parent).edit().putString(SEEN_KEY,GuardEventPolicy.remember(ids,id)).commit())return false;
        String title;
        switch(kind){
            case "sos": title="孩子发出 SOS 求助"; break;
            case "exit": title="孩子离开安全区域"; break;
            case "enter": title="孩子进入安全区域"; break;
            case "offline": title="孩子设备已经失联"; break;
            case "low_battery": title="孩子设备电量不足"; break;
            case "location_stale": title="孩子设备定位已中断"; break;
            default: return false;
        }
        String message=event.optString("message",title);
        long occurredAt=event.optLong("occurredAt",0L);
        if("sos".equals(kind) && occurredAt>0 && event.optLong("time",0)-occurredAt>120000L)
            message+="（这是离线后补传的求助，请核对实际发生时间）";
        NotificationManager nm=(NotificationManager)parent.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"家庭安全紧急提醒",NotificationManager.IMPORTANCE_HIGH));
        if(canNotify){
            Intent open=new Intent(parent,MainActivity.class);
            PendingIntent pending=PendingIntent.getActivity(parent,100,open,
                    PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification note=new Notification.Builder(parent,CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(title).setContentText(message)
                    .setStyle(new Notification.BigTextStyle().bigText(message))
                    .setContentIntent(pending).setAutoCancel(true)
                    .setCategory("sos".equals(kind)?Notification.CATEGORY_ALARM:Notification.CATEGORY_STATUS)
                    .build();
            nm.notify(id.hashCode(),note);
        }
        if(canDialog){
            new AlertDialog.Builder(parent).setTitle(title)
                    .setMessage(message+"\n\n请确认孩子的最新位置，必要时立即电话联系或报警。")
                    .setNegativeButton("稍后处理",null)
                    .setPositiveButton("我已知晓",(d,w)->{if(acknowledge!=null)acknowledge.accept(id);})
                    .show();
        }
        return true;
    }
}
