package org.telegram.messenger;

public class TuicProxyConfig {
    public String server;
    public int port;
    public String uuid;
    public String password;
    public String congestionControl;
    public boolean tlsInsecure;

    public TuicProxyConfig(String server, int port, String uuid, String password,
                           String congestionControl, boolean tlsInsecure) {
        this.server = server == null ? "" : server;
        this.port = port;
        this.uuid = uuid == null ? "" : uuid;
        this.password = password == null ? "" : password;
        this.congestionControl = (congestionControl == null || congestionControl.isEmpty()) ? "bbr" : congestionControl;
        this.tlsInsecure = tlsInsecure;
    }

    public String identity() {
        return server + ":" + port + ":" + uuid;
    }

    public tgx.singbox.TuicConfig toKotlin() {
        return new tgx.singbox.TuicConfig(server, port, uuid, password, congestionControl, tlsInsecure);
    }
}
