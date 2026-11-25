package com.samsung.oneui.viewer;

import android.content.Context;
import android.graphics.Bitmap;
import java.io.*;
import java.nio.file.*;
import java.util.*;

final class ViewerExports {
    private ViewerExports() {}
    static File snapshot(Context context,String source,String kind,StageCanvasView stage,NativeDocView document,int quality,int dpi,boolean modified)throws IOException {
        if(modified)return snapshot(context,source,kind,stage,document,quality,dpi);
        File directory=new File(context.getCacheDir(),"exports/"+UUID.randomUUID());
        if(!directory.mkdirs())throw new IOException("내보내기 폴더 생성 실패");
        File target=new File(directory,new File(source).getName());
        Files.copy(new File(source).toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);return target;
    }
    static File snapshot(Context context, String source, String kind, StageCanvasView stage, NativeDocView document, int quality, int dpi) throws IOException {
        File dir = new File(context.getCacheDir(), "exports/" + UUID.randomUUID());
        if (!dir.mkdirs()) throw new IOException("내보내기 폴더 생성 실패");
        String name = new File(source).getName(), ext = Util.extOf(name);
        if ("image".equals(kind) && !Arrays.asList(".png", ".jpg", ".jpeg", ".bmp", ".gif", ".tif", ".tiff", ".ico", ".webp").contains(ext)) name = name.substring(0, name.length() - ext.length()) + ".png";
        File target = new File(dir, name);
        if ("image".equals(kind)) {
            Bitmap flat = stage.getImageAnnotations().isEmpty() ? stage.getImageBitmap() : MarkupModel.flattenOntoBitmap(stage.getImageBitmap(), stage.getImageAnnotations());
            if(flat==null)throw new IOException("원본 이미지를 여는 중입니다.");
            try { require(FileOps.saveBitmap(target.getAbsolutePath(), flat, quality), "success"); } finally { if (flat != stage.getImageBitmap()) flat.recycle(); }
        } else if ("pdf".equals(kind)) {
            writePdf(target, stage.getPdfPages(), dpi,quality);
        } else if ("doc".equals(kind)) {
            Files.copy(new File(source).toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Map<String, Object> result = document.writeDocument(target.getAbsolutePath(), false); require(result, "ok");
            if (result.get("path") != null) target = new File(String.valueOf(result.get("path")));
        } else Files.copy(new File(source).toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
    static void writePdf(File target, List<MarkupModel.PdfPageItem> pages, int dpi) throws IOException {
        writePdf(target,pages,dpi,100);
    }
    static void writePdf(File target,List<MarkupModel.PdfPageItem> pages,int dpi,int quality)throws IOException {
        String id = UUID.randomUUID().toString(); require(PdfHost.begin(id, target.getAbsolutePath()), "ok");
        try {
            for (MarkupModel.PdfPageItem page : pages) {
                Bitmap bitmap = page.renderFlattened(pdfWidth(page,dpi));
                if (bitmap == null) throw new IOException("PDF 페이지 렌더링 실패");
                try { require(PdfHost.addPageBitmap(id, page.displayWidthPt(), page.displayHeightPt(), bitmap, quality), "ok"); } finally { bitmap.recycle(); }
            }
            require(PdfHost.end(id, target.getName()), "ok");
        } catch (IOException | RuntimeException e) { PdfHost.abort(id); throw e; }
    }
    static int pdfWidth(MarkupModel.PdfPageItem page,int dpi){return Math.max(1,Math.round(page.displayWidthPt()*dpi/72f));}
    static void require(Map<String, Object> result, String key) throws IOException {
        if (!Boolean.TRUE.equals(result.get(key))) throw new IOException(String.valueOf(result.get("error")));
    }
}
