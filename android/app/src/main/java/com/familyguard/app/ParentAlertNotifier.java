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

/** Foreground parent alerts; real background delivery needs FCM/vendor push deployment. */
final class ParentAlertNotifier {
    private static final String CHANNEL="parent_safety_events";
    private ParentAlertNotifier(){}
    static void handle(Activity parent,JSONObject event){
        if(event==null||!"parent".equals(Prefs.role(parent)))return;
        String id=event.optString("id","");
        if(id.isEmpty())return;
        if(id.equals(Prefs.get(parent,"last_seen_guard_event")))return;
        Prefs.put(parent,"last_seen_guard_event",id);
        String kind=event.optString("kind","");
        if(!("sos".equals(kind)||"exit".equals(kind)||"enter".equals(kind)))return;
        long when=event.optLong("time",0);
        long age=System.currentTimeMillis()-when;
        if(when<=0||age<0||age>10*60*1000L)return; // Never ring for stale alerts.
        String title="sos".equals(kind)?"孩子发出 SOS 求助":
                "exit".equals(kind)?"孩子离开安全区域":"孩子进入安全区域";
        String message=event.optString("message",title);
        NotificationManager nm=(NotificationManager)parent.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"家庭安全紧急提醒",NotificationManager.IMPORTANCE_HIGH));
        if(Build.VERSION.SDK_INT<33||parent.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED){
            Intent open=new Intent(parent,MainActivity.class);
            PendingIntent pending=PendingIntent.getActivity(parent,100,open,
                    PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification note=new Notification.Builder(parent,CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(title).setContentText(message)
                    .setContentIntent(pending).setAutoCancel(true)
                    .setCategory("sos".equals(kind)?Notification.CATEGORY_ALARM:Notification.CATEGORY_STATUS)
                    .build();
            nm.notify(id.hashCode(),note);
        }
        if(!parent.isFinishing()&&parent.hasWindowFocus()){
            new AlertDialog.Builder(parent).setTitle(title)
                    .setMessage(message+"\n\n请确认孩子的最新位置，必要时直接电话联系。")
                    .setPositiveButton("查看位置",(d,w)->{})
                    .show();
        }
    }
}
