package com.samsung.oneui.viewer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.LruCache;

/** Small GPU textures for large originals. The editable bitmap never gets resized. */
final class ImageTiles {
    private Bitmap source, preview;
    private final LruCache<Long,Bitmap> tiles=new LruCache<Long,Bitmap>(16*1024*1024) {
        @Override protected int sizeOf(Long key,Bitmap value){return value.getAllocationByteCount();}
        // Canvas display lists may still reference a texture after eviction. Let Android
        // reclaim it once the last display list releases it instead of recycling it here.
    };
    void clear(){source=null;preview=null;tiles.evictAll();}
    void draw(Canvas canvas,Bitmap bitmap,Paint paint,float zoom,RectF visible) {
        if(source!=bitmap){clear();source=bitmap;}
        int width=bitmap.getWidth(),height=bitmap.getHeight();
        int texture=Math.min(4096,Math.min(canvas.getMaximumBitmapWidth(),canvas.getMaximumBitmapHeight()));
        if(width<=texture && height<=texture && bitmap.getAllocationByteCount()<=64*1024*1024){canvas.drawBitmap(bitmap,null,new RectF(0,0,width,height),paint);return;}
        float shown=Math.max(width,height)*zoom;
        int previewSide=Math.min(texture,Math.max(512,(int)Math.ceil(shown)));
        if(shown<=texture && zoom<1) {
            if(preview==null || Math.max(preview.getWidth(),preview.getHeight())<previewSide) {
                try {
                    float scale=(float)previewSide/Math.max(width,height);
                    preview=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(width*scale)),Math.max(1,Math.round(height*scale)),true);
                }catch(OutOfMemoryError memory){preview=null;tiles.evictAll();}
            }
            if(preview!=null){canvas.drawBitmap(preview,null,new RectF(0,0,width,height),paint);return;}
        }
        int step=Math.max(128,Math.min(1024,texture-2));
        int left=Math.max(0,(int)Math.floor(visible.left/step)*step),top=Math.max(0,(int)Math.floor(visible.top/step)*step);
        int right=Math.min(width,(int)Math.ceil(visible.right)),bottom=Math.min(height,(int)Math.ceil(visible.bottom));
        for(int y=top;y<bottom;y+=step)for(int x=left;x<right;x+=step) {
            long key=((long)y<<32)|(x&0xffffffffL);Bitmap tile=tiles.get(key);
            // Filter with neighbouring pixels, but clip each tile to its own area so
            // semi-transparent edges are never painted twice.
            int tileX=Math.max(0,x-1),tileY=Math.max(0,y-1);
            int w=Math.min(step+2,width-tileX),h=Math.min(step+2,height-tileY);
            if(tile==null){tile=Bitmap.createBitmap(bitmap,tileX,tileY,w,h);tiles.put(key,tile);}
            canvas.save();canvas.clipRect(x,y,Math.min(width,x+step),Math.min(height,y+step));
            canvas.drawBitmap(tile,null,new RectF(tileX,tileY,tileX+w,tileY+h),paint);canvas.restore();
        }
    }
}
