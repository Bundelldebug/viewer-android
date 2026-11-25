package com.samsung.oneui.viewer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure Native Vector Annotation & PDF Page Model for Samsung Viewer Android.
 * Supports: pen, hl (highlighter), line, arrow, rect, ellipse, text, redact, sign.
 */
public final class MarkupModel {
    private MarkupModel() {}

    public static final class Annotation {
        public String id;
        public String type; // "pen", "hl", "line", "arrow", "rect", "ellipse", "text", "redact", "sign"
        public int color = 0xFFE53935;
        public float strokeWidth = 3f;
        public float fontSize = 20f;
        public boolean fillSolid = false;
        public String text = "";
        public Bitmap sigBitmap = null;
        public float x, y, w, h, x2, y2;
        public final List<float[]> points = new ArrayList<>();

        public Annotation(String id, String type) {
            this.id = id;
            this.type = type;
        }

        public Annotation copy() {
            Annotation c = new Annotation(id, type);
            c.color = color;
            c.strokeWidth = strokeWidth;
            c.fontSize = fontSize;
            c.fillSolid = fillSolid;
            c.text = text;
            c.sigBitmap = sigBitmap;
            c.x = x;
            c.y = y;
            c.w = w;
            c.h = h;
            c.x2 = x2;
            c.y2 = y2;
            for (float[] pt : points) {
                c.points.add(new float[]{pt[0], pt[1]});
            }
            return c;
        }

        public String label() {
            switch (type) {
                case "pen": return "펜";
                case "hl": return "형광펜";
                case "line": return "직선";
                case "arrow": return "화살표";
                case "rect": return "사각형";
                case "ellipse": return "원";
                case "text": return "텍스트" + (text != null && !text.isEmpty() ? ": " + (text.length() > 12 ? text.substring(0, 12) + "…" : text) : "");
                case "redact": return "가리기";
                case "sign": return "서명";
                default: return type;
            }
        }

        public RectF getBounds() {
            if ("pen".equals(type) || "hl".equals(type)) {
                if (points.isEmpty()) return new RectF(x, y, x + 10, y + 10);
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
                float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                for (float[] p : points) {
                    if (p[0] < minX) minX = p[0];
                    if (p[1] < minY) minY = p[1];
                    if (p[0] > maxX) maxX = p[0];
                    if (p[1] > maxY) maxY = p[1];
                }
                float pad = Math.max(4f, strokeWidth);
                return new RectF(minX - pad, minY - pad, maxX + pad, maxY + pad);
            }
            if ("line".equals(type) || "arrow".equals(type)) {
                float minX = Math.min(x, x2), maxX = Math.max(x, x2);
                float minY = Math.min(y, y2), maxY = Math.max(y, y2);
                return new RectF(minX - 6f, minY - 6f, maxX + 6f, maxY + 6f);
            }
            if ("text".equals(type)) {
                String[] lines = (text == null ? "" : text).split("\n");
                int maxChars = 2;
                for (String ln : lines) if (ln.length() > maxChars) maxChars = ln.length();
                float tw = Math.max(w, maxChars * fontSize * 0.72f + 12f);
                float th = Math.max(h, lines.length * fontSize * 1.3f + 8f);
                return new RectF(x, y, x + tw, y + th);
            }
            float rx = w < 0 ? x + w : x;
            float ry = h < 0 ? y + h : y;
            return new RectF(rx, ry, rx + Math.abs(w), ry + Math.abs(h));
        }
    }

    public static List<Annotation> copyList(List<Annotation> src) {
        List<Annotation> out = new ArrayList<>();
        if (src == null) return out;
        for (Annotation a : src) out.add(a.copy());
        return out;
    }

    public static void moveAnnotation(Annotation a, float dx, float dy) {
        if (a == null) return;
        if ("pen".equals(a.type) || "hl".equals(a.type)) {
            for (float[] p : a.points) {
                p[0] += dx;
                p[1] += dy;
            }
        } else if ("line".equals(a.type) || "arrow".equals(a.type)) {
            a.x += dx;
            a.y += dy;
            a.x2 += dx;
            a.y2 += dy;
        } else {
            a.x += dx;
            a.y += dy;
        }
    }

    public static Annotation hitTest(List<Annotation> anns, float x, float y, float tol) {
        if (anns == null) return null;
        for (int i = anns.size() - 1; i >= 0; i--) {
            Annotation a = anns.get(i);
            RectF b = a.getBounds();
            RectF expanded = new RectF(b.left - tol, b.top - tol, b.right + tol, b.bottom + tol);
            if (expanded.contains(x, y)) return a;
        }
        return null;
    }

    public static void drawAnnotations(Canvas canvas, List<Annotation> anns, String selectedId) {
        if (anns == null || anns.isEmpty()) return;
        for (Annotation a : anns) {
            drawSingle(canvas, a, a.id != null && a.id.equals(selectedId));
        }
    }

    public static void drawSingle(Canvas canvas, Annotation a, boolean selected) {
        if (a == null) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(a.color);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);

        switch (a.type) {
            case "pen":
            case "hl": {
                if (a.points.isEmpty()) break;
                p.setStyle(Paint.Style.STROKE);
                if ("hl".equals(a.type)) {
                    p.setStrokeWidth(Math.max(12f, a.strokeWidth * 4.2f));
                    p.setAlpha(98);
                } else {
                    p.setStrokeWidth(Math.max(1f, a.strokeWidth));
                }
                Path path = new Path();
                float[] p0 = a.points.get(0);
                path.moveTo(p0[0], p0[1]);
                for (int i = 1; i < a.points.size(); i++) {
                    float[] pt = a.points.get(i);
                    path.lineTo(pt[0], pt[1]);
                }
                if (a.points.size() == 1) {
                    path.lineTo(p0[0] + 0.5f, p0[1] + 0.5f);
                }
                canvas.drawPath(path, p);
                break;
            }
            case "line":
            case "arrow": {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1.5f, a.strokeWidth));
                canvas.drawLine(a.x, a.y, a.x2, a.y2, p);
                if ("arrow".equals(a.type)) {
                    double ang = Math.atan2(a.y2 - a.y, a.x2 - a.x);
                    float headLen = Math.max(12f, a.strokeWidth * 4f);
                    Path head = new Path();
                    head.moveTo(a.x2, a.y2);
                    head.lineTo(
                            (float) (a.x2 - headLen * Math.cos(ang - Math.PI / 6)),
                            (float) (a.y2 - headLen * Math.sin(ang - Math.PI / 6))
                    );
                    head.lineTo(
                            (float) (a.x2 - headLen * Math.cos(ang + Math.PI / 6)),
                            (float) (a.y2 - headLen * Math.sin(ang + Math.PI / 6))
                    );
                    head.close();
                    Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
                    hp.setColor(a.color);
                    hp.setStyle(Paint.Style.FILL);
                    canvas.drawPath(head, hp);
                }
                break;
            }
            case "rect": {
                RectF r = a.getBounds();
                if (a.fillSolid) {
                    Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
                    fp.setColor(a.color);
                    fp.setAlpha(68);
                    fp.setStyle(Paint.Style.FILL);
                    canvas.drawRect(r, fp);
                }
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1.5f, a.strokeWidth));
                canvas.drawRect(r, p);
                break;
            }
            case "ellipse": {
                RectF r = a.getBounds();
                if (a.fillSolid) {
                    Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
                    fp.setColor(a.color);
                    fp.setAlpha(68);
                    fp.setStyle(Paint.Style.FILL);
                    canvas.drawOval(r, fp);
                }
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1.5f, a.strokeWidth));
                canvas.drawOval(r, p);
                break;
            }
            case "redact": {
                RectF r = a.getBounds();
                Paint rp = new Paint(Paint.ANTI_ALIAS_FLAG);
                rp.setColor(Color.BLACK);
                rp.setStyle(Paint.Style.FILL);
                canvas.drawRect(r, rp);
                break;
            }
            case "text": {
                if (a.text == null || a.text.isEmpty()) break;
                Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
                tp.setColor(a.color);
                tp.setTextSize(Math.max(10f, a.fontSize));
                tp.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                String[] lines = a.text.split("\n");
                float ty = a.y + a.fontSize;
                for (String ln : lines) {
                    canvas.drawText(ln, a.x + 4f, ty, tp);
                    ty += a.fontSize * 1.28f;
                }
                break;
            }
            case "sign": {
                if (a.sigBitmap != null && !a.sigBitmap.isRecycled()) {
                    RectF r = a.getBounds();
                    Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                    canvas.drawBitmap(a.sigBitmap, null, r, bp);
                }
                break;
            }
        }

        if (selected) {
            RectF b = a.getBounds();
            Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
            sp.setColor(0xFF0381FE);
            sp.setStyle(Paint.Style.STROKE);
            sp.setStrokeWidth(2f);
            sp.setPathEffect(new DashPathEffect(new float[]{8f, 6f}, 0f));
            canvas.drawRect(b.left - 4f, b.top - 4f, b.right + 4f, b.bottom + 4f, sp);
            Paint cp = new Paint(Paint.ANTI_ALIAS_FLAG);
            cp.setColor(0xFF0381FE);
            cp.setStyle(Paint.Style.FILL);
            float[][] corners = new float[][]{
                    {b.left - 4f, b.top - 4f},
                    {b.right + 4f, b.top - 4f},
                    {b.left - 4f, b.bottom + 4f},
                    {b.right + 4f, b.bottom + 4f}
            };
            for (float[] c : corners) {
                canvas.drawCircle(c[0], c[1], 5f, cp);
            }
        }
    }

    public static Bitmap flattenOntoBitmap(Bitmap base, List<Annotation> anns) {
        if (base == null) return null;
        Bitmap out = base.copy(Bitmap.Config.ARGB_8888, true);
        if (anns != null && !anns.isEmpty()) {
            Canvas c = new Canvas(out);
            drawAnnotations(c, anns, null);
        }
        return out;
    }

    // ---------- PDF Page Descriptor ----------
    public static final class PdfPageItem {
        public String uid;
        public String kind; // "pdf", "img", "blank"
        public String srcPath;
        public int srcPageIdx;
        public Bitmap customBitmap;
        public float wPt = 595f;
        public float hPt = 842f;
        public int rot = 0; // 0, 90, 180, 270
        public final List<Annotation> anns = new ArrayList<>();
        public Bitmap cachedBitmap = null;
        public int cachedWidth = 0;

        public PdfPageItem(String uid, String kind, String srcPath, int srcPageIdx, float wPt, float hPt) {
            this.uid = uid;
            this.kind = kind;
            this.srcPath = srcPath;
            this.srcPageIdx = srcPageIdx;
            this.wPt = wPt;
            this.hPt = hPt;
        }

        public float displayWidthPt() {
            return (rot == 90 || rot == 270) ? hPt : wPt;
        }

        public float displayHeightPt() {
            return (rot == 90 || rot == 270) ? wPt : hPt;
        }

        public PdfPageItem copy(String newUid) {
            PdfPageItem p = new PdfPageItem(newUid, kind, srcPath, srcPageIdx, wPt, hPt);
            p.rot = rot;
            p.customBitmap = customBitmap != null ? customBitmap.copy(Bitmap.Config.ARGB_8888, true) : null;
            for (Annotation a : anns) p.anns.add(a.copy());
            return p;
        }

        public void invalidateCache() {
            if (cachedBitmap != null && cachedBitmap != customBitmap) {
                // Keep safe reference
                cachedBitmap = null;
            }
            cachedWidth = 0;
        }

        public Bitmap getOrRenderBaseBitmap(int targetWidthPx) {
            targetWidthPx = Math.max(1, targetWidthPx);
            if (cachedBitmap != null && !cachedBitmap.isRecycled() && cachedWidth == targetWidthPx) {
                return cachedBitmap;
            }
            // targetWidthPx is the rotated display width. Render the source at its
            // corresponding width so a landscape page still honours the selected dpi.
            int sourceWidth=(rot==90 || rot==270)?Math.max(1,Math.round(targetWidthPx*wPt/Math.max(1f,hPt))):targetWidthPx;
            Bitmap raw;
            if ("blank".equals(kind)) {
                int th = Math.max(1, Math.round(sourceWidth * (hPt / Math.max(1f, wPt))));
                raw = Bitmap.createBitmap(sourceWidth, th, Bitmap.Config.ARGB_8888);
                raw.eraseColor(Color.WHITE);
            } else if ("img".equals(kind) && customBitmap != null && !customBitmap.isRecycled()) {
                int th = Math.max(1, Math.round(sourceWidth * ((float) customBitmap.getHeight() / Math.max(1, customBitmap.getWidth()))));
                raw = Bitmap.createScaledBitmap(customBitmap, sourceWidth, th, true);
            } else {
                raw = PdfHost.renderBitmap(srcPath, srcPageIdx, sourceWidth);
                if (raw == null) return null;
            }
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                Bitmap rotated = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
                if (rotated != raw && raw != customBitmap) raw.recycle();
                raw = rotated;
            }
            cachedBitmap = raw;
            cachedWidth = targetWidthPx;
            return cachedBitmap;
        }

        public Bitmap renderFlattened(int targetWidthPx) {
            Bitmap base = getOrRenderBaseBitmap(targetWidthPx);
            if(base==null)throw new IllegalStateException("PDF를 요청한 해상도로 렌더링하지 못했습니다.");
            if (anns.isEmpty()) {
                return base.copy(Bitmap.Config.ARGB_8888, false);
            }
            Bitmap out = base.copy(Bitmap.Config.ARGB_8888, true);
            Canvas c = new Canvas(out);
            float scale = (float) out.getWidth() / Math.max(1f, displayWidthPt());
            c.save();
            c.scale(scale, scale);
            drawAnnotations(c, anns, null);
            c.restore();
            return out;
        }
    }
}
