package com.usama.echo;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Small helpers for building the screens in code. */
final class Ui {
    // "Midnight glass" theme: the window has a purple-blue gradient, screens are see-through on top of it.
    static final int BG = 0x00000000;
    static final int SURFACE = 0x14FFFFFF;
    static final int SURFACE2 = 0x24FFFFFF;
    static final int TEXT = 0xFFFFFFFF;
    static final int SUB = 0xFFB8B2E0;
    static final int DIM = 0xFF8F89B8;
    static final int ACCENT = 0xFF00D2FF;
    static final int ACCENT2 = 0xFF9D4EDD;
    static final int GLOW = 0xFF7DF9FF;
    static final int YT = 0xFFE53935;
    static final int PANEL = 0xF0161236;

    private static final int[] TILE_COLORS = {
            0xFF5E548E, 0xFF2D6A4F, 0xFF1D3557, 0xFF9C6644, 0xFF7B2CBF,
            0xFF005F73, 0xFFAE2012, 0xFF3A5A40, 0xFF6A4C93, 0xFF8D5524
    };

    /** Bright gradient pairs for icons and artwork. */
    private static final int[][] GRADS = {
            {0xFF00D2FF, 0xFF3A7BD5}, {0xFFF72585, 0xFF7209B7}, {0xFF4CC9F0, 0xFF4361EE},
            {0xFFFFB703, 0xFFFB8500}, {0xFFFF4D6D, 0xFFC9184A}, {0xFF06D6A0, 0xFF118AB2},
            {0xFFC77DFF, 0xFF7B2CBF}, {0xFFFF9E00, 0xFFFF5400}
    };

    static GradientDrawable gradFor(String key, float radiusPx) {
        int h = key == null ? 0 : key.hashCode();
        int[] g = GRADS[Math.abs(h % GRADS.length)];
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, g);
        d.setCornerRadius(radiusPx);
        return d;
    }

    static GradientDrawable accentGrad(float radiusPx) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACCENT, ACCENT2});
        d.setCornerRadius(radiusPx);
        return d;
    }

    static GradientDrawable ytGrad(float radiusPx) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFFF4D6D, 0xFFC9184A});
        d.setCornerRadius(radiusPx);
        return d;
    }

    /** See-through "glass" card. */
    static GradientDrawable glass(Context c, float radiusDp) {
        GradientDrawable g = round(0x17FFFFFF, dp(c, radiusDp));
        g.setStroke(dp(c, 1), 0x26FFFFFF);
        return g;
    }

    /** Cyan-to-purple text, like the "Good evening" title. */
    static void gradientText(final TextView t) {
        t.post(() -> {
            float w = t.getPaint().measureText(t.getText().toString());
            if (w <= 0) return;
            t.getPaint().setShader(new android.graphics.LinearGradient(0, 0, w, 0, GLOW, 0xFFC77DFF,
                    android.graphics.Shader.TileMode.CLAMP));
            t.invalidate();
        });
    }

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static TextView line(Context c, String s, float sp, int color, boolean bold) {
        TextView t = text(c, s, sp, color, bold);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static void ripple(View v, int baseColor, float radiusPx) {
        GradientDrawable mask = round(Color.WHITE, radiusPx);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                baseColor == 0 ? null : round(baseColor, radiusPx), mask));
    }

    static ImageButton icon(Context c, int res, int sizeDp, int tint) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(res);
        b.setColorFilter(tint);
        b.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(c, Math.max(6, sizeDp / 5f));
        b.setPadding(pad, pad, pad, pad);
        ripple(b, 0, dp(c, sizeDp));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        b.setFocusable(false);
        return b;
    }

    /** Rounded pill button. */
    static TextView pill(Context c, String label, boolean filled) {
        TextView t = text(c, label, 14, filled ? Color.BLACK : TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 16), dp(c, 9), dp(c, 16), dp(c, 9));
        if (filled) {
            t.setTextColor(TEXT);
            t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), accentGrad(dp(c, 24)),
                    round(Color.WHITE, dp(c, 24))));
        } else {
            GradientDrawable g = round(0, dp(c, 24));
            g.setColor(0x17FFFFFF);
            g.setStroke(dp(c, 1), 0x33FFFFFF);
            t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g,
                    round(Color.WHITE, dp(c, 24))));
        }
        return t;
    }

    static int colorFor(String s) {
        int h = s == null ? 0 : s.hashCode();
        return TILE_COLORS[Math.abs(h % TILE_COLORS.length)];
    }

    /** Square tile with the first letter, used where there is no album art. */
    static TextView tile(Context c, String name, int sizeDp, int color) {
        String letter = "♪";
        if (name != null) {
            String n = name.trim();
            if (!n.isEmpty()) letter = n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
        }
        TextView t = text(c, letter, sizeDp * 0.42f, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(color == SURFACE2 ? glass(c, 10) : gradFor(name, dp(c, 10)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp));
        t.setLayoutParams(lp);
        return t;
    }

    static String time(long ms) {
        if (ms <= 0) return "0:00";
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.US, "%d:%02d", m, sec);
    }

    static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    /** Top bar with a back arrow and a title. */
    static LinearLayout topBar(final android.app.Activity a, String title, int backIcon) {
        LinearLayout bar = row(a);
        bar.setPadding(dp(a, 4), dp(a, 6), dp(a, 8), dp(a, 6));
        ImageButton back = icon(a, backIcon, 44, TEXT);
        back.setOnClickListener(v -> a.finish());
        bar.addView(back);
        TextView t = line(a, title, 18, TEXT, true);
        t.setPadding(dp(a, 6), 0, 0, 0);
        bar.addView(t, weight());
        return bar;
    }
}
