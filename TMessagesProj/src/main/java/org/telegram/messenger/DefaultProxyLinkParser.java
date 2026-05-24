package org.telegram.messenger;

import android.net.Uri;
import android.text.TextUtils;

public class DefaultProxyLinkParser {

    private DefaultProxyLinkParser() {}

    public static SharedConfig.ProxyInfo parse(String link) {
        if (TextUtils.isEmpty(link)) return null;
        Uri uri;
        try {
            uri = Uri.parse(link);
        } catch (Exception e) {
            FileLog.e("Invalid default proxy link: " + link);
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!"tg".equals(scheme) || host == null) {
            FileLog.e("Default proxy link must use tg:// scheme: " + link);
            return null;
        }
        String server = uri.getQueryParameter("server");
        String portStr = uri.getQueryParameter("port");
        if (TextUtils.isEmpty(server) || TextUtils.isEmpty(portStr)) return null;
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            return null;
        }
        switch (host) {
            case "tuic": {
                String uuid = uri.getQueryParameter("uuid");
                if (TextUtils.isEmpty(uuid)) return null;
                String password = nullToEmpty(uri.getQueryParameter("password"));
                String congestion = uri.getQueryParameter("congestion_control");
                if (TextUtils.isEmpty(congestion)) congestion = "bbr";
                String tlsInsecure = uri.getQueryParameter("tls_insecure");
                boolean insecure = tlsInsecure == null || !tlsInsecure.equals("0");
                String calls = uri.getQueryParameter("calls");
                boolean useForCalls = calls == null || !calls.equals("0");
                return SharedConfig.ProxyInfo.forTuic(
                        new TuicProxyConfig(server, port, uuid, password, congestion, insecure),
                        useForCalls);
            }
            case "socks": {
                SharedConfig.ProxyInfo info = new SharedConfig.ProxyInfo(server, port,
                        nullToEmpty(uri.getQueryParameter("user")),
                        nullToEmpty(uri.getQueryParameter("pass")),
                        "");
                info.useForCalls = "1".equals(uri.getQueryParameter("calls"));
                return info;
            }
            case "proxy": {
                String secret = nullToEmpty(uri.getQueryParameter("secret"));
                SharedConfig.ProxyInfo info = new SharedConfig.ProxyInfo(server, port,
                        nullToEmpty(uri.getQueryParameter("user")),
                        nullToEmpty(uri.getQueryParameter("pass")),
                        secret);
                info.useForCalls = "1".equals(uri.getQueryParameter("calls"));
                return info;
            }
            default:
                FileLog.e("Unsupported default proxy scheme tg://" + host);
                return null;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
