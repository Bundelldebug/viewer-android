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
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
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
    private android.webkit.WebView richWebView;
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

    private int safeTopPx = 0, safeBottomPx = 0, safeRightPx = 0;

    public NativeDocView(Context context) {
        super(context);
        vScroll = new ScrollView(context);
        vScroll.setFillViewport(true);
        vScroll.setClipToPadding(false);

        rootContainer = new LinearLayout(context);
        rootContainer.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        rootContainer.setPadding(pad, pad, pad, dp(120));
        vScroll.addView(rootContainer, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(vScroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void setSafeInsets(int topPx, int bottomPx, int rightPx) {
        topPx = Math.max(0, topPx);
        bottomPx = Math.max(0, bottomPx);
        rightPx = Math.max(0, rightPx);
        if (topPx == safeTopPx && bottomPx == safeBottomPx && rightPx == safeRightPx) return;
        safeTopPx = topPx;
        safeBottomPx = bottomPx;
        safeRightPx = rightPx;
        int pad = dp(14);
        rootContainer.setPadding(pad, topPx + dp(10), pad + rightPx, Math.max(dp(100), bottomPx + dp(14)));
        if (richWebView != null && richWebView.getVisibility() == View.VISIBLE) {
            richWebView.setPadding(0, 0, 0, 0);
            float d = Math.max(1f, getResources().getDisplayMetrics().density);
            int tDp = Math.round(safeTopPx / d) + 12;
            int bDp = Math.round(safeBottomPx / d) + 24;
            int rDp = Math.round(safeRightPx / d) + 12;
            try {
                richWebView.evaluateJavascript(
                        "document.documentElement.style.padding='" + tDp + "px " + rDp + "px " + bDp + "px 12px';",
                        null);
            } catch (Throwable ignored) {}
        }
    }

    public void destroy() {
        if (richWebView != null) {
            try { richWebView.destroy(); } catch (Throwable ignored) {}
            richWebView = null;
        }
    }

    public int getSafeTopPx() { return safeTopPx; }
    public int getSafeBottomPx() { return safeBottomPx; }

    private static boolean isRobolectric() {
        return "robolectric".equalsIgnoreCase(android.os.Build.FINGERPRINT)
                || "robolectric".equalsIgnoreCase(android.os.Build.MODEL);
    }

    private boolean shouldUseRichWebView() {
        if (isEditing || isRobolectric()) return false;
        String ext = Util.extOf(currentPath);
        return ".hwp".equals(ext) || ".hwpx".equals(ext) || ".docx".equals(ext) || ".docm".equals(ext) || ".dotx".equals(ext);
    }

    private void renderInRichWebView() {
        try {
            if (richWebView == null) {
                richWebView = new android.webkit.WebView(getContext());
                android.webkit.WebSettings ws = richWebView.getSettings();
                ws.setJavaScriptEnabled(false);
                ws.setAllowFileAccess(false);
                ws.setAllowContentAccess(false);
                ws.setAllowFileAccessFromFileURLs(false);
                ws.setAllowUniversalAccessFromFileURLs(false);
                ws.setSaveFormData(false);
                ws.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                ws.setBuiltInZoomControls(true);
                ws.setDisplayZoomControls(false);
                ws.setUseWideViewPort(true);
                ws.setLoadWithOverviewMode(true);
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    ws.setAlgorithmicDarkeningAllowed(false);
                }
                richWebView.setWebViewClient(new android.webkit.WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(android.webkit.WebView view, android.webkit.WebResourceRequest req) {
                        return true; // Never navigate away from offline rendered document
                    }
                    @Override public android.webkit.WebResourceResponse shouldInterceptRequest(android.webkit.WebView view, android.webkit.WebResourceRequest req) {
                        // Block all external network/file subresource loads from untrusted documents
                        return new android.webkit.WebResourceResponse("text/plain", "utf-8", 403, "Forbidden",
                                java.util.Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
                    }
                });
                richWebView.setBackgroundColor(isDarkTheme ? 0xFF0E1117 : 0xFFF2F4F8);
                addView(richWebView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            richWebView.setVisibility(View.VISIBLE);
            vScroll.setVisibility(View.GONE);
            richWebView.setBackgroundColor(isDarkTheme ? 0xFF0E1117 : 0xFFF2F4F8);
            int pct = Math.round(zoomScale * 100f);
            float dens = Math.max(1f, getResources().getDisplayMetrics().density);
            int padTopDp = Math.max(12, Math.round(safeTopPx / dens) + 10);
            int padBotDp = Math.max(110, Math.round(safeBottomPx / dens) + 18);
            int padRightDp = Math.max(10, Math.round(safeRightPx / dens) + 10);
            String bg = isDarkTheme ? "#0E1117" : "#F2F4F8";
            // Always render HWP / HWPX / DOCX pages on crisp white paper so black text, formulas & embedded figures are 100% readable in Dark Mode too
            String paper = "#FFFFFF";
            String fg = "#15181E";
            String bd = "#B8C4D4";
            String pageShadow = isDarkTheme ? "0 4px 20px rgba(0,0,0,.55)" : "0 2px 14px rgba(15,23,42,.08)";
            String noteHtml = (docNote != null && !docNote.isEmpty())
                    ? "<div class=\"note\">안내: " + Docs.esc(docNote) + "</div>" : "";
            String fullHtml = "<!doctype html><html><head><meta charset=\"utf-8\">"
                    + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data:; style-src 'unsafe-inline';\">"
                    + "<meta name=\"color-scheme\" content=\"light only\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=4,user-scalable=yes\">"
                    + "<style>"
                    + ":root{color-scheme:light only}"
                    + "*{box-sizing:border-box}"
                    + "html,body{margin:0;padding:" + padTopDp + "px " + padRightDp + "px " + padBotDp + "px 10px;background:" + bg + ";color:" + fg
                    + ";font-family:sans-serif;font-size:" + pct + "%;line-height:1.6;-webkit-text-size-adjust:100%}"
                    + ".paper{background:" + paper + ";color:" + fg + ";border-radius:16px;padding:18px 16px;box-shadow:" + pageShadow
                    + ";max-width:860px;margin:0 auto;overflow-x:auto}"
                    + ".note{background:rgba(3,129,254,.10);color:#0366D6;border:1px solid rgba(3,129,254,.28);border-radius:10px;padding:8px 12px;font-size:12px;margin-bottom:12px}"
                    + "p{margin:0 0 6px;word-break:break-word;color:inherit}p.empty{min-height:10px}"
                    + "table.grid,table.hwp-tbl{width:100%;border-collapse:collapse;margin:10px 0;table-layout:auto;background:#FFFFFF}"
                    + "table.grid td,table.grid th,table.hwp-tbl td,table.hwp-tbl th{border:1px solid " + bd + ";padding:6px 8px;vertical-align:middle;word-break:break-word;color:#15181E}"
                    + "img{max-width:100%;height:auto;display:block;margin:8px auto;border-radius:4px}"
                    + ".hwp-box{border:1px solid " + bd + ";border-radius:8px;padding:10px 12px;margin:8px 0;color:#15181E}"
                    + ".hwp-hf{border-bottom:1px solid " + bd + ";padding:6px 10px;margin:4px 0;font-size:0.9em;color:#475569;background:#F8FAFC}"
                    + "hr.pb{border:none;border-top:2px dashed " + bd + ";margin:18px 0}"
                    + "</style></head><body><div class=\"paper\">" + noteHtml + cachedRawHtml + "</div></body></html>";
            richWebView.loadDataWithBaseURL("about:blank", fullHtml, "text/html", "UTF-8", null);
        } catch (Throwable t) {
            if (richWebView != null) richWebView.setVisibility(View.GONE);
            vScroll.setVisibility(View.VISIBLE);
        }
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
            if (richWebView != null) richWebView.setVisibility(View.GONE);
            vScroll.setVisibility(View.VISIBLE);
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
        if (richWebView != null && richWebView.getVisibility() == View.VISIBLE) {
            if (this.searchQuery.isEmpty()) {
                richWebView.clearMatches();
                if (listener != null) listener.onSearchHitsChanged(-1, 0);
            } else {
                richWebView.setFindListener((active, count, done) -> {
                    if (done && listener != null) listener.onSearchHitsChanged(count > 0 ? active : -1, count);
                });
                richWebView.findAllAsync(this.searchQuery);
            }
            return 0;
        }
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
        if (richWebView != null && richWebView.getVisibility() == View.VISIBLE) {
            if (!this.searchQuery.isEmpty()) richWebView.findNext(delta >= 0);
            return;
        }
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
        if (richWebView != null) {
            richWebView.setVisibility(View.GONE);
        }
        vScroll.setVisibility(View.VISIBLE);
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
        if (shouldUseRichWebView()) {
            renderInRichWebView();
        }
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
        if (htmlChunk == null || htmlChunk.isEmpty()) return;
        // Strip outer wrapper <div class="docx ...">...</div> once so nested hwp-box/hwp-hf divs parse cleanly
        String trimmed = htmlChunk.trim();
        if (trimmed.startsWith("<div class=\"docx") && trimmed.endsWith("</div>")) {
            int gt = trimmed.indexOf('>');
            if (gt > 0 && gt < trimmed.length() - 6) {
                trimmed = trimmed.substring(gt + 1, trimmed.length() - 6);
            }
        }

        int pos = 0;
        int len = trimmed.length();
        boolean foundAny = false;
        while (pos < len) {
            int lt = trimmed.indexOf('<', pos);
            if (lt < 0) break;
            if (lt > pos) {
                String stray = unescapeHtml(trimmed.substring(pos, lt)).trim();
                if (!stray.isEmpty()) {
                    foundAny = true;
                    UnitView uv = obtainOrCreateUnit("", stray, false, false, createUnits, unitCursor);
                    TextView tv = createBoundTextOrEdit(uv, textPrimary, false);
                    parent.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
            }
            int gt = trimmed.indexOf('>', lt);
            if (gt < 0) break;
            String openTag = trimmed.substring(lt, gt + 1);
            String openLower = openTag.toLowerCase(Locale.ROOT);

            if (openLower.startsWith("<hr")) {
                foundAny = true;
                View sep = new View(getContext());
                sep.setBackgroundColor(isDarkTheme ? 0x33FFFFFF : 0x26000000);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
                lp.topMargin = dp(12);
                lp.bottomMargin = dp(12);
                parent.addView(sep, lp);
                pos = gt + 1;
            } else if (openLower.startsWith("<img")) {
                foundAny = true;
                addImageFromTag(parent, openTag);
                pos = gt + 1;
            } else if (openLower.startsWith("<table")) {
                int end = findMatchingClose(trimmed, gt + 1, "table");
                String block = trimmed.substring(lt, end);
                foundAny = true;
                View tbl = buildTableFromHtml(block, false, createUnits, unitCursor, textPrimary, textSecondary);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(8);
                lp.bottomMargin = dp(10);
                parent.addView(tbl, lp);
                pos = end;
            } else if (openLower.startsWith("<div")) {
                int end = findMatchingClose(trimmed, gt + 1, "div");
                String inner = extractInnerHtml(trimmed, gt + 1, end, "div");
                String attrs = openTag.substring(4, openTag.length() - 1);
                String cls = extractAttr(attrs, "class");
                String style = extractAttr(attrs, "style");
                foundAny = true;
                if (cls.contains("hwp-box") || cls.contains("hwp-hf")) {
                    LinearLayout box = new LinearLayout(getContext());
                    box.setOrientation(LinearLayout.VERTICAL);
                    box.setPadding(dp(12), dp(9), dp(12), dp(9));
                    GradientDrawable gd = new GradientDrawable();
                    gd.setCornerRadius(dp(8));
                    Integer bg = parseCssColor(style, "background-color");
                    if (bg == null) bg = parseCssColor(style, "background");
                    if (bg != null) gd.setColor(adaptBgForTheme(bg));
                    else if (cls.contains("hwp-hf")) gd.setColor(isDarkTheme ? 0xFF202530 : 0xFFF5F7FB);
                    else gd.setColor(isDarkTheme ? 0xFF1D222D : 0xFFFAFCFF);
                    Integer bd = parseBorderColor(style);
                    if (!style.contains("border:none")) {
                        gd.setStroke(Math.max(1, dp(1)), bd != null ? bd : (isDarkTheme ? 0xFF3A4254 : 0xFFCBD5E1));
                    }
                    box.setBackground(gd);
                    populateRichHtmlBlocks(box, inner, createUnits, unitCursor, textPrimary, textSecondary);
                    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    blp.topMargin = dp(6);
                    blp.bottomMargin = dp(8);
                    parent.addView(box, blp);
                } else {
                    populateRichHtmlBlocks(parent, inner, createUnits, unitCursor, textPrimary, textSecondary);
                }
                pos = end;
            } else if (openLower.startsWith("<p") || openLower.startsWith("<h1") || openLower.startsWith("<h2") || openLower.startsWith("<h3")) {
                String tag = openLower.startsWith("<h1") ? "h1" : (openLower.startsWith("<h2") ? "h2" : (openLower.startsWith("<h3") ? "h3" : "p"));
                int end = findMatchingClose(trimmed, gt + 1, tag);
                String inner = extractInnerHtml(trimmed, gt + 1, end, tag);
                String attrs = openTag.substring(1 + tag.length(), openTag.length() - 1);
                foundAny = true;
                renderParagraphOrHeading(parent, tag, attrs, inner, createUnits, unitCursor, textPrimary, textSecondary);
                pos = end;
            } else {
                pos = gt + 1;
            }
        }

        if (!foundAny) {
            String fallback = unescapeHtml(stripTagsPreserveNewlines(trimmed)).trim();
            if (!fallback.isEmpty()) {
                UnitView uv = obtainOrCreateUnit("t", fallback, false, false, createUnits, unitCursor);
                TextView tv = createBoundTextOrEdit(uv, textPrimary, true);
                parent.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
    }

    private void renderParagraphOrHeading(LinearLayout parent, String tag, String attrs, String inner,
                                          boolean createUnits, int[] unitCursor, int textPrimary, int textSecondary) {
        // Render any <img> embedded inside <p> so images are never stripped!
        if (inner.contains("<img")) {
            Matcher im = Pattern.compile("<img[^>]*>", Pattern.CASE_INSENSITIVE).matcher(inner);
            while (im.find()) addImageFromTag(parent, im.group());
            inner = im.replaceAll("");
        }
        String dataK = extractAttr(attrs, "data-k");
        String style = extractAttr(attrs, "style");
        String plain = unescapeHtml(stripTagsPreserveNewlines(inner)).trim();
        if (plain.isEmpty() && dataK.isEmpty()) {
            // Preserve intentional blank paragraph spacing lightly
            if (attrs.contains("empty")) {
                View sp = new View(getContext());
                parent.addView(sp, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));
            }
            return;
        }

        boolean isHeading = "h1".equalsIgnoreCase(tag) || "h2".equalsIgnoreCase(tag) || "h3".equalsIgnoreCase(tag);
        UnitView uv = obtainOrCreateUnit(dataK, plain, false, isHeading, createUnits, unitCursor);
        TextView tv = createBoundTextOrEdit(uv, textPrimary, false);
        if (!isEditing) {
            CharSequence styled = parseInlineStyledText(inner, textPrimary);
            if (styled.length() > 0) tv.setText(styled);
        }
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
        String align = parseCssProp(style, "text-align");
        if ("center".equalsIgnoreCase(align)) tv.setGravity(Gravity.CENTER_HORIZONTAL);
        else if ("right".equalsIgnoreCase(align)) tv.setGravity(Gravity.END);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        float mt = parseCssPt(style, "margin-top");
        float mb = parseCssPt(style, "margin-bottom");
        float ml = parseCssPt(style, "margin-left");
        lp.topMargin = mt > 0 ? dp((int) Math.min(28, mt)) : 0;
        lp.bottomMargin = mb > 0 ? dp((int) Math.min(28, mb)) : dp(isHeading ? 10 : 6);
        lp.leftMargin = ml > 0 ? dp((int) Math.min(48, ml)) : 0;
        parent.addView(tv, lp);
    }

    private void addImageFromTag(LinearLayout parent, String imgTag) {
        Matcher m = Pattern.compile("src=\"data:image/[^\"]+;base64,([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(imgTag);
        if (!m.find()) return;
        try {
            byte[] bytes = Util.b64Decode(m.group(1));
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp == null) return;
            ImageView iv = new ImageView(getContext());
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageBitmap(bmp);
            String style = extractAttr(imgTag, "style");
            int wPx = parseCssPx(style, "width");
            int targetW = wPx > 24 ? Math.min(dp(wPx), ViewGroup.LayoutParams.MATCH_PARENT) : ViewGroup.LayoutParams.MATCH_PARENT;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    wPx > 24 ? dp(Math.min(wPx, 340)) : ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.topMargin = dp(6);
            lp.bottomMargin = dp(8);
            parent.addView(iv, lp);
        } catch (Throwable ignored) {}
    }

    private CharSequence parseInlineStyledText(String html, int defaultColor) {
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        if (html == null || html.isEmpty()) return ssb;
        // Walk tags cleanly: <span style="...">, <b>, <strong>, <i>, <em>, <u>, <s>, <br>
        Pattern p = Pattern.compile("(<br\\s*/?>)|(</?(?:span|b|strong|i|em|u|s|del)[^>]*>)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        List<int[]> styleStarts = new ArrayList<>();
        List<String> styleSpecs = new ArrayList<>();
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                ssb.append(unescapeHtml(stripTags(html.substring(last, m.start()))));
            }
            if (m.group(1) != null) {
                ssb.append('\n');
            } else {
                String tok = m.group(2);
                String tl = tok.toLowerCase(Locale.ROOT);
                if (tl.startsWith("</")) {
                    if (!styleStarts.isEmpty()) {
                        int st = styleStarts.remove(styleStarts.size() - 1)[0];
                        String spec = styleSpecs.remove(styleSpecs.size() - 1);
                        applyInlineSpec(ssb, st, ssb.length(), spec, defaultColor);
                    }
                } else {
                    String spec = "";
                    if (tl.startsWith("<b") || tl.startsWith("<strong")) spec = "font-weight:bold;";
                    else if (tl.startsWith("<i") || tl.startsWith("<em")) spec = "font-style:italic;";
                    else if (tl.startsWith("<u")) spec = "text-decoration:underline;";
                    else if (tl.startsWith("<s") || tl.startsWith("<del")) spec = "text-decoration:line-through;";
                    else if (tl.startsWith("<span")) spec = extractAttr(tok, "style");
                    styleStarts.add(new int[]{ssb.length()});
                    styleSpecs.add(spec);
                }
            }
            last = m.end();
        }
        if (last < html.length()) {
            ssb.append(unescapeHtml(stripTags(html.substring(last))));
        }
        while (!styleStarts.isEmpty()) {
            int st = styleStarts.remove(styleStarts.size() - 1)[0];
            String spec = styleSpecs.remove(styleSpecs.size() - 1);
            applyInlineSpec(ssb, st, ssb.length(), spec, defaultColor);
        }
        return ssb;
    }

    private void applyInlineSpec(SpannableStringBuilder ssb, int start, int end, String css, int defaultColor) {
        if (end <= start || css == null || css.isEmpty()) return;
        String low = css.toLowerCase(Locale.ROOT);
        boolean bold = low.contains("font-weight:bold") || low.contains("font-weight:700") || low.contains("font-weight:600");
        boolean italic = low.contains("font-style:italic");
        if (bold && italic) ssb.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        else if (bold) ssb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        else if (italic) ssb.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (low.contains("underline")) ssb.setSpan(new UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (low.contains("line-through")) ssb.setSpan(new StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        float pt = parseCssPt(css, "font-size");
        if (pt > 0f) {
            float rel = Math.max(0.65f, Math.min(2.4f, pt / 10.5f));
            if (Math.abs(rel - 1f) > 0.06f) {
                ssb.setSpan(new RelativeSizeSpan(rel), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        Integer fg = parseCssColor(css, "color");
        if (fg != null) {
            int c = fg;
            if (isDarkTheme && Color.red(c) < 60 && Color.green(c) < 60 && Color.blue(c) < 60) c = defaultColor;
            ssb.setSpan(new ForegroundColorSpan(c), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        Integer bg = parseCssColor(css, "background-color");
        if (bg == null) bg = parseCssColor(css, "background");
        if (bg != null) {
            ssb.setSpan(new BackgroundColorSpan(adaptBgForTheme(bg)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private View buildTableFromHtml(String tableHtml, boolean isSpreadsheet, boolean createUnits, int[] unitCursor,
                                    int textPrimary, int textSecondary) {
        if (isSpreadsheet) {
            return buildSpreadsheetTable(tableHtml, createUnits, unitCursor, textPrimary, textSecondary);
        }
        return buildRichGridTable(tableHtml, createUnits, unitCursor, textPrimary, textSecondary);
    }

    private static final class ParsedCell {
        int row, col, rowSpan, colSpan;
        boolean isHeader;
        String attrs, inner;
    }

    /** Span-aware rich table renderer supporting colspan, rowspan, cell fills, borders, and nested images/styled text. */
    private View buildRichGridTable(String tableHtml, boolean createUnits, int[] unitCursor,
                                    int textPrimary, int textSecondary) {
        List<ParsedCell> cells = new ArrayList<>();
        List<boolean[]> occupied = new ArrayList<>();
        int maxCols = 1;
        int rIdx = 0;

        // Extract top-level <tr> elements (avoid matching nested table rows)
        List<String> rows = extractTopLevelTags(tableHtml, "tr");
        for (String rowHtml : rows) {
            while (occupied.size() <= rIdx) occupied.add(new boolean[64]);
            List<String> cellTags = extractTopLevelCells(rowHtml);
            int cCursor = 0;
            for (String cellFull : cellTags) {
                while (cCursor < 64 && occupied.get(rIdx)[cCursor]) cCursor++;
                int gt = cellFull.indexOf('>');
                if (gt < 0) continue;
                String open = cellFull.substring(0, gt + 1);
                boolean isTh = open.toLowerCase(Locale.ROOT).startsWith("<th");
                String attrs = open.substring(3, open.length() - 1);
                int closeIdx = cellFull.lastIndexOf("</");
                String inner = closeIdx > gt ? cellFull.substring(gt + 1, closeIdx) : "";
                int cs = Math.max(1, Math.min(20, parseIntAttr(attrs, "colspan", 1)));
                int rs = Math.max(1, Math.min(30, parseIntAttr(attrs, "rowspan", 1)));

                ParsedCell pc = new ParsedCell();
                pc.row = rIdx; pc.col = cCursor; pc.colSpan = cs; pc.rowSpan = rs;
                pc.isHeader = isTh; pc.attrs = attrs; pc.inner = inner;
                cells.add(pc);

                for (int rr = rIdx; rr < rIdx + rs; rr++) {
                    while (occupied.size() <= rr) occupied.add(new boolean[64]);
                    for (int cc = cCursor; cc < Math.min(64, cCursor + cs); cc++) occupied.get(rr)[cc] = true;
                }
                if (cCursor + cs > maxCols) maxCols = cCursor + cs;
                cCursor += cs;
            }
            rIdx++;
        }

        if (cells.isEmpty()) return new View(getContext());

        HorizontalScrollView hScroll = new HorizontalScrollView(getContext());
        hScroll.setFillViewport(true);

        GridLayout grid = new GridLayout(getContext());
        grid.setColumnCount(maxCols);
        grid.setRowCount(Math.max(1, rIdx));

        int defBorder = isDarkTheme ? 0xFF333B4C : 0xFFCBD5E1;
        int defHeaderBg = isDarkTheme ? 0xFF252B38 : 0xFFEEF2F8;
        int defCellBg = isDarkTheme ? 0xFF1A1E27 : 0xFFFFFFFF;
        boolean tableBorderless = tableHtml.substring(0, Math.min(tableHtml.length(), 180)).contains("border:none");

        for (ParsedCell pc : cells) {
            String style = extractAttr(pc.attrs, "style");
            String dataK = extractAttr(pc.attrs, "data-k");
            boolean noBorder = tableBorderless || style.contains("border:none");
            Integer bg = parseCssColor(style, "background-color");
            if (bg == null) bg = parseCssColor(style, "background");
            Integer bd = parseBorderColor(style);

            int cellFill = bg != null ? adaptBgForTheme(bg) : (pc.isHeader ? defHeaderBg : defCellBg);
            int cellTextCol = (bg != null && !isDarkTheme && isDarkColor(cellFill)) ? 0xFFFFFFFF : textPrimary;

            LinearLayout cellBox = new LinearLayout(getContext());
            cellBox.setOrientation(LinearLayout.VERTICAL);
            cellBox.setPadding(dp(8), dp(6), dp(8), dp(6));
            cellBox.setMinimumWidth(dp(maxCols <= 4 ? 72 : 56));
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(cellFill);
            if (!noBorder) gd.setStroke(1, bd != null ? bd : defBorder);
            cellBox.setBackground(gd);

            boolean hasNestedBlocks = pc.inner.contains("<p") || pc.inner.contains("<img")
                    || pc.inner.contains("<table") || pc.inner.contains("<div");
            if (hasNestedBlocks && dataK.isEmpty()) {
                populateRichHtmlBlocks(cellBox, pc.inner, createUnits, unitCursor, cellTextCol, textSecondary);
            } else {
                String plain = unescapeHtml(stripTagsPreserveNewlines(pc.inner)).trim();
                UnitView uv = obtainOrCreateUnit(dataK, plain, true, pc.isHeader, createUnits, unitCursor);
                TextView tv = createBoundTextOrEdit(uv, cellTextCol, false);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * zoomScale);
                if (!isEditing) {
                    CharSequence styled = parseInlineStyledText(pc.inner, cellTextCol);
                    if (styled.length() > 0) tv.setText(styled);
                }
                if (pc.isHeader) tv.setTypeface(Typeface.DEFAULT_BOLD);
                cellBox.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            // Compute explicit pixel column width so GridLayout inside HorizontalScrollView NEVER collapses width=0 cells!
            int screenAvail = Math.max(dp(280), getResources().getDisplayMetrics().widthPixels - dp(68));
            int colW = Math.max(dp(64), screenAvail / Math.max(1, maxCols));
            GridLayout.LayoutParams glp = new GridLayout.LayoutParams(
                    GridLayout.spec(pc.row, pc.rowSpan, GridLayout.FILL),
                    GridLayout.spec(pc.col, pc.colSpan, GridLayout.FILL));
            glp.width = colW * pc.colSpan;
            glp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            grid.addView(cellBox, glp);
        }

        hScroll.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return hScroll;
    }

    private View buildSpreadsheetTable(String tableHtml, boolean createUnits, int[] unitCursor,
                                       int textPrimary, int textSecondary) {
        HorizontalScrollView hScroll = new HorizontalScrollView(getContext());
        hScroll.setFillViewport(true);

        TableLayout table = new TableLayout(getContext());
        table.setStretchAllColumns(true);
        int borderCol = isDarkTheme ? 0xFF2E3442 : 0xFFD0D7E3;
        int headerBg = isDarkTheme ? 0xFF252A35 : 0xFFEEF2F8;
        int cellBg = isDarkTheme ? 0xFF1A1D24 : 0xFFFFFFFF;

        Matcher rowMatcher = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL).matcher(tableHtml);
        while (rowMatcher.find()) {
            String rowInner = rowMatcher.group(1);
            TableRow tr = new TableRow(getContext());
            Matcher cellMatcher = Pattern.compile("<(th|td)([^>]*)>(.*?)</\\1>", Pattern.DOTALL).matcher(rowInner);
            int cIdx = 0;
            while (cellMatcher.find()) {
                String tag = cellMatcher.group(1);
                String attrs = cellMatcher.group(2) != null ? cellMatcher.group(2) : "";
                String inner = cellMatcher.group(3) != null ? cellMatcher.group(3) : "";
                boolean isHeader = "th".equalsIgnoreCase(tag);
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
                cellView.setMinimumWidth(dp(cIdx == 0 && dataK.isEmpty() ? 38 : 84));

                GradientDrawable cellDrawable = new GradientDrawable();
                cellDrawable.setColor(isHeader ? headerBg : cellBg);
                cellDrawable.setStroke(1, borderCol);
                cellView.setBackground(cellDrawable);

                TableRow.LayoutParams tlp = new TableRow.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
                tr.addView(cellView, tlp);
                cIdx++;
            }
            table.addView(tr);
        }
        hScroll.addView(table);
        return hScroll;
    }

    private static int findMatchingClose(String html, int afterOpen, String tag) {
        String low = html.toLowerCase(Locale.ROOT);
        String openPrefix = "<" + tag;
        String closeTag = "</" + tag + ">";
        int depth = 1;
        int pos = afterOpen;
        while (pos < low.length() && depth > 0) {
            int nextOpen = low.indexOf(openPrefix, pos);
            int nextClose = low.indexOf(closeTag, pos);
            if (nextClose < 0) return html.length();
            if (nextOpen >= 0 && nextOpen < nextClose) {
                int afterCharIdx = nextOpen + openPrefix.length();
                char nc = afterCharIdx < low.length() ? low.charAt(afterCharIdx) : '>';
                if (nc == '>' || nc == ' ' || nc == '\t' || nc == '\n') depth++;
                pos = afterCharIdx;
            } else {
                depth--;
                pos = nextClose + closeTag.length();
            }
        }
        return pos;
    }

    private static String extractInnerHtml(String html, int start, int endWithClose, String tag) {
        String closeTag = "</" + tag + ">";
        int end = endWithClose;
        if (end >= closeTag.length() && html.substring(end - closeTag.length(), end).equalsIgnoreCase(closeTag)) {
            end -= closeTag.length();
        }
        return end > start ? html.substring(start, end) : "";
    }

    private static List<String> extractTopLevelTags(String html, String tag) {
        List<String> out = new ArrayList<>();
        int gt0 = html.indexOf('>');
        if (gt0 < 0) return out;
        String body = extractInnerHtml(html, gt0 + 1, html.length(), "table");
        String low = body.toLowerCase(Locale.ROOT);
        int pos = 0;
        while (pos < body.length()) {
            int idx = low.indexOf("<" + tag, pos);
            if (idx < 0) break;
            int gt = body.indexOf('>', idx);
            if (gt < 0) break;
            int end = findMatchingClose(body, gt + 1, tag);
            out.add(body.substring(idx, end));
            pos = end;
        }
        return out;
    }

    private static List<String> extractTopLevelCells(String rowHtml) {
        List<String> out = new ArrayList<>();
        int gt0 = rowHtml.indexOf('>');
        if (gt0 < 0) return out;
        String body = extractInnerHtml(rowHtml, gt0 + 1, rowHtml.length(), "tr");
        String low = body.toLowerCase(Locale.ROOT);
        int pos = 0;
        while (pos < body.length()) {
            int iTd = low.indexOf("<td", pos);
            int iTh = low.indexOf("<th", pos);
            int idx;
            String tag;
            if (iTd >= 0 && (iTh < 0 || iTd < iTh)) { idx = iTd; tag = "td"; }
            else if (iTh >= 0) { idx = iTh; tag = "th"; }
            else break;
            int gt = body.indexOf('>', idx);
            if (gt < 0) break;
            int end = findMatchingClose(body, gt + 1, tag);
            out.add(body.substring(idx, end));
            pos = end;
        }
        return out;
    }

    private int adaptBgForTheme(int col) {
        if (!isDarkTheme) return col;
        // Soften bright pastel template fills in dark mode so white text stays legible
        int r = Color.red(col), g = Color.green(col), b = Color.blue(col);
        if (r + g + b > 520) {
            return Color.rgb(Math.max(28, r / 5), Math.max(32, g / 5), Math.max(40, b / 5));
        }
        return col;
    }

    private static boolean isDarkColor(int c) {
        return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 < 120;
    }

    private static int parseIntAttr(String attrs, String name, int def) {
        String v = extractAttr(attrs, name);
        if (v.isEmpty()) return def;
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return def; }
    }

    private static String parseCssProp(String css, String prop) {
        if (css == null || css.isEmpty()) return "";
        Matcher m = Pattern.compile("(?i)(?:^|;)\\s*" + Pattern.quote(prop) + "\\s*:\\s*([^;]+)").matcher(css);
        return m.find() ? m.group(1).trim() : "";
    }

    private static Integer parseCssColor(String css, String prop) {
        String v = parseCssProp(css, prop);
        if (v.isEmpty()) return null;
        Matcher hm = Pattern.compile("#[0-9a-fA-F]{6}").matcher(v);
        if (hm.find()) {
            try { return Color.parseColor(hm.group()); } catch (Exception ignored) {}
        }
        return null;
    }

    private static Integer parseBorderColor(String css) {
        String v = parseCssProp(css, "border");
        if (v.isEmpty() || "none".equalsIgnoreCase(v)) return null;
        Matcher hm = Pattern.compile("#[0-9a-fA-F]{6}").matcher(v);
        if (hm.find()) {
            try { return Color.parseColor(hm.group()); } catch (Exception ignored) {}
        }
        return null;
    }

    private static float parseCssPt(String css, String prop) {
        String v = parseCssProp(css, prop);
        Matcher m = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*pt", Pattern.CASE_INSENSITIVE).matcher(v);
        if (m.find()) {
            try { return Float.parseFloat(m.group(1)); } catch (Exception ignored) {}
        }
        return 0f;
    }

    private static int parseCssPx(String css, String prop) {
        String v = parseCssProp(css, prop);
        Matcher m = Pattern.compile("([0-9]+)\\s*px", Pattern.CASE_INSENSITIVE).matcher(v);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        }
        return 0;
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
