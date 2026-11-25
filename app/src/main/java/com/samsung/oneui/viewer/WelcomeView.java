package com.samsung.oneui.viewer;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.util.List;

/**
 * Prism + Apple Preview inspired start browser:
 * Large One UI 9.5 title, 26dp rounded grouped cards, quick actions (Open, Paste, Capture, Blank Sketch),
 * and a recent-documents list ready for one-tap opening.
 */
final class WelcomeView extends FrameLayout {
    static final int ACCENT = 0xFF387AFF;

    interface Actions {
        void open();
        void paste();
        void capture();
        void newSketch();
        void openFile(File file);
    }

    private final boolean dark;
    private TextView hint;
    private LinearLayout body;

    WelcomeView(Context context, boolean dark, Actions actions, List<File> recentFiles) {
        super(context);
        this.dark = dark;
        setTag("welcome");

        int text1 = dark ? 0xFFF4F6FA : 0xFF111318;
        int text2 = dark ? 0xFF9EA6B6 : 0xFF6B7280;
        int cardBg = dark ? 0xFF1B1E26 : 0xFFFFFFFF;
        int sepCol = dark ? 0x1AFFFFFF : 0x12000000;

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        body = new LinearLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int w = Math.min(MeasureSpec.getSize(widthSpec), dp(680));
                super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), heightSpec);
            }
        };
        body.setTag("welcome-card");
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(8), dp(18), dp(36));

        FrameLayout centerWrap = new FrameLayout(context);
        centerWrap.addView(body, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        scroll.addView(centerWrap, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // 1. Hero header (Prism style: icon + clean title, no PC slop)
        LinearLayout hero = new LinearLayout(context);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(20), dp(18), dp(20), dp(18));
        hero.setBackground(surface(cardBg, dp(26), 0));

        ImageView icon = new ImageView(context);
        icon.setTag("welcome-icon");
        icon.setContentDescription("뷰어");
        try (InputStream input = context.getAssets().open("icon.png")) {
            icon.setImageDrawable(Drawable.createFromStream(input, "viewer"));
        } catch (java.io.IOException ignored) {
            icon.setImageResource(context.getApplicationInfo().icon);
        }
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        hero.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout heroText = new LinearLayout(context);
        heroText.setOrientation(LinearLayout.VERTICAL);
        heroText.setPadding(dp(16), 0, 0, 0);
        TextView title = text("뷰어", 22, text1);
        title.setTag("welcome-title");
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setGravity(Gravity.START);
        TextView desc = text("PDF · 이미지 · 문서 보기 및 마크업", 13.5f, text2);
        desc.setTag("welcome-description");
        desc.setGravity(Gravity.START);
        heroText.addView(title);
        heroText.addView(desc);
        hero.addView(heroText, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        body.addView(hero, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // 2. Apple Preview style 4-tile Quick Actions grid
        body.addView(sectionLabel("빠른 시작", text2));
        GridLayout grid = new GridLayout(context);
        grid.setTag("welcome-actions");
        grid.setColumnCount(2);
        addTile(grid, "파일 열기", "PDF·사진·문서", "folder", ACCENT, cardBg, text1, text2, "welcome-open", v -> actions.open());
        addTile(grid, "빈 스케치", "새 캔버스 그리기", "markup", 0xFF10B981, cardBg, text1, text2, "welcome-sketch", v -> actions.newSketch());
        addTile(grid, "붙여넣기", "클립보드 이미지", "copy", 0xFF8B5CF6, cardBg, text1, text2, "welcome-paste", v -> actions.paste());
        addTile(grid, "화면 캡처", "현재 화면 편집", "crop", 0xFFF59E0B, cardBg, text1, text2, "welcome-capture", v -> actions.capture());
        body.addView(grid, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // 3. Recent Documents (Apple Preview document browser section)
        if (recentFiles != null && !recentFiles.isEmpty()) {
            body.addView(sectionLabel("최근 항목", text2));
            LinearLayout listCard = new LinearLayout(context);
            listCard.setOrientation(LinearLayout.VERTICAL);
            listCard.setBackground(surface(cardBg, dp(26), 0));
            int count = Math.min(recentFiles.size(), 6);
            for (int i = 0; i < count; i++) {
                File f = recentFiles.get(i);
                if (i > 0) {
                    View div = new View(context);
                    div.setBackgroundColor(sepCol);
                    LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1);
                    dlp.leftMargin = dp(64);
                    listCard.addView(div, dlp);
                }
                listCard.addView(recentRow(f, text1, text2, actions));
            }
            body.addView(listCard, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }

        hint = text("파일을 열거나 끌어다 놓으세요", 12.5f, text2);
        hint.setTag("welcome-hint");
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(18);
        body.addView(hint, hlp);
    }

    private void addTile(GridLayout grid, String label, String sub, String iconName, int tint,
                         int cardBg, int text1, int text2, String tag, OnClickListener onClick) {
        LinearLayout tile = new LinearLayout(getContext());
        tile.setTag(tag);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(16), dp(16), dp(16), dp(16));
        tile.setBackground(surface(cardBg, dp(24), 0));
        tile.setOnClickListener(onClick);
        GlassDrawable.attachRecoil(tile);

        FrameLayout badge = new FrameLayout(getContext());
        badge.setBackground(surface(Ui.alpha(tint, dark ? 0.25f : 0.14f), dp(14), 0));
        ImageView iv = new ImageView(getContext());
        iv.setImageDrawable(VectorIcons.create(getContext(), iconName, 20, tint));
        badge.addView(iv, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        tile.addView(badge, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView t = text(label, 15, text1);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        t.setGravity(Gravity.START);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(12);
        tile.addView(t, tlp);

        TextView s = text(sub, 12, text2);
        s.setGravity(Gravity.START);
        s.setMaxLines(1);
        s.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(s);

        GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
        glp.width = 0;
        glp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        glp.setMargins(dp(5), dp(5), dp(5), dp(5));
        grid.addView(tile, glp);
    }

    private View recentRow(File f, int text1, int text2, Actions actions) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(16), dp(13));
        row.setOnClickListener(v -> actions.openFile(f));
        GlassDrawable.attachRecoil(row);

        String name = f.getName();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toUpperCase() : "FILE";
        int badgeCol = "PDF".equals(ext) ? 0xFFEF4444 : ("PNG".equals(ext) || "JPG".equals(ext) || "JPEG".equals(ext) || "WEBP".equals(ext) ? 0xFF387AFF : 0xFF10B981);

        TextView extBadge = text(ext.length() > 4 ? ext.substring(0, 4) : ext, 10.5f, Color.WHITE);
        extBadge.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        extBadge.setBackground(surface(badgeCol, dp(12), 0));
        row.addView(extBadge, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout info = new LinearLayout(getContext());
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), 0, 0, 0);
        TextView nm = text(name, 14.5f, text1);
        nm.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        nm.setGravity(Gravity.START);
        nm.setMaxLines(1);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        TextView sz = text(formatSize(f.length()), 12, text2);
        sz.setGravity(Gravity.START);
        info.addView(nm);
        info.addView(sz);
        row.addView(info, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private TextView sectionLabel(String label, int color) {
        TextView tv = text(label, 13.5f, color);
        tv.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        tv.setGravity(Gravity.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        lp.bottomMargin = dp(8);
        lp.leftMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    void setOpening(boolean opening) {
        hint.setText(opening ? "파일을 여는 중…" : "파일을 열거나 끌어다 놓으세요");
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
