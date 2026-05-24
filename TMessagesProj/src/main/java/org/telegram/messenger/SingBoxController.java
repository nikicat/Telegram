package org.telegram.messenger;

import org.telegram.messenger.voip.Instance;
import tgx.singbox.SingBoxManager;
import tgx.singbox.TuicConfig;

import java.util.ArrayList;
import java.util.List;

public class SingBoxController {

    private SingBoxController() {}

    public static void syncWithProxyList() {
        List<TuicConfig> configs = new ArrayList<>();
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (info.type == SharedConfig.PROXY_TYPE_TUIC && info.tuicConfig != null) {
                configs.add(info.tuicConfig.toKotlin());
            }
        }
        SingBoxManager.INSTANCE.startAll(configs);
    }

    /** Returns a transient ProxyInfo pointing at the loopback SOCKS5 inbound
     *  for a TUIC entry. Returns the input unchanged for non-TUIC entries.
     *  Returns null if the TUIC tunnel is not currently up. */
    public static SharedConfig.ProxyInfo resolveForConnection(SharedConfig.ProxyInfo info) {
        if (info == null) return null;
        if (info.type != SharedConfig.PROXY_TYPE_TUIC) return info;
        if (info.tuicConfig == null) return null;
        int port = SingBoxManager.INSTANCE.getPort(info.tuicConfig.toKotlin());
        if (port == 0) return null;
        SharedConfig.ProxyInfo synthetic = new SharedConfig.ProxyInfo("127.0.0.1", port, "", "", "");
        synthetic.type = SharedConfig.PROXY_TYPE_SOCKS5;
        synthetic.useForCalls = info.useForCalls;
        return synthetic;
    }

    /** Returns an Instance.Proxy for the given proxy entry, or null if calls
     *  should not be tunnelled (useForCalls=false, TUIC tunnel down, or the
     *  entry is an MTProto-secret proxy which tgcalls doesn't speak). */
    public static Instance.Proxy resolveForVoIP(SharedConfig.ProxyInfo info) {
        if (info == null || !info.useForCalls) return null;
        if (info.type == SharedConfig.PROXY_TYPE_MTPROTO) return null;
        SharedConfig.ProxyInfo resolved = resolveForConnection(info);
        if (resolved == null) return null;
        return new Instance.Proxy(resolved.address, resolved.port, resolved.username, resolved.password);
    }
}
