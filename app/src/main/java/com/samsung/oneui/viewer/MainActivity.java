package com.samsung.oneui.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.print.pdf.PrintedPdfDocument;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 100% Pure Native Android MainActivity for Samsung Viewer (One UI 9.5).
 * Zero WebView usage. Compatible with all non-rooted Android devices (Android 8.0 ~ 15+).
 * Dynamically adapts between Mobile UI (Smartphone) and Tablet UI (Galaxy Tab / Fold / DeX).
 */
public class MainActivity extends Activity {

    private static final int REQ_OPEN_DOC = 1001;
    private static final int REQ_CREATE_DOC = 1002;
    private static final int REQ_CAMERA = 1003;
    private static final int REQ_INSERT_PAGE_DOC = 1004;
    private static final int REQ_OPEN_TREE = 1005;
    private static final int REQ_CAPTURE = 1006;
    private DocumentStore documents;

    private final ExecutorService bgPool = Executors.newCachedThreadPool();
    private final ExecutorService filePool = Executors.newSingleThreadExecutor();
    private final ExecutorService ocrPool = Executors.newSingleThreadExecutor();
    private volatile int searchTicket;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // App Workspace Directories (100% Accessible on Non-Rooted Devices)
    private File sampleDir;
    private File inboxDir;
    private File pendingCameraFile;
    private byte[] pendingExportBytes;
    private Runnable afterSave;
    private Runnable afterSaveCancelled;
    private boolean saveRunning;
    private boolean flattenedPdfApproved;
    private int imageLoadTicket;
    private File pendingExportFile;
    private final List<Runnable> dropPermissions = new ArrayList<>();
    private View dropOverlay;
    private volatile int openTicket;
    private boolean capturePending, activityResumed;
    private int exportQuality = 100, exportDpi = 300;

    // UI Mode & Theme State
    private String uiModePref = "auto"; // "auto", "mobile", "tablet"
    private boolean isTabletLayout = false;
    private float windowWidthDp = 360;
    private Rect foldBounds;
    private final FoldObserver foldObserver = new FoldObserver();
    private boolean bookLayout = false;
    private FrameLayout workspaceFrame;
    private boolean isDarkTheme = true;
    private boolean sidebarVisible = false;
    private boolean infoVisible = false;
    private String sidebarTab = "thumbs"; // "thumbs", "anns", "bookmarks"
    private String sortOrder = "name"; // "name", "date", "size"

    // Current File & Folder State
    private String currentPath = "";
    private String currentKind = ""; // "", "image", "pdf", "doc", "media"
    private final List<FileEntry> folderItems = new ArrayList<>();
    private int currentFileIndex = -1;
    private final List<String> bookmarks = new ArrayList<>();
    private boolean isModified = false;

    // Image Undo / Redo History State
    private static final class ImageSnapshot {
        final Bitmap bmp;
        final List<MarkupModel.Annotation> anns;
        ImageSnapshot(Bitmap b, List<MarkupModel.Annotation> a) {
            // Pixel operations return a new bitmap; annotations have their own copy.
            // Retaining the unchanged source avoids another full-resolution allocation.
            this.bmp = b;
            this.anns = MarkupModel.copyList(a);
        }
    }
    private final List<ImageSnapshot> imgUndoStack = new ArrayList<>();
    private final List<ImageSnapshot> imgRedoStack = new ArrayList<>();
    private final List<List<MarkupModel.PdfPageItem>> pdfUndo = new ArrayList<>(), pdfRedo = new ArrayList<>();
    private String lastPdfKey = "";

    // Color Adjustment Parameters
    private float adjExp = 0f, adjCon = 0f, adjSat = 0f, adjWarm = 0f, adjSharp = 0f;
    private boolean adjSepia = false, adjInvert = false;

    // Instant Alpha Parameters
    private int alphaTolerance = 32;
    private boolean alphaContiguous = true;

    // Saved Signature Bitmaps
    private final List<Bitmap> savedSignatures = new ArrayList<>();

    // Search / OCR State
    private boolean searchBarOpen = false;
    private String lastSearchQuery = "";
    private final List<StageCanvasView.OcrBox> currentOcrBoxes = new ArrayList<>();
    private int activeOcrHit = -1;
    private String fullExtractedOcrText = "";

    // Slideshow State
    private boolean slideshowRunning = false;
    private int slideshowIntervalSec = 3;
    private final Runnable slideshowRunnable = new Runnable() {
        @Override
        public void run() {
            if (!slideshowRunning) return;
            navigateFile(1);
            mainHandler.postDelayed(this, slideshowIntervalSec * 1000L);
        }
    };

    // Root & Structural Views
    private FrameLayout rootFrame;
    private LinearLayout topHeaderBar;
    private TextView headerTitleView;
    private TextView headerSubView;
    private TextView uiModeChipView;

    private FrameLayout stageHostFrame;
    private View emptyWelcomeView;
    private StageCanvasView stageCanvasView;
    private NativeDocView nativeDocView;
    private NativeMediaView nativeMediaView;

    // Sidebars & Panels
    private View mobileScrimView;
    private LinearLayout sidebarContainer;
    private LinearLayout sidebarListContainer;
    private LinearLayout sidebarPdfActionRow;
    private TextView tabBtnThumbs, tabBtnAnns, tabBtnBookmarks;

    private LinearLayout infoPanelContainer;
    private LinearLayout infoContentBody;

    // Toolbars & Subbars
    private LinearLayout floatingToolbarHost;
    private LinearLayout subbarContainer;
    private LinearLayout mainPillBar;

    // Search Bar Overlay
    private LinearLayout searchBarOverlay;
    private EditText searchInputEdit;
    private EditText replaceInputEdit;
    private LinearLayout replaceRow;
    private TextView searchCountLabel;

    // Status Bar (Tablet UI)
    private LinearLayout statusBarContainer;
    private TextView statusLeftText;
    private TextView statusCenterText;
    private TextView statusRightText;

    // Fullscreen Slideshow Overlay
    private FrameLayout slideshowOverlay;
    private ImageView slideshowImageView;
    private TextView slideshowCaptionView;

    public static final class FileEntry {
        public final String path;
        public final String name;
        public final String kind;
        public final long size;
        public final long lastModified;

        public FileEntry(String path, String name, String kind, long size, long lastModified) {
            this.path = path;
            this.name = name;
            this.kind = kind;
            this.size = size;
            this.lastModified = lastModified;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs=getSharedPreferences("samsung_viewer_native_prefs",Context.MODE_PRIVATE);
        boolean initialDark=prefs.getBoolean("system_theme",true)?(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES:prefs.getBoolean("dark_theme",true);
        setTheme(initialDark?android.R.style.Theme_DeviceDefault_NoActionBar:android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        prefs = getSharedPreferences("samsung_viewer_native_prefs", Context.MODE_PRIVATE);
        documents = new DocumentStore(this);
        uiModePref = prefs.getString("ui_mode", "auto");
        isDarkTheme = prefs.getBoolean("system_theme", true) ? (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES : prefs.getBoolean("dark_theme", true);
        exportQuality = prefs.getInt("export_quality", 100); exportDpi = prefs.getInt("export_dpi", 300);
        slideshowIntervalSec = prefs.getInt("slide_sec", 3);
        loadBookmarks();
        OcrHost.init(this); PdfHost.init(this);
        Updater.autoCheck(this);

        // Prepare app-private working directories. Examples are created only when requested.
        File baseExt = getExternalFilesDir(null);
        if (baseExt == null) baseExt = getFilesDir();
        sampleDir = new File(baseExt, "SamsungViewer_Samples");
        if (!sampleDir.exists()) sampleDir.mkdirs();
        inboxDir = new File(getCacheDir(), "inbox");
        if (!inboxDir.exists()) inboxDir.mkdirs();


        // Initialize Reusable Core Views
        stageCanvasView = new StageCanvasView(this);
        nativeDocView = new NativeDocView(this);
        nativeMediaView = new NativeMediaView(this);
        bindCoreViewListeners();

        // Build Adaptive Native UI (Tablet mirrors PC layout with left sidebar + top floating toolbar)
        determineLayoutMode();
        sidebarVisible = isTabletLayout;
        infoVisible = false;
        buildCompleteNativeUI();
        foldObserver.start(this, bounds -> {
            if (java.util.Objects.equals(foldBounds, bounds)) return;
            foldBounds = bounds; determineLayoutMode(); rebuildChromePreserveStage();
        });

        // Handle Incoming Intent or Open Sample Folder
        if (!handleIncomingIntent(getIntent())) {
            if (savedInstanceState != null && savedInstanceState.getString("path") != null && !savedInstanceState.getString("path").isEmpty()) {
                String original = savedInstanceState.getString("path"), draft = savedInstanceState.getString("draft");
                openFileUnchecked(draft == null ? original : draft);
                if (draft != null) { currentPath = original; isModified = true; if ("doc".equals(currentKind)) nativeDocView.markDraftRestored(); }
                stageCanvasView.setZoom(savedInstanceState.getFloat("zoom", 1)); stageCanvasView.setCurrentPdfPage(savedInstanceState.getInt("page", 0));
                String pending = savedInstanceState.getString("pending_export"); if (pending != null) pendingExportFile = new File(pending);
                updateHeaderTitles();
            }
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("path", currentPath); state.putFloat("zoom", stageCanvasView.getZoom()); state.putInt("page", stageCanvasView.getCurrentPdfPage());
        if (pendingExportFile != null) state.putString("pending_export", pendingExportFile.getAbsolutePath());
        if (isModified && !saveRunning && !currentPath.isEmpty()) try {
            File snapshot = ViewerExports.snapshot(this, currentPath, currentKind, stageCanvasView, nativeDocView, exportQuality, exportDpi);
            File dir = new File(getFilesDir(), "drafts"); dir.mkdirs(); File draft = new File(dir, System.currentTimeMillis() + "_" + snapshot.getName());
            java.nio.file.Files.move(snapshot.toPath(), draft.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); state.putString("draft", draft.getAbsolutePath());
        } catch (Exception ignored) {}
        super.onSaveInstanceState(state);
    }
    @Override protected void onResume() { super.onResume();activityResumed=true;if(prefs!=null)consumeCapture(); }
    @Override protected void onPause() { activityResumed=false;super.onPause(); stopSlideshow(); nativeMediaView.pauseMedia(); }
    @Override protected void onDestroy() {
        for (Runnable release : new ArrayList<>(dropPermissions)) release.run(); dropPermissions.clear(); cancelAfterSave();
        openTicket++; imageLoadTicket++; searchTicket++; foldObserver.stop(); ocrPool.shutdownNow(); filePool.shutdownNow(); bgPool.shutdownNow(); mainHandler.removeCallbacksAndMessages(null);
        nativeMediaView.stopMedia(); PdfHost.closeAll(); super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (prefs.getBoolean("system_theme", true)) isDarkTheme = (newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        boolean prevTablet = isTabletLayout;
        determineLayoutMode();
        if (prevTablet != isTabletLayout) {
            sidebarVisible = isTabletLayout;
            infoVisible = false;
        }
        rebuildChromePreserveStage();
    }

    @Override
    public void onBackPressed() {
        if (slideshowRunning) {
            stopSlideshow();
            return;
        }
        if (searchBarOpen) {
            closeSearchBar();
            return;
        }
        if (!isTabletLayout && (sidebarVisible || infoVisible)) {
            sidebarVisible = false;
            infoVisible = false;
            updateDrawerAndPanelVisibility();
            return;
        }
        if (!"hand".equals(stageCanvasView.getActiveTool())) {
            setTool("hand");
            return;
        }
        if ("doc".equals(currentKind) && nativeDocView.isEditing()) {
            nativeDocView.setEditing(false);
            refreshToolbars();
            return;
        }
        guardChanges(() -> finish());
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(final String msg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void determineLayoutMode() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = Build.VERSION.SDK_INT >= 30 ? getWindowManager().getCurrentWindowMetrics().getBounds().width() : dm.widthPixels;
        windowWidthDp = width / dm.density;
        bookLayout = foldBounds != null && foldBounds.height() > foldBounds.width() && AdaptiveLayout.book(windowWidthDp, foldBounds.left / dm.density, foldBounds.right / dm.density, foldBounds.height() / dm.density);
        boolean tabletop = foldBounds != null && foldBounds.width() > foldBounds.height();
        isTabletLayout = !tabletop && (bookLayout || AdaptiveLayout.tablet(windowWidthDp, uiModePref));
    }

    private void toggleUiModeManual() {
        final String[] values = {"auto", "mobile", "tablet"};
        int selected = java.util.Arrays.asList(values).indexOf(uiModePref);
        new AlertDialog.Builder(this).setTitle("화면 배치").setSingleChoiceItems(new String[]{"기기와 창 크기에 맞춤", "모바일 배치", "넓은 화면 배치 (600dp 이상)"}, selected, (dialog, which) -> {
            uiModePref = values[which]; prefs.edit().putString("ui_mode", uiModePref).apply(); determineLayoutMode();
            sidebarVisible = isTabletLayout; infoVisible = false; dialog.dismiss(); rebuildChromePreserveStage();
        }).setNegativeButton("닫기", null).show();
    }

    private void applySystemBarColors() {
        try {
            setTheme(isDarkTheme?android.R.style.Theme_DeviceDefault_NoActionBar:android.R.style.Theme_DeviceDefault_Light_NoActionBar);
            Window w = getWindow();
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            int bg = currentPath.isEmpty() ? (isDarkTheme?0xFF171C1A:0xFFF2F3F5) : (isDarkTheme?0xFF0E1015:0xFFF2F4F8);
            w.setStatusBarColor(bg);
            w.setNavigationBarColor(bg);
            if(Build.VERSION.SDK_INT>=30 && w.getInsetsController()!=null) {
                int mask=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                w.getInsetsController().setSystemBarsAppearance(isDarkTheme?0:mask,mask);
            }else{int mask=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;int flags=w.getDecorView().getSystemUiVisibility();w.getDecorView().setSystemUiVisibility(isDarkTheme?flags&~mask:flags|mask);}
        } catch (Exception ignored) {}
    }

    // ---------- Bind Stage & Document Callbacks ----------
    private void bindCoreViewListeners() {
        stageCanvasView.setStageListener(new StageCanvasView.StageListener() {
            @Override
            public void onZoomChanged(float zoom) {
                updateStatusBar();
                refreshToolbars();
            }

            @Override
            public void onCursorInfo(String info) {
                if (statusRightText != null) statusRightText.setText(info);
            }

            @Override
            public void onNavigateFile(int delta) {
                navigateFile(delta);
            }

            @Override
            public void onPdfPageChanged(int pageIndex, int totalPages) {
                updateHeaderTitles();
                updateStatusBar();
                refreshToolbars();
                if (sidebarVisible) refreshSidebarContent();
            }

            @Override
            public void onAnnotationCreated(MarkupModel.Annotation ann, int pageIndex) {
                if ("image".equals(currentKind)) {
                    pushImageUndo();
                    stageCanvasView.getImageAnnotations().add(ann);
                } else if ("pdf".equals(currentKind)) {
                    List<MarkupModel.PdfPageItem> pages = stageCanvasView.getPdfPages();
                    if (pageIndex >= 0 && pageIndex < pages.size()) {
                        pages.get(pageIndex).anns.add(ann);
                    }
                }
                markModified();
                updateHeaderTitles();
                if (sidebarVisible && "anns".equals(sidebarTab)) refreshSidebarContent();
            }

            @Override
            public void onAnnotationSelected(MarkupModel.Annotation ann, int pageIndex) {
                refreshSubbar();
            }

            @Override
            public void onAnnotationModified() {
                markModified();
                updateHeaderTitles();
                if (sidebarVisible && "anns".equals(sidebarTab)) refreshSidebarContent();
            }

            @Override
            public void onTextToolTap(final float docX, final float docY, final int pageIndex) {
                showAddTextAnnotationDialog(docX, docY, pageIndex);
            }

            @Override
            public void onSignToolTap(final float docX, final float docY, final int pageIndex) {
                showSignatureDialog(docX, docY, pageIndex);
            }

            @Override
            public void onAlphaTap(int imgX, int imgY) {
                performInstantAlphaAt(imgX, imgY);
            }

            @Override
            public void onSelectionChanged(RectF selRect) {
                refreshSubbar();
            }

            @Override
            public void onLongPressContext(float screenX, float screenY) {
                showStageQuickMenu();
            }
        });

        nativeDocView.setDocListener(new NativeDocView.DocListener() {
            @Override
            public void onDocDirtyChanged(boolean dirty) {
                isModified = dirty;
                updateHeaderTitles();
                refreshToolbars();
            }

            @Override
            public void onSheetSelected(int sheetIdx, String sheetTitle) {
                updateStatusBar();
            }

            @Override
            public void onSearchHitsChanged(int currentIdx, int totalHits) {
                if (searchCountLabel != null) {
                    searchCountLabel.setText(totalHits > 0 ? ((currentIdx + 1) + " / " + totalHits) : "0 / 0");
                }
            }
        });
    }

    // ---------- Build 100% Pure Native Android UI ----------
    private void rebuildChromePreserveStage() {
        if (stageHostFrame != null) {
            stageHostFrame.removeAllViews();
        }
        buildCompleteNativeUI();
        attachActiveStageView();
        updateHeaderTitles();
        refreshSidebarContent();
        refreshInfoPanelContent();
        refreshToolbars();
        updateStatusBar();
    }

    /** Prism glass for a floating surface (Android 13+, where the AGSL shader runs); the old flat surface otherwise. */
    private android.graphics.drawable.Drawable glassOr(android.graphics.drawable.Drawable flat, boolean panel, int radius) {
        if (!Glass.shadersOk || !(stageHostFrame instanceof Glass.Backdrop)) return flat;
        Glass.Backdrop b = (Glass.Backdrop) stageHostFrame;
        int base = isDarkTheme ? 0xFF0E1015 : 0xFFF2F4F8;
        return panel ? GlassDrawable.panel(b, base, radius) : GlassDrawable.bar(b, base, radius);
    }

    private boolean glassHooked;
    /** Heat rule: glass re-samples the stage only on frames where the stage actually changed. */
    private void hookGlassRefresh() {
        if (glassHooked) return;
        glassHooked = true;
        getWindow().getDecorView().getViewTreeObserver().addOnPreDrawListener(() -> {
            if (stageHostFrame != null && stageHostFrame.isDirty()) {
                if (mainPillBar != null) mainPillBar.invalidate();
                if (subbarContainer != null) subbarContainer.invalidate();
                if (searchBarOverlay != null) searchBarOverlay.invalidate();
            }
            return true;
        });
    }

    private void buildCompleteNativeUI() {
        if (currentPath.isEmpty()) { buildWelcomeWorkspace(); return; }
        applySystemBarColors();
        stageCanvasView.setDarkTheme(isDarkTheme);
        nativeDocView.setDarkTheme(isDarkTheme);

        int bgMain = isDarkTheme ? 0xFF0E1015 : 0xFFF2F4F8;
        int bgSurface = isDarkTheme ? 0xFF171A22 : 0xFFFFFFFF;
        int borderCol = isDarkTheme ? 0xFF282D3A : 0xFFDCE1EC;
        int textPrimary = isDarkTheme ? 0xFFF1F3F9 : 0xFF15181E;
        int textSecondary = isDarkTheme ? 0xFF9AA3B5 : 0xFF5D6575;

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(bgMain);

        LinearLayout masterVertical = new LinearLayout(this);
        masterVertical.setOrientation(LinearLayout.VERTICAL);
        rootFrame.addView(masterVertical, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 1. Top Header Bar
        topHeaderBar = buildTopHeaderBar(bgSurface, borderCol, textPrimary, textSecondary);
        masterVertical.addView(topHeaderBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(isTabletLayout ? 54 : 52)));

        // 2. Middle Workspace Area
        workspaceFrame = new FrameLayout(this);
        masterVertical.addView(workspaceFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Stage Host + Empty Welcome View
        // the stage is the glass backdrop: toolbars over / beside it refract what is on screen (Prism glass)
        Ui.init(this, isDarkTheme);
        stageHostFrame = new Glass.Backdrop(this);
        stageHostFrame.setBackgroundColor(isDarkTheme ? 0xFF0B0D12 : 0xFFE9EDF4);
        emptyWelcomeView = null;

        // Sidebar & Info Panel
        sidebarContainer = buildSidebarView(bgSurface, borderCol, textPrimary, textSecondary);
        infoPanelContainer = buildInfoPanelView(bgSurface, borderCol, textPrimary, textSecondary);

        // Floating Pill Toolbar + Subbar Host
        floatingToolbarHost = new LinearLayout(this);
        floatingToolbarHost.setOrientation(LinearLayout.VERTICAL);
        floatingToolbarHost.setGravity(Gravity.CENTER_HORIZONTAL);
        subbarContainer = new LinearLayout(this);
        subbarContainer.setOrientation(LinearLayout.HORIZONTAL);
        subbarContainer.setGravity(Gravity.CENTER_VERTICAL);
        mainPillBar = new LinearLayout(this) {
            @Override protected void onMeasure(int widthSpec,int heightSpec) {
                int width=MeasureSpec.getMode(widthSpec)==MeasureSpec.UNSPECIFIED?dp(520):Math.min(dp(520),MeasureSpec.getSize(widthSpec));
                super.onMeasure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),heightSpec);
            }
        };
        mainPillBar.setOrientation(LinearLayout.HORIZONTAL);
        mainPillBar.setGravity(Gravity.CENTER_VERTICAL);

        if (isTabletLayout) {
            // Tablet UI: Top Floating Toolbar, then Subbar; 3-Column Split Layout
            floatingToolbarHost.addView(mainPillBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sblp.topMargin = dp(6);
            floatingToolbarHost.addView(subbarContainer, sblp);

            LinearLayout splitRow = new LinearLayout(this);
            splitRow.setOrientation(LinearLayout.HORIZONTAL);
            workspaceFrame.addView(splitRow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            int sideWidth = bookLayout ? Math.max(dp(200), foldBounds.left) : dp(240);
            splitRow.addView(sidebarContainer, new LinearLayout.LayoutParams(sideWidth, ViewGroup.LayoutParams.MATCH_PARENT));
            if (bookLayout) { View gutter = new View(this); splitRow.addView(gutter, new LinearLayout.LayoutParams(Math.max(dp(12), foldBounds.width()), ViewGroup.LayoutParams.MATCH_PARENT)); sidebarVisible = true; }

            FrameLayout centerColumn = new FrameLayout(this);
            centerColumn.addView(stageHostFrame, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams ftLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ftLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            ftLp.topMargin = dp(10);
            ftLp.leftMargin = dp(12);
            ftLp.rightMargin = dp(12);
            centerColumn.addView(floatingToolbarHost, ftLp);

            splitRow.addView(centerColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            if (bookLayout) {
                FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                centerColumn.addView(infoPanelContainer, infoParams);
                topHeaderBar.setPadding(foldBounds.right + dp(8), 0, dp(8), 0);
            } else splitRow.addView(infoPanelContainer, new LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT));

            // Tablet Status Bar at bottom
            statusBarContainer = buildTabletStatusBar(bgSurface, borderCol, textSecondary);
            masterVertical.addView(statusBarContainer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
        } else {
            // Mobile UI: Fullscreen Stage + Bottom Docked Subbar & Pill Toolbar + Slide-over Drawer + Bottom Sheet Info
            floatingToolbarHost.addView(subbarContainer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams mplp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mplp.topMargin = dp(6);
            floatingToolbarHost.addView(mainPillBar, mplp);

            workspaceFrame.addView(stageHostFrame, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            FrameLayout.LayoutParams ftLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ftLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            ftLp.leftMargin = dp(10);
            ftLp.rightMargin = dp(10);
            ftLp.bottomMargin = dp(10);
            workspaceFrame.addView(floatingToolbarHost, ftLp);

            // Scrim for Mobile Drawer & Bottom Sheet
            mobileScrimView = new View(this);
            mobileScrimView.setBackgroundColor(0x88000000);
            mobileScrimView.setVisibility(View.GONE);
            mobileScrimView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sidebarVisible = false;
                    infoVisible = false;
                    updateDrawerAndPanelVisibility();
                }
            });
            workspaceFrame.addView(mobileScrimView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            FrameLayout.LayoutParams sideLp = new FrameLayout.LayoutParams(dp(AdaptiveLayout.drawerWidth(windowWidthDp)), ViewGroup.LayoutParams.MATCH_PARENT);
            sideLp.gravity = Gravity.LEFT;
            workspaceFrame.addView(sidebarContainer, sideLp);

            FrameLayout.LayoutParams infoLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(dp(430), Math.max(dp(180), getResources().getDisplayMetrics().heightPixels - dp(120))));
            infoLp.gravity = Gravity.BOTTOM;
            workspaceFrame.addView(infoPanelContainer, infoLp);
        }

        // Search / OCR Floating Bar Overlay
        searchBarOverlay = buildSearchBarOverlay(bgSurface, borderCol, textPrimary, textSecondary);
        FrameLayout.LayoutParams sblp = new FrameLayout.LayoutParams(
                isTabletLayout ? dp(420) : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        sblp.gravity = Gravity.TOP | (isTabletLayout ? Gravity.RIGHT : Gravity.CENTER_HORIZONTAL);
        sblp.topMargin = dp(isTabletLayout ? 64 : 8);
        sblp.leftMargin = dp(12);
        sblp.rightMargin = dp(12);
        workspaceFrame.addView(searchBarOverlay, sblp);

        // Fullscreen Slideshow Overlay
        slideshowOverlay = buildSlideshowOverlay();
        rootFrame.addView(slideshowOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        installRootLayout();
        floatingToolbarHost.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            FrameLayout.LayoutParams stageParams = (FrameLayout.LayoutParams) stageHostFrame.getLayoutParams();
            if (isTabletLayout) {
                int reserve=floatingToolbarHost.getVisibility()==View.VISIBLE?b-t+dp(22):0;
                if(stageParams.topMargin!=reserve){stageParams.topMargin=reserve;stageHostFrame.setLayoutParams(stageParams);}return;
            }
            boolean tabletop = foldBounds != null && foldBounds.width() > foldBounds.height();
            int oldHeight = stageParams.height, oldMargin = stageParams.bottomMargin;
            if (tabletop) {
                int[] at = new int[2]; workspaceFrame.getLocationInWindow(at);
                stageParams.height = Math.max(dp(120), foldBounds.top - at[1] - dp(8));
                stageParams.bottomMargin = 0;
            } else { stageParams.height = ViewGroup.LayoutParams.MATCH_PARENT; stageParams.bottomMargin = floatingToolbarHost.getVisibility() == View.VISIBLE ? b - t + dp(24) : 0; }
            if (oldHeight != stageParams.height || oldMargin != stageParams.bottomMargin) stageHostFrame.setLayoutParams(stageParams);
        });
        attachActiveStageView();
        updateDrawerAndPanelVisibility();
        refreshToolbars();
        hookGlassRefresh();
    }

    private void buildWelcomeWorkspace() {
        applySystemBarColors();
        sidebarVisible = infoVisible = false;
        sidebarContainer = sidebarListContainer = sidebarPdfActionRow = infoPanelContainer = infoContentBody = null;
        floatingToolbarHost = mainPillBar = subbarContainer = searchBarOverlay = statusBarContainer = null;
        statusLeftText = statusCenterText = statusRightText = null; mobileScrimView = null; slideshowOverlay = null;
        int ink = isDarkTheme ? 0xFFF0F4F1 : 0xFF19221F;
        rootFrame = new FrameLayout(this); rootFrame.setTag("welcome-root");
        rootFrame.setBackgroundColor(isDarkTheme ? 0xFF171C1A : 0xFFF2F3F5);
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        rootFrame.addView(column, new FrameLayout.LayoutParams(-1,-1));
        topHeaderBar = new LinearLayout(this); topHeaderBar.setOrientation(LinearLayout.VERTICAL); topHeaderBar.setPadding(dp(16),dp(10),dp(16),dp(12));
        headerTitleView = new TextView(this); headerTitleView.setText("Samsung Viewer"); headerTitleView.setTextColor(ink);
        headerTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP,18); headerTitleView.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        headerTitleView.setPadding(0,dp(2),0,dp(10)); topHeaderBar.addView(headerTitleView);
        if(getResources().getConfiguration().screenHeightDp<500 && getResources().getConfiguration().screenWidthDp>=600)headerTitleView.setVisibility(View.GONE);
        headerSubView = new TextView(this); headerSubView.setVisibility(View.GONE);
        LinearLayout pill = new LinearLayout(this); pill.setTag("welcome-toolbar"); pill.setGravity(Gravity.CENTER_VERTICAL); pill.setPadding(dp(6),dp(5),dp(6),dp(5));
        Ui.init(this, isDarkTheme);
        pill.setBackground(Glass.shadersOk ? GlassDrawable.bar(null, isDarkTheme ? 0xFF171C1A : 0xFFF2F3F5, dp(32)) : WelcomeView.surface(isDarkTheme ? 0xFF2A302D : Color.WHITE,dp(32),0)); pill.setElevation(dp(3));
        pill.addView(createIconBtn("menu","메뉴",ink,v -> showTopOverflowMenu(v)));
        pill.addView(createIconBtn("open","파일 열기",ink,v -> launchSystemFilePicker()));
        View save = createIconBtn("save","저장",ink,v -> saveCurrentFile(false)); save.setEnabled(false); save.setAlpha(.3f); pill.addView(save);
        topHeaderBar.addView(pill,new LinearLayout.LayoutParams(-2,-2)); column.addView(topHeaderBar,new LinearLayout.LayoutParams(-1,-2));
        workspaceFrame = new FrameLayout(this); column.addView(workspaceFrame,new LinearLayout.LayoutParams(-1,0,1));
        stageHostFrame = new FrameLayout(this); stageHostFrame.setTag("welcome-workspace");
        stageHostFrame.setBackground(WelcomeView.surface(isDarkTheme ? 0xFF202623 : 0xFFE8EAEC,dp(26),isDarkTheme ? 0xFF343E38 : 0xFFD4D8DB)); stageHostFrame.setClipToOutline(true);
        FrameLayout.LayoutParams workspace = new FrameLayout.LayoutParams(-1,-1); workspace.setMargins(dp(12),0,dp(12),dp(12)); workspaceFrame.addView(stageHostFrame,workspace);
        emptyWelcomeView = new WelcomeView(this,isDarkTheme,new WelcomeView.Actions() {
            public void open() { launchSystemFilePicker(); }
            public void paste() { pasteClipboardImage(); }
            public void capture() { launchScreenCapture(); }
        });
        stageHostFrame.addView(emptyWelcomeView,new FrameLayout.LayoutParams(-1,-1));
        stageHostFrame.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> fitWelcomeAroundFold());
        installRootLayout();
    }
    private void fitWelcomeAroundFold() {
        if (emptyWelcomeView == null || !currentPath.isEmpty()) return;
        int left=0,top=0,right=0,bottom=0;
        if (foldBounds != null) {
            int[] origin = new int[2]; stageHostFrame.getLocationInWindow(origin);
            Rect crease = new Rect(foldBounds); crease.offset(-origin[0],-origin[1]);
            if (crease.height() > crease.width() && crease.left > 0 && crease.right < stageHostFrame.getWidth()) {
                if (crease.left >= stageHostFrame.getWidth()-crease.right) right=stageHostFrame.getWidth()-crease.left+dp(8); else left=crease.right+dp(8);
            } else if (crease.width() > crease.height() && crease.top > 0 && crease.bottom < stageHostFrame.getHeight()) {
                if (crease.top >= stageHostFrame.getHeight()-crease.bottom) bottom=stageHostFrame.getHeight()-crease.top+dp(8); else top=crease.bottom+dp(8);
            }
        }
        emptyWelcomeView.setPadding(left,top,right,bottom);
    }
    private void installRootLayout() {
        setContentView(rootFrame);
        rootFrame.setOnApplyWindowInsetsListener((view,insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime()); view.setPadding(safe.left,safe.top,safe.right,safe.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        rootFrame.requestApplyInsets();
        configureFileDrop();
    }
    private void configureFileDrop() {
        LinearLayout cue = new LinearLayout(this); cue.setOrientation(LinearLayout.VERTICAL); cue.setGravity(Gravity.CENTER); cue.setPadding(dp(24),dp(24),dp(24),dp(24));
        cue.setBackground(WelcomeView.surface(isDarkTheme ? 0xF0203C31 : 0xF0E6F5EE,dp(26),WelcomeView.ACCENT)); cue.setTag("file-drop-overlay"); cue.setVisibility(View.GONE);
        TextView title = new TextView(this); title.setText("여기에 놓아 열기"); title.setTextColor(isDarkTheme?0xFFACE5CD:0xFF287A60); title.setTextSize(21); title.setGravity(Gravity.CENTER); title.setTypeface(Typeface.DEFAULT_BOLD); cue.addView(title);
        TextView sub = new TextView(this); sub.setText("이미지 · PDF · 문서 · 미디어"); sub.setTextSize(14); sub.setTextColor(isDarkTheme?0xFFB8D6C8:0xFF557A69); sub.setPadding(0,dp(10),0,0); sub.setGravity(Gravity.CENTER); cue.addView(sub);
        dropOverlay = cue; FrameLayout.LayoutParams overlay = new FrameLayout.LayoutParams(-1,-1); overlay.setMargins(dp(12),0,dp(12),dp(12)); workspaceFrame.addView(cue,overlay);
        rootFrame.setOnDragListener((view,event) -> {
            switch(event.getAction()) {
                case android.view.DragEvent.ACTION_DRAG_STARTED: return FileDrop.accepts(event.getClipDescription());
                case android.view.DragEvent.ACTION_DRAG_ENTERED: dropOverlay.setVisibility(View.VISIBLE); return true;
                case android.view.DragEvent.ACTION_DRAG_LOCATION: dropOverlay.setVisibility(View.VISIBLE); return true;
                case android.view.DragEvent.ACTION_DRAG_EXITED:
                case android.view.DragEvent.ACTION_DRAG_ENDED: dropOverlay.setVisibility(View.GONE); return true;
                case android.view.DragEvent.ACTION_DROP:
                    dropOverlay.setVisibility(View.GONE); List<Uri> uris = FileDrop.collect(event.getClipData()); if(uris.isEmpty()) return false;
                    final android.view.DragAndDropPermissions permission;
                    try { permission=requestDragAndDropPermissions(event); } catch(SecurityException e) { toast("이 파일을 읽을 권한이 없습니다."); return false; }
                    Runnable release=FileDrop.once(() -> { if(permission!=null) try{permission.release();}catch(RuntimeException ignored){} }); dropPermissions.add(release);
                    openUris(uris,Intent.FLAG_GRANT_READ_URI_PERMISSION,() -> {release.run();mainHandler.post(() -> dropPermissions.remove(release));}); return true;
                default: return true;
            }
        });
    }
    private LinearLayout buildTopHeaderBar(int bgSurface, int borderCol, int textPrimary, int textSecondary) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(isTabletLayout ? 8 : 12), 0, dp(isTabletLayout ? 8 : 12), 0);
        if (!isTabletLayout && stageHostFrame instanceof Glass.Backdrop) {
            bar.setBackground(GlassDrawable.bar((Glass.Backdrop) stageHostFrame, isDarkTheme ? 0xFF0E1015 : 0xFFF2F4F8, dp(26)));
            bar.setElevation(dp(4));
            bar.setClipToOutline(true);
        } else {
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(bgSurface);
            gd.setStroke(dp(1), borderCol);
            bar.setBackground(gd);
        }

        // Sidebar Toggle Button
        bar.addView(createIconBtn("sidebar", "사이드바", textPrimary, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sidebarVisible = !sidebarVisible;
                if (!isTabletLayout && sidebarVisible) infoVisible = false;
                updateDrawerAndPanelVisibility();
                if (sidebarVisible) refreshSidebarContent();
            }
        }));

        // Quick Open Explorer Button
        bar.addView(createIconBtn("folder", "파일 탐색기", textPrimary, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchSystemFilePicker();
            }
        }));

        // Title & Subtitle Stack
        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        titleStack.setGravity(Gravity.CENTER_VERTICAL);
        titleStack.setPadding(dp(8), 0, dp(6), 0);

        headerTitleView = new TextView(this);
        headerTitleView.setText("뷰어");
        headerTitleView.setTextColor(textPrimary);
        headerTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, isTabletLayout ? 15f : 14f);
        headerTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        headerTitleView.setSingleLine(true);
        headerTitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);

        headerSubView = new TextView(this);
        headerSubView.setText("9.5.0");
        headerSubView.setTextColor(textSecondary);
        headerSubView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        headerSubView.setSingleLine(true);

        titleStack.addView(headerTitleView);
        titleStack.addView(headerSubView);
        bar.addView(titleStack, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Mobile UI / Tablet UI Mode Switch Chip
        uiModeChipView = new TextView(this);
        uiModeChipView.setText(isTabletLayout ? "태블릿 UI" : "모바일 UI");
        uiModeChipView.setTextColor(0xFF0381FE);
        uiModeChipView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        uiModeChipView.setTypeface(Typeface.DEFAULT_BOLD);
        uiModeChipView.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(isDarkTheme ? 0x240381FE : 0x160381FE);
        chipBg.setCornerRadius(dp(14));
        chipBg.setStroke(dp(1), 0x550381FE);
        uiModeChipView.setBackground(chipBg);
        uiModeChipView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleUiModeManual();
            }
        });

        if (isTabletLayout && windowWidthDp >= 1100 && !bookLayout) {
            bar.addView(createIconBtn("save", "저장", textPrimary, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    saveCurrentFile(false);
                }
            }));
            bar.addView(createIconBtn("export", "내보내기 / 변환", textPrimary, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showExportDialog();
                }
            }));
            bar.addView(createIconBtn("print", "인쇄", textPrimary, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    printCurrentFile();
                }
            }));
            bar.addView(createIconBtn("share", "공유", textPrimary, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    shareCurrentFile();
                }
            }));
            bar.addView(createIconBtn("play", "슬라이드쇼", textPrimary, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startSlideshow();
                }
            }));
        }

        // Search / OCR Button
        bar.addView(createIconBtn("search", "찾기 / OCR", textPrimary, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSearchBar();
            }
        }));

        // Info / EXIF / AI Metadata Panel Button
        bar.addView(createIconBtn("info", "정보 및 AI 메타데이터", textPrimary, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                infoVisible = !infoVisible;
                if (!isTabletLayout && infoVisible) sidebarVisible = false;
                updateDrawerAndPanelVisibility();
                if (infoVisible) refreshInfoPanelContent();
            }
        }));

        // Overflow Menu Button
        bar.addView(createIconBtn("more", "더보기 메뉴", textPrimary, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTopOverflowMenu(v);
            }
        }));

        return bar;
    }

    private LinearLayout buildSidebarView(int bgSurface, int borderCol, final int textPrimary, final int textSecondary) {
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgSurface);
        gd.setStroke(dp(1), borderCol);
        side.setBackground(gd);

        // Tabs Row
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(8), dp(8), dp(8), dp(6));

        tabBtnThumbs = createSidebarTabBtn("미리보기", "thumbs", textPrimary, textSecondary);
        tabBtnAnns = createSidebarTabBtn("주석", "anns", textPrimary, textSecondary);
        tabBtnBookmarks = createSidebarTabBtn("책갈피", "bookmarks", textPrimary, textSecondary);

        tabs.addView(tabBtnThumbs, new LinearLayout.LayoutParams(0, dp(34), 1f));
        tabs.addView(tabBtnAnns, new LinearLayout.LayoutParams(0, dp(34), 1f));
        tabs.addView(tabBtnBookmarks, new LinearLayout.LayoutParams(0, dp(34), 1f));
        side.addView(tabs);

        // Sort & Folder Action Row
        LinearLayout sortRow = new LinearLayout(this);
        sortRow.setOrientation(LinearLayout.HORIZONTAL);
        sortRow.setGravity(Gravity.CENTER_VERTICAL);
        sortRow.setPadding(dp(10), dp(2), dp(10), dp(6));

        final TextView sortBtn = new TextView(this);
        sortBtn.setText("정렬: 이름순 ▾");
        sortBtn.setTextColor(textSecondary);
        sortBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        sortBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if ("name".equals(sortOrder)) { sortOrder = "date"; sortBtn.setText("정렬: 날짜순 ▾"); }
                else if ("date".equals(sortOrder)) { sortOrder = "size"; sortBtn.setText("정렬: 크기순 ▾"); }
                else { sortOrder = "name"; sortBtn.setText("정렬: 이름순 ▾"); }
                sortFolderItems();
                refreshSidebarContent();
            }
        });
        sortRow.addView(sortBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView openFolderChip = new TextView(this);
        openFolderChip.setText("+ 파일 열기");
        openFolderChip.setTextColor(0xFF0381FE);
        openFolderChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        openFolderChip.setTypeface(Typeface.DEFAULT_BOLD);
        openFolderChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchSystemFilePicker();
            }
        });
        sortRow.addView(openFolderChip);
        side.addView(sortRow);

        // PDF Page Organize Bar (shown when viewing PDF)
        sidebarPdfActionRow = new LinearLayout(this);
        sidebarPdfActionRow.setOrientation(LinearLayout.HORIZONTAL);
        sidebarPdfActionRow.setGravity(Gravity.CENTER_VERTICAL);
        sidebarPdfActionRow.setPadding(dp(8), dp(4), dp(8), dp(6));
        sidebarPdfActionRow.setVisibility(View.GONE);

        sidebarPdfActionRow.addView(createSmallActionChip("+ 빈 페이지", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                insertBlankPdfPage();
            }
        }));
        sidebarPdfActionRow.addView(createSmallActionChip("+ 파일 추가", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, REQ_INSERT_PAGE_DOC);
            }
        }));
        sidebarPdfActionRow.addView(createSmallActionChip("페이지 관리", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPdfPageOrganizeDialog();
            }
        }));
        side.addView(sidebarPdfActionRow);

        // Scrollable Item List
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sidebarListContainer = new LinearLayout(this);
        sidebarListContainer.setOrientation(LinearLayout.VERTICAL);
        sidebarListContainer.setPadding(dp(8), dp(4), dp(8), dp(24));
        sv.addView(sidebarListContainer);
        side.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return side;
    }

    private TextView createSidebarTabBtn(String title, final String tabId, final int textPrimary, final int textSecondary) {
        final TextView tv = new TextView(this);
        tv.setText(title);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sidebarTab = tabId;
                refreshSidebarContent();
            }
        });
        return tv;
    }

    private View createSmallActionChip(String label, View.OnClickListener listener) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFF0381FE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(8), dp(5), dp(8), dp(5));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(isDarkTheme ? 0x220381FE : 0x140381FE);
        gd.setCornerRadius(dp(10));
        tv.setBackground(gd);
        tv.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    // ---------- Info / EXIF / Histogram / AI Metadata Panel ----------
    private LinearLayout buildInfoPanelView(int bgSurface, int borderCol, int textPrimary, int textSecondary) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgSurface);
        if (!isTabletLayout) {
            gd.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        }
        gd.setStroke(dp(1), borderCol);
        panel.setBackground(gd);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(10), dp(10), dp(8));

        TextView title = new TextView(this);
        title.setText("파일 정보 · EXIF · AI 프롬프트");
        title.setTextColor(textPrimary);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        head.addView(createIconBtn("close", "닫기", textSecondary, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                infoVisible = false;
                updateDrawerAndPanelVisibility();
            }
        }));
        panel.addView(head);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        infoContentBody = new LinearLayout(this);
        infoContentBody.setOrientation(LinearLayout.VERTICAL);
        infoContentBody.setPadding(dp(14), dp(4), dp(14), dp(28));
        sv.addView(infoContentBody);
        panel.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return panel;
    }

    // ---------- Search / OCR Bar Overlay ----------
    private LinearLayout buildSearchBarOverlay(int bgSurface, int borderCol, int textPrimary, int textSecondary) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgSurface);
        gd.setCornerRadius(dp(18));
        gd.setStroke(dp(1), borderCol);
        box.setBackground(glassOr(gd, true, dp(18)));
        box.setElevation(dp(4));
        box.setVisibility(searchBarOpen ? View.VISIBLE : View.GONE);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        searchInputEdit = new EditText(this);
        searchInputEdit.setHint("텍스트 검색 또는 이미지/PDF OCR...");
        searchInputEdit.setTextColor(textPrimary);
        searchInputEdit.setHintTextColor(textSecondary);
        searchInputEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        searchInputEdit.setSingleLine(true);
        searchInputEdit.setBackgroundColor(Color.TRANSPARENT);
        searchInputEdit.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                executeSearchOrOcr(searchInputEdit.getText().toString());
                return true;
            }
        });
        row1.addView(searchInputEdit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        searchCountLabel = new TextView(this);
        searchCountLabel.setText("0 / 0");
        searchCountLabel.setTextColor(textSecondary);
        searchCountLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        searchCountLabel.setPadding(dp(6), 0, dp(6), 0);
        row1.addView(searchCountLabel);

        row1.addView(createSmallActionChip("찾기", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                executeSearchOrOcr(searchInputEdit.getText().toString());
            }
        }));

        row1.addView(createIconBtn("prev", "이전", textPrimary, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepSearchOrOcr(-1);
            }
        }));
        row1.addView(createIconBtn("next", "다음", textPrimary, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepSearchOrOcr(1);
            }
        }));
        row1.addView(createSmallActionChip("전체 텍스트", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFullExtractedTextDialog();
            }
        }));
        row1.addView(createIconBtn("close", "닫기", textSecondary, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSearchBar();
            }
        }));
        box.addView(row1);

        // Replace Row (for Editable Documents)
        replaceRow = new LinearLayout(this);
        replaceRow.setOrientation(LinearLayout.HORIZONTAL);
        replaceRow.setGravity(Gravity.CENTER_VERTICAL);
        replaceRow.setPadding(0, dp(6), 0, 0);
        replaceRow.setVisibility("doc".equals(currentKind) ? View.VISIBLE : View.GONE);

        replaceInputEdit = new EditText(this);
        replaceInputEdit.setHint("바꿀 내용 입력...");
        replaceInputEdit.setTextColor(textPrimary);
        replaceInputEdit.setHintTextColor(textSecondary);
        replaceInputEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        replaceInputEdit.setSingleLine(true);
        replaceInputEdit.setBackgroundColor(Color.TRANSPARENT);
        replaceRow.addView(replaceInputEdit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        replaceRow.addView(createSmallActionChip("바꾸기", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if ("doc".equals(currentKind)) {
                    int c = nativeDocView.replaceSearch(searchInputEdit.getText().toString(), replaceInputEdit.getText().toString(), false);
                    toast(c > 0 ? "바꾸었습니다" : "일치하는 항목이 없습니다");
                }
            }
        }));
        replaceRow.addView(createSmallActionChip("모두 바꾸기", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if ("doc".equals(currentKind)) {
                    int c = nativeDocView.replaceSearch(searchInputEdit.getText().toString(), replaceInputEdit.getText().toString(), true);
                    toast(c + "개 항목을 모두 바꾸었습니다");
                }
            }
        }));
        box.addView(replaceRow);

        return box;
    }

    private LinearLayout buildTabletStatusBar(int bgSurface, int borderCol, int textSecondary) {
        LinearLayout sb = new LinearLayout(this);
        sb.setOrientation(LinearLayout.HORIZONTAL);
        sb.setGravity(Gravity.CENTER_VERTICAL);
        sb.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgSurface);
        gd.setStroke(dp(1), borderCol);
        sb.setBackground(gd);

        statusLeftText = new TextView(this);
        statusLeftText.setTextColor(textSecondary);
        statusLeftText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);

        statusCenterText = new TextView(this);
        statusCenterText.setTextColor(textSecondary);
        statusCenterText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        statusCenterText.setGravity(Gravity.CENTER);

        statusRightText = new TextView(this);
        statusRightText.setTextColor(textSecondary);
        statusRightText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        statusRightText.setTypeface(Typeface.MONOSPACE);
        statusRightText.setGravity(Gravity.RIGHT);

        sb.addView(statusLeftText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sb.addView(statusCenterText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sb.addView(statusRightText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return sb;
    }

    private FrameLayout buildSlideshowOverlay() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        frame.setVisibility(View.GONE);

        slideshowImageView = new ImageView(this);
        slideshowImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frame.addView(slideshowImageView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        slideshowCaptionView = new TextView(this);
        slideshowCaptionView.setTextColor(Color.WHITE);
        slideshowCaptionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        slideshowCaptionView.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable capBg = new GradientDrawable();
        capBg.setColor(0x99000000);
        capBg.setCornerRadius(dp(16));
        slideshowCaptionView.setBackground(capBg);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        clp.bottomMargin = dp(32);
        frame.addView(slideshowCaptionView, clp);

        frame.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopSlideshow();
            }
        });
        return frame;
    }

    private View createIconBtn(String iconName, String contentDesc, int tintColor, View.OnClickListener listener) {
        ImageView iv = new ImageView(this);
        int pad = dp(8);
        iv.setPadding(pad, pad, pad, pad);
        iv.setImageDrawable(VectorIcons.create(this, iconName, 20f, tintColor));
        iv.setContentDescription(contentDesc);
        iv.setMinimumWidth(dp(48)); iv.setMinimumHeight(dp(48));
        iv.setOnClickListener(listener);
        return iv;
    }

    private void updateDrawerAndPanelVisibility() {
        if (isTabletLayout && !AdaptiveLayout.dockBoth(windowWidthDp) && !bookLayout && infoVisible) sidebarVisible = false;
        if (sidebarContainer != null) {
            sidebarContainer.setVisibility(sidebarVisible ? View.VISIBLE : (bookLayout ? View.INVISIBLE : View.GONE));
        }
        if (infoPanelContainer != null) {
            infoPanelContainer.setVisibility(infoVisible ? View.VISIBLE : View.GONE);
        }
        if (mobileScrimView != null) {
            mobileScrimView.setVisibility((!isTabletLayout && (sidebarVisible || infoVisible)) ? View.VISIBLE : View.GONE);
        }
    }

    private void attachActiveStageView() {
        if (stageHostFrame == null) return;
        stageHostFrame.removeAllViews();
        if (currentPath.isEmpty()) {
            stageHostFrame.addView(emptyWelcomeView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if ("image".equals(currentKind) || "pdf".equals(currentKind)) {
            if (stageCanvasView.getParent() instanceof ViewGroup) {
                ((ViewGroup) stageCanvasView.getParent()).removeView(stageCanvasView);
            }
            stageHostFrame.addView(stageCanvasView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if ("doc".equals(currentKind)) {
            if (nativeDocView.getParent() instanceof ViewGroup) {
                ((ViewGroup) nativeDocView.getParent()).removeView(nativeDocView);
            }
            stageHostFrame.addView(nativeDocView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if ("media".equals(currentKind)) {
            if (nativeMediaView.getParent() instanceof ViewGroup) {
                ((ViewGroup) nativeMediaView.getParent()).removeView(nativeMediaView);
            }
            stageHostFrame.addView(nativeMediaView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    // ---------- Floating One UI Pill Toolbar & Contextual Subbar ----------
    private void refreshToolbars() {
        if(mainPillBar==null || subbarContainer==null)return;
        mainPillBar.removeAllViews();
        if(currentPath.isEmpty() || "media".equals(currentKind)){floatingToolbarHost.setVisibility(View.GONE);return;}
        floatingToolbarHost.setVisibility(View.VISIBLE);
        mainPillBar.setTag("editor-toolbar");mainPillBar.setPadding(dp(8),dp(4),dp(8),dp(4));
        mainPillBar.setBackground(glassOr(WelcomeView.surface(isDarkTheme?0xF228302C:0xF2FFFFFF,dp(26),isDarkTheme?0xFF38443D:0xFFD4DDD8),false,dp(26)));mainPillBar.setElevation(dp(3));
        int ink=isDarkTheme?0xFFF1F4F2:0xFF202A25;
        if("doc".equals(currentKind)) {
            addEditorAction("menu","도구",false,ink,v -> showEditorTools(v));
            View edit=addEditorAction("pen","편집",nativeDocView.isEditing(),ink,v -> {nativeDocView.setEditing(!nativeDocView.isEditing());refreshToolbars();});edit.setEnabled(nativeDocView.canEdit());edit.setAlpha(edit.isEnabled()?1:.35f);
            addEditorAction("search","찾기",searchBarOpen,ink,v -> toggleSearchBar());
        }else if("pdf".equals(currentKind)) {
            addEditorAction("singlePage","페이지",false,ink,v -> showPdfJumpPageDialog());
            addEditorAction("menu","보기",false,ink,v -> showEditorTools(v));
            addEditorAction("markup","그리기","markup".equals(stageCanvasView.getActiveTool()),ink,v -> setTool("markup".equals(stageCanvasView.getActiveTool())?"hand":"markup"));
        }else {
            addEditorAction("fit","맞춤",false,ink,v -> stageCanvasView.fitToWindow());
            addEditorAction("sliders","도구",false,ink,v -> showEditorTools(v));
            addEditorAction("markup","그리기","markup".equals(stageCanvasView.getActiveTool()),ink,v -> setTool("markup".equals(stageCanvasView.getActiveTool())?"hand":"markup"));
        }
        addEditorAction("undo","실행 취소",false,ink,v -> {if("image".equals(currentKind))undoImageEdit();else if("pdf".equals(currentKind))undoPdf(false);else nativeDocView.undo();});
        addEditorAction("save","저장",false,ink,v -> saveCurrentFile(false));
        refreshSubbar();
    }
    private View addEditorAction(String icon,String label,boolean active,int ink,View.OnClickListener action) {
        LinearLayout button=new LinearLayout(this);button.setOrientation(LinearLayout.VERTICAL);button.setGravity(Gravity.CENTER);
        button.setPadding(dp(2),dp(7),dp(2),dp(7));button.setMinimumHeight(dp(60));button.setMinimumWidth(dp(48));
        button.setTag("editor-"+icon);button.setContentDescription(label);button.setFocusable(true);button.setOnClickListener(action);
        int fill=active?WelcomeView.ACCENT:Color.TRANSPARENT;
        button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x2255B499),WelcomeView.surface(fill,dp(20),0),null));
        ImageView image=new ImageView(this);image.setImageDrawable(VectorIcons.create(this,icon,22,active?Color.WHITE:ink));
        button.addView(image,new LinearLayout.LayoutParams(dp(22),dp(22)));
        TextView caption=new TextView(this);caption.setText(label);caption.setTextColor(active?Color.WHITE:ink);caption.setTextSize(10);caption.setGravity(Gravity.CENTER);caption.setMaxLines(2);caption.setPadding(0,dp(3),0,0);
        button.addView(caption,new LinearLayout.LayoutParams(-1,-2));mainPillBar.addView(button,new LinearLayout.LayoutParams(0,-2,1));return button;
    }
    private void showEditorTools(View anchor) {
        PopupMenu menu=new PopupMenu(this,anchor);
        if("image".equals(currentKind)) {
            String[] labels={"이동","영역 선택","자르기","크기 조절","색상 보정","배경 지우기","왼쪽으로 회전","오른쪽으로 회전","좌우 뒤집기","상하 뒤집기","투명 배경 격자","다시 실행"};
            for(int i=0;i<labels.length;i++)menu.getMenu().add(0,100+i,i,labels[i]);
        }else if("pdf".equals(currentKind)) {
            String[] labels={"화면에 맞추기","연속 보기","한 페이지","두 페이지","페이지 관리","왼쪽으로 회전","오른쪽으로 회전","다시 실행"};
            for(int i=0;i<labels.length;i++)menu.getMenu().add(0,200+i,i,labels[i]);
        }else {
            menu.getMenu().add(0,303,0,"기본 크기");menu.getMenu().add(0,300,1,"글자 확대");menu.getMenu().add(0,301,2,"글자 축소");menu.getMenu().add(0,302,3,"다시 실행");
        }
        menu.getMenu().add(0,10,90,"이전 파일");menu.getMenu().add(0,11,91,"다음 파일");menu.getMenu().add(0,12,92,bookmarks.contains(currentPath)?"책갈피 해제":"책갈피 추가");
        menu.setOnMenuItemClickListener(item -> {
            switch(item.getItemId()) {
                case 100:setTool("hand");break;case 101:setTool("select");break;case 102:setTool("crop");break;case 103:showResizeImageDialog();break;case 104:setTool("adjust");break;case 105:setTool("alpha");break;
                case 106:rotateCurrentImage(-90);break;case 107:rotateCurrentImage(90);break;case 108:flipCurrentImage(true,false);break;case 109:flipCurrentImage(false,true);break;
                case 110:stageCanvasView.setShowChecker(!stageCanvasView.isShowChecker());break;case 111:redoImageEdit();break;
                case 200:stageCanvasView.fitToWindow();break;case 201:stageCanvasView.setPdfViewMode("cont");break;case 202:stageCanvasView.setPdfViewMode("single");break;case 203:stageCanvasView.setPdfViewMode("two");break;
                case 204:showPdfPageOrganizeDialog();break;case 205:rotateCurrentPdfPage(-90);break;case 206:rotateCurrentPdfPage(90);break;case 207:undoPdf(true);break;
                case 300:nativeDocView.setZoomScale(nativeDocView.getZoomScale()+.15f);break;case 301:nativeDocView.setZoomScale(nativeDocView.getZoomScale()-.15f);break;case 302:nativeDocView.redo();break;
                case 303:nativeDocView.setZoomScale(1);break;
                case 10:navigateFile(-1);break;case 11:navigateFile(1);break;case 12:toggleBookmark(currentPath);break;
            }
            refreshToolbars();return true;
        });menu.show();
    }

    private void refreshSubbar() {
        if (subbarContainer == null) return;
        subbarContainer.removeAllViews();
        String tool = stageCanvasView.getActiveTool();
        if ("hand".equals(tool) || currentPath.isEmpty() || "doc".equals(currentKind) || "media".equals(currentKind)) {
            subbarContainer.setVisibility(View.GONE);
            return;
        }
        subbarContainer.setVisibility(View.VISIBLE);

        GradientDrawable sbBg = new GradientDrawable();
        sbBg.setColor(isDarkTheme ? 0xEE1A1E29 : 0xF5FFFFFF);
        sbBg.setCornerRadius(dp(20));
        sbBg.setStroke(dp(1), isDarkTheme ? 0xFF2E3547 : 0xFFD0D8E8);
        subbarContainer.setBackground(glassOr(sbBg, false, dp(20)));
        subbarContainer.setElevation(dp(3));
        subbarContainer.setPadding(dp(10), dp(6), dp(10), dp(6));

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int textCol = isDarkTheme ? 0xFFF1F3F9 : 0xFF181C24;

        if ("select".equals(tool)) {
            RectF sel = stageCanvasView.getSelectionRect();
            String label = (sel != null && sel.width() > 2 && sel.height() > 2)
                    ? ("선택됨: " + Math.round(sel.width()) + " × " + Math.round(sel.height()) + " px")
                    : "이미지 위를 드래그하여 영역을 선택하세요";
            TextView tv = new TextView(this);
            tv.setText(label);
            tv.setTextColor(textCol);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            tv.setPadding(dp(6), 0, dp(10), 0);
            row.addView(tv);

            row.addView(createSmallActionChip("선택 영역 자르기", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    RectF r = stageCanvasView.getSelectionRect();
                    if (r == null || r.width() < 4 || r.height() < 4) {
                        toast("먼저 영역을 드래그해서 선택하세요");
                        return;
                    }
                    pushImageUndo();
                    Bitmap cropped = ImageEditOps.crop(
                            stageCanvasView.getImageBitmap(),
                            Math.round(r.left), Math.round(r.top),
                            Math.round(r.width()), Math.round(r.height())
                    );
                    stageCanvasView.setImageContent(cropped, null, new ArrayList<MarkupModel.Annotation>(), true);
                    markModified();
                    setTool("hand");
                }
            }));
            row.addView(createSmallActionChip("선택 해제", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    stageCanvasView.clearSelection();
                    refreshSubbar();
                }
            }));
        } else if ("crop".equals(tool)) {
            float[][] ratios = {{0f, 0f}, {1f, 1f}, {4f, 3f}, {16f, 9f}, {3f, 2f}};
            String[] rLabels = {"자유 비율", "1:1", "4:3", "16:9", "3:2"};
            for (int i = 0; i < rLabels.length; i++) {
                final float rVal = ratios[i][0] == 0f ? 0f : (ratios[i][0] / ratios[i][1]);
                row.addView(createSmallActionChip(rLabels[i], new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        stageCanvasView.setCropAspect(rVal);
                    }
                }));
            }
            row.addView(createSmallActionChip("✓ 자르기 적용", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    RectF r = stageCanvasView.getCropRect();
                    if (r.width() >= 4 && r.height() >= 4) {
                        pushImageUndo();
                        Bitmap cropped = ImageEditOps.crop(
                                stageCanvasView.getImageBitmap(),
                                Math.round(r.left), Math.round(r.top),
                                Math.round(r.width()), Math.round(r.height())
                        );
                        stageCanvasView.setImageContent(cropped, null, new ArrayList<MarkupModel.Annotation>(), true);
                        markModified();
                        updateHeaderTitles();
                        updateStatusBar();
                    }
                    setTool("hand");
                }
            }));
            row.addView(createSmallActionChip("취소", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setTool("hand");
                }
            }));
        } else if ("adjust".equals(tool)) {
            row.addView(createSmallActionChip("슬라이더 상세 조절", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showColorAdjustDialog();
                }
            }));
            row.addView(createSmallActionChip("자동 레벨 (Auto)", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (stageCanvasView.getImageBitmap() != null) {
                        pushImageUndo();
                        Bitmap autoBmp = ImageEditOps.autoLevels(stageCanvasView.getImageBitmap());
                        stageCanvasView.setImageContent(autoBmp, null, stageCanvasView.getImageAnnotations(), false);
                        markModified();
                        toast("자동 레벨 보정을 적용했습니다");
                    }
                }
            }));
            row.addView(createSmallActionChip("세피아", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    adjSepia = !adjSepia;
                    updateLiveAdjustPreview();
                }
            }));
            row.addView(createSmallActionChip("색상 반전", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    adjInvert = !adjInvert;
                    updateLiveAdjustPreview();
                }
            }));
            row.addView(createSmallActionChip("✓ 적용", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    commitColorAdjustments();
                }
            }));
            row.addView(createSmallActionChip("초기화", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    resetColorAdjustments();
                }
            }));
        } else if ("alpha".equals(tool)) {
            TextView guide = new TextView(this);
            guide.setText("배경으로 지울 색상을 터치하세요 (허용치 " + alphaTolerance + ")");
            guide.setTextColor(textCol);
            guide.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            guide.setPadding(dp(4), 0, dp(8), 0);
            row.addView(guide);

            row.addView(createSmallActionChip(alphaContiguous ? "인접 영역만" : "전체 동일색상", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    alphaContiguous = !alphaContiguous;
                    refreshSubbar();
                }
            }));
            row.addView(createSmallActionChip("허용치 -", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    alphaTolerance = Math.max(5, alphaTolerance - 10);
                    refreshSubbar();
                }
            }));
            row.addView(createSmallActionChip("허용치 +", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    alphaTolerance = Math.min(140, alphaTolerance + 10);
                    refreshSubbar();
                }
            }));
            row.addView(createSmallActionChip("완료", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setTool("hand");
                }
            }));
        } else if ("markup".equals(tool)) {
            String curKind = stageCanvasView.getMarkupKind();
            String[][] mTools = {
                    {"select", "선택"}, {"pen", "펜"}, {"hl", "형광펜"},
                    {"line", "직선"}, {"arrow", "화살표"}, {"rect", "사각형"},
                    {"ellipse", "원"}, {"text", "텍스트"}, {"redact", "가리기"}, {"sign", "서명"}
            };
            for (final String[] mt : mTools) {
                boolean active = mt[0].equals(curKind);
                row.addView(createPillTextBtn(mt[1], active, textCol, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        stageCanvasView.setMarkupOptions(
                                mt[0],
                                stageCanvasView.getMarkupColor(),
                                stageCanvasView.getMarkupWidth(),
                                stageCanvasView.getMarkupFontSize(),
                                stageCanvasView.isMarkupFill()
                        );
                        refreshSubbar();
                    }
                }));
            }
            row.addView(createToolbarDivider());

            // Color Swatches
            final int[] colors = {0xFFE53935, 0xFFFFB300, 0xFF43A047, 0xFF0381FE, 0xFF8E24AA, 0xFFFFFFFF, 0xFF111111};
            for (final int c : colors) {
                View swatch = new View(this);
                GradientDrawable sgd = new GradientDrawable();
                sgd.setShape(GradientDrawable.OVAL);
                sgd.setColor(c);
                sgd.setStroke(dp(c == stageCanvasView.getMarkupColor() ? 2.5f : 1f),
                        c == stageCanvasView.getMarkupColor() ? 0xFF0381FE : 0x88888888);
                swatch.setBackground(sgd);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(22), dp(22));
                slp.leftMargin = dp(4);
                slp.rightMargin = dp(4);
                swatch.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        stageCanvasView.setMarkupOptions(
                                stageCanvasView.getMarkupKind(),
                                c,
                                stageCanvasView.getMarkupWidth(),
                                stageCanvasView.getMarkupFontSize(),
                                stageCanvasView.isMarkupFill()
                        );
                        refreshSubbar();
                    }
                });
                row.addView(swatch, slp);
            }
            row.addView(createToolbarDivider());

            // Stroke Width Cycle
            final float curW = stageCanvasView.getMarkupWidth();
            row.addView(createSmallActionChip("굵기 " + Math.round(curW) + "pt", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    float nextW = curW < 3f ? 3f : curW < 6f ? 6f : curW < 10f ? 10f : 1.5f;
                    stageCanvasView.setMarkupOptions(
                            stageCanvasView.getMarkupKind(),
                            stageCanvasView.getMarkupColor(),
                            nextW,
                            stageCanvasView.getMarkupFontSize(),
                            stageCanvasView.isMarkupFill()
                    );
                    refreshSubbar();
                }
            }));

            // Fill toggle
            row.addView(createSmallActionChip(stageCanvasView.isMarkupFill() ? "채우기 ON" : "채우기 OFF", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    stageCanvasView.setMarkupOptions(
                            stageCanvasView.getMarkupKind(),
                            stageCanvasView.getMarkupColor(),
                            stageCanvasView.getMarkupWidth(),
                            stageCanvasView.getMarkupFontSize(),
                            !stageCanvasView.isMarkupFill()
                    );
                    refreshSubbar();
                }
            }));

            // Delete Selected Annotation
            if (stageCanvasView.findSelectedAnnotation() != null) {
                row.addView(createSmallActionChip("선택 주석 삭제", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (stageCanvasView.deleteSelectedAnnotation()) {
                            markModified();
                            refreshSubbar();
                            if (sidebarVisible) refreshSidebarContent();
                        }
                    }
                }));
            }

            if ("image".equals(currentKind) && !stageCanvasView.getImageAnnotations().isEmpty()) {
                row.addView(createSmallActionChip("이미지에 병합", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pushImageUndo();
                        Bitmap flat = MarkupModel.flattenOntoBitmap(stageCanvasView.getImageBitmap(), stageCanvasView.getImageAnnotations());
                        stageCanvasView.setImageContent(flat, null, new ArrayList<MarkupModel.Annotation>(), false);
                        markModified();
                        toast("주석을 이미지 픽셀에 병합했습니다");
                        refreshSubbar();
                    }
                }));
            }
        }

        hsv.addView(row);
        subbarContainer.addView(hsv);
    }

    private View createPillToolBtn(String iconName, String label, boolean active, int defaultTint, View.OnClickListener listener) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        int padH = dp(label != null ? 10 : 8);
        int padV = dp(6);
        box.setPadding(padH, padV, padH, padV);
        if (active) {
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(0xFF0381FE);
            gd.setCornerRadius(dp(18));
            box.setBackground(gd);
        }
        int col = active ? Color.WHITE : defaultTint;
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(VectorIcons.create(this, iconName, 19f, col));
        box.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));
        if (label != null && !label.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText(label);
            tv.setTextColor(col);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setPadding(dp(5), 0, 0, 0);
            box.addView(tv);
        }
        box.setOnClickListener(listener);
        box.setMinimumWidth(dp(48)); box.setMinimumHeight(dp(48));
        box.setContentDescription(label == null ? toolDescription(iconName) : label); box.setFocusable(true);
        return box;
    }

    private String toolDescription(String icon) {
        switch (icon) {
            case "prev": return "이전 파일"; case "next": return "다음 파일";
            case "zoomOut": return "축소"; case "zoomIn": return "확대"; case "fit": return "화면에 맞추기";
            case "rotL": return "왼쪽으로 회전"; case "rotR": return "오른쪽으로 회전";
            case "flipH": return "좌우 뒤집기"; case "flipV": return "상하 뒤집기";
            case "undo": return "실행 취소"; case "redo": return "다시 실행";
            case "save": return "저장"; case "export": return "내보내기"; case "play": return "슬라이드쇼";
            case "checker": return "투명 배경 표시"; case "bookmark": return "책갈피"; case "trash": return "삭제";
            default: return "보기 도구";
        }
    }
    private View createPillTextBtn(String text, boolean active, int defaultCol, View.OnClickListener listener) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(active ? Color.WHITE : defaultCol);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        if (active) {
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(0xFF0381FE);
            gd.setCornerRadius(dp(16));
            tv.setBackground(gd);
        }
        tv.setOnClickListener(listener);
        tv.setMinimumHeight(dp(48)); tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private View createToolbarDivider() {
        View v = new View(this);
        v.setBackgroundColor(isDarkTheme ? 0x33FFFFFF : 0x22000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(20));
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        v.setLayoutParams(lp);
        return v;
    }

    private void setTool(String tool) {
        if ("adjust".equals(stageCanvasView.getActiveTool()) && !"adjust".equals(tool)) {
            resetColorAdjustments();
        }
        stageCanvasView.setActiveTool(tool);
        if ("adjust".equals(tool)) {
            showColorAdjustDialog();
        }
        refreshToolbars();
    }

    // ---------- File Loading & Non-Rooted Content URI Handling ----------
    private boolean handleIncomingIntent(Intent intent) {
        if (intent == null) return false;
        List<Uri> incoming = new ArrayList<>();
        Uri data = intent.getData();
        if (data != null) incoming.add(data);
        if (intent.getClipData() != null) for (int i = 0; i < intent.getClipData().getItemCount(); i++) { Uri uri = intent.getClipData().getItemAt(i).getUri(); if (uri != null && !incoming.contains(uri)) incoming.add(uri); }
        if (Intent.ACTION_SEND.equals(intent.getAction())) { Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM); if (uri != null && !incoming.contains(uri)) incoming.add(uri); }
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) { ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM); if (uris != null) for (Uri uri : uris) if (!incoming.contains(uri)) incoming.add(uri); }
        if (incoming.isEmpty()) return false;
        openUris(incoming, intent.getFlags()); return true;
    }

    private File copyContentUriToInbox(Uri uri) {
        if (uri == null) return null;
        try {
            return documents.importFile(uri);
        } catch (Exception e) { toast("파일을 가져올 수 없습니다: " + e.getMessage()); return null; }
    }
    private void openUris(List<Uri> uris, int flags) { openUris(uris,flags,() -> {}); }
    private void openUris(List<Uri> uris, int flags, Runnable completed) {
        final List<Uri> files=FileDrop.localUris(uris); final Runnable finish=FileDrop.once(completed);
        if(files.isEmpty()){finish.run();return;}
        guardChanges(() -> {
            final int ticket=++openTicket;
            if(emptyWelcomeView instanceof WelcomeView)((WelcomeView)emptyWelcomeView).setOpening(true);
            try { filePool.execute(() -> {
                List<FileEntry> entries=new ArrayList<>();
                try {
                    for(Uri uri:files) {
                        if(ticket!=openTicket || Thread.currentThread().isInterrupted())return;
                        documents.retain(uri,flags); File file=copyContentUriToInbox(uri);
                        if(file!=null && !FileOps.kindOf(file.getName()).isEmpty())entries.add(new FileEntry(file.getAbsolutePath(),file.getName(),FileOps.kindOf(file.getName()),file.length(),file.lastModified()));
                    }
                    mainHandler.post(() -> {
                        if(isFinishing() || isDestroyed() || ticket!=openTicket)return;
                        if(entries.isEmpty()){toast("열 수 있는 파일이 없습니다.");return;}
                        guardChanges(() -> {folderItems.clear();folderItems.addAll(entries);openFileUnchecked(entries.get(0).path);});
                    });
                } finally {finish.run();mainHandler.post(() -> {if(ticket==openTicket && emptyWelcomeView instanceof WelcomeView)((WelcomeView)emptyWelcomeView).setOpening(false);});}
            }); } catch(java.util.concurrent.RejectedExecutionException closed) { finish.run(); }
        },finish);
    }
    private void guardChanges(Runnable action) {
        guardChanges(action,() -> {});
    }
    private void guardChanges(Runnable action, Runnable cancelled) {
        if (!isModified) { action.run(); return; }
        new AlertDialog.Builder(this).setTitle("변경 내용을 저장할까요?").setMessage("저장하지 않은 편집 내용이 있습니다.")
            .setNegativeButton("계속 편집", (d,w) -> cancelled.run()).setOnCancelListener(d -> cancelled.run()).setNeutralButton("변경 버리기", (d,w) -> { isModified = false; action.run(); })
            .setPositiveButton("저장 후 계속", (d,w) -> { afterSave = action; afterSaveCancelled=cancelled; saveCurrentFile(false); }).show();
    }
    private void cancelAfterSave(){Runnable cancelled=afterSaveCancelled;afterSave=afterSaveCancelled=null;if(cancelled!=null)cancelled.run();}
    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQ_OPEN_TREE);
    }
    private void showTreeFolder(Uri tree, String parentId) {
        bgPool.execute(() -> {
            try {
                List<DocumentStore.Entry> entries = documents.children(tree, parentId);
                List<String> labels = new ArrayList<>(); for (DocumentStore.Entry entry : entries) labels.add((entry.directory ? "폴더 · " : "") + entry.name);
                mainHandler.post(() -> new AlertDialog.Builder(this).setTitle("선택한 폴더").setItems(labels.toArray(new String[0]), (d,w) -> {
                    DocumentStore.Entry chosen = entries.get(w);
                    if (chosen.directory) { showTreeFolder(tree, android.provider.DocumentsContract.getDocumentId(chosen.uri)); return; }
                    guardChanges(() -> {
                        try {
                            folderItems.clear(); String selected = "";
                            for (DocumentStore.Entry entry : entries) if (!entry.directory) {
                                String path = documents.register(entry.uri, entry.name);
                                folderItems.add(new FileEntry(path, entry.name, FileOps.kindOf(entry.name), entry.size, entry.modified));
                                if (entry == chosen) selected = path;
                            }
                            sortFolderItems(); openFileAtPath(selected);
                        } catch (Exception e) { toast(e.getMessage()); }
                    });
                }).setNegativeButton("닫기", null).setNeutralButton("폴더 다시 선택", (d,w) -> chooseFolder()).show());
            } catch (Exception e) { toast("폴더를 열 수 없습니다: " + e.getMessage()); }
        });
    }
    private void loadFolderList(String dirPath, String selectFilePath) {
        folderItems.clear();
        File dir = new File(dirPath);
        if (dir.exists() && dir.isFile()) {
            selectFilePath = dir.getAbsolutePath();
            dir = dir.getParentFile();
        }
        if (dir != null && dir.exists() && dir.isDirectory()) {
            File[] raw = dir.listFiles();
            if (raw != null) {
                for (File f : raw) {
                    if (f.isFile() && !f.getName().startsWith(".")) {
                        String k = FileOps.kindOf(f.getName());
                        if (!k.isEmpty()) {
                            folderItems.add(new FileEntry(f.getAbsolutePath(), f.getName(), k, f.length(), f.lastModified()));
                        }
                    }
                }
            }
        }
        sortFolderItems();

        if (selectFilePath != null) {
            openFileAtPath(selectFilePath);
        } else if (!folderItems.isEmpty()) {
            openFileAtPath(folderItems.get(0).path);
        } else {
            currentPath = "";
            currentKind = "";
            attachActiveStageView();
            updateHeaderTitles();
            refreshToolbars();
            refreshSidebarContent();
        }
    }

    private void sortFolderItems() {
        Collections.sort(folderItems, new Comparator<FileEntry>() {
            @Override
            public int compare(FileEntry a, FileEntry b) {
                if ("date".equals(sortOrder)) return Long.compare(b.lastModified, a.lastModified);
                if ("size".equals(sortOrder)) return Long.compare(b.size, a.size);
                return Util.NATURAL_SORT.compare(a.name, b.name);
            }
        });
        currentFileIndex = -1;
        for (int i = 0; i < folderItems.size(); i++) {
            if (folderItems.get(i).path.equalsIgnoreCase(currentPath)) {
                currentFileIndex = i;
                break;
            }
        }
    }

    public void openFileAtPath(final String path) {
        guardChanges(() -> openFileUnchecked(path));
    }
    private void openFileUnchecked(final String path) {
        if (path == null || path.isEmpty()) return;
        try { documents.ensureLocal(path); } catch (Exception e) { toast("파일을 읽을 수 없습니다: " + e.getMessage()); return; }
        final File f = new File(path);
        if (!f.exists()) {
            toast("파일이 존재하지 않습니다");
            return;
        }
        boolean fromWelcome=currentPath.isEmpty();
        nativeMediaView.stopMedia();
        searchTicket++; imageLoadTicket++; PdfHost.closeAll();
        currentPath = f.getAbsolutePath();
        currentKind = FileOps.kindOf(currentPath);
        isModified = false;
        flattenedPdfApproved = false; pdfUndo.clear(); pdfRedo.clear(); lastPdfKey = "";
        imgUndoStack.clear();
        imgRedoStack.clear();
        currentOcrBoxes.clear();
        activeOcrHit = -1;
        fullExtractedOcrText = "";

        // Sync folder items if opened from a different folder
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

        if(fromWelcome)buildCompleteNativeUI();else attachActiveStageView();

        if ("image".equals(currentKind)) {
            loadOriginalImage(currentPath);
        } else if ("pdf".equals(currentKind)) {
            stageCanvasView.clearContent(); openPdfWithPassword(currentPath, "");
        } else if ("doc".equals(currentKind)) {
            nativeDocView.loadDocument(currentPath);
        } else if ("media".equals(currentKind)) {
            nativeMediaView.loadMedia(currentPath);
        }

        if (replaceRow != null) {
            replaceRow.setVisibility("doc".equals(currentKind) ? View.VISIBLE : View.GONE);
        }
        if (sidebarPdfActionRow != null) {
            sidebarPdfActionRow.setVisibility("pdf".equals(currentKind) ? View.VISIBLE : View.GONE);
        }

        updateHeaderTitles();
        refreshToolbars();
        updateStatusBar();
        if (sidebarVisible) refreshSidebarContent();
        if (infoVisible) refreshInfoPanelContent();
    }

    private void loadOriginalImage(String path) {
        final int ticket=imageLoadTicket;
        stageCanvasView.clearContent();stageCanvasView.setActiveTool("hand");
        final android.widget.ProgressBar loading=new android.widget.ProgressBar(this);
        loading.setTag("image-loading");loading.setContentDescription("원본 이미지를 여는 중");
        stageHostFrame.addView(loading,new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.CENTER));
        bgPool.execute(() -> {
            Bitmap bitmap=null;Movie movie=null;String error=null;
            try {
                bitmap=ImageEditOps.decodeOriginal(path);
                if(".gif".equals(Util.extOf(path)))try{byte[] bytes=Util.readAllBytes(new File(path));movie=Movie.decodeByteArray(bytes,0,bytes.length);if(movie!=null && movie.duration()<=0)movie=null;}catch(Exception|OutOfMemoryError ignored){}
            }catch(Exception failure){error=failure.getMessage();}
            final Bitmap ready=bitmap;final Movie gif=movie;final String problem=error;
            mainHandler.post(() -> {
                if(loading.getParent() instanceof ViewGroup)((ViewGroup)loading.getParent()).removeView(loading);
                if(isFinishing() || isDestroyed() || ticket!=imageLoadTicket || !path.equals(currentPath)){if(ready!=null)ready.recycle();return;}
                if(ready==null){toast(problem==null?"이미지를 열 수 없습니다.":problem);return;}
                stageCanvasView.setImageContent(ready,gif,new ArrayList<MarkupModel.Annotation>(),true);
                updateHeaderTitles();updateStatusBar();refreshSidebarContent();refreshToolbars();
                if(slideshowRunning)updateSlideshowView();
            });
        });
    }
    private void navigateFile(int delta) {
        if (folderItems.isEmpty()) return;
        int nextIdx = (currentFileIndex + delta + folderItems.size()) % folderItems.size();
        openFileAtPath(folderItems.get(nextIdx).path);
        if (slideshowRunning) {
            updateSlideshowView();
        }
    }
    private void openPdfWithPassword(final String path, String password) {
        Map<String, Object> result = PdfHost.open(path, password);
        if (!Boolean.TRUE.equals(result.get("ok"))) {
            if (Boolean.TRUE.equals(result.get("locked"))) {
                final EditText input = new EditText(this);
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                input.setHint("PDF 암호");
                new AlertDialog.Builder(this).setTitle("암호로 보호된 PDF").setMessage(String.valueOf(result.get("error"))).setView(input)
                    .setNegativeButton("취소", null).setPositiveButton("열기", (d, w) -> { if (path.equals(currentPath)) openPdfWithPassword(path, input.getText().toString()); }).show();
            } else toast(String.valueOf(result.get("error")));
            return;
        }
        int count = PdfHost.getPageCount(path); List<MarkupModel.PdfPageItem> pages = new ArrayList<>(count);
        for (int i = 0; i < count; i++) { float[] size = PdfHost.getPageSizePt(path, i); pages.add(new MarkupModel.PdfPageItem("p_" + i, "pdf", path, i, size[0], size[1])); }
        stageCanvasView.setActiveTool("hand"); stageCanvasView.setPdfContent(pages, stageCanvasView.getPdfViewMode(), 0);
        recordPdfState();
        refreshSidebarContent(); refreshToolbars(); updateStatusBar();
    }

    private void updateHeaderTitles() {
        if (headerTitleView == null || headerSubView == null) return;
        if (currentPath.isEmpty()) {
            headerTitleView.setText("Samsung Viewer");
            headerSubView.setText("파일을 선택하세요");
            return;
        }
        File f = new File(currentPath);
        headerTitleView.setText((isModified ? "● " : "") + f.getName());
        String pos = folderItems.isEmpty() ? "" : ((currentFileIndex + 1) + " / " + folderItems.size() + " · ");
        if ("image".equals(currentKind) && stageCanvasView.getImageBitmap() != null) {
            Bitmap b = stageCanvasView.getImageBitmap();
            headerSubView.setText(pos + b.getWidth() + "×" + b.getHeight() + " px · " + fmtBytes(f.length()));
        } else if ("pdf".equals(currentKind)) {
            int p = stageCanvasView.getCurrentPdfPage() + 1;
            int tot = stageCanvasView.getPdfPages().size();
            headerSubView.setText(pos + "PDF " + p + " / " + tot + " 페이지 · " + fmtBytes(f.length()));
        } else {
            headerSubView.setText(pos + Util.extOf(currentPath).toUpperCase(Locale.ROOT).replace(".", "") + " · " + fmtBytes(f.length()));
        }
    }

    private void updateStatusBar() {
        if (statusLeftText == null || statusCenterText == null || statusRightText == null) return;
        if (currentPath.isEmpty()) {
            statusLeftText.setText("준비됨");
            statusCenterText.setText("");
            statusRightText.setText("9.5.0");
            return;
        }
        File f = new File(currentPath);
        String idxStr = folderItems.isEmpty() ? "1 / 1" : ((currentFileIndex + 1) + " / " + folderItems.size());
        statusLeftText.setText(idxStr + "  |  " + f.getName() + "  |  " + fmtBytes(f.length()));

        if ("image".equals(currentKind) && stageCanvasView.getImageBitmap() != null) {
            Bitmap b = stageCanvasView.getImageBitmap();
            statusCenterText.setText(b.getWidth() + " × " + b.getHeight() + " px  |  배율 " + Math.round(stageCanvasView.getZoom() * 100f) + "%");
        } else if ("pdf".equals(currentKind)) {
            statusCenterText.setText("페이지 " + (stageCanvasView.getCurrentPdfPage() + 1) + " / " + stageCanvasView.getPdfPages().size()
                    + "  |  배율 " + Math.round(stageCanvasView.getZoom() * 100f) + "%");
        } else if ("doc".equals(currentKind)) {
            statusCenterText.setText("문서 뷰어  |  배율 " + Math.round(nativeDocView.getZoomScale() * 100f) + "%");
        } else {
            statusCenterText.setText("미디어 플레이어");
        }
    }

    // ---------- Populate Sidebar Content ----------
    private void refreshSidebarContent() {
        if (sidebarListContainer == null) return;
        sidebarListContainer.removeAllViews();

        int activeCol = 0xFF0381FE;
        int inactiveCol = isDarkTheme ? 0xFF9AA3B5 : 0xFF5D6575;
        if (tabBtnThumbs != null) {
            tabBtnThumbs.setTextColor("thumbs".equals(sidebarTab) ? activeCol : inactiveCol);
            tabBtnAnns.setTextColor("anns".equals(sidebarTab) ? activeCol : inactiveCol);
            tabBtnBookmarks.setTextColor("bookmarks".equals(sidebarTab) ? activeCol : inactiveCol);
        }

        int textPrimary = isDarkTheme ? 0xFFF1F3F9 : 0xFF15181E;
        int textSecondary = isDarkTheme ? 0xFF9AA3B5 : 0xFF5D6575;

        if ("thumbs".equals(sidebarTab)) {
            if ("pdf".equals(currentKind) && !stageCanvasView.getPdfPages().isEmpty()) {
                // Show PDF Pages + Quick Switch to Folder Files
                TextView secTitle = new TextView(this);
                secTitle.setText("PDF 페이지 (" + stageCanvasView.getPdfPages().size() + ")");
                secTitle.setTextColor(textSecondary);
                secTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                secTitle.setTypeface(Typeface.DEFAULT_BOLD);
                secTitle.setPadding(dp(4), dp(4), dp(4), dp(6));
                sidebarListContainer.addView(secTitle);

                List<MarkupModel.PdfPageItem> pages = stageCanvasView.getPdfPages();
                for (int i = 0; i < pages.size(); i++) {
                    final int pIdx = i;
                    MarkupModel.PdfPageItem page = pages.get(i);
                    boolean isCur = (i == stageCanvasView.getCurrentPdfPage());

                    LinearLayout item = new LinearLayout(this);
                    item.setOrientation(LinearLayout.HORIZONTAL);
                    item.setGravity(Gravity.CENTER_VERTICAL);
                    item.setPadding(dp(10), dp(8), dp(10), dp(8));
                    GradientDrawable gd = new GradientDrawable();
                    gd.setCornerRadius(dp(12));
                    gd.setColor(isCur ? (isDarkTheme ? 0x330381FE : 0x1F0381FE) : Color.TRANSPARENT);
                    if (isCur) gd.setStroke(dp(1), 0xFF0381FE);
                    item.setBackground(gd);

                    ImageView thumbIv = new ImageView(this);
                    thumbIv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    Bitmap tBmp = page.getOrRenderBaseBitmap(180);
                    thumbIv.setImageBitmap(tBmp);
                    item.addView(thumbIv, new LinearLayout.LayoutParams(dp(42), dp(54)));

                    LinearLayout meta = new LinearLayout(this);
                    meta.setOrientation(LinearLayout.VERTICAL);
                    meta.setPadding(dp(10), 0, 0, 0);
                    TextView t1 = new TextView(this);
                    t1.setText((i + 1) + " 페이지");
                    t1.setTextColor(textPrimary);
                    t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
                    t1.setTypeface(Typeface.DEFAULT_BOLD);
                    TextView t2 = new TextView(this);
                    t2.setText(Math.round(page.displayWidthPt()) + "×" + Math.round(page.displayHeightPt()) + " pt"
                            + (page.anns.isEmpty() ? "" : " · 주석 " + page.anns.size()));
                    t2.setTextColor(textSecondary);
                    t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                    meta.addView(t1);
                    meta.addView(t2);
                    item.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    item.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            stageCanvasView.setCurrentPdfPage(pIdx);
                            if (!isTabletLayout) {
                                sidebarVisible = false;
                                updateDrawerAndPanelVisibility();
                            }
                        }
                    });
                    sidebarListContainer.addView(item);
                }
            }

            // Folder Files List
            TextView folderHeader = new TextView(this);
            folderHeader.setText("현재 폴더 파일 (" + folderItems.size() + ")");
            folderHeader.setTextColor(textSecondary);
            folderHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            folderHeader.setTypeface(Typeface.DEFAULT_BOLD);
            folderHeader.setPadding(dp(4), dp(10), dp(4), dp(6));
            sidebarListContainer.addView(folderHeader);

            for (int i = 0; i < folderItems.size(); i++) {
                final FileEntry fe = folderItems.get(i);
                boolean active = fe.path.equalsIgnoreCase(currentPath);

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                GradientDrawable gd = new GradientDrawable();
                gd.setCornerRadius(dp(12));
                gd.setColor(active ? (isDarkTheme ? 0x330381FE : 0x1F0381FE) : Color.TRANSPARENT);
                if (active) gd.setStroke(dp(1), 0xFF0381FE);
                row.setBackground(gd);

                String iconName = "image".equals(fe.kind) ? "image" : "pdf".equals(fe.kind) ? "pdf" : "doc".equals(fe.kind) ? "doc" : "play";
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(VectorIcons.create(this, iconName, 20f, active ? 0xFF0381FE : textSecondary));
                row.addView(iv, new LinearLayout.LayoutParams(dp(24), dp(24)));

                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(10), 0, 0, 0);

                TextView nTv = new TextView(this);
                nTv.setText(fe.name);
                nTv.setTextColor(textPrimary);
                nTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                nTv.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                nTv.setSingleLine(true);

                TextView sTv = new TextView(this);
                sTv.setText(fmtBytes(fe.size));
                sTv.setTextColor(textSecondary);
                sTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);

                col.addView(nTv);
                col.addView(sTv);
                row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openFileAtPath(fe.path);
                        if (!isTabletLayout) {
                            sidebarVisible = false;
                            updateDrawerAndPanelVisibility();
                        }
                    }
                });
                sidebarListContainer.addView(row);
            }
        } else if ("anns".equals(sidebarTab)) {
            List<MarkupModel.Annotation> anns = new ArrayList<>();
            if ("image".equals(currentKind)) {
                anns.addAll(stageCanvasView.getImageAnnotations());
            } else if ("pdf".equals(currentKind)) {
                for (MarkupModel.PdfPageItem p : stageCanvasView.getPdfPages()) {
                    anns.addAll(p.anns);
                }
            }
            if (anns.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("추가된 주석이 없습니다.\n마크업 도구로 펜, 형광펜, 도형, 텍스트, 서명을 추가해 보세요.");
                empty.setTextColor(textSecondary);
                empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                empty.setPadding(dp(10), dp(20), dp(10), dp(20));
                sidebarListContainer.addView(empty);
            } else {
                for (final MarkupModel.Annotation a : anns) {
                    LinearLayout r = new LinearLayout(this);
                    r.setOrientation(LinearLayout.HORIZONTAL);
                    r.setGravity(Gravity.CENTER_VERTICAL);
                    r.setPadding(dp(10), dp(8), dp(10), dp(8));

                    View dot = new View(this);
                    GradientDrawable dgd = new GradientDrawable();
                    dgd.setShape(GradientDrawable.OVAL);
                    dgd.setColor(a.color);
                    dot.setBackground(dgd);
                    r.addView(dot, new LinearLayout.LayoutParams(dp(12), dp(12)));

                    TextView tv = new TextView(this);
                    tv.setText("  " + a.label());
                    tv.setTextColor(textPrimary);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                    r.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    ImageView del = new ImageView(this);
                    del.setImageDrawable(VectorIcons.create(this, "trash", 18f, 0xFFEF5350));
                    del.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if ("image".equals(currentKind)) {
                                stageCanvasView.getImageAnnotations().remove(a);
                            } else if ("pdf".equals(currentKind)) {
                                for (MarkupModel.PdfPageItem p : stageCanvasView.getPdfPages()) {
                                    p.anns.remove(a);
                                }
                            }
                            stageCanvasView.invalidate();
                            refreshSidebarContent();
                        }
                    });
                    r.addView(del, new LinearLayout.LayoutParams(dp(24), dp(24)));
                    sidebarListContainer.addView(r);
                }
            }
        } else if ("bookmarks".equals(sidebarTab)) {
            if (bookmarks.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("저장된 책갈피가 없습니다.\n하단 툴바의 책갈피 아이콘을 눌러 자주 보는 파일을 등록하세요.");
                empty.setTextColor(textSecondary);
                empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                empty.setPadding(dp(10), dp(20), dp(10), dp(20));
                sidebarListContainer.addView(empty);
            } else {
                for (final String bPath : new ArrayList<>(bookmarks)) {
                    final File bf = new File(bPath);
                    LinearLayout r = new LinearLayout(this);
                    r.setOrientation(LinearLayout.HORIZONTAL);
                    r.setGravity(Gravity.CENTER_VERTICAL);
                    r.setPadding(dp(10), dp(9), dp(10), dp(9));

                    TextView tv = new TextView(this);
                    tv.setText(bf.getName());
                    tv.setTextColor(textPrimary);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                    tv.setSingleLine(true);
                    tv.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (bf.exists()) {
                                openFileAtPath(bPath);
                                if (!isTabletLayout) {
                                    sidebarVisible = false;
                                    updateDrawerAndPanelVisibility();
                                }
                            } else {
                                toast("파일을 찾을 수 없습니다");
                            }
                        }
                    });
                    r.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    ImageView del = new ImageView(this);
                    del.setImageDrawable(VectorIcons.create(this, "close", 16f, textSecondary));
                    del.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            toggleBookmark(bPath);
                            refreshSidebarContent();
                        }
                    });
                    r.addView(del, new LinearLayout.LayoutParams(dp(22), dp(22)));
                    sidebarListContainer.addView(r);
                }
            }
        }
    }

    // ---------- Populate Info / EXIF / Histogram / AI Metadata Panel ----------
    @SuppressWarnings("unchecked")
    private void refreshInfoPanelContent() {
        if (infoContentBody == null) return;
        infoContentBody.removeAllViews();

        int textPrimary = isDarkTheme ? 0xFFF1F3F9 : 0xFF15181E;
        int textSecondary = isDarkTheme ? 0xFF9AA3B5 : 0xFF5D6575;
        int cardBg = isDarkTheme ? 0xFF1F2430 : 0xFFF4F6FA;

        if (currentPath.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("열려 있는 파일이 없습니다.");
            tv.setTextColor(textSecondary);
            infoContentBody.addView(tv);
            return;
        }

        Map<String, Object> info = FileOps.info(currentPath);

        // 1. Live RGB/Luma Histogram Card (for Images)
        if ("image".equals(currentKind) && stageCanvasView.getImageBitmap() != null) {
            addInfoSectionHeader("히스토그램 (RGB / 밝기)", textSecondary);
            final Bitmap bmp = stageCanvasView.getImageBitmap();
            View histView = new View(this) {
                private int[] rHist, gHist, bHist;
                private int maxCount = 1;
                private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

                {
                    computeHistogram();
                }

                private void computeHistogram() {
                    rHist = new int[256];
                    gHist = new int[256];
                    bHist = new int[256];
                    try {
                        Bitmap small = Bitmap.createScaledBitmap(bmp, Math.min(160, bmp.getWidth()), Math.min(160, bmp.getHeight()), false);
                        int w = small.getWidth(), h = small.getHeight();
                        int[] px = new int[w * h];
                        small.getPixels(px, 0, w, 0, 0, w, h);
                        for (int c : px) {
                            if (((c >>> 24) & 0xFF) < 16) continue;
                            rHist[(c >> 16) & 0xFF]++;
                            gHist[(c >> 8) & 0xFF]++;
                            bHist[c & 0xFF]++;
                        }
                        for (int i = 4; i < 252; i++) {
                            maxCount = Math.max(maxCount, Math.max(rHist[i], Math.max(gHist[i], bHist[i])));
                        }
                        if (small != bmp) small.recycle();
                    } catch (Exception ignored) {}
                }

                @Override
                protected void onDraw(Canvas canvas) {
                    super.onDraw(canvas);
                    float w = getWidth(), h = getHeight();
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(dp(1.5f));
                    drawChannel(canvas, rHist, 0xCCEF5350, w, h);
                    drawChannel(canvas, gHist, 0xCC66BB6A, w, h);
                    drawChannel(canvas, bHist, 0xCC42A5F5, w, h);
                }

                private void drawChannel(Canvas canvas, int[] hist, int color, float w, float h) {
                    if (hist == null) return;
                    p.setColor(color);
                    Path path = new Path();
                    for (int i = 0; i < 256; i++) {
                        float x = (i / 255f) * w;
                        float ratio = Math.min(1f, (float) hist[i] / Math.max(1, maxCount));
                        float y = h - ratio * (h - dp(8)) - dp(4);
                        if (i == 0) path.moveTo(x, y);
                        else path.lineTo(x, y);
                    }
                    canvas.drawPath(path, p);
                }
            };
            GradientDrawable hBg = new GradientDrawable();
            hBg.setColor(cardBg);
            hBg.setCornerRadius(dp(14));
            histView.setBackground(hBg);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96));
            hlp.bottomMargin = dp(12);
            infoContentBody.addView(histView, hlp);
        }

        // 2. Basic File Info
        addInfoSectionHeader("기본 정보", textSecondary);
        addInfoRow("파일 이름", String.valueOf(info.get("name")), textPrimary, textSecondary);
        addInfoRow("형식", String.valueOf(info.get("ext")), textPrimary, textSecondary);
        addInfoRow("파일 크기", fmtBytes(Util.toLong(info.get("size"), 0)), textPrimary, textSecondary);
        addInfoRow("수정일", String.valueOf(info.get("modified")), textPrimary, textSecondary);
        addInfoRow("경로", String.valueOf(info.get("path")), textPrimary, textSecondary);

        if ("image".equals(currentKind) && stageCanvasView.getImageBitmap() != null) {
            Bitmap b = stageCanvasView.getImageBitmap();
            addInfoRow("해상도", b.getWidth() + " × " + b.getHeight() + " px", textPrimary, textSecondary);
        } else if ("pdf".equals(currentKind)) {
            addInfoRow("총 페이지 수", stageCanvasView.getPdfPages().size() + " 페이지", textPrimary, textSecondary);
        }

        // 3. EXIF Camera Info
        String[] exifKeys = {"camera", "lens", "taken", "exposure", "aperture", "focal", "iso", "software", "gps"};
        String[] exifLabels = {"카메라", "렌즈", "촬영 일시", "노출 시간", "조리개", "초점 거리", "ISO 감도", "소프트웨어", "GPS 좌표"};
        boolean hasExif = false;
        for (String k : exifKeys) {
            if (info.get(k) != null) { hasExif = true; break; }
        }
        if (hasExif) {
            addInfoSectionHeader("EXIF 촬영 정보", textSecondary);
            for (int i = 0; i < exifKeys.length; i++) {
                Object val = info.get(exifKeys[i]);
                if (val != null) {
                    addInfoRow(exifLabels[i], String.valueOf(val), textPrimary, textSecondary);
                }
            }
        }

        // 4. AI Generation Metadata (Stable Diffusion / ComfyUI / NovelAI / Midjourney)
        Object metaObj = info.get("meta");
        if (metaObj instanceof List) {
            AiMeta.ParsedAi ai = AiMeta.parseAiMeta((List<Object>) metaObj);
            if (ai != null) {
                addInfoSectionHeader("AI 생성 정보 (" + (ai.tool.isEmpty() ? "AI 메타데이터" : ai.tool) + ")", textSecondary);

                if (!ai.prompt.isEmpty()) {
                    addCopyableAiBlock("프롬프트 (Prompt)", ai.prompt, cardBg, textPrimary, textSecondary);
                }
                if (!ai.negative.isEmpty()) {
                    addCopyableAiBlock("네거티브 프롬프트 (Negative)", ai.negative, cardBg, textPrimary, textSecondary);
                }
                if (!ai.settings.isEmpty()) {
                    for (Map.Entry<String, String> e : ai.settings.entrySet()) {
                        addInfoRow(e.getKey(), e.getValue(), textPrimary, textSecondary);
                    }
                }
            }
        }
    }

    private void addInfoSectionHeader(String title, int color) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(10), 0, dp(6));
        infoContentBody.addView(tv);
    }

    private void addInfoRow(String label, String value, int textPrimary, int textSecondary) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView k = new TextView(this);
        k.setText(label);
        k.setTextColor(textSecondary);
        k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        row.addView(k, new LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(textPrimary);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        v.setTextIsSelectable(true);
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        infoContentBody.addView(row);
    }

    private void addCopyableAiBlock(String title, final String content, int cardBg, int textPrimary, int textSecondary) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(10);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(cardBg);
        gd.setCornerRadius(dp(12));
        box.setBackground(gd);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView lb = new TextView(this);
        lb.setText(title);
        lb.setTextColor(textSecondary);
        lb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        lb.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(lb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        top.addView(createSmallActionChip("복사", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToClipboard(content);
                toast("클립보드에 복사했습니다");
            }
        }));
        box.addView(top);

        TextView body = new TextView(this);
        body.setText(content);
        body.setTextColor(textPrimary);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        body.setTypeface(Typeface.MONOSPACE);
        body.setPadding(0, dp(6), 0, 0);
        body.setTextIsSelectable(true);
        box.addView(body);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        infoContentBody.addView(box, lp);
    }

    // ---------- Image Editing Operations & Undo/Redo ----------
    private void markModified() { isModified = true; if ("pdf".equals(currentKind)) recordPdfState(); }
    private String pdfKey() {
        StringBuilder key = new StringBuilder();
        for (MarkupModel.PdfPageItem p : stageCanvasView.getPdfPages()) {
            key.append(p.uid).append(':').append(p.rot).append('|');
            for (MarkupModel.Annotation a : p.anns) { key.append(a.id).append(a.type).append(a.color).append(a.strokeWidth).append(a.fontSize).append(a.fillSolid).append(a.x).append(a.y).append(a.w).append(a.h).append(a.x2).append(a.y2).append(a.text); for (float[] point : a.points) key.append(point[0]).append(',').append(point[1]).append(';'); }
        }
        return key.toString();
    }
    private List<MarkupModel.PdfPageItem> copyPdfState() {
        List<MarkupModel.PdfPageItem> copy = new ArrayList<>();
        for (MarkupModel.PdfPageItem page : stageCanvasView.getPdfPages()) {
            MarkupModel.PdfPageItem p = new MarkupModel.PdfPageItem(page.uid, page.kind, page.srcPath, page.srcPageIdx, page.wPt, page.hPt);
            p.rot = page.rot; p.customBitmap = page.customBitmap; p.anns.addAll(MarkupModel.copyList(page.anns)); copy.add(p);
        }
        return copy;
    }
    private void recordPdfState() {
        String key = pdfKey(); if (key.equals(lastPdfKey)) return;
        lastPdfKey = key; pdfUndo.add(copyPdfState()); pdfRedo.clear(); if (pdfUndo.size() > 30) pdfUndo.remove(0);
    }
    private void undoPdf(boolean redo) {
        if (!"pdf".equals(currentKind)) return;
        if (redo) { if (pdfRedo.isEmpty()) return; pdfUndo.add(pdfRedo.remove(pdfRedo.size() - 1)); }
        else { if (pdfUndo.size() < 2) return; pdfRedo.add(pdfUndo.remove(pdfUndo.size() - 1)); }
        List<MarkupModel.PdfPageItem> state = pdfUndo.get(pdfUndo.size() - 1);
        stageCanvasView.setPdfContent(new ArrayList<>(state), stageCanvasView.getPdfViewMode(), Math.min(stageCanvasView.getCurrentPdfPage(), state.size() - 1));
        List<MarkupModel.PdfPageItem> detached = copyPdfState(); stageCanvasView.setPdfContent(detached, stageCanvasView.getPdfViewMode(), stageCanvasView.getCurrentPdfPage());
        lastPdfKey = pdfKey(); isModified = true; updateHeaderTitles(); refreshSidebarContent();
    }
    private void pushImageUndo() {
        if (stageCanvasView.getImageBitmap() == null) return;
        imgUndoStack.add(new ImageSnapshot(stageCanvasView.getImageBitmap(), stageCanvasView.getImageAnnotations()));
        long historyBytes=0,budget=Math.max(32L*1024*1024,Runtime.getRuntime().maxMemory()/4);
        for(ImageSnapshot snapshot:imgUndoStack)if(snapshot.bmp!=null)historyBytes+=snapshot.bmp.getAllocationByteCount();
        while(imgUndoStack.size()>1 && (imgUndoStack.size()>15 || historyBytes>budget)){ImageSnapshot old=imgUndoStack.remove(0);if(old.bmp!=null)historyBytes-=old.bmp.getAllocationByteCount();}
        imgRedoStack.clear();
    }

    private void undoImageEdit() {
        if (imgUndoStack.isEmpty()) {
            toast("실행 취소할 내역이 없습니다");
            return;
        }
        imgRedoStack.add(new ImageSnapshot(stageCanvasView.getImageBitmap(), stageCanvasView.getImageAnnotations()));
        ImageSnapshot prev = imgUndoStack.remove(imgUndoStack.size() - 1);
        stageCanvasView.setImageContent(prev.bmp, null, prev.anns, false);
        markModified();
        updateHeaderTitles();
        updateStatusBar();
    }

    private void redoImageEdit() {
        if (imgRedoStack.isEmpty()) {
            toast("다시 실행할 내역이 없습니다");
            return;
        }
        imgUndoStack.add(new ImageSnapshot(stageCanvasView.getImageBitmap(), stageCanvasView.getImageAnnotations()));
        ImageSnapshot next = imgRedoStack.remove(imgRedoStack.size() - 1);
        stageCanvasView.setImageContent(next.bmp, null, next.anns, false);
        markModified();
        updateHeaderTitles();
        updateStatusBar();
    }

    private void rotateCurrentImage(int deg) {
        if (stageCanvasView.getImageBitmap() == null) return;
        pushImageUndo();
        Bitmap rotated = ImageEditOps.rotate(stageCanvasView.getImageBitmap(), deg);
        stageCanvasView.setImageContent(rotated, null, stageCanvasView.getImageAnnotations(), true);
        markModified();
        updateHeaderTitles();
        updateStatusBar();
    }

    private void flipCurrentImage(boolean h, boolean v) {
        if (stageCanvasView.getImageBitmap() == null) return;
        pushImageUndo();
        Bitmap flipped = ImageEditOps.flip(stageCanvasView.getImageBitmap(), h, v);
        stageCanvasView.setImageContent(flipped, null, stageCanvasView.getImageAnnotations(), false);
        markModified();
        updateHeaderTitles();
    }

    private void updateLiveAdjustPreview() {
        stageCanvasView.setLiveColorFilter(
                ImageEditOps.buildColorFilter(adjExp, adjCon, adjSat, adjWarm, adjSepia, adjInvert)
        );
    }

    private void resetColorAdjustments() {
        adjExp = 0f; adjCon = 0f; adjSat = 0f; adjWarm = 0f; adjSharp = 0f;
        adjSepia = false; adjInvert = false;
        stageCanvasView.setLiveColorFilter(null);
    }

    private void commitColorAdjustments() {
        if (stageCanvasView.getImageBitmap() == null) return;
        pushImageUndo();
        Bitmap adjusted = ImageEditOps.applyAdjust(
                stageCanvasView.getImageBitmap(),
                adjExp, adjCon, adjSat, adjWarm, adjSharp, adjSepia, adjInvert
        );
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
        toast("선택한 색상의 배경을 투명하게 제거했습니다");
    }

    // ---------- PDF Page Operations ----------
    private void rotateCurrentPdfPage(int deltaDeg) {
        List<MarkupModel.PdfPageItem> pages = stageCanvasView.getPdfPages();
        int idx = stageCanvasView.getCurrentPdfPage();
        if (idx < 0 || idx >= pages.size()) return;
        MarkupModel.PdfPageItem p = pages.get(idx);
        p.rot = ((p.rot + deltaDeg) % 360 + 360) % 360;
        p.invalidateCache();
        markModified();
        stageCanvasView.fitToWindow();
        if (sidebarVisible) refreshSidebarContent();
    }

    private void insertBlankPdfPage() {
        List<MarkupModel.PdfPageItem> pages = stageCanvasView.getPdfPages();
        int idx = stageCanvasView.getCurrentPdfPage();
        float w = 595f, h = 842f;
        if (idx >= 0 && idx < pages.size()) {
            w = pages.get(idx).wPt;
            h = pages.get(idx).hPt;
        }
        MarkupModel.PdfPageItem blank = new MarkupModel.PdfPageItem(
                "blank_" + System.currentTimeMillis(), "blank", "", 0, w, h
        );
        int insertAt = Math.min(pages.size(), idx + 1);
        pages.add(insertAt, blank);
        markModified();
        stageCanvasView.setCurrentPdfPage(insertAt);
        refreshToolbars();
        if (sidebarVisible) refreshSidebarContent();
        toast("빈 페이지를 추가했습니다");
    }

    // ---------- Dialogs (Pure Native One UI Dialogs) ----------

    // 1. Built-in One UI File & MediaStore Browser Dialog (Non-Rooted Compatible)
    private void launchScreenCapture() {
        if(capturePending)return;
        guardChanges(() -> {
            try {
                android.media.projection.MediaProjectionManager manager=(android.media.projection.MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
                capturePending=true;startActivityForResult(manager.createScreenCaptureIntent(),REQ_CAPTURE);
            }catch(Exception e){capturePending=false;toast("화면 캡처를 시작할 수 없습니다.");}
        });
    }
    private void consumeCapture() {
        String path=prefs.getString(ScreenCaptureService.RESULT_PATH,"");if(path.isEmpty())return;
        prefs.edit().remove(ScreenCaptureService.RESULT_PATH).apply();capturePending=false;
        if(new File(path).isFile())openFileAtPath(path);
    }
    private void launchSystemFilePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, REQ_OPEN_DOC);
        } catch (Exception e) {
            toast("파일 선택기를 열 수 없습니다: " + e.getMessage());
        }
    }

    private void launchCameraCapture() {
        try {
            pendingCameraFile = new File(sampleDir, "Camera_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg");
            Uri uri = ViewerFileProvider.getUriForFile(this, pendingCameraFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, REQ_CAMERA);
        } catch (Exception e) {
            toast("카메라를 실행할 수 없습니다: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode==REQ_CAPTURE) {
            if(resultCode!=Activity.RESULT_OK || data==null){capturePending=false;return;}
            Intent capture=new Intent(this,ScreenCaptureService.class).putExtra(ScreenCaptureService.DATA,data).putExtra(ScreenCaptureService.RECEIVER,new android.os.ResultReceiver(mainHandler){
                @Override protected void onReceiveResult(int code,Bundle result){capturePending=false;if(isDestroyed() || isFinishing())return;if(code==Activity.RESULT_OK){if(activityResumed)consumeCapture();}else toast(result.getString("error","화면 캡처를 완료하지 못했습니다."));}
            });
            try{startForegroundService(capture);}catch(Exception e){capturePending=false;toast("화면 캡처를 시작하지 못했습니다.");}
            return;
        }
        if (resultCode != Activity.RESULT_OK) return;
        if (requestCode == REQ_OPEN_TREE && data != null && data.getData() != null) {
            documents.retain(data.getData(), data.getFlags());
            showTreeFolder(data.getData(), android.provider.DocumentsContract.getTreeDocumentId(data.getData())); return;
        }
        if (requestCode == REQ_OPEN_DOC && data != null) { handleIncomingIntent(data); return; }

        if (requestCode == REQ_CAMERA) {
            if (pendingCameraFile != null && pendingCameraFile.exists() && pendingCameraFile.length() > 0) {
                loadFolderList(sampleDir.getAbsolutePath(), pendingCameraFile.getAbsolutePath());
            }
        } else if (requestCode == REQ_CREATE_DOC && data != null && data.getData() != null) {
            if (pendingExportBytes == null && pendingExportFile != null) try { pendingExportBytes = Util.readAllBytes(pendingExportFile); } catch (Exception e) { toast("저장할 파일을 다시 준비해 주세요."); }
            if (pendingExportBytes != null) {
                try {
                    OutputStream os = getContentResolver().openOutputStream(data.getData(), "wt");
                    if (os != null) {
                        try {
                            os.write(pendingExportBytes);
                            os.flush();
                        } finally {
                            os.close();
                        }
                        toast("선택한 위치에 저장했습니다");
                    }
                } catch (Exception e) {
                    toast("저장 실패: " + e.getMessage());
                } finally {
                    pendingExportBytes = null;
                }
            }
        } else if (requestCode == REQ_INSERT_PAGE_DOC && data != null && data.getData() != null) {
            File f = copyContentUriToInbox(data.getData());
            if (f != null && "pdf".equals(currentKind)) {
                String k = FileOps.kindOf(f.getName());
                List<MarkupModel.PdfPageItem> pages = stageCanvasView.getPdfPages();
                int insertAt = Math.min(pages.size(), stageCanvasView.getCurrentPdfPage() + 1);
                if ("pdf".equals(k)) {
                    PdfHost.open(f.getAbsolutePath(), "");
                    int cnt = PdfHost.getPageCount(f.getAbsolutePath());
                    for (int i = 0; i < cnt; i++) {
                        float[] sz = PdfHost.getPageSizePt(f.getAbsolutePath(), i);
                        pages.add(insertAt + i, new MarkupModel.PdfPageItem("ins_" + System.currentTimeMillis() + "_" + i, "pdf", f.getAbsolutePath(), i, sz[0], sz[1]));
                    }
                    markModified();
                    stageCanvasView.setCurrentPdfPage(insertAt);
                    if (sidebarVisible) refreshSidebarContent();
                    toast(cnt + "개의 PDF 페이지를 삽입했습니다");
                } else if ("image".equals(k)) {
                    Bitmap bmp;
                    try{bmp=ImageEditOps.decodeOriginal(f.getAbsolutePath());}catch(java.io.IOException error){toast(error.getMessage());return;}
                    if (bmp != null) {
                        float wPt = 595f;
                        float hPt = Math.max(200f, 595f * ((float) bmp.getHeight() / Math.max(1, bmp.getWidth())));
                        MarkupModel.PdfPageItem imgPage = new MarkupModel.PdfPageItem("img_" + System.currentTimeMillis(), "img", f.getAbsolutePath(), 0, wPt, hPt);
                        imgPage.customBitmap = bmp;
                        pages.add(insertAt, imgPage);
                        markModified();
                        stageCanvasView.setCurrentPdfPage(insertAt);
                        if (sidebarVisible) refreshSidebarContent();
                        toast("이미지 페이지를 삽입했습니다");
                    }
                }
            }
        }
    }

    // 2. Save & Export Dialogs
    private void saveCurrentFile(boolean saveAsSystemPicker) {
        if (currentPath.isEmpty() || saveRunning) return;
        if (!saveAsSystemPicker && isModified && "pdf".equals(currentKind) && !flattenedPdfApproved) {
            new AlertDialog.Builder(this).setTitle("편집한 PDF 저장").setMessage("페이지를 이미지로 저장해요. 텍스트 선택·링크·양식은 유지되지 않으며, 기존 파일은 앱의 백업 폴더에 보관해요.")
                .setNegativeButton("취소", (d,w) -> cancelAfterSave()).setOnCancelListener(d -> cancelAfterSave()).setPositiveButton("저장", (d,w) -> { flattenedPdfApproved = true; saveCurrentFile(false); }).show(); return;
        }
        final String path = currentPath, kind = currentKind; final boolean saveCopy = saveAsSystemPicker;
        final android.app.ProgressDialog progress = android.app.ProgressDialog.show(this, "저장", "파일을 준비하고 있어요…", true, false);
        saveRunning = true;
        bgPool.execute(() -> {
            try {
                File prepared = ViewerExports.snapshot(this, path, kind, stageCanvasView, nativeDocView, exportQuality, exportDpi, isModified);
                boolean needsPicker = saveCopy || !Util.extOf(prepared.getName()).equals(Util.extOf(path));
                if (!needsPicker) {
                    File backupDir = new File(getFilesDir(), "backups"); backupDir.mkdirs();
                    java.nio.file.Files.copy(new File(path).toPath(), new File(backupDir, System.currentTimeMillis() + "_" + new File(path).getName()).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    File replacement = new File(path + ".save");
                    java.nio.file.Files.copy(prepared.toPath(), replacement.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    java.nio.file.Files.move(replacement.toPath(), new File(path).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    documents.publish(path);
                }
                final boolean picker = needsPicker;
                final byte[] bytes = picker ? Util.readAllBytes(prepared) : null;
                mainHandler.post(() -> {
                    saveRunning = false; progress.dismiss();
                    if (isFinishing()) return;
                    if (picker) { cancelAfterSave(); promptSystemSavePicker(prepared.getName(), bytes); return; }
                    isModified = false; if ("doc".equals(kind)) nativeDocView.markSaved();
                    scanMediaFile(path); updateHeaderTitles(); toast("저장했습니다");
                    Runnable next = afterSave; afterSave = afterSaveCancelled = null; if (next != null) next.run();
                });
            } catch (Exception|OutOfMemoryError e) {
                mainHandler.post(() -> { saveRunning = false; cancelAfterSave(); progress.dismiss(); toast(e instanceof OutOfMemoryError?"원본 해상도로 저장할 메모리가 부족합니다. 다른 앱을 닫고 다시 저장해 주세요.":"저장 실패: " + e.getMessage() + " · 다른 이름으로 저장할 수 있어요."); });
            }
        });
    }
    private void promptSystemSavePicker(String defaultName, byte[] bytes) {
        try {
            pendingExportBytes = bytes;
            pendingExportFile = new File(getCacheDir(), "pending-export.bin"); Util.writeAllBytes(pendingExportFile, bytes);
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(FileOps.mimeOf(Util.extOf(defaultName)));
            intent.putExtra(Intent.EXTRA_TITLE, defaultName);
            startActivityForResult(intent, REQ_CREATE_DOC);
        } catch (Exception e) {
            toast("시스템 저장 창을 열 수 없습니다: " + e.getMessage());
        }
    }

    private void showExportDialog() {
        if (currentPath.isEmpty()) return;
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("내보내기 / 파일 형식 변환");

        final String[] formats = {
                "PNG 이미지 (.png)",
                "JPEG 이미지 (.jpg - 고화질)",
                "WebP 이미지 (.webp)",
                "BMP 비트맵 (.bmp)",
                "GIF 이미지 (.gif)",
                "TIFF 무손실 (.tiff)",
                "ICO 아이콘 (.ico)",
                "PDF 문서 (.pdf)",
                "다른 이름으로 기기에 저장 (시스템 선택기)"
        };
        final String[] exts = {".png", ".jpg", ".webp", ".bmp", ".gif", ".tiff", ".ico", ".pdf", "system"};

        b.setItems(formats, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String targetExt = exts[which];
                if ("system".equals(targetExt)) {
                    saveCurrentFile(true);
                    return;
                }
                exportCurrentToFormat(targetExt);
            }
        });
        b.setNegativeButton("취소", null);
        b.show();
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
                    float wPt = 595f;
                    float hPt = 595f * ((float) flat.getHeight() / Math.max(1, flat.getWidth()));
                    ViewerExports.require(PdfHost.addPageBitmap(wid,wPt,hPt,flat,exportQuality),"ok");
                    flat.recycle();
                } else if ("pdf".equals(currentKind)) {
                    for (MarkupModel.PdfPageItem p : stageCanvasView.getPdfPages()) {
                        Bitmap bmp = p.renderFlattened(ViewerExports.pdfWidth(p,exportDpi));
                        ViewerExports.require(PdfHost.addPageBitmap(wid,p.displayWidthPt(),p.displayHeightPt(),bmp,exportQuality),"ok");
                        bmp.recycle();
                    }
                } else if ("doc".equals(currentKind)) {
                    int width = Math.round(595f * exportDpi / 72), height = Math.round(842f * exportDpi / 72);
                    for (int page = 0; page < nativeDocView.printPageCount(width, height); page++) {
                        Bitmap docBmp = nativeDocView.printPage(page, width, height);
                        ViewerExports.require(PdfHost.addPageBitmap(wid, 595f, 842f, docBmp, exportQuality), "ok"); docBmp.recycle();
                    }
                }
                Map<String, Object> res = PdfHost.end(wid, outFile.getName());
                if (Boolean.TRUE.equals(res.get("ok"))) {
                    scanMediaFile(outFile.getAbsolutePath());
                    toast("PDF로 내보냈습니다: " + outFile.getName());
                    promptSystemSavePicker(outFile.getName(), Util.readAllBytes(outFile));
                }
                return;
            }

            // Export to Raster Image Format
            Bitmap srcBmp = null;
            if ("image".equals(currentKind) && stageCanvasView.getImageBitmap() != null) {
                srcBmp = stageCanvasView.getImageAnnotations().isEmpty()?stageCanvasView.getImageBitmap():MarkupModel.flattenOntoBitmap(stageCanvasView.getImageBitmap(),stageCanvasView.getImageAnnotations());
            } else if ("pdf".equals(currentKind) && !stageCanvasView.getPdfPages().isEmpty()) {
                MarkupModel.PdfPageItem page=stageCanvasView.getPdfPages().get(stageCanvasView.getCurrentPdfPage());
                srcBmp = page.renderFlattened(ViewerExports.pdfWidth(page,exportDpi));
            }
            if (srcBmp == null) {
                toast("이미지로 변환할 수 없는 항목입니다");
                return;
            }
            Map<String, Object> saved = FileOps.saveBitmap(outFile.getAbsolutePath(), srcBmp, exportQuality);
            if(srcBmp!=stageCanvasView.getImageBitmap())srcBmp.recycle();
            if (Boolean.TRUE.equals(saved.get("success"))) {
                scanMediaFile(outFile.getAbsolutePath());
                toast("변환 완료: " + outFile.getName());
                promptSystemSavePicker(outFile.getName(), Util.readAllBytes(outFile));
            }
        } catch (OutOfMemoryError memory) {
            toast("원본 해상도로 내보낼 메모리가 부족합니다. 다른 앱을 닫고 다시 시도해 주세요.");
        } catch (Exception e) {
            toast("내보내기 오류: " + e.getMessage());
        }
    }

    private void scanMediaFile(String path) {
        try {
            MediaScannerConnection.scanFile(this, new String[]{path}, null, null);
        } catch (Exception ignored) {}
    }

    // 3. Resize Image Dialog
    private void showResizeImageDialog() {
        final Bitmap cur = stageCanvasView.getImageBitmap();
        if (cur == null) return;
        final int origW = cur.getWidth(), origH = cur.getHeight();
        final double ratio = (double) origW / Math.max(1, origH);

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("이미지 크기 조절 (" + origW + " × " + origH + ")");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, pad, pad, pad);

        final EditText wEdit = new EditText(this);
        wEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        wEdit.setText(String.valueOf(origW));
        wEdit.setHint("가로 (px)");

        final EditText hEdit = new EditText(this);
        hEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        hEdit.setText(String.valueOf(origH));
        hEdit.setHint("세로 (px)");

        final CheckBox lockAspect = new CheckBox(this);
        lockAspect.setText("가로/세로 비율 유지");
        lockAspect.setChecked(true);

        final boolean[] updating = {false};
        wEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (updating[0] || !lockAspect.isChecked()) return;
                int w = Util.toInt(s.toString(), 0);
                if (w > 0) {
                    updating[0] = true;
                    hEdit.setText(String.valueOf(Math.max(1, (int) Math.round(w / ratio))));
                    updating[0] = false;
                }
            }
        });

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        int[] pcts = {25, 50, 75, 150, 200};
        for (final int pct : pcts) {
            presets.addView(createSmallActionChip(pct + "%", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    updating[0] = true;
                    wEdit.setText(String.valueOf(Math.max(1, origW * pct / 100)));
                    hEdit.setText(String.valueOf(Math.max(1, origH * pct / 100)));
                    updating[0] = false;
                }
            }));
        }

        box.addView(wEdit);
        box.addView(hEdit);
        box.addView(lockAspect);
        box.addView(presets);
        b.setView(box);

        b.setPositiveButton("적용", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                int nw = Util.toInt(wEdit.getText().toString(), origW);
                int nh = Util.toInt(hEdit.getText().toString(), origH);
                pushImageUndo();
                final Bitmap resized;
                try{resized=ImageEditOps.resize(cur,nw,nh);}catch(OutOfMemoryError memory){toast("요청한 크기를 처리할 메모리가 부족합니다.");return;}
                stageCanvasView.setImageContent(resized, null, stageCanvasView.getImageAnnotations(), true);
                markModified();
                updateHeaderTitles();
                updateStatusBar();
            }
        });
        b.setNegativeButton("취소", null);
        b.show();
    }

    // 4. Color Adjustment Sliders Dialog
    private void showColorAdjustDialog() {
        if (stageCanvasView.getImageBitmap() == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("색상 및 밝기 상세 보정");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, dp(8), pad, dp(8));

        box.addView(createAdjustSeekBar("밝기 (Exposure)", (int) (adjExp + 100), 200, new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                adjExp = progress - 100f;
                updateLiveAdjustPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        }));

        box.addView(createAdjustSeekBar("대비 (Contrast)", (int) (adjCon + 100), 200, new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                adjCon = progress - 100f;
                updateLiveAdjustPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        }));

        box.addView(createAdjustSeekBar("채도 (Saturation)", (int) (adjSat + 100), 200, new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                adjSat = progress - 100f;
                updateLiveAdjustPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        }));

        box.addView(createAdjustSeekBar("색온도 (Warmth)", (int) (adjWarm + 100), 200, new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                adjWarm = progress - 100f;
                updateLiveAdjustPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        }));

        box.addView(createAdjustSeekBar("선명도 (Sharpness)", (int) adjSharp, 100, new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                adjSharp = progress;
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        }));

        b.setView(box);
        b.setPositiveButton("적용", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                commitColorAdjustments();
            }
        });
        b.setNeutralButton("초기화", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                resetColorAdjustments();
            }
        });
        b.setNegativeButton("닫기", null);
        b.show();
    }

    private View createAdjustSeekBar(String label, int initVal, int maxVal, SeekBar.OnSeekBarChangeListener listener) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(0, dp(4), 0, dp(4));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        SeekBar sb = new SeekBar(this);
        sb.setMax(maxVal);
        sb.setProgress(Math.max(0, Math.min(maxVal, initVal)));
        sb.setOnSeekBarChangeListener(listener);
        l.addView(tv);
        l.addView(sb);
        return l;
    }

    // 5. Add Text Annotation Dialog
    private void showAddTextAnnotationDialog(final float docX, final float docY, final int pageIndex) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("텍스트 주석 추가");
        final EditText et = new EditText(this);
        et.setHint("추가할 텍스트를 입력하세요...");
        b.setView(et);
        b.setPositiveButton("추가", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String txt = et.getText().toString().trim();
                if (txt.isEmpty()) return;
                MarkupModel.Annotation a = new MarkupModel.Annotation("txt_" + System.currentTimeMillis(), "text");
                a.text = txt;
                a.x = docX;
                a.y = docY;
                a.color = stageCanvasView.getMarkupColor();
                a.fontSize = stageCanvasView.getMarkupFontSize();
                if ("image".equals(currentKind)) {
                    pushImageUndo();
                    stageCanvasView.getImageAnnotations().add(a);
                } else if ("pdf".equals(currentKind) && pageIndex >= 0 && pageIndex < stageCanvasView.getPdfPages().size()) {
                    stageCanvasView.getPdfPages().get(pageIndex).anns.add(a);
                }
                markModified();
                stageCanvasView.invalidate();
                if (sidebarVisible) refreshSidebarContent();
            }
        });
        b.setNegativeButton("취소", null);
        b.show();
    }

    // 6. Signature Pad Dialog (Draw or Type Signature)
    private void showSignatureDialog(final float docX, final float docY, final int pageIndex) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("서명 그리기 및 삽입");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        box.setPadding(pad, pad, pad, pad);

        final int[] inkColor = {0xFF111111};
        final Path sigPath = new Path();
        final Paint sigPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        sigPaint.setStyle(Paint.Style.STROKE);
        sigPaint.setStrokeWidth(dp(3.5f));
        sigPaint.setStrokeCap(Paint.Cap.ROUND);
        sigPaint.setStrokeJoin(Paint.Join.ROUND);
        sigPaint.setColor(inkColor[0]);

        final View padView = new View(this) {
            private float lx, ly;
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                canvas.drawColor(Color.WHITE);
                sigPaint.setColor(inkColor[0]);
                canvas.drawPath(sigPath, sigPaint);
            }

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                float x = event.getX(), y = event.getY();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sigPath.moveTo(x, y);
                        lx = x; ly = y;
                        invalidate();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        sigPath.quadTo(lx, ly, (x + lx) * 0.5f, (y + ly) * 0.5f);
                        lx = x; ly = y;
                        invalidate();
                        return true;
                }
                return true;
            }
        };
        GradientDrawable pBg = new GradientDrawable();
        pBg.setColor(Color.WHITE);
        pBg.setStroke(dp(1), 0xFFCCCCCC);
        padView.setBackground(pBg);
        box.addView(padView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));

        LinearLayout opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.HORIZONTAL);
        opts.setPadding(0, dp(8), 0, 0);
        opts.addView(createSmallActionChip("검정 잉크", new View.OnClickListener() {
            @Override public void onClick(View v) { inkColor[0] = 0xFF111111; padView.invalidate(); }
        }));
        opts.addView(createSmallActionChip("파랑 잉크", new View.OnClickListener() {
            @Override public void onClick(View v) { inkColor[0] = 0xFF0381FE; padView.invalidate(); }
        }));
        opts.addView(createSmallActionChip("빨강 잉크", new View.OnClickListener() {
            @Override public void onClick(View v) { inkColor[0] = 0xFFE53935; padView.invalidate(); }
        }));
        opts.addView(createSmallActionChip("지우기", new View.OnClickListener() {
            @Override public void onClick(View v) { sigPath.reset(); padView.invalidate(); }
        }));
        box.addView(opts);

        b.setView(box);
        b.setPositiveButton("서명 삽입", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                Bitmap sigBmp = Bitmap.createBitmap(Math.max(200, padView.getWidth()), Math.max(100, padView.getHeight()), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(sigBmp);
                sigPaint.setColor(inkColor[0]);
                c.drawPath(sigPath, sigPaint);
                savedSignatures.add(sigBmp);

                MarkupModel.Annotation a = new MarkupModel.Annotation("sig_" + System.currentTimeMillis(), "sign");
                a.sigBitmap = sigBmp;
                a.x = docX;
                a.y = docY;
                a.w = 150f;
                a.h = 75f;
                if ("image".equals(currentKind)) {
                    pushImageUndo();
                    stageCanvasView.getImageAnnotations().add(a);
                } else if ("pdf".equals(currentKind) && pageIndex >= 0 && pageIndex < stageCanvasView.getPdfPages().size()) {
                    stageCanvasView.getPdfPages().get(pageIndex).anns.add(a);
                }
                markModified();
                stageCanvasView.invalidate();
                if (sidebarVisible) refreshSidebarContent();
            }
        });
        b.setNegativeButton("취소", null);
        b.show();
    }

    // 7. PDF Page Organization Dialog
    private void showPdfPageOrganizeDialog() {
        if (!"pdf".equals(currentKind) || stageCanvasView.getPdfPages().isEmpty()) return;
        final List<MarkupModel.PdfPageItem> pages = stageCanvasView.getPdfPages();
        final int idx = stageCanvasView.getCurrentPdfPage();

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("PDF 페이지 관리 (현재 " + (idx + 1) + " / " + pages.size() + " 페이지)");
        String[] actions = {
                "현재 페이지 왼쪽 90° 회전",
                "현재 페이지 오른쪽 90° 회전",
                "현재 페이지 복제",
                "현재 페이지를 앞으로 이동 (위로)",
                "현재 페이지를 뒤로 이동 (아래로)",
                "현재 페이지 뒤에 빈 페이지 추가",
                "외부 PDF 또는 이미지 파일 삽입...",
                "현재 페이지를 PNG 이미지로 추출",
                "현재 페이지 삭제"
        };
        b.setItems(actions, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch (which) {
                    case 0: rotateCurrentPdfPage(-90); break;
                    case 1: rotateCurrentPdfPage(90); break;
                    case 2: {
                        MarkupModel.PdfPageItem copy = pages.get(idx).copy("dup_" + System.currentTimeMillis());
                        pages.add(idx + 1, copy);
                        markModified();
                        stageCanvasView.setCurrentPdfPage(idx + 1);
                        if (sidebarVisible) refreshSidebarContent();
                        toast("페이지를 복제했습니다");
                        break;
                    }
                    case 3: {
                        if (idx > 0) {
                            Collections.swap(pages, idx, idx - 1);
                            markModified();
                            stageCanvasView.setCurrentPdfPage(idx - 1);
                            if (sidebarVisible) refreshSidebarContent();
                        }
                        break;
                    }
                    case 4: {
                        if (idx + 1 < pages.size()) {
                            Collections.swap(pages, idx, idx + 1);
                            markModified();
                            stageCanvasView.setCurrentPdfPage(idx + 1);
                            if (sidebarVisible) refreshSidebarContent();
                        }
                        break;
                    }
                    case 5: insertBlankPdfPage(); break;
                    case 6: {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        startActivityForResult(intent, REQ_INSERT_PAGE_DOC);
                        break;
                    }
                    case 7: exportCurrentToFormat(".png"); break;
                    case 8: {
                        if (pages.size() <= 1) {
                            toast("마지막 남은 페이지는 삭제할 수 없습니다");
                            return;
                        }
                        pages.remove(idx);
                        markModified();
                        stageCanvasView.setCurrentPdfPage(Math.max(0, idx - 1));
                        if (sidebarVisible) refreshSidebarContent();
                        toast("페이지를 삭제했습니다");
                        break;
                    }
                }
            }
        });
        b.setNegativeButton("닫기", null);
        b.show();
    }

    private void showPdfJumpPageDialog() {
        final int total = stageCanvasView.getPdfPages().size();
        if (total <= 0) return;
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("페이지 이동 (1 ~ " + total + ")");
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(stageCanvasView.getCurrentPdfPage() + 1));
        b.setView(et);
        b.setPositiveButton("이동", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                int p = Util.toInt(et.getText().toString(), 1);
                stageCanvasView.setCurrentPdfPage(p - 1);
            }
        });
        b.setNegativeButton("취소", null);
        b.show();
    }

    // ---------- Search & Offline OCR Execution ----------
    private void toggleSearchBar() {
        if(currentPath.isEmpty())return;
        searchBarOpen = !searchBarOpen;
        if (searchBarOverlay != null) {
            searchBarOverlay.setVisibility(searchBarOpen ? View.VISIBLE : View.GONE);
        }
        if (searchBarOpen && searchInputEdit != null) {
            searchInputEdit.requestFocus();
        }
    }

    private void closeSearchBar() {
        searchBarOpen = false;
        if (searchBarOverlay != null) searchBarOverlay.setVisibility(View.GONE);
        currentOcrBoxes.clear();
        stageCanvasView.setOcrBoxes(null, -1);
    }

    @SuppressWarnings("unchecked")
    private void executeSearchOrOcr(String query) {
        final String q = query == null ? "" : query.trim(), path = currentPath, kind = currentKind;
        lastSearchQuery = q;
        if ("doc".equals(kind)) { nativeDocView.performSearch(q); return; }
        if (!"pdf".equals(kind) && !"image".equals(kind)) return;
        final int ticket = ++searchTicket;
        final Bitmap input = "image".equals(kind) && stageCanvasView.getImageBitmap() != null ? stageCanvasView.getImageBitmap().copy(Bitmap.Config.ARGB_8888, false) : null;
        final List<MarkupModel.PdfPageItem> pages = "pdf".equals(kind) ? copyPdfState() : new ArrayList<>();
        toast("텍스트를 인식하고 있어요…");
        ocrPool.execute(() -> {
            List<StageCanvasView.OcrBox> boxes = new ArrayList<>(); StringBuilder text = new StringBuilder();
            try {
                int count = "image".equals(kind) ? 1 : pages.size();
                for (int index = 0; index < count; index++) {
                    if (searchTicket != ticket || Thread.currentThread().isInterrupted()) return;
                    Map<String, Object> result;
                    boolean normalized = "pdf".equals(kind);
                    if (!normalized) { if (input == null) return; result = OcrHost.recognizeBitmap(input); }
                    else {
                        MarkupModel.PdfPageItem page = pages.get(index);
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
                    if (words != null) for (Object item : words) {
                        Map<?, ?> word = (Map<?, ?>)item; String value = String.valueOf(word.get("t"));
                        if (q.isEmpty() || value.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))) boxes.add(new StageCanvasView.OcrBox(normalized ? index : -1, (float)Util.toDouble(word.get("x"), 0), (float)Util.toDouble(word.get("y"), 0), (float)Util.toDouble(word.get("w"), 0), (float)Util.toDouble(word.get("h"), 0), normalized, value));
                    }
                }
                mainHandler.post(() -> {
                    if (isFinishing() || ticket != searchTicket || !path.equals(currentPath)) return;
                    fullExtractedOcrText = text.toString(); currentOcrBoxes.clear(); currentOcrBoxes.addAll(boxes); activeOcrHit = boxes.isEmpty() ? -1 : 0;
                    stageCanvasView.setOcrBoxes(boxes, activeOcrHit);
                    if (searchCountLabel != null) searchCountLabel.setText(boxes.isEmpty() ? "0 / 0" : "1 / " + boxes.size());
                    toast(boxes.isEmpty() ? "일치하는 텍스트가 없어요." : boxes.size() + "개 텍스트를 찾았어요.");
                });
            } catch (Exception | LinkageError e) { if (ticket == searchTicket) toast("텍스트 인식 실패: " + e.getMessage()); }
            finally { if (input != null) input.recycle(); }
        });
    }
    private void stepSearchOrOcr(int delta) {
        if ("doc".equals(currentKind)) {
            nativeDocView.stepSearch(delta);
            return;
        }
        if (currentOcrBoxes.isEmpty()) return;
        activeOcrHit = (activeOcrHit + delta + currentOcrBoxes.size()) % currentOcrBoxes.size();
        stageCanvasView.setOcrBoxes(currentOcrBoxes, activeOcrHit);
        if (searchCountLabel != null) {
            searchCountLabel.setText((activeOcrHit + 1) + " / " + currentOcrBoxes.size());
        }
    }

    private void showFullExtractedTextDialog() {
        String text = "doc".equals(currentKind) ? nativeDocView.getPlainTextAll() : fullExtractedOcrText;
        if (text == null || text.trim().isEmpty()) {
            executeSearchOrOcr("");
            toast("텍스트를 추출 중입니다. 잠시 후 다시 눌러주세요.");
            return;
        }
        final String finalText = text;
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("추출된 전체 텍스트");
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(finalText);
        tv.setPadding(dp(18), dp(12), dp(18), dp(12));
        tv.setTextIsSelectable(true);
        sv.addView(tv);
        b.setView(sv);
        b.setPositiveButton("전체 복사", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                copyToClipboard(finalText);
                toast("전체 텍스트를 복사했습니다");
            }
        });
        b.setNegativeButton("닫기", null);
        b.show();
    }

    // ---------- Slideshow, Print, Share, Bookmarks, Overflow Menu ----------
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
        File f = new File(currentPath);
        slideshowCaptionView.setText((currentFileIndex + 1) + " / " + folderItems.size() + "  ·  " + f.getName() + " (터치하면 종료)");
    }

    private void printCurrentFile() {
        if (currentPath.isEmpty()) return;
        PrintManager manager = (PrintManager)getSystemService(Context.PRINT_SERVICE);
        if (manager != null) manager.print(new File(currentPath).getName(), new ViewerPrintAdapter(this, new File(currentPath).getName(), currentKind, stageCanvasView, nativeDocView), null);
    }
    private void shareCurrentFile() {
        if (currentPath.isEmpty()) return;
        try {
            File f = isModified ? ViewerExports.snapshot(this, currentPath, currentKind, stageCanvasView, nativeDocView, exportQuality, exportDpi) : new File(currentPath);
            Uri uri = ViewerFileProvider.getUriForFile(this, f);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(FileOps.mimeOf(Util.extOf(currentPath)));
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "파일 공유"));
        } catch (Exception e) {
            toast("공유할 수 없습니다: " + e.getMessage());
        }
    }

    private void toggleBookmark(String path) {
        if (path == null || path.isEmpty()) return;
        if (bookmarks.contains(path)) {
            bookmarks.remove(path);
            toast("책갈피에서 제거했습니다");
        } else {
            bookmarks.add(path);
            toast("책갈피에 추가했습니다");
        }
        saveBookmarks();
        refreshToolbars();
    }

    private void loadBookmarks() {
        bookmarks.clear();
        String raw = prefs.getString("bookmarks_json", "[]");
        try {
            Object parsed = Util.jsonParse(raw);
            if (parsed instanceof List) {
                for (Object o : (List<?>) parsed) {
                    if (o != null) bookmarks.add(String.valueOf(o));
                }
            }
        } catch (Exception ignored) {}
    }

    private void saveBookmarks() {
        List<Object> l = new ArrayList<Object>(bookmarks);
        prefs.edit().putString("bookmarks_json", Util.jsonStringify(l)).apply();
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("SamsungViewer", text));
        }
    }

    private void copyCurrentImage() {
        try {
            Bitmap bitmap = "image".equals(currentKind) ? MarkupModel.flattenOntoBitmap(stageCanvasView.getImageBitmap(), stageCanvasView.getImageAnnotations()) : "pdf".equals(currentKind) ? stageCanvasView.getPdfPages().get(stageCanvasView.getCurrentPdfPage()).renderFlattened(1800) : null;
            if (bitmap == null) { toast("복사할 이미지가 없어요."); return; }
            RectF selection = stageCanvasView.getSelectionRect();
            if ("image".equals(currentKind) && selection != null && selection.width() > 0 && selection.height() > 0) {
                int x = Math.max(0, (int)selection.left), y = Math.max(0, (int)selection.top);
                int w = Math.min(bitmap.getWidth() - x, Math.max(1, (int)selection.width())), h = Math.min(bitmap.getHeight() - y, Math.max(1, (int)selection.height()));
                Bitmap cropped = Bitmap.createBitmap(bitmap, x, y, w, h); if (cropped != bitmap) bitmap.recycle(); bitmap = cropped;
            }
            File file = new File(getCacheDir(), "clipboard-" + System.currentTimeMillis() + ".png");
            ViewerExports.require(FileOps.saveBitmap(file.getAbsolutePath(), bitmap, 100), "success"); bitmap.recycle();
            ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newUri(getContentResolver(), "이미지", ViewerFileProvider.uriForFile(file))); toast("이미지를 복사했어요.");
        } catch (Exception e) { toast("복사 실패: " + e.getMessage()); }
    }
    private void pasteClipboardImage() {
        ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getUri() == null) { toast("클립보드에 이미지 파일이 없어요."); return; }
        openUris(java.util.Collections.singletonList(clip.getItemAt(0).getUri()), Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    private void showSettingsDialog() {
        String version; try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { version = Updater.VERSION; }
        String[] entries = {"테마", "화면 배치", "JPEG 품질 · " + exportQuality, "PDF 해상도 · " + exportDpi + " dpi", "슬라이드 간격 · " + slideshowIntervalSec + "초", "업데이트 확인"};
        new AlertDialog.Builder(this).setTitle("뷰어 · " + version).setItems(entries, (d,which) -> {
            if (which == 0) new AlertDialog.Builder(this).setTitle("테마").setItems(new String[]{"시스템 설정 따르기", "라이트", "다크"}, (dialog,index) -> {
                prefs.edit().putBoolean("system_theme", index == 0).putBoolean("dark_theme", index == 2).apply();
                isDarkTheme = index == 0 ? (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES : index == 2;
                rebuildChromePreserveStage();
            }).show();
            else if (which == 1) toggleUiModeManual();
            else if (which == 2) { final String[] levels = {"70", "80", "90", "95", "100"}; new AlertDialog.Builder(this).setTitle("JPEG 품질").setItems(levels, (dialog,index) -> { exportQuality = Integer.parseInt(levels[index]); prefs.edit().putInt("export_quality", exportQuality).apply(); }).show(); }
            else if (which == 3) { final String[] levels = {"96", "150", "200", "300"}; new AlertDialog.Builder(this).setTitle("PDF 해상도 (dpi)").setItems(levels, (dialog,index) -> { exportDpi = Integer.parseInt(levels[index]); prefs.edit().putInt("export_dpi", exportDpi).apply(); }).show(); }
            else if (which == 4) { final String[] levels = {"1", "3", "5", "10", "30"}; new AlertDialog.Builder(this).setTitle("슬라이드 간격 (초)").setItems(levels, (dialog,index) -> { slideshowIntervalSec = Integer.parseInt(levels[index]); prefs.edit().putInt("slide_sec", slideshowIntervalSec).apply(); }).show(); }
            else { Updater.checkInteractive(this); }
        }).setPositiveButton("닫기", null).show();
    }
    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (event.isCtrlPressed()) {
            switch (key) {
                case KeyEvent.KEYCODE_S: saveCurrentFile(event.isShiftPressed()); return true;
                case KeyEvent.KEYCODE_O: launchSystemFilePicker(); return true;
                case KeyEvent.KEYCODE_P: printCurrentFile(); return true;
                case KeyEvent.KEYCODE_F: toggleSearchBar(); return true;
            }
            if (!(getCurrentFocus() instanceof EditText)) {
                if (key == KeyEvent.KEYCODE_C) { copyCurrentImage(); return true; }
                if (key == KeyEvent.KEYCODE_V) { pasteClipboardImage(); return true; }
                if (key == KeyEvent.KEYCODE_Z || key == KeyEvent.KEYCODE_Y) {
                    boolean redo = key == KeyEvent.KEYCODE_Y || event.isShiftPressed();
                    if ("pdf".equals(currentKind)) undoPdf(redo); else if ("doc".equals(currentKind)) { if (redo) nativeDocView.redo(); else nativeDocView.undo(); } else if (redo) redoImageEdit(); else undoImageEdit(); return true;
                }
            }
        }
        if (!(getCurrentFocus() instanceof EditText) && (key == KeyEvent.KEYCODE_PAGE_DOWN || key == KeyEvent.KEYCODE_PAGE_UP)) {
            int delta = key == KeyEvent.KEYCODE_PAGE_DOWN ? 1 : -1;
            if ("pdf".equals(currentKind)) stageCanvasView.setCurrentPdfPage(stageCanvasView.getCurrentPdfPage() + delta); else navigateFile(delta); return true;
        }
        return super.onKeyDown(key, event);
    }
    private void showSavedCopies(File directory,String title) {
        File[] saved=directory.listFiles(file -> file.isFile() && !FileOps.kindOf(file.getName()).isEmpty());
        if(saved==null || saved.length==0){new AlertDialog.Builder(this).setTitle(title).setMessage("저장된 파일이 없어요.").setPositiveButton("닫기",null).show();return;}
        java.util.Arrays.sort(saved,(a,b) -> Long.compare(b.lastModified(),a.lastModified()));
        String[] names=new String[saved.length];for(int i=0;i<saved.length;i++)names[i]=saved[i].getName();
        new AlertDialog.Builder(this).setTitle(title).setItems(names,(d,which) -> guardChanges(() -> loadFolderList(directory.getAbsolutePath(),saved[which].getAbsolutePath()))).setNegativeButton("닫기",null).show();
    }
    private void closeCurrentFile() {
        guardChanges(() -> {
            openTicket++;imageLoadTicket++;searchTicket++;nativeMediaView.stopMedia();PdfHost.closeAll();stageCanvasView.clearContent();stageCanvasView.setActiveTool("hand");
            currentPath=currentKind="";folderItems.clear();currentFileIndex=-1;searchBarOpen=false;isModified=false;
            imgUndoStack.clear();imgRedoStack.clear();pdfUndo.clear();pdfRedo.clear();buildCompleteNativeUI();
        });
    }
    private void showTopOverflowMenu(View anchor) {
        PopupMenu menu=new PopupMenu(this,anchor);boolean opened=!currentPath.isEmpty();
        menu.getMenu().add(0,2,0,"파일 열기");menu.getMenu().add(0,14,0,"폴더 열기");
        menu.getMenu().add(0,16,0,"붙여넣기");menu.getMenu().add(0,19,0,"화면 캡처");
        if(opened){
            menu.getMenu().add(0,4,0,"저장");menu.getMenu().add(0,5,0,"다른 이름으로 저장·내보내기");
            menu.getMenu().add(0,15,0,"이미지 복사");menu.getMenu().add(0,6,0,"인쇄");menu.getMenu().add(0,7,0,"공유");
            menu.getMenu().add(0,8,0,"슬라이드쇼");menu.getMenu().add(0,9,0,"이름 바꾸기");menu.getMenu().add(0,10,0,"삭제");menu.getMenu().add(0,20,0,"파일 닫기");
        }
        menu.getMenu().add(0,17,0,"설정");menu.getMenu().add(0,21,0,"최근 캡처");menu.getMenu().add(0,18,0,"원본 백업");
        menu.getMenu().add(0,3,0,"사진 촬영");menu.getMenu().add(0,13,0,"예제 파일");
        menu.setOnMenuItemClickListener(item -> {
            switch(item.getItemId()){
                case 2:launchSystemFilePicker();break;case 14:chooseFolder();break;case 16:pasteClipboardImage();break;case 19:launchScreenCapture();break;
                case 4:saveCurrentFile(false);break;case 5:showExportDialog();break;case 15:copyCurrentImage();break;case 6:printCurrentFile();break;case 7:shareCurrentFile();break;
                case 8:startSlideshow();break;case 9:showRenameDialog();break;case 10:showDeleteConfirmDialog();break;case 20:closeCurrentFile();break;case 17:showSettingsDialog();break;
                case 18:showSavedCopies(new File(getFilesDir(),"backups"),"원본 백업");break;case 21:showSavedCopies(new File(getFilesDir(),"captures"),"최근 캡처");break;case 3:launchCameraCapture();break;
                case 13:guardChanges(() -> {SampleSeeder.ensureSeeded(sampleDir);loadFolderList(sampleDir.getAbsolutePath(),null);});break;
            }
            return true;
        });menu.show();
    }
    private void showStageQuickMenu() {
        if (currentPath.isEmpty()) return;
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(new File(currentPath).getName());
        String[] items = {
                "화면에 맞춤 (Fit)",
                "원본 크기 (100%)",
                "파일 정보 · EXIF · AI 프롬프트 보기",
                "내보내기 / 변환",
                "인쇄 (Print)",
                "공유 (Share)",
                "파일 이름 바꾸기",
                "파일 삭제"
        };
        b.setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch (which) {
                    case 0: stageCanvasView.fitToWindow(); break;
                    case 1: stageCanvasView.setZoom(1.0f); break;
                    case 2:
                        infoVisible = true;
                        updateDrawerAndPanelVisibility();
                        refreshInfoPanelContent();
                        break;
                    case 3: showExportDialog(); break;
                    case 4: printCurrentFile(); break;
                    case 5: shareCurrentFile(); break;
                    case 6: showRenameDialog(); break;
                    case 7: showDeleteConfirmDialog(); break;
                }
            }
        });
        b.show();
    }

    private void showRenameDialog() {
        if (currentPath.isEmpty()) return;
        final File f = new File(currentPath);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("파일 이름 바꾸기");
        final EditText et = new EditText(this);
        et.setText(f.getName());
        b.setView(et);
        b.setPositiveButton("변경", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String oldPath = currentPath;
                Map<String, Object> res = documents.rename(currentPath, et.getText().toString().trim());
                if (Boolean.TRUE.equals(res.get("success"))) {
                    String newPath = String.valueOf(res.get("path"));
                    if (isModified) { toast("이름을 변경했어요. 편집 내용은 저장 후 다시 열 수 있어요."); }
                    for (int index=0;index<folderItems.size();index++) if (folderItems.get(index).path.equals(oldPath)) { File renamed = new File(newPath); folderItems.set(index,new FileEntry(newPath,renamed.getName(),currentKind,renamed.length(),renamed.lastModified())); }
                    currentPath = newPath; nativeDocView.retarget(newPath);
                    if ("pdf".equals(currentKind)) { PdfHost.retarget(oldPath,newPath); for (MarkupModel.PdfPageItem page : stageCanvasView.getPdfPages()) if (page.srcPath.equals(oldPath)) page.srcPath = newPath; pdfUndo.clear(); pdfRedo.clear(); lastPdfKey = ""; recordPdfState(); }
                    updateHeaderTitles(); refreshSidebarContent();
                    toast("이름을 변경했습니다");
                } else {
                    toast("변경 실패: " + res.get("error"));
                }
            }
        });
        b.setNegativeButton("취소", null);
        b.show();
    }

    private void showDeleteConfirmDialog() {
        if (currentPath.isEmpty()) return;
        final File f = new File(currentPath);
        new AlertDialog.Builder(this)
                .setTitle("파일 삭제")
                .setMessage("'" + f.getName() + "' 파일을 삭제하시겠습니까?" + (documents.hasSource(currentPath) ? " 원본 위치에서도 삭제되며 되돌릴 수 없습니다." : ""))
                .setPositiveButton("삭제", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String dir = f.getParent();
                        Map<String, Object> res = documents.delete(currentPath);
                        if (Boolean.TRUE.equals(res.get("success"))) {
                            isModified = false;
                            loadFolderList(dir, null);
                            toast("삭제했습니다");
                        } else {
                            toast("삭제 실패: " + res.get("error"));
                        }
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private static String fmtBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        return String.format(Locale.US, "%.2f MB", b / (1024.0 * 1024.0));
    }
}
