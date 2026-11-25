package com.samsung.oneui.viewer;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.os.*;
import android.print.*;
import android.print.pdf.PrintedPdfDocument;
import java.io.*;

final class ViewerPrintAdapter extends PrintDocumentAdapter {
    final Context context; final String name, kind;
    final StageCanvasView stage; final NativeDocView document;
    PrintedPdfDocument output; int count;float rasterScale=300f/72;
    ViewerPrintAdapter(Context context, String name, String kind, StageCanvasView stage, NativeDocView document) {
        this.context = context; this.name = name; this.kind = kind; this.stage = stage; this.document = document;
    }
    @Override public void onLayout(PrintAttributes old, PrintAttributes attributes, CancellationSignal cancellation, LayoutResultCallback callback, Bundle extras) {
        if (cancellation.isCanceled()) { callback.onLayoutCancelled(); return; }
        if (output != null) output.close(); output = new PrintedPdfDocument(context, attributes);
        PrintAttributes.Resolution resolution=attributes.getResolution();rasterScale=(resolution==null?300:Math.max(resolution.getHorizontalDpi(),resolution.getVerticalDpi()))/72f;
        Rect area = output.getPageContentRect();
        count = "pdf".equals(kind) ? stage.getPdfPages().size() : "doc".equals(kind) ? document.printPageCount(area.width(), area.height()) : 1;
        callback.onLayoutFinished(new PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(count).build(), true);
    }
    private boolean selected(int index, PageRange[] ranges) { for (PageRange range : ranges) if (index >= range.getStart() && index <= range.getEnd()) return true; return false; }
    @Override public void onWrite(PageRange[] ranges, ParcelFileDescriptor destination, CancellationSignal cancellation, WriteResultCallback callback) {
        try {
            for (int index = 0; index < count; index++) {
                if (cancellation.isCanceled()) { callback.onWriteCancelled(); return; }
                if (!selected(index, ranges)) continue;
                PdfDocument.Page page = output.startPage(index); Rect area = page.getInfo().getContentRect();
                Bitmap bitmap = "doc".equals(kind) ? document.printPage(index,Math.round(area.width()*rasterScale),Math.round(area.height()*rasterScale)) : "pdf".equals(kind) ? stage.getPdfPages().get(index).renderFlattened(Math.max(1,Math.round(Math.min(area.width(),area.height()*stage.getPdfPages().get(index).displayWidthPt()/stage.getPdfPages().get(index).displayHeightPt())*rasterScale))) : MarkupModel.flattenOntoBitmap(stage.getImageBitmap(), stage.getImageAnnotations());
                if (bitmap != null) {
                    float scale = Math.min((float)area.width() / bitmap.getWidth(), (float)area.height() / bitmap.getHeight());
                    float w = bitmap.getWidth() * scale, h = bitmap.getHeight() * scale;
                    page.getCanvas().drawBitmap(bitmap, null, new RectF(area.left + (area.width()-w)/2, area.top + (area.height()-h)/2, area.left + (area.width()+w)/2, area.top + (area.height()+h)/2), new Paint(Paint.FILTER_BITMAP_FLAG));
                    if (bitmap != stage.getImageBitmap()) bitmap.recycle();
                }
                output.finishPage(page);
            }
            try (OutputStream stream = new FileOutputStream(destination.getFileDescriptor())) { output.writeTo(stream); }
            callback.onWriteFinished(ranges);
        } catch (OutOfMemoryError e) {callback.onWriteFailed("선택한 인쇄 해상도를 처리할 메모리가 부족합니다.");}
        catch (Exception e) { callback.onWriteFailed(e.getMessage()); }
    }
    @Override public void onFinish() { if (output != null) output.close(); output = null; }
}
