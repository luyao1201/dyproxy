package com.tzx.dyproxy;

import java.util.Locale;

/**
 * 按分类的代理规则（抽屉三个开关各自门控一组接口）：
 *   主页  → /aweme/v1/user/profile/self/
 *   评论  → comment publish / multi_publish / publish_evaluate / delete
 *   发视频 → aweme/v1/creation/publish
 * 其余流量（feed/视频/IM/直播）一律直连。
 */
public final class Rules {

    private static final String[] HOME = {
            "/aweme/v1/user/profile/self/",
            "/aweme/v1/user/profile/scene/",   // 冷启动/首屏的用户查询（属地显示可能随它实时计算）
    };
    private static final String[] COMMENT = {
            "/aweme/v1/comment/publish/",
            "/aweme/v1/comment/multi_publish/",
            "/aweme/v1/comment/publish_evaluate/",
            "/aweme/v1/comment/delete/",
    };
    private static final String[] VIDEO = {
            "aweme/v1/creation/publish",      // 发布流程（需尾部边界校验）
            "/aweme/v1/create/aweme",         // 视频作品提交（真正决定属地的请求）
            "/aweme/v1/create/image/aweme",   // 图文作品提交
    };

    private Rules() {}

    public static boolean match(String url, Config cfg) {
        if (url == null || url.isEmpty() || cfg == null || !cfg.configured()) return false;
        String u = url.toLowerCase(Locale.US);
        if (cfg.swHome && matchAny(u, HOME)) return true;
        if (cfg.swComment && matchAny(u, COMMENT)) return true;
        if (cfg.swVideo && matchAny(u, VIDEO)) return true;
        return false;
    }

    private static boolean matchAny(String u, String[] pats) {
        for (String p : pats) {
            int idx = u.indexOf(p);
            if (idx < 0) continue;
            if (p.endsWith("publish")) {   // 边界：后面不能是字母/数字/下划线（防误伤 publish_guide）
                int n = idx + p.length();
                if (n < u.length()) {
                    char c = u.charAt(n);
                    if (Character.isLetterOrDigit(c) || c == '_') continue;
                }
            }
            return true;
        }
        return false;
    }
}
