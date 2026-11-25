package com.samsung.oneui.viewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.googlecode.tesseract.android.TessBaseAPI;
import com.googlecode.tesseract.android.ResultIterator;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;
import java.io.*;
import java.util.*;

/** On-device Korean/English OCR and actual PDF text positions. */
public final class OcrHost {
    private static Context context;
    private OcrHost() {}
    public static void init(Context ctx) {
        context = ctx.getApplicationContext();
        PDFBoxResourceLoader.init(context);
    }
    private static synchronized String dataPath() throws IOException {
        if (context == null) throw new IOException("텍스트 인식 엔진 초기화 실패");
        File root = new File(context.getFilesDir(), "ocr-v4.1.0"), dir = new File(root, "tessdata");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("인식 모델 폴더 생성 실패");
        for (String lang : new String[]{"eng", "kor"}) {
            File target = new File(dir, lang + ".traineddata");
            if (!target.exists()) {
                File tmp = new File(dir, lang + ".tmp");
                try (InputStream in = context.getAssets().open("tessdata/" + lang + ".traineddata"); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buffer = new byte[32768]; int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
                if (!tmp.renameTo(target)) throw new IOException("인식 모델 준비 실패");
            }
        }
        return root.getAbsolutePath();
    }
    public static Map<String, Object> recognize(String imageBase64, String lang) {
        byte[] bytes = Util.b64Decode(imageBase64);
        Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bmp == null) return Util.D("ok", false, "error", "인식할 이미지를 열 수 없습니다.");
        try { return recognizeBitmap(bmp); } finally { bmp.recycle(); }
    }
    public static Map<String, Object> recognizeBitmap(Bitmap bmp) {
        TessBaseAPI tess = null;
        try {
            tess = new TessBaseAPI();
            if (!tess.init(dataPath(), "kor+eng", TessBaseAPI.OEM_LSTM_ONLY)) throw new IOException("한국어·영어 인식 모델 초기화 실패");
            tess.setPageSegMode(3);
            tess.setImage(bmp);
            String text = tess.getUTF8Text();
            List<Object> words = new ArrayList<>();
            ResultIterator it = tess.getResultIterator();
            if (it != null) {
                try {
                    it.begin();
                    do {
                        String t = it.getUTF8Text(3); int[] b = it.getBoundingBox(3);
                        if (t != null && !t.trim().isEmpty() && b != null && b.length == 4)
                            words.add(Util.D("t", t.trim(), "x", b[0], "y", b[1], "w", Math.max(1, b[2] - b[0]), "h", Math.max(1, b[3] - b[1])));
                    } while (it.next(3));
                } finally { it.delete(); }
            }
            return Util.D("ok", true, "text", text == null ? "" : text, "words", words);
        } catch (Exception | LinkageError e) {
            return Util.D("ok", false, "error", "텍스트 인식 실패: " + e.getMessage());
        } finally { if (tess != null) tess.recycle(); }
    }
    private static final class PositionStripper extends PDFTextStripper {
        final List<Object> words = new ArrayList<>();
        final float pageW, pageH;
        PositionStripper(float w, float h) throws IOException { pageW = w; pageH = h; setSortByPosition(true); }
        @Override protected void writeString(String text, List<TextPosition> positions) throws IOException {
            StringBuilder word = new StringBuilder();
            float left = Float.MAX_VALUE, top = Float.MAX_VALUE, right = 0, bottom = 0;
            for (TextPosition pos : positions) {
                String value = pos.getUnicode();
                if (value.trim().isEmpty()) {
                    if (word.length() > 0) addWord(word, left, top, right, bottom);
                    word.setLength(0); left = top = Float.MAX_VALUE; right = bottom = 0;
                } else {
                    word.append(value); left = Math.min(left, pos.getXDirAdj()); top = Math.min(top, pos.getYDirAdj() - pos.getHeightDir());
                    right = Math.max(right, pos.getXDirAdj() + pos.getWidthDirAdj()); bottom = Math.max(bottom, pos.getYDirAdj());
                }
            }
            if (word.length() > 0) addWord(word, left, top, right, bottom);
            super.writeString(text, positions);
        }
        private void addWord(StringBuilder text, float l, float t, float r, float b) {
            words.add(Util.D("t", text.toString(), "x", l / pageW, "y", t / pageH, "w", (r - l) / pageW, "h", (b - t) / pageH));
        }
    }
    public static Map<String, Object> pdfPageText(String path, int index) {
        try (PDDocument doc = PDDocument.load(new File(PdfHost.readablePath(path)))) {
            PDPage page = doc.getPage(index);
            float w = page.getCropBox().getWidth(), h = page.getCropBox().getHeight();
            if (page.getRotation() % 180 != 0) { float old = w; w = h; h = old; }
            PositionStripper stripper = new PositionStripper(w, h);
            stripper.setStartPage(index + 1); stripper.setEndPage(index + 1);
            String text = stripper.getText(doc);
            if (!text.trim().isEmpty()) return Util.D("ok", true, "text", text, "items", stripper.words);
            Bitmap bmp = PdfHost.renderBitmap(path, index, 1800);
            if (bmp == null) throw new IOException("PDF 페이지 렌더링 실패");
            try { return normalizedBitmapText(bmp); } finally { bmp.recycle(); }
        } catch (Exception e) { return Util.D("ok", false, "error", "PDF 텍스트 인식 실패: " + e.getMessage()); }
    }
    public static Map<String, Object> normalizedBitmapText(Bitmap bmp) {
        Map<String, Object> result = recognizeBitmap(bmp);
        if (!Boolean.TRUE.equals(result.get("ok"))) return result;
        List<Object> items = new ArrayList<>();
        for (Object obj : (List<?>) result.get("words")) {
            Map<?, ?> word = (Map<?, ?>) obj;
            items.add(Util.D("t", word.get("t"), "x", Util.toDouble(word.get("x"), 0) / bmp.getWidth(), "y", Util.toDouble(word.get("y"), 0) / bmp.getHeight(), "w", Util.toDouble(word.get("w"), 0) / bmp.getWidth(), "h", Util.toDouble(word.get("h"), 0) / bmp.getHeight()));
        }
        return Util.D("ok", true, "text", result.get("text"), "items", items);
    }
    public static Map<String, Object> pdfText(String path) {
        List<Object> pages = new ArrayList<>();
        for (int p = 0; p < PdfHost.getPageCount(path); p++) {
            Map<String, Object> result = pdfPageText(path, p);
            if (!Boolean.TRUE.equals(result.get("ok"))) return result;
            pages.add(result);
        }
        return Util.D("ok", true, "pages", pages);
    }
}
