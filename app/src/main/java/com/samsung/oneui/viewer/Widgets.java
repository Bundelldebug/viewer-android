package com.samsung.oneui.viewer;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Prism-style cards, grouped settings rows, chips, refractive Toggle and GlassSlider. */
final class Widgets {
    private Widgets() {}

    static final LinearLayout.LayoutParams MW = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

    static TextView section(Context c, String title) {
        TextView t = Ui.text(c, title, 20f, Ui.bold, Ui.text());
        t.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(18), Ui.dp(22), Ui.dp(18), Ui.dp(10));
        t.setLayoutParams(lp);
        return t;
    }

    static TextView caption(Context c, String text) {
        TextView t = Ui.text(c, text, 13, Ui.regular, Ui.text2());
        t.setSingleLine(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(20), Ui.dp(4), Ui.dp(20), Ui.dp(8));
        t.setLayoutParams(lp);
        return t;
    }

    static Drawable ripple() {
        return new RippleDrawable(ColorStateList.valueOf(Ui.pressed()), null, new ColorDrawable(0xFFFFFFFF));
    }

    static GradientDrawable chipBg(int color) {
        GradientDrawable g = Ui.round(color, 100);
        if (Ui.gStyle && color != Ui.ACCENT) {
            g.setStroke(Math.max(1, Ui.dp(0.8f)), Ui.dark ? 0x29FFFFFF : 0x1A000000);
        }
        return g;
    }

    /** Selectable pill chips row (exact Prism filter row). */
    static final class Chips extends HorizontalScrollView {
        interface OnPick { void pick(int i); }
        final LinearLayout row;
        final List<TextView> views = new ArrayList<>();
        int selected;

        Chips(Context c, String[] labels, OnPick pick) {
            super(c);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            row = new LinearLayout(c);
            row.setPadding(Ui.dp(16), 0, Ui.dp(8), 0);
            addView(row);
            for (int i = 0; i < labels.length; i++) {
                TextView t = Ui.text(c, labels[i], 14, Ui.medium, Ui.text());
                t.setGravity(Gravity.CENTER);
                t.setPadding(Ui.dp(16), 0, Ui.dp(16), 0);
                t.setMinWidth(Ui.dp(58));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(36));
                lp.rightMargin = Ui.dp(8);
                final int idx = i;
                t.setOnClickListener(v -> { set(idx, true); pick.pick(idx); });
                Ui.press(t, 0.94f);
                row.addView(t, lp);
                views.add(t);
            }
            set(0, false);
        }

        void set(int i, boolean animate) {
            int prev = selected;
            selected = i;
            for (int k = 0; k < views.size(); k++) {
                TextView t = views.get(k);
                boolean on = k == i;
                if (animate && (k == i || k == prev)) {
                    final int from = k == prev ? Ui.ACCENT : Ui.chip(), to = on ? Ui.ACCENT : Ui.chip();
                    final int tf = k == prev ? 0xFFFFFFFF : Ui.text(), tt = on ? 0xFFFFFFFF : Ui.text();
                    Ui.animate(0f, 1f, 220, Ui.STANDARD, a -> {
                        float f = (float) a.getAnimatedValue();
                        t.setBackground(chipBg(Ui.mix(from, to, f)));
                        t.setTextColor(Ui.mix(tf, tt, f));
                    });
                } else {
                    t.setBackground(chipBg(on ? Ui.ACCENT : Ui.chip()));
                    t.setTextColor(on ? 0xFFFFFFFF : Ui.text());
                }
                t.setTypeface(on ? Ui.semibold : Ui.medium);
            }
        }
    }

    /** Inset grouped settings / info card (exact Prism Group). */
    static final class Group extends LinearLayout {
        final LinearLayout card;
        int rows;

        Group(Context c, String header) {
            super(c);
            setOrientation(VERTICAL);
            if (header != null) {
                TextView h = Ui.text(c, header, Ui.gStyle ? 13.5f : 14f, Ui.gStyle ? Ui.semibold : Ui.regular, Ui.text2());
                h.setPadding(Ui.dp(Ui.gStyle ? 28 : 30), Ui.dp(Ui.gStyle ? 20 : 22), Ui.dp(28), Ui.dp(8));
                addView(h, MW);
            } else {
                View sp = new View(c);
                addView(sp, new LayoutParams(1, Ui.dp(14)));
            }
            card = new LinearLayout(c);
            card.setOrientation(VERTICAL);
            GradientDrawable cardBg = Ui.round(Ui.card(), Ui.cardRadius());
            cardBg.setStroke(Math.max(1, Ui.dp(0.6f)), Ui.dark ? 0x18FFFFFF : 0x0E000000);
            card.setBackground(cardBg);
            card.setClipToOutline(true);
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = lp.rightMargin = Ui.dp(16);
            addView(card, lp);
        }

        View row(int icon, int iconColor, String title, String value, boolean chevron, View.OnClickListener click) {
            Context c = getContext();
            if (rows++ > 0) {
                View sep = new View(c);
                sep.setBackgroundColor(Ui.separator());
                LayoutParams sl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(0.5f)));
                sl.leftMargin = icon != 0 ? Ui.dp(Ui.gStyle ? 64 : 52) : Ui.dp(Ui.gStyle ? 20 : 16);
                if (Ui.gStyle) sl.rightMargin = Ui.dp(20);
                card.addView(sep, sl);
            }
            LinearLayout r = new LinearLayout(c);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(Ui.dp(Ui.gStyle ? 18 : 16), 0, Ui.dp(Ui.gStyle ? 16 : 14), 0);
            r.setMinimumHeight(Ui.dp(Ui.gStyle ? 56 : 53));
            if (icon != 0) {
                if (Ui.gStyle) {
                    FrameLayout badge = new FrameLayout(c);
                    badge.setBackground(Ui.round(Ui.alpha(iconColor, Ui.dark ? 0.22f : 0.12f), 100));
                    Icons.IconView iv = new Icons.IconView(c, icon, 18, iconColor);
                    badge.addView(iv, new FrameLayout.LayoutParams(Ui.dp(20), Ui.dp(20), Gravity.CENTER));
                    LayoutParams il = new LayoutParams(Ui.dp(34), Ui.dp(34));
                    il.rightMargin = Ui.dp(12);
                    r.addView(badge, il);
                } else {
                    Icons.IconView iv = new Icons.IconView(c, icon, 21, iconColor);
                    LayoutParams il = new LayoutParams(Ui.dp(22), Ui.dp(22));
                    il.rightMargin = Ui.dp(14);
                    r.addView(iv, il);
                }
            }
            TextView t = Ui.text(c, title, Ui.gStyle ? 16f : 16.5f, Ui.gStyle ? Ui.medium : Ui.regular, Ui.text());
            r.addView(t, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (value != null) {
                TextView v = Ui.text(c, value, 14.5f, Ui.regular, Ui.text2());
                v.setMaxWidth(Ui.dp(180));
                LayoutParams vl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                vl.leftMargin = Ui.dp(8);
                r.addView(v, vl);
                r.setTag(v);
            }
            if (chevron) {
                Icons.IconView ch = new Icons.IconView(c, Icons.CHEVRON, 15, Ui.text3());
                LayoutParams cl = new LayoutParams(Ui.dp(18), Ui.dp(18));
                cl.leftMargin = Ui.dp(6);
                r.addView(ch, cl);
            }
            if (click != null) {
                r.setBackground(ripple());
                r.setOnClickListener(click);
                if (Ui.gStyle) Ui.press(r, 0.99f);
            }
            card.addView(r, MW);
            return r;
        }

        View toggleRow(int icon, int iconColor, String title, boolean on, Consumer<Boolean> change) {
            LinearLayout r = (LinearLayout) row(icon, iconColor, title, null, false, null);
            Toggle tg = new Toggle(getContext(), on);
            tg.change = change;
            r.addView(tg);
            return r;
        }

        View option(String title, boolean checked, View.OnClickListener click) {
            View r = row(0, 0, title, null, false, click);
            Icons.IconView ck = new Icons.IconView(getContext(), Icons.CHECK, 18, Ui.ACCENT);
            ck.setVisibility(checked ? VISIBLE : INVISIBLE);
            ((LinearLayout) r).addView(ck, new LayoutParams(Ui.dp(22), Ui.dp(22)));
            return r;
        }
    }

    private static final class Track {
        final Object nodeObj;
        Track() { nodeObj = Build.VERSION.SDK_INT >= 29 ? new RenderNode("track") : null; }
        Canvas begin(int w, int h) {
            if (Build.VERSION.SDK_INT >= 29 && nodeObj != null) {
                RenderNode n = (RenderNode) nodeObj;
                n.setPosition(0, 0, w, h);
                return n.beginRecording(w, h);
            }
            return null;
        }
        void end() {
            if (Build.VERSION.SDK_INT >= 29 && nodeObj != null) ((RenderNode) nodeObj).endRecording();
        }
    }

    /** Exact Prism lifting glass knob physics for Toggle and GlassSlider. */
    static void liftKnob(Glass.Surface g, RectF knob, float l, float magMax) {
        float k = Ui.clamp(l, 0f, 1f);
        g.shapes(knob, knob.height() / 2f, null, 0, null, 0);
        g.alpha = 1f;
        g.tint = Ui.gStyle ? 0xFFFFFFFF : Ui.mix(0xFFFFFFFF, Ui.dark ? 0x26FFFFFF : 0x14FFFFFF, k);
        g.refractDp = 2f + 6f * k;
        g.edgeDp = 8f + 4f * k;
        g.outerDp = 4f;
        g.outerRefractDp = 1f + 1.5f * k;
        g.chroma = 0f;
        g.rim = 0.3f + 0.25f * k;
        g.mag = 1f + magMax * l;
        g.magX = knob.centerX();
        g.magY = knob.centerY();
        g.shadowAlpha = 0.16f + 0.06f * k;
    }

    /** Kyant0 AndroidLiquidGlass LiquidToggle (64×28dp track, 40×24dp refractive capsule thumb, critically damped). */
    static final class Toggle extends View {
        boolean on;
        float pos;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint rimP = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF(), knob = new RectF();
        Consumer<Boolean> change;
        private final Ui.Spring lens = new Ui.Spring(460f, 1.0f, 0.002f);
        private final Ui.Spring move = new Ui.Spring(380f, 1.0f, 0.002f);
        private final Glass.Surface glass = new Glass.Surface();
        private final Track track = new Track();
        private float downX;
        private boolean dragged;

        Toggle(Context c, boolean on) {
            super(c);
            this.on = on;
            pos = on ? 1f : 0f;
            move.snap(pos);
            lens.onUpdate = this::invalidate;
            move.onUpdate = () -> { pos = move.value; invalidate(); };
            rimP.setStyle(Paint.Style.STROKE);
            glass.blur = true;
            glass.blurDp = 8f;
            glass.refractDp = 5f;
            glass.edgeDp = 10f;
            glass.chroma = 0.55f;
            glass.rim = 0.52f;
            glass.tint = 0xFFFFFFFF;
            glass.shadowAlpha = 0.14f;
            glass.strokeColor = 0x16000000;
        }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(Ui.dp(64), Ui.dp(36)); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float dragW = Math.max(1f, Ui.dpf(20));
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    downX = e.getX();
                    dragged = false;
                    lens.to(1f);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - downX;
                    if (Math.abs(dx) > Ui.dpf(3)) dragged = true;
                    if (dragged) move.to(Ui.clamp((on ? 1f : 0f) + dx / dragW, 0f, 1f));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    boolean next = dragged ? pos >= 0.5f : !on;
                    if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) next = on;
                    boolean changed = next != on;
                    on = next;
                    move.to(on ? 1f : 0f);
                    postDelayed(() -> lens.to(0f), 110);
                    if (changed && change != null) change.accept(on);
                    return true;
                }
            }
            return true;
        }

        private void drawTrack(Canvas c) {
            float trackH = Ui.dpf(28), top = (getHeight() - trackH) * 0.5f;
            r.set(0f, top, getWidth(), top + trackH);
            int off = Ui.dark ? 0x5C787880 : 0x33787878;
            int onCol = Ui.gStyle ? Ui.ACCENT : (Ui.dark ? 0xFF30D158 : 0xFF34C759);
            p.setColor(Ui.mix(off, onCol, Ui.clamp(pos, 0f, 1f)));
            c.drawRoundRect(r, trackH * 0.5f, trackH * 0.5f, p);
        }

        @Override protected void onDraw(Canvas c) {
            float pr = Ui.clamp(lens.value, 0f, 1f);
            float pad = Ui.dpf(2), baseW = Ui.dpf(40), baseH = Ui.dpf(24);
            float cx = pad + baseW * 0.5f + Math.max(0f, getWidth() - baseW - pad * 2f) * Ui.clamp(pos, 0f, 1f);
            float sc = Ui.lerp(1f, 1.40f, pr);
            float vel = Ui.clamp(move.velocity * 0.04f, -0.2f, 0.2f);
            float kw = baseW * sc / (1f - Math.abs(vel) * 0.75f);
            float kh = baseH * sc * (1f - Math.abs(vel) * 0.25f);
            knob.set(cx - kw * 0.5f, getHeight() * 0.5f - kh * 0.5f, cx + kw * 0.5f, getHeight() * 0.5f + kh * 0.5f);
            if (Build.VERSION.SDK_INT >= 33 && c.isHardwareAccelerated() && Glass.shadersOk && Ui.glass && !Ui.gStyle && track.nodeObj != null) {
                Canvas rc = track.begin(getWidth(), getHeight());
                try { drawTrack(rc); } finally { track.end(); }
                c.drawRenderNode((RenderNode) track.nodeObj);
                glass.shapes(knob, kh * 0.5f, null, 0, null, 0);
                glass.alpha = 1f;
                glass.blur = pr < 0.95f;
                glass.blurDp = 8f * (1f - pr);
                glass.refractDp = 5f * pr;
                glass.edgeDp = 10f * Math.max(0.25f, pr);
                glass.chroma = 0.55f * pr;
                glass.rim = 0.24f + 0.42f * pr;
                glass.mag = 1f + 0.14f * pr;
                glass.magX = cx;
                glass.magY = getHeight() * 0.5f;
                glass.tint = Ui.mix(0xFFFFFFFF, Ui.dark ? 0x1EFFFFFF : 0x14FFFFFF, pr);
                glass.extra = track.nodeObj;
                glass.draw(c, this, null);
                return;
            }
            drawTrack(c);
            p.setColor(Ui.gStyle ? 0xFFFFFFFF : Ui.mix(0xFFFFFFFF, 0x55FFFFFF, pr));
            p.setShadowLayer(Ui.dpf(3.5f), 0, Ui.dpf(1.2f), 0x24000000);
            c.drawRoundRect(knob, kh * 0.5f, kh * 0.5f, p);
            p.clearShadowLayer();
            if (pr > 0.02f && !Ui.gStyle) {
                rimP.setStrokeWidth(Ui.dpf(1.2f));
                rimP.setColor(Ui.alpha(0xFFFFFFFF, 0.78f * pr));
                c.drawRoundRect(knob, kh * 0.5f, kh * 0.5f, rimP);
            }
        }
    }

    /** Exact Prism GlassSlider: white pill thumb becomes a refracting magnifying lens while dragging. */
    static final class GlassSlider extends View {
        float value;
        int steps;
        Consumer<Float> change;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF(), knob = new RectF();
        private final Ui.Spring lens = new Ui.Spring(520f, 0.72f, 0.002f);
        private final Glass.Surface glass = new Glass.Surface();
        private final Track track = new Track();

        GlassSlider(Context c, float value, int steps) {
            super(c);
            this.value = value;
            this.steps = steps;
            lens.onUpdate = this::invalidate;
            glass.blur = false;
            glass.refractDp = 10f;
            glass.edgeDp = 12f;
            glass.chroma = 0.7f;
            glass.rim = 0.55f;
            glass.tint = 0x10FFFFFF;
            glass.shadowAlpha = 0.18f;
            glass.strokeColor = 0x1A000000;
        }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(40)); }

        private float pad() { return Ui.dpf(18); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float v = Ui.clamp((e.getX() - pad()) / Math.max(1f, getWidth() - pad() * 2), 0f, 1f);
            if (steps > 0) v = Math.round(v * steps) / (float) steps;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    lens.to(1f);
                case MotionEvent.ACTION_MOVE:
                    if (v != value) { value = v; if (change != null) change.accept(value); }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    lens.to(0f);
                    return true;
            }
            return true;
        }

        private void drawTrack(Canvas c) {
            float cy = getHeight() / 2f, x = pad() + (getWidth() - pad() * 2) * value;
            float half = Ui.dpf(Ui.gStyle ? 3f : 2.5f);
            r.set(pad(), cy - half, getWidth() - pad(), cy + half);
            p.setColor(Ui.dark ? 0xFF3A3A3C : 0xFFE5E5EA);
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, p);
            r.right = Math.max(pad(), x);
            p.setColor(Ui.ACCENT);
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, p);
        }

        @Override protected void onDraw(Canvas c) {
            float l = lens.value;
            float cx = pad() + (getWidth() - pad() * 2) * value, cy = getHeight() / 2f;
            if (Ui.gStyle) {
                float half = Ui.dpf(Ui.lerp(2.5f, 6.5f, l));
                r.set(pad(), cy - half, getWidth() - pad(), cy + half);
                p.setColor(Ui.dark ? 0xFF3A3A3D : 0xFFE4E4E7);
                c.drawRoundRect(r, half, half, p);
                r.right = Math.max(pad() + half * 2f, cx);
                p.setColor(Ui.ACCENT);
                c.drawRoundRect(r, half, half, p);
                float rad = Ui.lerp(Ui.dpf(9.5f), Ui.dpf(12.5f), l);
                p.setColor(0xFFFFFFFF);
                p.setShadowLayer(Ui.dpf(4), 0, Ui.dpf(1.5f), 0x33000000);
                c.drawCircle(cx, cy, rad, p);
                p.clearShadowLayer();
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dpf(2f));
                p.setColor(Ui.ACCENT);
                c.drawCircle(cx, cy, rad - Ui.dpf(1f), p);
                p.setStyle(Paint.Style.FILL);
                return;
            }
            float w = Ui.lerp(Ui.dpf(30), Ui.dpf(46), l), h = Ui.lerp(Ui.dpf(20), Ui.dpf(32), l);
            knob.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
            if (Build.VERSION.SDK_INT >= 33 && c.isHardwareAccelerated() && Glass.shadersOk && Ui.glass && track.nodeObj != null) {
                Canvas rc = track.begin(getWidth(), getHeight());
                try { drawTrack(rc); } finally { track.end(); }
                c.drawRenderNode((RenderNode) track.nodeObj);
                liftKnob(glass, knob, l, 0.16f);
                glass.extra = track.nodeObj;
                glass.draw(c, this, null);
                return;
            }
            drawTrack(c);
            p.setColor(0xFFFFFFFF);
            p.setShadowLayer(Ui.dpf(3), 0, Ui.dpf(1), 0x33000000);
            c.drawRoundRect(knob, h / 2, h / 2, p);
            p.clearShadowLayer();
        }
    }
}
