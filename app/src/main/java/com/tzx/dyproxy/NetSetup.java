package com.tzx.dyproxy;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;

import de.robv.android.xposed.XposedHelpers;

/**
 * 代理注入：
 * TTNet 的 okhttp 通道（X.1OAm / X.1OAj）在每次请求构建 OkHttpClient.Builder 时读取
 * 静态字段 X.1OAm.LIZLLL（java.net.Proxy）。把它设为 SOCKS 代理后，
 * 该通道的所有请求（含被我们强制分流过来的互动请求）都由 java.net 原生完成 SOCKS5 握手。
 */
public final class NetSetup {

    private static Class<?> sClientCls;      // okhttp 客户端类（按候选名解析，跨版本）
    private static String sAppliedKey = "";
    private static volatile boolean sAuthLogged = false;

    private static final String[] CLS_OKHTTP_CLIENTS = {
            "com.bytedance.frameworks.baselib.network.http.ok3.impl.SsOkHttp3Client",  // 40.x
            "X.1OAm",                                                                  // 39.x
    };

    private NetSetup() {}

    /** 删除代理时调用：把 TTNet okhttp 通道的静态代理字段清空 */
    public static synchronized void clear(ClassLoader cl) {
        if (sClientCls == null) {
            for (String cand : CLS_OKHTTP_CLIENTS) {
                try { sClientCls = XposedHelpers.findClass(cand, cl); break; } catch (Throwable ignored) {}
            }
        }
        try {
            if (sClientCls != null) XposedHelpers.setStaticObjectField(sClientCls, "LIZLLL", null);
        } catch (Throwable ignored) {}
        sAppliedKey = "";
    }

    /** 在每次命中的请求前调用：幂等，极廉价，可抵御 App 侧对静态字段的覆盖 */
    public static synchronized void ensure(Config cfg, ClassLoader cl) throws Throwable {
        if (sClientCls == null) {
            for (String cand : CLS_OKHTTP_CLIENTS) {
                try {
                    sClientCls = XposedHelpers.findClass(cand, cl);
                    break;
                } catch (Throwable ignored) {}
            }
        }
        ensureAuth(cfg);
        if (sClientCls == null) throw new NoClassDefFoundError("okhttp client class not found");
        Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(cfg.host, cfg.port));
        // 无论是否变化都重设一次（静态字段，纳秒级）
        XposedHelpers.setStaticObjectField(sClientCls, "LIZLLL", proxy);
        String key = cfg.host + ":" + cfg.port + ":" + cfg.user + ":" + cfg.pass;
        if (key.equals(sAppliedKey)) return;
        sAppliedKey = key;
        Dlog.boot("proxy applied (" + cfg.host + ":" + cfg.port + ")");
    }

    /** 只装 SOCKS5 认证：不碰宿主类，跨抖音版本永远可用（geo 地址解析只依赖它） */
    public static synchronized void ensureAuth(Config cfg) {
        String key = cfg.host + ":" + cfg.port + ":" + cfg.user + ":" + cfg.pass;
        if (key.equals(sAppliedKey)) return;
        if (!cfg.user.isEmpty() || !cfg.pass.isEmpty()) {
            final String u = cfg.user;
            final String p = cfg.pass == null ? "" : cfg.pass;
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    try {
                        // Android SocksSocketImpl 传的是 protocol="SOCKS5"、scheme=null、type=PROXY
                        String proto = getRequestingProtocol();
                        String scheme = getRequestingScheme();
                        boolean socks = (proto != null && proto.toUpperCase(java.util.Locale.US).contains("SOCKS"))
                                || (scheme != null && scheme.toUpperCase(java.util.Locale.US).contains("SOCKS"))
                                || getRequestorType() == RequestorType.PROXY;
                        if (socks) {
                            if (!sAuthLogged) {
                                sAuthLogged = true;
                                Dlog.boot("socks5 auth -> supplied (proto=" + proto + " scheme=" + scheme
                                        + " type=" + getRequestorType() + ")");
                            }
                            return new PasswordAuthentication(u, p.toCharArray());
                        }
                    } catch (Throwable ignored) {}
                    return null;
                }
            });
            Dlog.boot("socks5 auth installed");
        }
        sAppliedKey = key;
    }
}
