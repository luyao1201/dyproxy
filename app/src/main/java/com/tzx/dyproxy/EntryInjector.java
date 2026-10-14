package com.tzx.dyproxy;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 在抖音侧边栏「未成年人保护 / 清理缓存 / 切换账号」这张卡片的第一行插入菜单入口
 * 「我是歪果仁」，样式复制同行（字号/颜色/内边距/高度），点击弹出抽屉。
 *
 * 触发：Activity.performResume/onResume 后延时扫描 + RecyclerView.onLayout（节流）兜底。
 */
public final class EntryInjector {

    private static final String ANCHOR = "未成年人保护";
    private static final String SIBLING = "清理缓存";
    private static final Object TAG = new Object();

    private static WeakReference<Activity> sCurAct = new WeakReference<>(null);
    private static WeakReference<View> sInjected = new WeakReference<>(null);
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static volatile long sLastRvScan = 0;
    private static volatile boolean sLoggedOnce = false;

    private EntryInjector() {}

    public static Activity currentActivity() {
        return sCurAct.get();
    }

    public static void install(ClassLoader cl) {
        try {
            XposedBridge.hookAllMethods(Activity.class, "performResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    onAct((Activity) param.thisObject);
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    onAct((Activity) param.thisObject);
                }
            });
        } catch (Throwable t) {
            Dlog.e("activity hook failed: " + t);
        }
        try {
            XposedBridge.hookAllMethods(XposedHelpers.findClass(
                    "androidx.recyclerview.widget.RecyclerView", cl), "onLayout", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - sLastRvScan < 800) return;
                    sLastRvScan = now;
                    try {
                        View rv = (View) param.thisObject;
                        scan(rv.getRootView(), rv.getContext());
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            Dlog.e("recyclerview hook failed: " + t);
        }
        // 侧边栏是 ScrollView + 普通视图、且在同 Activity 内覆盖打开（无 resume / 无 RV onLayout），
        // 所以再加一个轻量周期扫描：已注入且仍在屏上就跳过。
        H.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    View inj = sInjected.get();
                    if (inj == null || !inj.isAttachedToWindow()) {
                        Activity a = sCurAct.get();
                        View root = (a != null && a.getWindow() != null) ? a.getWindow().getDecorView() : null;
                        if (root != null) scan(root, root.getContext());
                    }
                } catch (Throwable ignored) {}
                H.postDelayed(this, 1200);
            }
        }, 1500);
        Dlog.boot("entry injector installed");
    }

    private static void onAct(final Activity a) {
        if (a == null) return;
        sCurAct = new WeakReference<>(a);
        for (long delay : new long[]{350, 1000, 2200}) {
            H.postDelayed(() -> {
                try {
                    View decor = a.getWindow() != null ? a.getWindow().getDecorView() : null;
                    if (decor != null) scan(decor, a);
                } catch (Throwable ignored) {}
            }, delay);
        }
    }

    private static volatile long sLastDiag = 0;

    /** BFS 找锚点文本 → 定位卡片容器 → 插第一行 */
    private static void scan(View root, android.content.Context ctx) {
        if (root == null) return;
        TextView anchor = findText(root, ANCHOR, 4000);
        long now = android.os.SystemClock.elapsedRealtime();
        if (anchor == null) {
            if (now - sLastDiag > 10000 && sCurAct.get() != null) {
                sLastDiag = now;
                Dlog.boot("scan: anchor 未找到 (act=" + sCurAct.get().getClass().getSimpleName() + ")");
            }
            return;
        }
        ViewGroup container = findCardContainer(anchor);
        if (container == null) {
            if (now - sLastDiag > 5000) {
                sLastDiag = now;
                Dlog.boot("scan: 找到锚点但未找到卡片容器");
            }
            return;
        }
        for (int i = 0; i < container.getChildCount(); i++) {
            if (container.getChildAt(i).getTag() == TAG) return;   // 已注入
        }
        View siblingRow = directChildOf(container, anchor);
        if (siblingRow == null) return;
        try {
            View row = buildRow(anchor, siblingRow, ctx);
            int idx = Math.max(0, indexOf(container, siblingRow));   // 插到锚点行上方 = 卡片第一行
            ViewGroup.LayoutParams lp;
            if (siblingRow.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                lp = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) siblingRow.getLayoutParams());
            } else {
                lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            container.addView(row, idx, lp);
            sInjected = new WeakReference<>(row);
            if (!sLoggedOnce) {
                sLoggedOnce = true;
                Dlog.boot("ENTRY injected above " + ANCHOR + " (container=" + container.getClass().getSimpleName()
                        + " index=" + idx + ")");
            }
        } catch (Throwable t) {
            Dlog.e("entry insert failed: " + t);
        }
    }

    private static TextView findText(View root, String text, int cap) {
        ArrayDeque<View> q = new ArrayDeque<>();
        q.add(root);
        int n = 0;
        while (!q.isEmpty() && n++ < cap) {
            View v = q.poll();
            if (v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null && cs.toString().contains(text)) return (TextView) v;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) q.add(g.getChildAt(i));
            }
        }
        return null;
    }

    /** 卡片容器：包含锚点与「清理缓存」、子项 2~8 个的最深祖先 */
    private static ViewGroup findCardContainer(View anchor) {
        View cur = anchor;
        ViewGroup best = null;
        for (int depth = 0; depth < 12 && cur != null; depth++) {
            View parent = (View) cur.getParent();
            if (!(parent instanceof ViewGroup)) break;
            ViewGroup g = (ViewGroup) parent;
            String cls = g.getClass().getName();
            if (cls.startsWith("androidx.recyclerview")) break;      // 别抓到列表本身
            int cc = g.getChildCount();
            if (cc >= 2 && cc <= 8 && findText(g, SIBLING, 60) != null && findText(g, ANCHOR, 60) != null) {
                best = g;   // 继续向上找更"深"的（循环向上取最后一次满足 = 最外层仍满足的）——实际取最内层：break
                break;
            }
            cur = parent;
        }
        return best;
    }

    private static View directChildOf(ViewGroup g, View descendant) {
        View cur = descendant;
        while (cur != null && cur.getParent() != g) {
            View p = (View) cur.getParent();
            if (p == null) return null;
            cur = p;
        }
        return cur;
    }

    private static int indexOf(ViewGroup g, View child) {
        for (int i = 0; i < g.getChildCount(); i++) {
            if (g.getChildAt(i) == child) return i;
        }
        return 0;
    }

    /** 复刻同行样式：字号/颜色抄锚点，高度/内边距抄兄弟行，图标用「歪」字方块 */
    private static View buildRow(TextView anchor, View siblingRow, android.content.Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setTag(TAG);
        row.setClickable(true);
        row.setFocusable(true);
        try {
            row.setForeground(new RippleDrawable(ColorStateList.valueOf(0x14000000), null,
                    new ColorDrawable(0xFFFFFFFF)));
        } catch (Throwable ignored) {}

        int h = siblingRow.getHeight();
        if (h <= 0) h = T.dp(ctx, 52);
        row.setMinimumHeight(h);
        row.setPadding(siblingRow.getPaddingLeft(), siblingRow.getPaddingTop(),
                siblingRow.getPaddingRight(), siblingRow.getPaddingBottom());

        // 图标：黑色「周」字，无背景
        TextView icon = T.text(ctx, "周", 18f, 0xFF000000, true);
        icon.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(T.dp(ctx, 26), T.dp(ctx, 26));
        ilp.rightMargin = T.dp(ctx, 12);
        row.addView(icon, ilp);

        // 标题：抄锚点字号/颜色
        TextView title = new TextView(ctx);
        title.setText("我是歪果仁");
        title.setTextSize(0, anchor.getTextSize());
        title.setTextColor(anchor.getCurrentTextColor());
        if (anchor.getTypeface() != null) title.setTypeface(anchor.getTypeface());
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 右侧箭头（抄同行的「>」观感）
        TextView arrow = T.text(ctx, "›", 18f, 0xFFB0B0B5, false);
        row.addView(arrow);

        row.setOnClickListener(v -> {
            Dlog.boot("entry row clicked");
            Drawer.show(v.getContext());
        });
        return row;
    }
}
