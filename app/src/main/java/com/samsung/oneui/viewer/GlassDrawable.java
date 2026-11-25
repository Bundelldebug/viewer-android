package com.samsung.oneui.viewer;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * Prism Music identical liquid glass renderer:
 * Draws directly against the full-screen Glass.Backdrop (no clipped intermediate RenderNode,
 * no Android elevation outline double-border) with Prism press-bloom and spring recoil.
 */
final class GlassDrawable extends Drawable {
    private static final PathInterpolator SPRING = new PathInterpolator(0.22f, 1f, 0.36f, 1f);

    final Glass.Surface surface = new Glass.Surface();
    private final Glass.Backdrop backdrop;
    private final float radius;
    private final RectF r = new RectF();
    private ValueAnimator pressAnim;

    private GlassDrawable(Glass.Backdrop backdrop, float radius) {
        this.backdrop = backdrop;
        this.radius = radius;
        surface.shadowAlpha = 0.08f;
        surface.strokeColor = Ui.glassStroke();
    }

    /** Prism Header/Dock liquid glass optics (matches Prism_Music_Android Header.java). */
    static GlassDrawable bar(Glass.Backdrop backdrop, int ignoredBase, float radiusPx) {
        GlassDrawable d = new GlassDrawable(backdrop, radiusPx);
        Glass.Surface s = d.surface;
        s.blurDp = 10f;
        s.refractDp = 14f;
        s.edgeDp = 20f;
        s.outerDp = 5f;
        s.outerRefractDp = 8f;
        s.chroma = 0f;
        s.vibrance = 1.35f;
        s.rim = 0.28f;
        s.tint = Ui.barTint();
        return d;
    }

    /** Denser glass panels (search / OCR / floating sheets). */
    static GlassDrawable panel(Glass.Backdrop backdrop, int ignoredBase, float radiusPx) {
        GlassDrawable d = bar(backdrop, ignoredBase, radiusPx);
        d.surface.rim = 0.34f;
        d.surface.tint = Ui.menuTint();
        d.surface.blurDp = 14f;
        return d;
    }

    /** Applies clean Prism glass to a View without elevation/outline double borders. */
    static void attachGlass(View host, Drawable bg) {
        if (host == null) return;
        host.setBackground(bg);
        host.setElevation(0f);
        host.setOutlineProvider(null);
        host.setClipToOutline(false);
        if (bg instanceof GlassDrawable) {
            ((GlassDrawable) bg).attachTouch(host);
        }
    }

    void attachTouch(View host) {
        if (host == null) return;
        host.setOnTouchListener((v, ev) -> {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_MOVE) {
                surface.pressX = ev.getX();
                surface.pressY = ev.getY();
                surface.pressR = Math.max(Ui.dpf(44f), Math.min(v.getWidth(), v.getHeight()) * 0.9f);
                if (act == MotionEvent.ACTION_DOWN) {
                    animatePress(1f);
                    v.animate().scaleX(0.985f).scaleY(0.985f).setDuration(110).setInterpolator(SPRING).start();
                } else {
                    invalidateSelf();
                }
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                animatePress(0f);
                v.animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(SPRING).start();
            }
            return false;
        });
    }

    static void popIn(View v) {
        if (v == null || "robolectric".equals(android.os.Build.FINGERPRINT)) return;
        v.setAlpha(0f);
        v.setTranslationY(Ui.dpf(10f));
        v.setScaleX(0.96f);
        v.setScaleY(0.96f);
        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(240).setInterpolator(SPRING).start();
    }

    static void attachRecoil(View button) {
        if (button == null) return;
        button.setOnTouchListener((v, ev) -> {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(100).setInterpolator(SPRING).start();
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(SPRING).start();
            }
            return false;
        });
    }

    private void animatePress(float target) {
        if (pressAnim != null) pressAnim.cancel();
        pressAnim = ValueAnimator.ofFloat(surface.pressAmt, target);
        pressAnim.setDuration(target > 0f ? 130 : 260);
        pressAnim.setInterpolator(SPRING);
        pressAnim.addUpdateListener(a -> {
            surface.pressAmt = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        pressAnim.start();
    }

    @Override public void draw(Canvas c) {
        View host = getCallback() instanceof View ? (View) getCallback() : null;
        r.set(getBounds());
        if (r.isEmpty()) return;
        float rad = Math.min(radius, Math.min(r.width(), r.height()) / 2f);
        surface.shapes(r, rad, null, 0, null, 0);
        surface.extra = null;
        // Pass backdrop directly (same as Prism Header & Dock): zero clipped edges, zero double rim
        surface.draw(c, host, (backdrop != null && backdrop.recorded && backdrop.isAttachedToWindow()) ? backdrop : null);
    }

    @Override public void setAlpha(int alpha) { surface.alpha = alpha / 255f; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
