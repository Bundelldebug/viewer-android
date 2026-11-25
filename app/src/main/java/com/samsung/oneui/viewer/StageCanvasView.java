package com.samsung.oneui.viewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure Native 60/120fps Custom Canvas View for Images, Animated GIFs, and Multi-Page PDFs.
 * Supports pinch-to-zoom, pan, fling scroll, crop handles, rectangular selection,
 * live color filter adjustments, Instant Alpha magic wand, vector markup annotations,
 * and OCR text highlight overlays.
 */
public final class StageCanvasView extends View {

    public interface StageListener {
        void onZoomChanged(float zoom);
        void onCursorInfo(String info);
        void onNavigateFile(int delta);
        void onPdfPageChanged(int pageIndex, int totalPages);
        void onAnnotationCreated(MarkupModel.Annotation ann, int pageIndex);
        void onAnnotationSelected(MarkupModel.Annotation ann, int pageIndex);
        void onAnnotationModified();
        void onTextToolTap(float docX, float docY, int pageIndex);
        void onSignToolTap(float docX, float docY, int pageIndex);
        void onAlphaTap(int imgX, int imgY);
        void onSelectionChanged(RectF selRect);
        void onLongPressContext(float screenX, float screenY);
    }

    public static final class OcrBox {
        public final int pageIndex; // -1 for image, >= 0 for PDF page
        public final float x, y, w, h; // Normalized 0..1 or document coords
        public final boolean normalized;
        public final String text;

        public OcrBox(int pageIndex, float x, float y, float w, float h, boolean normalized, String text) {
            this.pageIndex = pageIndex;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.normalized = normalized;
            this.text = text;
        }
    }

    private StageListener listener;

    // Mode: "empty", "image", "pdf"
    private String mode = "empty";

    // Image state
    private Bitmap imageBitmap;
    private final ImageTiles imageTiles = new ImageTiles();
    private Movie gifMovie;
    private long gifStartTime;
    private boolean showChecker = true;
    private ColorFilter liveColorFilter;
    private List<MarkupModel.Annotation> imageAnns = new ArrayList<>();

    // PDF state
    private List<MarkupModel.PdfPageItem> pdfPages = new ArrayList<>();
    private final PdfTiles pdfTiles = new PdfTiles();
    private String pdfViewMode = "cont"; // "cont", "single", "two"
    private int currentPdfPage = 0;
    private final List<RectF> pdfPageScreenRects = new ArrayList<>();

    // Transform state
    private float zoom = 1f;
    private float fitZoom = 1f;
    private float panX = 0f;
    private float panY = 0f;
    private boolean isFitMode = true;

    // Tool state: "hand", "select", "crop", "adjust", "alpha", "markup"
    private String activeTool = "hand";
    private String markupKind = "pen"; // "select", "pen", "hl", "line", "arrow", "rect", "ellipse", "text", "redact", "sign"
    private int markupColor = 0xFFE53935;
    private float markupWidth = 3f;
    private float markupFontSize = 20f;
    private boolean markupFill = false;
    private String selectedAnnId = null;
    private int selectedAnnPage = -1;

    // Crop & Selection rectangles (in image pixel coordinates)
    private final RectF cropRect = new RectF();
    private float cropAspect = 0f; // 0 = free
    private String activeCropHandle = null;
    private final PointF cropDragStart = new PointF();
    private final RectF cropStartRect = new RectF();

    private final RectF selectRect = new RectF();
    private boolean hasSelection = false;

    // Active drawing/dragging state
    private MarkupModel.Annotation drawingAnn = null;
    private int drawingPageIdx = -1;
    private MarkupModel.Annotation draggingAnn = null;
    private final PointF lastTouch = new PointF();
    private final PointF downTouch = new PointF();
    private boolean isScaling = false;
    private boolean isDraggingTool = false;

    // OCR highlight state
    private final List<OcrBox> ocrBoxes = new ArrayList<>();
    private int activeOcrIndex = -1;

    // Gestures & Scroller
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final OverScroller scroller;

    // Paints
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint checkerLight = new Paint();
    private final Paint checkerDark = new Paint();
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean isDarkTheme = true;

    public StageCanvasView(Context context) {
        super(context);
        checkerLight.setColor(0xFF2A2E37);
        checkerDark.setColor(0xFF1F2229);
        shadowPaint.setColor(0x66000000);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1));
        borderPaint.setColor(0x33FFFFFF);
        textBadgePaint.setColor(Color.WHITE);
        textBadgePaint.setTextSize(dp(11));

        scroller = new OverScroller(context);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                isScaling = true;
                scroller.forceFinished(true);
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                float prevZoom = zoom;
                float nextZoom = Math.max(0.05f, Math.min(32f, zoom * factor));
                if (Math.abs(nextZoom - prevZoom) > 0.0001f) {
                    float fx = detector.getFocusX();
                    float fy = detector.getFocusY();
                    panX = fx - (fx - panX) * (nextZoom / prevZoom);
                    panY = fy - (fy - panY) * (nextZoom / prevZoom);
                    zoom = nextZoom;
                    isFitMode = false;
                    clampPdfPan();
                    if (listener != null) listener.onZoomChanged(zoom);
                    invalidate();
                }
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                isScaling = false;
            }
        });

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (!"hand".equals(activeTool)) return false;
                if (isFitMode || Math.abs(zoom - fitZoom) < 0.05f) {
                    float target = Math.min(8f, Math.max(2f, fitZoom * 2.2f));
                    float fx = e.getX(), fy = e.getY();
                    panX = fx - (fx - panX) * (target / zoom);
                    panY = fy - (fy - panY) * (target / zoom);
                    zoom = target;
                    isFitMode = false;
                    if (listener != null) listener.onZoomChanged(zoom);
                    invalidate();
                } else {
                    fitToWindow();
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if ("hand".equals(activeTool) && !isScaling && listener != null) {
                    listener.onLongPressContext(e.getX(), e.getY());
                }
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (isScaling || !"hand".equals(activeTool) || e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();

                if ("image".equals(mode) && (isFitMode || zoom <= fitZoom * 1.08f)) {
                    if (Math.abs(dx) > dp(64) && Math.abs(dx) > Math.abs(dy) * 1.6f && Math.abs(velocityX) > dp(220)) {
                        if (listener != null) listener.onNavigateFile(dx < 0 ? 1 : -1);
                        return true;
                    }
                } else if ("pdf".equals(mode) && !"cont".equals(pdfViewMode) && zoom <= fitZoom * 1.08f) {
                    if (Math.abs(dx) > dp(64) && Math.abs(dx) > Math.abs(dy) * 1.6f && Math.abs(velocityX) > dp(220)) {
                        int step = "two".equals(pdfViewMode) ? 2 : 1;
                        setCurrentPdfPage(currentPdfPage + (dx < 0 ? step : -step));
                        return true;
                    }
                }

                if ("pdf".equals(mode) && "cont".equals(pdfViewMode)) {
                    scroller.fling(
                            Math.round(panX), Math.round(panY),
                            Math.round(velocityX), Math.round(velocityY),
                            -getWidth() * 4, getWidth() * 4,
                            (int) getMinPdfPanY(), (int) dp(32)
                    );
                    postInvalidateOnAnimation();
                    return true;
                }
                return false;
            }
        });
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setStageListener(StageListener l) {
        this.listener = l;
    }

    public void setDarkTheme(boolean dark) {
        this.isDarkTheme = dark;
        checkerLight.setColor(dark ? 0xFF2A2E37 : 0xFFE2E6EE);
        checkerDark.setColor(dark ? 0xFF1F2229 : 0xFFD0D5DF);
        invalidate();
    }

    public void setShowChecker(boolean show) {
        this.showChecker = show;
        invalidate();
    }

    public boolean isShowChecker() {
        return showChecker;
    }

    // ---------- Content Loading ----------
    public void clearContent() {
        mode = "empty";
        imageBitmap = null;
        imageTiles.clear();
        gifMovie = null;
        liveColorFilter = null;
        imageAnns.clear();
        pdfPages.clear();
        pdfTiles.clear();
        ocrBoxes.clear();
        activeOcrIndex = -1;
        hasSelection = false;
        selectedAnnId = null;
        invalidate();
    }

    public void setImageContent(Bitmap bmp, Movie gif, List<MarkupModel.Annotation> anns, boolean resetTransform) {
        this.mode = "image";
        this.imageBitmap = bmp;
        imageTiles.clear();
        this.gifMovie = gif;
        this.gifStartTime = SystemClock.uptimeMillis();
        this.imageAnns = anns != null ? anns : new ArrayList<MarkupModel.Annotation>();
        this.liveColorFilter = null;
        this.hasSelection = false;
        if (bmp != null) {
            this.cropRect.set(0, 0, bmp.getWidth(), bmp.getHeight());
        }
        if (resetTransform) {
            post(new Runnable() {
                @Override
                public void run() {
                    fitToWindow();
                }
            });
        } else {
            invalidate();
        }
    }

    public Bitmap getImageBitmap() {
        return imageBitmap;
    }

    public List<MarkupModel.Annotation> getImageAnnotations() {
        return imageAnns;
    }

    public void setImageAnnotations(List<MarkupModel.Annotation> anns) {
        this.imageAnns = anns != null ? anns : new ArrayList<MarkupModel.Annotation>();
        invalidate();
    }

    public void setPdfContent(List<MarkupModel.PdfPageItem> pages, String viewMode, int startPage) {
        pdfTiles.clear();
        this.mode = "pdf";
        this.pdfPages = pages != null ? pages : new ArrayList<MarkupModel.PdfPageItem>();
        this.pdfViewMode = viewMode != null ? viewMode : "cont";
        this.currentPdfPage = Math.max(0, Math.min(startPage, Math.max(0, this.pdfPages.size() - 1)));
        this.selectedAnnId = null;
        post(new Runnable() {
            @Override
            public void run() {
                fitToWindow();
            }
        });
    }

    public List<MarkupModel.PdfPageItem> getPdfPages() {
        return pdfPages;
    }

    public String getPdfViewMode() {
        return pdfViewMode;
    }

    public void setPdfViewMode(String viewMode) {
        this.pdfViewMode = viewMode;
        fitToWindow();
    }

    public int getCurrentPdfPage() {
        return currentPdfPage;
    }

    public void setCurrentPdfPage(int pageIdx) {
        if (pdfPages.isEmpty()) return;
        int clamped = Math.max(0, Math.min(pageIdx, pdfPages.size() - 1));
        currentPdfPage = clamped;
        if ("cont".equals(pdfViewMode)) {
            scrollToPdfPage(clamped);
        } else {
            fitToWindow();
        }
        if (listener != null) listener.onPdfPageChanged(currentPdfPage, pdfPages.size());
        invalidate();
    }

    private void scrollToPdfPage(int pageIdx) {
        if (pdfPages.isEmpty() || getWidth() <= 0) return;
        float y = dp(20);
        float gap = dp(16);
        for (int i = 0; i < pageIdx && i < pdfPages.size(); i++) {
            MarkupModel.PdfPageItem p = pdfPages.get(i);
            y += p.displayHeightPt() * zoom + gap;
        }
        panY = dp(20) - y + dp(20);
        clampPdfPan();
        invalidate();
    }

    // ---------- Zoom & Fit ----------
    public float getZoom() {
        return zoom;
    }

    public void setZoom(float newZoom) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float prev = zoom;
        zoom = Math.max(0.05f, Math.min(32f, newZoom));
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.5f;
        panX = cx - (cx - panX) * (zoom / prev);
        panY = cy - (cy - panY) * (zoom / prev);
        isFitMode = false;
        clampPdfPan();
        if (listener != null) listener.onZoomChanged(zoom);
        invalidate();
    }

    public void fitToWindow() {
        int vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0) return;
        float pad = dp(24);
        if ("image".equals(mode) && imageBitmap != null) {
            float iw = imageBitmap.getWidth();
            float ih = imageBitmap.getHeight();
            fitZoom = Math.min((vw - pad * 2f) / Math.max(1f, iw), (vh - pad * 2f) / Math.max(1f, ih));
            fitZoom = Math.max(0.05f, Math.min(8f, fitZoom));
            zoom = fitZoom;
            panX = (vw - iw * zoom) * 0.5f;
            panY = (vh - ih * zoom) * 0.5f;
            isFitMode = true;
        } else if ("pdf".equals(mode) && !pdfPages.isEmpty()) {
            MarkupModel.PdfPageItem p = pdfPages.get(Math.max(0, Math.min(currentPdfPage, pdfPages.size() - 1)));
            float pw = p.displayWidthPt();
            float ph = p.displayHeightPt();
            if ("cont".equals(pdfViewMode)) {
                fitZoom = Math.min((vw - pad * 2f) / Math.max(1f, pw), (vh - pad * 2f) / Math.max(1f, ph) * 1.15f);
                zoom = fitZoom;
                panX = (vw - pw * zoom) * 0.5f;
                scrollToPdfPage(currentPdfPage);
            } else if ("two".equals(pdfViewMode)) {
                MarkupModel.PdfPageItem p2 = (currentPdfPage + 1 < pdfPages.size()) ? pdfPages.get(currentPdfPage + 1) : null;
                float totalW = pw + (p2 != null ? p2.displayWidthPt() + 16f : 0f);
                float maxH = Math.max(ph, p2 != null ? p2.displayHeightPt() : 0f);
                fitZoom = Math.min((vw - pad * 2f) / Math.max(1f, totalW), (vh - pad * 2f) / Math.max(1f, maxH));
                zoom = fitZoom;
                panX = (vw - totalW * zoom) * 0.5f;
                panY = (vh - maxH * zoom) * 0.5f;
            } else {
                fitZoom = Math.min((vw - pad * 2f) / Math.max(1f, pw), (vh - pad * 2f) / Math.max(1f, ph));
                zoom = fitZoom;
                panX = (vw - pw * zoom) * 0.5f;
                panY = (vh - ph * zoom) * 0.5f;
            }
            isFitMode = true;
        }
        if (listener != null) listener.onZoomChanged(zoom);
        invalidate();
    }

    public void fitWidth() {
        int vw = getWidth();
        if (vw <= 0) return;
        float pad = dp(20);
        if ("image".equals(mode) && imageBitmap != null) {
            zoom = (vw - pad * 2f) / Math.max(1f, imageBitmap.getWidth());
            panX = pad;
            panY = Math.max(pad, (getHeight() - imageBitmap.getHeight() * zoom) * 0.5f);
            isFitMode = false;
        } else if ("pdf".equals(mode) && !pdfPages.isEmpty()) {
            MarkupModel.PdfPageItem p = pdfPages.get(currentPdfPage);
            zoom = (vw - pad * 2f) / Math.max(1f, p.displayWidthPt());
            panX = pad;
            if (!"cont".equals(pdfViewMode)) {
                panY = Math.max(pad, (getHeight() - p.displayHeightPt() * zoom) * 0.5f);
            }
            isFitMode = false;
        }
        if (listener != null) listener.onZoomChanged(zoom);
        invalidate();
    }

    // ---------- Tools Configuration ----------
    public void setActiveTool(String tool) {
        this.activeTool = tool != null ? tool : "hand";
        if ("crop".equals(this.activeTool) && imageBitmap != null) {
            cropRect.set(0, 0, imageBitmap.getWidth(), imageBitmap.getHeight());
            applyCropAspectConstraint();
        }
        if (!"select".equals(this.activeTool)) {
            hasSelection = false;
        }
        invalidate();
    }

    public String getActiveTool() {
        return activeTool;
    }

    public void setMarkupOptions(String kind, int color, float width, float fontSize, boolean fill) {
        this.markupKind = kind != null ? kind : "pen";
        this.markupColor = color;
        this.markupWidth = width;
        this.markupFontSize = fontSize;
        this.markupFill = fill;
        if (selectedAnnId != null) {
            MarkupModel.Annotation sel = findSelectedAnnotation();
            if (sel != null) {
                sel.color = color;
                sel.strokeWidth = width;
                sel.fontSize = fontSize;
                sel.fillSolid = fill;
                if (listener != null) listener.onAnnotationModified();
                invalidate();
            }
        }
    }

    public String getMarkupKind() {
        return markupKind;
    }

    public int getMarkupColor() {
        return markupColor;
    }

    public float getMarkupWidth() {
        return markupWidth;
    }

    public float getMarkupFontSize() {
        return markupFontSize;
    }

    public boolean isMarkupFill() {
        return markupFill;
    }

    public void setLiveColorFilter(ColorFilter cf) {
        this.liveColorFilter = cf;
        invalidate();
    }

    public void setCropAspect(float aspect) {
        this.cropAspect = aspect;
        applyCropAspectConstraint();
        invalidate();
    }

    public RectF getCropRect() {
        return new RectF(cropRect);
    }

    public RectF getSelectionRect() {
        return hasSelection ? new RectF(selectRect) : null;
    }

    public void clearSelection() {
        hasSelection = false;
        invalidate();
    }

    private void applyCropAspectConstraint() {
        if (imageBitmap == null || cropAspect <= 0f) return;
        float iw = imageBitmap.getWidth();
        float ih = imageBitmap.getHeight();
        float cw = iw, ch = iw / cropAspect;
        if (ch > ih) {
            ch = ih;
            cw = ih * cropAspect;
        }
        float left = (iw - cw) * 0.5f;
        float top = (ih - ch) * 0.5f;
        cropRect.set(left, top, left + cw, top + ch);
    }

    public void setOcrBoxes(List<OcrBox> boxes, int activeIdx) {
        this.ocrBoxes.clear();
        if (boxes != null) this.ocrBoxes.addAll(boxes);
        this.activeOcrIndex = activeIdx;
        if (activeIdx >= 0 && activeIdx < this.ocrBoxes.size()) {
            OcrBox b = this.ocrBoxes.get(activeIdx);
            if ("pdf".equals(mode) && b.pageIndex >= 0 && b.pageIndex != currentPdfPage) {
                setCurrentPdfPage(b.pageIndex);
            }
        }
        invalidate();
    }

    public MarkupModel.Annotation findSelectedAnnotation() {
        if (selectedAnnId == null) return null;
        if ("image".equals(mode)) {
            for (MarkupModel.Annotation a : imageAnns) {
                if (selectedAnnId.equals(a.id)) return a;
            }
        } else if ("pdf".equals(mode) && selectedAnnPage >= 0 && selectedAnnPage < pdfPages.size()) {
            for (MarkupModel.Annotation a : pdfPages.get(selectedAnnPage).anns) {
                if (selectedAnnId.equals(a.id)) return a;
            }
        }
        return null;
    }

    public boolean deleteSelectedAnnotation() {
        if (selectedAnnId == null) return false;
        if ("image".equals(mode)) {
            for (int i = 0; i < imageAnns.size(); i++) {
                if (selectedAnnId.equals(imageAnns.get(i).id)) {
                    imageAnns.remove(i);
                    selectedAnnId = null;
                    invalidate();
                    return true;
                }
            }
        } else if ("pdf".equals(mode) && selectedAnnPage >= 0 && selectedAnnPage < pdfPages.size()) {
            List<MarkupModel.Annotation> list = pdfPages.get(selectedAnnPage).anns;
            for (int i = 0; i < list.size(); i++) {
                if (selectedAnnId.equals(list.get(i).id)) {
                    list.remove(i);
                    selectedAnnId = null;
                    invalidate();
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0 && isFitMode) {
            fitToWindow();
        }
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            panX = scroller.getCurrX();
            panY = scroller.getCurrY();
            clampPdfPan();
            updateCurrentPdfPageFromScroll();
            postInvalidateOnAnimation();
        }
    }

    private float getMinPdfPanY() {
        if (pdfPages.isEmpty()) return 0f;
        float totalH = dp(40);
        float gap = dp(16);
        for (MarkupModel.PdfPageItem p : pdfPages) {
            totalH += p.displayHeightPt() * zoom + gap;
        }
        return Math.min(dp(20), getHeight() - totalH);
    }

    private void clampPdfPan() {
        if (!"pdf".equals(mode) || !"cont".equals(pdfViewMode)) return;
        float minY = getMinPdfPanY();
        if (panY < minY) panY = minY;
        if (panY > dp(40)) panY = dp(40);
    }

    private void updateCurrentPdfPageFromScroll() {
        if (!"pdf".equals(mode) || !"cont".equals(pdfViewMode) || pdfPageScreenRects.isEmpty()) return;
        float centerY = getHeight() * 0.45f;
        int bestIdx = currentPdfPage;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < pdfPageScreenRects.size(); i++) {
            RectF r = pdfPageScreenRects.get(i);
            float cy = r.centerY();
            float d = Math.abs(cy - centerY);
            if (d < bestDist) {
                bestDist = d;
                bestIdx = i;
            }
        }
        if (bestIdx != currentPdfPage && bestIdx >= 0 && bestIdx < pdfPages.size()) {
            currentPdfPage = bestIdx;
            if (listener != null) listener.onPdfPageChanged(currentPdfPage, pdfPages.size());
        }
    }

    // ---------- Drawing ----------
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if ("image".equals(mode)) {
            drawImageStage(canvas);
        } else if ("pdf".equals(mode)) {
            drawPdfStage(canvas);
        }
    }

    private void drawImageStage(Canvas canvas) {
        if (imageBitmap == null || imageBitmap.isRecycled()) return;
        int iw = imageBitmap.getWidth();
        int ih = imageBitmap.getHeight();
        RectF dst = new RectF(panX, panY, panX + iw * zoom, panY + ih * zoom);

        // Shadow & Checkerboard
        canvas.drawRect(dst.left + dp(2), dst.top + dp(4), dst.right + dp(2), dst.bottom + dp(4), shadowPaint);
        if (showChecker) {
            drawCheckerboard(canvas, dst);
        } else {
            overlayPaint.setStyle(Paint.Style.FILL);
            overlayPaint.setColor(isDarkTheme ? 0xFF14161B : Color.WHITE);
            canvas.drawRect(dst, overlayPaint);
        }

        // Animated GIF or Static Bitmap
        if (gifMovie != null && liveColorFilter == null && imageAnns.isEmpty() && "hand".equals(activeTool)) {
            int dur = gifMovie.duration();
            if (dur <= 0) dur = 1000;
            int relTime = (int) ((SystemClock.uptimeMillis() - gifStartTime) % dur);
            gifMovie.setTime(relTime);
            canvas.save();
            canvas.translate(panX, panY);
            canvas.scale(zoom, zoom);
            gifMovie.draw(canvas, 0, 0);
            canvas.restore();
            postInvalidateOnAnimation();
        } else {
            bitmapPaint.setColorFilter(liveColorFilter);
            canvas.save();
            canvas.translate(panX, panY);
            canvas.scale(zoom, zoom);
            RectF visible=new RectF(Math.max(0,-panX/zoom),Math.max(0,-panY/zoom),Math.min(iw,(getWidth()-panX)/zoom),Math.min(ih,(getHeight()-panY)/zoom));
            imageTiles.draw(canvas,imageBitmap,bitmapPaint,zoom,visible);
            bitmapPaint.setColorFilter(null);

            // Vector annotations
            MarkupModel.drawAnnotations(canvas, imageAnns, selectedAnnId);
            if (drawingAnn != null && drawingPageIdx == -1) {
                MarkupModel.drawSingle(canvas, drawingAnn, false);
            }
            canvas.restore();
        }

        canvas.drawRect(dst, borderPaint);

        // OCR Highlight Boxes
        if (!ocrBoxes.isEmpty()) {
            drawOcrOverlays(canvas, -1, dst, iw, ih);
        }

        // Interactive Overlays (Crop or Select)
        if ("crop".equals(activeTool)) {
            drawCropOverlay(canvas);
        } else if ("select".equals(activeTool) && hasSelection) {
            drawSelectionOverlay(canvas);
        }
    }

    private void drawCheckerboard(Canvas canvas, RectF rect) {
        canvas.save();
        canvas.clipRect(rect);
        canvas.drawRect(rect, checkerLight);
        float cell = dp(10);
        int cols = (int) Math.ceil(rect.width() / cell) + 1;
        int rows = (int) Math.ceil(rect.height() / cell) + 1;
        if (cols * rows > 4500) {
            cell = Math.max(dp(18), Math.max(rect.width(), rect.height()) / 45f);
            cols = (int) Math.ceil(rect.width() / cell) + 1;
            rows = (int) Math.ceil(rect.height() / cell) + 1;
        }
        for (int r = 0; r < rows; r++) {
            for (int c = (r & 1); c < cols; c += 2) {
                float l = rect.left + c * cell;
                float t = rect.top + r * cell;
                canvas.drawRect(l, t, l + cell, t + cell, checkerDark);
            }
        }
        canvas.restore();
    }

    private void drawCropOverlay(Canvas canvas) {
        if (imageBitmap == null) return;
        float l = panX + cropRect.left * zoom;
        float t = panY + cropRect.top * zoom;
        float r = panX + cropRect.right * zoom;
        float b = panY + cropRect.bottom * zoom;
        RectF imgScreen = new RectF(panX, panY, panX + imageBitmap.getWidth() * zoom, panY + imageBitmap.getHeight() * zoom);

        // Dim outside crop area
        overlayPaint.setStyle(Paint.Style.FILL);
        overlayPaint.setColor(0x99000000);
        canvas.drawRect(imgScreen.left, imgScreen.top, imgScreen.right, t, overlayPaint);
        canvas.drawRect(imgScreen.left, b, imgScreen.right, imgScreen.bottom, overlayPaint);
        canvas.drawRect(imgScreen.left, t, l, b, overlayPaint);
        canvas.drawRect(r, t, imgScreen.right, b, overlayPaint);

        // Rule of thirds lines
        overlayPaint.setStyle(Paint.Style.STROKE);
        overlayPaint.setStrokeWidth(dp(1));
        overlayPaint.setColor(0x66FFFFFF);
        for (int i = 1; i <= 2; i++) {
            float gx = l + (r - l) * (i / 3f);
            float gy = t + (b - t) * (i / 3f);
            canvas.drawLine(gx, t, gx, b, overlayPaint);
            canvas.drawLine(l, gy, r, gy, overlayPaint);
        }

        // Crop border
        overlayPaint.setStrokeWidth(dp(2));
        overlayPaint.setColor(0xFF0381FE);
        canvas.drawRect(l, t, r, b, overlayPaint);

        // 8 Handles
        float hr = dp(7);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(Color.WHITE);
        Paint strokeP = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokeP.setStyle(Paint.Style.STROKE);
        strokeP.setStrokeWidth(dp(2));
        strokeP.setColor(0xFF0381FE);

        float cx = (l + r) * 0.5f, cy = (t + b) * 0.5f;
        float[][] pts = new float[][]{
                {l, t}, {cx, t}, {r, t},
                {r, cy}, {r, b}, {cx, b},
                {l, b}, {l, cy}
        };
        for (float[] pt : pts) {
            canvas.drawCircle(pt[0], pt[1], hr, handlePaint);
            canvas.drawCircle(pt[0], pt[1], hr, strokeP);
        }

        // Dimensions badge
        String dim = Math.round(cropRect.width()) + " × " + Math.round(cropRect.height()) + " px";
        drawFloatingBadge(canvas, cx, Math.max(dp(24), t - dp(14)), dim);
    }

    private void drawSelectionOverlay(Canvas canvas) {
        float l = panX + selectRect.left * zoom;
        float t = panY + selectRect.top * zoom;
        float r = panX + selectRect.right * zoom;
        float b = panY + selectRect.bottom * zoom;

        overlayPaint.setStyle(Paint.Style.FILL);
        overlayPaint.setColor(0x280381FE);
        canvas.drawRect(l, t, r, b, overlayPaint);

        overlayPaint.setStyle(Paint.Style.STROKE);
        overlayPaint.setStrokeWidth(dp(1.8f));
        overlayPaint.setColor(0xFF0381FE);
        overlayPaint.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(4)}, 0));
        canvas.drawRect(l, t, r, b, overlayPaint);
        overlayPaint.setPathEffect(null);

        String dim = Math.round(selectRect.width()) + " × " + Math.round(selectRect.height()) + " px";
        drawFloatingBadge(canvas, (l + r) * 0.5f, Math.max(dp(24), t - dp(12)), dim);
    }

    private void drawFloatingBadge(Canvas canvas, float cx, float cy, String text) {
        float tw = textBadgePaint.measureText(text);
        float padH = dp(8), padV = dp(4);
        RectF bg = new RectF(cx - tw * 0.5f - padH, cy - dp(10) - padV, cx + tw * 0.5f + padH, cy + dp(4) + padV);
        Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
        bp.setColor(0xCC12141A);
        canvas.drawRoundRect(bg, dp(8), dp(8), bp);
        canvas.drawText(text, cx - tw * 0.5f, cy, textBadgePaint);
    }

    private void drawPdfStage(Canvas canvas) {
        pdfPageScreenRects.clear();
        if (pdfPages.isEmpty()) return;

        if ("cont".equals(pdfViewMode)) {
            float y = panY;
            float gap = dp(16);
            for (int i = 0; i < pdfPages.size(); i++) {
                MarkupModel.PdfPageItem p = pdfPages.get(i);
                float pw = p.displayWidthPt() * zoom;
                float ph = p.displayHeightPt() * zoom;
                float x = panX;
                RectF rect = new RectF(x, y, x + pw, y + ph);
                pdfPageScreenRects.add(rect);
                if (rect.bottom >= -dp(100) && rect.top <= getHeight() + dp(100)) {
                    drawSinglePdfPage(canvas, i, p, rect);
                }
                y += ph + gap;
            }
        } else if ("two".equals(pdfViewMode)) {
            for (int i = 0; i < pdfPages.size(); i++) pdfPageScreenRects.add(new RectF());
            int i1 = currentPdfPage;
            int i2 = currentPdfPage + 1 < pdfPages.size() ? currentPdfPage + 1 : -1;
            MarkupModel.PdfPageItem p1 = pdfPages.get(i1);
            float pw1 = p1.displayWidthPt() * zoom;
            float ph1 = p1.displayHeightPt() * zoom;
            RectF r1 = new RectF(panX, panY, panX + pw1, panY + ph1);
            pdfPageScreenRects.set(i1, r1);
            drawSinglePdfPage(canvas, i1, p1, r1);
            if (i2 >= 0) {
                MarkupModel.PdfPageItem p2 = pdfPages.get(i2);
                float pw2 = p2.displayWidthPt() * zoom;
                float ph2 = p2.displayHeightPt() * zoom;
                float gap = 16f * zoom;
                RectF r2 = new RectF(r1.right + gap, panY, r1.right + gap + pw2, panY + ph2);
                pdfPageScreenRects.set(i2, r2);
                drawSinglePdfPage(canvas, i2, p2, r2);
            }
        } else {
            for (int i = 0; i < pdfPages.size(); i++) pdfPageScreenRects.add(new RectF());
            MarkupModel.PdfPageItem p = pdfPages.get(currentPdfPage);
            float pw = p.displayWidthPt() * zoom;
            float ph = p.displayHeightPt() * zoom;
            RectF r = new RectF(panX, panY, panX + pw, panY + ph);
            pdfPageScreenRects.set(currentPdfPage, r);
            drawSinglePdfPage(canvas, currentPdfPage, p, r);
        }
    }

    private void drawSinglePdfPage(Canvas canvas, int pageIndex, MarkupModel.PdfPageItem page, RectF screenRect) {
        canvas.drawRect(screenRect.left + dp(2), screenRect.top + dp(4), screenRect.right + dp(2), screenRect.bottom + dp(4), shadowPaint);
        overlayPaint.setStyle(Paint.Style.FILL);
        overlayPaint.setColor(Color.WHITE);
        canvas.drawRect(screenRect, overlayPaint);

        pdfTiles.draw(canvas,page,screenRect,zoom,bitmapPaint,getWidth(),getHeight());

        // Draw page annotations in PDF Point space
        canvas.save();
        canvas.translate(screenRect.left, screenRect.top);
        canvas.scale(zoom, zoom);
        MarkupModel.drawAnnotations(canvas, page.anns, pageIndex == selectedAnnPage ? selectedAnnId : null);
        if (drawingAnn != null && drawingPageIdx == pageIndex) {
            MarkupModel.drawSingle(canvas, drawingAnn, false);
        }
        canvas.restore();

        // Highlight active page border
        borderPaint.setColor(pageIndex == currentPdfPage ? 0x990381FE : 0x33000000);
        borderPaint.setStrokeWidth(pageIndex == currentPdfPage ? dp(1.5f) : dp(1f));
        canvas.drawRect(screenRect, borderPaint);

        // OCR Highlight Boxes on this page
        if (!ocrBoxes.isEmpty()) {
            drawOcrOverlays(canvas, pageIndex, screenRect, page.displayWidthPt(), page.displayHeightPt());
        }
    }

    private void drawOcrOverlays(Canvas canvas, int pageIndex, RectF screenRect, float docW, float docH) {
        for (int i = 0; i < ocrBoxes.size(); i++) {
            OcrBox b = ocrBoxes.get(i);
            if (b.pageIndex != pageIndex) continue;
            float l, t, r, bot;
            if (b.normalized) {
                l = screenRect.left + b.x * screenRect.width();
                t = screenRect.top + b.y * screenRect.height();
                r = l + b.w * screenRect.width();
                bot = t + b.h * screenRect.height();
            } else {
                l = screenRect.left + (b.x / Math.max(1f, docW)) * screenRect.width();
                t = screenRect.top + (b.y / Math.max(1f, docH)) * screenRect.height();
                r = l + (b.w / Math.max(1f, docW)) * screenRect.width();
                bot = t + (b.h / Math.max(1f, docH)) * screenRect.height();
            }
            boolean isCurrent = (i == activeOcrIndex);
            overlayPaint.setStyle(Paint.Style.FILL);
            overlayPaint.setColor(isCurrent ? 0x66FF9800 : 0x44FFEB3B);
            canvas.drawRoundRect(new RectF(l - dp(2), t - dp(2), r + dp(2), bot + dp(2)), dp(4), dp(4), overlayPaint);
            overlayPaint.setStyle(Paint.Style.STROKE);
            overlayPaint.setStrokeWidth(dp(isCurrent ? 2f : 1f));
            overlayPaint.setColor(isCurrent ? 0xFFFF6D00 : 0xFFFBC02D);
            canvas.drawRoundRect(new RectF(l - dp(2), t - dp(2), r + dp(2), bot + dp(2)), dp(4), dp(4), overlayPaint);
        }
    }

    // ---------- Touch Interaction ----------
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        int action = event.getActionMasked();
        float sx = event.getX();
        float sy = event.getY();

        if (event.getPointerCount() > 1 || isScaling) {
            drawingAnn = null;
            draggingAnn = null;
            activeCropHandle = null;
            isDraggingTool = false;
            return true;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                downTouch.set(sx, sy);
                lastTouch.set(sx, sy);
                handleTouchDown(sx, sy);
                break;

            case MotionEvent.ACTION_MOVE:
                handleTouchMove(sx, sy);
                lastTouch.set(sx, sy);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handleTouchUp(sx, sy);
                break;
        }
        return true;
    }

    private PointF screenToImage(float sx, float sy) {
        return new PointF((sx - panX) / zoom, (sy - panY) / zoom);
    }

    private int findPdfPageAtScreen(float sx, float sy) {
        for (int i = 0; i < pdfPageScreenRects.size(); i++) {
            RectF r = pdfPageScreenRects.get(i);
            if (r != null && !r.isEmpty() && r.contains(sx, sy)) return i;
        }
        return currentPdfPage;
    }

    private PointF screenToPdfPage(int pageIdx, float sx, float sy) {
        if (pageIdx < 0 || pageIdx >= pdfPageScreenRects.size()) return new PointF(0, 0);
        RectF r = pdfPageScreenRects.get(pageIdx);
        return new PointF((sx - r.left) / zoom, (sy - r.top) / zoom);
    }

    private void handleTouchDown(float sx, float sy) {
        if ("image".equals(mode) && imageBitmap != null) {
            PointF pt = screenToImage(sx, sy);
            int ix = Math.round(pt.x), iy = Math.round(pt.y);
            if (ix >= 0 && iy >= 0 && ix < imageBitmap.getWidth() && iy < imageBitmap.getHeight()) {
                int px = imageBitmap.getPixel(ix, iy);
                String hex = String.format(Locale.US, "#%02X%02X%02X", Color.red(px), Color.green(px), Color.blue(px));
                if (listener != null) listener.onCursorInfo("(" + ix + ", " + iy + ")  " + hex);
            }
        }

        if ("crop".equals(activeTool) && "image".equals(mode) && imageBitmap != null) {
            activeCropHandle = hitTestCropHandle(sx, sy);
            if (activeCropHandle != null) {
                isDraggingTool = true;
                PointF imgPt = screenToImage(sx, sy);
                cropDragStart.set(imgPt.x, imgPt.y);
                cropStartRect.set(cropRect);
            }
            return;
        }

        if ("select".equals(activeTool) && "image".equals(mode) && imageBitmap != null) {
            PointF imgPt = screenToImage(sx, sy);
            float cx = Math.max(0, Math.min(imageBitmap.getWidth(), imgPt.x));
            float cy = Math.max(0, Math.min(imageBitmap.getHeight(), imgPt.y));
            selectRect.set(cx, cy, cx, cy);
            hasSelection = true;
            isDraggingTool = true;
            cropDragStart.set(cx, cy);
            invalidate();
            return;
        }

        if ("alpha".equals(activeTool) && "image".equals(mode) && imageBitmap != null) {
            isDraggingTool = true;
            return;
        }

        if ("markup".equals(activeTool)) {
            int pageIdx = -1;
            PointF docPt;
            List<MarkupModel.Annotation> targetList;
            if ("image".equals(mode) && imageBitmap != null) {
                docPt = screenToImage(sx, sy);
                targetList = imageAnns;
            } else if ("pdf".equals(mode) && !pdfPages.isEmpty()) {
                pageIdx = findPdfPageAtScreen(sx, sy);
                currentPdfPage = pageIdx;
                docPt = screenToPdfPage(pageIdx, sx, sy);
                targetList = pdfPages.get(pageIdx).anns;
            } else {
                return;
            }

            if ("select".equals(markupKind)) {
                MarkupModel.Annotation hit = MarkupModel.hitTest(targetList, docPt.x, docPt.y, 12f / zoom);
                if (hit != null) {
                    selectedAnnId = hit.id;
                    selectedAnnPage = pageIdx;
                    draggingAnn = hit;
                    isDraggingTool = true;
                    cropDragStart.set(docPt.x, docPt.y);
                    if (listener != null) listener.onAnnotationSelected(hit, pageIdx);
                } else {
                    selectedAnnId = null;
                    draggingAnn = null;
                    if (listener != null) listener.onAnnotationSelected(null, pageIdx);
                }
                invalidate();
                return;
            }

            if ("text".equals(markupKind)) {
                isDraggingTool = true;
                drawingPageIdx = pageIdx;
                cropDragStart.set(docPt.x, docPt.y);
                return;
            }

            if ("sign".equals(markupKind)) {
                isDraggingTool = true;
                drawingPageIdx = pageIdx;
                cropDragStart.set(docPt.x, docPt.y);
                return;
            }

            // Start drawing vector shape
            String uid = "ann_" + System.currentTimeMillis();
            MarkupModel.Annotation a = new MarkupModel.Annotation(uid, markupKind);
            a.color = markupColor;
            a.strokeWidth = markupWidth;
            a.fontSize = markupFontSize;
            a.fillSolid = markupFill;
            a.x = docPt.x;
            a.y = docPt.y;
            a.x2 = docPt.x;
            a.y2 = docPt.y;
            a.w = 0;
            a.h = 0;
            if ("pen".equals(markupKind) || "hl".equals(markupKind)) {
                a.points.add(new float[]{docPt.x, docPt.y});
            }
            drawingAnn = a;
            drawingPageIdx = pageIdx;
            isDraggingTool = true;
            invalidate();
        }
    }

    private void handleTouchMove(float sx, float sy) {
        float dx = sx - lastTouch.x;
        float dy = sy - lastTouch.y;

        if ("crop".equals(activeTool) && isDraggingTool && activeCropHandle != null && imageBitmap != null) {
            PointF imgPt = screenToImage(sx, sy);
            float dIx = imgPt.x - cropDragStart.x;
            float dIy = imgPt.y - cropDragStart.y;
            updateCropRectByHandle(activeCropHandle, dIx, dIy);
            invalidate();
            return;
        }

        if ("select".equals(activeTool) && isDraggingTool && imageBitmap != null) {
            PointF imgPt = screenToImage(sx, sy);
            float cx = Math.max(0, Math.min(imageBitmap.getWidth(), imgPt.x));
            float cy = Math.max(0, Math.min(imageBitmap.getHeight(), imgPt.y));
            selectRect.set(
                    Math.min(cropDragStart.x, cx),
                    Math.min(cropDragStart.y, cy),
                    Math.max(cropDragStart.x, cx),
                    Math.max(cropDragStart.y, cy)
            );
            if (listener != null) listener.onSelectionChanged(selectRect);
            invalidate();
            return;
        }

        if ("markup".equals(activeTool) && isDraggingTool) {
            PointF docPt = ("image".equals(mode)) ? screenToImage(sx, sy) : screenToPdfPage(drawingPageIdx >= 0 ? drawingPageIdx : selectedAnnPage, sx, sy);
            if (draggingAnn != null) {
                MarkupModel.moveAnnotation(draggingAnn, docPt.x - cropDragStart.x, docPt.y - cropDragStart.y);
                cropDragStart.set(docPt.x, docPt.y);
                invalidate();
                return;
            }
            if (drawingAnn != null) {
                if ("pen".equals(drawingAnn.type) || "hl".equals(drawingAnn.type)) {
                    drawingAnn.points.add(new float[]{docPt.x, docPt.y});
                } else if ("line".equals(drawingAnn.type) || "arrow".equals(drawingAnn.type)) {
                    drawingAnn.x2 = docPt.x;
                    drawingAnn.y2 = docPt.y;
                } else {
                    drawingAnn.w = docPt.x - drawingAnn.x;
                    drawingAnn.h = docPt.y - drawingAnn.y;
                }
                invalidate();
                return;
            }
        }

        // Default Pan
        panX += dx;
        panY += dy;
        isFitMode = false;
        clampPdfPan();
        updateCurrentPdfPageFromScroll();
        invalidate();
    }

    private void handleTouchUp(float sx, float sy) {
        if ("alpha".equals(activeTool) && isDraggingTool && "image".equals(mode) && imageBitmap != null) {
            isDraggingTool = false;
            if (Math.hypot(sx - downTouch.x, sy - downTouch.y) < dp(12)) {
                PointF pt = screenToImage(sx, sy);
                int ix = Math.round(pt.x), iy = Math.round(pt.y);
                if (ix >= 0 && iy >= 0 && ix < imageBitmap.getWidth() && iy < imageBitmap.getHeight()) {
                    if (listener != null) listener.onAlphaTap(ix, iy);
                }
            }
            return;
        }

        if ("markup".equals(activeTool) && isDraggingTool) {
            isDraggingTool = false;
            if ("text".equals(markupKind) && Math.hypot(sx - downTouch.x, sy - downTouch.y) < dp(14)) {
                if (listener != null) listener.onTextToolTap(cropDragStart.x, cropDragStart.y, drawingPageIdx);
                return;
            }
            if ("sign".equals(markupKind) && Math.hypot(sx - downTouch.x, sy - downTouch.y) < dp(14)) {
                if (listener != null) listener.onSignToolTap(cropDragStart.x, cropDragStart.y, drawingPageIdx);
                return;
            }
            if (draggingAnn != null) {
                draggingAnn = null;
                if (listener != null) listener.onAnnotationModified();
                return;
            }
            if (drawingAnn != null) {
                MarkupModel.Annotation finished = drawingAnn;
                int pIdx = drawingPageIdx;
                drawingAnn = null;
                if ("rect".equals(finished.type) || "ellipse".equals(finished.type) || "redact".equals(finished.type)) {
                    if (finished.w < 0) { finished.x += finished.w; finished.w = -finished.w; }
                    if (finished.h < 0) { finished.y += finished.h; finished.h = -finished.h; }
                }
                selectedAnnId = finished.id;
                selectedAnnPage = pIdx;
                if (listener != null) listener.onAnnotationCreated(finished, pIdx);
                invalidate();
                return;
            }
        }

        activeCropHandle = null;
        isDraggingTool = false;
    }

    private String hitTestCropHandle(float sx, float sy) {
        float l = panX + cropRect.left * zoom;
        float t = panY + cropRect.top * zoom;
        float r = panX + cropRect.right * zoom;
        float b = panY + cropRect.bottom * zoom;
        float cx = (l + r) * 0.5f, cy = (t + b) * 0.5f;
        float tol = dp(22);

        String[] names = {"nw", "n", "ne", "e", "se", "s", "sw", "w"};
        float[][] pts = {
                {l, t}, {cx, t}, {r, t},
                {r, cy}, {r, b}, {cx, b},
                {l, b}, {l, cy}
        };
        for (int i = 0; i < names.length; i++) {
            if (Math.hypot(sx - pts[i][0], sy - pts[i][1]) <= tol) return names[i];
        }
        if (sx >= l && sx <= r && sy >= t && sy <= b) return "move";
        return null;
    }

    private void updateCropRectByHandle(String h, float dx, float dy) {
        if (imageBitmap == null) return;
        float iw = imageBitmap.getWidth(), ih = imageBitmap.getHeight();
        float minS = 16f;
        RectF r = new RectF(cropStartRect);

        if ("move".equals(h)) {
            float nw = r.width(), nh = r.height();
            float nl = Math.max(0f, Math.min(iw - nw, r.left + dx));
            float nt = Math.max(0f, Math.min(ih - nh, r.top + dy));
            cropRect.set(nl, nt, nl + nw, nt + nh);
            return;
        }
        if (h.contains("w")) r.left = Math.max(0f, Math.min(r.right - minS, r.left + dx));
        if (h.contains("e")) r.right = Math.min(iw, Math.max(r.left + minS, r.right + dx));
        if (h.contains("n")) r.top = Math.max(0f, Math.min(r.bottom - minS, r.top + dy));
        if (h.contains("s")) r.bottom = Math.min(ih, Math.max(r.top + minS, r.bottom + dy));

        if (cropAspect > 0f) {
            float w = r.width();
            float targetH = w / cropAspect;
            if (r.top + targetH <= ih) {
                r.bottom = r.top + targetH;
            } else {
                r.bottom = ih;
                r.right = Math.min(iw, r.left + r.height() * cropAspect);
            }
        }
        cropRect.set(r);
    }
}
