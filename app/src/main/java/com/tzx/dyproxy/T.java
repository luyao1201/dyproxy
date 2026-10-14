package com.tzx.dyproxy;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 设计系统（照 Apple Liquid Glass tokens，移植自 qqgold-lsposed 的 T.kt）
 */
public final class T {
    public static final int BG = 0xFFF5F5F7;
    public static final int SURFACE = 0xFFFFFFFF;
    public static final int FIELD = 0xFFF5F5F7;
    public static final int TEXT = 0xFF1D1D1F;
    public static final int TEXT_BODY = 0xFF424245;
    public static final int TEXT_2 = 0xFF6E6E73;
    public static final int TEXT_3 = 0xFF86868B;
    public static final int FAINT = 0xFFD2D2D7;
    public static final int HAIRLINE = 0x14000000;
    public static final int ACCENT = 0xFF0071E3;
    public static final int LIVE = 0xFF30D158;
    public static final int RED = 0xFFD70015;
    public static final int ORANGE = 0xFFFF6B00;

    public static final int R_THUMB = 12;
    public static final int R_CARD = 18;
    public static final int R_PANEL = 22;
    public static final int R_PILL = 999;

    private T() {}

    public static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable round(Context ctx, int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp >= R_PILL ? dp(ctx, 100) : dp(ctx, radiusDp));
        return g;
    }

    public static GradientDrawable roundStroke(Context ctx, int color, int radiusDp, int borderColor, int borderDp) {
        GradientDrawable g = round(ctx, color, radiusDp);
        g.setStroke(dp(ctx, borderDp), borderColor);
        return g;
    }

    public static TextView text(Context ctx, String s, float size, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    /** 主操作：蓝色胶囊 */
    public static TextView primary(Context ctx, String label, float size, Runnable onClick) {
        final TextView b = text(ctx, label, size, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(ctx, ACCENT, R_PILL), null));
        b.setClickable(true);
        b.setFocusable(true);
        b.setMinHeight(dp(ctx, 40));
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    /** 次级按钮：浅底 + 发丝描边 */
    public static TextView quiet(Context ctx, String label, Runnable onClick) {
        final TextView b = text(ctx, label, 13f, TEXT_2, false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x14000000),
                roundStroke(ctx, 0x66FFFFFF, R_THUMB, 0x1F000000, 1), null));
        b.setClickable(true);
        b.setFocusable(true);
        b.setMinHeight(dp(ctx, 36));
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    /** 发丝分隔线 */
    public static View hairline(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(HAIRLINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 0) + 1));
        lp.topMargin = dp(ctx, 6);
        lp.bottomMargin = dp(ctx, 6);
        v.setLayoutParams(lp);
        return v;
    }

    /** 顶部圆角玻璃面（磨砂玻璃卡片用） */
    public static GradientDrawable roundTop(Context ctx, int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        float r = dp(ctx, radiusDp);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return g;
    }

    /** 黑色描边 + 黑色文字按钮（透明玻璃底） */
    public static TextView buttonBlack(Context ctx, String label, Runnable onClick) {
        final TextView b = text(ctx, label, 13f, 0xFF000000, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(ctx, 14), dp(ctx, 7), dp(ctx, 14), dp(ctx, 7));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x14000000),
                roundStroke(ctx, 0x33FFFFFF, R_PILL, 0xFF000000, 1), null));
        b.setClickable(true);
        b.setFocusable(true);
        b.setMinHeight(dp(ctx, 34));
        b.setOnClickListener(v -> onClick.run());
        return b;
    }
}
