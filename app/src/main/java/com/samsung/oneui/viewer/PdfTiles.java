package com.samsung.oneui.viewer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.LruCache;

/** Render visible PDF regions at device pixels, including when zoomed past page width. */
final class PdfTiles {
    private final LruCache<String,Bitmap> tiles=new LruCache<String,Bitmap>(16*1024*1024) {
        @Override protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}
    };
    private final LruCache<Bitmap,ImageTiles> images=new LruCache<>(4);
    void clear(){tiles.evictAll();images.evictAll();}
    void draw(Canvas canvas,MarkupModel.PdfPageItem page,RectF bounds,float zoom,Paint paint,int viewportWidth,int viewportHeight) {
        if("blank".equals(page.kind))return;
        canvas.save();canvas.clipRect(bounds);
        try {
            if("img".equals(page.kind) && page.customBitmap!=null) {
                Bitmap bitmap=page.customBitmap;ImageTiles image=images.get(bitmap);
                if(image==null){image=new ImageTiles();images.put(bitmap,image);}
                canvas.translate(bounds.left,bounds.top);canvas.scale(zoom,zoom);
                if(page.rot==90){canvas.translate(page.hPt,0);canvas.rotate(90);}
                else if(page.rot==180){canvas.translate(page.wPt,page.hPt);canvas.rotate(180);}
                else if(page.rot==270){canvas.translate(0,page.wPt);canvas.rotate(270);}
                canvas.scale(page.wPt/bitmap.getWidth(),page.hPt/bitmap.getHeight());
                Rect clip=canvas.getClipBounds();image.draw(canvas,bitmap,paint,zoom*page.wPt/bitmap.getWidth(),new RectF(clip));return;
            }
            RectF visible=new RectF(bounds);if(!visible.intersect(0,0,viewportWidth,viewportHeight))return;
            float level=(float)Math.pow(2,Math.ceil(Math.log(Math.max(0.001f,zoom))/Math.log(2)));
            int step=512;
            int firstX=Math.max(0,(int)Math.floor((visible.left-bounds.left)/zoom*level/step)*step);
            int firstY=Math.max(0,(int)Math.floor((visible.top-bounds.top)/zoom*level/step)*step);
            int endX=(int)Math.ceil((visible.right-bounds.left)/zoom*level),endY=(int)Math.ceil((visible.bottom-bounds.top)/zoom*level);
            int pageWidth=(int)Math.ceil(page.displayWidthPt()*level),pageHeight=(int)Math.ceil(page.displayHeightPt()*level);
            for(int y=firstY;y<endY;y+=step)for(int x=firstX;x<endX;x+=step) {
                int w=Math.min(step+1,pageWidth-x),h=Math.min(step+1,pageHeight-y);if(w<=0 || h<=0)continue;
                String key=page.srcPath+"|"+page.srcPageIdx+"|"+page.rot+"|"+level+"|"+x+"|"+y;
                Bitmap tile=tiles.get(key);
                if(tile==null){tile=PdfHost.renderTile(page.srcPath,page.srcPageIdx,page.rot,level,x,y,w,h);if(tile==null)continue;tiles.put(key,tile);}
                float factor=zoom/level;
                canvas.drawBitmap(tile,null,new RectF(bounds.left+x*factor,bounds.top+y*factor,bounds.left+(x+w)*factor,bounds.top+(y+h)*factor),paint);
            }
        }finally{canvas.restore();}
    }
}
