package com.samsung.oneui.viewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Hand-written 24x24 crisp vector icons matching Prism Music Android's icon system. */
final class Icons {
    private Icons() {}

    static final int HOME = 1, VIEW = 2, FILES = 3, SETTINGS = 4, SEARCH = 5,
            MORE = 6, BACK = 7, CLOSE = 8, CHEVRON = 9, CHECK = 10,
            SIDEBAR = 11, INFO = 12, CLOCK = 13, BOOKMARK = 14, BOOKMARK_FILL = 15,
            OPEN_FILE = 16, FOLDER = 17, PASTE = 18, CAPTURE = 19, CAMERA = 20,
            SKETCH = 21, SAVE = 22, EXPORT = 23, SHARE = 24, PRINT = 25,
            SLIDESHOW = 26, UNDO = 27, REDO = 28, ZOOM_IN = 29, ZOOM_OUT = 30,
            FIT = 31, ROTATE_L = 32, ROTATE_R = 33, FLIP_H = 34, FLIP_V = 35,
            CROP = 36, PEN = 37, HIGHLIGHT = 38, SHAPES = 39, TEXT_ADD = 40,
            SIGN = 41, ALPHA = 42, ADJUST = 43, RESIZE = 44, OCR = 45,
            PAGES = 46, APPEARANCE = 47, DOWNLOAD = 48, TRASH = 49, EDIT = 50,
            COPY = 51, PLAY = 52, PREV = 53, NEXT = 54, DOC = 55, MEDIA = 56, PDF = 57, SORT = 58;

    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final CornerPathEffect ROUND2 = new CornerPathEffect(2.0f);
    private static final CornerPathEffect ROUND1 = new CornerPathEffect(1.2f);
    private static final Path T = new Path();
    private static final RectF R = new RectF();

    static void draw(Canvas c, int id, float cx, float cy, float size, int color) {
        c.save();
        c.translate(cx - size / 2f, cy - size / 2f);
        c.scale(size / 24f, size / 24f);
        P.reset();
        P.setAntiAlias(true);
        P.setColor(color);
        P.setStrokeCap(Paint.Cap.ROUND);
        P.setStrokeJoin(Paint.Join.ROUND);
        render(c, id);
        c.restore();
    }

    private static void stroke(float w) { P.setStyle(Paint.Style.STROKE); P.setStrokeWidth(w); P.setPathEffect(null); }
    private static void fill() { P.setStyle(Paint.Style.FILL); P.setPathEffect(null); }

    private static void render(Canvas c, int id) {
        Path p = T;
        p.reset();
        switch (id) {
            case HOME: {
                fill();
                P.setPathEffect(ROUND1);
                p.moveTo(12f, 2.6f); p.lineTo(22.4f, 11.6f); p.lineTo(19.6f, 11.6f); p.lineTo(19.6f, 21f);
                p.lineTo(14.4f, 21f); p.lineTo(14.4f, 15.2f); p.lineTo(9.6f, 15.2f); p.lineTo(9.6f, 21f);
                p.lineTo(4.4f, 21f); p.lineTo(4.4f, 11.6f); p.lineTo(1.6f, 11.6f); p.close();
                c.drawPath(p, P);
                break;
            }
            case VIEW: {
                // Framed image / viewer stage icon (filled with crisp landscape cutout)
                stroke(2.0f);
                R.set(3f, 4f, 21f, 20f);
                c.drawRoundRect(R, 3.4f, 3.4f, P);
                fill();
                c.drawCircle(8.4f, 9.2f, 1.7f, P);
                P.setPathEffect(ROUND1);
                p.moveTo(5.2f, 17.6f); p.lineTo(10.5f, 12.2f); p.lineTo(13.6f, 15.2f); p.lineTo(16.2f, 12.4f); p.lineTo(19.0f, 17.6f); p.close();
                c.drawPath(p, P);
                break;
            }
            case FILES:
            case FOLDER: {
                fill();
                P.setPathEffect(ROUND1);
                p.moveTo(2.6f, 6.2f); p.lineTo(9.5f, 6.2f); p.lineTo(11.6f, 8.6f); p.lineTo(21.4f, 8.6f);
                p.lineTo(21.4f, 19.6f); p.lineTo(2.6f, 19.6f); p.close();
                c.drawPath(p, P);
                break;
            }
            case SETTINGS: {
                stroke(1.8f);
                int teeth = 12;
                for (int i = 0; i < teeth * 2; i++) {
                    double a = Math.PI * 2 * i / (teeth * 2) - Math.PI / 2;
                    float r = (i % 2 == 0) ? 10.2f : 8.6f;
                    double a1 = a - Math.PI / (teeth * 2) * 0.55, a2 = a + Math.PI / (teeth * 2) * 0.55;
                    float x1 = 12 + (float) Math.cos(a1) * r, y1 = 12 + (float) Math.sin(a1) * r;
                    float x2 = 12 + (float) Math.cos(a2) * r, y2 = 12 + (float) Math.sin(a2) * r;
                    if (i == 0) p.moveTo(x1, y1); else p.lineTo(x1, y1);
                    p.lineTo(x2, y2);
                }
                p.close();
                c.drawPath(p, P);
                c.drawCircle(12f, 12f, 3.2f, P);
                break;
            }
            case SEARCH: {
                stroke(2.5f);
                c.drawCircle(10.4f, 10.4f, 6.6f, P);
                P.setStrokeWidth(3f);
                c.drawLine(15.4f, 15.4f, 20.6f, 20.6f, P);
                break;
            }
            case MORE: {
                fill();
                c.drawCircle(4.6f, 12f, 1.9f, P);
                c.drawCircle(12f, 12f, 1.9f, P);
                c.drawCircle(19.4f, 12f, 1.9f, P);
                break;
            }
            case BACK: {
                stroke(2.4f);
                p.moveTo(15.2f, 4.6f); p.lineTo(7.8f, 12f); p.lineTo(15.2f, 19.4f);
                c.drawPath(p, P);
                break;
            }
            case CLOSE: {
                stroke(2.2f);
                c.drawLine(6f, 6f, 18f, 18f, P);
                c.drawLine(18f, 6f, 6f, 18f, P);
                break;
            }
            case CHEVRON: {
                stroke(2f);
                p.moveTo(9.2f, 5f); p.lineTo(16f, 12f); p.lineTo(9.2f, 19f);
                c.drawPath(p, P);
                break;
            }
            case CHECK: {
                stroke(2.2f);
                p.moveTo(4.4f, 12.6f); p.lineTo(9.4f, 17.6f); p.lineTo(19.6f, 6.6f);
                c.drawPath(p, P);
                break;
            }
            case SIDEBAR: {
                stroke(1.9f);
                R.set(2.8f, 4.6f, 21.2f, 19.4f);
                c.drawRoundRect(R, 3.6f, 3.6f, P);
                c.drawLine(9.2f, 5f, 9.2f, 19f, P);
                break;
            }
            case INFO: {
                stroke(1.9f);
                c.drawCircle(12f, 12f, 9.2f, P);
                P.setStrokeWidth(2.1f);
                c.drawLine(12f, 11f, 12f, 16.6f, P);
                fill();
                c.drawCircle(12f, 7.6f, 1.3f, P);
                break;
            }
            case CLOCK: {
                stroke(1.8f);
                c.drawCircle(12f, 12f, 9.2f, P);
                p.moveTo(12f, 6.8f); p.lineTo(12f, 12f); p.lineTo(15.6f, 14.2f);
                c.drawPath(p, P);
                break;
            }
            case BOOKMARK:
            case BOOKMARK_FILL: {
                if (id == BOOKMARK) stroke(1.9f); else fill();
                P.setPathEffect(ROUND1);
                p.moveTo(6f, 3.5f); p.lineTo(18f, 3.5f); p.lineTo(18f, 20.8f);
                p.lineTo(12f, 16.5f); p.lineTo(6f, 20.8f); p.close();
                c.drawPath(p, P);
                break;
            }
            case OPEN_FILE:
            case DOC: {
                stroke(1.9f);
                p.moveTo(6f, 3.2f); p.lineTo(14.2f, 3.2f); p.lineTo(18.6f, 7.6f);
                p.lineTo(18.6f, 20.8f); p.lineTo(6f, 20.8f); p.close();
                c.drawPath(p, P);
                p.reset();
                p.moveTo(13.8f, 3.4f); p.lineTo(13.8f, 8f); p.lineTo(18.4f, 8f);
                c.drawPath(p, P);
                c.drawLine(9f, 12.2f, 15.5f, 12.2f, P);
                c.drawLine(9f, 15.8f, 14f, 15.8f, P);
                break;
            }
            case PDF: {
                stroke(1.9f);
                R.set(4.5f, 3f, 19.5f, 21f);
                c.drawRoundRect(R, 2.5f, 2.5f, P);
                c.drawLine(8f, 9f, 16f, 9f, P);
                c.drawLine(8f, 13f, 16f, 13f, P);
                c.drawLine(8f, 17f, 12.5f, 17f, P);
                break;
            }
            case MEDIA:
            case PLAY: {
                fill();
                P.setPathEffect(ROUND2);
                p.moveTo(6.5f, 3.8f); p.lineTo(20.4f, 12f); p.lineTo(6.5f, 20.2f); p.close();
                c.drawPath(p, P);
                break;
            }
            case PASTE:
            case COPY: {
                stroke(1.9f);
                R.set(8.2f, 7.6f, 19.2f, 20.4f);
                c.drawRoundRect(R, 2.2f, 2.2f, P);
                p.moveTo(15.6f, 7.4f); p.lineTo(15.6f, 4.8f); p.lineTo(5.2f, 4.8f); p.lineTo(5.2f, 17.2f); p.lineTo(8f, 17.2f);
                c.drawPath(p, P);
                break;
            }
            case CAPTURE: {
                stroke(2.0f);
                p.moveTo(4f, 8.5f); p.lineTo(4f, 5f); p.lineTo(8.5f, 5f);
                p.moveTo(15.5f, 5f); p.lineTo(20f, 5f); p.lineTo(20f, 8.5f);
                p.moveTo(20f, 15.5f); p.lineTo(20f, 19f); p.lineTo(15.5f, 19f);
                p.moveTo(8.5f, 19f); p.lineTo(4f, 19f); p.lineTo(4f, 15.5f);
                c.drawPath(p, P);
                c.drawCircle(12f, 12f, 3.2f, P);
                break;
            }
            case CAMERA: {
                stroke(1.9f);
                p.moveTo(3.5f, 8f); p.lineTo(7.4f, 8f); p.lineTo(9.2f, 5.2f); p.lineTo(14.8f, 5.2f);
                p.lineTo(16.6f, 8f); p.lineTo(20.5f, 8f); p.lineTo(20.5f, 19f); p.lineTo(3.5f, 19f); p.close();
                c.drawPath(p, P);
                c.drawCircle(12f, 13.5f, 3.3f, P);
                break;
            }
            case SKETCH:
            case PEN:
            case EDIT: {
                stroke(1.9f);
                p.moveTo(4.2f, 19.8f); p.lineTo(5.6f, 14.4f); p.lineTo(15.6f, 4.4f);
                p.lineTo(19.6f, 8.4f); p.lineTo(9.6f, 18.4f); p.close();
                c.drawPath(p, P);
                c.drawLine(13.6f, 6.4f, 17.6f, 10.4f, P);
                break;
            }
            case HIGHLIGHT: {
                stroke(1.9f);
                p.moveTo(4f, 19.5f); p.lineTo(19.5f, 19.5f);
                c.drawPath(p, P);
                p.reset();
                p.moveTo(7.5f, 15.5f); p.lineTo(15.5f, 5.5f); p.lineTo(19f, 8.5f); p.lineTo(11f, 17f); p.lineTo(7.5f, 15.5f);
                c.drawPath(p, P);
                break;
            }
            case SHAPES: {
                stroke(1.9f);
                R.set(3.5f, 3.5f, 13f, 13f);
                c.drawRoundRect(R, 2f, 2f, P);
                c.drawCircle(15.6f, 15.6f, 4.8f, P);
                break;
            }
            case TEXT_ADD: {
                stroke(2.1f);
                c.drawLine(5f, 6f, 19f, 6f, P);
                c.drawLine(12f, 6f, 12f, 19.2f, P);
                c.drawLine(9f, 19.2f, 15f, 19.2f, P);
                break;
            }
            case SIGN: {
                stroke(1.9f);
                p.moveTo(3.5f, 16.5f);
                p.cubicTo(6f, 9f, 9f, 9f, 9.5f, 14.5f);
                p.cubicTo(10f, 18f, 13f, 11f, 15f, 13.5f);
                p.cubicTo(16.5f, 15.2f, 18.5f, 14.8f, 20.5f, 14f);
                c.drawPath(p, P);
                c.drawLine(4f, 19.5f, 20f, 19.5f, P);
                break;
            }
            case ALPHA: {
                stroke(1.9f);
                c.drawLine(4.5f, 19.5f, 15.5f, 8.5f, P);
                c.drawLine(15.5f, 4f, 15.5f, 6.2f, P);
                c.drawLine(19.8f, 8.5f, 17.6f, 8.5f, P);
                c.drawLine(18.5f, 5.5f, 17.2f, 6.8f, P);
                c.drawLine(12.5f, 5.5f, 13.8f, 6.8f, P);
                break;
            }
            case ADJUST: {
                stroke(1.9f);
                c.drawLine(4f, 7f, 20f, 7f, P);
                c.drawLine(4f, 12f, 20f, 12f, P);
                c.drawLine(4f, 17f, 20f, 17f, P);
                fill();
                c.drawCircle(9f, 7f, 2.3f, P);
                c.drawCircle(15.5f, 12f, 2.3f, P);
                c.drawCircle(11f, 17f, 2.3f, P);
                break;
            }
            case CROP: {
                stroke(2.0f);
                p.moveTo(7f, 3.5f); p.lineTo(7f, 17f); p.lineTo(20.5f, 17f);
                p.moveTo(3.5f, 7f); p.lineTo(17f, 7f); p.lineTo(17f, 20.5f);
                c.drawPath(p, P);
                break;
            }
            case RESIZE: {
                stroke(2.0f);
                c.drawLine(14f, 4.5f, 19.5f, 4.5f, P); c.drawLine(19.5f, 4.5f, 19.5f, 10f, P); c.drawLine(19.5f, 4.5f, 13.5f, 10.5f, P);
                c.drawLine(10f, 19.5f, 4.5f, 19.5f, P); c.drawLine(4.5f, 19.5f, 4.5f, 14f, P); c.drawLine(4.5f, 19.5f, 10.5f, 13.5f, P);
                break;
            }
            case SAVE: {
                stroke(1.9f);
                p.moveTo(5f, 4f); p.lineTo(16.2f, 4f); p.lineTo(20f, 7.8f); p.lineTo(20f, 19.6f); p.lineTo(5f, 19.6f); p.close();
                c.drawPath(p, P);
                R.set(8f, 4f, 15f, 9.5f); c.drawRect(R, P);
                R.set(8f, 13.5f, 17f, 19.6f); c.drawRect(R, P);
                break;
            }
            case EXPORT:
            case DOWNLOAD: {
                stroke(1.9f);
                c.drawCircle(12f, 12f, 9.2f, P);
                c.drawLine(12f, 6.8f, 12f, 16f, P);
                p.moveTo(8.4f, 12.6f); p.lineTo(12f, 16.2f); p.lineTo(15.6f, 12.6f);
                c.drawPath(p, P);
                break;
            }
            case SHARE: {
                stroke(1.9f);
                c.drawCircle(17.5f, 5.5f, 2.4f, P);
                c.drawCircle(6.5f, 12f, 2.4f, P);
                c.drawCircle(17.5f, 18.5f, 2.4f, P);
                c.drawLine(8.6f, 10.8f, 15.4f, 6.7f, P);
                c.drawLine(8.6f, 13.2f, 15.4f, 17.3f, P);
                break;
            }
            case PRINT: {
                stroke(1.9f);
                R.set(4f, 9f, 20f, 16.5f); c.drawRoundRect(R, 2f, 2f, P);
                p.moveTo(7f, 9f); p.lineTo(7f, 4.5f); p.lineTo(17f, 4.5f); p.lineTo(17f, 9f);
                p.moveTo(7f, 15f); p.lineTo(7f, 19.8f); p.lineTo(17f, 19.8f); p.lineTo(17f, 15f);
                c.drawPath(p, P);
                break;
            }
            case SLIDESHOW: {
                stroke(1.9f);
                R.set(3.2f, 4.8f, 20.8f, 16.6f); c.drawRoundRect(R, 2.4f, 2.4f, P);
                c.drawLine(8.5f, 20f, 15.5f, 20f, P);
                c.drawLine(12f, 16.6f, 12f, 20f, P);
                fill();
                p.moveTo(10.2f, 8.2f); p.lineTo(15.4f, 10.7f); p.lineTo(10.2f, 13.2f); p.close();
                c.drawPath(p, P);
                break;
            }
            case UNDO: {
                stroke(2.0f);
                p.moveTo(8.5f, 6.5f); p.lineTo(4.2f, 10.8f); p.lineTo(8.5f, 15f);
                c.drawPath(p, P);
                p.reset();
                p.moveTo(4.6f, 10.8f); p.lineTo(14.5f, 10.8f);
                p.cubicTo(18.2f, 10.8f, 19.8f, 13.2f, 19.8f, 15.6f);
                p.cubicTo(19.8f, 18f, 17.8f, 19.4f, 14.5f, 19.4f);
                c.drawPath(p, P);
                break;
            }
            case REDO: {
                c.scale(-1f, 1f, 12f, 12f);
                render(c, UNDO);
                break;
            }
            case ZOOM_IN: {
                stroke(2.0f);
                c.drawCircle(10.5f, 10.5f, 6.4f, P);
                c.drawLine(15.4f, 15.4f, 20.2f, 20.2f, P);
                c.drawLine(7.6f, 10.5f, 13.4f, 10.5f, P);
                c.drawLine(10.5f, 7.6f, 10.5f, 13.4f, P);
                break;
            }
            case ZOOM_OUT: {
                stroke(2.0f);
                c.drawCircle(10.5f, 10.5f, 6.4f, P);
                c.drawLine(15.4f, 15.4f, 20.2f, 20.2f, P);
                c.drawLine(7.6f, 10.5f, 13.4f, 10.5f, P);
                break;
            }
            case FIT: {
                stroke(2.0f);
                p.moveTo(4.5f, 9f); p.lineTo(4.5f, 4.5f); p.lineTo(9f, 4.5f);
                p.moveTo(15f, 4.5f); p.lineTo(19.5f, 4.5f); p.lineTo(19.5f, 9f);
                p.moveTo(19.5f, 15f); p.lineTo(19.5f, 19.5f); p.lineTo(15f, 19.5f);
                p.moveTo(9f, 19.5f); p.lineTo(4.5f, 19.5f); p.lineTo(4.5f, 15f);
                c.drawPath(p, P);
                break;
            }
            case ROTATE_R: {
                stroke(2.0f);
                R.set(4.8f, 4.8f, 19.2f, 19.2f);
                c.drawArc(R, -60f, 285f, false, P);
                p.moveTo(15.5f, 4.2f); p.lineTo(19.2f, 6.8f); p.lineTo(15.4f, 9.6f);
                c.drawPath(p, P);
                break;
            }
            case ROTATE_L: {
                c.scale(-1f, 1f, 12f, 12f);
                render(c, ROTATE_R);
                break;
            }
            case FLIP_H: {
                stroke(1.9f);
                c.drawLine(12f, 4f, 12f, 20f, P);
                p.moveTo(9f, 7f); p.lineTo(4f, 17f); p.lineTo(9f, 17f); p.close();
                p.moveTo(15f, 7f); p.lineTo(20f, 17f); p.lineTo(15f, 17f); p.close();
                c.drawPath(p, P);
                break;
            }
            case FLIP_V: {
                c.rotate(90f, 12f, 12f);
                render(c, FLIP_H);
                break;
            }
            case OCR: {
                stroke(1.9f);
                p.moveTo(4f, 8f); p.lineTo(4f, 4.5f); p.lineTo(8f, 4.5f);
                p.moveTo(16f, 4.5f); p.lineTo(20f, 4.5f); p.lineTo(20f, 8f);
                p.moveTo(20f, 16f); p.lineTo(20f, 19.5f); p.lineTo(16f, 19.5f);
                p.moveTo(8f, 19.5f); p.lineTo(4f, 19.5f); p.lineTo(4f, 16f);
                c.drawPath(p, P);
                c.drawLine(8f, 9.2f, 16f, 9.2f, P);
                c.drawLine(8f, 12.2f, 16f, 12.2f, P);
                c.drawLine(8f, 15.2f, 13f, 15.2f, P);
                break;
            }
            case PAGES: {
                stroke(1.9f);
                R.set(7.5f, 3.6f, 19.5f, 17.6f); c.drawRoundRect(R, 2f, 2f, P);
                p.moveTo(4.5f, 7.6f); p.lineTo(4.5f, 20.4f); p.lineTo(16f, 20.4f);
                c.drawPath(p, P);
                break;
            }
            case APPEARANCE: {
                stroke(1.8f);
                p.moveTo(14.5f, 3.4f); p.lineTo(20.6f, 9.5f); p.lineTo(11.5f, 18.6f); p.lineTo(5.4f, 12.5f); p.close();
                c.drawPath(p, P);
                fill();
                p.reset();
                p.moveTo(5.6f, 15.2f); p.lineTo(8.8f, 18.4f); p.cubicTo(7.6f, 20.4f, 5f, 21.2f, 3f, 21f);
                p.cubicTo(3.6f, 19.4f, 3.4f, 16.8f, 5.6f, 15.2f); p.close();
                c.drawPath(p, P);
                break;
            }
            case TRASH: {
                stroke(1.9f);
                c.drawLine(4.5f, 6.8f, 19.5f, 6.8f, P);
                p.moveTo(9f, 6.8f); p.lineTo(9.8f, 4.2f); p.lineTo(14.2f, 4.2f); p.lineTo(15f, 6.8f);
                p.moveTo(6.4f, 6.8f); p.lineTo(7.4f, 19.6f); p.lineTo(16.6f, 19.6f); p.lineTo(17.6f, 6.8f);
                c.drawPath(p, P);
                c.drawLine(10.2f, 10.2f, 10.2f, 16.2f, P);
                c.drawLine(13.8f, 10.2f, 13.8f, 16.2f, P);
                break;
            }
            case PREV: {
                stroke(2.2f);
                p.moveTo(15f, 5.5f); p.lineTo(8.5f, 12f); p.lineTo(15f, 18.5f);
                c.drawPath(p, P);
                break;
            }
            case NEXT: {
                stroke(2.2f);
                p.moveTo(9f, 5.5f); p.lineTo(15.5f, 12f); p.lineTo(9f, 18.5f);
                c.drawPath(p, P);
                break;
            }
            case SORT: {
                stroke(2.0f);
                c.drawLine(4.5f, 7f, 19.5f, 7f, P);
                c.drawLine(7f, 12f, 17f, 12f, P);
                c.drawLine(9.5f, 17f, 14.5f, 17f, P);
                break;
            }
            default:
                break;
        }
    }

    static final class IconView extends View {
        int icon, color;
        float sizeDp;
        private int pendingIcon = -1;
        private float swap = 1f;

        IconView(Context c, int icon, float sizeDp, int color) {
            super(c);
            this.icon = icon;
            this.sizeDp = sizeDp;
            this.color = color;
        }

        void set(int newIcon, boolean animate) {
            if (newIcon == icon && pendingIcon < 0) return;
            if (!animate || !isAttachedToWindow()) { icon = newIcon; pendingIcon = -1; invalidate(); return; }
            pendingIcon = newIcon;
            Ui.animate(0f, 1f, 260, Ui.STANDARD, a -> {
                float t = (float) a.getAnimatedValue();
                if (t >= 0.5f && pendingIcon >= 0) { icon = pendingIcon; pendingIcon = -1; }
                swap = t < 0.5f ? 1f - t * 2f * 0.6f : 0.4f + (t - 0.5f) * 2f * 0.6f;
                invalidate();
            });
        }

        void setColor(int c) { color = c; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float s = Ui.dpf(sizeDp) * swap;
            Icons.draw(c, icon, getWidth() / 2f, getHeight() / 2f, s, color);
        }
    }
}
