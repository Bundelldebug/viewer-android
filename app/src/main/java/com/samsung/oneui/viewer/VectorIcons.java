package com.samsung.oneui.viewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;

import java.util.HashMap;
import java.util.Map;

public final class VectorIcons {
    private VectorIcons() {}

    private static final Map<String, String> PATHS = new HashMap<>();

    static {
        PATHS.put("sidebar", "M5 4h14a2 2 0 012 2v12a2 2 0 01-2 2H5a2 2 0 01-2-2V6a2 2 0 012-2zM9 4v16");
        PATHS.put("menu", "M4 7h16M4 12h16M4 17h16");
        PATHS.put("open", "M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2z");
        PATHS.put("save", "M5 4h11l3 3v13H5zM8 4v5h7V4M8 20v-6h8v6");
        PATHS.put("zoomIn", "M11 4a7 7 0 100 14 7 7 0 000-14zM16.5 16.5L21 21M8 11h6M11 8v6");
        PATHS.put("zoomOut", "M11 4a7 7 0 100 14 7 7 0 000-14zM16.5 16.5L21 21M8 11h6");
        PATHS.put("fit", "M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5");
        PATHS.put("rotL", "M4 12a8 8 0 108-8H7M7 1L4 4l3 3");
        PATHS.put("rotR", "M20 12a8 8 0 11-8-8h5M17 1l3 3-3 3");
        PATHS.put("flipH", "M12 3v18M8 7L3 17h5zM16 7l5 10h-5z");
        PATHS.put("select", "M5 3l14 8-6 2 4 7-3 1.5-4-7-5 4z");
        PATHS.put("markup", "M4 20l1-4L16 5l3 3L8 19zM14 7l3 3");
        PATHS.put("crop", "M7 3v14h14M3 7h14v14");
        PATHS.put("adjust", "M4 7h9M17 7h3M4 17h3M11 17h9M15 5v4M9 15v4");
        PATHS.put("resize", "M14 4h6v6M20 4l-7 7M10 20H4v-6M4 20l7-7");
        PATHS.put("alpha", "M4 4h16v16H4zM4 12h8V4M12 20v-8h8");
        PATHS.put("up", "M6 15l6-6 6 6");
        PATHS.put("down", "M6 9l6 6 6-6");
        PATHS.put("left", "M15 6l-6 6 6 6");
        PATHS.put("right", "M9 6l6 6-6 6");
        PATHS.put("layoutCont", "M6 3h12v8H6zM6 13h12v8H6z");
        PATHS.put("layoutSingle", "M7 3h10v18H7z");
        PATHS.put("layoutTwo", "M3 5h8v14H3zM13 5h8v14h-8z");
        PATHS.put("search", "M11 4a7 7 0 100 14 7 7 0 000-14zM16.5 16.5L21 21");
        PATHS.put("info", "M12 3a9 9 0 100 18 9 9 0 000-18zM12 11v6M12 7.5v.5");
        PATHS.put("play", "M7 4l13 8-13 8z");
        PATHS.put("pause", "M7 5v14M17 5v14");
        PATHS.put("stop", "M6 6h12v12H6z");
        PATHS.put("full", "M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5");
        PATHS.put("pen", "M4 20c3-1 4-5 7-5s2 3 4 3 3-2 5-6");
        PATHS.put("hl", "M6 15l7-9 5 4-7 9H6zM4 20h8");
        PATHS.put("line", "M5 19L19 5");
        PATHS.put("arrow", "M5 19L19 5M10 5h9v9");
        PATHS.put("rect", "M4 6h16v12H4z");
        PATHS.put("ellipse", "M12 5c5 0 9 3 9 7s-4 7-9 7-9-3-9-7 4-7 9-7z");
        PATHS.put("text", "M5 6V4h14v2M12 4v16M9 20h6");
        PATHS.put("redact", "M3 8h18v8H3z");
        PATHS.put("sign", "M3 17c3-6 5-9 6-9s-3 9-1 9 4-5 5-5 0 4 2 4 3-2 5-3M3 21h18");
        PATHS.put("note", "M5 4h14v12l-5 5H5zM14 21v-5h5");
        PATHS.put("undo", "M9 7L4 12l5 5M4 12h10a6 6 0 010 12");
        PATHS.put("redo", "M15 7l5 5-5 5M20 12H10a6 6 0 000 12");
        PATHS.put("trash", "M5 7h14M10 7V4h4v3M7 7l1 13h8l1-13M10 11v6M14 11v6");
        PATHS.put("copy", "M9 9h11v11H9zM5 15V4h11");
        PATHS.put("paste", "M8 4h8v3H8zM6 5h12v16H6z");
        PATHS.put("check", "M5 12l5 5 9-10");
        PATHS.put("close", "M6 6l12 12M18 6L6 18");
        PATHS.put("plus", "M12 5v14M5 12h14");
        PATHS.put("minus", "M5 12h14");
        PATHS.put("print", "M7 9V3h10v6M7 17H4v-7h16v7h-3M7 14h10v7H7z");
        PATHS.put("rename", "M4 20h4L19 9l-4-4L4 16zM14 6l4 4");
        PATHS.put("reveal", "M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2zM12 10v5M9.5 12.5L12 10l2.5 2.5");
        PATHS.put("export", "M12 3v12M7 8l5-5 5 5M5 14v6h14v-6");
        PATHS.put("bookmark", "M7 3h10v18l-5-4-5 4z");
        PATHS.put("hand", "M8 12V6a1.5 1.5 0 013 0v5M11 11V4.5a1.5 1.5 0 013 0V11M14 11V6a1.5 1.5 0 013 0v7c0 4-2 7-6 7s-5-2-7-5l-1-2a1.5 1.5 0 012-1.5l2 2");
        PATHS.put("edit", "M4 20h4.5L20 8.5 15.5 4 4 15.5zM13 6.5l4.5 4.5");
        PATHS.put("pageAdd", "M6 3h9l4 4v14H6zM12 11v6M9 14h6");
        PATHS.put("image", "M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4M9 9.5v.5");
        PATHS.put("gear", "M10.36 4.89 L10.73 2.38 L13.27 2.38 L13.64 4.89 A7.3 7.3 0 0 1 15.87 5.81 L17.9 4.3 L19.7 6.1 L18.19 8.13 A7.3 7.3 0 0 1 19.11 10.36 L21.62 10.73 L21.62 13.27 L19.11 13.64 A7.3 7.3 0 0 1 18.19 15.87 L19.7 17.9 L17.9 19.7 L15.87 18.19 A7.3 7.3 0 0 1 13.64 19.11 L13.27 21.62 L10.73 21.62 L10.36 19.11 A7.3 7.3 0 0 1 8.13 18.19 L6.1 19.7 L4.3 17.9 L5.81 15.87 A7.3 7.3 0 0 1 4.89 13.64 L2.38 13.27 L2.38 10.73 L4.89 10.36 A7.3 7.3 0 0 1 5.81 8.13 L4.3 6.1 L6.1 4.3 L8.13 5.81 A7.3 7.3 0 0 1 10.36 4.89Z M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z");
        PATHS.put("camera", "M4 8h4l2-3h4l2 3h4v11H4zM12 11a3.5 3.5 0 100 7 3.5 3.5 0 000-7z");
        PATHS.put("merge", "M5 4h8v7H5zM11 13h8v7h-8z");
        PATHS.put("share", "M18 8a3 3 0 100-6 3 3 0 000 6zM6 15a3 3 0 100-6 3 3 0 000 6zM18 22a3 3 0 100-6 3 3 0 000 6zM8.6 13.5l6.8 4M15.4 6.5l-6.8 4");
        PATHS.put("folder", PATHS.get("open"));
        PATHS.put("prev", PATHS.get("left"));
        PATHS.put("next", PATHS.get("right"));
        PATHS.put("more", PATHS.get("menu"));
        PATHS.put("star", "M12 3l2.8 5.7 6.2.9-4.5 4.4 1.1 6.2L12 17.3 6.4 20.2l1.1-6.2L3 9.6l6.2-.9z");
        PATHS.put("sliders", PATHS.get("adjust"));
        PATHS.put("wand", PATHS.get("alpha"));
        PATHS.put("flipV", "M3 12h18M7 8l10-5v5zM7 16l10 5v-5z");
        PATHS.put("grid", PATHS.get("alpha"));
        PATHS.put("contPage", PATHS.get("layoutCont"));
        PATHS.put("singlePage", PATHS.get("layoutSingle"));
        PATHS.put("twoPage", PATHS.get("layoutTwo"));
        PATHS.put("duplicate", PATHS.get("copy"));
        PATHS.put("pdf", "M6 3h9l4 4v14H6zM9 13h6M9 17h4");
        PATHS.put("doc", "M6 3h9l4 4v14H6zM9 9h6M9 13h6M9 17h4");
    }

    public static Drawable create(Context ctx, String name, float sizeDp, int color) {
        return drawable(ctx, name, color, sizeDp);
    }

    public static Drawable drawable(Context ctx, String name, int color, float sizeDp) {
        final int px = Math.max(8, Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, sizeDp, ctx.getResources().getDisplayMetrics())));
        final Path basePath = parseSvgPath(PATHS.getOrDefault(name, PATHS.get("image")));
        final float strokePx = Math.max(1.5f, px * (1.85f / 24f));

        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path scaled = new Path();
            private final Matrix mat = new Matrix();

            {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeWidth(strokePx);
                paint.setColor(color);
            }

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                float w = b.width() > 0 ? b.width() : px;
                float h = b.height() > 0 ? b.height() : px;
                mat.reset();
                mat.postScale(w / 24f, h / 24f);
                mat.postTranslate(b.left, b.top);
                scaled.reset();
                basePath.transform(mat, scaled);
                canvas.drawPath(scaled, paint);
            }

            @Override
            public int getIntrinsicWidth() {
                return px;
            }

            @Override
            public int getIntrinsicHeight() {
                return px;
            }

            @Override
            public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(ColorFilter colorFilter) {
                paint.setColorFilter(colorFilter);
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    // Complete SVG path parser (M, L, H, V, C, S, Q, T, A, Z, both absolute & relative)
    public static Path parseSvgPath(String d) {
        Path p = new Path();
        if (d == null || d.isEmpty()) return p;
        int i = 0, n = d.length();
        char cmd = 0;
        float cx = 0, cy = 0, sx = 0, sy = 0, ctrlX = 0, ctrlY = 0;
        float[] numOut = new float[1];

        while (i < n) {
            while (i < n && (Character.isWhitespace(d.charAt(i)) || d.charAt(i) == ',')) i++;
            if (i >= n) break;
            char c = d.charAt(i);
            if (Character.isLetter(c)) {
                cmd = c;
                i++;
            }
            boolean rel = Character.isLowerCase(cmd);
            char u = Character.toUpperCase(cmd);

            if (u == 'Z') {
                p.close();
                cx = sx;
                cy = sy;
                ctrlX = cx;
                ctrlY = cy;
                continue;
            }
            if (u == 'M') {
                i = readFloat(d, i, numOut); float x = numOut[0] + (rel ? cx : 0);
                i = readFloat(d, i, numOut); float y = numOut[0] + (rel ? cy : 0);
                p.moveTo(x, y);
                cx = sx = ctrlX = x;
                cy = sy = ctrlY = y;
                cmd = rel ? 'l' : 'L';
            } else if (u == 'L') {
                i = readFloat(d, i, numOut); float x = numOut[0] + (rel ? cx : 0);
                i = readFloat(d, i, numOut); float y = numOut[0] + (rel ? cy : 0);
                p.lineTo(x, y);
                cx = ctrlX = x;
                cy = ctrlY = y;
            } else if (u == 'H') {
                i = readFloat(d, i, numOut); float x = numOut[0] + (rel ? cx : 0);
                p.lineTo(x, cy);
                cx = ctrlX = x;
                ctrlY = cy;
            } else if (u == 'V') {
                i = readFloat(d, i, numOut); float y = numOut[0] + (rel ? cy : 0);
                p.lineTo(cx, y);
                cy = ctrlY = y;
                ctrlX = cx;
            } else if (u == 'C') {
                i = readFloat(d, i, numOut); float x1 = numOut[0] + (rel ? cx : 0);
                i = readFloat(d, i, numOut); float y1 = numOut[0] + (rel ? cy : 0);
                i = readFloat(d, i, numOut); float x2 = numOut[0] + (rel ? cx : 0);
                i = readFloat(d, i, numOut); float y2 = numOut[0] + (rel ? cy : 0);
                i = readFloat(d, i, numOut); float x = numOut[0] + (rel ? cx : 0);
                i = readFloat(d, i, numOut); float y = numOut[0] + (rel ? cy : 0);
                p.cubicTo(x1, y1, x2, y2, x, y);
                ctrlX = x2; ctrlY = y2;
                cx = x; cy = y;
            } else if (u == 'S') {
                float x1 = 2 * cx - ctrlX;
                float y1 = 2 * cy - ctrlY;
                i = readFloat(d, i, numOut); float x2 = numOut[0] + (rel ? cx : 0);
                i = readFloat(d, i, numOut); float y2 = numOut[0] + (rel ? cy : 0);
                i = readFloat(d, i, numOut); float x = numOut[0] + (rel ? cx : 0);
                i = readFloat(d, i, numOut); float y = numOut[0] + (rel ? cy : 0);
                p.cubicTo(x1, y1, x2, y2, x, y);
                ctrlX = x2; ctrlY = y2;
                cx = x; cy = y;
            } else if (u == 'Q') {
                i = readFloat(d, i, numOut); float x1 = numOut[0] + (rel ? cx : 0);
                i = readFloat(d, i, numOut); float y1 = numOut[0] + (rel ? cy : 0);
                i = readFloat(d, i, numOut); float x = numOut[0] + (rel ? cx : 0);
                i = readFloat(d, i, numOut); float y = numOut[0] + (rel ? cy : 0);
                p.quadTo(x1, y1, x, y);
                ctrlX = x1; ctrlY = y1;
                cx = x; cy = y;
            } else if (u == 'A') {
                i = readFloat(d, i, numOut); float rx = Math.abs(numOut[0]);
                i = readFloat(d, i, numOut); float ry = Math.abs(numOut[0]);
                i = readFloat(d, i, numOut); float rot = numOut[0];
                i = readFlag(d, i, numOut); boolean largeArc = numOut[0] != 0;
                i = readFlag(d, i, numOut); boolean sweep = numOut[0] != 0;
                i = readFloat(d, i, numOut); float x = numOut[0] + (rel ? cx : 0);
                i = readFloat(d, i, numOut); float y = numOut[0] + (rel ? cy : 0);
                drawArc(p, cx, cy, x, y, rx, ry, rot, largeArc, sweep);
                cx = ctrlX = x;
                cy = ctrlY = y;
            } else {
                i++;
            }
        }
        return p;
    }

    private static int readFlag(String s, int i, float[] out) {
        int n = s.length();
        while (i < n && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
        if (i < n && (s.charAt(i) == '0' || s.charAt(i) == '1')) {
            out[0] = s.charAt(i) - '0';
            return i + 1;
        }
        return readFloat(s, i, out);
    }

    private static int readFloat(String s, int i, float[] out) {
        int n = s.length();
        while (i < n && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
        int start = i;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
        boolean hasDot = false;
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                i++;
            } else if (c == '.' && !hasDot) {
                hasDot = true;
                i++;
            } else if ((c == 'e' || c == 'E') && i > start) {
                i++;
                if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            } else {
                break;
            }
        }
        if (i <= start) {
            out[0] = 0f;
            return Math.min(n, start + 1);
        }
        try {
            out[0] = Float.parseFloat(s.substring(start, i));
        } catch (Exception e) {
            out[0] = 0f;
        }
        return i;
    }

    private static void drawArc(Path p, float x0, float y0, float x, float y,
                                float rx, float ry, float angle, boolean largeArcFlag, boolean sweepFlag) {
        if (rx == 0 || ry == 0) {
            p.lineTo(x, y);
            return;
        }
        if (x0 == x && y0 == y) return;
        double dx2 = (x0 - x) / 2.0;
        double dy2 = (y0 - y) / 2.0;
        double rad = Math.toRadians(angle % 360.0);
        double cosA = Math.cos(rad);
        double sinA = Math.sin(rad);
        double x1 = cosA * dx2 + sinA * dy2;
        double y1 = -sinA * dx2 + cosA * dy2;
        double rxSq = rx * rx;
        double rySq = ry * ry;
        double x1Sq = x1 * x1;
        double y1Sq = y1 * y1;
        double radiiCheck = x1Sq / rxSq + y1Sq / rySq;
        if (radiiCheck > 1) {
            rx = (float) (Math.sqrt(radiiCheck) * rx);
            ry = (float) (Math.sqrt(radiiCheck) * ry);
            rxSq = rx * rx;
            rySq = ry * ry;
        }
        double sign = (largeArcFlag == sweepFlag) ? -1 : 1;
        double sq = ((rxSq * rySq) - (rxSq * y1Sq) - (rySq * x1Sq)) / ((rxSq * y1Sq) + (rySq * x1Sq));
        sq = Math.max(0, sq);
        double coef = sign * Math.sqrt(sq);
        double cx1 = coef * ((rx * y1) / ry);
        double cy1 = coef * -((ry * x1) / rx);
        double sx2 = (x0 + x) / 2.0;
        double sy2 = (y0 + y) / 2.0;
        double cx = sx2 + (cosA * cx1 - sinA * cy1);
        double cy = sy2 + (sinA * cx1 + cosA * cy1);
        double ux = (x1 - cx1) / rx;
        double uy = (y1 - cy1) / ry;
        double vx = (-x1 - cx1) / rx;
        double vy = (-y1 - cy1) / ry;
        double n = Math.sqrt(ux * ux + uy * uy);
        double pp = ux;
        sign = (uy < 0) ? -1.0 : 1.0;
        double angleStart = Math.toDegrees(sign * Math.acos(Math.max(-1, Math.min(1, pp / n))));
        n = Math.sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy));
        pp = ux * vx + uy * vy;
        sign = (ux * vy - uy * vx < 0) ? -1.0 : 1.0;
        double angleExtent = Math.toDegrees(sign * Math.acos(Math.max(-1, Math.min(1, pp / n))));
        if (!sweepFlag && angleExtent > 0) angleExtent -= 360f;
        else if (sweepFlag && angleExtent < 0) angleExtent += 360f;
        angleExtent %= 360f;
        angleStart %= 360f;

        RectF oval = new RectF((float) (cx - rx), (float) (cy - ry), (float) (cx + rx), (float) (cy + ry));
        p.arcTo(oval, (float) angleStart, (float) angleExtent);
    }
}
