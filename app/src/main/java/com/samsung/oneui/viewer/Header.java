package com.samsung.oneui.viewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.util.Arrays;

/** Fixed top Liquid Glass chrome ported 1:1 from Prism Music Android with icon press expansion & pill morphing. */
final class Header extends View {
    interface Listener {
        void onHeaderButton(int icon, RectF anchor);
        void onBack();
    }

    private final Glass.Backdrop backdrop;
    private Listener listener;
    private final Glass.Surface pillGlass = new Glass.Surface();
    private final Glass.Surface backGlass = new Glass.Surface();
    private final Object blurNodeObj; // RenderNode on API 29+
    private final Paint mask = new Paint();
    private final Paint wash = new Paint();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private String title = "";
    private int[] icons = {Icons.OPEN_FILE, Icons.MORE};
    private boolean back;
    float titleAlpha, blurAlpha;
    private float contentAlpha = 1f;
    boolean onDarkContent;

    final RectF pill = new RectF(), backRect = new RectF();
    private int pressed = -1; // index into icons, or 99 for back
    private final Ui.Spring press = new Ui.Spring(640f, 0.76f, 0.002f);

    Header(Context c, Glass.Backdrop backdrop) {
        super(c);
        this.backdrop = backdrop;
        this.blurNodeObj = Build.VERSION.SDK_INT >= 29 ? new RenderNode("header-blur") : null;
        titlePaint.setTypeface(Ui.bold);
        titlePaint.setTextSize(Ui.sp(17));
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));

        pillGlass.blurDp = Ui.barBlur();
        pillGlass.refractDp = 15;
        pillGlass.edgeDp = 20;
        pillGlass.outerDp = 5;
        pillGlass.outerRefractDp = 11;
        pillGlass.vibrance = 1.35f;
        pillGlass.rim = 0.32f;
        pillGlass.edgeDark = 0.32f;
        pillGlass.shadowAlpha = 0.07f;

        backGlass.blurDp = Ui.barBlur();
        backGlass.refractDp = 15;
        backGlass.edgeDp = 20;
        backGlass.outerDp = 5;
        backGlass.outerRefractDp = 11;
        backGlass.vibrance = 1.35f;
        backGlass.rim = 0.32f;
        backGlass.edgeDark = 0.32f;
        backGlass.shadowAlpha = 0.07f;

        press.onUpdate = this::invalidate;
        morph.onUpdate = this::invalidate;
        backP.onUpdate = this::invalidate;
        morph.snap(1f);
    }

    void setListener(Listener l) { listener = l; }

    private int[] prevIcons = new int[0];
    private final RectF fromPill = new RectF(), drawn = new RectF(), expandedDrawn = new RectF();
    private final Ui.Spring morph = new Ui.Spring(340f, 0.74f, 0.002f);
    private final Ui.Spring backP = new Ui.Spring(420f, 0.72f, 0.002f);
    private boolean configured;

    void configure(String t, int[] ic, boolean b) {
        title = t == null ? "" : t;
        int[] next = ic == null ? new int[0] : ic;
        if (!Arrays.equals(next, icons)) {
            prevIcons = icons;
            fromPill.set(drawn);
            icons = next;
            if (configured) { morph.snap(0f); morph.to(1f); } else morph.snap(1f);
        }
        if (b != back || !configured) {
            back = b;
            if (configured) backP.to(b ? 1f : 0f); else backP.snap(b ? 1f : 0f);
        }
        configured = true;
        invalidate();
    }

    private float[] pendingFrom;

    float[] snapshot() {
        float W = Math.max(1, getWidth());
        if (drawn.width() < 1) return null;
        return new float[]{drawn.left / W, drawn.top, drawn.right / W, drawn.bottom};
    }

    void morphFrom(float[] g) {
        if (g == null || g.length < 4) return;
        pendingFrom = g;
        invalidate();
    }

    private void startPendingMorph() {
        if (pendingFrom == null || getWidth() <= 0 || icons.length == 0) return;
        float W = getWidth();
        fromPill.set(pendingFrom[0] * W, pendingFrom[1], pendingFrom[2] * W, pendingFrom[3]);
        pendingFrom = null;
        prevIcons = icons;
        morph.snap(0f);
        morph.to(1f);
    }

    void setScroll(float titleA, float blurA) {
        if (Math.abs(titleA - titleAlpha) < 0.003f && Math.abs(blurA - blurAlpha) < 0.003f) return;
        titleAlpha = titleA;
        blurAlpha = blurA;
        invalidate();
    }

    void setContentAlpha(float a) { contentAlpha = a; invalidate(); }

    private Object blurFxObj; // RenderEffect on API 31+

    boolean animating() { return morph.running || backP.running || press.running; }

    private boolean pillHidden;
    boolean vertical;
    private float touchX, touchY;

    void setPillHidden(boolean h) { pillHidden = h; invalidate(); }

    int barBottom() { return Ui.statusInset + Ui.dp(60); }

    RectF pillInRoot() {
        layoutRects();
        float[] o = Glass.abs(this);
        RectF a = new RectF(pill);
        a.offset(o[0], o[1]);
        return a;
    }

    private void layoutRects() {
        float top = Ui.statusInset + Ui.dpf(14);
        int rw = getRootView() != null && getRootView().getWidth() > 0 ? getRootView().getWidth() : Ui.screenW;
        int rh = getRootView() != null && getRootView().getHeight() > 0 ? getRootView().getHeight() : Ui.screenH;
        boolean rail = Dock.railFor(rw, rh);
        vertical = rail && icons.length > 1;
        float h = Ui.dpf(rail ? 60 : 46);
        float len = icons.length <= 1 ? h : Ui.dpf(rail ? 112 : 100);
        float right = getWidth() - Ui.dpf(rail ? 20 : 16);
        if (vertical) pill.set(right - h, top, right, top + len);
        else pill.set(right - len, top, right, top + h);
        if (icons.length == 0) pill.setEmpty();
        backRect.set(Ui.dpf(16), top + Ui.dpf(1), Ui.dpf(16) + Ui.dpf(44), top + Ui.dpf(45));
    }

    @Override protected void onDraw(Canvas c) {
        layoutRects();
        if (pendingFrom != null) startPendingMorph();
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Progressive RenderEffect blur strip under status bar
        if (blurAlpha > 0.01f && Build.VERSION.SDK_INT >= 31 && c.isHardwareAccelerated() && backdrop.recorded && backdrop.nodeObj != null && blurNodeObj != null && Ui.glass) {
            int sc = c.saveLayer(0, 0, w, h, null);
            RenderNode blurNode = (RenderNode) blurNodeObj;
            blurNode.setPosition(0, 0, w, h);
            if (blurFxObj == null) blurFxObj = RenderEffect.createBlurEffect(Ui.dpf(22), Ui.dpf(22), Shader.TileMode.CLAMP);
            blurNode.setRenderEffect((RenderEffect) blurFxObj);
            RecordingCanvas rc = blurNode.beginRecording(w, h);
            try {
                float[] off = Glass.offsetIn(this, backdrop);
                rc.translate(-off[0], -off[1]);
                rc.drawRenderNode((RenderNode) backdrop.nodeObj);
            } finally {
                blurNode.endRecording();
            }
            c.drawRenderNode(blurNode);
            int bg = onDarkContent ? 0xFF000000 : Ui.bg();
            wash.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{Ui.alpha(bg, 0.72f), Ui.alpha(bg, 0.35f), Ui.alpha(bg, 0f)},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, wash);
            mask.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{Ui.alpha(0xFF000000, blurAlpha), Ui.alpha(0xFF000000, blurAlpha), 0},
                    new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, mask);
            c.restoreToCount(sc);
        } else if (blurAlpha > 0.01f) {
            wash.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{Ui.alpha(Ui.bg(), 0.94f * blurAlpha), Ui.alpha(Ui.bg(), 0f)},
                    null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, wash);
        }

        if (contentAlpha <= 0.01f) return;
        int fg = Ui.text();

        // Back button: glass circle with press expansion in A style; clean chevron in G style
        float bp = backP.value;
        float titleX = Ui.lerp(Ui.dpf(Ui.gStyle ? 22 : 16), backRect.right + Ui.dpf(Ui.gStyle ? 6 : 12), Ui.clamp(bp, 0f, 1f));
        if (bp > 0.01f) {
            float pressGrow = pressed == 99 ? 1f + 0.12f * press.value : 1f;
            float s = Ui.lerp(0.35f, 1f, bp) * pressGrow;
            float ba = Ui.clamp(bp, 0f, 1f) * contentAlpha;
            RectF br = tmpRect;
            br.set(backRect.centerX() - backRect.width() / 2f * s, backRect.centerY() - backRect.height() / 2f * s,
                    backRect.centerX() + backRect.width() / 2f * s, backRect.centerY() + backRect.height() / 2f * s);
            if (!Ui.gStyle) {
                backGlass.shapes(br, br.height() / 2f, null, 0, null, 0);
                backGlass.alpha = ba;
                backGlass.tint = Ui.barTint();
                backGlass.blurDp = Ui.barBlur();
                backGlass.pressX = br.centerX(); backGlass.pressY = br.centerY();
                backGlass.pressR = Ui.dpf(36);
                backGlass.pressAmt = pressed == 99 ? press.value : 0f;
                backGlass.draw(c, this, backdrop);
            }
            Icons.draw(c, Icons.BACK, br.centerX() - Ui.dpf(1), br.centerY(), Ui.dpf(Ui.gStyle ? 24 : 22) * s, Ui.alpha(fg, ba));
        }

        // Collapsing header title (zero overlap with tablet top tab pill)
        if (titleAlpha > 0.01f && title.length() > 0) {
            titlePaint.setColor(Ui.alpha(fg, titleAlpha * contentAlpha));
            int rw = getRootView() != null && getRootView().getWidth() > 0 ? getRootView().getWidth() : Ui.screenW;
            int rh = getRootView() != null && getRootView().getHeight() > 0 ? getRootView().getHeight() : Ui.screenH;
            boolean onTablet = Dock.tabletFor(rw, rh);
            float rightBound = pill.isEmpty() ? w - Ui.dpf(16) : pill.left - Ui.dpf(12);
            if (onTablet) {
                float maxTabLeft = (w - Math.min(rw * 0.42f, Ui.dpf(440)) - Ui.dpf(92)) / 2f - Ui.dpf(12);
                rightBound = Math.min(rightBound, maxTabLeft);
            }
            float avail = rightBound - titleX;
            if (avail >= Ui.dpf(44)) {
                float rowCy = vertical ? pill.top + Ui.dpf(23) : (pill.isEmpty() ? backRect.centerY() : pill.centerY());
                float ts = Ui.gStyle ? (onTablet ? 18f : 20f) : 17f;
                titlePaint.setTextSize(Ui.sp(ts));
                while (ts > 13f && titlePaint.measureText(title) > avail) { ts -= 1f; titlePaint.setTextSize(Ui.sp(ts)); }
                CharSequence t = TextUtils.ellipsize(title, titlePaint, avail, TextUtils.TruncateAt.END);
                float baseline = rowCy + titlePaint.getTextSize() * 0.36f;
                c.drawText(t, 0, t.length(), titleX, baseline - Ui.dpf(3) * (1f - titleAlpha), titlePaint);
            }
        }

        // Right-hand Liquid Glass action pill with press-to-expand + touch glow
        float k = morph.value;
        RectF from = fromPill.isEmpty() ? pointAt(pill.isEmpty() ? fromPill : pill) : fromPill;
        RectF to = pill.isEmpty() ? pointAt(fromPill) : pill;
        drawn.set(Ui.lerp(from.left, to.left, k), Ui.lerp(from.top, to.top, k),
                Ui.lerp(from.right, to.right, k), Ui.lerp(from.bottom, to.bottom, k));
        float pillA = pill.isEmpty() ? 1f - Ui.clamp(k, 0f, 1f) : (fromPill.isEmpty() ? Ui.clamp(k * 1.5f, 0f, 1f) : 1f);
        if (drawn.width() > 1 && pillA > 0.01f && !pillHidden) {
            float grow = (pressed >= 0 && pressed != 99) ? Ui.dpf(3.2f) * press.value : 0f;
            expandedDrawn.set(drawn.left - grow, drawn.top - grow, drawn.right + grow, drawn.bottom + grow);
            float surfaceA = Ui.gStyle ? Ui.clamp(0.68f + blurAlpha * 0.32f, 0f, 1f) : 1f;
            pillGlass.shapes(expandedDrawn, Math.min(expandedDrawn.width(), expandedDrawn.height()) / 2f, null, 0, null, 0);
            pillGlass.alpha = contentAlpha * pillA * surfaceA;
            pillGlass.tint = Ui.barTint();
            pillGlass.blurDp = Ui.barBlur();
            pillGlass.pressX = touchX; pillGlass.pressY = touchY;
            pillGlass.pressR = Ui.dpf(42);
            pillGlass.pressAmt = (pressed >= 0 && pressed != 99) ? press.value : 0f;
            if (pillGlass.alpha > 0.02f) pillGlass.draw(c, this, backdrop);

            if (k < 0.5f && prevIcons.length > 0) drawIcons(c, prevIcons, expandedDrawn, 1f - k * 2f, fg);
            float inA = prevIcons.length == 0 && fromPill.isEmpty() ? Ui.clamp(k, 0f, 1f) : Ui.clamp(k * 2f - 1f, 0f, 1f);
            drawIcons(c, icons, expandedDrawn, inA, fg);
        }
    }

    private final RectF tmpRect = new RectF(), pointRect = new RectF();

    private RectF pointAt(RectF r) {
        pointRect.set(r.centerX(), r.centerY(), r.centerX(), r.centerY());
        return pointRect;
    }

    /** Used by GlassMenu so the pill's icons ride inside the drop-morph. */
    void drawGlyphs(Canvas c, RectF box, float a) {
        float keep = contentAlpha;
        contentAlpha = 1f;
        int pr = pressed;
        pressed = -1;
        drawIcons(c, icons, box, a, Ui.text());
        pressed = pr;
        contentAlpha = keep;
    }

    private void drawIcons(Canvas c, int[] set, RectF box, float a, int fg) {
        if (a <= 0.01f) return;
        int n = set.length;
        boolean vert = box.height() > box.width() * 1.2f;
        float s = 0.6f + 0.4f * a;
        for (int i = 0; i < n; i++) {
            float along = n == 1 ? 0.5f : (i * 2 + 1) / (2f * n);
            float nudge = n == 1 ? 0 : (i == 0 ? Ui.dpf(3) : -Ui.dpf(3));
            float cx = vert ? box.centerX() : box.left + box.width() * along + nudge;
            float cy = vert ? box.top + box.height() * along + nudge : box.centerY();
            boolean isP = pressed == i && press.value > 0.01f;
            float pop = isP ? 1f + 0.16f * press.value : 1f;
            if (isP) {
                fill.setColor(Ui.alpha(Ui.pressed(), press.value));
                float r = Math.min(box.width(), box.height()) / 2f - Ui.dpf(2) + Ui.dpf(2) * press.value;
                c.drawCircle(cx, cy, r, fill);
            }
            Icons.draw(c, set[i], cx, cy, Ui.dpf(23) * s * pop, Ui.alpha(fg, contentAlpha * a));
        }
    }

    private int hit(float x, float y) {
        if (back && x <= backRect.right + Ui.dpf(8) && y <= backRect.bottom + Ui.dpf(8) && y >= backRect.top - Ui.dpf(8)) return 99;
        if (!pill.isEmpty() && x >= pill.left - Ui.dpf(6) && y >= pill.top - Ui.dpf(6) && y <= pill.bottom + Ui.dpf(6)) {
            int n = icons.length;
            if (n == 0) return -1;
            int i = vertical ? (int) ((y - pill.top) / (pill.height() / n)) : (int) ((x - pill.left) / (pill.width() / n));
            return Math.max(0, Math.min(n - 1, i));
        }
        return -1;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (contentAlpha < 0.5f) return false;
                layoutRects();
                pressed = hit(e.getX(), e.getY());
                if (pressed < 0) return false;
                touchX = e.getX(); touchY = e.getY();
                press.to(1f);
                return true;
            case MotionEvent.ACTION_UP: {
                int p = pressed;
                press.to(0f);
                if (p >= 0 && hit(e.getX(), e.getY()) == p && listener != null) {
                    if (p == 99) listener.onBack();
                    else {
                        float[] o = Glass.abs(this);
                        RectF a = new RectF(pill);
                        a.offset(o[0], o[1]);
                        listener.onHeaderButton(icons[p], a);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                press.to(0f);
                return true;
        }
        return pressed >= 0;
    }
}
