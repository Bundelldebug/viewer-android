package com.samsung.oneui.viewer;

import android.content.Context;
import android.graphics.Color;

/** The few material values the Prism glass needs (same numbers as Prism's Ui.java, "high" glass level). */
final class Ui {
    private Ui() {}

    static float density = 3f;
    static boolean dark;
    static boolean glass = true;

    static void init(Context c, boolean darkTheme) {
        density = c.getResources().getDisplayMetrics().density;
        dark = darkTheme;
    }

    static float dpf(float v) { return v * density; }
    static int bg() { return dark ? 0xFF000000 : 0xFFFFFFFF; }
    static int glassTint() { return dark ? 0x5C1C1C1E : 0x5CFAFAFC; }
    static int glassStroke() { return dark ? 0x33FFFFFF : 0x24000000; }
    /** Tab bar / tool pills: high transparency glass. */
    static int barTint() { return dark ? 0x521C1C1E : 0x40FFFFFF; }
    static float barBlur() { return 2f; }
    /** Menus and denser panels. */
    static int menuTint() { return dark ? 0x8C1C1C1E : 0x8CFAFAFC; }

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
}
