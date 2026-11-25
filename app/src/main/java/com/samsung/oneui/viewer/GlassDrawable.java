package com.samsung.oneui.viewer;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * A view background drawn as Prism liquid glass. What it refracts: the viewer stage (a {@link Glass.Backdrop})
 * laid over the workspace colour, so a toolbar that floats beside the stage still reads as glass on a plain surface.
 * Shadow comes from the host's elevation (the outline below), never from drawing outside the view.
 */
final class GlassDrawable extends Drawable {
    final Glass.Surface surface = new Glass.Surface();
    private final Glass.Backdrop backdrop;
    private final int base;
    private final float radius;
    private final RenderNode under = new RenderNode("glass-under");
    private final Paint basePaint = new Paint();
    private final RectF r = new RectF();

    private GlassDrawable(Glass.Backdrop backdrop, int base, float radius) {
        this.backdrop = backdrop;
        this.base = base;
        this.radius = radius;
        basePaint.setColor(base | 0xFF000000);
        under.setClipToBounds(false);
        surface.shadowAlpha = 0f;
        surface.strokeColor = Ui.glassStroke();
    }

    /** Tool pills and sub-bars (Prism tab-bar optics, high transparency). */
    static GlassDrawable bar(Glass.Backdrop backdrop, int base, float radiusPx) {
        GlassDrawable d = new GlassDrawable(backdrop, base, radiusPx);
        Glass.Surface s = d.surface;
        s.refractDp = 17f; s.edgeDp = 26f; s.outerDp = 7f; s.outerRefractDp = 12f;
        s.chroma = 0f; s.vibrance = 1.35f; s.rim = 0.32f;
        s.tint = Ui.barTint(); s.blurDp = Ui.barBlur();
        return d;
    }

    /** Overlays that hold text fields (search / OCR bar): Prism menu optics with the denser menu tint. */
    static GlassDrawable panel(Glass.Backdrop backdrop, int base, float radiusPx) {
        GlassDrawable d = bar(backdrop, base, radiusPx);
        d.surface.rim = 0.4f;
        d.surface.tint = Ui.menuTint();
        return d;
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
