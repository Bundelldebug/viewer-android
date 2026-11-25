package com.samsung.oneui.viewer;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * Prism liquid glass background with interactive press-bloom and spring recoil animation.
 * Refracts the live stage backdrop without opaque solid fills overlapping inside the pill.
 */
final class GlassDrawable extends Drawable {
    private static final PathInterpolator SPRING = new PathInterpolator(0.22f, 1f, 0.36f, 1f);

    final Glass.Surface surface = new Glass.Surface();
    private final Glass.Backdrop backdrop;
    private final float radius;
    private final RenderNode under = new RenderNode("glass-under");
    private final Paint basePaint = new Paint();
    private final RectF r = new RectF();
    private ValueAnimator pressAnim;

    private GlassDrawable(Glass.Backdrop backdrop, int base, float radius) {
        this.backdrop = backdrop;
        this.radius = radius;
        basePaint.setColor(base | 0xFF000000);
        under.setClipToBounds(false);
        surface.shadowAlpha = 0f;
        surface.strokeColor = Ui.glassStroke();
    }

    /** Tool pills and floating bars (Prism tab-bar optics, high transparency). */
    static GlassDrawable bar(Glass.Backdrop backdrop, int base, float radiusPx) {
        GlassDrawable d = new GlassDrawable(backdrop, base, radiusPx);
        Glass.Surface s = d.surface;
        s.refractDp = 17f; s.edgeDp = 26f; s.outerDp = 7f; s.outerRefractDp = 12f;
        s.chroma = 0.04f; s.vibrance = 1.35f; s.rim = 0.34f;
        s.tint = Ui.barTint(); s.blurDp = Ui.barBlur();
        return d;
    }

    /** Denser glass panels (search / OCR / floating menus). */
    static GlassDrawable panel(Glass.Backdrop backdrop, int base, float radiusPx) {
        GlassDrawable d = bar(backdrop, base, radiusPx);
        d.surface.rim = 0.42f;
        d.surface.tint = Ui.menuTint();
        d.surface.blurDp = 14f;
        return d;
    }

    /** Attaches Prism-style liquid press glow + subtle spring recoil to a glass host view. */
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
                    v.animate().scaleX(0.985f).scaleY(0.985f).setDuration(120).setInterpolator(SPRING).start();
                } else {
                    invalidateSelf();
                }
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                animatePress(0f);
                v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(SPRING).start();
            }
            return false;
        });
    }

    /** Utility for Prism spring pop-in animation on floating bars and subbars. */
    static void popIn(View v) {
        if (v == null || "robolectric".equals(android.os.Build.FINGERPRINT)) return;
        v.setAlpha(0f);
        v.setTranslationY(Ui.dpf(10f));
        v.setScaleX(0.96f);
        v.setScaleY(0.96f);
        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(240).setInterpolator(SPRING).start();
    }

    /** Utility for Prism button touch recoil without adding any opaque solid background. */
    static void attachRecoil(View button) {
        if (button == null) return;
        button.setOnTouchListener((v, ev) -> {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).setInterpolator(SPRING).start();
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(SPRING).start();
            }
            return false;
        });
    }

    private void animatePress(float target) {
        if (pressAnim != null) pressAnim.cancel();
        pressAnim = ValueAnimator.ofFloat(surface.pressAmt, target);
        pressAnim.setDuration(target > 0f ? 140 : 280);
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
        if (host != null && c.isHardwareAccelerated()) {
            int w = host.getWidth(), h = host.getHeight();
            float pad = Ui.dpf(60);
            under.setPosition(0, 0, Math.max(1, w), Math.max(1, h));
            RecordingCanvas rc = under.beginRecording(Math.max(1, w), Math.max(1, h));
            try {
                rc.drawRect(-pad, -pad, w + pad, h + pad, basePaint);
                if (backdrop != null && backdrop.recorded && backdrop.isAttachedToWindow()) {
                    float[] off = Glass.offsetIn(host, backdrop);
                    rc.save();
                    rc.translate(-off[0], -off[1]);
                    rc.drawRenderNode(backdrop.node);
                    rc.restore();
                }
            } finally {
                under.endRecording();
            }
            surface.extra = under;
        } else {
            surface.extra = null;
        }
        surface.draw(c, host, null);
    }

    @Override public void getOutline(Outline o) {
        android.graphics.Rect b = getBounds();
        o.setRoundRect(b, Math.min(radius, Math.min(b.width(), b.height()) / 2f));
    }

    @Override public void setAlpha(int alpha) { surface.alpha = alpha / 255f; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
