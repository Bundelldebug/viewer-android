package com.samsung.oneui.viewer;

import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A scrolling screen with an optional large title that collapses into the fixed glass header (ported from Prism). */
abstract class Page extends FrameLayout {
    final MainActivity main;
    final Scroll scroll;
    final LinearLayout content;
    TextView bigTitle;
    String title;
    int[] headerIcons = {Icons.OPEN_FILE, Icons.MORE};
    boolean back;
    boolean grouped;
    private boolean started;

    final class Scroll extends ScrollView {
        Scroll() {
            super(Page.this.getContext());
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
            setClipToPadding(false);
        }

        @Override protected void onScrollChanged(int l, int t, int ol, int ot) {
            super.onScrollChanged(l, t, ol, ot);
            main.onPageScroll(Page.this, t, t - ot);
            Page.this.onScrolled(t);
        }
    }

    Page(MainActivity m, String title, boolean largeTitle) {
        super(m);
        main = m;
        this.title = title;
        scroll = new Scroll();
        content = new LinearLayout(m);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyInsets();
        if (largeTitle) addBigTitle();
    }

    void applyInsets() {
        int top = Ui.statusInset + Ui.dp(largeTop() ? 64 : 60);
        int right = main.sideInset();
        int bot = main.bottomPadding();
        if (content.getPaddingTop() != top || content.getPaddingRight() != right || content.getPaddingBottom() != bot) {
            content.setPadding(0, top, right, bot);
        }
    }

    boolean largeTop() { return true; }

    void addBigTitle() {
        boolean gRoot = Ui.gStyle && !back;
        boolean gSubClean = Ui.gStyle && back;
        bigTitle = Ui.text(getContext(), title, gRoot ? 34 : 36, Ui.bold, Ui.text());
        bigTitle.setLetterSpacing(Ui.gStyle ? -0.02f : -0.01f);
        if (gSubClean) {
            bigTitle.setVisibility(GONE);
            content.addView(bigTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
            return;
        }
        int avail = Math.max(Ui.dp(120), Ui.screenW - main.sideInset() - main.contentInsetPx() - Ui.dp(Ui.gStyle ? 44 : 34));
        float size = Math.min(gRoot ? 34f : 36f, fitTitleSize(title, avail));
        bigTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        bigTitle.setSingleLine(false);
        bigTitle.setMaxLines(3);
        bigTitle.setLineSpacing(0, 1.05f);
        if (gRoot) {
            boolean wideScreen = main.sideInset() > 0 || main.tabletUi();
            bigTitle.setGravity(wideScreen ? Gravity.START : Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int topM = Ui.dp(wideScreen ? 10 : (Ui.cover() ? 16 : 26));
            int botM = Ui.dp(wideScreen ? 6 : (Ui.cover() ? 10 : 14));
            lp.setMargins(Ui.dp(22), topM, Ui.dp(22), botM);
            content.addView(bigTitle, lp);
            return;
        }
        boolean oneLine = lines(title, avail, size) <= 1;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                oneLine ? Ui.dp(50) : ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(Ui.gStyle ? 24 : 18), Ui.dp(Ui.cover() ? 4 : 12), Ui.dp(Ui.gStyle ? 20 : 16), Ui.dp(Ui.cover() ? 0 : 6));
        content.addView(bigTitle, lp);
    }

    private static int lines(String s, int width, float sp) {
        if (s == null || s.isEmpty() || width <= 0) return 1;
        TextPaint p = new TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Ui.bold);
        p.setTextSize(Ui.sp(sp));
        p.setLetterSpacing(-0.01f);
        return StaticLayout.Builder.obtain(s, 0, s.length(), p, width).build().getLineCount();
    }

    static float fitTitleSize(String s, int width) {
        for (int maxLines = 1; maxLines <= 2; maxLines++) {
            for (float sp = 36f; sp >= 26f; sp -= 2f) {
                if (lines(s, width, sp) <= maxLines) return sp;
            }
        }
        return 24f;
    }

    void setGrouped(boolean g) {
        grouped = g;
        setBackgroundColor(g ? Ui.groupedBg() : Ui.bg());
    }

    void show() {
        if (!started) {
            started = true;
            if (Ui.gStyle && back && bigTitle != null && bigTitle.getVisibility() == VISIBLE) {
                bigTitle.setVisibility(GONE);
                bigTitle.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
            }
            if (getBackground() == null) setBackgroundColor(grouped ? Ui.groupedBg() : Ui.bg());
            build();
        }
    }

    void rebuild() {
        started = false;
        content.removeAllViews();
        if (bigTitle != null) addBigTitle();
        show();
    }

    abstract void build();

    void onScrolled(int y) {}

    float titleProgress() {
        int y = scroll.getScrollY();
        if (Ui.gStyle && back) return 1f;
        if (bigTitle == null) return Ui.clamp((y - Ui.dp(120)) / (float) Ui.dp(24), 0f, 1f);
        float start = bigTitle.getTop() - Ui.statusInset - Ui.dp(Ui.gStyle ? 24 : 40);
        return Ui.clamp((y - start) / (float) Ui.dp(22), 0f, 1f);
    }

    float blurProgress() { return Ui.clamp(scroll.getScrollY() / (float) Ui.dp(20), 0f, 1f); }

    void scrollToTop() { scroll.smoothScrollTo(0, 0); }
}
