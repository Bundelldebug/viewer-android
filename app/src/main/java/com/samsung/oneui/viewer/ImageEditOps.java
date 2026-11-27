package com.samsung.oneui.viewer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.ExifInterface;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;

public final class ImageEditOps {
    private ImageEditOps() {}

    /** Editing and saving always use original pixels; maxSide is only for thumbnails. */
    public static Bitmap decodeOriginal(String path) throws IOException {
        try {
            Bitmap bitmap=decodeAnyBitmapInternal(path,0);
            if(bitmap==null)throw new IOException("이 이미지 형식은 이 기기에서 열 수 없습니다.");
            return bitmap;
        }catch(OutOfMemoryError memory){throw new IOException("원본 해상도를 처리할 메모리가 부족합니다. 다른 앱을 닫고 다시 열어주세요.",memory);}
        catch(IOException e){throw e;}
        catch(Exception e){throw new IOException("이미지를 열 수 없습니다.",e);}
    }

    public static Bitmap decodeAnyBitmap(String path, int maxSide) {
        try {
            return decodeAnyBitmapInternal(path, maxSide);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap decodeAnyBitmapInternal(String path, int maxSide) throws Exception {
        if (path == null || path.isEmpty()) return null;
        File f = new File(path);
        if (!f.exists()) return null;
        String ext = Util.extOf(path);

        if (".svg".equals(ext)) {
            return renderSvgToBitmap(f, maxSide);
        }
        if (Codecs.CUSTOM.contains(ext) && !".ico".equals(ext)) {
            Bitmap b = Codecs.decode(path);
            if (b != null && maxSide > 0 && Math.max(b.getWidth(), b.getHeight()) > maxSide) {
                float sc = (float) maxSide / Math.max(b.getWidth(), b.getHeight());
                Bitmap sb = Bitmap.createScaledBitmap(b,
                        Math.max(1, Math.round(b.getWidth() * sc)),
                        Math.max(1, Math.round(b.getHeight() * sc)), true);
                if (sb != b) b.recycle();
                return sb;
            }
            return b;
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int sample = 1;
        while (maxSide > 0 && Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > maxSide * 2) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inScaled = false;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inMutable = true;
        Bitmap bmp = BitmapFactory.decodeFile(path, opts);
        if (bmp == null) {
            bmp = Codecs.decode(path);
        } else {
            bmp = applyExifOrientation(bmp, path);
        }
        if (bmp != null && !bmp.isMutable()) {
            Bitmap m = bmp.copy(Bitmap.Config.ARGB_8888, true);
            bmp.recycle();
            bmp = m;
        }
        if (bmp != null && maxSide > 0 && Math.max(bmp.getWidth(), bmp.getHeight()) > maxSide) {
            float sc = (float) maxSide / Math.max(bmp.getWidth(), bmp.getHeight());
            Bitmap sb = Bitmap.createScaledBitmap(bmp,
                    Math.max(1, Math.round(bmp.getWidth() * sc)),
                    Math.max(1, Math.round(bmp.getHeight() * sc)), true);
            if (sb != bmp) bmp.recycle();
            bmp = sb;
        }
        return bmp;
    }

    private static Bitmap applyExifOrientation(Bitmap bmp, String path) {
        if (bmp == null) return null;
        try {
            ExifInterface exif = new ExifInterface(path);
            int ori = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            Matrix m = new Matrix();
            switch (ori) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.setScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_180: m.setRotate(180); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.setScale(1, -1); break;
                case ExifInterface.ORIENTATION_TRANSPOSE: m.setRotate(90); m.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_90: m.setRotate(90); break;
                case ExifInterface.ORIENTATION_TRANSVERSE: m.setRotate(-90); m.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_270: m.setRotate(-90); break;
                default: return bmp;
            }
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (rotated != bmp) bmp.recycle();
            return rotated;
        } catch (Exception e) {
            return bmp;
        }
    }

    public static Bitmap rotate(Bitmap src, int dir) {
        if (src == null) return null;
        Matrix m = new Matrix();
        float deg = (Math.abs(dir) <= 4) ? (dir * 90f) : dir;
        m.postRotate(deg);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    public static Bitmap flip(Bitmap src, boolean vertical) {
        if (src == null) return null;
        Matrix m = new Matrix();
        if (vertical) m.postScale(1f, -1f);
        else m.postScale(-1f, 1f);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    public static Bitmap flip(Bitmap src, boolean h, boolean v) {
        if (src == null) return null;
        Matrix m = new Matrix();
        m.postScale(h ? -1f : 1f, v ? -1f : 1f);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    public static Bitmap crop(Bitmap src, RectF r) {
        if (src == null || r == null) return src;
        int x = Math.max(0, Math.min(src.getWidth() - 1, Math.round(r.left)));
        int y = Math.max(0, Math.min(src.getHeight() - 1, Math.round(r.top)));
        int w = Math.max(1, Math.min(src.getWidth() - x, Math.round(r.width())));
        int h = Math.max(1, Math.min(src.getHeight() - y, Math.round(r.height())));
        return Bitmap.createBitmap(src, x, y, w, h);
    }

    public static Bitmap crop(Bitmap src, int x, int y, int w, int h) {
        return crop(src, new RectF(x, y, x + w, y + h));
    }

    public static Bitmap resize(Bitmap src, int targetW, int targetH) {
        if (src == null) return null;
        int w = Math.max(1, targetW);
        int h = Math.max(1, targetH);
        Bitmap cur = src;
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
            if (cur != src) cur.recycle();
            cur = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(cur, w, h, true);
        if (cur != src && cur != out) cur.recycle();
        return out;
    }

    public static ColorMatrixColorFilter buildColorFilter(int b, int c, int s, int h) {
        ColorMatrix cm = new ColorMatrix();

        // 1. Saturation
        float sat = Math.max(0f, 1f + s / 100f);
        cm.setSaturation(sat);

        // 2. Hue rotation
        if (h != 0) {
            double rad = Math.toRadians(h);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            float lumR = 0.213f, lumG = 0.715f, lumB = 0.072f;
            ColorMatrix hm = new ColorMatrix(new float[]{
                    lumR + cos * (1 - lumR) + sin * (-lumR), lumG + cos * (-lumG) + sin * (-lumG), lumB + cos * (-lumB) + sin * (1 - lumB), 0, 0,
                    lumR + cos * (-lumR) + sin * (0.143f), lumG + cos * (1 - lumG) + sin * (0.140f), lumB + cos * (-lumB) + sin * (-0.283f), 0, 0,
                    lumR + cos * (-lumR) + sin * (-(1 - lumR)), lumG + cos * (-lumG) + sin * (lumG), lumB + cos * (1 - lumB) + sin * (lumB), 0, 0,
                    0, 0, 0, 1, 0
            });
            cm.postConcat(hm);
        }

        // 3. Contrast & Brightness
        float contrast = Math.max(0f, 1f + c / 100f);
        float translate = (1f - contrast) * 127.5f + (b * 2.55f);
        ColorMatrix bc = new ColorMatrix(new float[]{
                contrast, 0, 0, 0, translate,
                0, contrast, 0, 0, translate,
                0, 0, contrast, 0, translate,
                0, 0, 0, 1, 0
        });
        cm.postConcat(bc);
        return new ColorMatrixColorFilter(cm);
    }

    public static ColorMatrixColorFilter buildColorFilter(float exp, float con, float sat, float warm, boolean sepia, boolean invert) {
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(Math.max(0f, 1f + sat / 100f));
        if (sepia) {
            ColorMatrix sep = new ColorMatrix(new float[]{
                    0.393f, 0.769f, 0.189f, 0, 0,
                    0.349f, 0.686f, 0.168f, 0, 0,
                    0.272f, 0.534f, 0.131f, 0, 0,
                    0, 0, 0, 1, 0
            });
            cm.postConcat(sep);
        }
        float contrast = Math.max(0.1f, 1f + con / 100f);
        float translate = (1f - contrast) * 127.5f + (exp * 2.2f);
        float wDelta = warm * 0.4f;
        ColorMatrix bc = new ColorMatrix(new float[]{
                contrast, 0, 0, 0, translate + wDelta,
                0, contrast, 0, 0, translate,
                0, 0, contrast, 0, translate - wDelta,
                0, 0, 0, 1, 0
        });
        cm.postConcat(bc);
        if (invert) {
            ColorMatrix inv = new ColorMatrix(new float[]{
                    -1, 0, 0, 0, 255,
                    0, -1, 0, 0, 255,
                    0, 0, -1, 0, 255,
                    0, 0, 0, 1, 0
            });
            cm.postConcat(inv);
        }
        return new ColorMatrixColorFilter(cm);
    }

    public static Bitmap applyAdjust(Bitmap src, float exp, float con, float sat, float warm, float sharp, boolean sepia, boolean invert) {
        if (src == null) return null;
        Bitmap base = applyAdjust(src, Math.round(exp), Math.round(con), Math.round(sat), 0, Math.round(warm), Math.round(sharp));
        if (!sepia && !invert) return base;
        Bitmap out = Bitmap.createBitmap(base.getWidth(), base.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColorFilter(buildColorFilter(0f, 0f, 0f, 0f, sepia, invert));
        c.drawBitmap(base, 0, 0, p);
        if (out != base) base.recycle();
        return out;
    }

    public static Bitmap applyAdjust(Bitmap src, int b, int c, int s, int h, int w, int sh) {
        if (src == null) return null;
        int width = src.getWidth();
        int height = src.getHeight();
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(buildColorFilter(b, c, s, h));
        canvas.drawBitmap(src, 0, 0, p);

        if (w != 0 || sh != 0) {
            int[] pixels = new int[width * height];
            out.getPixels(pixels, 0, width, 0, 0, width, height);

            if (w != 0) {
                int delta = Math.round(w * 0.45f);
                for (int i = 0; i < pixels.length; i++) {
                    int px = pixels[i];
                    int a = (px >>> 24) & 0xFF;
                    int r = clampByte(((px >> 16) & 0xFF) + delta);
                    int g = (px >> 8) & 0xFF;
                    int bl = clampByte((px & 0xFF) - delta);
                    pixels[i] = (a << 24) | (r << 16) | (g << 8) | bl;
                }
            }

            if (sh != 0) {
                float amount = sh / 60f;
                int[] srcPx = pixels.clone();
                for (int yy = 1; yy < height - 1; yy++) {
                    int rowOff = yy * width;
                    for (int xx = 1; xx < width - 1; xx++) {
                        int idx = rowOff + xx;
                        int center = srcPx[idx];
                        int left = srcPx[idx - 1];
                        int right = srcPx[idx + 1];
                        int up = srcPx[idx - width];
                        int down = srcPx[idx + width];

                        int a = (center >>> 24) & 0xFF;
                        int cr = (center >> 16) & 0xFF, cg = (center >> 8) & 0xFF, cb = center & 0xFF;
                        int nr = ((left >> 16) & 0xFF) + ((right >> 16) & 0xFF) + ((up >> 16) & 0xFF) + ((down >> 16) & 0xFF);
                        int ng = ((left >> 8) & 0xFF) + ((right >> 8) & 0xFF) + ((up >> 8) & 0xFF) + ((down >> 8) & 0xFF);
                        int nb = (left & 0xFF) + (right & 0xFF) + (up & 0xFF) + (down & 0xFF);

                        int r = clampByte(Math.round(cr * (1f + 4f * amount) - amount * nr));
                        int g = clampByte(Math.round(cg * (1f + 4f * amount) - amount * ng));
                        int bl = clampByte(Math.round(cb * (1f + 4f * amount) - amount * nb));
                        pixels[idx] = (a << 24) | (r << 16) | (g << 8) | bl;
                    }
                }
            }
            out.setPixels(pixels, 0, width, 0, 0, width, height);
        }
        return out;
    }

    public static Bitmap autoLevels(Bitmap src) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);

        int[] histR = new int[256];
        int[] histG = new int[256];
        int[] histB = new int[256];
        int total = 0;

        for (int px : pixels) {
            if (((px >>> 24) & 0xFF) < 8) continue;
            histR[(px >> 16) & 0xFF]++;
            histG[(px >> 8) & 0xFF]++;
            histB[px & 0xFF]++;
            total++;
        }

        int cut = Math.max(1, (int) (total * 0.005f));
        int[] lo = new int[3], hi = new int[3];
        int[][] hists = new int[][]{histR, histG, histB};
        for (int ch = 0; ch < 3; ch++) {
            int sum = 0, l = 0, r = 255;
            for (; l < 255; l++) {
                sum += hists[ch][l];
                if (sum > cut) break;
            }
            sum = 0;
            for (; r > 0; r--) {
                sum += hists[ch][r];
                if (sum > cut) break;
            }
            lo[ch] = l;
            hi[ch] = Math.max(l + 8, r);
        }

        int spanR = hi[0] - lo[0], spanG = hi[1] - lo[1], spanB = hi[2] - lo[2];
        for (int i = 0; i < pixels.length; i++) {
            int px = pixels[i];
            int a = (px >>> 24) & 0xFF;
            int r = clampByte((((px >> 16) & 0xFF) - lo[0]) * 255 / spanR);
            int g = clampByte((((px >> 8) & 0xFF) - lo[1]) * 255 / spanG);
            int b = clampByte(((px & 0xFF) - lo[2]) * 255 / spanB);
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        return out;
    }

    public static Bitmap floodAlpha(Bitmap src, int sx, int sy, int tol, boolean global) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        sx = Math.max(0, Math.min(w - 1, sx));
        sy = Math.max(0, Math.min(h - 1, sy));

        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);

        int seed = pixels[sy * w + sx];
        int r0 = (seed >> 16) & 0xFF;
        int g0 = (seed >> 8) & 0xFF;
        int b0 = seed & 0xFF;
        int threshSq = (int) (tol * tol * 3L * 255L * 255L / 10000L);

        if (global) {
            for (int i = 0; i < pixels.length; i++) {
                int px = pixels[i];
                if (((px >>> 24) & 0xFF) == 0) continue;
                int dr = ((px >> 16) & 0xFF) - r0;
                int dg = ((px >> 8) & 0xFF) - g0;
                int db = (px & 0xFF) - b0;
                if (dr * dr + dg * dg + db * db <= threshSq) {
                    pixels[i] = 0;
                }
            }
        } else {
            byte[] seen = new byte[w * h];
            int[] stack = new int[w * h];
            int sp = 0;
            stack[sp++] = sy * w + sx;
            seen[sy * w + sx] = 1;

            while (sp > 0) {
                int idx = stack[--sp];
                int px = pixels[idx];
                if (((px >>> 24) & 0xFF) == 0) continue;
                int dr = ((px >> 16) & 0xFF) - r0;
                int dg = ((px >> 8) & 0xFF) - g0;
                int db = (px & 0xFF) - b0;
                if (dr * dr + dg * dg + db * db > threshSq) continue;

                pixels[idx] = 0;
                int x = idx % w;
                int y = idx / w;
                if (x + 1 < w && seen[idx + 1] == 0) { seen[idx + 1] = 1; stack[sp++] = idx + 1; }
                if (x - 1 >= 0 && seen[idx - 1] == 0) { seen[idx - 1] = 1; stack[sp++] = idx - 1; }
                if (y + 1 < h && seen[idx + w] == 0) { seen[idx + w] = 1; stack[sp++] = idx + w; }
                if (y - 1 >= 0 && seen[idx - w] == 0) { seen[idx - w] = 1; stack[sp++] = idx - w; }
            }
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        return out;
    }

    private static int clampByte(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    // Pure Java SVG renderer (supports viewBox, width/height, path, rect, circle, ellipse, line, polygon, polyline, text, g)
    public static Bitmap renderSvgToBitmap(File svgFile, int targetMaxSide) {
        try (FileInputStream fis = new FileInputStream(svgFile)) {
            DocumentBuilderFactory dbf = Docs.newSecureDocumentBuilderFactory();
            Document doc = dbf.newDocumentBuilder().parse(fis);
            Element root = doc.getDocumentElement();

            float vbX = 0, vbY = 0, vbW = 512, vbH = 512;
            String viewBox = root.getAttribute("viewBox");
            if (viewBox != null && !viewBox.trim().isEmpty()) {
                String[] parts = viewBox.trim().split("[\\s,]+");
                if (parts.length >= 4) {
                    vbX = parseDim(parts[0], 0);
                    vbY = parseDim(parts[1], 0);
                    vbW = Math.max(1f, parseDim(parts[2], 512));
                    vbH = Math.max(1f, parseDim(parts[3], 512));
                }
            } else {
                vbW = Math.max(1f, parseDim(root.getAttribute("width"), 512));
                vbH = Math.max(1f, parseDim(root.getAttribute("height"), 512));
            }

            float scale = targetMaxSide>0 ? (float) targetMaxSide / Math.max(vbW, vbH) : 1f;
            int outW = Math.max(1, Math.round(vbW * scale));
            int outH = Math.max(1, Math.round(vbH * scale));
            Bitmap bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.scale(scale, scale);
            canvas.translate(-vbX, -vbY);

            drawSvgElement(canvas, root, Color.BLACK, Color.TRANSPARENT, 1f);
            return bmp;
        } catch (Exception e) {
            Bitmap fallback = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(fallback);
            c.drawColor(Color.WHITE);
            return fallback;
        }
    }

    private static void drawSvgElement(Canvas canvas, Element el, int inheritedFill, int inheritedStroke, float inheritedStrokeW) {
        String tag = el.getLocalName() != null ? el.getLocalName() : el.getTagName();
        int fill = parseSvgColor(el.getAttribute("fill"), inheritedFill);
        int stroke = parseSvgColor(el.getAttribute("stroke"), inheritedStroke);
        float strokeW = parseDim(el.getAttribute("stroke-width"), inheritedStrokeW);

        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(fill);

        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeWidth(strokeW);
        strokePaint.setColor(stroke);

        if ("path".equalsIgnoreCase(tag)) {
            Path p = VectorIcons.parseSvgPath(el.getAttribute("d"));
            if (fill != Color.TRANSPARENT) canvas.drawPath(p, fillPaint);
            if (stroke != Color.TRANSPARENT) canvas.drawPath(p, strokePaint);
        } else if ("rect".equalsIgnoreCase(tag)) {
            float x = parseDim(el.getAttribute("x"), 0);
            float y = parseDim(el.getAttribute("y"), 0);
            float w = parseDim(el.getAttribute("width"), 0);
            float h = parseDim(el.getAttribute("height"), 0);
            float rx = parseDim(el.getAttribute("rx"), 0);
            RectF rf = new RectF(x, y, x + w, y + h);
            if (fill != Color.TRANSPARENT) canvas.drawRoundRect(rf, rx, rx, fillPaint);
            if (stroke != Color.TRANSPARENT) canvas.drawRoundRect(rf, rx, rx, strokePaint);
        } else if ("circle".equalsIgnoreCase(tag)) {
            float cx = parseDim(el.getAttribute("cx"), 0);
            float cy = parseDim(el.getAttribute("cy"), 0);
            float r = parseDim(el.getAttribute("r"), 0);
            if (fill != Color.TRANSPARENT) canvas.drawCircle(cx, cy, r, fillPaint);
            if (stroke != Color.TRANSPARENT) canvas.drawCircle(cx, cy, r, strokePaint);
        } else if ("ellipse".equalsIgnoreCase(tag)) {
            float cx = parseDim(el.getAttribute("cx"), 0);
            float cy = parseDim(el.getAttribute("cy"), 0);
            float rx = parseDim(el.getAttribute("rx"), 0);
            float ry = parseDim(el.getAttribute("ry"), 0);
            RectF rf = new RectF(cx - rx, cy - ry, cx + rx, cy + ry);
            if (fill != Color.TRANSPARENT) canvas.drawOval(rf, fillPaint);
            if (stroke != Color.TRANSPARENT) canvas.drawOval(rf, strokePaint);
        } else if ("line".equalsIgnoreCase(tag)) {
            float x1 = parseDim(el.getAttribute("x1"), 0);
            float y1 = parseDim(el.getAttribute("y1"), 0);
            float x2 = parseDim(el.getAttribute("x2"), 0);
            float y2 = parseDim(el.getAttribute("y2"), 0);
            if (stroke != Color.TRANSPARENT) canvas.drawLine(x1, y1, x2, y2, strokePaint);
        } else if ("text".equalsIgnoreCase(tag)) {
            float x = parseDim(el.getAttribute("x"), 0);
            float y = parseDim(el.getAttribute("y"), 20);
            float fs = parseDim(el.getAttribute("font-size"), 16);
            fillPaint.setTextSize(fs);
            String txt = el.getTextContent();
            if (txt != null && !txt.isEmpty() && fill != Color.TRANSPARENT) {
                canvas.drawText(txt.trim(), x, y, fillPaint);
            }
        }

        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element) {
                drawSvgElement(canvas, (Element) n, fill, stroke, strokeW);
            }
        }
    }

    private static float parseDim(String s, float def) {
        if (s == null || s.trim().isEmpty()) return def;
        s = s.trim().replaceAll("[a-zA-Z%]+$", "");
        try {
            return Float.parseFloat(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static int parseSvgColor(String s, int def) {
        if (s == null || s.trim().isEmpty()) return def;
        s = s.trim().toLowerCase(Locale.ROOT);
        if ("none".equals(s) || "transparent".equals(s)) return Color.TRANSPARENT;
        if ("currentcolor".equals(s)) return def;
        if (s.startsWith("#") && s.length() == 4) {
            char r = s.charAt(1), g = s.charAt(2), b = s.charAt(3);
            s = "#" + r + r + g + g + b + b;
        }
        try {
            return Color.parseColor(s);
        } catch (Exception e) {
            return def;
        }
    }
}
