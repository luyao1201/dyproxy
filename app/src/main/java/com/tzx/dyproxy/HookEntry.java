package com.tzx.dyproxy;

import android.app.AndroidAppHelper;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 我是歪果仁 —— LSPosed 模块入口（抖音 39.4.0）。
 *
 *  1) Retrofit API 请求经 com.bytedance.ttnet.retrofit.SsRetrofitClient.newSsCall 分流：
 *     默认 cronet；本模块把命中分类开关的 URL 强制改走 okhttp 通道（X.1OAm）。
 *  2) okhttp 通道每个请求从静态字段 X.1OAm.LIZLLL 读取 java.net.Proxy 应用到连接
 *     => 命中请求从 SOCKS5 代理 IP 发出；其余流量（feed/视频/IM/直播）不动。
 *  3) 侧边栏「未成年人保护」卡片第一行注入「我是歪果仁」入口 → 抽屉（代理+三开关）。
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TARGET = "com.ss.android.ugc.aweme";
    private static final String CLS_RETROFIT_CLIENT = "com.bytedance.ttnet.retrofit.SsRetrofitClient";
    private static final String CLS_RETROFIT_REQ   = "com.bytedance.retrofit2.client.Request";
    // okhttp 客户端（代理注入目标）：40.x 起改为稳定命名；保留 39.x 混淆名兜底
    private static final String[] CLS_OKHTTP_CLIENTS = {
            "com.bytedance.frameworks.baselib.network.http.ok3.impl.SsOkHttp3Client",
            "X.1OAm",
    };
    // cronet 客户端（兜底分流点）：名字随版本变，逐版本补
    private static final String[] CLS_CRONET_CLIENTS = {
            "X.1PHs",   // 40.x
            "X.1O3y",   // 39.x
    };
    private static volatile Class<?> sOkHttpCls;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) return;
        final ClassLoader cl = lpparam.classLoader;
        Dlog.init();
        Dlog.boot("module loaded v1.0.6 in " + lpparam.packageName);
        try {
            Config cfg = Config.get(true);
            Dlog.boot("config s5=" + (cfg.s5 == null || cfg.s5.isEmpty() ? "-" : "(已配置)")
                    + " home=" + cfg.swHome + " comment=" + cfg.swComment + " video=" + cfg.swVideo
                    + " clean=" + cfg.swClean);
            Dlog.setVerbose(cfg.verbose);
            Dlog.setAutoClean(cfg.swClean);
            if (cfg.swClean) Dlog.cleanOldEntries();
            Class<?> reqCls = XposedHelpers.findClass(CLS_RETROFIT_REQ, cl);
            installHook(CLS_RETROFIT_CLIENT, cl, reqCls);
            for (String cand : CLS_CRONET_CLIENTS) {
                if (installHook(cand, cl, reqCls)) break;
            }
            EntryInjector.install(cl);
        } catch (Throwable t) {
            Dlog.boot("hook install FAILED: " + Log.getStackTraceString(t));
        }
    }

    private boolean installHook(String cls, final ClassLoader cl, Class<?> reqCls) {
        try {
            XposedHelpers.findAndHookMethod(cls, cl, "newSsCall", reqCls, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Config cfg = Config.get(false);
                        if (!cfg.configured() || !cfg.anySwitch()) return;
                        Object req = param.args[0];
                        String url = (String) XposedHelpers.callMethod(req, "getUrl");
                        if (url == null || url.isEmpty()) return;
                        Dlog.diag(url);
                        if (!Rules.match(url, cfg)) return;
                        Object app = AndroidAppHelper.currentApplication();
                        if (app == null) { Dlog.w("PROXY-SKIP no application ctx: " + url); return; }
                        NetSetup.ensure(cfg, cl);
                        Class<?> clientCls = resolveOkHttpCls(cl);
                        if (clientCls == null) { Dlog.w("PROXY-SKIP okhttp class not found"); return; }
                        Object client = XposedHelpers.callStaticMethod(clientCls, "LJFF", app);
                        Object call = XposedHelpers.callMethod(client, "newSsCall", req);
                        if (call != null) {
                            param.setResult(call);
                            Dlog.i("PROXY " + url);
                        } else {
                            Dlog.w("PROXY-SKIP null call, fallback original: " + url);
                        }
                    } catch (Throwable t) {
                        Dlog.e("hook " + cls + " error: " + Log.getStackTraceString(t));
                    }
                }
            });
            Dlog.boot("hooked " + cls + "#newSsCall");
            return true;
        } catch (Throwable t) {
            Dlog.boot("hook " + cls + " FAILED: " + Log.getStackTraceString(t));
            return false;
        }
    }

    /** 按候选名单解析 okhttp 客户端类（缓存） */
    static Class<?> resolveOkHttpCls(ClassLoader cl) {
        Class<?> c = sOkHttpCls;
        if (c != null) return c;
        for (String cand : CLS_OKHTTP_CLIENTS) {
            try {
                c = XposedHelpers.findClass(cand, cl);
                sOkHttpCls = c;
                Dlog.boot("okhttp client class = " + cand);
                return c;
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
