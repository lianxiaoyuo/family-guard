package com.familyguard.app;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** A private-network HTTP exception is allowed only for debug builds.
 * Tokens and family locations must never be sent to arbitrary cleartext hosts. */
final class ServerAddress {
    private ServerAddress() {}

    static String normalize(String value, boolean debug) {
        if (value == null) throw new IllegalArgumentException("服务器地址不能为空");
        String s = value.trim().replaceAll("/+$", "");
        final URI uri;
        try { uri = new URI(s); }
        catch (URISyntaxException ex) { throw new IllegalArgumentException("服务器地址格式不正确"); }
        String scheme = uri.getScheme();
        if (scheme == null || uri.getHost() == null || uri.getHost().isEmpty() ||
            uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null ||
            (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) ||
            uri.getPort() > 65535 || uri.getPort() < -1)
            throw new IllegalArgumentException("请只填写服务器根地址，不要附加路径、账号或参数");
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if ("https".equalsIgnoreCase(scheme)) return s;
        if ("http".equalsIgnoreCase(scheme) && debug && privateHost(host)) return s;
        throw new IllegalArgumentException("真实位置必须通过 HTTPS 传输；Debug HTTP 仅允许局域网 IP");
    }

    static boolean privateHost(String host) {
        if ("localhost".equals(host) || "[::1]".equals(host) || "::1".equals(host)) return true;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] ip = new int[4];
        for (int i=0;i<4;i++) {
            if (parts[i].isEmpty() || parts[i].length()>3) return false;
            for (int c=0;c<parts[i].length();c++) if (parts[i].charAt(c)<'0' || parts[i].charAt(c)>'9') return false;
            try { ip[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException ex) { return false; }
            if (ip[i]<0 || ip[i]>255) return false;
        }
        return ip[0]==10 || ip[0]==127 || (ip[0]==192 && ip[1]==168) ||
               (ip[0]==172 && ip[1]>=16 && ip[1]<=31) ||
               (ip[0]==169 && ip[1]==254);
    }
}
