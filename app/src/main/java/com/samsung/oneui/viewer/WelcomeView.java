package com.samsung.oneui.viewer;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** The desktop viewer's empty workspace, measured with native Android views. */
final class WelcomeView extends FrameLayout {
    static final int ACCENT = 0xFF55B499;
    interface Actions { void open(); void paste(); void capture(); }
    private final boolean dark;
    private TextView hint;
    private LinearLayout card;
    private FrameLayout center;
    private ImageView icon;
    private TextView title, description;
    private Flow actionsRow;
    private boolean compact;

    WelcomeView(Context context, boolean dark, Actions actions) {
        super(context); this.dark = dark; setTag("welcome");
        ScrollView scroll = new ScrollView(context); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        center = new FrameLayout(context);
        center.setPadding(dp(16), dp(24), dp(16), dp(24));
        scroll.addView(center, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        card = new LinearLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = Math.min(MeasureSpec.getSize(widthSpec), dp(560));
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
            }
        };
        card.setTag("welcome-card"); card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(24), dp(30), dp(24), dp(28));
        card.setBackground(surface(dark ? 0xFF282D2E : 0xFFFFFFFF, dp(28), 0));
        center.addView(card, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        icon = new ImageView(context); icon.setTag("welcome-icon"); icon.setContentDescription("뷰어");
        try (InputStream input = context.getAssets().open("icon.png")) { icon.setImageDrawable(Drawable.createFromStream(input, "viewer")); }
        catch (java.io.IOException ignored) { icon.setImageResource(context.getApplicationInfo().icon); }
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card.addView(icon, new LinearLayout.LayoutParams(dp(76), dp(76)));

        title = text("Samsung Viewer", 22, dark ? 0xFFF1F4F3 : 0xFF19221F);
        title.setTag("welcome-title"); title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(16); card.addView(title, titleParams);

        description = text("파일을 끌어다 놓거나 열기를 누르세요.", 14, dark ? 0xFFB1BEBA : 0xFF697773);
        description.setTag("welcome-description"); description.setLineSpacing(dp(3), 1);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        descriptionParams.topMargin = dp(6); card.addView(description, descriptionParams);

        actionsRow = new Flow(context); actionsRow.setTag("welcome-actions");
        actionsRow.addView(button("파일 열기", "welcome-open", true, v -> actions.open()));
        TextView paste = button("클립보드에서 새로 만들기", "welcome-paste", false, v -> actions.paste());
        actionsRow.addView(paste);
        actionsRow.addView(button("화면 캡처", "welcome-capture", false, v -> actions.capture()));
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dp(22); card.addView(actionsRow, actionsParams);
        actionsRow.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            String label = r-l < dp(400) ? "붙여넣기" : "클립보드에서 새로 만들기";
            if (!label.contentEquals(paste.getText())) paste.setText(label);
        });
        paste.setContentDescription("클립보드 이미지에서 새로 만들기");

        hint = text("Ctrl+O 열기 · Ctrl+V 붙여넣기", 12, dark ? 0xFFA2AFAB : 0xFF77837F);
        hint.setTag("welcome-hint");
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(18); card.addView(hint, hintParams);
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        boolean shortWindow=MeasureSpec.getMode(heightSpec)!=MeasureSpec.UNSPECIFIED && MeasureSpec.getSize(heightSpec)<dp(380);
        if(compact!=shortWindow) {
            compact=shortWindow;
            center.setPadding(dp(16),dp(compact?12:24),dp(16),dp(compact?12:24));
            card.setPadding(dp(24),dp(compact?16:30),dp(24),dp(compact?16:28));
            LinearLayout.LayoutParams iconSize=(LinearLayout.LayoutParams)icon.getLayoutParams();iconSize.width=iconSize.height=dp(compact?48:76);icon.setLayoutParams(iconSize);
            margin(title,compact?8:16);margin(description,compact?4:6);margin(actionsRow,compact?12:22);margin(hint,compact?12:18);
        }
        super.onMeasure(widthSpec,heightSpec);
    }
    private void margin(View view,int top){LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)view.getLayoutParams();p.topMargin=dp(top);view.setLayoutParams(p);}
    void setOpening(boolean opening) { hint.setText(opening ? "파일을 여는 중…" : "Ctrl+O 열기 · Ctrl+V 붙여넣기"); }
    private TextView text(String value, float size, int color) {
        TextView view = new TextView(getContext()); view.setText(value); view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color); view.setGravity(Gravity.CENTER); return view;
    }
    private TextView button(String label, String tag, boolean primary, OnClickListener action) {
        TextView button = text(label, 14, primary ? 0xFFFFFFFF : dark ? 0xFFE7EEEB : 0xFF202A26);
        button.setTag(tag); button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setPadding(dp(18), dp(12), dp(18), dp(12)); button.setMinHeight(dp(48)); button.setMaxLines(2);
        int fill = primary ? ACCENT : dark ? 0xFF363E3B : 0xFFEEF1EF;
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x33FFFFFF : 0x22000000), surface(fill, dp(28), 0), null));
        button.setOnClickListener(action); button.setFocusable(true); return button;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    static GradientDrawable surface(int color, float radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (stroke != 0) drawable.setStroke(1, stroke); return drawable;
    }

    /** Center each line of actions; no clipped labels or fixed-width tablet layouts on phones. */
    private final class Flow extends ViewGroup {
        private final List<List<View>> lines = new ArrayList<>();
        Flow(Context context) { super(context); }
        @Override protected LayoutParams generateDefaultLayoutParams() { return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int available = MeasureSpec.getSize(widthSpec), height = 0, lineWidth = 0, lineHeight = 0, gap = dp(8);
            lines.clear(); List<View> line = new ArrayList<>();
            for (int i=0;i<getChildCount();i++) {
                View child = getChildAt(i); child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (!line.isEmpty() && lineWidth + gap + child.getMeasuredWidth() > available) {
                    lines.add(line); line = new ArrayList<>(); height += lineHeight + gap; lineWidth = lineHeight = 0;
                }
                if (!line.isEmpty()) lineWidth += gap;
                line.add(child); lineWidth += child.getMeasuredWidth(); lineHeight = Math.max(lineHeight, child.getMeasuredHeight());
            }
            if (!line.isEmpty()) { lines.add(line); height += lineHeight; }
            setMeasuredDimension(resolveSize(available, widthSpec), resolveSize(height, heightSpec));
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int top = 0, gap = dp(8);
            for (List<View> line : lines) {
                int width = gap * Math.max(0, line.size()-1), height = 0;
                for (View child : line) { width += child.getMeasuredWidth(); height = Math.max(height, child.getMeasuredHeight()); }
                int left = (getWidth()-width)/2;
                for (View child : line) { int y = top+(height-child.getMeasuredHeight())/2; child.layout(left,y,left+child.getMeasuredWidth(),y+child.getMeasuredHeight()); left += child.getMeasuredWidth()+gap; }
                top += height+gap;
            }
        }
    }
}
