package com.samsung.oneui.viewer;

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

/** Theme, metrics, typography and spring physics ported 1:1 from Prism Music Android. */
final class Ui {
    private Ui() {}

    static float density = 3f;
    static float fontScale = 1f;
    static boolean dark;
    static boolean glass = true;
    static int screenW, screenH;
    static int statusInset, navInset, imeInset;

    // Default Viewer Samsung Blue, plus all Prism accent presets
    static final int ACCENT_DEFAULT = 0xFF0381FE;
    static int ACCENT = ACCENT_DEFAULT;
    static final String[] ACCENT_KEYS = {"blue", "red", "violet", "teal", "green", "orange", "pink", "dynamic"};
    static final int[] ACCENT_COLORS = {0xFF0381FE, 0xFFE92D44, 0xFF7B61FF, 0xFF0FB59D, 0xFF28B457, 0xFFF26D21, 0xFFE84593, 0};
    static final String[] ACCENT_NAMES = {"블루 (기본)", "레드", "바이올렛", "틸", "그린", "오렌지", "핑크", "시스템 다이나믹"};

    static final TimeInterpolator EMPHASIZED = new PathInterpolator(0.2f, 0f, 0f, 1f);
    static final TimeInterpolator DECEL = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    static final TimeInterpolator STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);
    static final TimeInterpolator ACCEL = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);
    static final TimeInterpolator ONEUI_RECOIL_PRESS = new PathInterpolator(0f, 0f, 1f, 1f);
    static final TimeInterpolator ONEUI_RECOIL_RELEASE = new PathInterpolator(0.22f, 0.25f, 0f, 1f);
    static final TimeInterpolator ONEUI_SINE_80 = new PathInterpolator(0.33f, 0f, 0.20f, 1f);
    static final TimeInterpolator ONEUI_SINE_90 = new PathInterpolator(0.33f, 0f, 0.10f, 1f);
    static final TimeInterpolator ONEUI_POPUP_ENTER = new PathInterpolator(0.365f, 0.483f, 0f, 1.215f);

    static Typeface regular, medium, semibold, bold, heavy;

    static float glassT = 0.5f;
    static boolean unfoldTablet;
    static boolean gStyle;

    static String accentKey(SharedPreferences p) { return p.getString("accent_color", "blue"); }

    static int resolveAccent(Context c, String key) {
        if ("dynamic".equals(key) && Build.VERSION.SDK_INT >= 31) {
            try {
                int col = c.getColor(dark ? android.R.color.system_accent1_300 : android.R.color.system_accent1_600);
                return (col & 0xFFFFFF) | 0xFF000000;
            } catch (Throwable ignored) {
                return ACCENT_DEFAULT;
            }
        }
        for (int i = 0; i < ACCENT_KEYS.length; i++) {
            if (ACCENT_KEYS[i].equals(key) && ACCENT_COLORS[i] != 0) return ACCENT_COLORS[i];
        }
        return ACCENT_DEFAULT;
    }

    static void init(Context c, SharedPreferences p) {
        density = c.getResources().getDisplayMetrics().density;
        fontScale = Math.min(1.4f, c.getResources().getConfiguration().fontScale);
        screenW = c.getResources().getDisplayMetrics().widthPixels;
        screenH = c.getResources().getDisplayMetrics().heightPixels;
        if (p.contains("theme")) {
            String theme = p.getString("theme", "system");
            if ("dark".equals(theme)) dark = true;
            else if ("light".equals(theme)) dark = false;
            else dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        } else {
            // Backward compatibility with previous Viewer prefs (system_theme / dark_theme)
            boolean sys = p.getBoolean("system_theme", true);
            dark = sys
                    ? (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                    : p.getBoolean("dark_theme", true);
        }
        glass = p.getBoolean("glass", true);
        glassT = clamp(p.getFloat("glass_t", 0.5f), 0f, 1f);
        unfoldTablet = "tablet".equals(p.getString("unfold_ui", "fold"));
        gStyle = "oneui".equals(p.getString("app_style", "prism"));
        ACCENT = resolveAccent(c, accentKey(p));
        Typeface base = Typeface.SANS_SERIF;
        if (Build.VERSION.SDK_INT >= 28) {
            regular = Typeface.create(base, 400, false);
            medium = Typeface.create(base, 500, false);
            semibold = Typeface.create(base, 600, false);
            bold = Typeface.create(base, 700, false);
            heavy = Typeface.create(base, 800, false);
        } else {
            regular = Typeface.create(base, Typeface.NORMAL);
            medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            semibold = Typeface.create("sans-serif-medium", Typeface.BOLD);
            bold = Typeface.create(base, Typeface.BOLD);
            heavy = Typeface.create(base, Typeface.BOLD);
        }
    }

    static int dp(float v) { return Math.round(v * density); }
    static float dpf(float v) { return v * density; }
    static float sp(float v) { return v * density * fontScale; }

    static boolean cover() {
        float wd = screenW / Math.max(0.5f, density);
        float ratio = screenH / (float) Math.max(1, screenW);
        return wd < 600 && ratio > 1f && ratio <= 1.75f;
    }

    static boolean wideInner() {
        float wd = screenW / Math.max(0.5f, density), hd = screenH / Math.max(0.5f, density);
        float minD = Math.min(wd, hd), maxD = Math.max(wd, hd);
        return minD >= 600 && (maxD / Math.max(1f, minD)) <= 1.45f;
    }

    // ---- Prism A & One UI 9.5 G palette ----
    static int bg() { return dark ? (gStyle ? 0xFF010102 : 0xFF000000) : (gStyle ? 0xFFF2F2F5 : 0xFFFFFFFF); }
    static int groupedBg() { return dark ? (gStyle ? 0xFF010102 : 0xFF000000) : (gStyle ? 0xFFF2F2F5 : 0xFFF2F2F7); }
    static int card() { return dark ? (gStyle ? 0xFF17171A : 0xFF1C1C1E) : 0xFFFFFFFF; }
    static int text() { return dark ? (gStyle ? 0xFFFCFCFF : 0xFFFFFFFF) : (gStyle ? 0xFF010102 : 0xFF000000); }
    static int text2() { return dark ? (gStyle ? 0xFF99999E : 0x99EBEBF5) : (gStyle ? 0xFF77777A : 0x993C3C43); }
    static int text3() { return dark ? 0x66EBEBF5 : 0x4D3C3C43; }
    static int chip() { return dark ? (gStyle ? 0xFF252528 : 0xFF1C1C1E) : (gStyle ? 0xFFE8E8ED : 0xFFF2F1F6); }
    static int separator() { return dark ? (gStyle ? 0x1EFFFFFF : 0x33FFFFFF) : (gStyle ? 0x10000000 : 0x1F000000); }
    static int placeholder() { return dark ? 0xFF2C2C2E : 0xFFE9E9EE; }
    static int glassTint() { return dark ? 0x5C1C1C1E : 0x5CFAFAFC; }
    static int glassStroke() { return dark ? 0x33FFFFFF : 0x24000000; }
    static int gStroke() { return dark ? 0x1FFFFFFF : 0x12000000; }
    static float cardRadius() { return gStyle ? 26f : 18f; }

    static int gTint(int t) {
        int a = t >>> 24;
        if (a >= 242) return t;
        int rgb = (dark ? 0xFF3A3A3D : 0xFFFCFCFF) & 0xFFFFFF;
        int minA = Math.round(lerp(dark ? 236f : 244f, dark ? 182f : 188f, glassT));
        int ga = Math.max(a, minA);
        return (ga << 24) | rgb;
    }

    static int glassBlend(int opaque, int medium, int high) {
        return glassT >= 0.5f ? mix(medium, high, (glassT - 0.5f) * 2f) : mix(opaque, medium, glassT * 2f);
    }

    static int barTint() {
        return dark ? glassBlend(0xFF1C1C1E, 0xA61C1C1E, 0x521C1C1E) : glassBlend(0xFFFAFAFC, 0xA8FAFAFC, 0x40FFFFFF);
    }

    static float barBlur() { return glassT >= 0.5f ? lerp(6f, 2f, (glassT - 0.5f) * 2f) : 6f; }

    static int menuTint() {
        return dark ? glassBlend(0xF51C1C1E, 0xB81C1C1E, 0x8C1C1C1E) : glassBlend(0xF5FAFAFC, 0xBDFAFAFC, 0x8CFAFAFC);
    }

    static int pressed() { return dark ? (gStyle ? 0x33FFFFFF : 0x1FFFFFFF) : (gStyle ? 0x1A000000 : 0x0F000000); }

    static int alpha(int color, float a) {
        return (Math.round(Color.alpha(color) * Math.max(0f, Math.min(1f, a))) << 24) | (color & 0xFFFFFF);
    }

    static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, aa = a >>> 24;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255, ba = b >>> 24;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    static TextView text(Context c, CharSequence s, float sizeSp, Typeface tf, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    static GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dpf(radiusDp));
        return g;
    }

    /** Press recoil (scale down or expand on touch, spring back on release). */
    static void press(View v, float scale) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(scale).scaleY(scale)
                            .setDuration(gStyle ? 100 : 140)
                            .setInterpolator(gStyle ? ONEUI_RECOIL_PRESS : STANDARD).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f)
                            .setDuration(gStyle ? 350 : 420)
                            .setInterpolator(gStyle ? ONEUI_RECOIL_RELEASE : new Overshoot(1.6f)).start();
                    break;
            }
            return false;
        });
    }

    static final class Overshoot implements TimeInterpolator {
        final float tension;
        Overshoot(float t) { tension = t; }
        @Override public float getInterpolation(float t) {
            t -= 1.0f;
            return t * t * ((tension + 1) * t + tension) + 1.0f;
        }
    }

    static ValueAnimator animate(float from, float to, long ms, TimeInterpolator in, ValueAnimator.AnimatorUpdateListener l) {
        ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(ms);
        a.setInterpolator(in);
        a.addUpdateListener(l);
        a.start();
        return a;
    }

    static LayoutTransition appear() {
        LayoutTransition t = new LayoutTransition();
        ObjectAnimator in = ObjectAnimator.ofPropertyValuesHolder((Object) null,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, dpf(14), 0f));
        in.setInterpolator(EMPHASIZED);
        t.setAnimator(LayoutTransition.APPEARING, in);
        t.setDuration(LayoutTransition.APPEARING, 340);
        t.setStagger(LayoutTransition.APPEARING, 28);
        t.setStartDelay(LayoutTransition.APPEARING, 0);
        t.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
        t.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
        t.disableTransitionType(LayoutTransition.DISAPPEARING);
        t.setAnimateParentHierarchy(false);
        return t;
    }

    /** Critically-damped spring driven by Choreographer (exact Prism spring engine). */
    static final class Spring implements Choreographer.FrameCallback {
        float value, target, velocity;
        float stiffness, damping;
        float precision;
        boolean running;
        private long last;
        Runnable onUpdate, onEnd;

        Spring(float stiffness, float dampingRatio, float precision) {
            this.stiffness = stiffness;
            this.damping = dampingRatio;
            this.precision = precision;
        }

        void to(float t) {
            target = t;
            if (!running) {
                running = true;
                last = 0;
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        void snap(float v) {
            if (running) Choreographer.getInstance().removeFrameCallback(this);
            running = false;
            value = target = v;
            velocity = 0;
            if (onUpdate != null) onUpdate.run();
        }

        @Override public void doFrame(long now) {
            float dt = last == 0 ? 1f / 120f : Math.min((now - last) / 1e9f, 1f / 30f);
            last = now;
            int steps = 6;
            float h = dt / steps;
            float w0 = (float) Math.sqrt(stiffness);
            float c = 2f * damping * w0;
            for (int i = 0; i < steps; i++) {
                float a = -stiffness * (value - target) - c * velocity;
                velocity += a * h;
                value += velocity * h;
            }
            if (Math.abs(velocity) < precision * 6 && Math.abs(value - target) < precision) {
                value = target;
                velocity = 0;
                running = false;
                if (onUpdate != null) onUpdate.run();
                if (onEnd != null) onEnd.run();
                return;
            }
            if (onUpdate != null) onUpdate.run();
            Choreographer.getInstance().postFrameCallback(this);
        }
    }
}
