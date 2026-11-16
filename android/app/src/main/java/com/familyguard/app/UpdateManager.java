package com.familyguard.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** HTTPS updater for owner-published APKs. Android's installer verifies the signing identity. */
final class UpdateManager {
    private UpdateManager() {}
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final Handler UI=new Handler(Looper.getMainLooper());
    private static final String BASE=ServerAddress.DEFAULT_URL;
    private static final long MAX_APK=150L<<20;

    static void check(Activity activity,boolean manual){
        if(!manual){
            long last=Prefs.store(activity).getLong("last_update_check",0L);
            if(System.currentTimeMillis()-last<24*60*60*1000L)return;
        }
        IO.execute(()->{
            HttpURLConnection conn=null;
            try{
                conn=(HttpURLConnection)new URL(BASE+"/v1/app/update").openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(6000);conn.setReadTimeout(8000);
                if(conn.getResponseCode()!=200)throw new IllegalStateException("服务器版本接口不可用");
                byte[] buf=new byte[8192];
                int total=0;
                try(InputStream in=conn.getInputStream()){
                    int n;java.io.ByteArrayOutputStream b=new java.io.ByteArrayOutputStream();
                    while((n=in.read(buf))!=-1){total+=n;if(total>8192)throw new IllegalStateException("更新信息超出限制");b.write(buf,0,n);}
                    JSONObject meta=new JSONObject(b.toString("UTF-8"));
                    if(!meta.optBoolean("available",false)){
                        if(manual)UI.post(()->toast(activity,"目前没有发布新的安装包"));
                        markChecked(activity);return;
                    }
                    int remote=meta.getInt("versionCode");
                    long local=Build.VERSION.SDK_INT>=28?
                            activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).getLongVersionCode():
                            activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).versionCode;
                    if(remote<=local){
                        if(manual)UI.post(()->toast(activity,"当前已是最新已发布版本"));
                        markChecked(activity);return;
                    }
                    String sha=meta.getString("sha256").toLowerCase(Locale.ROOT);
                    long size=meta.getLong("size");
                    if(!sha.matches("[0-9a-f]{64}")||size<1024||size>MAX_APK)
                        throw new IllegalStateException("更新信息校验失败");
                    String name=meta.optString("versionName","新版本");
                    if(name.length()>64)throw new IllegalStateException("版本号无效");
                    String notes=meta.optString("notes","优化使用体验");
                    if(notes.length()>1024)notes=notes.substring(0,1024);
                    String finalNotes=notes;
                    UI.post(()->new AlertDialog.Builder(activity)
                            .setTitle("发现新版本 "+name)
                            .setMessage(finalNotes+"\n\n下载后需确认系统安装授权，家庭资料不会主动清除。")
                            .setNegativeButton("稍后",null)
                            .setPositiveButton("下载更新",(d,w)->download(activity,remote,sha,size))
                            .show());
                    markChecked(activity);
                }
            }catch(Exception ex){
                if(manual)UI.post(()->toast(activity,"检查更新失败："+ex.getMessage()));
            }finally{if(conn!=null)conn.disconnect();}
        });
    }
    private static void markChecked(Activity a){
        Prefs.store(a).edit().putLong("last_update_check",System.currentTimeMillis()).apply();
    }
    private static void toast(Activity a,String msg){Toast.makeText(a,msg,Toast.LENGTH_LONG).show();}
    private static void download(Activity activity,int expectedCode,String expectedSHA,long expectedSize){
        ProgressDialog dlg=new ProgressDialog(activity);
        dlg.setTitle("正在下载更新");
        dlg.setMessage("下载后将检查文件完整性");
        dlg.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        dlg.setMax(100);
        dlg.setCancelable(false);
        dlg.show();
        IO.execute(()->{
            File tmp=null;
            HttpURLConnection conn=null;
            try{
                File cache=new File(activity.getCacheDir(),"updates");
                if(!cache.exists()&&!cache.mkdirs())throw new IllegalStateException("无法建立下载缓存");
                tmp=new File(cache,"familyguard.part");
                if(tmp.exists()&&!tmp.delete())throw new IllegalStateException("无法清理旧下载");
                File apk=new File(cache,"familyguard.apk");
                if(apk.exists()&&!apk.delete())throw new IllegalStateException("无法替换旧版本文件");
                conn=(HttpURLConnection)new URL(BASE+"/v1/app/download").openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(8000);conn.setReadTimeout(45000);
                if(conn.getResponseCode()!=200)throw new IllegalStateException("下载接口未准备好");
                long length=conn.getContentLengthLong();
                if(length>0&&length!=expectedSize)throw new IllegalStateException("下载文件大小不符合版本信息");
                MessageDigest sha=MessageDigest.getInstance("SHA-256");
                long written=0;
                int last=-1;
                try(InputStream in=conn.getInputStream();FileOutputStream out=new FileOutputStream(tmp)){
                    byte[] buffer=new byte[64*1024];int n;
                    while((n=in.read(buffer))!=-1){
                        written+=n;
                        if(written>expectedSize||written>MAX_APK)throw new IllegalStateException("下载大小超过限制");
                        sha.update(buffer,0,n);out.write(buffer,0,n);
                        int pct=(int)(written*100/expectedSize);
                        if(pct!=last&&pct%2==0){last=pct;int v=pct;UI.post(()->dlg.setProgress(v));}
                    }
                    out.getFD().sync();
                }
                if(written!=expectedSize)throw new IllegalStateException("安装包下载不完整");
                StringBuilder hex=new StringBuilder(64);
                for(byte b:sha.digest())hex.append(String.format(Locale.ROOT,"%02x",b&0xff));
                if(!expectedSHA.equals(hex.toString()))throw new IllegalStateException("安装包 SHA-256 校验不通过");
                if(!tmp.renameTo(apk))throw new IllegalStateException("无法完成安装包写入");
                PackageInfo info=activity.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(),0);
                if(info==null||!activity.getPackageName().equals(info.packageName))throw new IllegalStateException("安装包的应用身份不一致");
                long newCode=Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;
                if(newCode!=expectedCode)throw new IllegalStateException("安装包版本与服务器声明不一致");
                UI.post(()->{
                    dlg.dismiss();
                    new AlertDialog.Builder(activity)
                            .setTitle("更新文件已验证")
                            .setMessage("下一步由 Android 系统检查签名并确认安装。请不要卸载原 App。")
                            .setNegativeButton("稍后",null)
                            .setPositiveButton("安装",(d,w)->install(activity,apk))
                            .show();
                });
            }catch(Exception ex){
                if(tmp!=null)tmp.delete();
                UI.post(()->{dlg.dismiss();toast(activity,"更新失败："+ex.getMessage());});
            }finally{if(conn!=null)conn.disconnect();}
        });
    }
    private static void install(Activity a,File apk){
        if(Build.VERSION.SDK_INT>=26&&!a.getPackageManager().canRequestPackageInstalls()){
            new AlertDialog.Builder(a).setTitle("需要安装授权")
                    .setMessage("请在系统设置中允许家庭守护安装更新，然后返回 App 再次点击检查更新。")
                    .setNegativeButton("取消",null)
                    .setPositiveButton("前往设置",(d,w)->{
                        Intent i=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:"+a.getPackageName()));
                        a.startActivity(i);
                    }).show();
            return;
        }
        Uri uri=FileProvider.getUriForFile(a,a.getPackageName()+".fileprovider",apk);
        Intent view=new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri,"application/vnd.android.package-archive");
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{a.startActivity(view);}catch(Exception ex){toast(a,"无法打开系统安装程序："+ex.getMessage());}
    }
}
