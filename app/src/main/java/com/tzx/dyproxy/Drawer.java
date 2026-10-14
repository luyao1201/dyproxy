package com.tzx.dyproxy;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.PixelCopy;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.Locale;

/**
 * 抖音内抽屉（抽屉局部磨砂玻璃 / 极简）。
 *
 * 说明：本 ROM（HyperOS/Android16）对任何 BLUR_BEHIND 窗口都会把整屏背景模糊
 * （窗口边界无效），因此不用系统窗口模糊，改为「窗口快照 + GPU 模糊」：
 *   PixelCopy 抓取抖音窗口画面 → RenderEffect 模糊 → 作为抽屉卡片底板，
 *   只让抽屉磨砂，抽屉外背景保持原样（不模糊、不暗化）。
 *
 * 结构：全屏透明内容窗口（坐标正常）→ 底部卡片
 *   [小白条:上滑伸展(松手回弹)/下滑关闭] · [我是歪果仁]
 *   · [输入框(掩码*) + 保存] · [状态:代理已配置 | IP:xx | 地址:美国] · 四个开关
 *   配置弹窗 = 窗口内覆盖层（不关抽屉，磨砂卡片）。
 */
public final class Drawer {

    private static final int WASH = 0x99FFFFFF;   // 白纱（叠在模糊底图上形成磨砂玻璃）

    private Drawer() {}

    public static void show(Context anyCtx) {
        final Activity act = unwrapActivity(anyCtx);
        if (act == null) {
            Dlog.w("drawer: no activity context (" + anyCtx + ")");
            return;
        }
        Dlog.boot("drawer: show on " + act.getClass().getSimpleName());
        try {
            act.runOnUiThread(() -> {
                try {
                    Dialog cur = sCurrentDlg;
                    if (cur != null && cur.isShowing()) cur.dismiss();   // 连点入口不叠加多个抽屉
                } catch (Throwable ignored) {}
                build(act);
            });
        } catch (Throwable t) {
            Dlog.e("drawer show failed: " + t);
        }
    }

    private static volatile Dialog sCurrentDlg;

    private static Activity unwrapActivity(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return EntryInjector.currentActivity();
    }

    private static void build(final Activity act) {
        final Config cfg = Config.get(true);
        final int screenH = act.getResources().getDisplayMetrics().heightPixels;
        final int screenW = act.getResources().getDisplayMetrics().widthPixels;
        final int maxH = screenH - T.dp(act, 96);
        final int[] naturalH = {T.dp(act, 360)};

        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        sCurrentDlg = dlg;
        dlg.setOnDismissListener(d -> {
            if (sCurrentDlg == d) sCurrentDlg = null;
        });

        final FrameLayout root = new FrameLayout(act);

        // ── 卡片：FrameLayout(磨砂底图 + 白纱 + 内容)，顶部圆角 ──
        final FrameLayout card = new FrameLayout(act);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                // 底部圆角推离屏幕外，实现「仅顶部圆角」
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight() + 400, T.dp(act, T.R_PANEL));
            }
        });
        card.setClipToOutline(true);

        final ImageView bg = new ImageView(act);
        bg.setScaleType(ImageView.ScaleType.MATRIX);
        card.addView(bg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View wash = new View(act);
        wash.setBackgroundColor(WASH);
        card.addView(wash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(act);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = T.dp(act, 20);
        content.setPadding(pad, T.dp(act, 8), pad, T.dp(act, 20));
        card.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 小白条（整行抓取区）
        LinearLayout grabRow = new LinearLayout(act);
        grabRow.setOrientation(LinearLayout.HORIZONTAL);
        grabRow.setGravity(Gravity.CENTER);
        grabRow.setPadding(0, T.dp(act, 6), 0, T.dp(act, 14));
        View grab = new View(act);
        grab.setBackground(T.round(act, 0x66FFFFFF, T.R_PILL));
        grabRow.addView(grab, new LinearLayout.LayoutParams(T.dp(act, 40), T.dp(act, 5)));
        content.addView(grabRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 标题行：我是歪果仁 + 版本号（动态读取模块 APK 的 versionName）
        LinearLayout titleRow = new LinearLayout(act);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(T.text(act, "我是歪果仁", 15f, 0xFF000000, true),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        String ver = "";
        try {
            ver = act.getPackageManager().getPackageInfo("com.tzx.dyproxy", 0).versionName;
        } catch (Throwable ignored) {}
        if (ver != null && !ver.isEmpty()) {
            TextView verTv = T.text(act, ver, 12f, 0x99000000, false);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp.leftMargin = T.dp(act, 6);
            titleRow.addView(verTv, vlp);
        }
        content.addView(titleRow);

        content.addView(T.hairline(act));

        // 输入行：输入框（掩码显示 *） + 保存按钮
        // 不用 TransformationMethod —— 在部分 ROM 上它会导致 TextView 布局缓存错位崩溃
        // （IndexOutOfBoundsException in TextLine.getRunAdvance）；用 TextWatcher 手动掩码。
        final StringBuilder real = new StringBuilder();
        final boolean[] maskGuard = new boolean[1];
        final EditText et = new EditText(act);
        et.setHint("输入 socks5 代理");
        et.setTextSize(13.5f);
        et.setSingleLine(true);
        et.setTextColor(0xFF000000);
        et.setHintTextColor(0x99000000);
        et.setBackground(T.roundStroke(act, 0x14FFFFFF, T.R_THUMB, 0x66000000, 1));
        et.setPadding(T.dp(act, 12), T.dp(act, 8), T.dp(act, 12), T.dp(act, 8));
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        try { et.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); } catch (Throwable ignored) {}
        // 已配置 → 输入框以掩码显示当前代理（而非空白）
        if (cfg.s5 != null && !cfg.s5.isEmpty()) {
            real.append(cfg.s5);
            maskGuard[0] = true;
            et.setText(maskOf(real.length()));
            et.setSelection(et.getText().length());
            maskGuard[0] = false;
        }
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (maskGuard[0]) return;
                try {
                    int s1 = Math.min(Math.max(start, 0), real.length());
                    int e1 = Math.min(Math.max(start + before, 0), real.length());
                    String inserted = s.subSequence(start, Math.min(start + count, s.length())).toString();
                    real.replace(s1, e1, inserted);
                } catch (Throwable t) {
                    real.setLength(0);
                }
                maskGuard[0] = true;
                char[] stars = new char[real.length()];
                java.util.Arrays.fill(stars, '*');
                et.setText(new String(stars));
                et.setSelection(et.getText().length());
                maskGuard[0] = false;
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
        LinearLayout inputRow = new LinearLayout(act);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.addView(et, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = T.dp(act, 10);
        final Activity actF = act;
        inputRow.addView(T.buttonBlack(act, "保存", () -> {
            final String raw = real.toString().trim();
            if (raw.isEmpty()) {
                // 空输入 = 删除代理（恢复直连）
                cfg.s5 = ""; cfg.host = ""; cfg.port = 0; cfg.user = ""; cfg.pass = "";
                cfg.geoCountry = ""; cfg.geoIp = ""; cfg.geoAt = 0;
                cfg.ts = System.currentTimeMillis();
                Config.set(cfg);
                Config.persist(cfg);
                try { NetSetup.clear(actF.getClassLoader()); } catch (Throwable ignored) {}
                refreshStatus(cfg);
                try {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) actF.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
                } catch (Throwable ignored) {}
                Toast.makeText(actF, "已删除代理（恢复直连）", Toast.LENGTH_SHORT).show();
                return;
            }
            ProxyParse pp = ProxyParse.parse(raw);
            if (!pp.ok) {
                Toast.makeText(actF, "解析失败：" + pp.error, Toast.LENGTH_SHORT).show();
                return;
            }
            cfg.s5 = real.toString().trim();
            cfg.host = pp.host; cfg.port = pp.port; cfg.user = pp.user; cfg.pass = pp.pass;
            cfg.geoCountry = "";          // 换代理 → 清旧出口国家缓存
            cfg.geoIp = "";
            cfg.geoAt = 0;
            cfg.ts = System.currentTimeMillis();
            Config.set(cfg);
            Config.persist(cfg);
            maskGuard[0] = true;          // 保存后仍以掩码显示（不再明文）
            et.setText(maskOf(real.length()));
            et.setSelection(et.getText().length());
            maskGuard[0] = false;
            refreshStatus(cfg);
            try {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) actF.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
            } catch (Throwable ignored) {}
            refreshGeoAsync(actF, cfg);
            Toast.makeText(actF, "已保存并即时生效", Toast.LENGTH_SHORT).show();
        }), blp);
        LinearLayout.LayoutParams irlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        irlp.topMargin = T.dp(act, 4);
        irlp.bottomMargin = T.dp(act, 4);
        content.addView(inputRow, irlp);

        // 状态行：状态:代理已配置 | IP:23.x.x.x | 地址:美国（与开关行同款：15sp 黑色、全宽）
        LinearLayout statusRow = new LinearLayout(act);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, T.dp(act, 7), 0, T.dp(act, 7));
        final TextView stateTv = T.text(act, "", 15f, 0xFF000000, false);
        final TextView ipTv = T.text(act, "", 15f, 0xFF000000, false);
        final TextView addrTv = T.text(act, "", 15f, 0xFF000000, false);
        LinearLayout.LayoutParams iplp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        iplp.leftMargin = T.dp(act, 10);
        iplp.rightMargin = T.dp(act, 10);
        statusRow.addView(stateTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statusRow.addView(ipTv, iplp);
        statusRow.addView(addrTv);
        content.addView(statusRow);
        sStateTv = stateTv;
        sIpTv = ipTv;
        sAddrTv = addrTv;
        refreshStatus(cfg);

        // 四个开关
        content.addView(switchRow(act, "主页IP替换", cfg.swHome, b -> {
            cfg.swHome = b; save(cfg);
        }));
        content.addView(switchRow(act, "评论IP替换", cfg.swComment, b -> {
            cfg.swComment = b; save(cfg);
        }));
        content.addView(switchRow(act, "发视频IP替换", cfg.swVideo, b -> {
            cfg.swVideo = b; save(cfg);
        }));
        content.addView(switchRow(act, "自动清理2小时前的日志", cfg.swClean, b -> {
            cfg.swClean = b;
            Dlog.setAutoClean(b);
            if (b) Dlog.cleanOldEntries();
            save(cfg);
        }));

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.BOTTOM;
        root.addView(card, clp);

        // 点卡片外部 → 关闭
        root.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && ev.getY() < card.getTop()) {
                dlg.dismiss();
                return true;
            }
            return false;
        });

        // 输入法弹起时把抽屉整体上移（窗口不被挤压，键盘只做覆盖），保证输入框始终可见
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                try {
                    int ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                    int nav = insets.getInsets(android.view.WindowInsets.Type.systemBars()).bottom;
                    card.setTranslationY(-Math.max(0, ime - nav));
                } catch (Throwable ignored) {}
                return insets;
            });
        }

        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.dimAmount = 0f;      // 抽屉外不暗化
            // 关键：窗口永远占满整屏（不受状态栏/输入法 frame 挤压），输入法只做"覆盖"，
            // 否则本 ROM 会把窗口 frame 压小，底部锚定的抽屉会被整体顶上去
            lp.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            // 输入法弹出时不平移窗口
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
            w.setAttributes(lp);
            // 确保状态栏（时间/电量等）始终可见，且背景透明（不接管成默认黑色）
            w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            try { w.setStatusBarColor(Color.TRANSPARENT); } catch (Throwable ignored) {}
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    w.getInsetsController().show(android.view.WindowInsets.Type.statusBars());
                    // 背景透明且背后是浅色内容 → 状态栏图标/文字用黑色
                    w.getInsetsController().setSystemBarsAppearance(
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                } catch (Throwable ignored) {}
            }
            // 同时让窗口不 fit 系统窗：否则输入法 inset 仍会压缩内容区，把底部锚定的抽屉顶上去
            if (Build.VERSION.SDK_INT >= 30) {
                try { w.setDecorFitsSystemWindows(false); } catch (Throwable ignored) {}
            } else {
                try {
                    w.getDecorView().setSystemUiVisibility(
                            w.getDecorView().getSystemUiVisibility()
                                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
                } catch (Throwable ignored) {}
            }
        }
        dlg.show();

        // 测量 → 固定自然高度 → 抓背景做磨砂 → 挂拖拽
        card.post(() -> {
            int h = card.getHeight();
            if (h <= 0 || h > screenH * 0.6f) {
                try {
                    card.measure(View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    int mh = card.getMeasuredHeight();
                    if (mh > 0 && mh <= screenH * 0.6f) h = mh;
                } catch (Throwable ignored) {}
            }
            naturalH[0] = Math.max(h, T.dp(act, 240));
            setCardH(card, naturalH[0]);
            attachDrag(act, dlg, card, grabRow, naturalH, maxH);
            setupFrost(act, root, card, bg);
        });

        refreshGeoAsync(act, cfg);
    }

    /** 抽屉局部磨砂：PixelCopy 抓抖音窗口 → RenderEffect 模糊 → 对齐铺到卡片底图 */
    private static void setupFrost(final Activity act, final View root, final FrameLayout card, final ImageView bg) {
        if (Build.VERSION.SDK_INT < 31) return;   // RenderEffect 需 API31+
        final int[] cardLoc = new int[2];
        final int[] winLoc = new int[2];
        try {
            card.getLocationOnScreen(cardLoc);
            act.getWindow().getDecorView().getLocationOnScreen(winLoc);
            int winW = act.getWindow().getDecorView().getWidth();
            int winH = act.getWindow().getDecorView().getHeight();
            if (winW <= 0 || winH <= 0) return;
            final Bitmap shot = Bitmap.createBitmap(winW, winH, Bitmap.Config.ARGB_8888);
            final int srcTop = winLoc[1];
            PixelCopy.request(act.getWindow(), shot, result -> {
                if (result != PixelCopy.SUCCESS) {
                    Dlog.w("frost: PixelCopy failed code=" + result);
                    return;
                }
                try {
                    // 纯软件磨砂：缩图 → 盒式模糊 → 放大回填。
                    // （不用 RenderEffect：它会让窗口变成带特效层的表面，本 ROM 上会盖住状态栏）
                    new Thread(() -> {
                        Bitmap blurred = softBlur(shot);
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try {
                                bg.setImageBitmap(blurred);
                                updateFrostMatrix(root, card, bg, srcTop);
                                card.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob)
                                        -> updateFrostMatrix(root, card, bg, srcTop));
                                Dlog.boot("frost: applied (" + winW + "x" + winH + ")");
                            } catch (Throwable t2) {
                                Dlog.w("frost apply failed: " + t2);
                            }
                        });
                    }, "dyproxy-frost").start();
                } catch (Throwable t) {
                    Dlog.w("frost apply failed: " + t);
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            Dlog.w("frost setup failed: " + t);
        }
    }

    /** 缩图+两遍盒式模糊+放大：低成本磨砂（替代 RenderEffect，避免覆盖状态栏） */
    private static Bitmap softBlur(Bitmap src) {
        try {
            int w = Math.max(1, src.getWidth() / 8);
            int h = Math.max(1, src.getHeight() / 8);
            Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
            int[] px = new int[w * h];
            small.getPixels(px, 0, w, 0, 0, w, h);
            for (int pass = 0; pass < 2; pass++) {
                int[] out = new int[w * h];
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int r = 0, g = 0, b = 0, n = 0;
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dx = -1; dx <= 1; dx++) {
                                int xx = x + dx, yy = y + dy;
                                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                                int c = px[yy * w + xx];
                                r += (c >> 16) & 0xFF;
                                g += (c >> 8) & 0xFF;
                                b += c & 0xFF;
                                n++;
                            }
                        }
                        out[y * w + x] = 0xFF000000 | ((r / n) << 16) | ((g / n) << 8) | (b / n);
                    }
                }
                px = out;
            }
            small.setPixels(px, 0, w, 0, 0, w, h);
            return Bitmap.createScaledBitmap(small, src.getWidth(), src.getHeight(), true);
        } catch (Throwable t) {
            return src;
        }
    }

    private static void updateFrostMatrix(View root, FrameLayout card, ImageView bg, int srcTop) {
        try {
            int[] rootLoc = new int[2];
            root.getLocationOnScreen(rootLoc);
            int cardTopOnScreen = rootLoc[1] + card.getTop();
            int offset = cardTopOnScreen - srcTop;
            if (offset < 0) offset = 0;
            Matrix m = new Matrix();
            m.setTranslate(0f, -offset);
            bg.setImageMatrix(m);
        } catch (Throwable ignored) {}
    }

    private static void setCardH(View card, int h) {
        ViewGroup.LayoutParams lp = card.getLayoutParams();
        if (lp != null && lp.height != h) {
            lp.height = h;
            card.setLayoutParams(lp);
        }
    }

    /** 小白条拖拽：上滑伸展填充（松手自动回弹），下滑缩回/关闭 */
    private static void attachDrag(final Activity act, final Dialog dlg, final View card, View handle,
                                   final int[] naturalH, final int maxH) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            float startY;
            int startH;

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = ev.getRawY();
                        startH = card.getHeight() > 0 ? card.getHeight() : naturalH[0];
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int nh = (int) (startH - (ev.getRawY() - startY));   // 上滑 → nh 增大
                        if (nh < T.dp(act, 60)) nh = T.dp(act, 60);
                        if (nh > maxH) nh = maxH;
                        setCardH(card, nh);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        int h = card.getHeight();
                        if (h < naturalH[0] * 0.45f) {
                            dlg.dismiss();
                        } else {
                            // 上滑/下拉松手 → 自动回弹到原始高度
                            animateTo(card, h, naturalH[0]);
                        }
                        return true;
                    }
                }
                return false;
            }
        });
    }

    private static void animateTo(final View card, int from, int to) {
        ValueAnimator va = ValueAnimator.ofInt(from, to);
        va.setDuration(170);
        va.addUpdateListener(a -> setCardH(card, (int) a.getAnimatedValue()));
        va.start();
    }

    // ── 状态行 ──────────────────────────────────────────────

    private static TextView sStateTv;
    private static TextView sIpTv;
    private static TextView sAddrTv;

    private static void refreshStatus(Config cfg) {
        final Config c = Config.get(false);
        if (sStateTv != null) {
            sStateTv.setText(c.configured() ? "状态:代理已配置" : "状态:代理未配置");
        }
        if (sIpTv != null) {
            sIpTv.setText("IP:" + (c.geoIp == null || c.geoIp.isEmpty() ? "—" : c.geoIp));
        }
        if (sAddrTv != null) {
            sAddrTv.setText("地址:" + (c.geoCountry == null || c.geoCountry.isEmpty() ? "—" : c.geoCountry));
        }
    }

    /**
     * 经代理查询出口国家（预计IP）。
     * 实测接口：GET http://ip-api.com/json/?fields=status,countryCode,query
     * （这条请求走 SOCKS5 代理出去，拿到代理出口 IP 与 countryCode，
     *  再用 Locale(countryCode).getDisplayCountry(中文) 得到“美国”这类中文名）
     * 结果缓存 30 分钟并回写加密配置。
     */
    private static void refreshGeoAsync(final Activity act, final Config cfg) {
        new Thread(() -> {
            try {
                if (!cfg.configured()) return;
                if (System.currentTimeMillis() - cfg.geoAt < 30 * 60 * 1000L
                        && cfg.geoCountry != null && !cfg.geoCountry.isEmpty()) return;
                // 确保 SOCKS 认证已安装（只装认证，不依赖宿主类名 → 跨抖音版本永远可用）
                try { NetSetup.ensureAuth(cfg); } catch (Throwable ignored) {}
                Proxy p = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(cfg.host, cfg.port));
                HttpURLConnection c = (HttpURLConnection)
                        new URL("http://ip-api.com/json/?fields=status,countryCode,query").openConnection(p);
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                StringBuilder sb = new StringBuilder();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                }
                JSONObject o = new JSONObject(sb.toString());
                String cc = o.optString("countryCode", "");
                String ip = o.optString("query", "");
                String name = cc.isEmpty() ? "" : new Locale("", cc).getDisplayCountry(Locale.CHINA);
                if (!name.isEmpty()) {
                    cfg.geoCountry = name;
                    cfg.geoIp = ip;
                    cfg.geoAt = System.currentTimeMillis();
                    Config.persist(cfg);
                    Dlog.boot("geo: " + name + " (" + ip + ") via ip-api.com");
                    act.runOnUiThread(() -> {
                        if (sIpTv != null) sIpTv.setText("IP:" + (ip.isEmpty() ? "—" : ip));
                        if (sAddrTv != null) sAddrTv.setText("地址:" + name);
                    });
                }
            } catch (Throwable t) {
                Dlog.w("geo lookup failed: " + t);
            }
        }, "dyproxy-geo").start();
    }

    private static String maskOf(int n) {
        char[] c = new char[n];
        java.util.Arrays.fill(c, '*');
        return new String(c);
    }

    private static void save(Config cfg) {
        cfg.ts = System.currentTimeMillis();
        Config.set(cfg);
        Config.persist(cfg);
    }

    /** 黑白单色开关行 */
    private static View switchRow(Activity act, String label, boolean checked, final OnToggle cb) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, T.dp(act, 7), 0, T.dp(act, 7));

        row.addView(T.text(act, label, 15f, 0xFF000000, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = new Switch(act);
        sw.setChecked(checked);
        styleSwitch(sw, checked);
        sw.setOnCheckedChangeListener((v, b) -> {
            styleSwitch(sw, b);
            cb.on(b);
        });
        row.addView(sw);
        return row;
    }

    private static void styleSwitch(Switch sw, boolean on) {
        try {
            sw.setButtonTintList(ColorStateList.valueOf(0xFF000000));
            sw.setThumbTintList(ColorStateList.valueOf(on ? 0xFF000000 : 0xFFB0B0B5));
            sw.setTrackTintList(ColorStateList.valueOf(on ? 0x33000000 : 0x1F000000));
        } catch (Throwable ignored) {}
    }

    private interface OnToggle { void on(boolean b); }
}
