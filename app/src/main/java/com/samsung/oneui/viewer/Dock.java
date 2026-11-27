package com.samsung.oneui.viewer;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

/**
 * Floating bottom Liquid Glass chrome ported 1:1 from Prism Music Android:
 * - Floating glass tab pill with draggable magnifying refractive lens
 * - Real-time active accent-color clipping inside the moving lens (every icon & label entering the lens turns active accent color)
 * - Press-to-expand glass regions + Gaussian touch glow on icons & bubbles
 * - Metaball smooth-union between pill and detached search bubble while lens is lifted
 * - Floating contextual glass bar above the pill (Viewer tool bar on Viewer tab / Open-file mini bar on other tabs)
 * - Adaptive layouts: phone floating dock, foldable right-hand vertical rail, tablet top-centered pill
 */
final class Dock extends FrameLayout {
    interface Listener {
        void onTab(int index, boolean reselect);
        void onOpenCurrentViewer();
        void onSearch();
        void onExitSearch();
        void onQuery(String q, boolean submit);
        default void onSidebar() {}
        default void onToolAction(int slotIndex, RectF anchor) {}
        default void onMiniQuickAction(int which, RectF anchor) {}
    }

    static final int QUICK_SAVE = 0, QUICK_CLOSE = 1;
    static final int[] TAB_ICONS = {Icons.HOME, Icons.VIEW, Icons.FILES, Icons.SETTINGS};

    /** Contextual viewer tool slot descriptor shown on the floating glass bar above the dock. */
    static final class ToolSlot {
        int icon;
        String label;
        boolean active;
        boolean enabled = true;
        ToolSlot(int icon, String label, boolean active) {
            this.icon = icon;
            this.label = label;
            this.active = active;
        }
    }

    private float sideW, sideP;

    void setSidebar(float widthPx, float progress) {
        sideW = widthPx;
        sideP = progress;
        invalidate();
    }

    private float contentLeft() { return tablet ? sideW * sideP : 0f; }
    private float lead() { return tablet ? Ui.dpf(46) * (1f - search) : 0f; }

    private final Glass.Backdrop backdrop;
    private Listener listener;
    private final String[] labels;
    int selected;

    // Geometry state
    float collapse, search;
    private ValueAnimator collapseAnim, searchAnim;
    private int imeOffset;
    final RectF pill = new RectF(), circle = new RectF(), lens = new RectF(), mini = new RectF();
    private final RectF tmp = new RectF(), expandedCircle = new RectF(), toolBubble = new RectF();
    private final Path lensClipPath = new Path();

    private final Glass.Surface barGlass = new Glass.Surface();
    private final Glass.Surface lensGlass = new Glass.Surface();
    private final Glass.Surface miniGlass = new Glass.Surface();
    private final Object contentNodeObj; // RenderNode on API 29+

    // Exact Prism spring physics
    final Ui.Spring lensX = new Ui.Spring(380f, 0.98f, 0.4f);
    final Ui.Spring lift = new Ui.Spring(460f, 1.0f, 0.002f);
    final Ui.Spring miniPress = new Ui.Spring(700f, 0.9f, 0.002f);
    final Ui.Spring searchPress = new Ui.Spring(640f, 0.76f, 0.002f);
    final Ui.Spring barPress = new Ui.Spring(520f, 0.85f, 0.002f);
    final Ui.Spring toolPress = new Ui.Spring(560f, 0.72f, 0.002f);

    private float touchX, touchY;
    private VelocityTracker vt;
    private float lensVel;

    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint toolLabel = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint sub = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

    final EditText field;
    private boolean miniVisible;
    private float miniShow;
    private ValueAnimator miniShowAnim;
    private boolean toolMode;
    private String miniTitle = "", miniSub = "";
    private int miniIcon = Icons.VIEW;
    private boolean miniModified;
    private final ToolSlot[] toolSlots = new ToolSlot[5];
    private int pressedToolSlot = -1;

    // Touch targets
    private static final int T_NONE = 0, T_TAB = 1, T_SEARCH = 2, T_MINI = 3,
            T_MINI_SAVE = 4, T_MINI_CLOSE = 5, T_HOME_CIRCLE = 6, T_SIDE = 7, T_TOOL_SLOT = 8;
    private int touch = T_NONE;
    private float downX, downY;
    private boolean dragging;

    Dock(Context c, Glass.Backdrop backdrop, String[] labels) {
        super(c);
        this.backdrop = backdrop;
        this.labels = labels;
        this.contentNodeObj = Build.VERSION.SDK_INT >= 29 ? new RenderNode("dock-content") : null;
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);

        label.setTypeface(Ui.semibold);
        label.setTextSize(Ui.sp(10.5f));
        label.setTextAlign(Paint.Align.CENTER);
        toolLabel.setTypeface(Ui.semibold);
        toolLabel.setTextSize(Ui.sp(10f));
        toolLabel.setTextAlign(Paint.Align.CENTER);
        title.setTypeface(Ui.semibold);
        title.setTextSize(Ui.sp(14f));
        sub.setTypeface(Ui.regular);
        sub.setTextSize(Ui.sp(12f));

        // Exact Prism optical presets
        barGlass.blurDp = Ui.barBlur();
        barGlass.refractDp = 17f;
        barGlass.edgeDp = 26f;
        barGlass.outerDp = 7f;
        barGlass.outerRefractDp = 12f;
        barGlass.chroma = 0f;
        barGlass.vibrance = 1.35f;
        barGlass.rim = 0.32f;
        barGlass.edgeDark = 0.32f;
        barGlass.shadowAlpha = 0.16f;

        miniGlass.blurDp = Ui.barBlur();
        miniGlass.refractDp = 17f;
        miniGlass.edgeDp = 24f;
        miniGlass.outerDp = 7f;
        miniGlass.outerRefractDp = 12f;
        miniGlass.chroma = 0f;
        miniGlass.vibrance = 1.35f;
        miniGlass.rim = 0.32f;
        miniGlass.edgeDark = 0.32f;
        miniGlass.shadowAlpha = 0.16f;

        lensGlass.blur = false;
        lensGlass.refractDp = 22f;
        lensGlass.edgeDp = 28f;
        lensGlass.outerDp = 8f;
        lensGlass.outerRefractDp = 14f;
        lensGlass.chroma = 0.9f;
        lensGlass.rim = 0.6f;
        lensGlass.tint = Ui.dark ? 0x0FFFFFFF : 0x0AFFFFFF;
        lensGlass.shadowAlpha = 0.16f;
        lensGlass.strokeColor = Ui.dark ? 0x40FFFFFF : 0x1A000000;

        Runnable inv = this::invalidate;
        lensX.onUpdate = inv;
        lift.onUpdate = inv;
        miniPress.onUpdate = inv;
        searchPress.onUpdate = inv;
        barPress.onUpdate = inv;
        toolPress.onUpdate = inv;

        toolSlots[0] = new ToolSlot(Icons.PEN, "마커", false);
        toolSlots[1] = new ToolSlot(Icons.CROP, "편집", false);
        toolSlots[2] = new ToolSlot(Icons.OCR, "OCR", false);
        toolSlots[3] = new ToolSlot(Icons.FIT, "맞춤", false);
        toolSlots[4] = new ToolSlot(Icons.SAVE, "저장", false);

        field = new EditText(c);
        field.setBackground(null);
        field.setSingleLine(true);
        field.setIncludeFontPadding(false);
        field.setMinHeight(0);
        field.setMinimumHeight(0);
        field.setHint("파일, PDF 텍스트, OCR 검색");
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        field.setTextColor(Ui.text());
        field.setHintTextColor(Ui.text2());
        field.setTypeface(Ui.regular);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        field.setPadding(0, 0, 0, 0);
        field.setVisibility(GONE);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void afterTextChanged(Editable e) { if (listener != null) listener.onQuery(e.toString(), false); }
        });
        field.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                if (listener != null) listener.onQuery(field.getText().toString(), true);
                hideIme();
                return true;
            }
            return false;
        });
        addView(field, new LayoutParams(1, 1));
    }

    void setListener(Listener l) { listener = l; }

    void select(int i, boolean animate) {
        selected = Math.max(0, Math.min(3, i));
        layoutGeometry();
        float cx = slotCenter(selected);
        if (animate) lensX.to(cx); else lensX.snap(cx);
        invalidate();
    }

    void setImeOffset(int px) {
        if (px == imeOffset) return;
        imeOffset = px;
        invalidate();
    }

    /** Updates the floating contextual bar above the tab pill (open file info + viewer tools). */
    void setFileState(boolean hasFile, boolean viewerTabActive, String name, String subtitle, int kindIcon, boolean modified, ToolSlot[] slots) {
        toolMode = hasFile && viewerTabActive;
        miniTitle = name == null ? "" : name;
        miniSub = subtitle == null ? "" : subtitle;
        miniIcon = kindIcon;
        miniModified = modified;
        if (slots != null && slots.length == 5) {
            System.arraycopy(slots, 0, toolSlots, 0, 5);
        }
        if (hasFile != miniVisible) {
            miniVisible = hasFile;
            if (miniShowAnim != null) miniShowAnim.cancel();
            miniShowAnim = Ui.animate(miniShow, hasFile ? 1f : 0f, 380, Ui.EMPHASIZED, a -> {
                miniShow = (float) a.getAnimatedValue();
                invalidate();
            });
            if (!hasFile) setCollapsed(false);
        } else if (hasFile && miniShow < 0.01f && (miniShowAnim == null || !miniShowAnim.isRunning())) {
            miniShow = 1f;
        }
        if (toolMode) setCollapsed(false);
        invalidate();
    }

    /** Test/inspection seam: manually position & lift the lens to inspect mid-drag refraction + active accent clipping. */
    void previewLensDrag(float normalizedSlotPos, float liftAmount) {
        layoutGeometry();
        float pos = axisStart() + slotPadPx() + slotW() * (normalizedSlotPos + 0.5f);
        lensX.snap(clampLens(pos));
        lift.snap(Ui.clamp(liftAmount, 0f, 1f));
        barPress.snap(Ui.clamp(liftAmount, 0f, 1f));
        lensVel = liftAmount > 0.1f ? 1400f : 0f;
        invalidate();
    }

    /** Returns the slot index whose center currently lies inside the lens rect (used for verification). */
    int lensTargetSlot() {
        layoutGeometry();
        computeLens();
        return slotAt(rail ? lens.centerY() : lens.centerX());
    }

    /** True while any spring/animator is running. */
    boolean animating() {
        return (morphAnim != null && morphAnim.isRunning()) || lensX.running || lift.running
                || miniPress.running || searchPress.running || barPress.running || toolPress.running
                || (collapseAnim != null && collapseAnim.isRunning()) || (searchAnim != null && searchAnim.isRunning())
                || (miniShowAnim != null && miniShowAnim.isRunning());
    }

    private float collapseTarget;
    private boolean unifiedG() { return Ui.gStyle && !rail && !tablet; }
    private int slotCount() { return unifiedG() ? 5 : 4; }

    void setCollapsed(boolean c) {
        float target = (c && miniVisible && !toolMode && !rail && !tablet && !unifiedG()) ? 1f : 0f;
        if (target == collapseTarget && (collapseAnim != null && collapseAnim.isRunning() || Math.abs(collapse - target) < 0.001f)) return;
        collapseTarget = target;
        if (collapseAnim != null) collapseAnim.cancel();
        long ms = (long) (460 * Math.abs(collapse - target));
        collapseAnim = Ui.animate(collapse, target, Math.max(120, ms), new LinearInterpolator(), a -> {
            collapse = (float) a.getAnimatedValue();
            invalidate();
        });
    }

    boolean isCollapsed() { return collapse > 0.5f; }

    void setSearchMode(boolean on) {
        if (searchAnim != null) searchAnim.cancel();
        if (on) {
            field.setVisibility(VISIBLE);
            field.requestFocus();
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) postDelayed(() -> imm.showSoftInput(field, 0), 120);
        } else {
            hideIme();
            field.setText("");
        }
        searchAnim = Ui.animate(search, on ? 1f : 0f, 480, Ui.EMPHASIZED, a -> {
            search = (float) a.getAnimatedValue();
            if (!on && search <= 0.001f) field.setVisibility(GONE);
            invalidate();
        });
    }

    boolean inSearch() { return search > 0.5f; }

    void hideIme() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
        field.clearFocus();
    }

    int chromeHeight() {
        if (tablet || rail) return Ui.navInset + Ui.dp(miniVisible ? 76 : 20);
        return Ui.navInset + (int) raise() + Ui.dp(miniVisible ? (64 + 8 + 56 + 16) : (64 + 16));
    }

    // ---- Adaptive form-factor rules (exact Prism) ----
    boolean rail;
    boolean tablet;

    static boolean tabletFor(int wPx, int hPx) {
        return !railFor(wPx, hPx) && wPx / Math.max(0.5f, Ui.density) >= 600 && hPx / Math.max(0.5f, Ui.density) >= 480;
    }

    static boolean railFor(int wPx, int hPx) {
        float wd = wPx / Math.max(0.5f, Ui.density), hd = hPx / Math.max(0.5f, Ui.density);
        float aspect = Math.max(wd, hd) / Math.max(1f, Math.min(wd, hd));
        return !Ui.unfoldTablet && wd >= 600 && aspect < 1.35f;
    }

    private static float ease(float t) { return Ui.EMPHASIZED.getInterpolation(Ui.clamp(t, 0f, 1f)); }
    private float pillK() { return ease(collapse / 0.7f); }
    private float miniK() { return ease((collapse - 0.25f) / 0.75f); }
    private float barH() { return Ui.lerp(Ui.dpf(64), Ui.dpf(48), Math.max(pillK(), search)); }

    static final int RAISE_DEFAULT = 18;
    private float raise() { return Ui.dpf(MainActivity.prefs(getContext()).getInt("raise_dp", RAISE_DEFAULT)); }
    private float bottomY() { return getHeight() - Math.max(Ui.navInset + raise(), imeOffset + Ui.dpf(10)); }

    // ---- Fold / unfold / rotation morph ----
    private float[] morphFrom;
    private float morph = 1f;
    private ValueAnimator morphAnim;

    float[] snapshot() {
        float W = Math.max(1, getWidth()), H = Math.max(1, getHeight());
        return new float[]{W, H, pill.left / W, pill.top / H, pill.right / W, pill.bottom / H,
                circle.left / W, circle.top / H, circle.right / W, circle.bottom / H,
                mini.left / W, mini.top / H, mini.right / W, mini.bottom / H};
    }

    void morphFrom(float[] g) {
        if (g == null || g.length < 14) return;
        morphFrom = g;
        if (morphAnim != null) morphAnim.cancel();
        morph = 0f;
        morphAnim = Ui.animate(0f, 1f, 620, Ui.EMPHASIZED, a -> { morph = (float) a.getAnimatedValue(); invalidate(); });
        morphAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                morph = 1f; morphFrom = null; layoutGeometry(); lensX.snap(slotCenter(selected)); invalidate();
            }
        });
        invalidate();
    }

    private void morphRect(RectF r, int i) {
        float W = getWidth(), H = getHeight();
        float l = morphFrom[i] * W, t = morphFrom[i + 1] * H, rr = morphFrom[i + 2] * W, b = morphFrom[i + 3] * H;
        if (rr - l < 1 || b - t < 1) return;
        r.set(Ui.lerp(l, r.left, morph), Ui.lerp(t, r.top, morph), Ui.lerp(rr, r.right, morph), Ui.lerp(b, r.bottom, morph));
    }

    private void layoutGeometry() {
        layoutGeometry0();
        if (morphFrom != null && morph < 1f) {
            morphRect(pill, 2);
            morphRect(circle, 6);
            morphRect(mini, 10);
        }
    }

    private void layoutGeometry0() {
        float W = getWidth(), H = getHeight();
        if (W <= 0) return;
        rail = railFor((int) W, (int) H);
        tablet = tabletFor((int) W, (int) H);
        float gap = Ui.dpf(8);
        if (tablet) {
            float cl = contentLeft(), cw = W - cl;
            float h = Ui.dpf(46);
            float maxPw = Math.max(Ui.dpf(220), cw - Ui.dpf(260));
            float pw = Math.min(Math.min(W * 0.42f, Ui.dpf(440)) + lead(), maxPw);
            float left = cl + (cw - pw - gap - h) / 2f, top = Ui.statusInset + Ui.dpf(14);
            float maxRight = W - Ui.dpf(130);
            if (left + pw + gap + h > maxRight) left = Math.max(cl + Ui.dpf(16), maxRight - (pw + gap + h));
            float fr = left + pw + gap + h;
            pill.set(left, top, Ui.lerp(left + pw, fr, search), top + h);
            circle.set(Ui.lerp(left + pw + gap, left, search), top, fr, top + h);
            float mw = Math.min(cw - Ui.dpf(40), Ui.dpf(640)), bottom = bottomY();
            mini.set(cl + (cw - mw) / 2f, bottom - Ui.dpf(56), cl + (cw + mw) / 2f, bottom);
            return;
        }
        float m = rail ? Ui.dpf(20) : Math.max(Ui.dpf(18), (W - Ui.dpf(560)) / 2f);
        float cp = pillK(), cm = miniK();
        float bottom = bottomY();
        float mh = Ui.dpf(toolMode ? 56 : 52);
        if (rail) {
            float rw = Ui.dpf(60), len = Ui.dpf(SLOT_PAD * 2 + 4 * 54);
            float right = W - Ui.dpf(20), top = (H - len) / 2f;
            pill.set(right - rw, top, right, top + len);
            float cs = Ui.dpf(60);
            RectF railCircle = tmp;
            railCircle.set(W - m - cs, bottom - cs, W - m, bottom);
            circle.set(Ui.lerp(railCircle.left, m, search), railCircle.top, railCircle.right, railCircle.bottom);
            float miniR = Ui.lerp(W - m - cs - gap, W - m, search);
            float liftY = (cs + gap) * search;
            mini.set(m, bottom - mh - liftY, miniR, bottom - Ui.dpf(2) - liftY);
            return;
        }
        if (unifiedG()) {
            float barW = Math.min(W - Ui.dpf(32), Ui.dpf(420));
            float h = Ui.lerp(Ui.dpf(60), Ui.dpf(54), search);
            float left = (W - barW) / 2f, top = bottom - h;
            pill.set(left, top, left + barW, bottom);
            circle.setEmpty();
            float expBottom = top - Ui.dpf(8);
            mini.set(left, expBottom - mh, left + barW, expBottom);
            return;
        }
        float h = barH();
        bottom += Ui.dpf(4) * cp;
        float top = bottom - h;
        float expandedRight = W - m - Ui.dpf(64) - gap;
        float pillRight = Ui.lerp(expandedRight, m + h, Math.max(cp, search));
        pill.set(m, top, pillRight, bottom);
        float searchLeftNormal = W - m - h;
        float searchLeft = Ui.lerp(searchLeftNormal, m + h + gap, search);
        circle.set(searchLeft, top, W - m, bottom);
        float expBottom = top - Ui.dpf(8);
        RectF e = tmp;
        e.set(m, expBottom - mh, W - m, expBottom);
        float cl = m + h + gap, cr = W - m - h - gap;
        float effCm = cm * (1f - search);
        mini.set(Ui.lerp(e.left, cl, effCm), Ui.lerp(e.top, top, effCm), Ui.lerp(e.right, cr, effCm), Ui.lerp(e.bottom, bottom, effCm));
    }

    private static final float SLOT_PAD = 14f;
    private float slotPadPx() { return Ui.dpf(unifiedG() ? 5f : SLOT_PAD); }
    private float axisStart() { return rail ? pill.top : pill.left + lead(); }
    private float axisLen() { return rail ? pill.height() : pill.width() - lead(); }
    private float slotW() { return (axisLen() - slotPadPx() * 2f) / (float) slotCount(); }
    private float slotCenter(int i) { return axisStart() + slotPadPx() + slotW() * (i + 0.5f); }
    private float axisOf(float x, float y) { return rail ? y : x; }

    private int slotAt(float a) {
        int i = (int) ((a - axisStart() - slotPadPx()) / Math.max(1f, slotW()));
        return Math.max(0, Math.min(slotCount() - 1, i));
    }

    private void computeLens() {
        float k = rail ? search : Math.max(pillK(), search);
        float sw = slotW();
        float l = lift.value;
        float thick = rail ? pill.width() : pill.height();
        float stretch = Math.min(Math.abs(lensVel) / 5000f, 0.22f) * l;
        float restLen = unifiedG() ? sw : Math.min(sw * 1.32f, sw + Ui.dpf(22));
        float restThick = thick - Ui.dpf(10);
        float len = Ui.lerp(restLen, unifiedG() ? sw + Ui.dpf(6) : Math.min(sw * 1.62f, sw + Ui.dpf(46)), l) * (1f + stretch);
        float th = Ui.lerp(restThick, thick + Ui.dpf(16), l) * (1f - stretch * 0.35f);
        float a = k > 0.01f ? slotCenter(selected) : lensX.value;
        float minA = axisStart() + Ui.dpf(5) + len / 2f;
        float maxA = axisStart() + axisLen() - Ui.dpf(5) - len / 2f;
        if (l < 0.05f && minA < maxA) a = Ui.clamp(a, minA, maxA);
        if (rail) lens.set(pill.centerX() - th / 2f, a - len / 2f, pill.centerX() + th / 2f, a + len / 2f);
        else lens.set(a - len / 2f, pill.centerY() - th / 2f, a + len / 2f, pill.centerY() + th / 2f);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutGeometry();
        lensX.snap(slotCenter(selected));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        layoutGeometry();
        positionField();
    }

    private void positionField() {
        RectF host = (tablet || unifiedG()) ? pill : circle;
        if (host.isEmpty()) return;
        int padL = Ui.dp(42), padR = Ui.dp(44);
        int fl = Math.round(host.left) + padL;
        int fr = Math.round(host.right) - padR;
        int ft = Math.round(host.top);
        int fb = Math.round(host.bottom);
        int fw = Math.max(1, fr - fl);
        int fh = Math.max(1, fb - ft);
        if (field.getMeasuredWidth() != fw || field.getMeasuredHeight() != fh) {
            field.measure(MeasureSpec.makeMeasureSpec(fw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(fh, MeasureSpec.EXACTLY));
        }
        field.layout(fl, ft, fl + fw, ft + fh);
    }

    private RectF activeCircleRect() {
        if (circle.isEmpty()) return circle;
        // Press-to-expand glass region animation on the detached search circle!
        float grow = Ui.dpf(4.5f) * searchPress.value * (1f - search);
        expandedCircle.set(circle.left - grow, circle.top - grow, circle.right + grow, circle.bottom + grow);
        return expandedCircle;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (getWidth() == 0) return;
        layoutGeometry();
        if (lensX.value == 0) lensX.snap(slotCenter(selected));
        computeLens();
        if (search > 0.01f) { positionField(); field.setAlpha(Ui.clamp((search - 0.35f) / 0.55f, 0f, 1f)); }

        float k = rail ? 0f : Math.max(pillK(), search);
        float l = lift.value;
        float barA = (1f - k) * (tablet ? (1f - sideP) : 1f);

        if (miniShow > 0.01f && !mini.isEmpty() && search < 0.15f) drawMini(canvas);

        if (tablet && sideP > 0.98f) return;
        boolean lensVisible = (l > 0.02f || (touch == T_TAB && lift.target > 0f)) && barA > 0.25f && !Ui.gStyle;
        if (lensVisible && Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated() && Glass.shadersOk && Ui.glass && contentNodeObj != null) {
            drawBarAndLens(canvas, k, l, barA);
        } else {
            drawBar(canvas, k, l, barA);
        }
    }

    private void drawBarAndLens(Canvas canvas, float k, float l, float barA) {
        int W = getWidth(), H = getHeight();
        RectF cRect = activeCircleRect();
        RenderNode contentNode = (RenderNode) contentNodeObj;
        contentNode.setPosition(0, 0, W, H);
        RecordingCanvas rc = contentNode.beginRecording(W, H);
        try {
            // 1. Resting translucent selection halo underneath when lens is settling
            if (l < 0.99f && barA > 0.01f) {
                fill.setColor(Ui.alpha(Ui.dark ? 0x26FFFFFF : 0x16000000, barA * (1f - l)));
                float r = Math.min(lens.width(), lens.height()) / 2f;
                rc.drawRoundRect(lens, r, r, fill);
            }
            // 2. Base tabs in default text color
            drawTabs(rc, k, Ui.text(), false);
            // 3. Exact Prism active accent clipping inside the moving refractive lens!
            // Every icon & label entering `lens` smoothly shifts to Ui.ACCENT (bold) and gets magnified/refracted!
            if (barA > 0.05f) {
                float lr = Math.min(lens.width(), lens.height()) / 2f;
                lensClipPath.reset();
                lensClipPath.addRoundRect(lens, lr, lr, Path.Direction.CW);
                rc.save();
                rc.clipPath(lensClipPath);
                drawTabs(rc, k, Ui.ACCENT, true);
                rc.restore();
            }
            drawSearchGlyphs(rc);
        } finally {
            contentNode.endRecording();
        }

        // Pill bar & detached search circle metaball-blended with the lifted lens!
        barGlass.smoothK = Ui.dpf(16) * l;
        barGlass.shapes(pill, Math.min(pill.width(), pill.height()) / 2f,
                cRect.isEmpty() ? null : cRect, cRect.isEmpty() ? 0 : cRect.height() / 2f,
                null, 0);
        barGlass.tint = Ui.barTint();
        barGlass.blurDp = Ui.barBlur();
        barGlass.pressX = touchX; barGlass.pressY = touchY;
        barGlass.pressR = Ui.dpf(48);
        barGlass.pressAmt = (touch == T_TAB ? barPress.value : (touch == T_SEARCH ? searchPress.value : 0f)) * 0.7f;
        barGlass.draw(canvas, this, backdrop);

        canvas.drawRenderNode(contentNode);

        // Refracting & magnifying liquid lens floating on top of contentNode!
        lensGlass.shapes(lens, Math.min(lens.width(), lens.height()) / 2f, null, 0, null, 0);
        lensGlass.mag = 1f + 0.14f * l;
        lensGlass.magX = lens.centerX();
        lensGlass.magY = lens.centerY();
        lensGlass.alpha = Ui.clamp(l * 1.4f, 0f, 1f);
        lensGlass.refractDp = 22f;
        lensGlass.edgeDp = 28f;
        lensGlass.outerDp = 8f;
        lensGlass.outerRefractDp = 14f;
        lensGlass.chroma = 0.9f;
        lensGlass.rim = 0.6f;
        lensGlass.tint = Ui.dark ? 0x0FFFFFFF : 0x0AFFFFFF;
        lensGlass.strokeColor = Ui.dark ? 0x40FFFFFF : 0x1A000000;
        lensGlass.pressX = touchX; lensGlass.pressY = touchY;
        lensGlass.pressR = Ui.dpf(42);
        lensGlass.pressAmt = barPress.value * 0.5f;
        lensGlass.extra = contentNode;
        lensGlass.draw(canvas, this, backdrop);
    }

    private void drawBar(Canvas canvas, float k, float l, float barA) {
        RectF cRect = activeCircleRect();
        barGlass.smoothK = 0f;
        barGlass.shapes(pill, Math.min(pill.width(), pill.height()) / 2f,
                cRect.isEmpty() ? null : cRect, cRect.isEmpty() ? 0 : cRect.height() / 2f,
                null, 0);
        barGlass.tint = Ui.barTint();
        barGlass.blurDp = Ui.barBlur();
        barGlass.pressX = touchX; barGlass.pressY = touchY;
        barGlass.pressR = Ui.dpf(48);
        barGlass.pressAmt = touch == T_SEARCH ? searchPress.value * 0.85f : (touch == T_HOME_CIRCLE ? barPress.value : 0f);
        barGlass.draw(canvas, this, backdrop);

        if (barA > 0.01f && !lens.isEmpty()) {
            float r = Math.min(lens.width(), lens.height()) / 2f;
            if (Ui.gStyle) {
                fill.setColor(Ui.alpha(Ui.dark ? 0x29FFFFFF : 0x14000000, barA));
                canvas.drawRoundRect(lens, r, r, fill);
            } else {
                // Crisp glass capsule indicator (expands smoothly when lifted even on software canvas!)
                int lensCol = l > 0.05f
                        ? Ui.mix(Ui.dark ? 0x26FFFFFF : 0x15000000, Ui.dark ? 0x3DFFFFFF : 0x28FFFFFF, l)
                        : (Ui.dark ? 0x26FFFFFF : 0x14000000);
                fill.setColor(Ui.alpha(lensCol, barA));
                canvas.drawRoundRect(lens, r, r, fill);
                if (l > 0.05f) {
                    fill.setStyle(Paint.Style.STROKE);
                    fill.setStrokeWidth(Math.max(1f, Ui.dpf(1.1f)));
                    fill.setColor(Ui.alpha(Ui.dark ? 0x66FFFFFF : 0x44000000, l * barA));
                    canvas.drawRoundRect(lens, r, r, fill);
                    fill.setStyle(Paint.Style.FILL);
                }
            }
        }

        // Draw base tabs + exact active accent clipping inside `lens`!
        drawTabs(canvas, k, Ui.text(), false);
        if (barA > 0.05f && !lens.isEmpty()) {
            float lr = Math.min(lens.width(), lens.height()) / 2f;
            lensClipPath.reset();
            lensClipPath.addRoundRect(lens, lr, lr, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(lensClipPath);
            drawTabs(canvas, k, Ui.ACCENT, true);
            canvas.restore();
        }
        drawSearchGlyphs(canvas);
    }

    private void drawTabs(Canvas c, float k, int col, boolean activePass) {
        if (tablet && search < 0.99f) {
            float sa = 1f - search;
            Icons.draw(c, Icons.SIDEBAR, pill.left + Ui.dpf(26), pill.centerY(), Ui.dpf(20), Ui.alpha(Ui.text(), sa));
        }
        if (tablet) {
            float ta = 1f - search;
            if (ta <= 0.01f) return;
            label.setTextSize(Ui.sp(14.5f));
            label.setTypeface(activePass ? Ui.bold : Ui.semibold);
            label.setColor(Ui.alpha(col, ta));
            float baseline = pill.centerY() + label.getTextSize() * 0.35f;
            for (int i = 0; i < 4; i++) {
                c.drawText(labels[i], slotCenter(i), baseline, label);
            }
            return;
        }
        if (rail) {
            float iconSize = Ui.dpf(23);
            for (int i = 0; i < 4; i++) {
                Icons.draw(c, TAB_ICONS[i], pill.centerX(), slotCenter(i), iconSize, col);
            }
            return;
        }

        float barA = 1f - k;
        if (barA > 0.01f) {
            int n = slotCount();
            label.setTextSize(Ui.sp(10.5f));
            label.setTypeface(activePass ? Ui.bold : Ui.semibold);
            label.setColor(Ui.alpha(col, barA));
            float iconY = pill.top + Ui.dpf(unifiedG() ? 22 : 23);
            float textY = pill.bottom - Ui.dpf(unifiedG() ? 10.5f : 11.5f);
            float iconSz = Ui.dpf(activePass ? 22.5f : 21.5f);
            for (int i = 0; i < n; i++) {
                float cx = slotCenter(i);
                int ic = i < 4 ? TAB_ICONS[i] : Icons.SEARCH;
                String lb = i < 4 ? labels[i] : "검색";
                Icons.draw(c, ic, cx, iconY, iconSz, Ui.alpha(col, barA));
                c.drawText(lb, cx, textY, label);
            }
        }
        // Collapsed home circle icon (when scrolled down)
        if (!unifiedG() && k > 0.01f && !activePass) {
            float pop = 1f + 0.12f * barPress.value;
            Icons.draw(c, TAB_ICONS[selected], pill.centerX(), pill.centerY(), Ui.dpf(22) * pop, Ui.alpha(Ui.text(), k));
        }
    }

    private void drawSearchGlyphs(Canvas c) {
        if (search <= 0.01f) {
            RectF cHost = activeCircleRect();
            if (!unifiedG() && !cHost.isEmpty()) {
                float pop = 1f + 0.14f * searchPress.value;
                Icons.draw(c, Icons.SEARCH, cHost.centerX(), cHost.centerY(), Ui.dpf(22) * pop, Ui.text());
            }
            return;
        }
        RectF host = (tablet || unifiedG()) ? pill : activeCircleRect();
        if (host.isEmpty()) return;
        float sa = Ui.clamp(search * 1.4f, 0f, 1f);
        float iconX = host.left + Ui.dpf(22);
        Icons.draw(c, Icons.SEARCH, iconX, host.centerY(), Ui.dpf(18), Ui.alpha(Ui.text2(), sa));
        float closeX = host.right - Ui.dpf(22);
        Icons.draw(c, Icons.CLOSE, closeX, host.centerY(), Ui.dpf(18), Ui.alpha(Ui.text(), sa));
    }

    /** Floating contextual glass bar above the tab pill (Viewer tools OR Open-file mini bar). */
    private void drawMini(Canvas c) {
        float sc = Ui.lerp(0.92f, 1f, miniShow);
        float cx = mini.centerX(), cy = mini.centerY();
        tmp.set(cx - mini.width() * 0.5f * sc, cy - mini.height() * 0.5f * sc,
                cx + mini.width() * 0.5f * sc, cy + mini.height() * 0.5f * sc);

        miniGlass.shapes(tmp, tmp.height() / 2f, null, 0, null, 0);
        miniGlass.tint = Ui.barTint();
        miniGlass.blurDp = Ui.barBlur();
        miniGlass.alpha = miniShow;
        miniGlass.pressX = touchX; miniGlass.pressY = touchY;
        miniGlass.pressR = Ui.dpf(54);
        miniGlass.pressAmt = (touch == T_TOOL_SLOT ? toolPress.value : miniPress.value) * 0.85f;
        miniGlass.draw(c, this, backdrop);

        if (toolMode) {
            drawViewerToolSlots(c, tmp);
        } else {
            drawOpenFileMini(c, tmp);
        }
    }

    /** 5 press-to-expand Liquid Glass tool slots inside the floating viewer bar! */
    private void drawViewerToolSlots(Canvas c, RectF box) {
        float pad = Ui.dpf(10);
        float sw = (box.width() - pad * 2f) / 5f;
        for (int i = 0; i < 5; i++) {
            ToolSlot s = toolSlots[i];
            if (s == null) continue;
            float scx = box.left + pad + sw * (i + 0.5f);
            float scy = box.centerY();
            boolean isPressed = (touch == T_TOOL_SLOT && pressedToolSlot == i);
            float p = isPressed ? toolPress.value : 0f;

            // Icon area expands cleanly with a glowing glass capsule when pressed or active!
            if (s.active || p > 0.02f) {
                float bw = Ui.lerp(sw * 0.84f, Math.min(sw * 1.08f, sw + Ui.dpf(12)), p);
                float bh = Ui.lerp(box.height() - Ui.dpf(10), box.height() + Ui.dpf(6), p);
                toolBubble.set(scx - bw / 2f, scy - bh / 2f, scx + bw / 2f, scy + bh / 2f);
                int bg = s.active
                        ? Ui.alpha(Ui.ACCENT, Ui.lerp(Ui.dark ? 0.28f : 0.16f, Ui.dark ? 0.42f : 0.26f, p))
                        : Ui.alpha(Ui.dark ? 0x38FFFFFF : 0x1E000000, p);
                fill.setColor(Ui.alpha(bg, miniShow));
                float r = Math.min(toolBubble.width(), toolBubble.height()) / 2f;
                c.drawRoundRect(toolBubble, r, r, fill);
                if (p > 0.05f && !Ui.gStyle) {
                    fill.setStyle(Paint.Style.STROKE);
                    fill.setStrokeWidth(Math.max(1f, Ui.dpf(0.9f)));
                    fill.setColor(Ui.alpha(Ui.dark ? 0x66FFFFFF : 0x33000000, p * miniShow));
                    c.drawRoundRect(toolBubble, r, r, fill);
                    fill.setStyle(Paint.Style.FILL);
                }
            }

            float pop = 1f + 0.16f * p;
            int fg = !s.enabled ? Ui.text3() : (s.active || p > 0.25f ? Ui.ACCENT : Ui.text());
            Icons.draw(c, s.icon, scx, scy - Ui.dpf(7.5f), Ui.dpf(20.5f) * pop, Ui.alpha(fg, miniShow));
            toolLabel.setColor(Ui.alpha(fg, miniShow));
            toolLabel.setTypeface((s.active || p > 0.25f) ? Ui.bold : Ui.semibold);
            c.drawText(s.label, scx, scy + Ui.dpf(15.5f), toolLabel);
        }
    }

    private void drawOpenFileMini(Canvas c, RectF box) {
        float pad = Ui.dpf(10);
        float badgeS = Ui.dpf(34);
        float bx = box.left + pad;
        float by = box.centerY() - badgeS / 2f;
        fill.setColor(Ui.alpha(Ui.ACCENT, (Ui.dark ? 0.25f : 0.15f) * miniShow));
        c.drawRoundRect(bx, by, bx + badgeS, by + badgeS, Ui.dpf(10), Ui.dpf(10), fill);
        Icons.draw(c, miniIcon, bx + badgeS / 2f, box.centerY(), Ui.dpf(19), Ui.alpha(Ui.ACCENT, miniShow));

        float rightZone = Ui.dpf(82);
        float tx = bx + badgeS + Ui.dpf(10);
        float avail = Math.max(Ui.dpf(40), box.right - rightZone - tx);
        String tStr = (miniModified ? "● " : "") + miniTitle;
        title.setColor(Ui.alpha(Ui.text(), miniShow));
        CharSequence tEl = TextUtils.ellipsize(tStr, title, avail, TextUtils.TruncateAt.END);
        c.drawText(tEl, 0, tEl.length(), tx, box.centerY() - Ui.dpf(2), title);

        sub.setColor(Ui.alpha(Ui.text2(), miniShow));
        CharSequence sEl = TextUtils.ellipsize(miniSub, sub, avail, TextUtils.TruncateAt.END);
        c.drawText(sEl, 0, sEl.length(), tx, box.centerY() + Ui.dpf(13.5f), sub);

        float saveCx = box.right - Ui.dpf(56);
        float closeCx = box.right - Ui.dpf(22);
        Icons.draw(c, Icons.SAVE, saveCx, box.centerY(), Ui.dpf(19), Ui.alpha(miniModified ? Ui.ACCENT : Ui.text(), miniShow));
        Icons.draw(c, Icons.CLOSE, closeCx, box.centerY(), Ui.dpf(18), Ui.alpha(Ui.text2(), miniShow));
    }

    private float clampLens(float a) {
        float half = slotW() * 0.48f;
        return Ui.clamp(a, axisStart() + slotPadPx() + half, axisStart() + axisLen() - slotPadPx() - half);
    }

    private int toolSlotAt(float x) {
        float pad = Ui.dpf(10);
        float sw = Math.max(1f, (mini.width() - pad * 2f) / 5f);
        int i = (int) ((x - mini.left - pad) / sw);
        return Math.max(0, Math.min(4, i));
    }

    RectF toolSlotAnchorInRoot(int slot) {
        layoutGeometry();
        float[] o = Glass.abs(this);
        float pad = Ui.dpf(10);
        float sw = (mini.width() - pad * 2f) / 5f;
        float l = mini.left + pad + sw * slot;
        RectF r = new RectF(l, mini.top, l + sw, mini.bottom);
        r.offset(o[0], o[1]);
        return r;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        layoutGeometry();
        float x = e.getX(), y = e.getY();
        int act = e.getActionMasked();
        boolean collapsed = isCollapsed();

        switch (act) {
            case MotionEvent.ACTION_DOWN: {
                touch = T_NONE;
                dragging = false;
                pressedToolSlot = -1;
                downX = touchX = x;
                downY = touchY = y;
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);

                // Search mode close button
                if (inSearch()) {
                    RectF host = (tablet || unifiedG()) ? pill : circle;
                    if (host.contains(x, y) && x >= host.right - Ui.dpf(48)) {
                        if (listener != null) listener.onExitSearch();
                        return true;
                    }
                    return host.contains(x, y);
                }

                // Floating contextual viewer bar (tools or open-file mini bar)
                if (miniVisible && miniShow > 0.5f && mini.contains(x, y)) {
                    if (toolMode) {
                        touch = T_TOOL_SLOT;
                        pressedToolSlot = toolSlotAt(x);
                        toolPress.to(1f);
                        return true;
                    }
                    if (x >= mini.right - Ui.dpf(38)) { touch = T_MINI_CLOSE; miniPress.to(1f); return true; }
                    if (x >= mini.right - Ui.dpf(74)) { touch = T_MINI_SAVE; miniPress.to(1f); return true; }
                    touch = T_MINI;
                    miniPress.to(1f);
                    return true;
                }

                // Detached search circle
                if (!circle.isEmpty() && circle.contains(x, y)) {
                    touch = T_SEARCH;
                    searchPress.to(1f);
                    return true;
                }

                // Floating tab pill
                if (!(tablet && sideP > 0.5f) && pill.contains(x, y)) {
                    if (tablet && x < pill.left + lead()) {
                        touch = T_SIDE;
                        barPress.to(1f);
                        return true;
                    }
                    if (!rail && !tablet && !unifiedG() && collapsed) {
                        touch = T_HOME_CIRCLE;
                        barPress.to(1f);
                        return true;
                    }
                    touch = T_TAB;
                    barPress.to(1f);
                    lift.to(1f);
                    lensX.to(clampLens(axisOf(x, y)));
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (touch == T_NONE) return false;
                touchX = x; touchY = y;
                if (vt != null) vt.addMovement(e);
                if (touch == T_TAB) {
                    float d = (float) Math.hypot(x - downX, y - downY);
                    if (d > Ui.dpf(6)) dragging = true;
                    if (vt != null) {
                        vt.computeCurrentVelocity(1000);
                        lensVel = rail ? vt.getYVelocity() : vt.getXVelocity();
                    }
                    lensX.to(clampLens(axisOf(x, y)));
                    invalidate();
                    return true;
                }
                if (touch == T_TOOL_SLOT && mini.contains(x, y)) {
                    int s = toolSlotAt(x);
                    if (s != pressedToolSlot) { pressedToolSlot = s; invalidate(); }
                    return true;
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                int was = touch;
                int wasTool = pressedToolSlot;
                boolean cancel = act == MotionEvent.ACTION_CANCEL;
                touch = T_NONE;
                pressedToolSlot = -1;
                lensVel = 0f;
                if (vt != null) { vt.recycle(); vt = null; }

                lift.to(0f);
                barPress.to(0f);
                miniPress.to(0f);
                searchPress.to(0f);
                toolPress.to(0f);

                if (cancel) {
                    lensX.to(slotCenter(selected));
                    return true;
                }

                if (was == T_TAB) {
                    int idx = slotAt(axisOf(x, y));
                    if (unifiedG() && idx == 4) {
                        lensX.to(slotCenter(selected));
                        if (listener != null) listener.onSearch();
                        return true;
                    }
                    boolean reselect = idx == selected && !dragging;
                    selected = Math.min(3, idx);
                    lensX.to(slotCenter(selected));
                    if (listener != null) listener.onTab(selected, reselect);
                    return true;
                }
                if (was == T_SEARCH && circle.contains(x, y)) {
                    if (listener != null) listener.onSearch();
                    return true;
                }
                if (was == T_SIDE && pill.contains(x, y)) {
                    if (listener != null) listener.onSidebar();
                    return true;
                }
                if (was == T_HOME_CIRCLE && pill.contains(x, y)) {
                    setCollapsed(false);
                    return true;
                }
                if (was == T_TOOL_SLOT && mini.contains(x, y) && wasTool >= 0 && wasTool < 5) {
                    if (listener != null && toolSlots[wasTool] != null && toolSlots[wasTool].enabled) {
                        listener.onToolAction(wasTool, toolSlotAnchorInRoot(wasTool));
                    }
                    return true;
                }
                if (was == T_MINI && mini.contains(x, y)) {
                    if (listener != null) listener.onOpenCurrentViewer();
                    return true;
                }
                if (was == T_MINI_SAVE && mini.contains(x, y)) {
                    if (listener != null) listener.onMiniQuickAction(QUICK_SAVE, toolSlotAnchorInRoot(3));
                    return true;
                }
                if (was == T_MINI_CLOSE && mini.contains(x, y)) {
                    if (listener != null) listener.onMiniQuickAction(QUICK_CLOSE, toolSlotAnchorInRoot(4));
                    return true;
                }
                return true;
            }
        }
        return false;
    }
}
