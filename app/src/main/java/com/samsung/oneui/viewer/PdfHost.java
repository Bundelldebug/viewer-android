package com.samsung.oneui.viewer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.content.Context;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PdfHost {
    private PdfHost() {}
    private static File cacheDir;
    public static void init(Context context) { cacheDir = new File(context.getCacheDir(), "unlocked-pdf"); cacheDir.mkdirs(); File[] old = cacheDir.listFiles(); if (old != null) for (File file : old) if (file.isFile() && file.getName().endsWith(".pdf")) file.delete(); }

    private static final class DocHolder {
        final ParcelFileDescriptor pfd;
        final PdfRenderer renderer;
        File unlockedFile;

        DocHolder(ParcelFileDescriptor pfd, PdfRenderer renderer) {
            this.pfd = pfd;
            this.renderer = renderer;
        }

        void close() {
            try { renderer.close(); } catch (Exception ignored) {}
            try { pfd.close(); } catch (Exception ignored) {}
            if (unlockedFile != null) unlockedFile.delete();
        }
    }

    private static final Map<String, DocHolder> DOCS = new HashMap<>();
    private static final Object GATE = new Object();

    public static Map<String, Object> open(String path, String password) {
        synchronized (GATE) {
            try {
                if (path == null || path.isEmpty()) return Util.D("ok", false, "error", "파일을 찾을 수 없습니다.");
                File f = new File(path);
                if (!f.exists()) return Util.D("ok", false, "error", "파일을 찾을 수 없습니다.");
                DocHolder old = DOCS.remove(path);
                if (old != null) old.close();

                File unlocked = null;
                if (password != null && !password.isEmpty()) {
                    try (PDDocument doc = PDDocument.load(f, password)) {
                        unlocked = File.createTempFile("page-", ".pdf", cacheDir);
                        doc.setAllSecurityToBeRemoved(true); doc.save(unlocked); f = unlocked;
                    } catch (InvalidPasswordException e) { return Util.D("ok", false, "locked", true, "error", "PDF 암호가 맞지 않습니다."); }
                }
                ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                PdfRenderer renderer;
                try {
                    renderer = new PdfRenderer(pfd);
                } catch (SecurityException se) {
                    try { pfd.close(); } catch (Exception ignored) {}
                    if (unlocked != null) unlocked.delete();
                    return Util.D("ok", false, "locked", true, "error", "PDF 암호를 입력해 주세요.");
                } catch (Exception ex) {
                    try { pfd.close(); } catch (Exception ignored) {}
                    if (unlocked != null) unlocked.delete();
                    return Util.D("ok", false, "error", "PDF를 열 수 없습니다: " + ex.getMessage());
                }
                DocHolder holder = new DocHolder(pfd, renderer); holder.unlockedFile = unlocked;
                DOCS.put(path, holder);
                int count = renderer.getPageCount();
                List<Object> pages = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    PdfRenderer.Page p = renderer.openPage(i);
                    try {
                        // PdfRenderer returns dimensions in points (1/72 inch), matching Windows p.Size * 0.75
                        pages.add(Util.D(
                                "w", Math.round(p.getWidth() * 100.0) / 100.0,
                                "h", Math.round(p.getHeight() * 100.0) / 100.0
                        ));
                    } finally {
                        p.close();
                    }
                }
                return Util.D("ok", true, "pages", pages, "count", count);
            } catch (Exception e) {
                return Util.D("ok", false, "error", "PDF를 열 수 없습니다: " + e.getMessage());
            }
        }
    }

    public static void close(String path) {
        synchronized (GATE) {
            DocHolder d = DOCS.remove(path);
            if (d != null) d.close();
        }
    }
    public static void closeAll() {
        synchronized (GATE) { for (DocHolder d : DOCS.values()) d.close(); DOCS.clear(); }
    }
    public static String readablePath(String path) {
        synchronized (GATE) { DocHolder d = DOCS.get(path); return d != null && d.unlockedFile != null ? d.unlockedFile.getAbsolutePath() : path; }
    }
    public static void retarget(String oldPath, String newPath) { synchronized (GATE) { DocHolder holder = DOCS.remove(oldPath); if (holder != null) DOCS.put(newPath,holder); } }

    public static int getPageCount(String path) {
        synchronized (GATE) {
            DocHolder d = DOCS.get(path);
            if (d == null) {
                open(path, "");
                d = DOCS.get(path);
                if (d == null) return 0;
            }
            return d.renderer.getPageCount();
        }
    }

    public static float[] getPageSizePt(String path, int index) {
        synchronized (GATE) {
            DocHolder d = DOCS.get(path);
            if (d == null) {
                open(path, "");
                d = DOCS.get(path);
                if (d == null) return new float[]{595f, 842f};
            }
            if (index < 0 || index >= d.renderer.getPageCount()) return new float[]{595f, 842f};
            PdfRenderer.Page p = d.renderer.openPage(index);
            try {
                return new float[]{p.getWidth(), p.getHeight()};
            } finally {
                p.close();
            }
        }
    }

    static Bitmap renderTile(String path,int index,int rotation,float scale,int x,int y,int width,int height) {
        synchronized(GATE) {
            DocHolder document=DOCS.get(path);if(document==null){open(path,"");document=DOCS.get(path);}
            if(document==null || index<0 || index>=document.renderer.getPageCount())return null;
            PdfRenderer.Page page=document.renderer.openPage(index);Bitmap bitmap=null;
            try {
                bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);
                android.graphics.Matrix matrix=tileMatrix(page.getWidth(),page.getHeight(),rotation,scale,x,y);
                page.render(bitmap,null,matrix,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);return bitmap;
            }catch(Exception|OutOfMemoryError error){if(bitmap!=null)bitmap.recycle();return null;}
            finally{page.close();}
        }
    }
    static android.graphics.Matrix tileMatrix(float width,float height,int rotation,float scale,int x,int y) {
        android.graphics.Matrix matrix=new android.graphics.Matrix();matrix.setRotate(rotation);
        if(rotation==90)matrix.postTranslate(height,0);else if(rotation==180)matrix.postTranslate(width,height);else if(rotation==270)matrix.postTranslate(0,width);
        matrix.postScale(scale,scale);matrix.postTranslate(-x,-y);return matrix;
    }
    public static Bitmap renderBitmap(String path, int index, int width) {
        synchronized (GATE) {
            DocHolder d = DOCS.get(path);
            if (d == null) {
                open(path, "");
                d = DOCS.get(path);
                if (d == null) return null;
            }
            if (index < 0 || index >= d.renderer.getPageCount()) return null;
            if (width < 32) width = 32;
            PdfRenderer.Page page = d.renderer.openPage(index);
            try {
                double ratio = (double) page.getHeight() / Math.max(1, page.getWidth());
                int h = Math.max(1, (int) Math.round(width * ratio));
                Bitmap bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                canvas.drawColor(Color.WHITE);
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bmp;
            } catch (Exception e) {
                return null;
            } finally {
                page.close();
            }
        }
    }

    public static Map<String, Object> addPageBitmap(String id, double widthPt, double heightPt, Bitmap bmp, int quality) {
        try {
            if (bmp == null) return Util.D("ok", false, "error", "이미지가 없습니다.");
            if(quality>=100) {
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();int width=bmp.getWidth();int[] pixels=new int[width];byte[] rgb=new byte[width*3];
                try(java.util.zip.DeflaterOutputStream compressed=new java.util.zip.DeflaterOutputStream(bytes)) {
                    for(int y=0;y<bmp.getHeight();y++) {
                        bmp.getPixels(pixels,0,width,0,y,width,1);
                        for(int x=0;x<width;x++){int pixel=pixels[x],alpha=pixel>>>24;for(int channel=0;channel<3;channel++){int value=(pixel>>(16-channel*8))&255;rgb[x*3+channel]=(byte)((value*alpha+255*(255-alpha)+127)/255);}}
                        compressed.write(rgb);
                    }
                }
                return addPageData(id,widthPt,heightPt,bmp.getWidth(),bmp.getHeight(),bytes.toByteArray(),"FlateDecode");
            }
            Bitmap flat = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(flat);
            c.drawColor(Color.WHITE);
            c.drawBitmap(bmp, 0, 0, null);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            flat.compress(Bitmap.CompressFormat.JPEG, Math.max(50, Math.min(100, quality)), bos);
            int pxW = flat.getWidth(), pxH = flat.getHeight();
            flat.recycle();
            return addPageBytes(id, widthPt, heightPt, pxW, pxH, bos.toByteArray());
        } catch (Exception e) {
            return Util.D("ok", false, "error", e.getMessage());
        }
    }

    public static Map<String, Object> addPageBytes(String id, double widthPt, double heightPt, int pxW, int pxH, byte[] jpg) {
        return addPageData(id,widthPt,heightPt,pxW,pxH,jpg,"DCTDecode");
    }
    private static Map<String,Object> addPageData(String id,double widthPt,double heightPt,int pxW,int pxH,byte[] jpg,String filter) {
        try {
            Writer w;
            synchronized (WRITERS) {
                w = WRITERS.get(id);
            }
            if (w == null) return Util.D("ok", false, "error", "쓰기 세션이 없습니다.");
            int imgObj = w.nextObj++, contObj = w.nextObj++, pageObj = w.nextObj++;
            String cw = String.format(Locale.US, "%.2f", widthPt);
            String ch = String.format(Locale.US, "%.2f", heightPt);

            mark(w, imgObj);
            putAscii(w, imgObj + " 0 obj\n<< /Type /XObject /Subtype /Image /Width " + pxW + " /Height " + pxH
                    + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /"+filter+" /Length " + jpg.length + " >>\nstream\n");
            w.fos.write(jpg);
            w.pos += jpg.length;
            putAscii(w, "\nendstream\nendobj\n");

            String content = "q " + cw + " 0 0 " + ch + " 0 0 cm /Im0 Do Q";
            mark(w, contObj);
            putAscii(w, contObj + " 0 obj\n<< /Length " + content.length() + " >>\nstream\n" + content + "\nendstream\nendobj\n");

            mark(w, pageObj);
            putAscii(w, pageObj + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + cw + " " + ch
                    + "] /Resources << /XObject << /Im0 " + imgObj + " 0 R >> /ProcSet [/PDF /ImageC] >> /Contents " + contObj + " 0 R >>\nendobj\n");
            w.pageObjs.add(pageObj);
            return Util.D("ok", true);
        } catch (Exception e) {
            return Util.D("ok", false, "error", e.getMessage());
        }
    }

    public static String render(String path, int index, int width, boolean jpeg) {
        synchronized (GATE) {
            DocHolder d = DOCS.get(path);
            if (d == null) {
                open(path, "");
                d = DOCS.get(path);
                if (d == null) return null;
            }
            if (index < 0 || index >= d.renderer.getPageCount()) return null;
            if (width < 32) width = 32;
            if (width > 4096) width = 4096;
            PdfRenderer.Page page = d.renderer.openPage(index);
            try {
                double ratio = (double) page.getHeight() / Math.max(1, page.getWidth());
                int h = Math.max(1, (int) Math.round(width * ratio));
                Bitmap bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                canvas.drawColor(Color.WHITE);
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                if (jpeg) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 86, bos);
                    bmp.recycle();
                    return "data:image/jpeg;base64," + Util.b64Encode(bos.toByteArray());
                } else {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
                    bmp.recycle();
                    return "data:image/png;base64," + Util.b64Encode(bos.toByteArray());
                }
            } catch (Exception e) {
                return null;
            } finally {
                page.close();
            }
        }
    }

    // ---------- PDF 1.4 Writer (one JPEG per page, exact port of PdfHost.cs) ----------
    private static final class Writer {
        FileOutputStream fos;
        String finalPath;
        String tmpPath;
        long pos = 0;
        final List<Long> offsets = new ArrayList<>();
        final List<Integer> pageObjs = new ArrayList<>();
        int nextObj = 3; // 1 = catalog, 2 = pages
    }

    private static final Map<String, Writer> WRITERS = new HashMap<>();
    public static void abort(String id) {
        Writer writer; synchronized (WRITERS) { writer = WRITERS.remove(id); }
        if (writer != null) { try { writer.fos.close(); } catch (IOException ignored) {} new File(writer.tmpPath).delete(); }
    }

    private static void putAscii(Writer w, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        w.fos.write(b);
        w.pos += b.length;
    }

    private static void mark(Writer w, int obj) {
        while (w.offsets.size() <= obj) w.offsets.add(0L);
        w.offsets.set(obj, w.pos);
    }

    public static Map<String, Object> begin(String id, String path) {
        try {
            Writer w = new Writer();
            w.finalPath = path;
            w.tmpPath = path + ".tmp";
            File tmp = new File(w.tmpPath);
            if (tmp.getParentFile() != null) tmp.getParentFile().mkdirs();
            w.fos = new FileOutputStream(tmp);
            putAscii(w, "%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n");
            synchronized (WRITERS) {
                WRITERS.put(id, w);
            }
            return Util.D("ok", true);
        } catch (Exception e) {
            return Util.D("ok", false, "error", e.getMessage());
        }
    }

    public static Map<String, Object> addPage(String id, double widthPt, double heightPt, int pxW, int pxH, String jpegBase64) {
        try {
            Writer w;
            synchronized (WRITERS) {
                w = WRITERS.get(id);
            }
            if (w == null) return Util.D("ok", false, "error", "쓰기 세션이 없습니다.");
            byte[] jpg = Util.b64Decode(jpegBase64);
            int imgObj = w.nextObj++, contObj = w.nextObj++, pageObj = w.nextObj++;
            String cw = String.format(Locale.US, "%.2f", widthPt);
            String ch = String.format(Locale.US, "%.2f", heightPt);

            mark(w, imgObj);
            putAscii(w, imgObj + " 0 obj\n<< /Type /XObject /Subtype /Image /Width " + pxW + " /Height " + pxH
                    + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length " + jpg.length + " >>\nstream\n");
            w.fos.write(jpg);
            w.pos += jpg.length;
            putAscii(w, "\nendstream\nendobj\n");

            String content = "q " + cw + " 0 0 " + ch + " 0 0 cm /Im0 Do Q";
            mark(w, contObj);
            putAscii(w, contObj + " 0 obj\n<< /Length " + content.length() + " >>\nstream\n" + content + "\nendstream\nendobj\n");

            mark(w, pageObj);
            putAscii(w, pageObj + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + cw + " " + ch
                    + "] /Resources << /XObject << /Im0 " + imgObj + " 0 R >> /ProcSet [/PDF /ImageC] >> /Contents " + contObj + " 0 R >>\nendobj\n");
            w.pageObjs.add(pageObj);
            return Util.D("ok", true);
        } catch (Exception e) {
            return Util.D("ok", false, "error", e.getMessage());
        }
    }

    public static Map<String, Object> end(String id, String title) {
        Writer w;
        synchronized (WRITERS) {
            w = WRITERS.remove(id);
        }
        if (w == null) return Util.D("ok", false, "error", "쓰기 세션이 없습니다.");
        try {
            if (w.pageObjs.isEmpty()) {
                w.fos.close();
                new File(w.tmpPath).delete();
                return Util.D("ok", false, "error", "페이지가 없습니다.");
            }
            mark(w, 1);
            putAscii(w, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
            mark(w, 2);
            StringBuilder kids = new StringBuilder();
            for (int p : w.pageObjs) kids.append(p).append(" 0 R ");
            putAscii(w, "2 0 obj\n<< /Type /Pages /Count " + w.pageObjs.size() + " /Kids [" + kids + "] >>\nendobj\n");

            int infoObj = w.nextObj++;
            mark(w, infoObj);
            String safe = (title == null ? "" : title).replace("\\", "").replace("(", "").replace(")", "");
            StringBuilder tt = new StringBuilder();
            for (int i = 0; i < safe.length(); i++) {
                char c = safe.charAt(i);
                tt.append(c < 128 ? c : '?');
            }
            String dt = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(new Date());
            putAscii(w, infoObj + " 0 obj\n<< /Title (" + tt + ") /Producer (Viewer Android) /CreationDate (D:" + dt + ") >>\nendobj\n");

            long xref = w.pos;
            int total = w.nextObj;
            putAscii(w, "xref\n0 " + total + "\n0000000000 65535 f \n");
            for (int i = 1; i < total; i++) {
                long off = i < w.offsets.size() ? w.offsets.get(i) : 0L;
                putAscii(w, String.format(Locale.US, "%010d 00000 n \n", off));
            }
            putAscii(w, "trailer\n<< /Size " + total + " /Root 1 0 R /Info " + infoObj + " 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
            w.fos.flush();
            w.fos.close();

            File finalFile = new File(w.finalPath);
            File tmpFile = new File(w.tmpPath);
            if (finalFile.exists()) finalFile.delete();
            if (!tmpFile.renameTo(finalFile)) {
                Util.writeAllBytes(finalFile, Util.readAllBytes(tmpFile));
                tmpFile.delete();
            }
            return Util.D("ok", true, "path", finalFile.getAbsolutePath(), "size", finalFile.length());
        } catch (Exception e) {
            try { w.fos.close(); } catch (Exception ignored) {}
            new File(w.tmpPath).delete();
            return Util.D("ok", false, "error", e.getMessage());
        }
    }
}
