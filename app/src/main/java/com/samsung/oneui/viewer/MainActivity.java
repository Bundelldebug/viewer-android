package com.samsung.oneui.viewer;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.print.PrintManager;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Samsung Viewer Android (9.5.3) rebuilt cleanly on Prism Music Android's Liquid Glass UI engine:
 * - True AGSL Liquid Glass Backdrop + Header + Dock (draggable magnifying refractive lens + real-time active accent clipping)
 * - Press-to-expand icon regions + drop-morphing GlassMenu context menus & sheets
 * - Full Viewer feature parity: Image / Animated GIF / Multi-page PDF / Office & HWP / Media / Markup / Edit / OCR / Print / Export / Capture / Drag-and-drop.
 */
public final class MainActivity extends Activity implements Dock.Listener, Header.Listener {

    private static final int REQ_OPEN_DOC = 1001;
    private static final int REQ_CREATE_DOC = 1002;
    private static final int REQ_CAMERA = 1003;
    private static final int REQ_INSERT_PAGE_DOC = 1004;
    private static final int REQ_OPEN_TREE = 1005;
    private static final int REQ_CAPTURE = 1006;

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("samsung_viewer_native_prefs", MODE_PRIVATE);
    }

    // ---- Backend executors & services ----
    private DocumentStore documents;
    private final ExecutorService bgPool = Executors.newCachedThreadPool();
    private final ExecutorService filePool = Executors.newSingleThreadExecutor();
    private final ExecutorService ocrPool = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private File sampleDir;
    private File inboxDir;
    private File pendingCameraFile;
    private byte[] pendingExportBytes;
    private File pendingExportFile;
    private Runnable afterSave, afterSaveCancelled;
    private boolean saveRunning, flattenedPdfApproved;
    private volatile int openTicket, imageLoadTicket, searchTicket;
    private final List<Runnable> dropPermissions = new ArrayList<>();
    private boolean capturePending, activityResumed;

    int exportQuality = 100;
    int exportDpi = 300;
    int slideshowIntervalSec = 3;
    boolean isDarkTheme;
    Rect foldBounds;
    private final FoldObserver foldObserver = new FoldObserver();

    // ---- Current file & folder state ----
    String currentPath = "";
    String currentKind = ""; // "", "image", "pdf", "doc", "media"
    final List<FileEntry> folderItems = new ArrayList<>();
    int currentFileIndex = -1;
    final List<String> bookmarks = new ArrayList<>();
    private String sortOrder = "name";
    boolean isModified;

    // ---- Undo / Redo & Image adjustment state ----
    private static final class ImageSnapshot {
        final Bitmap bmp;
        final List<MarkupModel.Annotation> anns;
        ImageSnapshot(Bitmap b, List<MarkupModel.Annotation> a) {
            this.bmp = b;
            this.anns = MarkupModel.copyList(a);
        }
    }
    private final List<ImageSnapshot> imgUndoStack = new ArrayList<>();
    private final List<ImageSnapshot> imgRedoStack = new ArrayList<>();
    private final List<List<MarkupModel.PdfPageItem>> pdfUndo = new ArrayList<>(), pdfRedo = new ArrayList<>();
    private String lastPdfKey = "";

    private float adjExp, adjCon, adjSat, adjWarm, adjSharp;
    private boolean adjSepia, adjInvert;
    private int alphaTolerance = 32;
    private boolean alphaContiguous = true;
    private final List<Bitmap> savedSignatures = new ArrayList<>();

    // ---- OCR & Search state ----
    final List<StageCanvasView.OcrBox> currentOcrBoxes = new ArrayList<>();
    private int activeOcrHit = -1;
    private String fullExtractedOcrText = "";
    private int docHitIndex = -1, docHitTotal = 0;

    // ---- Slideshow state ----
    private boolean slideshowRunning;
    private FrameLayout slideshowOverlay;
    private ImageView slideshowImageView;
    private TextView slideshowCaptionView;
    private final Runnable slideshowRunnable = new Runnable() {
        @Override public void run() {
            if (!slideshowRunning) return;
            navigateFile(1);
            mainHandler.postDelayed(this, slideshowIntervalSec * 1000L);
        }
    };

    // ---- Prism Liquid Glass UI hierarchy ----
    FrameLayout root;
    Glass.Backdrop backdrop;
    private FrameLayout pageHost;
    Header header;
    Dock dock;
    private Sidebar sidebar;
    private View dropOverlay;

    private final Page[] pages = new Page[4];
    private Pages.HomePage homePage;
    private Pages.StagePage stagePage;
    private Pages.FilesPage filesPage;
    private Pages.SettingsPage settingsPage;
    private Pages.SearchPage searchPage;
    private int tab;
    private boolean searching;

    // ---- Core native stage views (kept alive across UI rebuilds) ----
    StageCanvasView stageCanvasView;
    NativeDocView nativeDocView;
    NativeMediaView nativeMediaView;
    FrameLayout stageHostFrame;

    // ---- Tablet sidebar animation state ----
    private static boolean sidebarWanted;
    private float sideP;
    private ValueAnimator sideAnim;
    private int contentInset = -1;

    public static final class FileEntry {
        public final String path, name, kind;
        public final long size, lastModified;
        public FileEntry(String path, String name, String kind, long size, long lastModified) {
            this.path = path; this.name = name; this.kind = kind; this.size = size; this.lastModified = lastModified;
        }
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        prefs = prefs(this);
        Ui.init(this, prefs);
        isDarkTheme = Ui.dark;
        setTheme(Ui.dark ? android.R.style.Theme_DeviceDefault_NoActionBar : android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        documents = new DocumentStore(this);
        exportQuality = prefs.getInt("export_quality", 100);
        exportDpi = prefs.getInt("export_dpi", 300);
        slideshowIntervalSec = prefs.getInt("slide_sec", 3);
        loadBookmarks();
        OcrHost.init(this);
        PdfHost.init(this);

        File baseExt = getExternalFilesDir(null);
        if (baseExt == null) baseExt = getFilesDir();
        sampleDir = new File(baseExt, "Viewer_Samples");
        if (!sampleDir.exists()) sampleDir.mkdirs();
        inboxDir = new File(getCacheDir(), "inbox");
        if (!inboxDir.exists()) inboxDir.mkdirs();

        stageCanvasView = new StageCanvasView(this);
        nativeDocView = new NativeDocView(this);
        nativeMediaView = new NativeMediaView(this);
        bindCoreViewListeners();

        buildCompleteNativeUI();

        foldObserver.start(this, bounds -> {
            if (java.util.Objects.equals(foldBounds, bounds)) return;
            foldBounds = bounds;
            if (homePage != null) homePage.rebuild();
            fitHeader();
            dock.invalidate();
        });

        if (savedInstanceState == null) {
            root.postDelayed(() -> Updater.autoCheck(this), 2800);
        }

        if (!handleIncomingIntent(getIntent())) {
            if (savedInstanceState != null) {
                String orig = savedInstanceState.getString("path");
                String draft = savedInstanceState.getString("draft");
                if (orig != null && !orig.isEmpty()) {
                    openFileUnchecked(draft == null ? orig : draft);
                    if (draft != null) {
                        currentPath = orig;
                        isModified = true;
                        if ("doc".equals(currentKind)) nativeDocView.markDraftRestored();
                    }
                    stageCanvasView.setZoom(savedInstanceState.getFloat("zoom", 1f));
                    stageCanvasView.setCurrentPdfPage(savedInstanceState.getInt("page", 0));
                    String pending = savedInstanceState.getString("pending_export");
                    if (pending != null) pendingExportFile = new File(pending);
                    syncChromeState();
                } else {
                    int savedTab = savedInstanceState.getInt("tab", 0);
                    switchTab(savedTab);
                }
                final Bundle st0 = savedInstanceState;
                root.post(() -> screenChangeTransition(st0));
            }
        }
    }

    /** Builds (or cleanly rebuilds) the full Prism Liquid Glass UI while preserving stage views & open files. */
    void buildCompleteNativeUI() {
        Ui.init(this, prefs);
        isDarkTheme = Ui.dark;
        applySystemBars();

        // Detach reusable stage views cleanly before rebuilding the view hierarchy
        detachFromParent(stageCanvasView);
        detachFromParent(nativeDocView);
        detachFromParent(nativeMediaView);
        stageCanvasView.setDarkTheme(Ui.dark);
        nativeDocView.setDarkTheme(Ui.dark);

        root = new FrameLayout(this);
        root.setTag("welcome-root");
        root.setBackgroundColor(Ui.bg());

        backdrop = new Glass.Backdrop(this);
        backdrop.setBackgroundColor(Ui.bg());
        root.addView(backdrop, match());

        pageHost = new FrameLayout(this);
        backdrop.addView(pageHost, match());

        header = new Header(this, backdrop);
        header.setListener(this);
        root.addView(header, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, headerHeight()));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            Ui.screenW = Math.max(1, r - l);
            Ui.screenH = Math.max(1, b - t);
            fitHeader();
            applyAllPageInsets();
        });

        dock = new Dock(this, backdrop, new String[]{"홈", "보기", "목록", "설정"});
        dock.setListener(this);
        root.addView(dock, match());

        buildDropOverlay();
        buildSlideshowOverlay();

        homePage = new Pages.HomePage(this);
        stagePage = new Pages.StagePage(this);
        filesPage = new Pages.FilesPage(this);
        settingsPage = new Pages.SettingsPage(this);
        stageHostFrame = stagePage.stageContainer;

        pages[0] = homePage;
        pages[1] = stagePage;
        pages[2] = filesPage;
        pages[3] = settingsPage;
        for (Page p : pages) {
            p.setVisibility(View.GONE);
            pageHost.addView(p, match());
        }
        searchPage = null;
        searching = false;
        sidebar = null;
        sideP = 0f;
        contentInset = -1;

        setContentView(root);
        configureFileDrop();

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                int st = insets.getInsets(WindowInsets.Type.statusBars()).top;
                int nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                boolean changed = st != Ui.statusInset || nav != Ui.navInset;
                Ui.statusInset = st;
                Ui.navInset = nav;
                Ui.imeInset = ime;
                dock.setImeOffset(ime);
                fitHeader();
                if (changed) {
                    for (Page p : pages) if (p != null) p.applyInsets();
                    if (searchPage != null) searchPage.applyInsets();
                }
                return WindowInsets.CONSUMED;
            } else {
                Ui.statusInset = insets.getSystemWindowInsetTop();
                Ui.navInset = insets.getSystemWindowInsetBottom();
                fitHeader();
                for (Page p : pages) if (p != null) p.applyInsets();
                return insets.consumeSystemWindowInsets();
            }
        });

        // Exact Prism live glass invalidation hook
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                updateScrollChrome(currentPage());
                boolean pageChanged = backdrop.isBackdropDirty();
                if (pageChanged || header.animating()) header.invalidate();
                if (dock.getVisibility() == View.VISIBLE && (pageChanged || dock.animating())) dock.invalidate();
                for (int i = 0; i < root.getChildCount(); i++) {
                    View c = root.getChildAt(i);
                    if (c instanceof GlassMenu) c.invalidate();
                }
                return true;
            }
        });

        int initialTab = hasOpenFile() ? 1 : Math.min(3, Math.max(0, tab));
        if (!hasOpenFile() && initialTab == 1) initialTab = 0;
        tab = initialTab;
        pages[tab].setVisibility(View.VISIBLE);
        pages[tab].show();
        dock.select(tab, false);

        attachActiveStageView();
        syncChromeState();

        if (sidebarWanted) root.post(() -> { if (tabletUi()) openSidebar(false); });
    }

    private void applySystemBars() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        }
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) w.setNavigationBarContrastEnforced(false);
        w.getDecorView().setBackgroundColor(Ui.bg());
        if (Build.VERSION.SDK_INT >= 30 && w.getInsetsController() != null) {
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            w.getInsetsController().setSystemBarsAppearance(Ui.dark ? 0 : mask, mask);
        } else {
            int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            int flags = w.getDecorView().getSystemUiVisibility();
            w.getDecorView().setSystemUiVisibility(Ui.dark ? (flags & ~mask) : (flags | mask));
        }
    }

    private int headerHeight() {
        int W = root != null && root.getWidth() > 0 ? root.getWidth() : Ui.screenW;
        int H = root != null && root.getHeight() > 0 ? root.getHeight() : Ui.screenH;
        return Ui.statusInset + Ui.dp(Dock.railFor(W, H) ? 172 : 88);
    }

    private void fitHeader() {
        if (header == null || header.getLayoutParams() == null) return;
        int h = headerHeight();
        if (header.getLayoutParams().height == h) return;
        header.getLayoutParams().height = h;
        header.post(header::requestLayout);
    }

    private static void detachFromParent(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    // ---- Drag-and-drop overlay & Slideshow overlay ----
    private void buildDropOverlay() {
        LinearLayout ov = new LinearLayout(this);
        ov.setTag("file-drop-overlay");
        ov.setOrientation(LinearLayout.VERTICAL);
        ov.setGravity(Gravity.CENTER);
        ov.setBackgroundColor(Ui.dark ? 0xCC0A0D14 : 0xCCF2F5FA);
        ov.setPadding(Ui.dp(32), Ui.dp(32), Ui.dp(32), Ui.dp(32));
        ov.setVisibility(View.GONE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(Ui.dp(28), Ui.dp(28), Ui.dp(28), Ui.dp(28));
        GradientDrawable bg = Ui.round(Ui.card(), 26);
        bg.setStroke(Ui.dp(2), Ui.ACCENT);
        card.setBackground(bg);
        card.addView(new Icons.IconView(this, Icons.OPEN_FILE, 42, Ui.ACCENT), new LinearLayout.LayoutParams(Ui.dp(48), Ui.dp(48)));
        TextView t = Ui.text(this, "여기에 놓아서 열기", 19, Ui.bold, Ui.text());
        t.setPadding(0, Ui.dp(12), 0, Ui.dp(4));
        card.addView(t);
        card.addView(Ui.text(this, "이미지 · PDF · 문서 · 미디어", 13.5f, Ui.regular, Ui.text2()));
        ov.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dropOverlay = ov;
        root.addView(dropOverlay, match());
    }

    private void configureFileDrop() {
        root.setOnDragListener((v, event) -> {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return FileDrop.accepts(event.getClipDescription());
                case DragEvent.ACTION_DRAG_ENTERED:
                case DragEvent.ACTION_DRAG_LOCATION:
                    if (dropOverlay != null) dropOverlay.setVisibility(View.VISIBLE);
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    if (dropOverlay != null) dropOverlay.setVisibility(View.GONE);
                    return true;
                case DragEvent.ACTION_DROP: {
                    if (dropOverlay != null) dropOverlay.setVisibility(View.GONE);
                    List<Uri> uris = FileDrop.collect(event.getClipData());
                    if (uris.isEmpty()) return false;
                    final android.view.DragAndDropPermissions perm = requestDragAndDropPermissions(event);
                    Runnable release = FileDrop.once(() -> {
                        if (perm != null) { try { perm.release(); } catch (Throwable ignored) {} }
                    });
                    synchronized (dropPermissions) { dropPermissions.add(release); }
                    openUris(uris, Intent.FLAG_GRANT_READ_URI_PERMISSION, () -> {
                        synchronized (dropPermissions) { dropPermissions.remove(release); }
                        release.run();
                    });
                    return true;
                }
            }
            return false;
        });
    }

    private void buildSlideshowOverlay() {
        slideshowOverlay = new FrameLayout(this);
        slideshowOverlay.setBackgroundColor(0xFF000000);
        slideshowOverlay.setVisibility(View.GONE);
        slideshowImageView = new ImageView(this);
        slideshowImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        slideshowOverlay.addView(slideshowImageView, match());
        slideshowCaptionView = Ui.text(this, "", 14, Ui.semibold, 0xFFFFFFFF);
        slideshowCaptionView.setGravity(Gravity.CENTER);
        slideshowCaptionView.setPadding(Ui.dp(18), Ui.dp(10), Ui.dp(18), Ui.dp(10));
        slideshowCaptionView.setBackground(Ui.round(0x88000000, 100));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        clp.bottomMargin = Ui.navInset + Ui.dp(28);
        slideshowOverlay.addView(slideshowCaptionView, clp);
        slideshowOverlay.setOnClickListener(v -> stopSlideshow());
        root.addView(slideshowOverlay, match());
    }

    // ---- Activity lifecycle & fold transition ----
    private static Bitmap lastFrame;

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("tab", tab);
        out.putString("path", currentPath);
        out.putFloat("zoom", stageCanvasView != null ? stageCanvasView.getZoom() : 1f);
        out.putInt("page", stageCanvasView != null ? stageCanvasView.getCurrentPdfPage() : 0);
        if (pendingExportFile != null) out.putString("pending_export", pendingExportFile.getAbsolutePath());
        if (dock != null && dock.getWidth() > 0) out.putFloatArray("dock_geo", dock.snapshot());
        if (header != null) { float[] hg = header.snapshot(); if (hg != null) out.putFloatArray("hdr_geo", hg); }
        lastFrame = isChangingConfigurations() ? captureFrame() : null;
        if (isModified && !saveRunning && !currentPath.isEmpty()) {
            try {
                File snapshot = ViewerExports.snapshot(this, currentPath, currentKind, stageCanvasView, nativeDocView, exportQuality, exportDpi);
                File dir = new File(getFilesDir(), "drafts");
                dir.mkdirs();
                File draft = new File(dir, System.currentTimeMillis() + "_" + snapshot.getName());
                Files.move(snapshot.toPath(), draft.toPath(), StandardCopyOption.REPLACE_EXISTING);
                out.putString("draft", draft.getAbsolutePath());
            } catch (Exception ignored) {}
        }
        super.onSaveInstanceState(out);
    }

    private Bitmap captureFrame() {
        try {
            int w = root.getWidth(), h = root.getHeight();
            if (w <= 0 || h <= 0) return null;
            float s = 0.5f;
            Bitmap b = Bitmap.createBitmap((int) (w * s), (int) (h * s), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(s, s);
            root.draw(c);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private void screenChangeTransition(Bundle state) {
        Bitmap frame = lastFrame;
        lastFrame = null;
        float[] g = state.getFloatArray("dock_geo");
        if (g == null) return;
        boolean grew = Ui.screenW > g[0] * 1.08f;
        boolean shrank = Ui.screenW < g[0] * 0.92f;
        boolean reshaped = Math.abs(Ui.screenH - g[1]) > g[1] * 0.08f;
        if (!grew && !shrank && !reshaped) return;
        if (frame != null) {
            ImageView old = new ImageView(this);
            old.setImageBitmap(frame);
            old.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(old, match());
            old.animate().alpha(0f).scaleX(grew ? 1.06f : 0.96f).scaleY(grew ? 1.06f : 0.96f)
                    .setDuration(280).setInterpolator(Ui.DECEL)
                    .withEndAction(() -> { root.removeView(old); frame.recycle(); }).start();
        }
        float from = grew ? 0.92f : 1.06f;
        pageHost.setAlpha(0f);
        pageHost.setScaleX(from); pageHost.setScaleY(from);
        pageHost.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(Ui.EMPHASIZED).start();
        float[] hg = state.getFloatArray("hdr_geo");
        if (hg != null) header.morphFrom(hg);
        dock.morphFrom(g);
    }

    @Override protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (prefs != null) consumeCapture();
    }

    @Override protected void onPause() {
        activityResumed = false;
        super.onPause();
        stopSlideshow();
        if (nativeMediaView != null) nativeMediaView.pauseMedia();
    }

    @Override protected void onDestroy() {
        synchronized (dropPermissions) {
            for (Runnable r : new ArrayList<>(dropPermissions)) r.run();
            dropPermissions.clear();
        }
        cancelAfterSave();
        openTicket++; imageLoadTicket++; searchTicket++;
        foldObserver.stop();
        ocrPool.shutdownNow();
        filePool.shutdownNow();
        bgPool.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        if (nativeMediaView != null) nativeMediaView.stopMedia();
        PdfHost.closeAll();
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Ui.init(this, prefs);
        isDarkTheme = Ui.dark;
        buildCompleteNativeUI();
    }

    @Override public void onBackPressed() {
        if (GlassMenu.dismissAny(root)) return;
        if (slideshowRunning) { stopSlideshow(); return; }
        if (sideP > 0.1f) { closeSidebar(); return; }
        if (searching) { onExitSearch(); return; }
        if (tab == 1 && hasOpenFile()) {
            if (!"hand".equals(stageCanvasView.getActiveTool())) {
                setTool("hand");
                return;
            }
            if ("doc".equals(currentKind) && nativeDocView.isEditing()) {
                nativeDocView.setEditing(false);
                syncChromeState();
                return;
            }
            switchTab(0);
            return;
        }
        if (tab != 0) { switchTab(0); return; }
        guardChanges(this::finish);
    }

    // ---- Navigation & Prism Chrome Coordination ----
    boolean hasOpenFile() { return currentPath != null && !currentPath.isEmpty(); }
    boolean tabletUi() {
        int W = root != null && root.getWidth() > 0 ? root.getWidth() : Ui.screenW;
        int H = root != null && root.getHeight() > 0 ? root.getHeight() : Ui.screenH;
        return Dock.tabletFor(W, H);
    }

    int sideInset() {
        int W = root != null && root.getWidth() > 0 ? root.getWidth() : Ui.screenW;
        int H = root != null && root.getHeight() > 0 ? root.getHeight() : Ui.screenH;
        return Dock.railFor(W, H) ? Ui.dp(20 + 64 + 12) : 0;
    }

    int bottomPadding() { return dock == null ? Ui.dp(180) : dock.chromeHeight() + Ui.dp(14); }
    int contentInsetPx() { return Math.max(0, contentInset); }

    Page currentPage() {
        if (searching && searchPage != null) return searchPage;
        return pages[tab];
    }

    void switchToViewerTab() { switchTab(1); }

    void openFilesFilter(int filterIdx) {
        switchTab(2);
        if (filesPage != null) filesPage.setFilter(filterIdx);
    }

    void switchTab(int i) {
        i = Math.max(0, Math.min(3, i));
        if (i == tab && !searching) {
            if (pages[i] != null) pages[i].scrollToTop();
            return;
        }
        if (searching) onExitSearch();
        Page from = pages[tab], to = pages[i];
        tab = i;
        if (dock != null && dock.selected != i) dock.select(i, true);
        if (from != null && from != to) {
            from.animate().cancel();
            from.animate().alpha(0f).setDuration(140).withEndAction(() -> {
                from.setVisibility(View.GONE);
                from.setAlpha(1f);
            }).start();
        }
        if (to != null) {
            to.animate().cancel();
            to.setVisibility(View.VISIBLE);
            to.setAlpha(0f);
            to.setScaleX(0.985f);
            to.setScaleY(0.985f);
            to.show();
            to.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(50).setDuration(290).setInterpolator(Ui.EMPHASIZED).start();
        }
        if (sidebar != null) sidebar.setSelected(i);
        if (tab == 2 && filesPage != null) filesPage.refresh();
        if (dock != null) dock.setCollapsed(false);
        syncChromeState();
    }

    private float collapseAcc;

    void onPageScroll(Page p, int y, int dy) {
        if (p != currentPage()) return;
        updateScrollChrome(p);
        if (dock.inSearch()) { dock.hideIme(); return; }
        if ((dy > 0) != (collapseAcc > 0)) collapseAcc = 0;
        collapseAcc += dy;
        if (y < Ui.dp(60)) dock.setCollapsed(false);
        else if (collapseAcc > Ui.dp(28)) dock.setCollapsed(true);
        else if (collapseAcc < -Ui.dp(14)) dock.setCollapsed(false);
    }

    private void updateScrollChrome(Page p) {
        if (p == null || header == null) return;
        float t = p.titleProgress();
        header.setScroll(t, p.blurProgress());
        if (p.bigTitle != null) p.bigTitle.setAlpha(1f - t);
    }

    void syncChromeState() {
        if (header == null || dock == null) return;
        Page p = currentPage();
        if (p == stagePage) {
            String t = hasOpenFile() ? ((isModified ? "● " : "") + new File(currentPath).getName()) : "보기";
            stagePage.title = t;
            stagePage.back = hasOpenFile();
            stagePage.headerIcons = hasOpenFile() ? new int[]{Icons.INFO, Icons.MORE} : new int[]{Icons.OPEN_FILE, Icons.MORE};
        }
        header.configure(p.title, p.headerIcons, p.back);
        updateScrollChrome(p);

        Dock.ToolSlot[] slots = buildToolSlots();
        String name = hasOpenFile() ? new File(currentPath).getName() : "";
        String meta = buildFileMetaSummary();
        dock.setFileState(hasOpenFile(), tab == 1, name, meta, Pages.iconForKind(currentKind), isModified, slots);
        refreshSubToolBar();
        applyAllPageInsets();
    }

    void applyAllPageInsets() {
        for (Page p : pages) if (p != null) p.applyInsets();
        if (searchPage != null) searchPage.applyInsets();
    }

    private String buildFileMetaSummary() {
        if (!hasOpenFile()) return "";
        File f = new File(currentPath);
        String pos = folderItems.size() > 1 ? ((currentFileIndex + 1) + "/" + folderItems.size() + " · ") : "";
        if ("image".equals(currentKind) && stageCanvasView.getImageBitmap() != null) {
            Bitmap b = stageCanvasView.getImageBitmap();
            return pos + b.getWidth() + "×" + b.getHeight() + " · " + Math.round(stageCanvasView.getZoom() * 100f) + "%";
        }
        if ("pdf".equals(currentKind)) {
            int p = stageCanvasView.getCurrentPdfPage() + 1;
            int tot = Math.max(1, stageCanvasView.getPdfPages().size());
            return pos + "PDF " + p + "/" + tot + " · " + Math.round(stageCanvasView.getZoom() * 100f) + "%";
        }
        if ("doc".equals(currentKind)) {
            return pos + Util.extOf(currentPath).toUpperCase(Locale.ROOT).replace(".", "") + " · " + Math.round(nativeDocView.getZoomScale() * 100f) + "%";
        }
        return pos + fmtBytes(f.length());
    }

    private Dock.ToolSlot[] buildToolSlots() {
        String activeTool = stageCanvasView != null ? stageCanvasView.getActiveTool() : "hand";
        if ("doc".equals(currentKind)) {
            boolean ed = nativeDocView != null && nativeDocView.isEditing();
            return new Dock.ToolSlot[]{
                    new Dock.ToolSlot(Icons.EDIT, ed ? "완료" : "편집", ed),
                    new Dock.ToolSlot(Icons.UNDO, "취소", false),
                    new Dock.ToolSlot(Icons.ZOOM_IN, "배율", false),
                    new Dock.ToolSlot(Icons.SEARCH, "찾기", false),
                    new Dock.ToolSlot(Icons.SAVE, "저장", isModified)
            };
        }
        if ("pdf".equals(currentKind)) {
            return new Dock.ToolSlot[]{
                    new Dock.ToolSlot(Icons.PEN, "마커", "markup".equals(activeTool)),
                    new Dock.ToolSlot(Icons.PAGES, "페이지", false),
                    new Dock.ToolSlot(Icons.OCR, "OCR", !currentOcrBoxes.isEmpty()),
                    new Dock.ToolSlot(Icons.FIT, "맞춤", false),
                    new Dock.ToolSlot(Icons.SAVE, "저장", isModified)
            };
        }
        if ("media".equals(currentKind)) {
            return new Dock.ToolSlot[]{
                    new Dock.ToolSlot(Icons.PREV, "이전", false),
                    new Dock.ToolSlot(Icons.NEXT, "다음", false),
                    new Dock.ToolSlot(Icons.BOOKMARK, bookmarks.contains(currentPath) ? "저장됨" : "책갈피", bookmarks.contains(currentPath)),
                    new Dock.ToolSlot(Icons.SHARE, "공유", false),
                    new Dock.ToolSlot(Icons.MORE, "더보기", false)
            };
        }
        // Image mode
        boolean editOn = "crop".equals(activeTool) || "adjust".equals(activeTool) || "alpha".equals(activeTool) || "select".equals(activeTool);
        return new Dock.ToolSlot[]{
                new Dock.ToolSlot(Icons.PEN, "마커", "markup".equals(activeTool)),
                new Dock.ToolSlot(Icons.CROP, "편집", editOn),
                new Dock.ToolSlot(Icons.OCR, "OCR", !currentOcrBoxes.isEmpty()),
                new Dock.ToolSlot(Icons.FIT, "맞춤", false),
                new Dock.ToolSlot(Icons.SAVE, "저장", isModified)
        };
    }

    /** Floating sub-tool glass pill shown when Crop / Markup / Alpha / Select / Doc Edit is active. */
    private void refreshSubToolBar() {
        if (stagePage == null) return;
        LinearLayout row = stagePage.subToolRow;
        row.removeAllViews();
        if (!hasOpenFile() || tab != 1) {
            stagePage.subToolHost.setVisibility(View.GONE);
            return;
        }
        String tool = stageCanvasView.getActiveTool();
        if ("markup".equals(tool)) {
            stagePage.subToolHost.setVisibility(View.VISIBLE);
            String cur = stageCanvasView.getMarkupKind();
            String[][] kinds = {{"pen", "펜"}, {"hl", "형광"}, {"arrow", "화살"}, {"rect", "사각"}, {"ellipse", "원"}, {"text", "글자"}, {"sign", "서명"}, {"redact", "가림"}, {"select", "선택"}};
            for (String[] k : kinds) {
                row.addView(subChip(k[1], k[0].equals(cur), v -> { applyMarkupKind(k[0]); refreshSubToolBar(); }));
            }
            int[] colors = {0xFFE53935, 0xFF0381FE, 0xFF28B457, 0xFFF2C94C, 0xFF111111, 0xFFFFFFFF};
            for (int col : colors) {
                View sw = new View(this);
                GradientDrawable d = Ui.round(col, 100);
                d.setStroke(Ui.dp(stageCanvasView.getMarkupColor() == col ? 2.5f : 1f),
                        stageCanvasView.getMarkupColor() == col ? Ui.ACCENT : 0x55888888);
                sw.setBackground(d);
                sw.setOnClickListener(v -> { applyMarkupColor(col); refreshSubToolBar(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(24), Ui.dp(24));
                lp.leftMargin = Ui.dp(4); lp.rightMargin = Ui.dp(4);
                row.addView(sw, lp);
            }
            row.addView(subChip("취소", false, v -> { if ("pdf".equals(currentKind)) undoPdf(false); else undoImageEdit(); }));
            row.addView(subChip("완료", true, v -> setTool("hand")));
            return;
        }
        if ("crop".equals(tool)) {
            stagePage.subToolHost.setVisibility(View.VISIBLE);
            row.addView(subChip("자유", false, v -> stageCanvasView.setCropAspect(0f)));
            row.addView(subChip("1:1", false, v -> stageCanvasView.setCropAspect(1f)));
            row.addView(subChip("4:3", false, v -> stageCanvasView.setCropAspect(4f / 3f)));
            row.addView(subChip("16:9", false, v -> stageCanvasView.setCropAspect(16f / 9f)));
            row.addView(subChip("자르기 적용", true, v -> applyCrop()));
            row.addView(subChip("취소", false, v -> setTool("hand")));
            return;
        }
        if ("alpha".equals(tool)) {
            stagePage.subToolHost.setVisibility(View.VISIBLE);
            row.addView(subChip("배경 터치하여 제거", false, null));
            row.addView(subChip(alphaContiguous ? "인접 영역" : "전체 동일색", true, v -> { alphaContiguous = !alphaContiguous; refreshSubToolBar(); }));
            row.addView(subChip("완료", false, v -> setTool("hand")));
            return;
        }
        if ("select".equals(tool)) {
            stagePage.subToolHost.setVisibility(View.VISIBLE);
            row.addView(subChip("영역 복사", true, v -> copyCurrentImage()));
            row.addView(subChip("완료", false, v -> setTool("hand")));
            return;
        }
        if ("doc".equals(currentKind) && nativeDocView.isEditing()) {
            stagePage.subToolHost.setVisibility(View.VISIBLE);
            row.addView(subChip("실행 취소", false, v -> nativeDocView.undo()));
            row.addView(subChip("다시 실행", false, v -> nativeDocView.redo()));
            row.addView(subChip("편집 완료", true, v -> { nativeDocView.setEditing(false); syncChromeState(); }));
            return;
        }
        stagePage.subToolHost.setVisibility(View.GONE);
    }

    private TextView subChip(String label, boolean active, View.OnClickListener click) {
        TextView tv = Ui.text(this, label, 12.5f, active ? Ui.bold : Ui.semibold, active ? 0xFFFFFFFF : Ui.text());
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.dp(12), Ui.dp(6), Ui.dp(12), Ui.dp(6));
        tv.setBackground(Ui.round(active ? Ui.ACCENT : Ui.chip(), 100));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(32));
        lp.leftMargin = Ui.dp(3); lp.rightMargin = Ui.dp(3);
        tv.setLayoutParams(lp);
        if (click != null) { tv.setOnClickListener(click); Ui.press(tv, 0.94f); }
        return tv;
    }

    private void applyCrop() {
        Bitmap src = stageCanvasView.getImageBitmap();
        RectF r = stageCanvasView.getCropRect();
        if (src == null || r == null || r.width() < 2 || r.height() < 2) { setTool("hand"); return; }
        pushImageUndo();
        Bitmap cropped = ImageEditOps.crop(src, r);
        stageCanvasView.setImageContent(cropped, null, stageCanvasView.getImageAnnotations(), true);
        markModified();
        setTool("hand");
        toast("이미지를 잘랐습니다");
    }

    // ---- Dock.Listener ----
    @Override public void onTab(int index, boolean reselect) {
        if (searching) { onExitSearch(); dock.select(index, true); }
        if (reselect) {
            if (pages[index] != null) pages[index].scrollToTop();
            return;
        }
        switchTab(index);
    }

    @Override public void onOpenCurrentViewer() {
        if (hasOpenFile()) switchTab(1);
    }

    @Override public void onMiniQuickAction(int which, RectF anchor) {
        if (which == Dock.QUICK_SAVE) saveCurrentFile(false);
        else if (which == Dock.QUICK_CLOSE) closeCurrentFile();
    }

    @Override public void onToolAction(int slotIndex, RectF anchor) {
        if (!hasOpenFile()) return;
        if ("doc".equals(currentKind)) {
            switch (slotIndex) {
                case 0:
                    if (!nativeDocView.canEdit()) { toast("이 문서 형식은 읽기 전용입니다"); return; }
                    nativeDocView.setEditing(!nativeDocView.isEditing());
                    syncChromeState();
                    break;
                case 1: nativeDocView.undo(); break;
                case 2: showZoomMenu(anchor); break;
                case 3: onSearch(); break;
                case 4: saveCurrentFile(false); break;
            }
            return;
        }
        if ("media".equals(currentKind)) {
            switch (slotIndex) {
                case 0: navigateFile(-1); break;
                case 1: navigateFile(1); break;
                case 2: toggleBookmark(currentPath); break;
                case 3: shareCurrentFile(); break;
                case 4: showOverflowGlassMenu(anchor); break;
            }
            return;
        }
        if ("pdf".equals(currentKind)) {
            switch (slotIndex) {
                case 0:
                    if ("markup".equals(stageCanvasView.getActiveTool())) setTool("hand");
                    else showMarkupMenu(anchor);
                    break;
                case 1: showPdfMenu(anchor); break;
                case 2: showOcrMenu(anchor); break;
                case 3: showZoomMenu(anchor); break;
                case 4: showSaveExportMenu(anchor); break;
            }
            return;
        }
        // Image mode
        switch (slotIndex) {
            case 0:
                if ("markup".equals(stageCanvasView.getActiveTool())) setTool("hand");
                else showMarkupMenu(anchor);
                break;
            case 1: showImageEditMenu(anchor); break;
            case 2: showOcrMenu(anchor); break;
            case 3: showZoomMenu(anchor); break;
            case 4: showSaveExportMenu(anchor); break;
        }
    }

    @Override public void onSearch() {
        if (searchPage == null) {
            searchPage = new Pages.SearchPage(this);
            pageHost.addView(searchPage, match());
        }
        searching = true;
        searchPage.setVisibility(View.VISIBLE);
        searchPage.show();
        searchPage.query("", false);
        searchPage.setAlpha(0f);
        searchPage.setTranslationY(Ui.dp(16));
        searchPage.animate().alpha(1f).translationY(0).setDuration(320).setInterpolator(Ui.EMPHASIZED).start();
        dock.setSearchMode(true);
        syncChromeState();
    }

    @Override public void onExitSearch() {
        searching = false;
        dock.setSearchMode(false);
        if (searchPage != null) {
            Pages.SearchPage s = searchPage;
            s.animate().alpha(0f).setDuration(200).withEndAction(() -> s.setVisibility(View.GONE)).start();
        }
        header.post(this::syncChromeState);
    }

    @Override public void onQuery(String q, boolean submit) {
        if (searchPage != null) searchPage.query(q, submit);
    }

    // ---- Tablet Sidebar (exact Prism) ----
    @Override public void onSidebar() { if (sideP > 0.5f) closeSidebar(); else openSidebar(true); }

    void openSidebar(boolean animate) {
        if (!tabletUi()) return;
        if (searching) onExitSearch();
        sidebarWanted = true;
        int W = root.getWidth() > 0 ? root.getWidth() : Ui.screenW;
        float sw = Sidebar.widthFor(W);
        if (sidebar == null) {
            sidebar = new Sidebar(this);
            root.addView(sidebar, root.indexOfChild(backdrop) + 1, new FrameLayout.LayoutParams((int) sw, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        sidebar.getLayoutParams().width = (int) sw;
        sidebar.setSelected(tab);
        sidebar.setVisibility(View.VISIBLE);
        setContentInset((int) sw);
        animateSidebar(1f, animate);
    }

    void closeSidebar() {
        sidebarWanted = false;
        if (sidebar == null || sideP <= 0f) return;
        animateSidebar(0f, true);
    }

    private void animateSidebar(float to, boolean animate) {
        if (sideAnim != null) sideAnim.cancel();
        if (!animate) { applySidebar(to); if (to == 0f) finishCloseSidebar(); return; }
        sideAnim = Ui.animate(sideP, to, to > 0 ? 440 : 360, Ui.EMPHASIZED, a -> applySidebar((float) a.getAnimatedValue()));
        sideAnim.addListener(new AnimatorListenerAdapter() {
            boolean canceled;
            @Override public void onAnimationCancel(Animator a) { canceled = true; }
            @Override public void onAnimationEnd(Animator a) { if (!canceled && to == 0f) finishCloseSidebar(); }
        });
    }

    private void applySidebar(float p) {
        sideP = p;
        float sw = sidebar == null ? 0 : sidebar.getLayoutParams().width;
        if (sidebar != null) sidebar.setTranslationX(-sw * (1f - p));
        float dx = -sw * (1f - p);
        pageHost.setTranslationX(dx);
        header.setTranslationX(dx);
        dock.setSidebar(sw, p);
    }

    private void finishCloseSidebar() {
        sideP = 0f;
        if (sidebar != null) sidebar.setVisibility(View.GONE);
        setContentInset(0);
        pageHost.setTranslationX(0);
        header.setTranslationX(0);
        dock.setSidebar(0, 0);
    }

    private void setContentInset(int px) {
        if (px == contentInset) return;
        contentInset = px;
        ((FrameLayout.LayoutParams) pageHost.getLayoutParams()).leftMargin = px;
        pageHost.requestLayout();
        ((FrameLayout.LayoutParams) header.getLayoutParams()).leftMargin = px;
        header.requestLayout();
    }

    void sidebarSearch() {
        closeSidebar();
        root.postDelayed(this::onSearch, 280);
    }

    void sidebarTab(int i) { onTab(i, i == tab); }

    // ---- Header.Listener ----
    @Override public void onBack() {
        if (tab == 1) switchTab(0);
    }

    @Override public void onHeaderButton(int icon, RectF anchor) {
        if (icon == Icons.OPEN_FILE) { launchSystemFilePicker(); return; }
        if (icon == Icons.FOLDER) { chooseFolder(); return; }
        if (icon == Icons.INFO) { showFileInfoDialog(); return; }
        if (icon == Icons.DOWNLOAD) { Updater.checkInteractive(this); return; }
        if (icon == Icons.SORT) { showSortGlassMenu(anchor); return; }
        if (icon == Icons.MORE) { showOverflowGlassMenu(anchor); }
    }

    // ---- Drop-Morphing GlassMenu Palettes ----
    GlassMenu showOverflowGlassMenu(RectF anchor) {
        List<GlassMenu.Entry> list = new ArrayList<>();
        list.add(new GlassMenu.Entry("파일 열기", Icons.OPEN_FILE, false, this::launchSystemFilePicker));
        list.add(new GlassMenu.Entry("폴더 열기", Icons.FOLDER, false, this::chooseFolder));
        list.add(new GlassMenu.Entry("화면 캡처", Icons.CAPTURE, false, this::launchScreenCapture));
        list.add(new GlassMenu.Entry("붙여넣기", Icons.PASTE, false, this::pasteClipboardImage));
        if (hasOpenFile()) {
            list.add(new GlassMenu.Entry("저장", Icons.SAVE, isModified, () -> saveCurrentFile(false)));
            list.add(new GlassMenu.Entry("다른 이름으로 저장 · 변환", Icons.EXPORT, false, () -> showExportGlassMenu(anchor)));
            if ("image".equals(currentKind) || "pdf".equals(currentKind)) {
                list.add(new GlassMenu.Entry("이미지 복사", Icons.COPY, false, this::copyCurrentImage));
            }
            list.add(new GlassMenu.Entry("인쇄", Icons.PRINT, false, this::printCurrentFile));
            list.add(new GlassMenu.Entry("공유", Icons.SHARE, false, this::shareCurrentFile));
            boolean bm = bookmarks.contains(currentPath);
            list.add(new GlassMenu.Entry(bm ? "책갈피 제거" : "책갈피 추가", bm ? Icons.BOOKMARK_FILL : Icons.BOOKMARK, bm, () -> toggleBookmark(currentPath)));
            if (folderItems.size() > 1) list.add(new GlassMenu.Entry("슬라이드쇼", Icons.SLIDESHOW, false, this::startSlideshow));
            list.add(new GlassMenu.Entry("이름 바꾸기", Icons.EDIT, false, this::showRenameDialog));
            list.add(new GlassMenu.Entry("삭제", Icons.TRASH, false, this::showDeleteConfirmDialog));
            list.add(new GlassMenu.Entry("파일 닫기", Icons.CLOSE, false, this::closeCurrentFile));
        }
        return GlassMenu.show(this, anchor, list);
    }

    private void showSortGlassMenu(RectF anchor) {
        List<GlassMenu.Entry> list = Arrays.asList(
                new GlassMenu.Entry("이름순", Icons.SORT, "name".equals(sortOrder), () -> { sortOrder = "name"; sortFolderItems(); if (filesPage != null) filesPage.refresh(); }).checkable(),
                new GlassMenu.Entry("최신 수정순", Icons.CLOCK, "date".equals(sortOrder), () -> { sortOrder = "date"; sortFolderItems(); if (filesPage != null) filesPage.refresh(); }).checkable(),
                new GlassMenu.Entry("파일 크기순", Icons.FILES, "size".equals(sortOrder), () -> { sortOrder = "size"; sortFolderItems(); if (filesPage != null) filesPage.refresh(); }).checkable()
        );
        GlassMenu.show(this, anchor, list);
    }

    private void applyMarkupKind(String kind) {
        stageCanvasView.setMarkupOptions(kind, stageCanvasView.getMarkupColor(),
                stageCanvasView.getMarkupWidth(), stageCanvasView.getMarkupFontSize(), stageCanvasView.isMarkupFill());
    }

    private void applyMarkupColor(int color) {
        stageCanvasView.setMarkupOptions(stageCanvasView.getMarkupKind(), color,
                stageCanvasView.getMarkupWidth(), stageCanvasView.getMarkupFontSize(), stageCanvasView.isMarkupFill());
    }

    private void showMarkupMenu(RectF anchor) {
        List<GlassMenu.Entry> list = Arrays.asList(
                new GlassMenu.Entry("펜 그리기", Icons.PEN, false, () -> { applyMarkupKind("pen"); setTool("markup"); }),
                new GlassMenu.Entry("형광펜", Icons.HIGHLIGHT, false, () -> { applyMarkupKind("hl"); setTool("markup"); }),
                new GlassMenu.Entry("화살표 · 도형", Icons.SHAPES, false, () -> { applyMarkupKind("arrow"); setTool("markup"); }),
                new GlassMenu.Entry("텍스트 주석", Icons.TEXT_ADD, false, () -> { applyMarkupKind("text"); setTool("markup"); }),
                new GlassMenu.Entry("서명 추가", Icons.SIGN, false, () -> { applyMarkupKind("sign"); setTool("markup"); }),
                new GlassMenu.Entry("가리기 (리댁트)", Icons.CROP, false, () -> { applyMarkupKind("redact"); setTool("markup"); })
        );
        GlassMenu.show(this, anchor, list);
    }

    private void showImageEditMenu(RectF anchor) {
        List<GlassMenu.Entry> list = Arrays.asList(
                new GlassMenu.Entry("자르기", Icons.CROP, "crop".equals(stageCanvasView.getActiveTool()), () -> setTool("crop")),
                new GlassMenu.Entry("색상 보정", Icons.ADJUST, "adjust".equals(stageCanvasView.getActiveTool()), () -> setTool("adjust")),
                new GlassMenu.Entry("배경 투명 제거 (알파)", Icons.ALPHA, "alpha".equals(stageCanvasView.getActiveTool()), () -> setTool("alpha")),
                new GlassMenu.Entry("오른쪽 90° 회전", Icons.ROTATE_R, false, () -> rotateCurrentImage(90)),
                new GlassMenu.Entry("왼쪽 90° 회전", Icons.ROTATE_L, false, () -> rotateCurrentImage(-90)),
                new GlassMenu.Entry("좌우 반전", Icons.FLIP_H, false, () -> flipCurrentImage(true, false)),
                new GlassMenu.Entry("상하 반전", Icons.FLIP_V, false, () -> flipCurrentImage(false, true)),
                new GlassMenu.Entry("크기 조절", Icons.RESIZE, false, this::showResizeImageDialog),
                new GlassMenu.Entry("영역 선택", Icons.SHAPES, "select".equals(stageCanvasView.getActiveTool()), () -> setTool("select")),
                new GlassMenu.Entry("실행 취소", Icons.UNDO, false, this::undoImageEdit),
                new GlassMenu.Entry("다시 실행", Icons.REDO, false, this::redoImageEdit)
        );
        GlassMenu.show(this, anchor, list);
    }

    private void showPdfMenu(RectF anchor) {
        String vm = stageCanvasView.getPdfViewMode();
        List<GlassMenu.Entry> list = Arrays.asList(
                new GlassMenu.Entry("페이지 이동", Icons.PAGES, false, this::showPdfJumpPageDialog),
                new GlassMenu.Entry("페이지 정리 · 삭제", Icons.SORT, false, this::showPdfPageOrganizeDialog),
                new GlassMenu.Entry("현재 페이지 90° 회전", Icons.ROTATE_R, false, () -> rotateCurrentPdfPage(90)),
                new GlassMenu.Entry("빈 페이지 추가", Icons.OPEN_FILE, false, this::insertBlankPdfPage),
                new GlassMenu.Entry("파일에서 페이지 삽입", Icons.FOLDER, false, this::launchInsertPagePicker),
                new GlassMenu.Entry("연속 스크롤 보기", Icons.PDF, "cont".equals(vm), () -> { stageCanvasView.setPdfViewMode("cont"); syncChromeState(); }),
                new GlassMenu.Entry("한 페이지 보기", Icons.PDF, "single".equals(vm), () -> { stageCanvasView.setPdfViewMode("single"); syncChromeState(); }),
                new GlassMenu.Entry("두 페이지 보기", Icons.PDF, "two".equals(vm), () -> { stageCanvasView.setPdfViewMode("two"); syncChromeState(); }),
                new GlassMenu.Entry("실행 취소", Icons.UNDO, false, () -> undoPdf(false)),
                new GlassMenu.Entry("다시 실행", Icons.REDO, false, () -> undoPdf(true))
        );
        GlassMenu.show(this, anchor, list);
    }

    private void showOcrMenu(RectF anchor) {
        List<GlassMenu.Entry> list = new ArrayList<>();
        list.add(new GlassMenu.Entry("텍스트 인식 및 검색", Icons.SEARCH, false, this::onSearch));
        list.add(new GlassMenu.Entry("전체 텍스트 추출 보기", Icons.OCR, false, this::showFullExtractedTextDialog));
        if (!currentOcrBoxes.isEmpty()) {
            list.add(new GlassMenu.Entry("이전 검색 결과", Icons.PREV, false, () -> stepSearchOrOcr(-1)));
            list.add(new GlassMenu.Entry("다음 검색 결과", Icons.NEXT, false, () -> stepSearchOrOcr(1)));
            list.add(new GlassMenu.Entry("강조 표시 지우기", Icons.CLOSE, false, () -> {
                currentOcrBoxes.clear(); activeOcrHit = -1; stageCanvasView.setOcrBoxes(null, -1); syncChromeState();
            }));
        }
        GlassMenu.show(this, anchor, list);
    }

    private void showZoomMenu(RectF anchor) {
        if ("doc".equals(currentKind)) {
            List<GlassMenu.Entry> list = Arrays.asList(
                    new GlassMenu.Entry("확대 (+15%)", Icons.ZOOM_IN, false, () -> { nativeDocView.setZoomScale(nativeDocView.getZoomScale() + 0.15f); syncChromeState(); }),
                    new GlassMenu.Entry("축소 (-15%)", Icons.ZOOM_OUT, false, () -> { nativeDocView.setZoomScale(nativeDocView.getZoomScale() - 0.15f); syncChromeState(); }),
                    new GlassMenu.Entry("기본 배율 (100%)", Icons.FIT, false, () -> { nativeDocView.setZoomScale(1f); syncChromeState(); })
            );
            GlassMenu.show(this, anchor, list);
            return;
        }
        List<GlassMenu.Entry> list = Arrays.asList(
                new GlassMenu.Entry("화면에 맞춤", Icons.FIT, false, () -> { stageCanvasView.fitToWindow(); syncChromeState(); }),
                new GlassMenu.Entry("원본 크기 (100%)", Icons.RESIZE, false, () -> { stageCanvasView.setZoom(1f); syncChromeState(); }),
                new GlassMenu.Entry("확대 (+25%)", Icons.ZOOM_IN, false, () -> { stageCanvasView.setZoom(stageCanvasView.getZoom() * 1.25f); syncChromeState(); }),
                new GlassMenu.Entry("축소 (-25%)", Icons.ZOOM_OUT, false, () -> { stageCanvasView.setZoom(stageCanvasView.getZoom() / 1.25f); syncChromeState(); })
        );
        GlassMenu.show(this, anchor, list);
    }

    private void showSaveExportMenu(RectF anchor) {
        List<GlassMenu.Entry> list = Arrays.asList(
                new GlassMenu.Entry("원본 위치에 저장", Icons.SAVE, isModified, () -> saveCurrentFile(false)),
                new GlassMenu.Entry("다른 이름으로 저장", Icons.FOLDER, false, () -> saveCurrentFile(true)),
                new GlassMenu.Entry("형식 변환 · 내보내기", Icons.EXPORT, false, () -> showExportGlassMenu(anchor)),
                new GlassMenu.Entry("인쇄", Icons.PRINT, false, this::printCurrentFile),
                new GlassMenu.Entry("공유", Icons.SHARE, false, this::shareCurrentFile)
        );
        GlassMenu.show(this, anchor, list);
    }

    private void showExportGlassMenu(RectF anchor) {
        List<GlassMenu.Entry> list = Arrays.asList(
                new GlassMenu.Entry("PNG 이미지 (.png)", Icons.VIEW, false, () -> exportCurrentToFormat(".png")),
                new GlassMenu.Entry("JPEG 고화질 (.jpg)", Icons.VIEW, false, () -> exportCurrentToFormat(".jpg")),
                new GlassMenu.Entry("WebP 이미지 (.webp)", Icons.VIEW, false, () -> exportCurrentToFormat(".webp")),
                new GlassMenu.Entry("PDF 문서 (.pdf)", Icons.PDF, false, () -> exportCurrentToFormat(".pdf")),
                new GlassMenu.Entry("TIFF 무손실 (.tiff)", Icons.VIEW, false, () -> exportCurrentToFormat(".tiff")),
                new GlassMenu.Entry("BMP / GIF / ICO", Icons.EXPORT, false, this::showMoreExportFormatsDialog)
        );
        GlassMenu.sheet(this, anchor, "형식 변환 · 내보내기", list);
    }

    // ---- Settings Pickers (Drop-Morph GlassMenu Sheets!) ----
    private RectF anchorForView(View v) {
        float[] o = Glass.abs(v);
        return new RectF(o[0], o[1], o[0] + v.getWidth(), o[1] + v.getHeight());
    }

    void pickTheme(View v) {
        String cur = prefs.getString("theme", prefs.getBoolean("system_theme", true) ? "system" : (prefs.getBoolean("dark_theme", true) ? "dark" : "light"));
        List<GlassMenu.Entry> list = Arrays.asList(
                new GlassMenu.Entry("시스템 설정 따르기", 0, "system".equals(cur), () -> applyThemePref("system")).checkable(),
                new GlassMenu.Entry("라이트 모드", 0, "light".equals(cur), () -> applyThemePref("light")).checkable(),
                new GlassMenu.Entry("다크 모드", 0, "dark".equals(cur), () -> applyThemePref("dark")).checkable()
        );
        GlassMenu.sheet(this, anchorForView(v), "테마 선택", list);
    }

    private void applyThemePref(String mode) {
        prefs.edit()
                .putString("theme", mode)
                .putBoolean("system_theme", "system".equals(mode))
                .putBoolean("dark_theme", "dark".equals(mode))
                .apply();
        recreateChromeForTheme();
    }

    void pickStyle(View v) {
        List<GlassMenu.Entry> list = Arrays.asList(
                new GlassMenu.Entry("A 스타일 (글래스 굴절)", Icons.VIEW, !Ui.gStyle, () -> {
                    prefs.edit().putString("app_style", "prism").apply();
                    recreateChromeForTheme();
                }).checkable(),
                new GlassMenu.Entry("G 스타일 (프로스트 블러)", Icons.SHAPES, Ui.gStyle, () -> {
                    prefs.edit().putString("app_style", "oneui").apply();
                    recreateChromeForTheme();
                }).checkable()
        );
        GlassMenu.sheet(this, anchorForView(v), "앱 디자인 스타일", list);
    }

    void pickAccent(View v) {
        String cur = Ui.accentKey(prefs);
        List<GlassMenu.Entry> list = new ArrayList<>();
        for (int i = 0; i < Ui.ACCENT_KEYS.length; i++) {
            final String key = Ui.ACCENT_KEYS[i];
            list.add(new GlassMenu.Entry(Ui.ACCENT_NAMES[i], 0, key.equals(cur), () -> {
                prefs.edit().putString("accent_color", key).apply();
                recreateChromeForTheme();
            }).checkable());
        }
        GlassMenu.sheet(this, anchorForView(v), "강조 색상", list);
    }

    void pickExportQuality(View v) {
        int[] vals = {70, 80, 90, 95, 100};
        List<GlassMenu.Entry> list = new ArrayList<>();
        for (int q : vals) {
            list.add(new GlassMenu.Entry(q + "%", 0, exportQuality == q, () -> {
                exportQuality = q;
                prefs.edit().putInt("export_quality", q).apply();
                if (settingsPage != null) settingsPage.rebuild();
            }).checkable());
        }
        GlassMenu.sheet(this, anchorForView(v), "JPEG 저장 품질", list);
    }

    void pickExportDpi(View v) {
        int[] vals = {96, 150, 200, 300};
        List<GlassMenu.Entry> list = new ArrayList<>();
        for (int d : vals) {
            list.add(new GlassMenu.Entry(d + " dpi", 0, exportDpi == d, () -> {
                exportDpi = d;
                prefs.edit().putInt("export_dpi", d).apply();
                if (settingsPage != null) settingsPage.rebuild();
            }).checkable());
        }
        GlassMenu.sheet(this, anchorForView(v), "PDF 내보내기 해상도", list);
    }

    void pickSlideInterval(View v) {
        int[] vals = {1, 3, 5, 10, 30};
        List<GlassMenu.Entry> list = new ArrayList<>();
        for (int s : vals) {
            list.add(new GlassMenu.Entry(s + "초", 0, slideshowIntervalSec == s, () -> {
                slideshowIntervalSec = s;
                prefs.edit().putInt("slide_sec", s).apply();
                if (settingsPage != null) settingsPage.rebuild();
            }).checkable());
        }
        GlassMenu.sheet(this, anchorForView(v), "슬라이드쇼 간격", list);
    }

    void refreshVisualTheme() {
        if (header != null) header.invalidate();
        if (dock != null) dock.invalidate();
    }

    void recreateChromeForTheme() {
        buildCompleteNativeUI();
    }

    // ---- Stage & Document Listener Binding ----
    private void bindCoreViewListeners() {
        stageCanvasView.setStageListener(new StageCanvasView.StageListener() {
            @Override public void onZoomChanged(float zoom) { syncChromeState(); }
            @Override public void onCursorInfo(String info) {}
            @Override public void onNavigateFile(int delta) { navigateFile(delta); }
            @Override public void onPdfPageChanged(int pageIndex, int totalPages) {
                syncChromeState();
                if (filesPage != null && tab == 2) filesPage.refresh();
            }
            @Override public void onAnnotationCreated(MarkupModel.Annotation ann, int pageIndex) {
                if ("image".equals(currentKind)) {
                    pushImageUndo();
                    stageCanvasView.getImageAnnotations().add(ann);
                } else if ("pdf".equals(currentKind)) {
                    List<MarkupModel.PdfPageItem> pages = stageCanvasView.getPdfPages();
                    if (pageIndex >= 0 && pageIndex < pages.size()) pages.get(pageIndex).anns.add(ann);
                }
                markModified();
                syncChromeState();
            }
            @Override public void onAnnotationSelected(MarkupModel.Annotation ann, int pageIndex) { refreshSubToolBar(); }
            @Override public void onAnnotationModified() { markModified(); syncChromeState(); }
            @Override public void onTextToolTap(float docX, float docY, int pageIndex) { showAddTextAnnotationDialog(docX, docY, pageIndex); }
            @Override public void onSignToolTap(float docX, float docY, int pageIndex) { showSignatureDialog(docX, docY, pageIndex); }
            @Override public void onAlphaTap(int imgX, int imgY) { performInstantAlphaAt(imgX, imgY); }
            @Override public void onSelectionChanged(RectF selRect) { refreshSubToolBar(); }
            @Override public void onLongPressContext(float screenX, float screenY) {
                RectF a = new RectF(screenX - Ui.dp(24), screenY - Ui.dp(24), screenX + Ui.dp(24), screenY + Ui.dp(24));
                showOverflowGlassMenu(a);
            }
        });

        nativeDocView.setDocListener(new NativeDocView.DocListener() {
            @Override public void onDocDirtyChanged(boolean dirty) { isModified = dirty; syncChromeState(); }
            @Override public void onSheetSelected(int sheetIdx, String sheetTitle) { syncChromeState(); }
            @Override public void onSearchHitsChanged(int currentIdx, int totalHits) {
                docHitIndex = currentIdx;
                docHitTotal = totalHits;
            }
        });
    }

    private void attachActiveStageView() {
        if (stageHostFrame == null) return;
        stageHostFrame.removeAllViews();
        if (stagePage != null) stagePage.emptyOverlay.setVisibility(hasOpenFile() ? View.GONE : View.VISIBLE);
        if (!hasOpenFile()) return;
        if ("image".equals(currentKind) || "pdf".equals(currentKind)) {
            detachFromParent(stageCanvasView);
            stageHostFrame.addView(stageCanvasView, match());
        } else if ("doc".equals(currentKind)) {
            detachFromParent(nativeDocView);
            nativeDocView.setPadding(0, Ui.statusInset + Ui.dp(62), sideInset(), bottomPadding());
            stageHostFrame.addView(nativeDocView, match());
        } else if ("media".equals(currentKind)) {
            detachFromParent(nativeMediaView);
            stageHostFrame.addView(nativeMediaView, match());
        }
    }

    void setTool(String tool) {
        if ("adjust".equals(stageCanvasView.getActiveTool()) && !"adjust".equals(tool)) resetColorAdjustments();
        stageCanvasView.setActiveTool(tool);
        if ("adjust".equals(tool)) showColorAdjustDialog();
        syncChromeState();
    }

    // ---- File Open / Intent / Folder / Capture / Camera / Sketch ----
    private boolean handleIncomingIntent(Intent intent) {
        if (intent == null) return false;
        List<Uri> incoming = new ArrayList<>();
        Uri data = intent.getData();
        if (data != null) incoming.add(data);
        if (intent.getClipData() != null) {
            for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                Uri uri = intent.getClipData().getItemAt(i).getUri();
                if (uri != null && !incoming.contains(uri)) incoming.add(uri);
            }
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null && !incoming.contains(uri)) incoming.add(uri);
        }
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (uris != null) for (Uri uri : uris) if (!incoming.contains(uri)) incoming.add(uri);
        }
        if (incoming.isEmpty()) return false;
        openUris(incoming, intent.getFlags());
        return true;
    }

    private File copyContentUriToInbox(Uri uri) {
        if (uri == null) return null;
        try { return documents.importFile(uri); }
        catch (Exception e) { toast("파일을 가져올 수 없습니다: " + e.getMessage()); return null; }
    }

    void openUris(List<Uri> uris, int flags) { openUris(uris, flags, () -> {}); }

    void openUris(List<Uri> uris, int flags, Runnable completed) {
        final List<Uri> files = FileDrop.localUris(uris);
        final Runnable finish = FileDrop.once(completed);
        if (files.isEmpty()) { finish.run(); return; }
        guardChanges(() -> {
            final int ticket = ++openTicket;
            try {
                filePool.execute(() -> {
                    List<FileEntry> entries = new ArrayList<>();
                    try {
                        for (Uri uri : files) {
                            if (ticket != openTicket || Thread.currentThread().isInterrupted()) return;
                            documents.retain(uri, flags);
                            File file = copyContentUriToInbox(uri);
                            if (file != null && !FileOps.kindOf(file.getName()).isEmpty()) {
                                entries.add(new FileEntry(file.getAbsolutePath(), file.getName(), FileOps.kindOf(file.getName()), file.length(), file.lastModified()));
                            }
                        }
                        mainHandler.post(() -> {
                            if (isFinishing() || isDestroyed() || ticket != openTicket) return;
                            if (entries.isEmpty()) { toast("열 수 있는 파일이 없습니다."); return; }
                            guardChanges(() -> {
                                folderItems.clear();
                                folderItems.addAll(entries);
                                openFileUnchecked(entries.get(0).path);
                            });
                        });
                    } finally {
                        finish.run();
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException closed) {
                finish.run();
            }
        }, finish);
    }

    private void guardChanges(Runnable action) { guardChanges(action, () -> {}); }

    private void guardChanges(Runnable action, Runnable cancelled) {
        if (!isModified) { action.run(); return; }
        new AlertDialog.Builder(this)
                .setTitle("변경 내용을 저장할까요?")
                .setMessage("저장하지 않은 편집 내용이 있습니다.")
                .setNegativeButton("계속 편집", (d, w) -> cancelled.run())
                .setOnCancelListener(d -> cancelled.run())
                .setNeutralButton("변경 버리기", (d, w) -> { isModified = false; action.run(); })
                .setPositiveButton("저장 후 계속", (d, w) -> { afterSave = action; afterSaveCancelled = cancelled; saveCurrentFile(false); })
                .show();
    }

    private void cancelAfterSave() {
        Runnable c = afterSaveCancelled;
        afterSave = afterSaveCancelled = null;
        if (c != null) c.run();
    }

    public void openFileAtPath(final String path) { guardChanges(() -> openFileUnchecked(path)); }

    private void openFileUnchecked(final String path) {
        if (path == null || path.isEmpty()) return;
        try { documents.ensureLocal(path); } catch (Exception e) { toast("파일을 읽을 수 없습니다: " + e.getMessage()); return; }
        final File f = new File(path);
        if (!f.exists()) { toast("파일이 존재하지 않습니다"); return; }

        nativeMediaView.stopMedia();
        searchTicket++; imageLoadTicket++; PdfHost.closeAll();
        currentPath = f.getAbsolutePath();
        currentKind = FileOps.kindOf(currentPath);
        isModified = false;
        flattenedPdfApproved = false;
        pdfUndo.clear(); pdfRedo.clear(); lastPdfKey = "";
        imgUndoStack.clear(); imgRedoStack.clear();
        currentOcrBoxes.clear(); activeOcrHit = -1; fullExtractedOcrText = "";

        boolean foundInFolder = false;
        for (int i = 0; i < folderItems.size(); i++) {
            if (folderItems.get(i).path.equalsIgnoreCase(currentPath)) {
                currentFileIndex = i;
                foundInFolder = true;
                break;
            }
        }
        if (!foundInFolder && f.getParentFile() != null) {
            File[] siblings = f.getParentFile().listFiles();
            if (siblings != null && siblings.length > 0) {
                folderItems.clear();
                for (File s : siblings) {
                    if (s.isFile() && !FileOps.kindOf(s.getName()).isEmpty()) {
                        folderItems.add(new FileEntry(s.getAbsolutePath(), s.getName(), FileOps.kindOf(s.getName()), s.length(), s.lastModified()));
                    }
                }
                sortFolderItems();
            }
        }

        attachActiveStageView();
        switchTab(1);

        if ("image".equals(currentKind)) {
            loadOriginalImage(currentPath);
        } else if ("pdf".equals(currentKind)) {
            stageCanvasView.clearContent();
            openPdfWithPassword(currentPath, "");
        } else if ("doc".equals(currentKind)) {
            nativeDocView.loadDocument(currentPath);
        } else if ("media".equals(currentKind)) {
            nativeMediaView.loadMedia(currentPath);
        }

        if (homePage != null) homePage.rebuild();
        if (filesPage != null) filesPage.refresh();
        syncChromeState();
    }

    private void loadOriginalImage(String path) {
        final int ticket = imageLoadTicket;
        stageCanvasView.clearContent();
        stageCanvasView.setActiveTool("hand");
        bgPool.execute(() -> {
            Bitmap bitmap = null;
            Movie movie = null;
            String error = null;
            try {
                bitmap = ImageEditOps.decodeOriginal(path);
                if (".gif".equals(Util.extOf(path))) {
                    try {
                        byte[] bytes = Util.readAllBytes(new File(path));
                        movie = Movie.decodeByteArray(bytes, 0, bytes.length);
                        if (movie != null && movie.duration() <= 0) movie = null;
                    } catch (Exception | OutOfMemoryError ignored) {}
                }
            } catch (Exception failure) {
                error = failure.getMessage();
            }
            final Bitmap ready = bitmap;
            final Movie gif = movie;
            final String problem = error;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed() || ticket != imageLoadTicket || !path.equals(currentPath)) {
                    if (ready != null) ready.recycle();
                    return;
                }
                if (ready == null) { toast(problem == null ? "이미지를 열 수 없습니다." : problem); return; }
                stageCanvasView.setImageContent(ready, gif, new ArrayList<>(), true);
                syncChromeState();
                if (slideshowRunning) updateSlideshowView();
            });
        });
    }

    private void openPdfWithPassword(final String path, String password) {
        Map<String, Object> result = PdfHost.open(path, password);
        if (!Boolean.TRUE.equals(result.get("ok"))) {
            if (Boolean.TRUE.equals(result.get("locked"))) {
                final EditText input = new EditText(this);
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                input.setHint("PDF 암호");
                new AlertDialog.Builder(this).setTitle("암호로 보호된 PDF").setMessage(String.valueOf(result.get("error")))
                        .setView(input).setNegativeButton("취소", null)
                        .setPositiveButton("열기", (d, w) -> { if (path.equals(currentPath)) openPdfWithPassword(path, input.getText().toString()); })
                        .show();
            } else toast(String.valueOf(result.get("error")));
            return;
        }
        int count = PdfHost.getPageCount(path);
        List<MarkupModel.PdfPageItem> pages = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            float[] size = PdfHost.getPageSizePt(path, i);
            pages.add(new MarkupModel.PdfPageItem("p_" + i, "pdf", path, i, size[0], size[1]));
        }
        stageCanvasView.setActiveTool("hand");
        stageCanvasView.setPdfContent(pages, stageCanvasView.getPdfViewMode(), 0);
        recordPdfState();
        if (filesPage != null) filesPage.refresh();
        syncChromeState();
    }

    void closeCurrentFile() {
        guardChanges(() -> {
            openTicket++; imageLoadTicket++; searchTicket++;
            nativeMediaView.stopMedia();
            PdfHost.closeAll();
            stageCanvasView.clearContent();
            stageCanvasView.setActiveTool("hand");
            currentPath = "";
            currentKind = "";
            folderItems.clear();
            currentFileIndex = -1;
            isModified = false;
            imgUndoStack.clear(); imgRedoStack.clear();
            pdfUndo.clear(); pdfRedo.clear();
            attachActiveStageView();
            switchTab(0);
            if (homePage != null) homePage.rebuild();
            if (filesPage != null) filesPage.refresh();
            syncChromeState();
        });
    }

    void openSampleFiles() {
        guardChanges(() -> {
            SampleSeeder.ensureSeeded(sampleDir);
            loadFolderList(sampleDir.getAbsolutePath(), null);
        });
    }

    void createBlankSketch() {
        guardChanges(() -> {
            try {
                File f = new File(sampleDir, "Sketch_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png");
                Bitmap bmp = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                FileOps.saveBitmap(f.getAbsolutePath(), bmp, 100);
                bmp.recycle();
                openFileUnchecked(f.getAbsolutePath());
                setTool("markup");
            } catch (Exception e) {
                toast("새 스케치를 만들 수 없습니다: " + e.getMessage());
            }
        });
    }

    List<File> collectRecentFiles() {
        List<File> out = new ArrayList<>();
        File[] dirs = {inboxDir, sampleDir, new File(getFilesDir(), "captures"), new File(getFilesDir(), "backups")};
        for (File d : dirs) {
            if (d == null || !d.isDirectory()) continue;
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (f.isFile() && !f.getName().startsWith(".") && !FileOps.kindOf(f.getName()).isEmpty()) out.add(f);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    private void loadFolderList(String dirPath, String selectFilePath) {
        folderItems.clear();
        File dir = new File(dirPath);
        if (dir.exists() && dir.isFile()) { selectFilePath = dir.getAbsolutePath(); dir = dir.getParentFile(); }
        if (dir != null && dir.exists() && dir.isDirectory()) {
            File[] raw = dir.listFiles();
            if (raw != null) {
                for (File f : raw) {
                    if (f.isFile() && !f.getName().startsWith(".")) {
                        String k = FileOps.kindOf(f.getName());
                        if (!k.isEmpty()) folderItems.add(new FileEntry(f.getAbsolutePath(), f.getName(), k, f.length(), f.lastModified()));
                    }
                }
            }
        }
        sortFolderItems();
        if (selectFilePath != null) openFileAtPath(selectFilePath);
        else if (!folderItems.isEmpty()) openFileAtPath(folderItems.get(0).path);
    }

    private void sortFolderItems() {
        Collections.sort(folderItems, (a, b) -> {
            if ("date".equals(sortOrder)) return Long.compare(b.lastModified, a.lastModified);
            if ("size".equals(sortOrder)) return Long.compare(b.size, a.size);
            return Util.NATURAL_SORT.compare(a.name, b.name);
        });
        currentFileIndex = -1;
        for (int i = 0; i < folderItems.size(); i++) {
            if (folderItems.get(i).path.equalsIgnoreCase(currentPath)) { currentFileIndex = i; break; }
        }
    }

    private void navigateFile(int delta) {
        if (folderItems.isEmpty()) return;
        int nextIdx = (currentFileIndex + delta + folderItems.size()) % folderItems.size();
        openFileAtPath(folderItems.get(nextIdx).path);
        if (slideshowRunning) updateSlideshowView();
    }

    void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQ_OPEN_TREE);
    }

    private void showTreeFolder(Uri tree, String parentId) {
        bgPool.execute(() -> {
            try {
                List<DocumentStore.Entry> entries = documents.children(tree, parentId);
                List<String> labels = new ArrayList<>();
                for (DocumentStore.Entry entry : entries) labels.add((entry.directory ? "폴더 · " : "") + entry.name);
                mainHandler.post(() -> new AlertDialog.Builder(this).setTitle("선택한 폴더")
                        .setItems(labels.toArray(new String[0]), (d, w) -> {
                            DocumentStore.Entry chosen = entries.get(w);
                            if (chosen.directory) { showTreeFolder(tree, DocumentsContract.getDocumentId(chosen.uri)); return; }
                            guardChanges(() -> {
                                try {
                                    folderItems.clear();
                                    String selected = "";
                                    for (DocumentStore.Entry entry : entries) {
                                        if (!entry.directory) {
                                            String path = documents.register(entry.uri, entry.name);
                                            folderItems.add(new FileEntry(path, entry.name, FileOps.kindOf(entry.name), entry.size, entry.modified));
                                            if (entry == chosen) selected = path;
                                        }
                                    }
                                    sortFolderItems();
                                    openFileAtPath(selected);
                                } catch (Exception e) { toast(e.getMessage()); }
                            });
                        }).setNegativeButton("닫기", null).setNeutralButton("폴더 다시 선택", (d, w) -> chooseFolder()).show());
            } catch (Exception e) { toast("폴더를 열 수 없습니다: " + e.getMessage()); }
        });
    }

    void launchSystemFilePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, REQ_OPEN_DOC);
        } catch (Exception e) { toast("파일 선택기를 열 수 없습니다: " + e.getMessage()); }
    }

    private void launchInsertPagePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_INSERT_PAGE_DOC);
        } catch (Exception e) { toast("파일 선택기를 열 수 없습니다: " + e.getMessage()); }
    }

    void launchScreenCapture() {
        if (capturePending) return;
        guardChanges(() -> {
            try {
                MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                capturePending = true;
                startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE);
            } catch (Exception e) {
                capturePending = false;
                toast("화면 캡처를 시작할 수 없습니다.");
            }
        });
    }

    private void consumeCapture() {
        String path = prefs.getString(ScreenCaptureService.RESULT_PATH, "");
        if (path.isEmpty()) return;
        prefs.edit().remove(ScreenCaptureService.RESULT_PATH).apply();
        capturePending = false;
        if (new File(path).isFile()) openFileAtPath(path);
    }

    void launchCameraCapture() {
        try {
            pendingCameraFile = new File(sampleDir, "Camera_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg");
            Uri uri = ViewerFileProvider.getUriForFile(this, pendingCameraFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, REQ_CAMERA);
        } catch (Exception e) { toast("카메라를 실행할 수 없습니다: " + e.getMessage()); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE) {
            if (resultCode != Activity.RESULT_OK || data == null) { capturePending = false; return; }
            Intent capture = new Intent(this, ScreenCaptureService.class)
                    .putExtra(ScreenCaptureService.DATA, data)
                    .putExtra(ScreenCaptureService.RECEIVER, new ResultReceiver(mainHandler) {
                        @Override protected void onReceiveResult(int code, Bundle result) {
                            capturePending = false;
                            if (isDestroyed() || isFinishing()) return;
                            if (code == Activity.RESULT_OK) { if (activityResumed) consumeCapture(); }
                            else toast(result.getString("error", "화면 캡처를 완료하지 못했습니다."));
                        }
                    });
            try { startForegroundService(capture); }
            catch (Exception e) { capturePending = false; toast("화면 캡처를 시작하지 못했습니다."); }
            return;
        }
        if (resultCode != Activity.RESULT_OK) return;
        if (requestCode == REQ_OPEN_TREE && data != null && data.getData() != null) {
            documents.retain(data.getData(), data.getFlags());
            showTreeFolder(data.getData(), DocumentsContract.getTreeDocumentId(data.getData()));
            return;
        }
        if (requestCode == REQ_OPEN_DOC && data != null) { handleIncomingIntent(data); return; }
        if (requestCode == REQ_CAMERA) {
            if (pendingCameraFile != null && pendingCameraFile.exists() && pendingCameraFile.length() > 0) {
                loadFolderList(sampleDir.getAbsolutePath(), pendingCameraFile.getAbsolutePath());
            }
        } else if (requestCode == REQ_CREATE_DOC && data != null && data.getData() != null) {
            if (pendingExportBytes == null && pendingExportFile != null) {
                try { pendingExportBytes = Util.readAllBytes(pendingExportFile); } catch (Exception e) { toast("저장할 파일을 다시 준비해 주세요."); }
            }
            if (pendingExportBytes != null) {
                try (OutputStream os = getContentResolver().openOutputStream(data.getData(), "wt")) {
                    if (os != null) { os.write(pendingExportBytes); os.flush(); toast("선택한 위치에 저장했습니다"); }
                } catch (Exception e) { toast("저장 실패: " + e.getMessage()); }
                finally { pendingExportBytes = null; }
            }
        } else if (requestCode == REQ_INSERT_PAGE_DOC && data != null && data.getData() != null) {
            File f = copyContentUriToInbox(data.getData());
            if (f != null && "pdf".equals(currentKind)) {
                String k = FileOps.kindOf(f.getName());
                List<MarkupModel.PdfPageItem> pList = stageCanvasView.getPdfPages();
                int insertAt = Math.min(pList.size(), stageCanvasView.getCurrentPdfPage() + 1);
                if ("pdf".equals(k)) {
                    PdfHost.open(f.getAbsolutePath(), "");
                    int cnt = PdfHost.getPageCount(f.getAbsolutePath());
                    for (int i = 0; i < cnt; i++) {
                        float[] sz = PdfHost.getPageSizePt(f.getAbsolutePath(), i);
                        pList.add(insertAt + i, new MarkupModel.PdfPageItem("ins_" + System.currentTimeMillis() + "_" + i, "pdf", f.getAbsolutePath(), i, sz[0], sz[1]));
                    }
                    markModified();
                    stageCanvasView.setCurrentPdfPage(insertAt);
                    syncChromeState();
                    toast(cnt + "개의 PDF 페이지를 삽입했습니다");
                } else if ("image".equals(k)) {
                    try {
                        Bitmap bmp = ImageEditOps.decodeOriginal(f.getAbsolutePath());
                        if (bmp != null) {
                            float wPt = 595f, hPt = Math.max(200f, 595f * ((float) bmp.getHeight() / Math.max(1, bmp.getWidth())));
                            MarkupModel.PdfPageItem imgPage = new MarkupModel.PdfPageItem("img_" + System.currentTimeMillis(), "img", f.getAbsolutePath(), 0, wPt, hPt);
                            imgPage.customBitmap = bmp;
                            pList.add(insertAt, imgPage);
                            markModified();
                            stageCanvasView.setCurrentPdfPage(insertAt);
                            syncChromeState();
                            toast("이미지 페이지를 삽입했습니다");
                        }
                    } catch (Exception e) { toast(e.getMessage()); }
                }
            }
        }
    }

    // ---- Image / PDF Edits & Undo/Redo ----
    private void markModified() { isModified = true; if ("pdf".equals(currentKind)) recordPdfState(); }

    private String pdfKey() {
        StringBuilder key = new StringBuilder();
        for (MarkupModel.PdfPageItem p : stageCanvasView.getPdfPages()) {
            key.append(p.uid).append(':').append(p.rot).append('|');
            for (MarkupModel.Annotation a : p.anns) {
                key.append(a.id).append(a.type).append(a.color).append(a.strokeWidth).append(a.x).append(a.y).append(a.text);
            }
        }
        return key.toString();
    }

    private List<MarkupModel.PdfPageItem> copyPdfState() {
        List<MarkupModel.PdfPageItem> copy = new ArrayList<>();
        for (MarkupModel.PdfPageItem page : stageCanvasView.getPdfPages()) {
            MarkupModel.PdfPageItem p = new MarkupModel.PdfPageItem(page.uid, page.kind, page.srcPath, page.srcPageIdx, page.wPt, page.hPt);
            p.rot = page.rot; p.customBitmap = page.customBitmap; p.anns.addAll(MarkupModel.copyList(page.anns));
            copy.add(p);
        }
        return copy;
    }

    private void recordPdfState() {
        String key = pdfKey();
        if (key.equals(lastPdfKey)) return;
        lastPdfKey = key;
        pdfUndo.add(copyPdfState());
        pdfRedo.clear();
        if (pdfUndo.size() > 30) pdfUndo.remove(0);
    }

    private void undoPdf(boolean redo) {
        if (!"pdf".equals(currentKind)) return;
        if (redo) { if (pdfRedo.isEmpty()) return; pdfUndo.add(pdfRedo.remove(pdfRedo.size() - 1)); }
        else { if (pdfUndo.size() < 2) return; pdfRedo.add(pdfUndo.remove(pdfUndo.size() - 1)); }
        List<MarkupModel.PdfPageItem> state = pdfUndo.get(pdfUndo.size() - 1);
        stageCanvasView.setPdfContent(new ArrayList<>(state), stageCanvasView.getPdfViewMode(), Math.min(stageCanvasView.getCurrentPdfPage(), state.size() - 1));
        stageCanvasView.setPdfContent(copyPdfState(), stageCanvasView.getPdfViewMode(), stageCanvasView.getCurrentPdfPage());
        lastPdfKey = pdfKey();
        isModified = true;
        syncChromeState();
    }

    private void pushImageUndo() {
        if (stageCanvasView.getImageBitmap() == null) return;
        imgUndoStack.add(new ImageSnapshot(stageCanvasView.getImageBitmap(), stageCanvasView.getImageAnnotations()));
        long bytes = 0, budget = Math.max(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4);
        for (ImageSnapshot s : imgUndoStack) if (s.bmp != null) bytes += s.bmp.getAllocationByteCount();
        while (imgUndoStack.size() > 1 && (imgUndoStack.size() > 15 || bytes > budget)) {
            ImageSnapshot old = imgUndoStack.remove(0);
            if (old.bmp != null) bytes -= old.bmp.getAllocationByteCount();
        }
        imgRedoStack.clear();
    }

    void undoImageEdit() {
        if (imgUndoStack.isEmpty()) { toast("실행 취소할 내역이 없습니다"); return; }
        imgRedoStack.add(new ImageSnapshot(stageCanvasView.getImageBitmap(), stageCanvasView.getImageAnnotations()));
        ImageSnapshot prev = imgUndoStack.remove(imgUndoStack.size() - 1);
        stageCanvasView.setImageContent(prev.bmp, null, prev.anns, false);
        markModified();
        syncChromeState();
    }

    void redoImageEdit() {
        if (imgRedoStack.isEmpty()) { toast("다시 실행할 내역이 없습니다"); return; }
        imgUndoStack.add(new ImageSnapshot(stageCanvasView.getImageBitmap(), stageCanvasView.getImageAnnotations()));
        ImageSnapshot next = imgRedoStack.remove(imgRedoStack.size() - 1);
        stageCanvasView.setImageContent(next.bmp, null, next.anns, false);
        markModified();
        syncChromeState();
    }

    void rotateCurrentImage(int deg) {
        if (stageCanvasView.getImageBitmap() == null) return;
        pushImageUndo();
        Bitmap rotated = ImageEditOps.rotate(stageCanvasView.getImageBitmap(), deg);
        stageCanvasView.setImageContent(rotated, null, stageCanvasView.getImageAnnotations(), true);
        markModified();
        syncChromeState();
    }

    private void flipCurrentImage(boolean h, boolean v) {
        if (stageCanvasView.getImageBitmap() == null) return;
        pushImageUndo();
        Bitmap flipped = ImageEditOps.flip(stageCanvasView.getImageBitmap(), h, v);
        stageCanvasView.setImageContent(flipped, null, stageCanvasView.getImageAnnotations(), false);
        markModified();
        syncChromeState();
    }

    private void updateLiveAdjustPreview() {
        stageCanvasView.setLiveColorFilter(ImageEditOps.buildColorFilter(adjExp, adjCon, adjSat, adjWarm, adjSepia, adjInvert));
    }

    private void resetColorAdjustments() {
        adjExp = adjCon = adjSat = adjWarm = adjSharp = 0f;
        adjSepia = adjInvert = false;
        stageCanvasView.setLiveColorFilter(null);
    }

    private void commitColorAdjustments() {
        if (stageCanvasView.getImageBitmap() == null) return;
        pushImageUndo();
        Bitmap adjusted = ImageEditOps.applyAdjust(stageCanvasView.getImageBitmap(), adjExp, adjCon, adjSat, adjWarm, adjSharp, adjSepia, adjInvert);
        resetColorAdjustments();
        stageCanvasView.setImageContent(adjusted, null, stageCanvasView.getImageAnnotations(), false);
        markModified();
        setTool("hand");
        toast("색상 보정을 적용했습니다");
    }

    private void performInstantAlphaAt(int imgX, int imgY) {
        if (stageCanvasView.getImageBitmap() == null) return;
        pushImageUndo();
        Bitmap res = ImageEditOps.floodAlpha(stageCanvasView.getImageBitmap(), imgX, imgY, alphaTolerance, alphaContiguous);
        stageCanvasView.setImageContent(res, null, stageCanvasView.getImageAnnotations(), false);
        markModified();
        toast("배경을 투명하게 제거했습니다");
    }

    private void rotateCurrentPdfPage(int deltaDeg) {
        List<MarkupModel.PdfPageItem> pList = stageCanvasView.getPdfPages();
        int idx = stageCanvasView.getCurrentPdfPage();
        if (idx < 0 || idx >= pList.size()) return;
        MarkupModel.PdfPageItem p = pList.get(idx);
        p.rot = ((p.rot + deltaDeg) % 360 + 360) % 360;
        p.invalidateCache();
        markModified();
        stageCanvasView.fitToWindow();
        syncChromeState();
    }

    private void insertBlankPdfPage() {
        List<MarkupModel.PdfPageItem> pList = stageCanvasView.getPdfPages();
        int idx = stageCanvasView.getCurrentPdfPage();
        float w = 595f, h = 842f;
        if (idx >= 0 && idx < pList.size()) { w = pList.get(idx).wPt; h = pList.get(idx).hPt; }
        MarkupModel.PdfPageItem blank = new MarkupModel.PdfPageItem("blank_" + System.currentTimeMillis(), "blank", "", 0, w, h);
        int insertAt = Math.min(pList.size(), idx + 1);
        pList.add(insertAt, blank);
        markModified();
        stageCanvasView.setCurrentPdfPage(insertAt);
        syncChromeState();
        toast("빈 페이지를 추가했습니다");
    }

    // ---- Save / Export / Print / Share ----
    void saveCurrentFile(boolean saveAsSystemPicker) {
        if (currentPath.isEmpty() || saveRunning) return;
        if (!saveAsSystemPicker && isModified && "pdf".equals(currentKind) && !flattenedPdfApproved) {
            new AlertDialog.Builder(this).setTitle("편집한 PDF 저장")
                    .setMessage("페이지를 이미지로 저장해요. 기존 파일은 앱의 백업 폴더에 보관해요.")
                    .setNegativeButton("취소", (d, w) -> cancelAfterSave())
                    .setOnCancelListener(d -> cancelAfterSave())
                    .setPositiveButton("저장", (d, w) -> { flattenedPdfApproved = true; saveCurrentFile(false); })
                    .show();
            return;
        }
        final String path = currentPath, kind = currentKind;
        final boolean saveCopy = saveAsSystemPicker;
        final ProgressDialog progress = ProgressDialog.show(this, "저장", "파일을 준비하고 있어요…", true, false);
        saveRunning = true;
        bgPool.execute(() -> {
            try {
                File prepared = ViewerExports.snapshot(this, path, kind, stageCanvasView, nativeDocView, exportQuality, exportDpi, isModified);
                boolean needsPicker = saveCopy || !Util.extOf(prepared.getName()).equals(Util.extOf(path));
                if (!needsPicker) {
                    File backupDir = new File(getFilesDir(), "backups");
                    backupDir.mkdirs();
                    Files.copy(new File(path).toPath(), new File(backupDir, System.currentTimeMillis() + "_" + new File(path).getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                    File replacement = new File(path + ".save");
                    Files.copy(prepared.toPath(), replacement.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    Files.move(replacement.toPath(), new File(path).toPath(), StandardCopyOption.REPLACE_EXISTING);
                    documents.publish(path);
                }
                final boolean picker = needsPicker;
                final byte[] bytes = picker ? Util.readAllBytes(prepared) : null;
                mainHandler.post(() -> {
                    saveRunning = false;
                    progress.dismiss();
                    if (isFinishing()) return;
                    if (picker) { cancelAfterSave(); promptSystemSavePicker(prepared.getName(), bytes); return; }
                    isModified = false;
                    if ("doc".equals(kind)) nativeDocView.markSaved();
                    scanMediaFile(path);
                    syncChromeState();
                    toast("저장했습니다");
                    Runnable next = afterSave;
                    afterSave = afterSaveCancelled = null;
                    if (next != null) next.run();
                });
            } catch (Exception | OutOfMemoryError e) {
                mainHandler.post(() -> {
                    saveRunning = false; cancelAfterSave(); progress.dismiss();
                    toast(e instanceof OutOfMemoryError ? "메모리가 부족합니다." : "저장 실패: " + e.getMessage());
                });
            }
        });
    }

    private void promptSystemSavePicker(String defaultName, byte[] bytes) {
        try {
            pendingExportBytes = bytes;
            pendingExportFile = new File(getCacheDir(), "pending-export.bin");
            Util.writeAllBytes(pendingExportFile, bytes);
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(FileOps.mimeOf(Util.extOf(defaultName)));
            intent.putExtra(Intent.EXTRA_TITLE, defaultName);
            startActivityForResult(intent, REQ_CREATE_DOC);
        } catch (Exception e) { toast("시스템 저장 창을 열 수 없습니다: " + e.getMessage()); }
    }

    private void showMoreExportFormatsDialog() {
        String[] labels = {"BMP 비트맵 (.bmp)", "GIF 이미지 (.gif)", "ICO 아이콘 (.ico)"};
        String[] exts = {".bmp", ".gif", ".ico"};
        new AlertDialog.Builder(this).setTitle("추가 형식 변환").setItems(labels, (d, w) -> exportCurrentToFormat(exts[w])).setNegativeButton("취소", null).show();
    }

    private void exportCurrentToFormat(String targetExt) {
        try {
            File curFile = new File(currentPath);
            String baseName = curFile.getName();
            int dot = baseName.lastIndexOf('.');
            if (dot > 0) baseName = baseName.substring(0, dot);
            File outFile = new File(sampleDir, baseName + "_export" + targetExt);

            if (".pdf".equals(targetExt)) {
                String wid = "exp_" + System.currentTimeMillis();
                PdfHost.begin(wid, outFile.getAbsolutePath());
                if ("image".equals(currentKind) && stageCanvasView.getImageBitmap() != null) {
                    Bitmap flat = MarkupModel.flattenOntoBitmap(stageCanvasView.getImageBitmap(), stageCanvasView.getImageAnnotations());
                    float wPt = 595f, hPt = 595f * ((float) flat.getHeight() / Math.max(1, flat.getWidth()));
                    ViewerExports.require(PdfHost.addPageBitmap(wid, wPt, hPt, flat, exportQuality), "ok");
                    flat.recycle();
                } else if ("pdf".equals(currentKind)) {
                    for (MarkupModel.PdfPageItem p : stageCanvasView.getPdfPages()) {
                        Bitmap bmp = p.renderFlattened(ViewerExports.pdfWidth(p, exportDpi));
                        ViewerExports.require(PdfHost.addPageBitmap(wid, p.displayWidthPt(), p.displayHeightPt(), bmp, exportQuality), "ok");
                        bmp.recycle();
                    }
                } else if ("doc".equals(currentKind)) {
                    int width = Math.round(595f * exportDpi / 72), height = Math.round(842f * exportDpi / 72);
                    for (int page = 0; page < nativeDocView.printPageCount(width, height); page++) {
                        Bitmap docBmp = nativeDocView.printPage(page, width, height);
                        ViewerExports.require(PdfHost.addPageBitmap(wid, 595f, 842f, docBmp, exportQuality), "ok");
                        docBmp.recycle();
                    }
                }
                Map<String, Object> res = PdfHost.end(wid, outFile.getName());
                if (Boolean.TRUE.equals(res.get("ok"))) {
                    scanMediaFile(outFile.getAbsolutePath());
                    promptSystemSavePicker(outFile.getName(), Util.readAllBytes(outFile));
                }
                return;
            }
            Bitmap srcBmp = null;
            if ("image".equals(currentKind) && stageCanvasView.getImageBitmap() != null) {
                srcBmp = stageCanvasView.getImageAnnotations().isEmpty()
                        ? stageCanvasView.getImageBitmap()
                        : MarkupModel.flattenOntoBitmap(stageCanvasView.getImageBitmap(), stageCanvasView.getImageAnnotations());
            } else if ("pdf".equals(currentKind) && !stageCanvasView.getPdfPages().isEmpty()) {
                MarkupModel.PdfPageItem page = stageCanvasView.getPdfPages().get(stageCanvasView.getCurrentPdfPage());
                srcBmp = page.renderFlattened(ViewerExports.pdfWidth(page, exportDpi));
            }
            if (srcBmp == null) { toast("이미지로 변환할 수 없는 항목입니다"); return; }
            Map<String, Object> saved = FileOps.saveBitmap(outFile.getAbsolutePath(), srcBmp, exportQuality);
            if (srcBmp != stageCanvasView.getImageBitmap()) srcBmp.recycle();
            if (Boolean.TRUE.equals(saved.get("success"))) {
                scanMediaFile(outFile.getAbsolutePath());
                promptSystemSavePicker(outFile.getName(), Util.readAllBytes(outFile));
            }
        } catch (Exception | OutOfMemoryError e) { toast("내보내기 오류: " + e.getMessage()); }
    }

    private void scanMediaFile(String path) {
        try { MediaScannerConnection.scanFile(this, new String[]{path}, null, null); } catch (Exception ignored) {}
    }

    private void printCurrentFile() {
        if (!hasOpenFile()) return;
        PrintManager manager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        if (manager != null) manager.print(new File(currentPath).getName(),
                new ViewerPrintAdapter(this, new File(currentPath).getName(), currentKind, stageCanvasView, nativeDocView), null);
    }

    private void shareCurrentFile() {
        if (!hasOpenFile()) return;
        try {
            File f = isModified ? ViewerExports.snapshot(this, currentPath, currentKind, stageCanvasView, nativeDocView, exportQuality, exportDpi) : new File(currentPath);
            Uri uri = ViewerFileProvider.getUriForFile(this, f);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(FileOps.mimeOf(Util.extOf(currentPath)));
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "파일 공유"));
        } catch (Exception e) { toast("공유할 수 없습니다: " + e.getMessage()); }
    }

    // ---- Search / Offline OCR ----
    String searchSummaryLabel() {
        if ("doc".equals(currentKind)) return docHitTotal > 0 ? ((docHitIndex + 1) + "/" + docHitTotal) : "";
        if (!currentOcrBoxes.isEmpty()) return (activeOcrHit + 1) + "/" + currentOcrBoxes.size();
        return "";
    }

    @SuppressWarnings("unchecked")
    void executeSearchOrOcr(String query) {
        final String q = query == null ? "" : query.trim(), path = currentPath, kind = currentKind;
        if ("doc".equals(kind)) { nativeDocView.performSearch(q); return; }
        if (!"pdf".equals(kind) && !"image".equals(kind)) return;
        final int ticket = ++searchTicket;
        final Bitmap input = "image".equals(kind) && stageCanvasView.getImageBitmap() != null
                ? stageCanvasView.getImageBitmap().copy(Bitmap.Config.ARGB_8888, false) : null;
        final List<MarkupModel.PdfPageItem> pList = "pdf".equals(kind) ? copyPdfState() : new ArrayList<>();
        toast("텍스트를 인식하고 있어요…");
        ocrPool.execute(() -> {
            List<StageCanvasView.OcrBox> boxes = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            try {
                int count = "image".equals(kind) ? 1 : pList.size();
                for (int index = 0; index < count; index++) {
                    if (searchTicket != ticket || Thread.currentThread().isInterrupted()) return;
                    Map<String, Object> result;
                    boolean normalized = "pdf".equals(kind);
                    if (!normalized) { if (input == null) return; result = OcrHost.recognizeBitmap(input); }
                    else {
                        MarkupModel.PdfPageItem page = pList.get(index);
                        if ("pdf".equals(page.kind) && page.rot == 0 && page.anns.isEmpty()) result = OcrHost.pdfPageText(page.srcPath, page.srcPageIdx);
                        else {
                            Bitmap bitmap = page.renderFlattened(1800);
                            try { result = OcrHost.normalizedBitmapText(bitmap); } finally { bitmap.recycle(); }
                        }
                    }
                    if (!Boolean.TRUE.equals(result.get("ok"))) throw new java.io.IOException(String.valueOf(result.get("error")));
                    if (normalized) text.append("[페이지 ").append(index + 1).append("]\n");
                    text.append(result.get("text")).append('\n');
                    List<?> words = (List<?>) result.get(normalized ? "items" : "words");
                    if (words != null) {
                        for (Object item : words) {
                            Map<?, ?> word = (Map<?, ?>) item;
                            String value = String.valueOf(word.get("t"));
                            if (q.isEmpty() || value.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))) {
                                boxes.add(new StageCanvasView.OcrBox(normalized ? index : -1,
                                        (float) Util.toDouble(word.get("x"), 0), (float) Util.toDouble(word.get("y"), 0),
                                        (float) Util.toDouble(word.get("w"), 0), (float) Util.toDouble(word.get("h"), 0), normalized, value));
                            }
                        }
                    }
                }
                mainHandler.post(() -> {
                    if (isFinishing() || ticket != searchTicket || !path.equals(currentPath)) return;
                    fullExtractedOcrText = text.toString();
                    currentOcrBoxes.clear();
                    currentOcrBoxes.addAll(boxes);
                    activeOcrHit = boxes.isEmpty() ? -1 : 0;
                    stageCanvasView.setOcrBoxes(boxes, activeOcrHit);
                    syncChromeState();
                    toast(boxes.isEmpty() ? "일치하는 텍스트가 없어요." : boxes.size() + "개 텍스트를 찾았어요.");
                });
            } catch (Exception | LinkageError e) {
                if (ticket == searchTicket) toast("텍스트 인식 실패: " + e.getMessage());
            } finally {
                if (input != null) input.recycle();
            }
        });
    }

    void stepSearchOrOcr(int delta) {
        if ("doc".equals(currentKind)) { nativeDocView.stepSearch(delta); return; }
        if (currentOcrBoxes.isEmpty()) return;
        activeOcrHit = (activeOcrHit + delta + currentOcrBoxes.size()) % currentOcrBoxes.size();
        stageCanvasView.setOcrBoxes(currentOcrBoxes, activeOcrHit);
        syncChromeState();
    }

    void showFullExtractedTextDialog() {
        String text = "doc".equals(currentKind) ? nativeDocView.getPlainTextAll() : fullExtractedOcrText;
        if (text == null || text.trim().isEmpty()) {
            executeSearchOrOcr("");
            toast("텍스트를 추출 중입니다. 잠시 후 다시 열어 주세요.");
            return;
        }
        final String finalText = text;
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(finalText);
        tv.setPadding(Ui.dp(20), Ui.dp(14), Ui.dp(20), Ui.dp(14));
        tv.setTextIsSelectable(true);
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle("추출된 전체 텍스트").setView(sv)
                .setPositiveButton("전체 복사", (d, w) -> { copyToClipboard(finalText); toast("복사했습니다"); })
                .setNegativeButton("닫기", null).show();
    }

    // ---- Native Dialogs (Color Adjust, Resize, Text/Sign Annotation, PDF Organize, File Info, Rename/Delete) ----
    private void showColorAdjustDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(18), Ui.dp(12), Ui.dp(18), Ui.dp(8));
        box.addView(adjustRow("노출", Math.round((adjExp + 1f) * 50), 100, v -> { adjExp = (v - 50) / 50f; updateLiveAdjustPreview(); }));
        box.addView(adjustRow("대비", Math.round((adjCon + 1f) * 50), 100, v -> { adjCon = (v - 50) / 50f; updateLiveAdjustPreview(); }));
        box.addView(adjustRow("채도", Math.round((adjSat + 1f) * 50), 100, v -> { adjSat = (v - 50) / 50f; updateLiveAdjustPreview(); }));
        box.addView(adjustRow("색온도", Math.round((adjWarm + 1f) * 50), 100, v -> { adjWarm = (v - 50) / 50f; updateLiveAdjustPreview(); }));
        box.addView(adjustRow("선명도", Math.round(adjSharp * 100), 100, v -> adjSharp = v / 100f));
        CheckBox sepia = new CheckBox(this); sepia.setText("세피아"); sepia.setChecked(adjSepia);
        sepia.setOnCheckedChangeListener((b, ch) -> { adjSepia = ch; updateLiveAdjustPreview(); });
        box.addView(sepia);
        CheckBox inv = new CheckBox(this); inv.setText("색상 반전"); inv.setChecked(adjInvert);
        inv.setOnCheckedChangeListener((b, ch) -> { adjInvert = ch; updateLiveAdjustPreview(); });
        box.addView(inv);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle("색상 보정").setView(sv)
                .setPositiveButton("적용", (d, w) -> commitColorAdjustments())
                .setNeutralButton("초기화", (d, w) -> { resetColorAdjustments(); setTool("hand"); })
                .setNegativeButton("취소", (d, w) -> { resetColorAdjustments(); setTool("hand"); })
                .show();
    }

    private View adjustRow(String label, int initVal, int maxVal, java.util.function.IntConsumer onChange) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, Ui.dp(4), 0, Ui.dp(4));
        TextView tv = Ui.text(this, label, 13.5f, Ui.semibold, Ui.text());
        r.addView(tv);
        SeekBar sb = new SeekBar(this);
        sb.setMax(maxVal);
        sb.setProgress(initVal);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) { if (fromUser) onChange.accept(progress); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        r.addView(sb);
        return r;
    }

    private void showResizeImageDialog() {
        Bitmap cur = stageCanvasView.getImageBitmap();
        if (cur == null) return;
        int origW = cur.getWidth(), origH = cur.getHeight();
        double ratio = (double) origW / Math.max(1, origH);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(20), Ui.dp(12), Ui.dp(20), Ui.dp(8));
        EditText ew = new EditText(this); ew.setInputType(InputType.TYPE_CLASS_NUMBER); ew.setHint("너비 (px)"); ew.setText(String.valueOf(origW));
        EditText eh = new EditText(this); eh.setInputType(InputType.TYPE_CLASS_NUMBER); eh.setHint("높이 (px)"); eh.setText(String.valueOf(origH));
        CheckBox lock = new CheckBox(this); lock.setText("가로·세로 비율 유지"); lock.setChecked(true);
        final boolean[] syncing = {false};
        ew.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (syncing[0] || !lock.isChecked()) return;
                try {
                    int w = Integer.parseInt(s.toString());
                    syncing[0] = true; eh.setText(String.valueOf(Math.max(1, (int) Math.round(w / ratio)))); syncing[0] = false;
                } catch (Exception ignored) {}
            }
        });
        box.addView(ew); box.addView(eh); box.addView(lock);
        new AlertDialog.Builder(this).setTitle("이미지 크기 조절 (" + origW + " × " + origH + ")").setView(box)
                .setPositiveButton("적용", (d, w) -> {
                    try {
                        int nw = Math.max(1, Math.min(12000, Integer.parseInt(ew.getText().toString())));
                        int nh = Math.max(1, Math.min(12000, Integer.parseInt(eh.getText().toString())));
                        pushImageUndo();
                        Bitmap resized = ImageEditOps.resize(cur, nw, nh);
                        stageCanvasView.setImageContent(resized, null, stageCanvasView.getImageAnnotations(), true);
                        markModified();
                        syncChromeState();
                    } catch (Exception e) { toast("올바른 크기를 입력해 주세요"); }
                }).setNegativeButton("취소", null).show();
    }

    private void showAddTextAnnotationDialog(float docX, float docY, int pageIndex) {
        EditText input = new EditText(this);
        input.setHint("주석 내용을 입력하세요");
        new AlertDialog.Builder(this).setTitle("텍스트 주석 추가").setView(input)
                .setPositiveButton("추가", (d, w) -> {
                    String txt = input.getText().toString().trim();
                    if (txt.isEmpty()) return;
                    MarkupModel.Annotation ann = new MarkupModel.Annotation("txt_" + System.currentTimeMillis(), "text");
                    ann.x = docX; ann.y = docY; ann.text = txt;
                    ann.color = stageCanvasView.getMarkupColor();
                    ann.fontSize = stageCanvasView.getMarkupFontSize();
                    if ("image".equals(currentKind)) { pushImageUndo(); stageCanvasView.getImageAnnotations().add(ann); }
                    else if ("pdf".equals(currentKind) && pageIndex >= 0 && pageIndex < stageCanvasView.getPdfPages().size()) {
                        stageCanvasView.getPdfPages().get(pageIndex).anns.add(ann);
                    }
                    markModified();
                    stageCanvasView.invalidate();
                    syncChromeState();
                }).setNegativeButton("취소", null).show();
    }

    private void showSignatureDialog(float docX, float docY, int pageIndex) {
        final Path sigPath = new Path();
        final Paint sigPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        sigPaint.setStyle(Paint.Style.STROKE);
        sigPaint.setStrokeWidth(Ui.dpf(3.2f));
        sigPaint.setStrokeCap(Paint.Cap.ROUND);
        sigPaint.setColor(0xFF111111);
        View pad = new View(this) {
            @Override protected void onDraw(Canvas c) {
                c.drawColor(0xFFFFFFFF);
                c.drawPath(sigPath, sigPaint);
            }
            @Override public boolean onTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) sigPath.moveTo(e.getX(), e.getY());
                else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) sigPath.lineTo(e.getX(), e.getY());
                invalidate();
                return true;
            }
        };
        pad.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(200)));
        new AlertDialog.Builder(this).setTitle("서명 그리기").setView(pad)
                .setPositiveButton("서명 삽입", (d, w) -> {
                    int pw = Math.max(200, pad.getWidth()), ph = Math.max(120, pad.getHeight());
                    Bitmap bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
                    Canvas cv = new Canvas(bmp);
                    cv.drawPath(sigPath, sigPaint);
                    savedSignatures.add(bmp);
                    MarkupModel.Annotation ann = new MarkupModel.Annotation("sig_" + System.currentTimeMillis(), "sign");
                    ann.x = docX; ann.y = docY; ann.w = 180f; ann.h = 90f; ann.sigBitmap = bmp;
                    if ("image".equals(currentKind)) { pushImageUndo(); stageCanvasView.getImageAnnotations().add(ann); }
                    else if ("pdf".equals(currentKind) && pageIndex >= 0 && pageIndex < stageCanvasView.getPdfPages().size()) {
                        stageCanvasView.getPdfPages().get(pageIndex).anns.add(ann);
                    }
                    markModified();
                    stageCanvasView.invalidate();
                    syncChromeState();
                }).setNegativeButton("취소", null).show();
    }

    void showPdfPageOrganizeDialog() {
        List<MarkupModel.PdfPageItem> pList = stageCanvasView.getPdfPages();
        if (pList.isEmpty()) return;
        int idx = stageCanvasView.getCurrentPdfPage();
        String[] ops = {"현재 페이지 90° 회전", "빈 페이지 뒤에 추가", "파일에서 페이지 삽입", "현재 페이지 앞으로 이동", "현재 페이지 뒤로 이동", "현재 페이지 삭제"};
        new AlertDialog.Builder(this).setTitle("PDF 페이지 정리 (" + (idx + 1) + "/" + pList.size() + ")")
                .setItems(ops, (d, w) -> {
                    switch (w) {
                        case 0: rotateCurrentPdfPage(90); break;
                        case 1: insertBlankPdfPage(); break;
                        case 2: launchInsertPagePicker(); break;
                        case 3:
                            if (idx > 0) { Collections.swap(pList, idx, idx - 1); markModified(); stageCanvasView.setCurrentPdfPage(idx - 1); syncChromeState(); }
                            break;
                        case 4:
                            if (idx < pList.size() - 1) { Collections.swap(pList, idx, idx + 1); markModified(); stageCanvasView.setCurrentPdfPage(idx + 1); syncChromeState(); }
                            break;
                        case 5:
                            if (pList.size() <= 1) { toast("마지막 페이지는 삭제할 수 없습니다"); return; }
                            pList.remove(idx); markModified(); stageCanvasView.setCurrentPdfPage(Math.min(idx, pList.size() - 1)); syncChromeState();
                            break;
                    }
                }).setNegativeButton("닫기", null).show();
    }

    private void showPdfJumpPageDialog() {
        int tot = stageCanvasView.getPdfPages().size();
        if (tot <= 0) return;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("1 ~ " + tot);
        new AlertDialog.Builder(this).setTitle("페이지 이동").setView(input)
                .setPositiveButton("이동", (d, w) -> {
                    try {
                        int p = Integer.parseInt(input.getText().toString()) - 1;
                        stageCanvasView.setCurrentPdfPage(Math.max(0, Math.min(tot - 1, p)));
                        syncChromeState();
                    } catch (Exception ignored) {}
                }).setNegativeButton("취소", null).show();
    }

    private void showFileInfoDialog() {
        if (!hasOpenFile()) return;
        File f = new File(currentPath);
        StringBuilder sb = new StringBuilder();
        sb.append("파일명: ").append(f.getName()).append('\n');
        sb.append("크기: ").append(fmtBytes(f.length())).append('\n');
        sb.append("종류: ").append(Pages.labelForKind(currentKind)).append('\n');
        if ("image".equals(currentKind) && stageCanvasView.getImageBitmap() != null) {
            Bitmap b = stageCanvasView.getImageBitmap();
            sb.append("해상도: ").append(b.getWidth()).append(" × ").append(b.getHeight()).append(" px\n");
            try {
                List<Object> raw = AiMeta.read(currentPath);
                AiMeta.ParsedAi ai = raw != null && !raw.isEmpty() ? AiMeta.parseAiMeta(raw) : null;
                if (ai != null) {
                    sb.append("\n[AI 생성 메타데이터]\n");
                    if (ai.tool != null && !ai.tool.isEmpty()) sb.append("도구: ").append(ai.tool).append('\n');
                    if (ai.prompt != null && !ai.prompt.isEmpty()) sb.append("프롬프트: ").append(ai.prompt).append('\n');
                    if (ai.negative != null && !ai.negative.isEmpty()) sb.append("네거티브: ").append(ai.negative).append('\n');
                    for (Map.Entry<String, String> se : ai.settings.entrySet()) {
                        sb.append(se.getKey()).append(": ").append(se.getValue()).append('\n');
                    }
                }
            } catch (Throwable ignored) {}
        } else if ("pdf".equals(currentKind)) {
            sb.append("페이지 수: ").append(stageCanvasView.getPdfPages().size()).append('\n');
        }
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextIsSelectable(true);
        tv.setPadding(Ui.dp(22), Ui.dp(14), Ui.dp(22), Ui.dp(14));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle("파일 정보").setView(sv)
                .setPositiveButton("복사", (d, w) -> { copyToClipboard(sb.toString()); toast("정보를 복사했습니다"); })
                .setNegativeButton("닫기", null).show();
    }

    void showSavedCopies(File directory, String title) {
        File[] saved = directory.listFiles(file -> file.isFile() && !FileOps.kindOf(file.getName()).isEmpty());
        if (saved == null || saved.length == 0) {
            new AlertDialog.Builder(this).setTitle(title).setMessage("저장된 파일이 없어요.").setPositiveButton("닫기", null).show();
            return;
        }
        Arrays.sort(saved, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        String[] names = new String[saved.length];
        for (int i = 0; i < saved.length; i++) names[i] = saved[i].getName();
        new AlertDialog.Builder(this).setTitle(title)
                .setItems(names, (d, w) -> guardChanges(() -> loadFolderList(directory.getAbsolutePath(), saved[w].getAbsolutePath())))
                .setNegativeButton("닫기", null).show();
    }

    private void showRenameDialog() {
        if (!hasOpenFile()) return;
        File f = new File(currentPath);
        EditText input = new EditText(this);
        input.setText(f.getName());
        new AlertDialog.Builder(this).setTitle("이름 바꾸기").setView(input)
                .setPositiveButton("변경", (d, w) -> {
                    String n = input.getText().toString().trim();
                    if (n.isEmpty() || n.equals(f.getName())) return;
                    File dst = new File(f.getParentFile(), n);
                    if (f.renameTo(dst)) {
                        currentPath = dst.getAbsolutePath();
                        syncChromeState();
                        toast("이름을 변경했습니다");
                    } else toast("이름을 변경할 수 없습니다");
                }).setNegativeButton("취소", null).show();
    }

    private void showDeleteConfirmDialog() {
        if (!hasOpenFile()) return;
        File f = new File(currentPath);
        new AlertDialog.Builder(this).setTitle("파일 삭제").setMessage("\"" + f.getName() + "\" 파일을 삭제할까요?")
                .setPositiveButton("삭제", (d, w) -> {
                    if (f.delete()) { isModified = false; closeCurrentFile(); toast("삭제했습니다"); }
                    else toast("파일을 삭제할 수 없습니다");
                }).setNegativeButton("취소", null).show();
    }

    // ---- Slideshow, Bookmarks, Clipboard, Keyboard Shortcuts ----
    private void startSlideshow() {
        if (isModified) { toast("슬라이드쇼를 시작하기 전에 편집 내용을 저장해 주세요."); return; }
        if (folderItems.isEmpty()) return;
        slideshowRunning = true;
        slideshowOverlay.setVisibility(View.VISIBLE);
        updateSlideshowView();
        mainHandler.removeCallbacks(slideshowRunnable);
        mainHandler.postDelayed(slideshowRunnable, slideshowIntervalSec * 1000L);
    }

    private void stopSlideshow() {
        slideshowRunning = false;
        mainHandler.removeCallbacks(slideshowRunnable);
        if (slideshowOverlay != null) slideshowOverlay.setVisibility(View.GONE);
    }

    private void updateSlideshowView() {
        if (!slideshowRunning || slideshowImageView == null) return;
        if ("image".equals(currentKind) && stageCanvasView.getImageBitmap() != null) {
            slideshowImageView.setImageBitmap(stageCanvasView.getImageBitmap());
        } else if ("pdf".equals(currentKind) && !stageCanvasView.getPdfPages().isEmpty()) {
            slideshowImageView.setImageBitmap(stageCanvasView.getPdfPages().get(stageCanvasView.getCurrentPdfPage()).getOrRenderBaseBitmap(1600));
        }
        slideshowCaptionView.setText((currentFileIndex + 1) + " / " + folderItems.size() + " · " + new File(currentPath).getName() + " (터치하면 종료)");
    }

    private void toggleBookmark(String path) {
        if (path == null || path.isEmpty()) return;
        if (bookmarks.contains(path)) { bookmarks.remove(path); toast("책갈피에서 제거했습니다"); }
        else { bookmarks.add(path); toast("책갈피에 추가했습니다"); }
        saveBookmarks();
        if (filesPage != null) filesPage.refresh();
        syncChromeState();
    }

    private void loadBookmarks() {
        bookmarks.clear();
        try {
            Object parsed = Util.jsonParse(prefs.getString("bookmarks_json", "[]"));
            if (parsed instanceof List) {
                for (Object o : (List<?>) parsed) if (o != null) bookmarks.add(String.valueOf(o));
            }
        } catch (Exception ignored) {}
    }

    private void saveBookmarks() {
        prefs.edit().putString("bookmarks_json", Util.jsonStringify(new ArrayList<>(bookmarks))).apply();
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Viewer", text));
    }

    private void copyCurrentImage() {
        try {
            Bitmap bitmap = "image".equals(currentKind)
                    ? MarkupModel.flattenOntoBitmap(stageCanvasView.getImageBitmap(), stageCanvasView.getImageAnnotations())
                    : ("pdf".equals(currentKind) ? stageCanvasView.getPdfPages().get(stageCanvasView.getCurrentPdfPage()).renderFlattened(1800) : null);
            if (bitmap == null) { toast("복사할 이미지가 없어요."); return; }
            RectF sel = stageCanvasView.getSelectionRect();
            if ("image".equals(currentKind) && sel != null && sel.width() > 0 && sel.height() > 0) {
                int x = Math.max(0, (int) sel.left), y = Math.max(0, (int) sel.top);
                int w = Math.min(bitmap.getWidth() - x, Math.max(1, (int) sel.width()));
                int h = Math.min(bitmap.getHeight() - y, Math.max(1, (int) sel.height()));
                Bitmap cropped = Bitmap.createBitmap(bitmap, x, y, w, h);
                if (cropped != bitmap) bitmap.recycle();
                bitmap = cropped;
            }
            File file = new File(getCacheDir(), "clipboard-" + System.currentTimeMillis() + ".png");
            ViewerExports.require(FileOps.saveBitmap(file.getAbsolutePath(), bitmap, 100), "success");
            bitmap.recycle();
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newUri(getContentResolver(), "이미지", ViewerFileProvider.uriForFile(file)));
            toast("이미지를 복사했어요.");
        } catch (Exception e) { toast("복사 실패: " + e.getMessage()); }
    }

    void pasteClipboardImage() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getUri() == null) {
            toast("클립보드에 이미지 파일이 없어요.");
            return;
        }
        openUris(Collections.singletonList(clip.getItemAt(0).getUri()), Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (event.isCtrlPressed()) {
            switch (key) {
                case KeyEvent.KEYCODE_S: saveCurrentFile(event.isShiftPressed()); return true;
                case KeyEvent.KEYCODE_O: launchSystemFilePicker(); return true;
                case KeyEvent.KEYCODE_P: printCurrentFile(); return true;
                case KeyEvent.KEYCODE_F: onSearch(); return true;
            }
            if (!(getCurrentFocus() instanceof EditText)) {
                if (key == KeyEvent.KEYCODE_C) { copyCurrentImage(); return true; }
                if (key == KeyEvent.KEYCODE_V) { pasteClipboardImage(); return true; }
                if (key == KeyEvent.KEYCODE_Z || key == KeyEvent.KEYCODE_Y) {
                    boolean redo = key == KeyEvent.KEYCODE_Y || event.isShiftPressed();
                    if ("pdf".equals(currentKind)) undoPdf(redo);
                    else if ("doc".equals(currentKind)) { if (redo) nativeDocView.redo(); else nativeDocView.undo(); }
                    else if (redo) redoImageEdit(); else undoImageEdit();
                    return true;
                }
            }
        }
        if (!(getCurrentFocus() instanceof EditText) && (key == KeyEvent.KEYCODE_PAGE_DOWN || key == KeyEvent.KEYCODE_PAGE_UP)) {
            int delta = key == KeyEvent.KEYCODE_PAGE_DOWN ? 1 : -1;
            if ("pdf".equals(currentKind)) stageCanvasView.setCurrentPdfPage(stageCanvasView.getCurrentPdfPage() + delta);
            else navigateFile(delta);
            return true;
        }
        return super.onKeyDown(key, event);
    }

    private void toast(String msg) {
        mainHandler.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    static String fmtBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        return String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024.0));
    }
}
