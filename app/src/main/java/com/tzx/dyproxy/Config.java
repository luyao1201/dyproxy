package com.tzx.dyproxy;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 配置（加密存储）：
 *  文件 /sdcard/Android/data/com.ss.android.ugc.aweme/files/dyproxy.conf
 *  · 加密格式 "DPX1:"+Base64（AES-GCM，密钥设备绑定）
 *  · 旧明文 JSON 自动迁移：读到明文 → 解析 → 立即以加密格式回写
 *
 * 字段：{s5, swHome, swComment, swVideo, swClean, verbose, ts}
 * 旧格式（enabled/host/port…）自动迁移为 s5 串。
 */
public final class Config {
    public static final String FILE = "/sdcard/Android/data/com.ss.android.ugc.aweme/files/dyproxy.conf";

    public String s5 = "";            // 原始代理串（加密落盘，界面不再明文显示）
    public boolean swHome = true;     // 主页 IP 替换
    public boolean swComment = true;  // 评论 IP 替换
    public boolean swVideo = true;    // 发视频 IP 替换
    public boolean swClean = true;    // 自动清理 2 小时前的日志
    public boolean verbose = true;
    public long ts = 0;

    // 代理出口地理信息（经代理实测解析后缓存）
    public String geoCountry = "";    // 如 "美国"
    public String geoIp = "";         // 出口 IP
    public long geoAt = 0;            // 解析时间

    // 解析产物（供 NetSetup 用）
    public String host = "";
    public int port = 0;
    public String user = "";
    public String pass = "";

    private static Config sCache;
    private static long sLastLoad;

    private Config() {}

    public static synchronized Config get(boolean force) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && sCache != null && now - sLastLoad < 2000) return sCache;
        Config c = fromFile();
        if (c == null) c = new Config();
        sCache = c;
        sLastLoad = now;
        return c;
    }

    public static synchronized void set(Config c) {
        sCache = c;
        sLastLoad = android.os.SystemClock.elapsedRealtime();
    }

    public boolean configured() {
        return host != null && !host.isEmpty() && port > 0;
    }

    public boolean anySwitch() { return swHome || swComment || swVideo; }

    private static Config fromFile() {
        try {
            File f = new File(FILE);
            if (!f.exists() || f.length() <= 0) return null;
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int n = in.read(buf);
                if (n <= 0) return null;
            }
            String content = new String(buf, "UTF-8").trim();
            String json = null;
            if (Crypto.isEncrypted(content)) {
                json = Crypto.decrypt(content);
            } else if (content.startsWith("{")) {
                json = content;                       // 旧明文 → 迁移
                Dlog.boot("config: 检测到明文配置，迁移为加密存储");
            }
            if (json == null) return null;
            Config c = fromJson(new JSONObject(json));
            if (!Crypto.isEncrypted(content)) persist(c);   // 立即加密回写
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    public static Config fromJson(JSONObject o) {
        Config c = new Config();
        c.ts = o.optLong("ts", 0);
        c.verbose = o.optBoolean("verbose", true);
        c.swClean = o.optBoolean("swClean", true);
        c.geoCountry = o.optString("geoCountry", "");
        c.geoIp = o.optString("geoIp", "");
        c.geoAt = o.optLong("geoAt", 0);
        String s5 = o.optString("s5", "");
        if (s5 != null && !s5.trim().isEmpty()) {
            c.s5 = s5.trim();
            c.swHome = o.optBoolean("swHome", true);
            c.swComment = o.optBoolean("swComment", true);
            c.swVideo = o.optBoolean("swVideo", true);
        } else {
            // 更早版本迁移：enabled+host/port/user/pass → s5 串
            boolean enabled = o.optBoolean("enabled", false);
            String h = o.optString("host", "").trim();
            int p = o.optInt("port", 0);
            String u = o.optString("user", "").trim();
            String w = o.optString("pass", "");
            if (!h.isEmpty() && p > 0) {
                StringBuilder sb = new StringBuilder();
                if (!u.isEmpty()) sb.append(u).append(':').append(w).append('@');
                sb.append(h).append(':').append(p);
                c.s5 = sb.toString();
            }
            c.swHome = c.swComment = c.swVideo = enabled;
        }
        ProxyParse pp = ProxyParse.parse(c.s5);
        if (pp.ok) {
            c.host = pp.host; c.port = pp.port; c.user = pp.user; c.pass = pp.pass;
        }
        return c;
    }

    /** 抖音进程内落盘（加密） */
    public static void persist(Config c) {
        try {
            File f = new File(FILE);
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            String json = toJson(c).toString();
            String out = Crypto.encrypt(json);
            if (out == null) {
                Dlog.w("persist: encrypt failed, 未写入");
                return;
            }
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(out.getBytes("UTF-8"));
            }
        } catch (Throwable t) {
            Dlog.w("persist failed: " + t);
        }
    }

    public static JSONObject toJson(Config c) {
        JSONObject o = new JSONObject();
        try {
            o.put("s5", c.s5);
            o.put("swHome", c.swHome);
            o.put("swComment", c.swComment);
            o.put("swVideo", c.swVideo);
            o.put("swClean", c.swClean);
            o.put("verbose", c.verbose);
            o.put("ts", c.ts > 0 ? c.ts : System.currentTimeMillis());
            o.put("geoCountry", c.geoCountry);
            o.put("geoIp", c.geoIp);
            o.put("geoAt", c.geoAt);
        } catch (Throwable ignored) {}
        return o;
    }
}
