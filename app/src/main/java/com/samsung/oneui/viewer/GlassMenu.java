package com.samsung.oneui.viewer;

import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drop-morphing Liquid Glass context menu & sheet ported 1:1 from Prism Music Android:
 * gathers the tapped glass pill/button into a round drop, swells into the menu with spring overshoot,
 * carries the tapped button's glyphs inside the refracting glass, and shrinks back into it on dismiss.
 */
final class GlassMenu extends FrameLayout {
    static final class Entry {
        final String title;
        final int icon;
        final boolean checked;
        final Runnable run;
        boolean checkable;

        Entry(String title, int icon, boolean checked, Runnable run) {
            this.title = title;
            this.icon = icon;
            this.checked = checked;
            this.run = run;
        }

        Entry checkable() { this.checkable = true; return this; }
    }

    private final MainActivity main;
    private final RectF anchor, target = new RectF(), cur = new RectF();
    private final Glass.Surface glass = new Glass.Surface();
    private final ScrollView scroller;
    private final LinearLayout list;
    final Ui.Spring p = new Ui.Spring(420f, 0.82f, 0.002f);
    private boolean closing;
    private boolean sheet;
    private View source;
    private boolean fromHeader;
    private float blobRadius;
    private boolean restored;
    private final Object extraNodeObj; // RenderNode on API 29+

    private static final Set<View> HIDDEN_SOURCES = new HashSet<>();

    static GlassMenu show(MainActivity m, RectF anchor, List<Entry> entries) {
        GlassMenu g = new GlassMenu(m, anchor, entries);
        m.root.addView(g, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return g;
    }

    static GlassMenu sheet(MainActivity m, RectF anchor, String title, List<Entry> entries) {
        return sheet(m, anchor, title, entries, null);
    }

    static GlassMenu sheet(MainActivity m, RectF anchor, String title, List<Entry> entries, View source) {
        GlassMenu g = new GlassMenu(m, anchor, entries);
        g.sheet = true;
        g.source = source;
        if (source != null) {
            HIDDEN_SOURCES.add(source);
            source.animate().cancel();
            source.setAlpha(0f);
            source.setScaleX(1f);
            source.setScaleY(1f);
        }
        TextView t = Ui.text(m, title, 17, Ui.semibold, Ui.text());
        t.setGravity(Gravity.CENTER);
        g.list.addView(t, 0, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(50)));
        View grip = new View(m);
        grip.setBackground(Ui.round(Ui.text3(), 3));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(5));
        gl.gravity = Gravity.CENTER_HORIZONTAL;
        gl.topMargin = Ui.dp(4);
        g.list.addView(grip, 0, gl);
        m.root.addView(g, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return g;
    }

    private GlassMenu(MainActivity m, RectF anchor, List<Entry> entries) {
        super(m);
        main = m;
        this.anchor = new RectF(anchor);
        this.extraNodeObj = Build.VERSION.SDK_INT >= 29 ? new RenderNode("menu-extra") : null;
        setWillNotDraw(false);
        glass.blurDp = Ui.barBlur();
        glass.vibrance = 1.35f;
        glass.strokeColor = Ui.glassStroke();

        int fg = Ui.text();
        boolean anyCheck = false;
        for (Entry e : entries) anyCheck |= e.checked || e.checkable;

        list = new LinearLayout(m);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(6), 0, Ui.dp(6));
        for (Entry e : entries) {
            LinearLayout r = new LinearLayout(m);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(Ui.dp(anyCheck ? 12 : 18), 0, Ui.dp(18), 0);
            if (anyCheck) {
                Icons.IconView ck = new Icons.IconView(m, Icons.CHECK, 17, Ui.gStyle ? Ui.ACCENT : fg);
                ck.setVisibility(e.checked ? VISIBLE : INVISIBLE);
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22));
                cl.rightMargin = Ui.dp(8);
                r.addView(ck, cl);
            }
            TextView t = Ui.text(m, e.title, 15.5f, e.checked ? Ui.semibold : Ui.regular, e.checked ? Ui.ACCENT : fg);
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (e.icon != 0) {
                LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22));
                il.leftMargin = Ui.dp(14);
                r.addView(new Icons.IconView(m, e.icon, 19, e.checked ? Ui.ACCENT : fg), il);
            }
            r.setBackground(Widgets.ripple());
            r.setOnClickListener(v -> { dismiss(); if (e.run != null) postDelayed(e.run, 130); });
            list.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(46)));
        }

        scroller = new ScrollView(m);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(OVER_SCROLL_NEVER);
        scroller.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroller.setAlpha(0f);
        addView(scroller, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        p.stiffness = Ui.gStyle ? 320f : 260f;
        p.damping = Ui.gStyle ? 0.78f : 0.68f;
        p.onUpdate = this::onProgress;
        p.onEnd = () -> {
            if (closing) {
                if (fromHeader) main.header.setPillHidden(false);
                restoreSource();
                main.root.removeView(this);
            }
        };
        cur.set(anchor);
        blobRadius = Math.max(Ui.dpf(18), anchor.height() / 2f);
        fromHeader = !Ui.gStyle && RectF.intersects(anchor, headerPillInRoot(m));
        if (fromHeader) m.header.setPillHidden(true);
    }

    private static RectF headerPillInRoot(MainActivity m) {
        float[] o = Glass.abs(m.header);
        RectF r = new RectF(m.header.pill);
        r.offset(o[0], o[1]);
        return r;
    }

    /** Test/inspection seam: snap the drop-morph to any progress 0..1. */
    void snapProgress(float v) {
        p.snap(v);
    }

    @Override protected void onMeasure(int w, int h) {
        super.onMeasure(w, h);
        int maxH = Math.max(Ui.dp(160), getMeasuredHeight() - Ui.statusInset - Ui.navInset - Ui.dp(48));
        if (sheet) {
            int sw = Math.min(getMeasuredWidth() - Ui.dp(24), Ui.dp(520));
            list.measure(MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int sh = Math.min(maxH, list.getMeasuredHeight());
            scroller.measure(MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(sh, MeasureSpec.EXACTLY));
            return;
        }
        int maxW = Math.min(Ui.dp(276), getMeasuredWidth() - Ui.dp(32));
        list.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int mw = Math.max(Ui.dp(206), Math.min(maxW, list.getMeasuredWidth()));
        int mh = Math.min(maxH, list.getMeasuredHeight());
        scroller.measure(MeasureSpec.makeMeasureSpec(mw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(mh, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = scroller.getMeasuredWidth(), h = scroller.getMeasuredHeight();
        float W = getWidth(), H = getHeight();
        if (sheet) {
            float left = (W - w) / 2f, bottom = H - Ui.navInset - Ui.dpf(12);
            target.set(left, Math.max(Ui.statusInset + Ui.dpf(16), bottom - h), left + w, bottom);
            scroller.layout((int) target.left, (int) target.top, (int) target.right, (int) target.bottom);
            if (!closing && p.target == 0f && p.value == 0f) p.to(1f);
            return;
        }
        boolean below = anchor.centerY() < H * 0.55f;
        boolean alignRight = anchor.centerX() > W / 2f;
        float left = alignRight ? Math.min(anchor.right, W - Ui.dpf(16)) - w : Math.max(anchor.left, Ui.dpf(16));
        left = Ui.clamp(left, Ui.dpf(12), Math.max(Ui.dpf(12), W - Ui.dpf(12) - w));
        float top = below ? anchor.top : anchor.bottom - h;
        top = Ui.clamp(top, Ui.statusInset + Ui.dpf(4), Math.max(Ui.statusInset + Ui.dpf(4), H - Ui.navInset - Ui.dpf(12) - h));
        target.set(left, top, left + w, top + h);
        scroller.layout((int) left, (int) top, (int) left + w, (int) top + h);
        if (!closing && p.target == 0f && p.value == 0f) p.to(1f);
    }

    private void onProgress() {
        float v = p.value;
        if (Ui.gStyle) {
            float sc = Ui.lerp(0.78f, 1f, Ui.clamp(v, 0f, 1.08f));
            float px = anchor.centerX() > getWidth() / 2f ? target.right : target.left;
            float py = anchor.centerY() < getHeight() * 0.55f ? target.top : target.bottom;
            float w = target.width() * sc, h = target.height() * sc;
            float l = px == target.right ? target.right - w : target.left;
            float t = py == target.top ? target.top : target.bottom - h;
            cur.set(l, t, l + w, t + h);
            blobRadius = Ui.dpf(26f) * sc;
            applyMaterial(Ui.clamp(v, 0f, 1f));
            glass.alpha = Ui.clamp(v * 1.4f, 0f, 1f);
            float ca = Ui.clamp((v - 0.15f) / 0.65f, 0f, 1f);
            scroller.setAlpha(ca);
            scroller.setPivotX(px - target.left);
            scroller.setPivotY(py - target.top);
            scroller.setScaleX(sc);
            scroller.setScaleY(sc);
            invalidate();
            return;
        }
        // Exact Prism drop-morph: gather into a liquid drop -> drift -> swell into menu card
        float gather = Ui.clamp(v / 0.42f, 0f, 1f);
        float swell = Ui.clamp((v - 0.28f) / 0.72f, 0f, 1.2f);
        gather = gather * gather * (3f - 2f * gather);
        float blob = Math.max(Ui.dpf(44), anchor.height() * 1.18f);
        float bw = Ui.lerp(anchor.width(), blob, gather), bh = Ui.lerp(anchor.height(), blob, gather);
        float w = Ui.lerp(bw, target.width(), swell), h = Ui.lerp(bh, target.height(), swell);
        float t = Ui.clamp(v, 0f, 1.15f);
        float cx = Ui.lerp(anchor.centerX(), target.centerX(), t), cy = Ui.lerp(anchor.centerY(), target.centerY(), t);
        float travel = (float) Math.sin(Math.PI * Ui.clamp(v, 0f, 1f)) * 0.08f;
        w *= 1f + travel;
        h *= 1f - travel * 0.5f;
        cur.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        blobRadius = Ui.lerp(Math.min(w, h) * 0.5f, Ui.dpf(22f), Ui.clamp(swell, 0f, 1f));
        applyMaterial(Ui.clamp(swell, 0f, 1f));
        float ca = Ui.clamp((v - 0.55f) / 0.45f, 0f, 1f);
        scroller.setAlpha(ca);
        float s = Ui.lerp(0.92f, 1f, ca);
        scroller.setPivotX(anchor.centerX() - target.left);
        scroller.setPivotY(anchor.centerY() - target.top);
        scroller.setScaleX(s);
        scroller.setScaleY(s);
        invalidate();
    }

    private void drawGlyph(Canvas c, float a) {
        if (a <= 0.01f) return;
        float s = Ui.clamp(Math.min(cur.width() / Math.max(1f, anchor.width()), cur.height() / Math.max(1f, anchor.height())), 0.7f, 1.12f);
        c.save();
        c.translate(cur.centerX(), cur.centerY());
        c.scale(s, s);
        c.translate(-anchor.centerX(), -anchor.centerY());
        if (fromHeader) {
            main.header.drawGlyphs(c, anchor, a);
        } else if (source != null) {
            float[] o = Glass.abs(source), me = Glass.abs(this);
            int sc = c.saveLayerAlpha(null, (int) (255 * a));
            c.translate(o[0] - me[0], o[1] - me[1]);
            source.draw(c);
            c.restoreToCount(sc);
        }
        c.restore();
    }

    private void restoreSource() {
        if (restored || source == null) return;
        restored = true;
        HIDDEN_SOURCES.remove(source);
        source.animate().cancel();
        source.setAlpha(1f); source.setScaleX(1f); source.setScaleY(1f);
    }

    private void applyMaterial(float k) {
        glass.tint = Ui.menuTint();
        glass.blurDp = Ui.barBlur();
        glass.refractDp = Ui.lerp(15f, 17f, k);
        glass.edgeDp = Ui.lerp(20f, 26f, k);
        glass.outerDp = Ui.lerp(5f, 7f, k);
        glass.outerRefractDp = Ui.lerp(11f, 12f, k);
        glass.rim = Ui.lerp(0.32f, 0.40f, k);
        glass.edgeDark = 0.32f;
        glass.vibrance = 1.35f;
        glass.shadowAlpha = Ui.lerp(fromHeader ? 0.07f : 0f, 0.22f, k);
        glass.chroma = 0f;
        glass.alpha = fromHeader ? 1f : Ui.clamp(p.value / 0.1f, 0f, 1f);
    }

    @Override protected void dispatchDraw(Canvas c) {
        glass.shapes(cur, Math.min(blobRadius, Math.min(cur.width(), cur.height()) / 2f), null, 0, null, 0);
        float v = p.value;
        float swell = Ui.clamp((v - 0.28f) / 0.72f, 0f, 1f);
        float inA = 1f - Ui.clamp(swell * 2.2f, 0f, 1f);
        boolean glyph = inA > 0.01f && (fromHeader || source != null) && Build.VERSION.SDK_INT >= 29 && c.isHardwareAccelerated() && extraNodeObj != null;
        if (glyph) {
            RenderNode extraNode = (RenderNode) extraNodeObj;
            extraNode.setPosition(0, 0, getWidth(), getHeight());
            RecordingCanvas rc = extraNode.beginRecording(getWidth(), getHeight());
            try { drawGlyph(rc, inA); } finally { extraNode.endRecording(); }
            glass.extra = extraNode;
        } else {
            glass.extra = null;
        }
        glass.draw(c, this, main.backdrop);
        float topA = 1f - Ui.clamp(v / 0.3f, 0f, 1f);
        if (topA > 0.01f && (fromHeader || source != null)) drawGlyph(c, topA);
        c.save();
        c.clipRect(cur);
        super.dispatchDraw(c);
        c.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && !target.contains(e.getX(), e.getY())) {
            dismiss();
        }
        return true;
    }

    void dismiss() {
        if (closing) return;
        closing = true;
        p.stiffness = 380f;
        p.damping = 0.9f;
        p.to(0f);
    }

    static boolean dismissAny(ViewGroup root) {
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            View v = root.getChildAt(i);
            if (v instanceof GlassMenu) { ((GlassMenu) v).dismiss(); return true; }
        }
        return false;
    }
}
