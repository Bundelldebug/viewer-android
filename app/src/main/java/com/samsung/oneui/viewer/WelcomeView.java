package com.samsung.oneui.viewer;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * Prism Music identical Home / Start Browser for Viewer:
 * Large 34sp title, horizontal quick-action chips, Prism 26dp grouped file list,
 * and floating bottom Prism Liquid Glass Dock (최근 | 책갈피 | 설정 + detached [+] button).
 */
final class WelcomeView extends FrameLayout {
    static final int ACCENT = 0xFF387AFF;

    interface Actions {
        void open();
        void paste();
        void capture();
        void newSketch();
        void openFile(File file);
        void openSettings();
        void checkUpdate();
        void toggleTheme();
    }

    private final boolean dark;
    private final Actions actions;
    private final List<File> recentFiles;
    private final List<String> bookmarks;
    private final Glass.Backdrop backdrop;
    private int activeTab = 0; // 0 = 최근, 1 = 책갈피, 2 = 설정
    private LinearLayout content;
    private LinearLayout dockPill;
    private TextView hint;

    WelcomeView(Context context, boolean dark, Glass.Backdrop backdrop, Actions actions,
                List<File> recentFiles, List<String> bookmarks) {
        super(context);
        this.dark = dark;
        this.backdrop = backdrop;
        this.actions = actions;
        this.recentFiles = recentFiles;
        this.bookmarks = bookmarks;
        setTag("welcome");
        setClipChildren(false);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        content = new LinearLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int w = Math.min(MeasureSpec.getSize(widthSpec), dp(720));
                super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), heightSpec);
            }
        };
        content.setTag("welcome-card");
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(64), dp(16), dp(116));

        FrameLayout centerWrap = new FrameLayout(context);
        centerWrap.addView(content, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        scroll.addView(centerWrap, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Floating Prism Bottom Dock (tab pill + detached circular [+] open button)
        LinearLayout dockRow = new LinearLayout(context);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER_VERTICAL);
        dockRow.setClipChildren(false);
        dockRow.setPadding(dp(16), dp(8), dp(16), dp(14));

        dockPill = new LinearLayout(context);
        dockPill.setOrientation(LinearLayout.HORIZONTAL);
        dockPill.setGravity(Gravity.CENTER_VERTICAL);
        dockPill.setPadding(dp(6), dp(5), dp(6), dp(5));
        GlassDrawable.attachGlass(dockPill, Glass.shadersOk
                ? GlassDrawable.bar(backdrop, dark ? 0xFF0E1015 : 0xFFF2F4F8, dp(32))
                : surface(dark ? 0xE61C2029 : 0xEBFFFFFF, dp(32), dark ? 0x26FFFFFF : 0x18000000));
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(0, dp(62), 1f);
        pillLp.rightMargin = dp(10);
        dockRow.addView(dockPill, pillLp);

        // Detached circular glass [+] button (same silhouette as Prism's detached search circle)
        FrameLayout plusCircle = new FrameLayout(context);
        GlassDrawable.attachGlass(plusCircle, Glass.shadersOk
                ? GlassDrawable.bar(backdrop, dark ? 0xFF0E1015 : 0xFFF2F4F8, dp(31))
                : surface(dark ? 0xE61C2029 : 0xEBFFFFFF, dp(31), dark ? 0x26FFFFFF : 0x18000000));
        ImageView plusIv = new ImageView(context);
        plusIv.setImageDrawable(VectorIcons.create(context, "open", 22, dark ? 0xFFF4F6FA : 0xFF111318));
        plusCircle.addView(plusIv, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        plusCircle.setContentDescription("파일 열기");
        plusCircle.setOnClickListener(v -> actions.open());
        dockRow.addView(plusCircle, new LinearLayout.LayoutParams(dp(62), dp(62)));

        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        addView(dockRow, dlp);

        rebuildPage();
        rebuildDock();
    }

    private void rebuildDock() {
        dockPill.removeAllViews();
        String[] labels = {"최근", "책갈피", "설정"};
        String[] icons = {"folder", "bookmark", "menu"};
        int ink = dark ? 0xFFB8C0D0 : 0xFF525A69;
        int selBg = dark ? 0x28387AFF : 0x1A387AFF;
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean sel = activeTab == i;
            LinearLayout tab = new LinearLayout(getContext());
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(6), dp(6), dp(6), dp(6));
            tab.setBackground(sel ? surface(selBg, dp(26), 0) : null);
            GlassDrawable.attachRecoil(tab);

            int fg = sel ? ACCENT : ink;
            ImageView iv = new ImageView(getContext());
            iv.setImageDrawable(VectorIcons.create(getContext(), icons[i], 20, fg));
            tab.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));

            TextView tv = text(labels[i], 10.5f, fg);
            tv.setTypeface(Typeface.create("sans-serif", sel ? Typeface.BOLD : Typeface.NORMAL));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(2);
            tab.addView(tv, tlp);

            tab.setOnClickListener(v -> {
                if (activeTab != idx) {
                    activeTab = idx;
                    rebuildPage();
                    rebuildDock();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            dockPill.addView(tab, lp);
        }
    }

    private void rebuildPage() {
        content.removeAllViews();
        int text1 = dark ? 0xFFF4F6FA : 0xFF111318;
        int text2 = dark ? 0xFF9EA6B6 : 0xFF6B7280;
        int cardBg = dark ? 0xFF1B1E26 : 0xFFFFFFFF;
        int sepCol = dark ? 0x1AFFFFFF : 0x12000000;

        // Prism large title (no duplicate hero card!)
        String pageTitle = activeTab == 0 ? "뷰어" : (activeTab == 1 ? "책갈피" : "설정");
        TextView bigTitle = text(pageTitle, 34, text1);
        bigTitle.setTag("welcome-title");
        bigTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        bigTitle.setGravity(Gravity.START);
        LinearLayout.LayoutParams btlp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        btlp.leftMargin = dp(4);
        btlp.bottomMargin = dp(16);
        content.addView(bigTitle, btlp);

        if (activeTab == 0) {
            // Prism-style clean horizontal quick chips (no pastel 2x2 AI slop tiles)
            HorizontalScrollView hs = new HorizontalScrollView(getContext());
            hs.setHorizontalScrollBarEnabled(false);
            hs.setClipToPadding(false);
            LinearLayout chipRow = new LinearLayout(getContext());
            chipRow.setTag("welcome-actions");
            chipRow.setOrientation(LinearLayout.HORIZONTAL);
            chipRow.addView(quickChip("파일 열기", "open", true, cardBg, text1, "welcome-open", v -> actions.open()));
            chipRow.addView(quickChip("빈 스케치", "markup", false, cardBg, text1, "welcome-sketch", v -> actions.newSketch()));
            chipRow.addView(quickChip("붙여넣기", "copy", false, cardBg, text1, "welcome-paste", v -> actions.paste()));
            chipRow.addView(quickChip("화면 캡처", "crop", false, cardBg, text1, "welcome-capture", v -> actions.capture()));
            hs.addView(chipRow);
            content.addView(hs, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            // Recent Files — Prism 26dp grouped card
            content.addView(sectionLabel("최근 항목", text2));
            LinearLayout group = new LinearLayout(getContext());
            group.setOrientation(LinearLayout.VERTICAL);
            group.setBackground(surface(cardBg, dp(26), 0));
            if (recentFiles == null || recentFiles.isEmpty()) {
                TextView empty = text("최근에 연 문서가 없습니다", 14, text2);
                empty.setPadding(dp(20), dp(28), dp(20), dp(28));
                group.addView(empty);
            } else {
                int count = Math.min(recentFiles.size(), 12);
                for (int i = 0; i < count; i++) {
                    if (i > 0) group.addView(divider(sepCol));
                    group.addView(fileRow(recentFiles.get(i), text1, text2));
                }
            }
            content.addView(group, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        } else if (activeTab == 1) {
            LinearLayout group = new LinearLayout(getContext());
            group.setOrientation(LinearLayout.VERTICAL);
            group.setBackground(surface(cardBg, dp(26), 0));
            if (bookmarks == null || bookmarks.isEmpty()) {
                TextView empty = text("책갈피한 파일이 없습니다", 14, text2);
                empty.setPadding(dp(20), dp(28), dp(20), dp(28));
                group.addView(empty);
            } else {
                int added = 0;
                for (String path : bookmarks) {
                    File f = new File(path);
                    if (!f.exists()) continue;
                    if (added > 0) group.addView(divider(sepCol));
                    group.addView(fileRow(f, text1, text2));
                    added++;
                }
                if (added == 0) {
                    TextView empty = text("책갈피한 파일이 없습니다", 14, text2);
                    empty.setPadding(dp(20), dp(28), dp(20), dp(28));
                    group.addView(empty);
                }
            }
            content.addView(group, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        } else {
            // Prism Settings grouped card
            LinearLayout g1 = new LinearLayout(getContext());
            g1.setOrientation(LinearLayout.VERTICAL);
            g1.setBackground(surface(cardBg, dp(26), 0));
            g1.addView(settingRow("테마 전환", dark ? "다크" : "라이트", text1, text2, v -> actions.toggleTheme()));
            g1.addView(divider(sepCol));
            g1.addView(settingRow("뷰어 설정", "내보내기 · 보기 옵션", text1, text2, v -> actions.openSettings()));
            content.addView(g1, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            content.addView(sectionLabel("앱 정보", text2));
            LinearLayout g2 = new LinearLayout(getContext());
            g2.setOrientation(LinearLayout.VERTICAL);
            g2.setBackground(surface(cardBg, dp(26), 0));
            g2.addView(settingRow("뷰어", Updater.VERSION, text1, text2, null));
            g2.addView(divider(sepCol));
            g2.addView(settingRow("업데이트 확인", "GitHub", text1, ACCENT, v -> actions.checkUpdate()));
            content.addView(g2, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }

        hint = text("", 12, text2);
        hint.setTag("welcome-hint");
        hint.setVisibility(View.GONE);
        content.addView(hint);
    }

    private View quickChip(String label, String iconName, boolean primary, int cardBg, int text1, String tag, OnClickListener onClick) {
        LinearLayout chip = new LinearLayout(getContext());
        chip.setTag(tag);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(14), dp(10), dp(16), dp(10));
        chip.setBackground(surface(primary ? ACCENT : cardBg, dp(22), 0));
        chip.setOnClickListener(onClick);
        GlassDrawable.attachRecoil(chip);

        int fg = primary ? Color.WHITE : text1;
        ImageView iv = new ImageView(getContext());
        iv.setImageDrawable(VectorIcons.create(getContext(), iconName, 17, fg));
        chip.addView(iv, new LinearLayout.LayoutParams(dp(17), dp(17)));

        TextView tv = text(label, 13.5f, fg);
        tv.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(7);
        chip.addView(tv, tlp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        chip.setLayoutParams(lp);
        return chip;
    }

    private View fileRow(File f, int text1, int text2) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(16), dp(13));
        row.setOnClickListener(v -> actions.openFile(f));
        GlassDrawable.attachRecoil(row);

        String name = f.getName();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toUpperCase() : "FILE";
        int badgeCol = "PDF".equals(ext) ? 0xFFEF4444
                : ("PNG".equals(ext) || "JPG".equals(ext) || "JPEG".equals(ext) || "WEBP".equals(ext) ? ACCENT : 0xFF10B981);

        TextView extBadge = text(ext.length() > 4 ? ext.substring(0, 4) : ext, 10.5f, badgeCol);
        extBadge.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        extBadge.setBackground(surface(Ui.alpha(badgeCol, dark ? 0.24f : 0.12f), dp(12), 0));
        row.addView(extBadge, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout info = new LinearLayout(getContext());
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(13), 0, dp(8), 0);
        TextView nm = text(name, 15f, text1);
        nm.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        nm.setGravity(Gravity.START);
        nm.setMaxLines(1);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        TextView sz = text(formatSize(f.length()), 12.5f, text2);
        sz.setGravity(Gravity.START);
        info.addView(nm);
        info.addView(sz);
        row.addView(info, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        TextView chev = text("›", 19, dark ? 0x55FFFFFF : 0x44000000);
        row.addView(chev);
        return row;
    }

    private View settingRow(String title, String value, int text1, int valCol, OnClickListener onClick) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(15), dp(18), dp(15));
        if (onClick != null) {
            row.setOnClickListener(onClick);
            GlassDrawable.attachRecoil(row);
        }
        TextView t = text(title, 15.5f, text1);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.START);
        row.addView(t, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(value, 14f, valCol);
        row.addView(v);
        return row;
    }

    private View divider(int color) {
        View div = new View(getContext());
        div.setBackgroundColor(color);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1);
        dlp.leftMargin = dp(18);
        div.setLayoutParams(dlp);
        return div;
    }

    private TextView sectionLabel(String label, int color) {
        TextView tv = text(label, 14f, color);
        tv.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        tv.setGravity(Gravity.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(22);
        lp.bottomMargin = dp(8);
        lp.leftMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    void setOpening(boolean opening) {
        if (hint == null) return;
        hint.setVisibility(opening ? View.VISIBLE : View.GONE);
        hint.setText(opening ? "파일을 여는 중…" : "");
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private static String formatSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", b / (1024f * 1024f));
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    static GradientDrawable surface(int color, float radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (stroke != 0) drawable.setStroke(1, stroke);
        return drawable;
    }
}
