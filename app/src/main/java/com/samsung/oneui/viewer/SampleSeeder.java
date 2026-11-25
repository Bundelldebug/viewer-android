package com.samsung.oneui.viewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SampleSeeder {
    private SampleSeeder() {}

    public static File ensureWorkspaceDir(Context ctx) {
        File ext = ctx.getExternalFilesDir(null);
        File base = (ext != null) ? new File(ext, "SamsungViewer_Samples") : new File(ctx.getFilesDir(), "SamsungViewer_Samples");
        if (!base.exists()) base.mkdirs();
        seedIfNeeded(base);
        return base;
    }

    public static void ensureSeeded(File dir) {
        if (dir != null) {
            if (!dir.exists()) dir.mkdirs();
            seedIfNeeded(dir);
        }
    }

    private static void seedIfNeeded(File dir) {
        try {
            File marker = new File(dir, ".seeded_v1");
            if (marker.exists()) return;

            createSampleAiPng(new File(dir, "01_Galaxy_OneUI_AI_Art.png"));
            createSamplePhotoJpg(new File(dir, "02_Sunset_Landscape.jpg"));
            createSamplePdf(new File(dir, "03_OneUI_Viewer_Guide.pdf"));
            createSampleDocx(new File(dir, "04_Project_Overview.docx"));
            createSampleXlsx(new File(dir, "05_Monthly_Metrics.xlsx"));
            createSampleHwpx(new File(dir, "06_Korean_Report_Sample.hwpx"));
            createSampleMarkdown(new File(dir, "07_Release_Notes.md"));

            try (FileOutputStream fos = new FileOutputStream(marker)) {
                fos.write("ok".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {}
    }

    private static void createSampleAiPng(File outFile) throws Exception {
        int w = 1080, h = 1080;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        p.setShader(new LinearGradient(0, 0, w, h,
            new int[]{0xFF0A122C, 0xFF1428A0, 0xFF0381FE, 0xFF56C8FF},
            new float[]{0f, 0.4f, 0.78f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        p.setColor(0x28FFFFFF);
        c.drawCircle(w * 0.78f, h * 0.24f, 220f, p);
        p.setColor(0x44FFFFFF);
        c.drawCircle(w * 0.78f, h * 0.24f, 130f, p);

        p.setColor(0x33FFFFFF);
        RectF card = new RectF(96, 560, w - 96, h - 110);
        c.drawRoundRect(card, 48, 48, p);

        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        p.setTextSize(68f);
        c.drawText("Samsung Viewer", 148, 675, p);

        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        p.setTextSize(34f);
        p.setColor(0xFFE5F0FF);
        c.drawText("One UI 9.5 · Mobile & Tablet Edition", 148, 738, p);
        c.drawText("Image · PDF · Office · HWP/HWPX · AI Metadata · Markup", 148, 800, p);
        c.drawText("Pinch Zoom · Instant Alpha · Color Adjust · OCR", 148, 858, p);

        ByteArrayOutputStream rawPng = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, rawPng);
        bmp.recycle();

        byte[] pngBytes = injectPngTextChunk(
            rawPng.toByteArray(),
            "parameters",
            "masterpiece, best quality, futuristic Samsung One UI 9.5 glassmorphism artwork, cobalt blue & cyan aurora, ultra detailed\n" +
            "Negative prompt: blurry, low quality, watermark, artifacts\n" +
            "Steps: 30, Sampler: DPM++ 2M Karras, CFG scale: 7.0, Seed: 20260950, Size: 1080x1080, Model: OneUI_XL_v95"
        );
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(pngBytes);
        }
    }

    private static byte[] injectPngTextChunk(byte[] png, String keyword, String text) {
        try {
            byte[] keyB = keyword.getBytes(StandardCharsets.ISO_8859_1);
            byte[] txtB = text.getBytes(StandardCharsets.UTF_8);
            byte[] data = new byte[keyB.length + 1 + txtB.length];
            System.arraycopy(keyB, 0, data, 0, keyB.length);
            data[keyB.length] = 0;
            System.arraycopy(txtB, 0, data, keyB.length + 1, txtB.length);

            byte[] type = "tEXt".getBytes(StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32();
            crc.update(type);
            crc.update(data);
            int crcVal = (int) crc.getValue();

            ByteBuffer chunk = ByteBuffer.allocate(12 + data.length);
            chunk.putInt(data.length);
            chunk.put(type);
            chunk.put(data);
            chunk.putInt(crcVal);
            byte[] chunkBytes = chunk.array();

            // Insert right after IHDR chunk (8-byte sig + 25-byte IHDR = 33)
            int insertPos = 33;
            if (png.length <= insertPos) return png;
            ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + chunkBytes.length);
            out.write(png, 0, insertPos);
            out.write(chunkBytes);
            out.write(png, insertPos, png.length - insertPos);
            return out.toByteArray();
        } catch (Throwable t) {
            return png;
        }
    }

    private static void createSamplePhotoJpg(File outFile) throws Exception {
        int w = 1280, h = 800;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        p.setShader(new LinearGradient(0, 0, 0, h,
            new int[]{0xFF1E1B4B, 0xFF7C2D12, 0xFFF97316, 0xFFFDE047},
            new float[]{0f, 0.45f, 0.78f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        p.setColor(0xFFFFFBEB);
        c.drawCircle(w * 0.5f, h * 0.62f, 110f, p);

        p.setColor(0xFF0F172A);
        c.drawRect(0, h * 0.68f, w, h, p);

        p.setColor(Color.WHITE);
        p.setTextSize(42f);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        c.drawText("Golden Horizon · Sample Photo", 64, h - 80, p);

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos);
        }
        bmp.recycle();
    }

    private static void createSamplePdf(File outFile) throws Exception {
        PdfDocument doc = new PdfDocument();
        String[] titles = new String[]{
            "1. Samsung Viewer (Android) 소개",
            "2. 이미지 편집 · 마크업 · AI 메타데이터",
            "3. PDF 페이지 구성 및 오피스/한글 문서 편집"
        };
        String[][] bullets = new String[][]{
            {
                "• 모바일 UI (하단 플로팅 필 툴바 & 드로어) 및 태블릿 UI (다단 레이아웃) 완벽 지원",
                "• 두 손가락 핀치 줌, 팬 이동, 더블탭 확대/맞춤, 좌우 스와이프 탐색",
                "• PNG, JPEG, WebP, GIF, SVG, PSD, TGA, QOI, PNM, TIFF, HEIC, BMP, ICO 지원",
                "• 텍스트 인식 (OCR) 및 문서 내 검색 기능 내장"
            },
            {
                "• 회전, 대칭, 자유/비율 자르기, 크기 조절, 색상 조정 (밝기/대비/채도/색조/온도/선명도)",
                "• 인스턴트 알파 (배경 투명화) 및 사각형 영역 선택 자르기/복사",
                "• 벡터 마크업: 펜, 형광펜, 선, 화살표, 사각형, 원, 텍스트, 가리기, 전자 서명",
                "• AI 이미지 생성 프롬프트/모델/시드 자동 추출 (Stable Diffusion, ComfyUI, NAI 등)"
            },
            {
                "• PDF 연속 스크롤 / 한 페이지 / 두 페이지 보기, 썸네일 및 북마크 탐색",
                "• PDF 페이지 회전, 복제, 삭제, 순서 이동, 빈 페이지 및 외부 PDF/이미지 삽입",
                "• 마크업이 반영된 고해상도 PDF 저장 및 페이지별 PNG 내보내기",
                "• DOCX, XLSX, PPTX, HWP, HWPX, DOC, RTF, CSV, JSON, Markdown 열람 및 직접 편집"
            }
        };

        for (int i = 0; i < 3; i++) {
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(842, 595, i + 1).create();
            PdfDocument.Page page = doc.startPage(info);
            Canvas c = page.getCanvas();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            p.setColor(0xFFF6F8FC);
            c.drawRect(0, 0, 842, 595, p);

            p.setColor(0xFF0381FE);
            c.drawRoundRect(new RectF(48, 42, 794, 130), 20, 20, p);

            p.setColor(Color.WHITE);
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            p.setTextSize(26f);
            c.drawText(titles[i], 76, 96, p);

            p.setColor(0xFF151923);
            p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            p.setTextSize(18f);
            int y = 200;
            for (String line : bullets[i]) {
                c.drawText(line, 76, y, p);
                y += 58;
            }

            p.setColor(0xFF64748B);
            p.setTextSize(14f);
            c.drawText("Samsung Viewer One UI 9.5 — Page " + (i + 1) + " / 3", 76, 540, p);
            doc.finishPage(page);
        }

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            doc.writeTo(fos);
        }
        doc.close();
    }

    private static void createSampleDocx(File outFile) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outFile))) {
            putZip(zos, "[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                "</Types>");
            putZip(zos, "_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
                "</Relationships>");
            putZip(zos, "word/document.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" +
                "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>Samsung Viewer 안드로이드 프로젝트 개요</w:t></w:r></w:p>" +
                "<w:p><w:r><w:t>이 문서는 Samsung Viewer 앱에서 직접 열람하고 상단 '문서 편집' 버튼을 눌러 내용을 수정 및 저장할 수 있는 DOCX 샘플 문서입니다.</w:t></w:r></w:p>" +
                "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>주요 기능:</w:t></w:r></w:p>" +
                "<w:p><w:r><w:t>1. 모바일 UI 및 태블릿 UI 자동/수동 전환 지원</w:t></w:r></w:p>" +
                "<w:p><w:r><w:t>2. 문서 내 검색 및 찾아 바꾸기 (Find &amp; Replace)</w:t></w:r></w:p>" +
                "<w:p><w:r><w:t>3. 편집 후 원본 DOCX 형식으로 즉시 저장</w:t></w:r></w:p>" +
                "</w:body></w:document>");
        }
    }

    private static void createSampleXlsx(File outFile) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outFile))) {
            putZip(zos, "xl/workbook.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<sheets><sheet name=\"성과 지표\" sheetId=\"1\" r:id=\"rId1\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"/></sheets>" +
                "</workbook>");
            putZip(zos, "xl/sharedStrings.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"8\" uniqueCount=\"8\">" +
                "<si><t>구분</t></si><si><t>모바일 UI</t></si><si><t>태블릿 UI</t></si><si><t>상태</t></si>" +
                "<si><t>이미지 편집/코덱</t></si><si><t>PDF 뷰어/편집</t></si><si><t>오피스/HWP 문서</t></si><si><t>정상 완료</t></si>" +
                "</sst>");
            putZip(zos, "xl/worksheets/sheet1.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"C1\" t=\"s\"><v>2</v></c><c r=\"D1\" t=\"s\"><v>3</v></c></row>" +
                "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>4</v></c><c r=\"B2\"><v>100</v></c><c r=\"C2\"><v>100</v></c><c r=\"D2\" t=\"s\"><v>7</v></c></row>" +
                "<row r=\"3\"><c r=\"A3\" t=\"s\"><v>5</v></c><c r=\"B3\"><v>100</v></c><c r=\"C3\"><v>100</v></c><c r=\"D3\" t=\"s\"><v>7</v></c></row>" +
                "<row r=\"4\"><c r=\"A4\" t=\"s\"><v>6</v></c><c r=\"B4\"><v>100</v></c><c r=\"C4\"><v>100</v></c><c r=\"D4\" t=\"s\"><v>7</v></c></row>" +
                "</sheetData></worksheet>");
        }
    }

    private static void createSampleHwpx(File outFile) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outFile))) {
            putZip(zos, "Contents/section0.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<hs:sec xmlns:hs=\"http://www.hancom.co.kr/hwpml/2011/section\" xmlns:hp=\"http://www.hancom.co.kr/hwpml/2011/paragraph\">" +
                "<hp:p><hp:run><hp:t>한글 HWPX 문서 열람 및 편집 샘플</hp:t></hp:run></hp:p>" +
                "<hp:p><hp:run><hp:t>Samsung Viewer 안드로이드 버전은 HWP(한글 97~) 및 HWPX(개방형 한글) 문서를 별도 뷰어 없이 바로 열고 편집할 수 있습니다.</hp:t></hp:run></hp:p>" +
                "<hp:p><hp:run><hp:t>상단 툴바의 '문서 편집' 아이콘을 눌러 문단을 수정한 뒤 저장해 보세요.</hp:t></hp:run></hp:p>" +
                "</hs:sec>");
        }
    }

    private static void createSampleMarkdown(File outFile) throws Exception {
        String md = "# Samsung Viewer for Android (One UI 9.5)\n\n" +
            "데스크톱용 **Samsung Viewer**의 모든 기능을 그대로 안드로이드 스마트폰 및 태블릿 환경에 맞춰 이식한 버전입니다.\n\n" +
            "## 핵심 특징\n" +
            "- **모바일 UI & 태블릿 UI 맞춤형 인터페이스**: 화면 크기에 따라 자동 전환되며, 설정에서 언제든 원하는 UI 모드를 직접 고정할 수 있습니다.\n" +
            "- **강력한 이미지 뷰어 & 편집기**: 자유/비율 자르기, 색상 조정, 인스턴트 알파(배경 제거), 벡터 마크업, 서명, AI 프롬프트 정보 추출.\n" +
            "- **올인원 PDF & 문서 도구**: PDF 페이지 구성/병합/주석 저장, DOCX/XLSX/PPTX/HWP/HWPX/Markdown 실시간 편집.\n";
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(md.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void putZip(ZipOutputStream zos, String name, String content) throws Exception {
        ZipEntry ze = new ZipEntry(name);
        zos.putNextEntry(ze);
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
