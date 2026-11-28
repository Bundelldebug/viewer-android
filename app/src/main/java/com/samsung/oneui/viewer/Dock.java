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
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

/**
 * Bottom Liquid Glass chrome (9.5 A1A1), laid out like a document browser app on iOS 26:
 * - phone: a centred floating glass tab pill (최근 항목 · 공유됨 · 둘러보기) with Prism's draggable refracting lens
 * - foldable unfolded: a vertical glass tab pill at the bottom right; the tab names show only while it is pressed
 * - tablet: a text tab pill at the top centre (sidebar button at its left)
 * - a document is open: the tabs give way to the document's tool groups, each its own glass capsule
 *   (phone: bottom left / bottom right; foldable: stacked at the bottom of the right-hand column)
 * - search: the tab pill morphs into the search field
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
    static final int[] TAB_ICONS = {Icons.RECENT, Icons.SHARED, Icons.FOLDER};
    static final String[] TAB_LABELS = {"최근 항목", "공유됨", "둘러보기"};
    /** The page each tab slot shows (MainActivity pages: 0 recents, 2 browse, 4 shared). */
    static final int[] SLOT_PAGE = {0, 4, 2};

    static int slotOfPage(int page) {
        for (int i = 0; i < SLOT_PAGE.length; i++) if (SLOT_PAGE[i] == page) return i;
        return -1;
    }

    /** One document tool button. */
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
    /** Selected tab slot (0 최근 항목, 1 공유됨, 2 둘러보기). */
    int selected;

    float collapse, search;   // collapse is kept for callers; this layout never folds the tab pill away
    private ValueAnimator searchAnim, toolAnim;
    private int imeOffset;
    final RectF pill = new RectF(), circle = new RectF(), lens = new RectF(), mini = new RectF();
    private final RectF tabsRect = new RectF(), fieldRect = new RectF(), tmp = new RectF();
    private final Path lensClipPath = new Path();

    private final Glass.Surface barGlass = new Glass.Surface();
    private final Glass.Surface lensGlass = new Glass.Surface();
    private final Glass.Surface[] groupGlass = {new Glass.Surface(), new Glass.Surface(), new Glass.Surface(), new Glass.Surface()};
    private final Object contentNodeObj; // RenderNode on API 29+

    final Ui.Spring lensX = new Ui.Spring(380f, 0.98f, 0.4f);
    final Ui.Spring lift = new Ui.Spring(460f, 1.0f, 0.002f);
    final Ui.Spring searchPress = new Ui.Spring(640f, 0.76f, 0.002f);
    final Ui.Spring barPress = new Ui.Spring(520f, 0.85f, 0.002f);
    final Ui.Spring toolPress = new Ui.Spring(560f, 0.72f, 0.002f);

    private float touchX, touchY;
    private VelocityTracker vt;
    private float lensVel;

    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

    final EditText field;

    // ---- document tools ----
    private boolean toolMode;
    private float toolK;
    private ToolSlot[] tools = new ToolSlot[0];
    private int[] groups = new int[0];
    private final RectF[] groupRects = {new RectF(), new RectF(), new RectF(), new RectF()};
    private RectF[] slotRects = new RectF[0];
    private int[] slotGroup = new int[0];
    private int pressedToolSlot = -1;

    private static final int T_NONE = 0, T_TAB = 1, T_SIDE = 7, T_TOOL_SLOT = 8;
    private int touch = T_NONE;
    private float downX, downY;
    private boolean dragging;

    Dock(Context c, Glass.Backdrop backdrop, String[] unusedLabels) {
        super(c);
        this.backdrop = backdrop;
        this.contentNodeObj = Build.VERSION.SDK_INT >= 29 ? new RenderNode("dock-content") : null;
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);

        label.setTypeface(Ui.semibold);
        label.setTextSize(Ui.sp(10.5f));
        label.setTextAlign(Paint.Align.CENTER);

        presetBar(barGlass);
        for (Glass.Surface g : groupGlass) presetBar(g);

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
        searchPress.onUpdate = inv;
        barPress.onUpdate = inv;
        toolPress.onUpdate = inv;

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

    private static void presetBar(Glass.Surface g) {
        g.blurDp = Ui.barBlur();
        g.refractDp = 17f;
        g.edgeDp = 26f;
        g.outerDp = 7f;
        g.outerRefractDp = 12f;
        g.chroma = 0f;
        g.vibrance = 1.35f;
        g.rim = 0.32f;
        g.edgeDark = 0.32f;
        g.shadowAlpha = 0.16f;
    }

    void setListener(Listener l) { listener = l; }

    /** Selects the tab slot that shows {@code page}; pages without a tab (the document, settings) keep the last one. */
    void select(int page, boolean animate) {
        int s = slotOfPage(page);
        if (s < 0) return;
        selected = s;
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

    /** Old entry point: one tool group. */
    void setFileState(boolean hasFile, boolean viewerTabActive, String name, String subtitle, int kindIcon, boolean modified, ToolSlot[] slots) {
        setTools(hasFile && viewerTabActive, slots, slots == null ? null : new int[]{slots.length});
    }

    /**
     * Document tools: {@code groups} splits {@code slots} into separate glass capsules (e.g. {3, 3} for a PDF:
     * markup · form · select | info · share · search; {1} for a picture: the markup button alone).
     */
    void setTools(boolean on, ToolSlot[] slots, int[] grp) {
        if (slots == null) slots = new ToolSlot[0];
        if (grp == null || grp.length == 0) grp = new int[]{slots.length};
        int total = 0;
        for (int g : grp) total += g;
        if (total != slots.length) grp = new int[]{slots.length};
        if (grp.length > groupRects.length) {
            int[] g2 = new int[groupRects.length];
            System.arraycopy(grp, 0, g2, 0, g2.length);
            int rest = 0;
            for (int i = groupRects.length - 1; i < grp.length; i++) rest += grp[i];
            g2[g2.length - 1] = rest;
            grp = g2;
        }
        tools = slots;
        groups = grp;
        slotRects = new RectF[slots.length];
        slotGroup = new int[slots.length];
        for (int i = 0; i < slots.length; i++) slotRects[i] = new RectF();
        for (int g = 0, k = 0; g < grp.length; g++) for (int j = 0; j < grp[g]; j++) slotGroup[k++] = g;
        if (on != toolMode) {
            toolMode = on;
            if (toolAnim != null) toolAnim.cancel();
            toolAnim = Ui.animate(toolK, on ? 1f : 0f, 440, Ui.EMPHASIZED, a -> { toolK = (float) a.getAnimatedValue(); invalidate(); });
        }
        invalidate();
    }

    boolean toolMode() { return toolMode; }
    int toolCount() { return tools.length; }
    RectF toolGroupRect(int g) { layoutGeometry(); return g >= 0 && g < groupRects.length ? new RectF(groupRects[g]) : new RectF(); }

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

    boolean animating() {
        return (morphAnim != null && morphAnim.isRunning()) || lensX.running || lift.running
                || searchPress.running || barPress.running || toolPress.running
                || (searchAnim != null && searchAnim.isRunning()) || (toolAnim != null && toolAnim.isRunning());
    }

    void setCollapsed(boolean c) {}
    boolean isCollapsed() { return false; }

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

    /** Space the floating chrome takes at the bottom of a page. */
    int chromeHeight() {
        if (tablet || rail) return Ui.navInset + Ui.dp(20);
        return Ui.navInset + (int) raise() + Ui.dp(toolMode ? 52 + 16 : 62 + 16);
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
        if (morphFrom != null && morph < 1f) morphRect(pill, 2);
    }

    private void layoutGeometry0() {
        float W = getWidth(), H = getHeight();
        if (W <= 0) return;
        rail = railFor((int) W, (int) H);
        tablet = tabletFor((int) W, (int) H);
        float bottom = bottomY();
        if (tablet) {
            float cl = contentLeft(), cw = W - cl;
            float h = Ui.dpf(46), top = Ui.statusInset + Ui.dpf(14);
            float pw = Math.min(Ui.dpf(46 + 3 * 112), Math.max(Ui.dpf(240), cw - Ui.dpf(260)));
            float left = cl + (cw - pw) / 2f;
            tabsRect.set(left, top, left + pw, top + h);
            float fw = Math.min(cw - Ui.dpf(40), Ui.dpf(520));
            fieldRect.set(cl + (cw - fw) / 2f, top, cl + (cw + fw) / 2f, top + h);
        } else if (rail) {
            // bottom-right vertical pill, sharing the right-hand column with the header's glass buttons
            float rw = Ui.dpf(60), len = Ui.dpf(RAIL_PAD * 2 + 3 * 58);
            float right = W - Ui.dpf(20);
            float top = Math.max(Ui.statusInset + Ui.dpf(150), bottom - len);
            tabsRect.set(right - rw, top, right, top + len);
            fieldRect.set(Ui.dpf(20), bottom - Ui.dpf(52), right, bottom);
        } else {
            float barW = Math.min(W - Ui.dpf(48), Ui.dpf(300)), h = Ui.dpf(62);
            tabsRect.set((W - barW) / 2f, bottom - h, (W + barW) / 2f, bottom);
            float m = Math.max(Ui.dpf(16), (W - Ui.dpf(600)) / 2f);
            fieldRect.set(m, bottom - Ui.dpf(52), W - m, bottom);
        }
        float s = ease(search);
        pill.set(Ui.lerp(tabsRect.left, fieldRect.left, s), Ui.lerp(tabsRect.top, fieldRect.top, s),
                Ui.lerp(tabsRect.right, fieldRect.right, s), Ui.lerp(tabsRect.bottom, fieldRect.bottom, s));
        if (search > 0.001f || field.getVisibility() == VISIBLE) circle.set(pill); else circle.setEmpty();
        mini.setEmpty();
        layoutTools(W, bottom);
    }

    /** Places the document tool capsules and the buttons inside them. */
    private void layoutTools(float W, float bottom) {
        for (RectF r : groupRects) r.setEmpty();
        int ng = Math.min(groups.length, groupRects.length);
        if (tools.length == 0 || ng == 0) return;
        float slot = Ui.dpf(rail ? 50 : 46), pad = Ui.dpf(6), thick = Ui.dpf(rail ? 60 : 52), gap = Ui.dpf(10);
        float[] len = new float[ng];
        int[] start = new int[ng];
        for (int g = 0, k = 0; g < ng; g++) {
            start[g] = k;
            k += groups[g];
            len[g] = groups[g] <= 1 ? thick : groups[g] * slot + pad * 2;
        }
        if (rail) {
            float right = W - Ui.dpf(20), y = bottom;
            for (int g = ng - 1; g >= 0; g--) {
                groupRects[g].set(right - thick, y - len[g], right, y);
                y -= len[g] + gap;
            }
        } else {
            float m = Ui.dpf(tablet ? 24 : 16);
            if (ng == 1) {
                float left = groups[0] <= 1 ? m : (W - len[0]) / 2f;
                groupRects[0].set(left, bottom - thick, left + len[0], bottom);
            } else if (ng == 2) {
                groupRects[0].set(m, bottom - thick, m + len[0], bottom);
                groupRects[1].set(W - m - len[1], bottom - thick, W - m, bottom);
            } else {
                float total = gap * (ng - 1);
                for (float l : len) total += l;
                float x = (W - total) / 2f;
                for (int g = 0; g < ng; g++) {
                    groupRects[g].set(x, bottom - thick, x + len[g], bottom);
                    x += len[g] + gap;
                }
            }
        }
        for (int g = 0; g < ng; g++) {
            RectF r = groupRects[g];
            int n = groups[g];
            for (int j = 0; j < n; j++) {
                int i = start[g] + j;
                if (i >= slotRects.length) break;
                float c = n <= 1 ? 0.5f : (pad + slot * (j + 0.5f)) / (rail ? r.height() : r.width());
                if (rail) {
                    float cy = r.top + r.height() * c;
                    slotRects[i].set(r.left, cy - slot / 2f, r.right, cy + slot / 2f);
                } else {
                    float cx = r.left + r.width() * c;
                    slotRects[i].set(cx - slot / 2f, r.top, cx + slot / 2f, r.bottom);
                }
            }
        }
    }

    private static final float SLOT_PAD = 6f, RAIL_PAD = 14f;
    private int slotCount() { return 3; }
    private float slotPadPx() { return Ui.dpf(rail || tablet ? RAIL_PAD : SLOT_PAD); }
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
        float sw = slotW();
        float l = lift.value;
        float thick = rail ? pill.width() : pill.height();
        float stretch = Math.min(Math.abs(lensVel) / 5000f, 0.22f) * l;
        float restLen = rail ? sw - Ui.dpf(4) : sw;
        float restThick = thick - Ui.dpf(rail ? 10 : 8);
        float len = Ui.lerp(restLen, Math.min(sw * 1.4f, sw + Ui.dpf(34)), l) * (1f + stretch);
        float th = Ui.lerp(restThick, thick + Ui.dpf(14), l) * (1f - stretch * 0.35f);
        float a = search > 0.01f ? slotCenter(selected) : lensX.value;
        float minA = axisStart() + Ui.dpf(4) + len / 2f;
        float maxA = axisStart() + axisLen() - Ui.dpf(4) - len / 2f;
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
        RectF host = circle;
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

    // ---- drawing ----
    /** Opacity of the tab pill's glass: gone while a document's tools are up (unless searching inside it). */
    private float pillGlassA() { return Math.max(1f - toolK, search) * (tablet ? 1f - sideP : 1f); }
    /** Opacity of the tab icons / names. */
    private float tabsA() { return (1f - toolK) * (1f - search) * (tablet ? 1f - sideP : 1f); }

    @Override protected void onDraw(Canvas canvas) {
        if (getWidth() == 0) return;
        layoutGeometry();
        if (lensX.value == 0) lensX.snap(slotCenter(selected));
        computeLens();
        if (!circle.isEmpty()) { positionField(); field.setAlpha(Ui.clamp((search - 0.35f) / 0.55f, 0f, 1f)); }

        drawTools(canvas);
        if (pillGlassA() <= 0.01f) return;
        float l = lift.value;
        boolean lensVisible = (l > 0.02f || (touch == T_TAB && lift.target > 0f)) && tabsA() > 0.25f && !Ui.gStyle;
        if (lensVisible && Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated() && Glass.shadersOk && Ui.glass && contentNodeObj != null) {
            drawBarAndLens(canvas, l);
        } else {
            drawBar(canvas, l);
        }
    }

    private void prepBar() {
        barGlass.smoothK = Ui.dpf(16) * lift.value;
        barGlass.shapes(pill, Math.min(pill.width(), pill.height()) / 2f, null, 0, null, 0);
        barGlass.tint = Ui.barTint();
        barGlass.blurDp = Ui.barBlur();
        barGlass.alpha = pillGlassA();
        barGlass.pressX = touchX; barGlass.pressY = touchY;
        barGlass.pressR = Ui.dpf(48);
        barGlass.pressAmt = touch == T_TAB ? barPress.value * 0.7f : 0f;
    }

    private void drawBarAndLens(Canvas canvas, float l) {
        int W = getWidth(), H = getHeight();
        float ta = tabsA();
        RenderNode contentNode = (RenderNode) contentNodeObj;
        contentNode.setPosition(0, 0, W, H);
        RecordingCanvas rc = contentNode.beginRecording(W, H);
        try {
            if (l < 0.99f && ta > 0.01f) {
                fill.setColor(Ui.alpha(Ui.dark ? 0x26FFFFFF : 0x16000000, ta * (1f - l)));
                float r = Math.min(lens.width(), lens.height()) / 2f;
                rc.drawRoundRect(lens, r, r, fill);
            }
            drawTabs(rc, Ui.text(), false);
            if (ta > 0.05f) {
                float lr = Math.min(lens.width(), lens.height()) / 2f;
                lensClipPath.reset();
                lensClipPath.addRoundRect(lens, lr, lr, Path.Direction.CW);
                rc.save();
                rc.clipPath(lensClipPath);
                drawTabs(rc, Ui.ACCENT, true);
                rc.restore();
            }
            drawSearchGlyphs(rc);
        } finally {
            contentNode.endRecording();
        }
        prepBar();
        barGlass.draw(canvas, this, backdrop);
        canvas.drawRenderNode(contentNode);

        lensGlass.shapes(lens, Math.min(lens.width(), lens.height()) / 2f, null, 0, null, 0);
        lensGlass.mag = 1f + 0.14f * l;
        lensGlass.magX = lens.centerX();
        lensGlass.magY = lens.centerY();
        lensGlass.alpha = Ui.clamp(l * 1.4f, 0f, 1f) * ta;
        lensGlass.tint = Ui.dark ? 0x0FFFFFFF : 0x0AFFFFFF;
        lensGlass.strokeColor = Ui.dark ? 0x40FFFFFF : 0x1A000000;
        lensGlass.pressX = touchX; lensGlass.pressY = touchY;
        lensGlass.pressR = Ui.dpf(42);
        lensGlass.pressAmt = barPress.value * 0.5f;
        lensGlass.extra = contentNode;
        lensGlass.draw(canvas, this, backdrop);
    }

    private void drawBar(Canvas canvas, float l) {
        prepBar();
        barGlass.draw(canvas, this, backdrop);
        float ta = tabsA();
        if (ta > 0.01f && !lens.isEmpty()) {
            float r = Math.min(lens.width(), lens.height()) / 2f;
            if (Ui.gStyle) {
                fill.setColor(Ui.alpha(Ui.dark ? 0x29FFFFFF : 0x14000000, ta));
                canvas.drawRoundRect(lens, r, r, fill);
            } else {
                int lensCol = l > 0.05f
                        ? Ui.mix(Ui.dark ? 0x26FFFFFF : 0x15000000, Ui.dark ? 0x3DFFFFFF : 0x28FFFFFF, l)
                        : (Ui.dark ? 0x26FFFFFF : 0x14000000);
                fill.setColor(Ui.alpha(lensCol, ta));
                canvas.drawRoundRect(lens, r, r, fill);
                if (l > 0.05f) {
                    fill.setStyle(Paint.Style.STROKE);
                    fill.setStrokeWidth(Math.max(1f, Ui.dpf(1.1f)));
                    fill.setColor(Ui.alpha(Ui.dark ? 0x66FFFFFF : 0x44000000, l * ta));
                    canvas.drawRoundRect(lens, r, r, fill);
                    fill.setStyle(Paint.Style.FILL);
                }
            }
        }
        drawTabs(canvas, Ui.text(), false);
        if (ta > 0.05f && !lens.isEmpty()) {
            float lr = Math.min(lens.width(), lens.height()) / 2f;
            lensClipPath.reset();
            lensClipPath.addRoundRect(lens, lr, lr, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(lensClipPath);
            drawTabs(canvas, Ui.ACCENT, true);
            canvas.restore();
        }
        drawSearchGlyphs(canvas);
    }

    private void drawTabs(Canvas c, int col, boolean activePass) {
        float ta = tabsA();
        if (ta <= 0.01f) return;
        if (tablet) {
            Icons.draw(c, Icons.SIDEBAR, pill.left + Ui.dpf(26), pill.centerY(), Ui.dpf(20), Ui.alpha(Ui.text(), ta));
            label.setTextSize(Ui.sp(14.5f));
            label.setTypeface(activePass ? Ui.bold : Ui.semibold);
            label.setColor(Ui.alpha(col, ta));
            float baseline = pill.centerY() + label.getTextSize() * 0.35f;
            for (int i = 0; i < 3; i++) c.drawText(TAB_LABELS[i], slotCenter(i), baseline, label);
            return;
        }
        if (rail) {
            // icons only; the names come out under the icons while the pill is pressed / dragged
            float lab = Ui.clamp(lift.value, 0f, 1f);
            label.setTextSize(Ui.sp(9.5f));
            label.setTypeface(activePass ? Ui.bold : Ui.semibold);
            label.setColor(Ui.alpha(col, ta * lab));
            for (int i = 0; i < 3; i++) {
                float cy = slotCenter(i);
                Icons.draw(c, TAB_ICONS[i], pill.centerX(), cy - Ui.dpf(7f) * lab, Ui.dpf(23) * Ui.lerp(1f, 0.92f, lab), Ui.alpha(col, ta));
                if (lab > 0.01f) c.drawText(TAB_LABELS[i], pill.centerX(), cy + Ui.dpf(16.5f), label);
            }
            return;
        }
        label.setTextSize(Ui.sp(10.5f));
        label.setTypeface(activePass ? Ui.bold : Ui.semibold);
        label.setColor(Ui.alpha(col, ta));
        float iconY = pill.top + Ui.dpf(23);
        float textY = pill.bottom - Ui.dpf(11.5f);
        float iconSz = Ui.dpf(activePass ? 22.5f : 21.5f);
        for (int i = 0; i < 3; i++) {
            float cx = slotCenter(i);
            Icons.draw(c, TAB_ICONS[i], cx, iconY, iconSz, Ui.alpha(col, ta));
            c.drawText(TAB_LABELS[i], cx, textY, label);
        }
    }

    private void drawSearchGlyphs(Canvas c) {
        if (search <= 0.01f || circle.isEmpty()) return;
        float sa = Ui.clamp(search * 1.4f, 0f, 1f);
        Icons.draw(c, Icons.SEARCH, circle.left + Ui.dpf(22), circle.centerY(), Ui.dpf(18), Ui.alpha(Ui.text2(), sa));
        Icons.draw(c, Icons.CLOSE, circle.right - Ui.dpf(22), circle.centerY(), Ui.dpf(18), Ui.alpha(Ui.text(), sa));
    }

    /** Document tool capsules: they grow out of their centres as the tab pill fades away. */
    private void drawTools(Canvas c) {
        float a = toolK * (1f - search);
        if (a <= 0.01f || tools.length == 0) return;
        float sc = Ui.lerp(0.8f, 1f, ease(toolK));
        int ng = Math.min(groups.length, groupRects.length);
        for (int g = 0; g < ng; g++) {
            RectF r = groupRects[g];
            if (r.isEmpty()) continue;
            scaled(r, sc, tmp);
            Glass.Surface gs = groupGlass[g];
            gs.shapes(tmp, Math.min(tmp.width(), tmp.height()) / 2f, null, 0, null, 0);
            gs.tint = Ui.barTint();
            gs.blurDp = Ui.barBlur();
            gs.alpha = a;
            boolean pressedHere = touch == T_TOOL_SLOT && pressedToolSlot >= 0 && pressedToolSlot < slotGroup.length && slotGroup[pressedToolSlot] == g;
            gs.pressX = touchX; gs.pressY = touchY;
            gs.pressR = Ui.dpf(44);
            gs.pressAmt = pressedHere ? toolPress.value * 0.85f : 0f;
            gs.draw(c, this, backdrop);
        }
        for (int i = 0; i < tools.length && i < slotRects.length; i++) {
            ToolSlot s = tools[i];
            if (s == null) continue;
            RectF gr = groupRects[Math.min(slotGroup[i], groupRects.length - 1)];
            float cx = Ui.lerp(gr.centerX(), slotRects[i].centerX(), sc);
            float cy = Ui.lerp(gr.centerY(), slotRects[i].centerY(), sc);
            float p = (touch == T_TOOL_SLOT && pressedToolSlot == i) ? toolPress.value : 0f;
            if (s.active) {
                fill.setColor(Ui.alpha(Ui.ACCENT, a));
                c.drawCircle(cx, cy, Ui.dpf(18) * (1f + 0.08f * p), fill);
            } else if (p > 0.02f) {
                fill.setColor(Ui.alpha(Ui.dark ? 0x33FFFFFF : 0x1A000000, a * p));
                c.drawCircle(cx, cy, Ui.dpf(20), fill);
            }
            int fg = !s.enabled ? Ui.text3() : (s.active ? 0xFFFFFFFF : Ui.text());
            Icons.draw(c, s.icon, cx, cy, Ui.dpf(22) * (1f + 0.12f * p), Ui.alpha(fg, a));
        }
    }

    private static void scaled(RectF r, float s, RectF out) {
        float hw = r.width() * s / 2f, hh = r.height() * s / 2f;
        out.set(r.centerX() - hw, r.centerY() - hh, r.centerX() + hw, r.centerY() + hh);
    }

    private float clampLens(float a) {
        float half = slotW() * 0.48f;
        return Ui.clamp(a, axisStart() + slotPadPx() + half, axisStart() + axisLen() - slotPadPx() - half);
    }

    private int toolAt(float x, float y) {
        float slop = Ui.dpf(4);
        for (int i = 0; i < slotRects.length; i++) {
            RectF r = slotRects[i];
            if (x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop) return i;
        }
        return -1;
    }

    RectF toolSlotAnchorInRoot(int slot) {
        layoutGeometry();
        float[] o = Glass.abs(this);
        RectF r = slot >= 0 && slot < slotRects.length ? new RectF(slotRects[slot])
                : new RectF(getWidth() / 2f - Ui.dpf(24), bottomY() - Ui.dpf(52), getWidth() / 2f + Ui.dpf(24), bottomY());
        r.offset(o[0], o[1]);
        return r;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        layoutGeometry();
        float x = e.getX(), y = e.getY();
        int act = e.getActionMasked();

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

                if (inSearch()) {
                    if (circle.contains(x, y) && x >= circle.right - Ui.dpf(48)) {
                        if (listener != null) listener.onExitSearch();
                        return true;
                    }
                    return circle.contains(x, y);
                }
                if (toolMode) {
                    if (toolK < 0.5f) return false;
                    int i = toolAt(x, y);
                    if (i < 0) return false;
                    touch = T_TOOL_SLOT;
                    pressedToolSlot = i;
                    toolPress.to(1f);
                    return true;
                }
                if (tablet && sideP > 0.5f) return false;
                if (pill.contains(x, y)) {
                    if (tablet && x < pill.left + lead()) {
                        touch = T_SIDE;
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
                    if (Math.hypot(x - downX, y - downY) > Ui.dpf(6)) dragging = true;
                    if (vt != null) {
                        vt.computeCurrentVelocity(1000);
                        lensVel = rail ? vt.getYVelocity() : vt.getXVelocity();
                    }
                    lensX.to(clampLens(axisOf(x, y)));
                    invalidate();
                    return true;
                }
                if (touch == T_TOOL_SLOT) {
                    int s = toolAt(x, y);
                    if (s >= 0 && s != pressedToolSlot) { pressedToolSlot = s; invalidate(); }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                int was = touch;
                int wasTool = pressedToolSlot;
                boolean cancel = act == MotionEvent.ACTION_CANCEL;
                touch = T_NONE;
                lensVel = 0f;
                if (vt != null) { vt.recycle(); vt = null; }
                lift.to(0f);
                barPress.to(0f);
                searchPress.to(0f);
                toolPress.to(0f);
                if (cancel) {
                    pressedToolSlot = -1;
                    lensX.to(slotCenter(selected));
                    return true;
                }
                if (was == T_TAB) {
                    int idx = slotAt(axisOf(x, y));
                    boolean reselect = idx == selected && !dragging;
                    selected = idx;
                    lensX.to(slotCenter(selected));
                    if (listener != null) listener.onTab(SLOT_PAGE[idx], reselect);
                    return true;
                }
                if (was == T_SIDE && pill.contains(x, y)) {
                    if (listener != null) listener.onSidebar();
                    return true;
                }
                if (was == T_TOOL_SLOT && wasTool >= 0 && toolAt(x, y) == wasTool) {
                    if (listener != null && wasTool < tools.length && tools[wasTool] != null && tools[wasTool].enabled) {
                        listener.onToolAction(wasTool, toolSlotAnchorInRoot(wasTool));
                    }
                }
                pressedToolSlot = -1;
                return true;
            }
        }
        return false;
    }
}
