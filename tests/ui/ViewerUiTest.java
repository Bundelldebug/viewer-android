package com.samsung.oneui.viewer;

import android.app.Activity;
import android.app.Application;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.DragEvent;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, manifest=Config.NONE, application=ViewerUiTest.TestApplication.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ViewerUiTest {
    public static class ImageProvider extends ContentProvider {
        File file;
        @Override public boolean onCreate(){return true;}
        @Override public String getType(Uri uri){return "image/png";}
        @Override public Cursor query(Uri uri,String[] columns,String selection,String[] args,String sort){MatrixCursor cursor=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});cursor.addRow(new Object[]{"image-from-files"});return cursor;}
        @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws java.io.FileNotFoundException{return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);}
        @Override public Uri insert(Uri uri,ContentValues values){return null;}
        @Override public int update(Uri uri,ContentValues values,String where,String[] args){return 0;}
        @Override public int delete(Uri uri,String where,String[] args){return 0;}
    }
    public static class TestApplication extends Application {
        @Override public void onCreate() {
            super.onCreate();
            try {
                Method add=getAssets().getClass().getMethod("addAssetPath",String.class);
                add.invoke(getAssets(),System.getProperty("viewer.assets"));
            } catch(Exception e) { throw new RuntimeException(e); }
            getSharedPreferences("samsung_viewer_native_prefs",Context.MODE_PRIVATE).edit().clear().putBoolean("system_theme",false).putBoolean("dark_theme",false).commit();
        }
    }
    private ActivityController<MainActivity> launch(int width,int height) {
        RuntimeEnvironment.setQualifiers("w"+width+"dp-h"+height+"dp-mdpi");
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class,new Intent(Intent.ACTION_MAIN)).setup().visible();
        layout(controller.get(),width,height);return controller;
    }
    private void layout(Activity activity,int width,int height) {
        View decor=activity.getWindow().getDecorView();
        for(int i=0;i<4;i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            decor.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
            decor.layout(0,0,width,height);
        }
    }
    private View tagged(Activity activity,String tag) { return activity.getWindow().getDecorView().findViewWithTag(tag); }
    private static Object field(Object target,String name)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void setField(Object target,String name,Object value)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static Object call(Object target,String name,Class<?>[] types,Object...args)throws Exception {Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);}
    private void screenshot(Activity activity,String name,int width,int height)throws Exception {
        Bitmap image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        activity.getWindow().getDecorView().draw(new Canvas(image));
        try(FileOutputStream out=new FileOutputStream(new File(System.getProperty("viewer.output"),name+".png"))) {assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}
        image.recycle();
    }
    private void awaitFiles(MainActivity a)throws Exception {
        for(int i=0;i<3;i++) {
            ((ExecutorService)field(a,"filePool")).submit(() -> {}).get(10,TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ((ExecutorService)field(a,"bgPool")).submit(() -> {}).get(10,TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(30);
        }
    }
    private DragEvent drag(int action,ClipData clip)throws Exception {
        for(Method method:DragEvent.class.getDeclaredMethods())if(method.getName().equals("obtain") && method.getParameterCount()==11){method.setAccessible(true);return (DragEvent)method.invoke(null,action,120f,180f,0f,0f,null,clip.getDescription(),clip,null,null,false);}
        throw new AssertionError("DragEvent factory missing");
    }
    @Test public void coldStartPhoneHasEmptyWorkspaceAndNativeOpen()throws Exception {
        ActivityController<MainActivity> c=launch(360,800);MainActivity a=c.get();
        assertEquals("",field(a,"currentPath"));
        assertNotNull(tagged(a,"welcome-card"));
        assertEquals("Samsung Viewer",((TextView)tagged(a,"welcome-title")).getText().toString());
        assertNotNull(((ImageView)tagged(a,"welcome-icon")).getDrawable());
        ViewGroup toolbar=(ViewGroup)tagged(a,"welcome-toolbar");assertFalse(toolbar.getChildAt(2).isEnabled());
        assertNull(Shadows.shadowOf(a).getNextStartedActivity());
        screenshot(a,"phone-360",360,800);
        tagged(a,"welcome-open").performClick();
        Intent picker=Shadows.shadowOf(a).getNextStartedActivity();assertNotNull(picker);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT,picker.getAction());assertTrue(picker.hasCategory(Intent.CATEGORY_OPENABLE));
        assertTrue(picker.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false));
        assertEquals("*/*",picker.getType());
        toolbar.getChildAt(1).performClick();assertEquals(Intent.ACTION_OPEN_DOCUMENT,Shadows.shadowOf(a).getNextStartedActivity().getAction());
        c.pause().stop().destroy();
    }
    @Test public void cardsAndActionsFitCompactFoldableTabletAndLandscape()throws Exception {
        int[][] sizes={{320,720},{600,900},{840,980},{1280,900},{800,360}};
        for(int[] size:sizes) {
            ActivityController<MainActivity> c=launch(size[0],size[1]);MainActivity a=c.get();
            View card=tagged(a,"welcome-card"),actions=tagged(a,"welcome-actions");
            assertTrue(card.getWidth()>0);assertTrue(card.getWidth()<=560);
            for(String tag:new String[]{"welcome-open","welcome-paste","welcome-capture"}) {
                View v=tagged(a,tag);assertTrue(tag+" width",v.getWidth()>0);
                assertTrue(tag+" touch height",v.getHeight()>=48);assertTrue(tag+" left",v.getLeft()>=0);
                assertTrue(tag+" right",v.getRight()<=actions.getWidth());assertTrue(tag+" bottom",v.getBottom()<=actions.getHeight());
                TextView text=(TextView)v;assertNotNull(text.getLayout());assertEquals(0,text.getLayout().getEllipsisCount(text.getLineCount()-1));
            }
            if(size[0]==800) {Rect shown=new Rect();assertTrue(tagged(a,"welcome-capture").getGlobalVisibleRect(shown));assertEquals(tagged(a,"welcome-capture").getHeight(),shown.height());}
            if(size[0]==840 || size[0]==800) screenshot(a,"workspace-"+size[0]+"x"+size[1],size[0],size[1]);
            c.pause().stop().destroy();
        }
    }
    @Test public void droppedFileOpensThenClosesBackToWorkspace()throws Exception {
        ActivityController<MainActivity> c=launch(360,800);MainActivity a=c.get();
        File file=new File(a.getCacheDir(),"drop.txt");java.nio.file.Files.writeString(file.toPath(),"드롭한 문서");
        ClipData clip=new ClipData("files",new String[]{"text/plain"},new ClipData.Item(Uri.fromFile(file)));
        View root=tagged(a,"welcome-root");
        assertTrue(root.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_STARTED,clip)));
        root.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_LOCATION,clip));
        assertEquals(View.VISIBLE,tagged(a,"file-drop-overlay").getVisibility());
        layout(a,360,800);assertTrue(tagged(a,"file-drop-overlay").getHeight()>0);
        screenshot(a,"phone-drop",360,800);
        assertTrue(root.dispatchDragEvent(drag(DragEvent.ACTION_DROP,clip)));awaitFiles(a);layout(a,360,800);
        assertEquals(file.getAbsolutePath(),field(a,"currentPath"));assertNull(tagged(a,"welcome-card"));
        assertTrue(((java.util.List<?>)field(a,"dropPermissions")).isEmpty());
        call(a,"closeCurrentFile",new Class<?>[0]);layout(a,360,800);assertEquals("",field(a,"currentPath"));assertNotNull(tagged(a,"welcome-card"));
        c.pause().stop().destroy();
    }
    @Test public void cancellingChangedDocumentReleasesPendingDrop()throws Exception {
        ActivityController<MainActivity> c=launch(360,800);MainActivity a=c.get();setField(a,"isModified",true);
        AtomicInteger released=new AtomicInteger();
        call(a,"openUris",new Class<?>[]{java.util.List.class,int.class,Runnable.class},Arrays.asList(Uri.parse("content://tests/one.png")),Intent.FLAG_GRANT_READ_URI_PERMISSION,(Runnable)released::incrementAndGet);
        assertEquals(0,released.get());AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,released.get());
        c.pause().stop().destroy();assertEquals(1,released.get());
    }
    @Test public void foldHingeDoesNotCrossWelcomeCard()throws Exception {
        ActivityController<MainActivity> c=launch(840,980);MainActivity a=c.get();
        setField(a,"foldBounds",new Rect(410,0,430,980));call(a,"buildCompleteNativeUI",new Class<?>[0]);layout(a,840,980);
        View card=tagged(a,"welcome-card");int[] at=new int[2];card.getLocationInWindow(at);
        assertTrue(at[0]+card.getWidth()<=410 || at[0]>=430);screenshot(a,"fold-hinge",840,980);
        c.pause().stop().destroy();
    }
    @Test public void originalImagePixelsSurviveDecodeEditsAndSave()throws Exception {
        ActivityController<MainActivity> c=launch(360,800);MainActivity a=c.get();
        Bitmap fixture=Bitmap.createBitmap(5200,600,Bitmap.Config.ARGB_8888);fixture.eraseColor(Color.WHITE);
        fixture.setPixel(4800,300,Color.RED);fixture.setPixel(4801,300,Color.BLUE);
        File file=new File(a.getCacheDir(),"original-5200.png");try(FileOutputStream out=new FileOutputStream(file)){fixture.compress(Bitmap.CompressFormat.PNG,100,out);}fixture.recycle();
        Bitmap original=ImageEditOps.decodeOriginal(file.getAbsolutePath());assertEquals(5200,original.getWidth());assertEquals(600,original.getHeight());
        assertEquals(Color.RED,original.getPixel(4800,300));assertEquals(Color.BLUE,original.getPixel(4801,300));
        Bitmap rotated=ImageEditOps.rotate(original,90);assertEquals(600,rotated.getWidth());assertEquals(5200,rotated.getHeight());rotated.recycle();original.recycle();
        a.openFileAtPath(file.getAbsolutePath());
        for(int i=0;i<100 && ((StageCanvasView)field(a,"stageCanvasView")).getImageBitmap()==null;i++){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(20);}
        layout(a,360,800);StageCanvasView stage=(StageCanvasView)field(a,"stageCanvasView");assertNotNull(stage.getImageBitmap());assertEquals(5200,stage.getImageBitmap().getWidth());
        File saved=ViewerExports.snapshot(a,file.getAbsolutePath(),"image",stage,null,100,300);
        Bitmap roundTrip=BitmapFactory.decodeFile(saved.getAbsolutePath());assertEquals(5200,roundTrip.getWidth());assertEquals(Color.RED,roundTrip.getPixel(4800,300));assertEquals(Color.BLUE,roundTrip.getPixel(4801,300));roundTrip.recycle();
        File copied=ViewerExports.snapshot(a,file.getAbsolutePath(),"image",stage,null,92,150,false);assertArrayEquals(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.file.Files.readAllBytes(copied.toPath()));
        screenshot(a,"phone-image",360,800);
        assertToolbarVisible(a);
        call(a,"rotateCurrentImage",new Class<?>[]{int.class},90);assertEquals(5200,stage.getImageBitmap().getHeight());
        call(a,"undoImageEdit",new Class<?>[0]);assertEquals(5200,stage.getImageBitmap().getWidth());assertEquals(Color.RED,stage.getImageBitmap().getPixel(4800,300));
        c.pause().stop().destroy();
    }
    private void assertToolbarVisible(MainActivity a) {
        ViewGroup toolbar=(ViewGroup)tagged(a,"editor-toolbar");assertNotNull(toolbar);assertEquals(5,toolbar.getChildCount());
        for(int i=0;i<toolbar.getChildCount();i++){View child=toolbar.getChildAt(i);Rect visible=new Rect();assertTrue(child.getGlobalVisibleRect(visible));assertEquals(child.getHeight(),visible.height());assertEquals(child.getWidth(),visible.width());assertTrue(child.getWidth()>=48*a.getResources().getDisplayMetrics().density);}
    }
    @Test public void contentUriWithoutExtensionUsesProviderMimeAndOriginalBytes()throws Exception {
        ActivityController<MainActivity> c=launch(360,800);MainActivity a=c.get();
        ImageProvider provider=new ImageProvider();ProviderInfo info=new ProviderInfo();info.authority="viewer.tests";provider.attachInfo(a,info);
        provider.file=new File(a.getCacheDir(),"provider.png");Bitmap image=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);
        try(FileOutputStream out=new FileOutputStream(provider.file)){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();
        ShadowContentResolver.registerProviderInternal(info.authority,provider);
        Uri uri=Uri.parse("content://viewer.tests/image/42");File imported=((DocumentStore)field(a,"documents")).importFile(uri);
        assertTrue(imported.getName().endsWith(".png"));assertArrayEquals(java.nio.file.Files.readAllBytes(provider.file.toPath()),java.nio.file.Files.readAllBytes(imported.toPath()));
        c.pause().stop().destroy();
    }
    @Test public void compactEditorToolbarFitsNarrowPhoneAndTablet()throws Exception {
        for(int width:new int[]{320,600,840,1280}) {
            ActivityController<MainActivity> c=launch(width,900);MainActivity a=c.get();File file=new File(a.getCacheDir(),"document.txt");java.nio.file.Files.writeString(file.toPath(),"모바일 문서");
            a.openFileAtPath(file.getAbsolutePath());layout(a,width,900);assertToolbarVisible(a);
            if(width>=840){Rect toolbar=new Rect(),stage=new Rect();tagged(a,"editor-toolbar").getGlobalVisibleRect(toolbar);((View)field(a,"stageHostFrame")).getGlobalVisibleRect(stage);assertTrue("Document starts below toolbar",stage.top>=toolbar.bottom);}
            if(width==320 || width==840)screenshot(a,"editor-"+width,width,900);
            c.pause().stop().destroy();
        }
    }
    @Test public void darkWelcomeAndLargeTextRemainUsable()throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("samsung_viewer_native_prefs",Context.MODE_PRIVATE).edit().putBoolean("dark_theme",true).commit();
        ActivityController<MainActivity> c=launch(360,800);MainActivity a=c.get();
        android.content.res.Configuration config=new android.content.res.Configuration(a.getResources().getConfiguration());config.fontScale=1.4f;a.getResources().updateConfiguration(config,a.getResources().getDisplayMetrics());
        call(a,"buildCompleteNativeUI",new Class<?>[0]);layout(a,360,800);
        View button=tagged(a,"welcome-open");Rect visible=new Rect();assertTrue(button.getGlobalVisibleRect(visible));assertEquals(button.getHeight(),visible.height());
        assertTrue((Boolean)field(a,"isDarkTheme"));screenshot(a,"phone-dark-large-text",360,800);c.pause().stop().destroy();
    }
    @Test public void largeTextureTilesKeepAdjacentPixelDetail()throws Exception {
        Bitmap original=Bitmap.createBitmap(9000,32,Bitmap.Config.ARGB_8888);original.eraseColor(Color.WHITE);
        original.setPixel(8191,10,Color.RED);original.setPixel(8192,10,Color.BLUE);
        Bitmap same=ImageEditOps.resize(original,9000,32);assertEquals(9000,same.getWidth());if(same!=original)same.recycle();
        Bitmap viewport=Bitmap.createBitmap(40,32,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(viewport);canvas.translate(-8180,0);
        new ImageTiles().draw(canvas,original,new Paint(Paint.FILTER_BITMAP_FLAG),1f,new RectF(8180,0,8220,32));
        assertEquals(Color.RED,viewport.getPixel(11,10));assertEquals(Color.BLUE,viewport.getPixel(12,10));original.recycle();viewport.recycle();
    }
    @Test public void pdfExportHonoursRequestedWidthAndRotatedDpi()throws Exception {
        MarkupModel.PdfPageItem page=new MarkupModel.PdfPageItem("blank","blank",null,0,1000,500);
        assertEquals(4167,ViewerExports.pdfWidth(page,300));Bitmap normal=page.renderFlattened(4167);assertEquals(4167,normal.getWidth());normal.recycle();
        page.rot=90;page.invalidateCache();Bitmap rotated=page.renderFlattened(3000);assertEquals(3000,rotated.getWidth());assertEquals(6000,rotated.getHeight());rotated.recycle();
    }
    @Test public void imagePixelsUseExplicitBoundsAtHighDisplayDensity()throws Exception {
        Bitmap original=Bitmap.createBitmap(20,10,Bitmap.Config.ARGB_8888);original.setDensity(160);original.eraseColor(Color.RED);
        Bitmap target=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(target);canvas.setDensity(480);
        new ImageTiles().draw(canvas,original,new Paint(),1f,new RectF(0,0,20,10));
        assertEquals(Color.RED,target.getPixel(19,9));assertEquals(Color.TRANSPARENT,target.getPixel(20,9));original.recycle();target.recycle();
    }
    @Test public void highDensityPhoneKeepsNativeSizingAndButtons()throws Exception {
        RuntimeEnvironment.setQualifiers("w360dp-h800dp-xxhdpi");
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class,new Intent(Intent.ACTION_MAIN)).setup().visible();MainActivity a=c.get();layout(a,1080,2400);
        assertEquals(3f,a.getResources().getDisplayMetrics().density,.01f);
        View card=tagged(a,"welcome-card");assertTrue(card.getWidth()<=1080);assertTrue(tagged(a,"welcome-open").getHeight()>=144);
        screenshot(a,"phone-1080x2400",1080,2400);c.pause().stop().destroy();
    }
    @Test public void pdfTileRotationMapsSourceIntoDisplayBounds() {
        int[][] rotations={{0,0,0},{90,1000,0},{180,600,1000},{270,0,600}};
        for(int[] r:rotations){float[] origin={0,0};PdfHost.tileMatrix(600,1000,r[0],2,15,25).mapPoints(origin);assertEquals(r[1]*2-15,origin[0],.01f);assertEquals(r[2]*2-25,origin[1],.01f);}
    }
    @Test public void transparentTileBoundaryIsPaintedOnce() {
        Bitmap original=Bitmap.createBitmap(9000,32,Bitmap.Config.ARGB_8888);original.eraseColor(0x80FF0000);
        Bitmap viewport=Bitmap.createBitmap(40,32,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(viewport);canvas.translate(-8180,0);
        new ImageTiles().draw(canvas,original,new Paint(Paint.FILTER_BITMAP_FLAG),1f,new RectF(8180,0,8220,32));
        for(int x=0;x<40;x++)assertEquals(128,Color.alpha(viewport.getPixel(x,10)));original.recycle();viewport.recycle();
    }
    @Test public void fullQualityPdfKeepsPixelColoursLosslessly()throws Exception {
        ActivityController<MainActivity> c=launch(360,800);MainActivity a=c.get();File file=new File(a.getCacheDir(),"lossless.pdf");
        Bitmap source=Bitmap.createBitmap(4,2,Bitmap.Config.ARGB_8888);source.eraseColor(Color.WHITE);source.setPixel(1,0,Color.RED);source.setPixel(2,0,Color.BLUE);
        ViewerExports.require(PdfHost.begin("quality-test",file.getAbsolutePath()),"ok");ViewerExports.require(PdfHost.addPageBitmap("quality-test",4,2,source,100),"ok");ViewerExports.require(PdfHost.end("quality-test","lossless.pdf"),"ok");
        try(com.tom_roush.pdfbox.pdmodel.PDDocument document=com.tom_roush.pdfbox.pdmodel.PDDocument.load(file)) {
            com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject image=(com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject)document.getPage(0).getResources().getXObject(com.tom_roush.pdfbox.cos.COSName.getPDFName("Im0"));
            assertEquals(4,image.getWidth());assertEquals(2,image.getHeight());
            // Validate PDF RGB values independently of the host's native bitmap byte order.
            try(java.io.InputStream decoded=image.getCOSObject().createInputStream()) {
                byte[] rgb=decoded.readAllBytes();assertEquals(24,rgb.length);
                assertArrayEquals(new byte[]{(byte)255,0,0},Arrays.copyOfRange(rgb,3,6));
                assertArrayEquals(new byte[]{0,0,(byte)255},Arrays.copyOfRange(rgb,6,9));
            }
        }
        java.nio.file.Files.copy(file.toPath(),new File(System.getProperty("viewer.output"),"lossless.pdf").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        source.recycle();c.pause().stop().destroy();
    }
    @Test public void fileDropKeepsLocalUrisAndReleasesExactlyOnce()throws Exception {
        Uri first=Uri.parse("content://tests/one.png"),second=Uri.parse("content://tests/two.pdf");
        ClipData clip=new ClipData("files",new String[]{"image/png","application/pdf"},new ClipData.Item(first));
        clip.addItem(new ClipData.Item(second));clip.addItem(new ClipData.Item(first));clip.addItem(new ClipData.Item("plain text"));clip.addItem(new ClipData.Item(Uri.parse("https://example.com/no.png")));
        assertTrue(FileDrop.accepts(clip.getDescription()));assertFalse(FileDrop.accepts(null));
        assertEquals(Arrays.asList(first,second),FileDrop.collect(clip));
        assertEquals(Arrays.asList(Uri.parse("file:///tmp/test.txt")),FileDrop.localUris(Arrays.asList(null,Uri.parse("file:///tmp/test.txt"))));
        AtomicInteger releases=new AtomicInteger();Runnable release=FileDrop.once(releases::incrementAndGet);
        Thread one=new Thread(release),two=new Thread(release);one.start();two.start();one.join();two.join();release.run();assertEquals(1,releases.get());
    }
    @Test public void captureDenialNeverStartsProjectionService()throws Exception {
        ActivityController<MainActivity> c=launch(360,800);MainActivity a=c.get();
        tagged(a,"welcome-capture").performClick();
        assertNotNull(Shadows.shadowOf(a).getNextStartedActivity());
        assertNull(Shadows.shadowOf(a).getNextStartedService());
        call(a,"onActivityResult",new Class<?>[]{int.class,int.class,Intent.class},1006,Activity.RESULT_CANCELED,null);
        assertFalse((Boolean)field(a,"capturePending"));assertNull(Shadows.shadowOf(a).getNextStartedService());
        c.pause().stop().destroy();
    }
}
