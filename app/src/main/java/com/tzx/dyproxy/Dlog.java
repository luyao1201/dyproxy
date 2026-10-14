package com.tzx.dyproxy;

import android.os.Process;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/**
 * 进程内文件日志（抖音进程自身私有目录可达，免 root 直读）+ XposedBridge 兜底。
 * 支持「自动清理 2 小时前的日志」（抽屉第四个开关）。
 */
public final class Dlog {
    private static final String TAG = "DYProxy";
    private static final long MAX_BYTES = 512 * 1024;
    private static final long KEEP_MS = 2 * 3600 * 1000L;

    private static File sFile;
    private static boolean sVerbose = false;
    private static volatile boolean sAutoClean = true;
    private static long sLastClean = 0;
    private static final Set<String> sDiagSeen = new HashSet<>();

    private Dlog() {}

    public static synchronized void init() {
        if (sFile != null) return;
        File f = new File("/sdcard/Android/data/com.ss.android.ugc.aweme/files/dyproxy.log");
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            if (!dir.canWrite()) throw new IllegalStateException("not writable");
        } catch (Throwable t) {
            f = new File("/data/data/com.ss.android.ugc.aweme/files/dyproxy.log");
        }
        sFile = f;
    }

    public static void setVerbose(boolean b) { sVerbose = b; }

    public static void setAutoClean(boolean b) { sAutoClean = b; }

    private static String stamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static synchronized void write(String level, String msg) {
        String line = stamp() + " [" + level + "] tid=" + Process.myTid() + " " + msg;
        try { XposedBridge.log(TAG + ": " + msg); } catch (Throwable ignored) {}
        try {
            init();
            if (sFile.length() > MAX_BYTES) rotate();
            maybeClean();
            try (FileWriter w = new FileWriter(sFile, true)) {
                w.write(line + "\n");
            }
        } catch (Throwable ignored) {}
    }

    private static void rotate() throws Exception {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(sFile, "rw")) {
            long keep = MAX_BYTES / 4;
            byte[] tail = new byte[(int) keep];
            raf.seek(raf.length() - keep);
            raf.readFully(tail);
            raf.setLength(0);
            raf.seek(0);
            raf.write("---- rotated ----\n".getBytes("UTF-8"));
            raf.write(tail);
        }
    }

    /** 每 5 分钟最多清一次：删除 2 小时前的行 */
    private static void maybeClean() {
        if (!sAutoClean) return;
        long now = System.currentTimeMillis();
        if (now - sLastClean < 5 * 60 * 1000L) return;
        sLastClean = now;
        cleanOldEntries();
    }

    public static synchronized void cleanOldEntries() {
        try {
            init();
            if (!sFile.exists()) return;
            List<String> keep = new ArrayList<>();
            long now = System.currentTimeMillis();
            // 行首是 "MM-dd HH:mm:ss.SSS"（无年份），解析前补上当前年份，否则会被解析成 1970 而全部误删
            String year = new SimpleDateFormat("yyyy", Locale.US).format(new Date());
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
            for (String line : java.nio.file.Files.readAllLines(sFile.toPath())) {
                if (line == null || line.isEmpty()) continue;
                long t;
                try {
                    String head = line.substring(0, Math.min(18, line.length()));
                    Date d = fmt.parse(year + "-" + head);
                    t = d.getTime();
                    if (t > now + 24 * 3600 * 1000L) t -= 366L * 24 * 3600 * 1000L;   // 跨年
                } catch (Throwable e) {
                    keep.add(line);   // 解析不了的行保留
                    continue;
                }
                if (now - t <= KEEP_MS) keep.add(line);
            }
            try (FileWriter w = new FileWriter(sFile, false)) {
                for (String line : keep) w.write(line + "\n");
            }
        } catch (Throwable ignored) {}
    }

    public static void boot(String msg) { write("BOOT", msg); }
    public static void i(String msg) { write("INFO", msg); }
    public static void w(String msg) { write("WARN", msg); }
    public static void e(String msg) { write("ERR ", msg); }

    /** 详细诊断：仅在 verbose 且 URL 命中诊断关键字时输出，每个 URL 进程内只记一次 */
    public static synchronized void diag(String url) {
        if (!sVerbose || url == null) return;
        String low = url.toLowerCase(Locale.US);
        if (!(low.contains("comment") || low.contains("publish") || low.contains("creation")
                || low.contains("profile") || low.contains("/im/") || low.contains("statuses")
                || low.contains("/create/") || low.contains("location"))) return;
        String key = low.length() > 160 ? low.substring(0, 160) : low;
        if (sDiagSeen.contains(key) || sDiagSeen.size() > 500) return;
        sDiagSeen.add(key);
        write("DIAG", "see " + key);
    }
}
