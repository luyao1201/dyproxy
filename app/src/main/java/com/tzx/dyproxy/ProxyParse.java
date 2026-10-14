package com.tzx.dyproxy;

/**
 * SOCKS5 代理串自动解析。
 * 支持格式：
 *   socks5://user:pass@host:port
 *   socks://user:pass@host:port
 *   user:pass@host:port
 *   host:port:user:pass
 *   host:port
 */
public final class ProxyParse {
    public String host = "";
    public int port = 0;
    public String user = "";
    public String pass = "";
    public boolean ok = false;
    public String error = "";

    public static ProxyParse parse(String raw) {
        ProxyParse p = new ProxyParse();
        if (raw == null) { p.error = "空"; return p; }
        String s = raw.trim();
        if (s.isEmpty()) { p.error = "空"; return p; }
        // 去协议头
        String low = s.toLowerCase();
        if (low.startsWith("socks5://")) s = s.substring("socks5://".length());
        else if (low.startsWith("socks://")) s = s.substring("socks://".length());
        else if (low.startsWith("socks5h://")) s = s.substring("socks5h://".length());
        s = s.trim();

        try {
            if (s.contains("@")) {                       // [user:pass@]host:port
                int at = s.lastIndexOf('@');
                String cred = s.substring(0, at);
                String hp = s.substring(at + 1);
                if (cred.contains(":")) {
                    int c = cred.indexOf(':');
                    p.user = cred.substring(0, c);
                    p.pass = cred.substring(c + 1);
                } else if (!cred.isEmpty()) {
                    p.user = cred;
                }
                parseHostPort(hp, p);
            } else {
                String[] parts = s.split(":");
                if (parts.length == 2) {                 // host:port
                    p.host = parts[0].trim();
                    p.port = Integer.parseInt(parts[1].trim());
                } else if (parts.length == 4) {          // host:port:user:pass
                    p.host = parts[0].trim();
                    p.port = Integer.parseInt(parts[1].trim());
                    p.user = parts[2].trim();
                    p.pass = parts[3];
                } else {
                    p.error = "格式不认识（支持 host:port / user:pass@host:port / host:port:user:pass）";
                    return p;
                }
            }
        } catch (Throwable t) {
            p.error = "解析失败: " + t.getMessage();
            return p;
        }
        if (p.host.isEmpty() || p.port <= 0 || p.port > 65535) {
            p.error = "host/端口不合法";
            return p;
        }
        p.ok = true;
        return p;
    }

    private static void parseHostPort(String hp, ProxyParse p) {
        int c = hp.lastIndexOf(':');
        if (c <= 0) throw new IllegalArgumentException("缺端口");
        p.host = hp.substring(0, c).trim();
        p.port = Integer.parseInt(hp.substring(c + 1).trim());
    }

    public String toPretty() {
        if (!ok) return error;
        StringBuilder sb = new StringBuilder("socks5://");
        if (!user.isEmpty()) sb.append(user).append(":***@");
        sb.append(host).append(':').append(port);
        return sb.toString();
    }
}
