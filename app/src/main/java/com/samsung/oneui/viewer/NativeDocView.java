package com.samsung.oneui.viewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 100% Pure Native Android Document, Spreadsheet, Slide, and Code/Markdown Viewer & Editor.
 * Zero WebView usage. Renders Docs.render(path) output using native ScrollView, TableLayout,
 * LinearLayout, TextView, EditText, and ImageView, preserving data-k unit bindings for DocEdit.save().
 */
public final class NativeDocView extends FrameLayout {

    public interface DocListener {
        void onDocDirtyChanged(boolean dirty);
        void onSheetSelected(int sheetIdx, String sheetTitle);
        void onSearchHitsChanged(int currentIdx, int totalHits);
    }

    public static final class UnitView {
        public final String key; // e.g., "u0", "r0c1", "s0r1c2", "t"
        public String initialText;
        public String currentText;
        public TextView view;
        public final boolean isCell;
        public final boolean isHeading;

        public UnitView(String key, String text, boolean isCell, boolean isHeading) {
            this.key = key;
            this.initialText = text != null ? text : "";
            this.currentText = this.initialText;
            this.isCell = isCell;
            this.isHeading = isHeading;
        }
    }

    private DocListener listener;
    private final ScrollView vScroll;
    private final LinearLayout rootContainer;
    private LinearLayout sheetTabsBar;

    private String currentPath = "";
    private String docKind = "word"; // "word", "sheet", "slides", "text"
    private String editMode = "none"; // "zip", "csv", "text", "conv", "none"
    private String docNote = null;
    private boolean isEditing = false;
    private boolean isDarkTheme = true;
    private float zoomScale = 1.0f;

    private final List<UnitView> units = new ArrayList<>();
    private final List<View> sheetContainers = new ArrayList<>();
    private final List<String> sheetNames = new ArrayList<>();
    private int activeSheetIdx = 0;

    // Undo / Redo snapshots of unit values
    private final List<Map<String, String>> undoStack = new ArrayList<>();
    private final List<Map<String, String>> redoStack = new ArrayList<>();
    private boolean suppressWatcher = false;

    // Search state
    private String searchQuery = "";
    private final List<UnitView> searchMatches = new ArrayList<>();
    private int activeSearchMatch = -1;

    public NativeDocView(Context context) {
        super(context);
        vScroll = new ScrollView(context);
        vScroll.setFillViewport(true);

        rootContainer = new LinearLayout(context);
        rootContainer.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        rootContainer.setPadding(pad, pad, pad, dp(88));
        vScroll.addView(rootContainer, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(vScroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    public void setDocListener(DocListener l) {
        this.listener = l;
    }

    public void setDarkTheme(boolean dark) {
        if (isDarkTheme == dark) return;
        this.isDarkTheme = dark;
        if (!currentPath.isEmpty()) {
            rebuildNativeViews();
        }
    }

    public String getDocKind() {
        return docKind;
    }

    public String getEditMode() {
        return editMode;
    }

    public boolean canEdit() {
        return !"none".equals(editMode);
    }

    public boolean isEditing() {
        return isEditing;
    }

    public void setEditing(boolean edit) {
        if (this.isEditing == edit) return;
        this.isEditing = edit;
        rebuildNativeViews();
    }

    public float getZoomScale() {
        return zoomScale;
    }

    public void setZoomScale(float scale) {
        this.zoomScale = Math.max(0.5f, Math.min(2.5f, scale));
        rebuildNativeViews();
    }

    public boolean isDirty() {
        for (UnitView u : units) {
            if (!u.initialText.equals(u.currentText)) return true;
        }
        return false;
    }
    public void retarget(String path) { currentPath = path; }

    // ---------- Load Document ----------
    private String cachedRawHtml = "";

    public Map<String, Object> loadDocument(String path) {
        this.currentPath = path;
        this.isEditing = false;
        this.searchQuery = "";
        this.searchMatches.clear();
        this.activeSearchMatch = -1;
        this.undoStack.clear();
        this.redoStack.clear();
        this.units.clear();
        this.sheetContainers.clear();
        this.sheetNames.clear();
        this.activeSheetIdx = 0;

        Map<String, Object> res = Docs.render(path);
        if (!Boolean.TRUE.equals(res.get("ok"))) {
            rootContainer.removeAllViews();
            TextView err = new TextView(getContext());
            err.setText(String.valueOf(res.get("error") != null ? res.get("error") : "문서를 열 수 없습니다."));
            err.setTextColor(0xFFEF5350);
            err.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            rootContainer.addView(err);
            return res;
        }

        this.cachedRawHtml = String.valueOf(res.get("html") != null ? res.get("html") : "");
        this.docKind = String.valueOf(res.get("kind") != null ? res.get("kind") : "word");
        this.editMode = String.valueOf(res.get("edit") != null ? res.get("edit") : "none");
        this.docNote = res.get("note") != null ? String.valueOf(res.get("note")) : null;

        parseUnitsAndBuildViews(cachedRawHtml);
        pushUndoSnapshot();
        return res;
    }

    private void pushUndoSnapshot() {
        Map<String, String> snap = new LinkedHashMap<>();
        for (UnitView u : units) {
            snap.put(u.key, u.currentText);
        }
        if (!undoStack.isEmpty() && undoStack.get(undoStack.size() - 1).equals(snap)) return;
        undoStack.add(snap);
        if (undoStack.size() > 35) undoStack.remove(0);
    }

    public boolean canUndo() {
        return undoStack.size() > 1;
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public void undo() {
        if (!canUndo()) return;
        Map<String, String> cur = undoStack.remove(undoStack.size() - 1);
        redoStack.add(cur);
        Map<String, String> prev = undoStack.get(undoStack.size() - 1);
        applySnapshot(prev);
    }

    public void redo() {
        if (!canRedo()) return;
        Map<String, String> next = redoStack.remove(redoStack.size() - 1);
        undoStack.add(next);
        applySnapshot(next);
    }

    private void applySnapshot(Map<String, String> snap) {
        suppressWatcher = true;
        try {
            for (UnitView u : units) {
                if (snap.containsKey(u.key)) {
                    u.currentText = snap.get(u.key);
                    if (u.view != null) {
                        u.view.setText(u.currentText);
                    }
                }
            }
        } finally {
            suppressWatcher = false;
        }
        if (listener != null) listener.onDocDirtyChanged(isDirty());
    }

    // ---------- Save Document ----------
    public Map<String, Object> saveDocument(String targetPath) {
        return writeDocument(targetPath, true);
    }
    public void markSaved() { for (UnitView unit : units) unit.initialText = unit.currentText; if (listener != null) listener.onDocDirtyChanged(false); }
    public void markDraftRestored() { for (UnitView unit : units) unit.initialText = "\u0000"; }
    public int printPageCount(int width, int height) {
        if (rootContainer.getWidth() <= 0) return 1;
        return Math.max(1, (int)Math.ceil((double)rootContainer.getHeight() * width / rootContainer.getWidth() / Math.max(1, height)));
    }
    public Bitmap printPage(int page, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, width), Math.max(1, height), Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap); canvas.drawColor(Color.WHITE);
        float scale = (float)width / Math.max(1, rootContainer.getWidth());
        canvas.translate(0, -page * height); canvas.scale(scale, scale); rootContainer.draw(canvas); return bitmap;
    }
    public Map<String, Object> writeDocument(String targetPath, boolean updateBaseline) {
        Map<String, Object> editsMap = new LinkedHashMap<>();
        StringBuilder fullTextBuilder = new StringBuilder();
        for (UnitView u : units) {
            if ("t".equals(u.key)) {
                fullTextBuilder.append(u.currentText);
            } else {
                if (fullTextBuilder.length() > 0) fullTextBuilder.append("\n");
                fullTextBuilder.append(u.currentText);
            }
            if (!u.initialText.equals(u.currentText) || "csv".equals(editMode)) {
                if (!u.key.isEmpty()) {
                    editsMap.put(u.key, u.currentText);
                }
            }
        }
        List<Object> edits = new ArrayList<>();
        for (Map.Entry<String, Object> edit : editsMap.entrySet()) edits.add(Util.D("k", edit.getKey(), "t", edit.getValue()));
        Map<String, Object> payload = Util.D("edits", edits, "text", fullTextBuilder.toString());
        if ("csv".equals(editMode)) {
            List<UnitView> cells = new ArrayList<>(); for (UnitView unit : units) if ("g".equals(unit.key)) cells.add(unit);
            List<Object> rows = new ArrayList<>(); int cursor = 0;
            Matcher row = Pattern.compile("<tr[^>]*>([\\s\\S]*?)</tr>", Pattern.CASE_INSENSITIVE).matcher(cachedRawHtml);
            while (row.find()) {
                Matcher cell = Pattern.compile("data-k=\"g\"").matcher(row.group(1)); List<Object> values = new ArrayList<>();
                while (cell.find() && cursor < cells.size()) values.add(cells.get(cursor++).currentText);
                if (!values.isEmpty()) rows.add(values);
            }
            payload.put("rows", rows);
        }
        String outputPath = targetPath;
        if ("conv".equals(editMode)) {
            outputPath = targetPath.substring(0, targetPath.length() - Util.extOf(targetPath).length()) + ".docx";
            List<Object> paragraphs = new ArrayList<>(); for (UnitView unit : units) paragraphs.add(Util.D("t", unit.currentText)); payload.put("paras", paragraphs);
        }
        Map<String, Object> saved = DocEdit.save(currentPath, outputPath, Util.jsonStringify(payload));
        if (Boolean.TRUE.equals(saved.get("ok")) && updateBaseline) {
            for (UnitView u : units) {
                u.initialText = u.currentText;
            }
            if (listener != null) listener.onDocDirtyChanged(false);
        }
        return saved;
    }

    public String getPlainTextAll() {
        StringBuilder sb = new StringBuilder();
        for (UnitView u : units) {
            if (u.currentText != null && !u.currentText.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(u.currentText);
            }
        }
        return sb.toString();
    }

    // ---------- Find & Replace ----------
    public int performSearch(String query) {
        this.searchQuery = query != null ? query.trim() : "";
        this.searchMatches.clear();
        this.activeSearchMatch = -1;
        if (!this.searchQuery.isEmpty()) {
            String qLow = this.searchQuery.toLowerCase(Locale.ROOT);
            for (UnitView u : units) {
                if (u.currentText != null && u.currentText.toLowerCase(Locale.ROOT).contains(qLow)) {
                    searchMatches.add(u);
                }
            }
            if (!searchMatches.isEmpty()) {
                activeSearchMatch = 0;
            }
        }
        applySearchHighlights();
        scrollToActiveMatch();
        if (listener != null) listener.onSearchHitsChanged(activeSearchMatch, searchMatches.size());
        return searchMatches.size();
    }

    public void stepSearch(int delta) {
        if (searchMatches.isEmpty()) return;
        activeSearchMatch = (activeSearchMatch + delta + searchMatches.size()) % searchMatches.size();
        applySearchHighlights();
        scrollToActiveMatch();
        if (listener != null) listener.onSearchHitsChanged(activeSearchMatch, searchMatches.size());
    }

    public int replaceSearch(String findStr, String replaceStr, boolean all) {
        if (findStr == null || findStr.isEmpty()) return 0;
        String rep = replaceStr != null ? replaceStr : "";
        int count = 0;
        if (all) {
            for (UnitView u : units) {
                if (u.currentText != null && u.currentText.contains(findStr)) {
                    u.currentText = u.currentText.replace(findStr, rep);
                    count++;
                }
            }
        } else if (activeSearchMatch >= 0 && activeSearchMatch < searchMatches.size()) {
            UnitView u = searchMatches.get(activeSearchMatch);
            int idx = u.currentText.toLowerCase(Locale.ROOT).indexOf(findStr.toLowerCase(Locale.ROOT));
            if (idx >= 0) {
                u.currentText = u.currentText.substring(0, idx) + rep + u.currentText.substring(idx + findStr.length());
                count = 1;
            }
        }
        if (count > 0) {
            pushUndoSnapshot();
            rebuildNativeViews();
            performSearch(findStr);
            if (listener != null) listener.onDocDirtyChanged(isDirty());
        }
        return count;
    }

    private void applySearchHighlights() {
        String qLow = searchQuery.toLowerCase(Locale.ROOT);
        for (int i = 0; i < units.size(); i++) {
            UnitView u = units.get(i);
            if (u.view == null || u.view instanceof EditText) continue;
            if (qLow.isEmpty() || u.currentText == null || !u.currentText.toLowerCase(Locale.ROOT).contains(qLow)) {
                u.view.setText(u.currentText);
                continue;
            }
            SpannableStringBuilder ssb = new SpannableStringBuilder(u.currentText);
            String textLow = u.currentText.toLowerCase(Locale.ROOT);
            boolean isPrimaryUnit = (activeSearchMatch >= 0 && activeSearchMatch < searchMatches.size() && searchMatches.get(activeSearchMatch) == u);
            int pos = 0;
            while ((pos = textLow.indexOf(qLow, pos)) >= 0) {
                int end = pos + qLow.length();
                ssb.setSpan(new BackgroundColorSpan(isPrimaryUnit ? 0xFFFF9800 : 0x88FFEB3B), pos, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new ForegroundColorSpan(Color.BLACK), pos, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                pos = end;
            }
            u.view.setText(ssb);
        }
    }

    private void scrollToActiveMatch() {
        if (activeSearchMatch < 0 || activeSearchMatch >= searchMatches.size()) return;
        final UnitView u = searchMatches.get(activeSearchMatch);
        if (u.view != null) {
            vScroll.post(new Runnable() {
                @Override
                public void run() {
                    int[] loc = new int[2];
                    u.view.getLocationOnScreen(loc);
                    int[] scrollLoc = new int[2];
                    vScroll.getLocationOnScreen(scrollLoc);
                    int targetY = vScroll.getScrollY() + (loc[1] - scrollLoc[1]) - dp(100);
                    vScroll.smoothScrollTo(0, Math.max(0, targetY));
                }
            });
        }
    }

    // ---------- Parser & Native View Builder ----------
    private void parseUnitsAndBuildViews(String html) {
        units.clear();
        rebuildNativeViewsFromHtml(html, true);
    }

    private void rebuildNativeViews() {
        final int scroll = vScroll.getScrollY();
        rebuildNativeViewsFromHtml(cachedRawHtml, false);
        vScroll.post(() -> vScroll.scrollTo(0, scroll));
        if (!searchQuery.isEmpty()) {
            applySearchHighlights();
        }
    }

    private void rebuildNativeViewsFromHtml(String html, boolean createUnits) {
        rootContainer.removeAllViews();
        sheetContainers.clear();
        sheetNames.clear();

        int cardBg = isDarkTheme ? 0xFF1A1D24 : 0xFFFFFFFF;
        int cardBorder = isDarkTheme ? 0xFF2C313D : 0xFFDCE1EB;
        int textPrimary = isDarkTheme ? 0xFFF1F3F9 : 0xFF15181E;
        int textSecondary = isDarkTheme ? 0xFF9AA3B5 : 0xFF5D6575;

        // Optional Note Banner
        if (docNote != null && !docNote.isEmpty()) {
            TextView banner = new TextView(getContext());
            banner.setText("안내: " + docNote);
            banner.setTextColor(isDarkTheme ? 0xFF8AB4F8 : 0xFF0366D6);
            banner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            banner.setPadding(dp(14), dp(10), dp(14), dp(10));
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(isDarkTheme ? 0x220381FE : 0x160381FE);
            gd.setCornerRadius(dp(12));
            gd.setStroke(dp(1), isDarkTheme ? 0x440381FE : 0x330381FE);
            banner.setBackground(gd);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(12);
            rootContainer.addView(banner, lp);
        }

        int[] unitCursor = new int[]{0};

        // Case 1: Plain text / JSON / Markdown / Code (<pre class="txt" data-k="t">...</pre>)
        String ext = Util.extOf(currentPath);
        if ("text".equals(docKind) || html.startsWith("<pre")) {
            String rawText = unescapeHtml(stripTagsPreserveNewlines(html));
            UnitView uv;
            if (createUnits || units.isEmpty()) {
                uv = new UnitView("t", rawText, false, false);
                units.add(uv);
            } else {
                uv = units.get(0);
            }

            LinearLayout pageCard = createCardContainer(cardBg, cardBorder);
            if (!isEditing && (".md".equals(ext) || ".markdown".equals(ext))) {
                buildMarkdownNativeView(pageCard, uv.currentText, textPrimary, textSecondary);
            } else {
                TextView tv = createBoundTextOrEdit(uv, textPrimary, true);
                tv.setTypeface(Typeface.MONOSPACE);
                pageCard.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            rootContainer.addView(pageCard);
            return;
        }

        // Case 2: Spreadsheet (.xlsx, .csv, .tsv)
        if ("sheet".equals(docKind)) {
            buildSpreadsheetNativeViews(html, createUnits, unitCursor, cardBg, cardBorder, textPrimary, textSecondary);
            return;
        }

        // Case 3: Slides (.pptx)
        if ("slides".equals(docKind)) {
            buildSlidesNativeViews(html, createUnits, unitCursor, cardBg, cardBorder, textPrimary, textSecondary);
            return;
        }

        // Case 4: Word / HWPX / HWP / ODT / RTF Document
        buildWordNativeViews(html, createUnits, unitCursor, cardBg, cardBorder, textPrimary, textSecondary);
    }

    private LinearLayout createCardContainer(int bgColor, int borderColor) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(18));
        gd.setStroke(dp(1), borderColor);
        card.setBackground(gd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        card.setLayoutParams(lp);
        return card;
    }

    private void buildMarkdownNativeView(LinearLayout container, String md, int textPrimary, int textSecondary) {
        String[] lines = md.replace("\r\n", "\n").split("\n");
        boolean inCode = false;
        StringBuilder codeBuf = new StringBuilder();
        for (String line : lines) {
            if (line.trim().startsWith("```")) {
                if (inCode) {
                    TextView codeView = new TextView(getContext());
                    codeView.setText(codeBuf.toString());
                    codeView.setTypeface(Typeface.MONOSPACE);
                    codeView.setTextColor(isDarkTheme ? 0xFFE6EDF3 : 0xFF24292F);
                    codeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * zoomScale);
                    codeView.setPadding(dp(12), dp(10), dp(12), dp(10));
                    GradientDrawable gd = new GradientDrawable();
                    gd.setColor(isDarkTheme ? 0xFF101217 : 0xFFF3F5F8);
                    gd.setCornerRadius(dp(10));
                    codeView.setBackground(gd);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.bottomMargin = dp(8);
                    container.addView(codeView, lp);
                    codeBuf.setLength(0);
                    inCode = false;
                } else {
                    inCode = true;
                }
                continue;
            }
            if (inCode) {
                if (codeBuf.length() > 0) codeBuf.append('\n');
                codeBuf.append(line);
                continue;
            }
            if (line.trim().isEmpty()) {
                View spacer = new View(getContext());
                container.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));
                continue;
            }
            TextView tv = new TextView(getContext());
            tv.setTextIsSelectable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            if (line.startsWith("# ")) {
                tv.setText(line.substring(2).trim());
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f * zoomScale);
                tv.setTypeface(Typeface.DEFAULT_BOLD);
                tv.setTextColor(textPrimary);
            } else if (line.startsWith("## ")) {
                tv.setText(line.substring(3).trim());
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * zoomScale);
                tv.setTypeface(Typeface.DEFAULT_BOLD);
                tv.setTextColor(textPrimary);
            } else if (line.startsWith("### ")) {
                tv.setText(line.substring(4).trim());
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f * zoomScale);
                tv.setTypeface(Typeface.DEFAULT_BOLD);
                tv.setTextColor(textPrimary);
            } else if (line.trim().startsWith("- ") || line.trim().startsWith("* ")) {
                tv.setText("•  " + line.trim().substring(2));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f * zoomScale);
                tv.setTextColor(textPrimary);
                tv.setPadding(dp(8), 0, 0, 0);
            } else if (line.trim().startsWith("> ")) {
                tv.setText(line.trim().substring(2));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * zoomScale);
                tv.setTextColor(textSecondary);
                tv.setPadding(dp(12), dp(4), dp(8), dp(4));
            } else {
                tv.setText(line);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f * zoomScale);
                tv.setTextColor(textPrimary);
            }
            container.addView(tv, lp);
        }
    }

    private void buildSpreadsheetNativeViews(String html, boolean createUnits, int[] unitCursor,
                                             int cardBg, int cardBorder, int textPrimary, int textSecondary) {
        // Extract sheets (<div class="sht"...> or direct <table class="grid">)
        Matcher tabMatcher = Pattern.compile("<button[^>]*data-s=\"(\\d+)\"[^>]*>(.*?)</button>", Pattern.DOTALL).matcher(html);
        while (tabMatcher.find()) {
            sheetNames.add(unescapeHtml(stripTags(tabMatcher.group(2))));
        }

        List<String> tableBlocks = new ArrayList<>();
        Matcher tblMatcher = Pattern.compile("<table[^>]*>(.*?)</table>", Pattern.DOTALL).matcher(html);
        while (tblMatcher.find()) {
            tableBlocks.add(tblMatcher.group(1));
        }

        if (sheetNames.size() > 1) {
            HorizontalScrollView tabScroll = new HorizontalScrollView(getContext());
            tabScroll.setHorizontalScrollBarEnabled(false);
            sheetTabsBar = new LinearLayout(getContext());
            sheetTabsBar.setOrientation(LinearLayout.HORIZONTAL);
            sheetTabsBar.setPadding(0, 0, 0, dp(10));
            for (int i = 0; i < sheetNames.size(); i++) {
                final int idx = i;
                TextView chip = new TextView(getContext());
                chip.setText(sheetNames.get(i));
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                chip.setTypeface(Typeface.DEFAULT_BOLD);
                chip.setPadding(dp(14), dp(8), dp(14), dp(8));
                boolean active = (i == activeSheetIdx);
                GradientDrawable gd = new GradientDrawable();
                gd.setCornerRadius(dp(18));
                gd.setColor(active ? 0xFF0381FE : (isDarkTheme ? 0xFF232730 : 0xFFE6EAF2));
                chip.setBackground(gd);
                chip.setTextColor(active ? Color.WHITE : textPrimary);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.rightMargin = dp(8);
                chip.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        activeSheetIdx = idx;
                        rebuildNativeViews();
                        if (listener != null && idx < sheetNames.size()) {
                            listener.onSheetSelected(idx, sheetNames.get(idx));
                        }
                    }
                });
                sheetTabsBar.addView(chip, clp);
            }
            tabScroll.addView(sheetTabsBar);
            rootContainer.addView(tabScroll);
        }

        for (int s = 0; s < tableBlocks.size(); s++) {
            View tableView = buildTableFromHtml(tableBlocks.get(s), true, createUnits, unitCursor, textPrimary, textSecondary);
            tableView.setVisibility(s == activeSheetIdx ? View.VISIBLE : View.GONE);
            sheetContainers.add(tableView);
            rootContainer.addView(tableView);
        }
    }

    private void buildSlidesNativeViews(String html, boolean createUnits, int[] unitCursor,
                                        int cardBg, int cardBorder, int textPrimary, int textSecondary) {
        String[] slides = html.split("<div class=\"slide\"");
        for (int i = 1; i < slides.length; i++) {
            String chunk = slides[i];
            int closeIdx = chunk.indexOf(">");
            if (closeIdx >= 0) chunk = chunk.substring(closeIdx + 1);

            LinearLayout slideCard = createCardContainer(cardBg, cardBorder);
            TextView badge = new TextView(getContext());
            badge.setText("슬라이드 " + i);
            badge.setTextColor(0xFF0381FE);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setPadding(0, 0, 0, dp(8));
            slideCard.addView(badge);

            populateRichHtmlBlocks(slideCard, chunk, createUnits, unitCursor, textPrimary, textSecondary);
            rootContainer.addView(slideCard);
        }
    }

    private void buildWordNativeViews(String html, boolean createUnits, int[] unitCursor,
                                      int cardBg, int cardBorder, int textPrimary, int textSecondary) {
        LinearLayout pageCard = createCardContainer(cardBg, cardBorder);
        populateRichHtmlBlocks(pageCard, html, createUnits, unitCursor, textPrimary, textSecondary);
        rootContainer.addView(pageCard);
    }

    private void populateRichHtmlBlocks(LinearLayout parent, String htmlChunk, boolean createUnits, int[] unitCursor,
                                        int textPrimary, int textSecondary) {
        Pattern tokenPattern = Pattern.compile(
                "(<table[^>]*>.*?</table>)|(<img[^>]*src=\"data:image/[^\"]+;base64,([^\"]+)\"[^>]*>)|(<(h1|h2|h3|p|div)([^>]*)>(.*?)</\\5>)",
                Pattern.DOTALL
        );
        Matcher m = tokenPattern.matcher(htmlChunk);
        boolean foundAny = false;
        while (m.find()) {
            foundAny = true;
            if (m.group(1) != null) {
                // Table block
                View tbl = buildTableFromHtml(m.group(1), false, createUnits, unitCursor, textPrimary, textSecondary);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(8);
                lp.bottomMargin = dp(10);
                parent.addView(tbl, lp);
            } else if (m.group(2) != null) {
                // Embedded Base64 Image
                String b64 = m.group(3);
                try {
                    byte[] bytes = Util.b64Decode(b64);
                    Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bmp != null) {
                        ImageView iv = new ImageView(getContext());
                        iv.setAdjustViewBounds(true);
                        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        iv.setImageBitmap(bmp);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        lp.topMargin = dp(8);
                        lp.bottomMargin = dp(8);
                        parent.addView(iv, lp);
                    }
                } catch (Exception ignored) {}
            } else if (m.group(4) != null) {
                String tag = m.group(5);
                String attrs = m.group(6) != null ? m.group(6) : "";
                String inner = m.group(7) != null ? m.group(7) : "";
                if (inner.contains("<p") || inner.contains("<table") || inner.contains("<h1") || inner.contains("<h2")) {
                    populateRichHtmlBlocks(parent, inner, createUnits, unitCursor, textPrimary, textSecondary);
                    continue;
                }
                String dataK = extractAttr(attrs, "data-k");
                String plain = unescapeHtml(stripTagsPreserveNewlines(inner)).trim();
                if (plain.isEmpty() && dataK.isEmpty()) continue;

                boolean isHeading = "h1".equalsIgnoreCase(tag) || "h2".equalsIgnoreCase(tag) || "h3".equalsIgnoreCase(tag);
                UnitView uv = obtainOrCreateUnit(dataK, plain, false, isHeading, createUnits, unitCursor);
                TextView tv = createBoundTextOrEdit(uv, textPrimary, false);
                if ("h1".equalsIgnoreCase(tag)) {
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f * zoomScale);
                    tv.setTypeface(Typeface.DEFAULT_BOLD);
                } else if ("h2".equalsIgnoreCase(tag)) {
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17.5f * zoomScale);
                    tv.setTypeface(Typeface.DEFAULT_BOLD);
                } else if ("h3".equalsIgnoreCase(tag)) {
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f * zoomScale);
                    tv.setTypeface(Typeface.DEFAULT_BOLD);
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(isHeading ? 10 : 6);
                parent.addView(tv, lp);
            }
        }

        if (!foundAny) {
            String fallback = unescapeHtml(stripTagsPreserveNewlines(htmlChunk)).trim();
            if (!fallback.isEmpty()) {
                UnitView uv = obtainOrCreateUnit("t", fallback, false, false, createUnits, unitCursor);
                TextView tv = createBoundTextOrEdit(uv, textPrimary, true);
                parent.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
    }

    private View buildTableFromHtml(String tableHtml, boolean isSpreadsheet, boolean createUnits, int[] unitCursor,
                                    int textPrimary, int textSecondary) {
        HorizontalScrollView hScroll = new HorizontalScrollView(getContext());
        hScroll.setFillViewport(true);

        TableLayout table = new TableLayout(getContext());
        table.setStretchAllColumns(true);
        int borderCol = isDarkTheme ? 0xFF2E3442 : 0xFFD0D7E3;
        int headerBg = isDarkTheme ? 0xFF252A35 : 0xFFEEF2F8;
        int cellBg = isDarkTheme ? 0xFF1A1D24 : 0xFFFFFFFF;

        Matcher rowMatcher = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL).matcher(tableHtml);
        int rIdx = 0;
        while (rowMatcher.find()) {
            String rowInner = rowMatcher.group(1);
            TableRow tr = new TableRow(getContext());
            Matcher cellMatcher = Pattern.compile("<(th|td)([^>]*)>(.*?)</\\1>", Pattern.DOTALL).matcher(rowInner);
            int cIdx = 0;
            while (cellMatcher.find()) {
                String tag = cellMatcher.group(1);
                String attrs = cellMatcher.group(2) != null ? cellMatcher.group(2) : "";
                String inner = cellMatcher.group(3) != null ? cellMatcher.group(3) : "";
                boolean isHeader = "th".equalsIgnoreCase(tag) || (rIdx == 0 && !isSpreadsheet);
                String dataK = extractAttr(attrs, "data-k");
                String text = unescapeHtml(stripTagsPreserveNewlines(inner));

                TextView cellView;
                if (isHeader && dataK.isEmpty()) {
                    cellView = new TextView(getContext());
                    cellView.setText(text);
                    cellView.setTextColor(textSecondary);
                    cellView.setTypeface(Typeface.DEFAULT_BOLD);
                    cellView.setGravity(Gravity.CENTER);
                    cellView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * zoomScale);
                } else {
                    UnitView uv = obtainOrCreateUnit(dataK, text, true, false, createUnits, unitCursor);
                    cellView = createBoundTextOrEdit(uv, textPrimary, false);
                    cellView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f * zoomScale);
                    if (isHeader) cellView.setTypeface(Typeface.DEFAULT_BOLD);
                }
                cellView.setPadding(dp(10), dp(7), dp(10), dp(7));
                cellView.setMinimumWidth(dp(isSpreadsheet && cIdx == 0 && dataK.isEmpty() ? 38 : 84));

                GradientDrawable cellDrawable = new GradientDrawable();
                cellDrawable.setColor(isHeader ? headerBg : cellBg);
                cellDrawable.setStroke(1, borderCol);
                cellView.setBackground(cellDrawable);

                TableRow.LayoutParams tlp = new TableRow.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
                tr.addView(cellView, tlp);
                cIdx++;
            }
            table.addView(tr);
            rIdx++;
        }
        hScroll.addView(table);
        return hScroll;
    }

    private UnitView obtainOrCreateUnit(String dataK, String text, boolean isCell, boolean isHeading, boolean createUnits, int[] cursor) {
        if (createUnits || cursor[0] >= units.size()) {
            UnitView uv = new UnitView(dataK != null ? dataK : "", text, isCell, isHeading);
            units.add(uv);
            cursor[0] = units.size();
            return uv;
        }
        UnitView existing = units.get(cursor[0]++);
        return existing;
    }

    private TextView createBoundTextOrEdit(final UnitView uv, int textColor, boolean multilineCode) {
        if (isEditing && (!uv.key.isEmpty() || "text".equals(editMode) || "conv".equals(editMode))) {
            EditText et = new EditText(getContext());
            et.setText(uv.currentText);
            et.setTextColor(textColor);
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, (multilineCode ? 13.5f : 14.5f) * zoomScale);
            et.setPadding(dp(8), dp(6), dp(8), dp(6));
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(isDarkTheme ? 0xFF12151C : 0xFFF7F9FC);
            gd.setCornerRadius(dp(6));
            gd.setStroke(dp(1), 0x660381FE);
            et.setBackground(gd);
            et.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    if (suppressWatcher) return;
                    uv.currentText = s.toString();
                    redoStack.clear();
                    pushUndoSnapshot();
                    if (listener != null) listener.onDocDirtyChanged(isDirty());
                }
            });
            uv.view = et;
            return et;
        } else {
            TextView tv = new TextView(getContext());
            tv.setText(uv.currentText);
            tv.setTextColor(textColor);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (multilineCode ? 13.5f : 14.5f) * zoomScale);
            tv.setLineSpacing(dp(3), 1.12f);
            tv.setTextIsSelectable(true);
            uv.view = tv;
            return tv;
        }
    }

    private static String extractAttr(String attrs, String name) {
        if (attrs == null || attrs.isEmpty()) return "";
        Matcher m = Pattern.compile(name + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : "";
    }

    private static String stripTags(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "");
    }

    private static String stripTagsPreserveNewlines(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?i)<br\\s*/?>", "\n");
        return s.replaceAll("<[^>]+>", "");
    }

    private static String unescapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&");
    }
}
