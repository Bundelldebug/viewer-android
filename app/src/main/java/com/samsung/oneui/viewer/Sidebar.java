package com.samsung.oneui.viewer;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Tablet PC-style collapsible sidebar ported 1:1 from Prism Music Android. */
final class Sidebar extends FrameLayout {
    private final MainActivity m;
    private final List<LinearLayout> tabRows = new ArrayList<>();
    private final LinearLayout quickBox;
    private final Icons.IconView quickChevron;
    private boolean quickOpen = true;

    static float widthFor(int W) { return Ui.clamp(W * 0.23f, Ui.dpf(232), Ui.dpf(276)); }

    Sidebar(MainActivity m) {
        super(m);
        this.m = m;
        setBackgroundColor(Ui.gStyle ? Ui.groupedBg() : (Ui.dark ? 0xFF151517 : 0xFFF3F3F7));
        setClickable(true);

        ScrollView sv = new ScrollView(m);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(OVER_SCROLL_NEVER);
        addView(sv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View divider = new View(m);
        divider.setBackgroundColor(Ui.separator());
        addView(divider, new LayoutParams(Math.max(1, Ui.dp(1)), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));

        LinearLayout col = new LinearLayout(m);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(11), Ui.statusInset + Ui.dp(12), Ui.dp(11), m.bottomPadding());
        sv.addView(col, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Title + sidebar close button
        LinearLayout top = new LinearLayout(m);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(8), 0, Ui.dp(2), Ui.dp(12));
        top.addView(Ui.text(m, "뷰어", 19, Ui.bold, Ui.text()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Icons.IconView close = new Icons.IconView(m, Icons.SIDEBAR, 19, Ui.text());
        GradientDrawable cb = Ui.round(Ui.card(), 100);
        cb.setStroke(Math.max(1, Ui.dp(0.8f)), Ui.dark ? 0x2BFFFFFF : 0x1A000000);
        close.setBackground(cb);
        close.setElevation(Ui.dpf(1.5f));
        close.setOnClickListener(v -> m.closeSidebar());
        Ui.press(close, 0.9f);
        top.addView(close, new LinearLayout.LayoutParams(Ui.dp(38), Ui.dp(38)));
        col.addView(top, Widgets.MW);

        // Compact PC-style search pill
        LinearLayout search = new LinearLayout(m);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        GradientDrawable sbg = Ui.round(Ui.dark ? 0xFF262629 : 0xFFE6E6EC, 100);
        sbg.setStroke(Math.max(1, Ui.dp(0.6f)), Ui.dark ? 0x1EFFFFFF : 0x10000000);
        search.setBackground(sbg);
        search.addView(new Icons.IconView(m, Icons.SEARCH, 16, Ui.text2()), new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)));
        TextView hint = Ui.text(m, "검색", 14.5f, Ui.regular, Ui.text2());
        hint.setPadding(Ui.dp(8), 0, 0, 0);
        search.addView(hint);
        search.setOnClickListener(v -> m.sidebarSearch());
        Ui.press(search, 0.97f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(38));
        slp.bottomMargin = Ui.dp(14);
        col.addView(search, slp);

        // 4 tabs
        String[] titles = {"홈", "보기", "목록", "설정"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout r = row(Dock.TAB_ICONS[i], titles[i], v -> m.sidebarTab(idx));
            tabRows.add(r);
            col.addView(r, rowLp());
        }

        // Collapsible quick tools section
        LinearLayout head = new LinearLayout(m);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(10), Ui.dp(20), Ui.dp(10), Ui.dp(6));
        head.addView(Ui.text(m, "빠른 작업", 13, Ui.medium, Ui.text2()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        quickChevron = new Icons.IconView(m, Icons.CHEVRON, 14, Ui.text2());
        quickChevron.setRotation(90);
        head.addView(quickChevron, new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)));
        head.setOnClickListener(v -> toggleQuick());
        col.addView(head, Widgets.MW);

        quickBox = new LinearLayout(m);
        quickBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(quickBox, Widgets.MW);
        buildQuick();
    }

    void buildQuick() {
        quickBox.removeAllViews();
        quickBox.addView(row(Icons.OPEN_FILE, "파일 열기", v -> m.launchSystemFilePicker()), rowLp());
        quickBox.addView(row(Icons.FOLDER, "폴더 열기", v -> m.chooseFolder()), rowLp());
        quickBox.addView(row(Icons.CAPTURE, "화면 캡처", v -> m.launchScreenCapture()), rowLp());
        quickBox.addView(row(Icons.PASTE, "클립보드 붙여넣기", v -> m.pasteClipboardImage()), rowLp());
        quickBox.addView(row(Icons.SKETCH, "새 스케치", v -> m.createBlankSketch()), rowLp());
        quickBox.addView(row(Icons.BOOKMARK_FILL, "책갈피 보기", v -> m.openFilesFilter(5)), rowLp());
    }

    private void toggleQuick() {
        quickOpen = !quickOpen;
        quickChevron.animate().rotation(quickOpen ? 90 : 0).setDuration(240).setInterpolator(Ui.EMPHASIZED).start();
        if (quickOpen) {
            quickBox.setVisibility(VISIBLE);
            quickBox.setAlpha(0f);
            quickBox.setTranslationY(-Ui.dpf(8));
            quickBox.animate().alpha(1f).translationY(0).setDuration(260).setInterpolator(Ui.EMPHASIZED).withEndAction(null).start();
        } else {
            quickBox.animate().alpha(0f).translationY(-Ui.dpf(8)).setDuration(180).setInterpolator(Ui.EMPHASIZED)
                    .withEndAction(() -> quickBox.setVisibility(GONE)).start();
        }
    }

    private static LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(40));
        lp.bottomMargin = Ui.dp(2);
        return lp;
    }

    private LinearLayout row(int icon, String title, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(getContext());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(11), 0, Ui.dp(11), 0);
        r.addView(new Icons.IconView(getContext(), icon, 19, Ui.ACCENT), new LinearLayout.LayoutParams(Ui.dp(22), Ui.dp(22)));
        TextView t = Ui.text(getContext(), title, 15, Ui.regular, Ui.text());
        t.setPadding(Ui.dp(12), 0, 0, 0);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.setOnClickListener(click);
        r.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.pressed()), null, Ui.round(0xFFFFFFFF, 100)));
        return r;
    }

    void setSelected(int tab) {
        for (int i = 0; i < tabRows.size(); i++) {
            LinearLayout r = tabRows.get(i);
            boolean on = i == tab;
            int selCol = Ui.gStyle ? Ui.alpha(Ui.ACCENT, Ui.dark ? 0.22f : 0.13f) : (Ui.dark ? 0x33FFFFFF : 0x17000000);
            Drawable base = on ? Ui.round(selCol, 100) : null;
            r.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.pressed()), base, Ui.round(0xFFFFFFFF, 100)));
            TextView t = (TextView) r.getChildAt(1);
            t.setTypeface(on ? Ui.semibold : Ui.regular);
            t.setTextColor(on && Ui.gStyle ? Ui.ACCENT : Ui.text());
        }
    }
}
