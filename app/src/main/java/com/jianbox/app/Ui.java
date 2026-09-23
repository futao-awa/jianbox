package com.jianbox.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static int GREEN = Color.rgb(76, 175, 80);
    static int GREEN_DARK = Color.rgb(27, 70, 32);
    static int GREEN_SOFT = Color.rgb(232, 245, 233);
    static int GREEN_PALE = Color.rgb(247, 251, 247);
    static int BORDER = Color.rgb(200, 230, 201);
    static int TEXT = Color.rgb(26, 58, 26);
    static int MUTED = Color.rgb(100, 125, 100);
    static final int RED = Color.rgb(239, 83, 80);
    static boolean GLASS;
    static int SURFACE = Color.WHITE;
    static int SOFT_SURFACE = Color.rgb(247, 251, 247);

    static void applyAccent(int accent) {
        GREEN = 0xFF000000 | (accent & 0x00FFFFFF);
        GREEN_DARK = blend(GREEN, Color.BLACK, .55f);
        GREEN_SOFT = blend(GREEN, Color.WHITE, .88f);
        GREEN_PALE = blend(GREEN, Color.WHITE, .96f);
        BORDER = blend(GREEN, Color.WHITE, .70f);
        TEXT = blend(GREEN_DARK, Color.BLACK, .18f);
        MUTED = blend(GREEN_DARK, Color.WHITE, .42f);
    }

    static void applyTheme(JianData data) {
        String style=data.appStyle();
        if ("midnight".equals(style)) applyAccent(0xFF607DFF);
        else if ("rose".equals(style)) applyAccent(0xFFCE5D81);
        else if ("glass".equals(style)) applyAccent(0xFF48A873);
        else applyAccent(data.appAccentColor());
        GLASS = "glass".equals(style);
        if ("midnight".equals(style)) { GREEN_PALE=0xFF111721; GREEN_SOFT=0xFF202A3A; SURFACE=0xF0182230; SOFT_SURFACE=0xD8243042; BORDER=0x665B78A5; TEXT=0xFFF1F5FF; MUTED=0xFFB7C3D5; }
        else if ("rose".equals(style)) { GREEN_PALE=0xFFFFF3F7; GREEN_SOFT=0xFFFFE5EE; SURFACE=0xFFFFFCFD; SOFT_SURFACE=0xFFFFF0F5; BORDER=0xFFFFB8CC; TEXT=0xFF512036; MUTED=0xFF916478; }
        else { SURFACE = GLASS ? 0xD9FFFFFF : Color.WHITE; SOFT_SURFACE = GLASS ? 0xAFFFFFFF : GREEN_PALE; }
    }

    private static int blend(int from, int to, float amount) {
        float a = Math.max(0f, Math.min(1f, amount));
        int r = Math.round(Color.red(from) * (1f-a) + Color.red(to) * a);
        int g = Math.round(Color.green(from) * (1f-a) + Color.green(to) * a);
        int b = Math.round(Color.blue(from) * (1f-a) + Color.blue(to) * a);
        return Color.rgb(r,g,b);
    }

    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable bg(int color, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static GradientDrawable bordered(int color, int stroke, float radiusDp, Context c) {
        GradientDrawable d = bg(color, radiusDp, c);
        d.setStroke(dp(c, 1), stroke);
        return d;
    }

    static TextView text(Context c, String value, float sp, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        v.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    static TextView label(Context c, String value) {
        TextView v = text(c, value, 10, GREEN, true);
        v.setAllCaps(true);
        v.setLetterSpacing(.12f);
        return v;
    }

    static LinearLayout vertical(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout horizontal(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = vertical(c);
        l.setPadding(dp(c, 12), dp(c, 11), dp(c, 12), dp(c, 11));
        l.setBackground(bordered(SURFACE, GLASS ? 0x66FFFFFF : BORDER, 14, c));
        l.setElevation(dp(c, GLASS ? 5 : 1));
        return l;
    }

    static Button button(Context c, String label, boolean primary) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(primary ? Color.WHITE : TEXT);
        b.setBackground(primary ? bg(GREEN, 9, c) : bordered(SURFACE, BORDER, 9, c));
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        return b;
    }

    static TextView icon(Context c, String value, int color) {
        TextView v = text(c, value, 18, color, true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(bg(color == Color.WHITE ? GREEN : GREEN_SOFT, 11, c));
        return v;
    }

    static LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    static LinearLayout.LayoutParams weight(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    static void margin(View view, int left, int top, int right, int bottom, Context c) {
        ViewGroup.LayoutParams raw = view.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) raw;
        p.setMargins(dp(c, left), dp(c, top), dp(c, right), dp(c, bottom));
        view.setLayoutParams(p);
    }

    static void selectable(View v) {
        v.setClickable(true);
        v.setFocusable(true);
    }

    private Ui() {}
}
