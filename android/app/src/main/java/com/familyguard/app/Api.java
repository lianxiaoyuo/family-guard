package com.familyguard.app;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

final class Api {
    private Api() {}
    static final class ApiException extends IOException {
        final int status;
        ApiException(int status,String message){super("HTTP "+status+"："+message);this.status=status;}
        boolean revoked(){return status==401||status==403;}
    }
    static JSONObject call(Context context, String method, String path, JSONObject payload, String token) throws Exception {
        String base=ServerAddress.DEFAULT_URL;
        URL url = new URL(base + path);
        HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        // Live coordinate traffic must not occupy a network worker for 19 seconds.
        // Account creation/recovery retains the more generous original timeouts.
        boolean live="/v1/position".equals(path) || "/v1/latest".equals(path) || "/v1/heartbeat".equals(path) || "/v1/points".equals(path);
        conn.setConnectTimeout(live?3500:7000);
        conn.setReadTimeout(live?6000:12000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Cache-Control", "no-store");
        conn.setInstanceFollowRedirects(false); // never leak tokens to another host
        if (token != null && !token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (payload != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = conn.getResponseCode();
            InputStream input = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (input == null) throw new IOException("服务器无响应：HTTP " + status);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = input) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) > 0) {
                    if (bytes.size() + n > 1024 * 1024) throw new IOException("响应太大");
                    bytes.write(buffer, 0, n);
                }
            }
            JSONObject result = new JSONObject(bytes.toString("UTF-8"));
            if (status < 200 || status >= 300) {
                throw new ApiException(status,result.optString("error","请求失败"));
            }
            return result;
        } finally { conn.disconnect(); }
    }

    /** Authenticated media transport, max 25 MiB, no redirects or public media URLs. */
    static void upload(Context c,String id,String mime,File file) throws Exception {
        if(file.length()>25L*1024*1024 || file.length()<128)throw new IOException("媒体文件大小必须为 128B–25MB");
        String base=ServerAddress.DEFAULT_URL;
        HttpURLConnection conn=(HttpURLConnection)new URL(base+"/v1/media/upload?id="+id).openConnection();
        conn.setConnectTimeout(7000);conn.setReadTimeout(45000);conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization","Bearer "+Prefs.token(c));
        conn.setRequestProperty("Content-Type",mime);conn.setInstanceFollowRedirects(false);
        conn.setDoOutput(true);conn.setFixedLengthStreamingMode(file.length());
        try {
            try(OutputStream out=conn.getOutputStream();InputStream in=new FileInputStream(file)) {
                byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
            }
            int status=conn.getResponseCode();if(status<200||status>=300)throw new IOException("媒体上传失败 HTTP "+status);
        }finally{conn.disconnect();}
    }
    static void download(Context c,String id,File dest) throws Exception {
        String base=ServerAddress.DEFAULT_URL;
        HttpURLConnection conn=(HttpURLConnection)new URL(base+"/v1/media/file?id="+id).openConnection();
        conn.setConnectTimeout(7000);conn.setReadTimeout(45000);conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Authorization","Bearer "+Prefs.token(c));
        try {
            if(conn.getResponseCode()!=200)throw new IOException("媒体下载失败 HTTP "+conn.getResponseCode());
            try(InputStream in=conn.getInputStream();OutputStream out=new FileOutputStream(dest)) {
                byte[] b=new byte[8192];long count=0;int n;
                while((n=in.read(b))!=-1) {count+=n;if(count>25L*1024*1024)throw new IOException("媒体文件超过限制");out.write(b,0,n);}
            }
        }finally{conn.disconnect();}
    }
}
