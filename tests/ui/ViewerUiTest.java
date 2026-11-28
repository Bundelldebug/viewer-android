package com.samsung.oneui.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ClipData;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.DragEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

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

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = ViewerUiTest.TestApplication.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ViewerUiTest {

    public static class ImageProvider extends ContentProvider {
        File file;
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return "image/png"; }
        @Override public Cursor query(Uri uri, String[] columns, String selection, String[] args, String sort) {
            MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});
            cursor.addRow(new Object[]{"image-from-files"});
            return cursor;
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int update(Uri uri, ContentValues values, String where, String[] args) { return 0; }
        @Override public int delete(Uri uri, String where, String[] args) { return 0; }
    }

    public static class TestApplication extends Application {
        @Override public void onCreate() {
            super.onCreate();
            try {
                Method add = getAssets().getClass().getMethod("addAssetPath", String.class);
                add.invoke(getAssets(), System.getProperty("viewer.assets"));
            } catch (Exception e) { throw new RuntimeException(e); }
            getSharedPreferences("samsung_viewer_native_prefs", Context.MODE_PRIVATE)
                    .edit().clear().putString("theme", "light").putBoolean("system_theme", false).putBoolean("dark_theme", false).commit();
        }
    }

    private ActivityController<MainActivity> launch(int width, int height) {
        RuntimeEnvironment.setQualifiers("w" + width + "dp-h" + height + "dp-mdpi");
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, new Intent(Intent.ACTION_MAIN)).setup().visible();
        layout(controller.get(), width, height);
        return controller;
    }

    private void layout(Activity activity, int width, int height) {
        View decor = activity.getWindow().getDecorView();
        for (int i = 0; i < 4; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, width, height);
        }
    }

    private View tagged(Activity activity, String tag) { return activity.getWindow().getDecorView().findViewWithTag(tag); }
    private static Object field(Object target, String name) throws Exception { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
    private static void setField(Object target, String name, Object value) throws Exception { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(target, args); }

    private void screenshot(Activity activity, String name, int width, int height) throws Exception {
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        activity.getWindow().getDecorView().draw(new Canvas(image));
        try (FileOutputStream out = new FileOutputStream(new File(System.getProperty("viewer.output"), name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
        image.recycle();
    }

    private void awaitFiles(MainActivity a) throws Exception {
        for (int i = 0; i < 3; i++) {
            ((ExecutorService) field(a, "filePool")).submit(() -> {}).get(10, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ((ExecutorService) field(a, "bgPool")).submit(() -> {}).get(10, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(30);
        }
    }

    private DragEvent drag(int action, ClipData clip) throws Exception {
        for (Method method : DragEvent.class.getDeclaredMethods()) {
            if (method.getName().equals("obtain") && method.getParameterCount() == 11) {
                method.setAccessible(true);
                return (DragEvent) method.invoke(null, action, 120f, 180f, 0f, 0f, null, clip.getDescription(), clip, null, null, false);
            }
        }
        throw new AssertionError("DragEvent factory missing");
    }

    @Test public void hwp5CharShapeNeverPaintsBlackBackgroundAndDocHasSafeInsets() throws Exception {
        // Verify HWP 5.0 HWPTAG_CHAR_SHAPE with underlineColor=0x00000000 (offset 56) & shadeColor=0xFFFFFFFF (offset 60)
        // does NOT emit background-color:#000000!
        java.lang.reflect.Method parseDocInfo = HwpRich.class.getDeclaredMethod("colorRefToHex", int.class, boolean.class, boolean.class);
        parseDocInfo.setAccessible(true);
        assertNull(parseDocInfo.invoke(null, 0x00000000, true, true));
        assertNull(parseDocInfo.invoke(null, 0xFFFFFFFF, true, true));
        assertEquals("#E8F0FE", parseDocInfo.invoke(null, 0x00FEF0E8, true, true));

        ActivityController<MainActivity> c = launch(360, 800);
        MainActivity a = c.get();
        a.applyAllPageInsets();
        assertTrue("Document top safe inset clears header", a.nativeDocView.getSafeTopPx() >= 60);
        assertTrue("Document bottom safe inset clears floating dock", a.nativeDocView.getSafeBottomPx() >= 80);
        c.pause().stop().destroy();
    }

    @Test public void coldStartPhoneHasEmptyWorkspaceAndNativeOpen() throws Exception {
        ActivityController<MainActivity> c = launch(360, 800);
        MainActivity a = c.get();
        assertEquals("", field(a, "currentPath"));
        assertNotNull(tagged(a, "welcome-card"));
        assertEquals("뷰어", ((TextView) tagged(a, "welcome-title")).getText().toString());
        assertNotNull(((ImageView) tagged(a, "welcome-icon")).getDrawable());
        assertNotNull(a.dock);
        assertNotNull(a.header);
        assertFalse(a.dock.pill.isEmpty());
        assertTrue("search field only exists while searching", a.dock.circle.isEmpty());
        assertNull(Shadows.shadowOf(a).getNextStartedActivity());
        screenshot(a, "phone-360", 360, 800);
        ((View) tagged(a, "welcome-icon").getParent()).performClick();
        Intent picker = Shadows.shadowOf(a).getNextStartedActivity();
        assertNotNull(picker);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.getAction());
        assertTrue(picker.hasCategory(Intent.CATEGORY_OPENABLE));
        assertTrue(picker.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
        assertEquals("*/*", picker.getType());
        c.pause().stop().destroy();
    }

    @Test public void prismLiquidGlassDockLensDragAndActiveAccentClipping() throws Exception {
        ActivityController<MainActivity> c = launch(360, 800);
        MainActivity a = c.get();
        // Verify AGSL Liquid Glass shader source has refraction, chroma, smooth-union, touch glow & magnification
        assertTrue(Glass.SHADER.contains("outerRefr"));
        assertTrue(Glass.SHADER.contains("chroma"));
        assertTrue(Glass.SHADER.contains("smoothK"));
        assertTrue(Glass.SHADER.contains("press"));
        assertTrue(Glass.SHADER.contains("magC"));

        // Verify lens starts at tab 0, then dragging across tabs lifts/expands the lens and targets slot 2
        assertEquals(0, a.dock.lensTargetSlot());
        RectF restLens = new RectF(a.dock.lens);
        a.dock.previewLensDrag(2.0f, 1.0f);
        layout(a, 360, 800);
        assertEquals(2, a.dock.lensTargetSlot());
        assertTrue("Lifted lens expands in height", a.dock.lens.height() > restLens.height());
        assertTrue("Lifted lens expands in width", a.dock.lens.width() > restLens.width());
        screenshot(a, "phone-dock-drag-lens", 360, 800);

        // Verify touch down -> move -> up selects the dragged tab cleanly
        a.dock.previewLensDrag(0f, 0f);
        layout(a, 360, 800);
        float startX = a.dock.pill.left + 36f, y = a.dock.pill.centerY();
        float endX = a.dock.pill.right - 36f;
        assertTrue(a.dock.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, startX, y, 0)));
        assertTrue(a.dock.dispatchTouchEvent(MotionEvent.obtain(0, 50, MotionEvent.ACTION_MOVE, endX, y, 0)));
        assertTrue(a.dock.dispatchTouchEvent(MotionEvent.obtain(0, 100, MotionEvent.ACTION_UP, endX, y, 0)));
        assertEquals(2, a.dock.selected);
        c.pause().stop().destroy();
    }

    @Test public void glassMenuDropMorphOpensAndDismissesCleanly() throws Exception {
        ActivityController<MainActivity> c = launch(360, 800);
        MainActivity a = c.get();
        GlassMenu menu = a.showOverflowGlassMenu(a.header.pillInRoot());
        layout(a, 360, 800);
        menu.snapProgress(1.0f);
        layout(a, 360, 800);
        screenshot(a, "phone-glass-menu", 360, 800);
        assertTrue(GlassMenu.dismissAny(a.root));
        c.pause().stop().destroy();
    }

    @Test public void settingsScreenShowsPrismGlassAAndOneUiGStyles() throws Exception {
        ActivityController<MainActivity> c = launch(360, 800);
        MainActivity a = c.get();
        a.switchTab(3);
        layout(a, 360, 800);
        screenshot(a, "phone-settings-a", 360, 800);

        MainActivity.prefs(a).edit().putString("app_style", "oneui").putString("theme", "dark").apply();
        a.recreateChromeForTheme();
        a.switchTab(3);
        layout(a, 360, 800);
        assertTrue(Ui.gStyle);
        assertTrue(Ui.dark);
        screenshot(a, "phone-settings-g-dark", 360, 800);

        // Verify Markdown release notes formatter removes raw syntax
        CharSequence notes = Updater.formatReleaseNotes("## 9.5.4\n- **글래스 굴절** 및 `AGSL` 렌더링 적용\n- [GitHub](https://github.com)");
        String s = notes.toString();
        assertFalse(s.contains("##"));
        assertFalse(s.contains("**"));
        assertFalse(s.contains("`"));
        assertTrue(s.contains("글래스 굴절"));
        c.pause().stop().destroy();
    }

    @Test public void searchBarTextIsVerticallyCentered() throws Exception {
        ActivityController<MainActivity> c = launch(360, 800);
        MainActivity a = c.get();
        a.onSearch();
        a.dock.search = 1f;
        a.dock.field.setVisibility(View.VISIBLE);
        a.dock.field.setText("문서 검색 정렬 확인");
        layout(a, 360, 800);
        int expectedH = Math.round(a.dock.circle.height());
        assertEquals(expectedH, a.dock.field.getMeasuredHeight());
        assertEquals(expectedH, a.dock.field.getHeight());
        assertEquals(android.view.Gravity.CENTER_VERTICAL, a.dock.field.getGravity() & android.view.Gravity.VERTICAL_GRAVITY_MASK);
        screenshot(a, "phone-search-bar", 360, 800);
        c.pause().stop().destroy();
    }

    @Test public void cardsAndActionsFitCompactFoldableTabletAndLandscape() throws Exception {
        int[][] sizes = {{320, 720}, {600, 900}, {840, 980}, {1280, 900}, {800, 360}};
        for (int[] size : sizes) {
            ActivityController<MainActivity> c = launch(size[0], size[1]);
            MainActivity a = c.get();
            View card = tagged(a, "welcome-card"), actions = tagged(a, "welcome-actions");
            assertTrue(card.getWidth() > 0);
            assertTrue(card.getWidth() <= size[0]);
            for (String tag : new String[]{"welcome-new", "welcome-scan"}) {
                View v = tagged(a, tag);
                assertTrue(tag + " width", v.getWidth() > 0);
                assertTrue(tag + " touch height", v.getHeight() >= 48);
                assertTrue(tag + " left", v.getLeft() >= 0);
                assertTrue(tag + " right", v.getRight() <= actions.getWidth());
                assertTrue(tag + " bottom", v.getBottom() <= actions.getHeight());
                TextView text = (TextView) v;
                assertNotNull(text.getLayout());
                assertEquals(0, text.getLayout().getEllipsisCount(text.getLineCount() - 1));
            }
            if (size[0] == 840) {
                assertTrue("Foldable uses vertical glass rail", a.dock.rail);
                screenshot(a, "fold-rail-840", 840, 980);
            }
            if (size[0] == 1280) {
                assertTrue("Tablet uses top glass tab pill", a.dock.tablet);
                a.openSidebar(false);
                layout(a, 1280, 900);
                screenshot(a, "tablet-sidebar-1280", 1280, 900);
                a.closeSidebar();
            }
            if (size[0] == 800) {
                screenshot(a, "workspace-800x360", 800, 360);
            }
            c.pause().stop().destroy();
        }
    }

    @Test public void droppedFileOpensThenClosesBackToWorkspace() throws Exception {
        ActivityController<MainActivity> c = launch(360, 800);
        MainActivity a = c.get();
        File file = new File(a.getCacheDir(), "drop.txt");
        java.nio.file.Files.writeString(file.toPath(), "드롭한 문서");
        ClipData clip = new ClipData("files", new String[]{"text/plain"}, new ClipData.Item(Uri.fromFile(file)));
        View root = tagged(a, "welcome-root");
        assertTrue(root.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_STARTED, clip)));
        root.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_LOCATION, clip));
        assertEquals(View.VISIBLE, tagged(a, "file-drop-overlay").getVisibility());
        layout(a, 360, 800);
        assertTrue(tagged(a, "file-drop-overlay").getHeight() > 0);
        screenshot(a, "phone-drop", 360, 800);
        assertTrue(root.dispatchDragEvent(drag(DragEvent.ACTION_DROP, clip)));
        awaitFiles(a);
        layout(a, 360, 800);
        assertEquals(file.getAbsolutePath(), field(a, "currentPath"));
        assertEquals(1, a.currentTab());
        assertTrue(a.dock.toolMode());
        assertTrue(((java.util.List<?>) field(a, "dropPermissions")).isEmpty());
        call(a, "closeCurrentFile", new Class<?>[0]);
        layout(a, 360, 800);
        assertEquals("", field(a, "currentPath"));
        assertEquals(0, a.currentTab());
        assertEquals(0, a.dock.selected);
        c.pause().stop().destroy();
    }

    /** 9.5 A1A1: document-browser layout (home card, document tool capsules, markup palette, foldable column). */
    @Test public void previewStyleScreens() throws Exception {
        ActivityController<MainActivity> c = launch(390, 844);
        MainActivity a = c.get();
        File dir = (File) field(a, "sampleDir");
        SampleSeeder.ensureSeeded(dir);
        call(a, "buildCompleteNativeUI", new Class<?>[0]);
        layout(a, 390, 844);
        Thread.sleep(400);
        layout(a, 390, 844);
        assertNotNull(tagged(a, "welcome-card"));
        assertNotNull(tagged(a, "recent-grid"));
        assertTrue("phone home has no header buttons", a.header.pill.isEmpty());
        screenshot(a, "preview-home-phone", 390, 844);

        File pdf = new File(dir, "Viewer_Guide.pdf"), png = new File(dir, "Aurora_Art.png");
        Bitmap pg = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888);
        pg.eraseColor(Color.WHITE);
        Canvas pc = new Canvas(pg);
        android.graphics.Paint pp = new android.graphics.Paint();
        pp.setColor(0xFF2E7D32); pc.drawRect(60, 60, 540, 300, pp);
        pp.setColor(0xFF333333);
        for (int i = 0; i < 10; i++) pc.drawRect(60, 340 + i * 40, 540 - (i % 3) * 80, 356 + i * 40, pp);
        ViewerExports.require(PdfHost.begin("guide", pdf.getAbsolutePath()), "ok");
        ViewerExports.require(PdfHost.addPageBitmap("guide", 600, 800, pg, 90), "ok");
        ViewerExports.require(PdfHost.addPageBitmap("guide", 600, 800, pg, 90), "ok");
        ViewerExports.require(PdfHost.end("guide", "Viewer_Guide.pdf"), "ok");
        Bitmap art = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888);
        Canvas ac = new Canvas(art);
        ac.drawColor(0xFF5B7FD6);
        pp.setColor(0xFFF2B84B); ac.drawCircle(560, 220, 120, pp);
        pp.setColor(0xFF2F4F7F); ac.drawRect(0, 420, 800, 600, pp);
        try (FileOutputStream out = new FileOutputStream(png)) { art.compress(Bitmap.CompressFormat.PNG, 100, out); }
        call(a, "buildCompleteNativeUI", new Class<?>[0]);
        layout(a, 390, 844);
        Thread.sleep(300);
        layout(a, 390, 844);
        screenshot(a, "preview-home-phone", 390, 844);
        a.openFileAtPath(pdf.getAbsolutePath());
        awaitFiles(a);
        layout(a, 390, 844);
        assertEquals(1, a.currentTab());
        assertTrue(a.dock.toolMode());
        assertEquals(6, a.dock.toolCount());
        RectF g0 = a.dock.toolGroupRect(0), g1 = a.dock.toolGroupRect(1);
        assertTrue("two capsules: left and right", g0.right < g1.left);
        Thread.sleep(500);
        layout(a, 390, 844);
        screenshot(a, "preview-pdf-phone", 390, 844);
        assertFalse("PDF sidebar button", a.header.sideRect.isEmpty());
        a.onToolAction(0, new RectF());   // markup on
        layout(a, 390, 844);
        Thread.sleep(500);
        layout(a, 390, 844);
        screenshot(a, "preview-pdf-markup-phone", 390, 844);
        a.onBack();
        awaitFiles(a);
        layout(a, 390, 844);
        assertEquals("back closes the document", "", field(a, "currentPath"));
        assertEquals(0, a.currentTab());

        a.openFileAtPath(png.getAbsolutePath());
        awaitFiles(a);
        layout(a, 390, 844);
        Thread.sleep(500);
        layout(a, 390, 844);
        screenshot(a, "preview-image-phone", 390, 844);
        a.onHeaderButton(Icons.MARKUP, new RectF());
        layout(a, 390, 844);
        Thread.sleep(500);
        layout(a, 390, 844);
        assertTrue(a.markupOn());
        assertEquals(1, a.dock.toolCount());
        screenshot(a, "preview-image-markup-phone", 390, 844);
        call(a, "closeCurrentFile", new Class<?>[0]);
        a.switchTab(4);
        layout(a, 390, 844);
        Thread.sleep(300);
        layout(a, 390, 844);
        screenshot(a, "preview-shared-phone", 390, 844);
        c.pause().stop().destroy();

        // foldable unfolded
        ActivityController<MainActivity> f = launch(1000, 800);
        MainActivity b = f.get();
        call(b, "buildCompleteNativeUI", new Class<?>[0]);
        layout(b, 1000, 800);
        Thread.sleep(400);
        layout(b, 1000, 800);
        assertTrue(b.dock.rail);
        screenshot(b, "preview-home-fold", 1000, 800);
        float px = b.dock.pill.centerX(), py = b.dock.pill.top + b.dock.pill.height() * 0.3f;
        assertTrue(b.dock.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, px, py, 0)));
        for (int i = 0; i < 30; i++) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16)); }
        layout(b, 1000, 800);
        screenshot(b, "preview-fold-tab-labels", 1000, 800);
        b.dock.dispatchTouchEvent(MotionEvent.obtain(0, 600, MotionEvent.ACTION_CANCEL, px, py, 0));
        b.openFileAtPath(pdf.getAbsolutePath());
        awaitFiles(b);
        layout(b, 1000, 800);
        Thread.sleep(500);
        layout(b, 1000, 800);
        assertEquals(3, b.dock.toolCount());
        screenshot(b, "preview-pdf-fold", 1000, 800);
        b.onToolAction(0, new RectF());
        layout(b, 1000, 800);
        Thread.sleep(400);
        layout(b, 1000, 800);
        screenshot(b, "preview-pdf-markup-fold", 1000, 800);
        f.pause().stop().destroy();
    }

    @Test public void cancellingChangedDocumentReleasesPendingDrop() throws Exception {
        ActivityController<MainActivity> c = launch(360, 800);
        MainActivity a = c.get();
        setField(a, "isModified", true);
        AtomicInteger released = new AtomicInteger();
        call(a, "openUris", new Class<?>[]{java.util.List.class, int.class, Runnable.class},
                Arrays.asList(Uri.parse("content://tests/one.png")), Intent.FLAG_GRANT_READ_URI_PERMISSION, (Runnable) released::incrementAndGet);
        assertEquals(0, released.get());
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, released.get());
        c.pause().stop().destroy();
        assertEquals(1, released.get());
    }

    @Test public void foldHingeDoesNotCrossWelcomeCard() throws Exception {
        ActivityController<MainActivity> c = launch(840, 980);
        MainActivity a = c.get();
        setField(a, "foldBounds", new Rect(410, 0, 430, 980));
        call(a, "buildCompleteNativeUI", new Class<?>[0]);
        layout(a, 840, 980);
        View card = tagged(a, "welcome-card");
        int[] at = new int[2];
        card.getLocationInWindow(at);
        assertTrue(at[0] + card.getWidth() <= 410 || at[0] >= 430);
        screenshot(a, "fold-hinge", 840, 980);
        c.pause().stop().destroy();
    }

    @Test public void originalImagePixelsSurviveDecodeEditsAndSave() throws Exception {
        ActivityController<MainActivity> c = launch(360, 800);
        MainActivity a = c.get();
        Bitmap fixture = Bitmap.createBitmap(5200, 600, Bitmap.Config.ARGB_8888);
        fixture.eraseColor(Color.WHITE);
        fixture.setPixel(4800, 300, Color.RED);
        fixture.setPixel(4801, 300, Color.BLUE);
        File file = new File(a.getCacheDir(), "original-5200.png");
        try (FileOutputStream out = new FileOutputStream(file)) { fixture.compress(Bitmap.CompressFormat.PNG, 100, out); }
        fixture.recycle();

        Bitmap original = ImageEditOps.decodeOriginal(file.getAbsolutePath());
        assertEquals(5200, original.getWidth());
        assertEquals(600, original.getHeight());
        assertEquals(Color.RED, original.getPixel(4800, 300));
        assertEquals(Color.BLUE, original.getPixel(4801, 300));
        Bitmap rotated = ImageEditOps.rotate(original, 90);
        assertEquals(600, rotated.getWidth());
        assertEquals(5200, rotated.getHeight());
        rotated.recycle();
        original.recycle();

        a.openFileAtPath(file.getAbsolutePath());
        for (int i = 0; i < 100 && a.stageCanvasView.getImageBitmap() == null; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(20);
        }
        layout(a, 360, 800);
        StageCanvasView stage = a.stageCanvasView;
        assertNotNull(stage.getImageBitmap());
        assertEquals(5200, stage.getImageBitmap().getWidth());

        File saved = ViewerExports.snapshot(a, file.getAbsolutePath(), "image", stage, null, 100, 300);
        Bitmap roundTrip = BitmapFactory.decodeFile(saved.getAbsolutePath());
        assertEquals(5200, roundTrip.getWidth());
        assertEquals(Color.RED, roundTrip.getPixel(4800, 300));
        assertEquals(Color.BLUE, roundTrip.getPixel(4801, 300));
        roundTrip.recycle();

        File copied = ViewerExports.snapshot(a, file.getAbsolutePath(), "image", stage, null, 92, 150, false);
        assertArrayEquals(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.file.Files.readAllBytes(copied.toPath()));

        assertTrue("Document tool capsules are up", a.dock.toolMode());
        screenshot(a, "phone-image", 360, 800);

        call(a, "rotateCurrentImage", new Class<?>[]{int.class}, 90);
        assertEquals(5200, stage.getImageBitmap().getHeight());
        call(a, "undoImageEdit", new Class<?>[0]);
        assertEquals(5200, stage.getImageBitmap().getWidth());
        assertEquals(Color.RED, stage.getImageBitmap().getPixel(4800, 300));
        c.pause().stop().destroy();
    }

    @Test public void contentUriWithoutExtensionUsesProviderMimeAndOriginalBytes() throws Exception {
        ActivityController<MainActivity> c = launch(360, 800);
        MainActivity a = c.get();
        ImageProvider provider = new ImageProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = "viewer.tests";
        provider.attachInfo(a, info);
        provider.file = new File(a.getCacheDir(), "provider.png");
        Bitmap image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(provider.file)) { image.compress(Bitmap.CompressFormat.PNG, 100, out); }
        image.recycle();
        ShadowContentResolver.registerProviderInternal(info.authority, provider);
        Uri uri = Uri.parse("content://viewer.tests/image/42");
        File imported = ((DocumentStore) field(a, "documents")).importFile(uri);
        assertTrue(imported.getName().endsWith(".png"));
        assertArrayEquals(java.nio.file.Files.readAllBytes(provider.file.toPath()), java.nio.file.Files.readAllBytes(imported.toPath()));
        c.pause().stop().destroy();
    }

    @Test public void darkWelcomeAndLargeTextRemainUsable() throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("samsung_viewer_native_prefs", Context.MODE_PRIVATE)
                .edit().putString("theme", "dark").putBoolean("dark_theme", true).commit();
        ActivityController<MainActivity> c = launch(360, 800);
        MainActivity a = c.get();
        Configuration config = new Configuration(a.getResources().getConfiguration());
        config.fontScale = 1.4f;
        a.getResources().updateConfiguration(config, a.getResources().getDisplayMetrics());
        call(a, "buildCompleteNativeUI", new Class<?>[0]);
        layout(a, 360, 800);
        View button = tagged(a, "welcome-new");
        Rect visible = new Rect();
        assertTrue(button.getGlobalVisibleRect(visible));
        assertEquals(button.getHeight(), visible.height());
        assertTrue((Boolean) field(a, "isDarkTheme"));
        screenshot(a, "phone-dark-large-text", 360, 800);
        c.pause().stop().destroy();
    }

    @Test public void largeTextureTilesKeepAdjacentPixelDetail() throws Exception {
        Bitmap original = Bitmap.createBitmap(9000, 32, Bitmap.Config.ARGB_8888);
        original.eraseColor(Color.WHITE);
        original.setPixel(8191, 10, Color.RED);
        original.setPixel(8192, 10, Color.BLUE);
        Bitmap same = ImageEditOps.resize(original, 9000, 32);
        assertEquals(9000, same.getWidth());
        if (same != original) same.recycle();
        Bitmap viewport = Bitmap.createBitmap(40, 32, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(viewport);
        canvas.translate(-8180, 0);
        new ImageTiles().draw(canvas, original, new Paint(Paint.FILTER_BITMAP_FLAG), 1f, new RectF(8180, 0, 8220, 32));
        assertEquals(Color.RED, viewport.getPixel(11, 10));
        assertEquals(Color.BLUE, viewport.getPixel(12, 10));
        original.recycle();
        viewport.recycle();
    }

    @Test public void pdfExportHonoursRequestedWidthAndRotatedDpi() throws Exception {
        MarkupModel.PdfPageItem page = new MarkupModel.PdfPageItem("blank", "blank", null, 0, 1000, 500);
        assertEquals(4167, ViewerExports.pdfWidth(page, 300));
        Bitmap normal = page.renderFlattened(4167);
        assertEquals(4167, normal.getWidth());
        normal.recycle();
        page.rot = 90;
        page.invalidateCache();
        Bitmap rotated = page.renderFlattened(3000);
        assertEquals(3000, rotated.getWidth());
        assertEquals(6000, rotated.getHeight());
        rotated.recycle();
    }

    @Test public void imagePixelsUseExplicitBoundsAtHighDisplayDensity() throws Exception {
        Bitmap original = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888);
        original.setDensity(160);
        original.eraseColor(Color.RED);
        Bitmap target = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(target);
        canvas.setDensity(480);
        new ImageTiles().draw(canvas, original, new Paint(), 1f, new RectF(0, 0, 20, 10));
        assertEquals(Color.RED, target.getPixel(19, 9));
        assertEquals(Color.TRANSPARENT, target.getPixel(20, 9));
        original.recycle();
        target.recycle();
    }

    @Test public void highDensityPhoneKeepsNativeSizingAndButtons() throws Exception {
        RuntimeEnvironment.setQualifiers("w360dp-h800dp-xxhdpi");
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class, new Intent(Intent.ACTION_MAIN)).setup().visible();
        MainActivity a = c.get();
        layout(a, 1080, 2400);
        assertEquals(3f, a.getResources().getDisplayMetrics().density, .01f);
        View card = tagged(a, "welcome-card");
        assertTrue(card.getWidth() <= 1080);
        assertTrue(tagged(a, "welcome-new").getHeight() >= 144);
        screenshot(a, "phone-1080x2400", 1080, 2400);
        c.pause().stop().destroy();
    }

    @Test public void pdfTileRotationMapsSourceIntoDisplayBounds() {
        int[][] rotations = {{0, 0, 0}, {90, 1000, 0}, {180, 600, 1000}, {270, 0, 600}};
        for (int[] r : rotations) {
            float[] origin = {0, 0};
            PdfHost.tileMatrix(600, 1000, r[0], 2, 15, 25).mapPoints(origin);
            assertEquals(r[1] * 2 - 15, origin[0], .01f);
            assertEquals(r[2] * 2 - 25, origin[1], .01f);
        }
    }

    @Test public void transparentTileBoundaryIsPaintedOnce() {
        Bitmap original = Bitmap.createBitmap(9000, 32, Bitmap.Config.ARGB_8888);
        original.eraseColor(0x80FF0000);
        Bitmap viewport = Bitmap.createBitmap(40, 32, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(viewport);
        canvas.translate(-8180, 0);
        new ImageTiles().draw(canvas, original, new Paint(Paint.FILTER_BITMAP_FLAG), 1f, new RectF(8180, 0, 8220, 32));
        for (int x = 0; x < 40; x++) assertEquals(128, Color.alpha(viewport.getPixel(x, 10)));
        original.recycle();
        viewport.recycle();
    }

    @Test public void fullQualityPdfKeepsPixelColoursLosslessly() throws Exception {
        ActivityController<MainActivity> c = launch(360, 800);
        MainActivity a = c.get();
        File file = new File(a.getCacheDir(), "lossless.pdf");
        Bitmap source = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.WHITE);
        source.setPixel(1, 0, Color.RED);
        source.setPixel(2, 0, Color.BLUE);
        ViewerExports.require(PdfHost.begin("quality-test", file.getAbsolutePath()), "ok");
        ViewerExports.require(PdfHost.addPageBitmap("quality-test", 4, 2, source, 100), "ok");
        ViewerExports.require(PdfHost.end("quality-test", "lossless.pdf"), "ok");
        try (com.tom_roush.pdfbox.pdmodel.PDDocument document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(file)) {
            com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject image =
                    (com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject) document.getPage(0).getResources().getXObject(com.tom_roush.pdfbox.cos.COSName.getPDFName("Im0"));
            assertEquals(4, image.getWidth());
            assertEquals(2, image.getHeight());
            try (java.io.InputStream decoded = image.getCOSObject().createInputStream()) {
                byte[] rgb = decoded.readAllBytes();
                assertEquals(24, rgb.length);
                assertArrayEquals(new byte[]{(byte) 255, 0, 0}, Arrays.copyOfRange(rgb, 3, 6));
                assertArrayEquals(new byte[]{0, 0, (byte) 255}, Arrays.copyOfRange(rgb, 6, 9));
            }
        }
        source.recycle();
        c.pause().stop().destroy();
    }

    @Test public void fileDropKeepsLocalUrisAndReleasesExactlyOnce() throws Exception {
        Uri first = Uri.parse("content://tests/one.png"), second = Uri.parse("content://tests/two.pdf");
        ClipData clip = new ClipData("files", new String[]{"image/png", "application/pdf"}, new ClipData.Item(first));
        clip.addItem(new ClipData.Item(second));
        clip.addItem(new ClipData.Item(first));
        clip.addItem(new ClipData.Item("plain text"));
        clip.addItem(new ClipData.Item(Uri.parse("https://example.com/no.png")));
        assertTrue(FileDrop.accepts(clip.getDescription()));
        assertFalse(FileDrop.accepts(null));
        assertEquals(Arrays.asList(first, second), FileDrop.collect(clip));
        assertEquals(Arrays.asList(Uri.parse("file:///tmp/test.txt")), FileDrop.localUris(Arrays.asList(null, Uri.parse("file:///tmp/test.txt"))));
        AtomicInteger releases = new AtomicInteger();
        Runnable release = FileDrop.once(releases::incrementAndGet);
        Thread one = new Thread(release), two = new Thread(release);
        one.start(); two.start(); one.join(); two.join(); release.run();
        assertEquals(1, releases.get());
    }

    @Test public void captureDenialNeverStartsProjectionService() throws Exception {
        ActivityController<MainActivity> c = launch(360, 800);
        MainActivity a = c.get();
        a.launchScreenCapture();
        assertNotNull(Shadows.shadowOf(a).getNextStartedActivity());
        assertNull(Shadows.shadowOf(a).getNextStartedService());
        call(a, "onActivityResult", new Class<?>[]{int.class, int.class, Intent.class}, 1006, Activity.RESULT_CANCELED, null);
        assertFalse((Boolean) field(a, "capturePending"));
        assertNull(Shadows.shadowOf(a).getNextStartedService());
        c.pause().stop().destroy();
    }

    @Test public void hwpxRichRendersImagesTablesAndFramesEndToEnd() throws Exception {
        ActivityController<MainActivity> c = launch(412, 915);
        MainActivity a = c.get();
        // Build a synthetic .hwpx containing a real PNG in BinData, a shaded merged table, and a framed text box
        File hwpx = new File(a.getCacheDir(), "rich_test.hwpx");
        Bitmap bmp = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.drawColor(0xFF1E88E5);
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setColor(0xFFFFD54F);
        cv.drawCircle(80, 40, 28, pt);
        java.io.ByteArrayOutputStream pngBos = new java.io.ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, pngBos);
        bmp.recycle();

        String hpf = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><opf:package xmlns:opf=\"http://www.idpf.org/2007/opf/\">"
                + "<opf:manifest><opf:item id=\"image1\" href=\"../BinData/image1.png\" media-type=\"image/png\"/></opf:manifest></opf:package>";
        String hdr = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><hh:head xmlns:hh=\"http://www.hancom.co.kr/hwpml/2011/head\" xmlns:hc=\"http://www.hancom.co.kr/hwpml/2011/core\">"
                + "<hh:refList><hh:borderFills><hh:borderFill id=\"1\"><hh:leftBorder type=\"SOLID\" color=\"#334155\"/><hh:rightBorder type=\"SOLID\" color=\"#334155\"/>"
                + "<hh:topBorder type=\"SOLID\" color=\"#334155\"/><hh:bottomBorder type=\"SOLID\" color=\"#334155\"/>"
                + "<hc:fillBrush><hc:winBrush faceColor=\"#DBEAFE\"/></hc:fillBrush></hh:borderFill></hh:borderFills></hh:refList></hh:head>";
        String sec = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><hs:sec xmlns:hs=\"http://www.hancom.co.kr/hwpml/2011/section\" xmlns:hp=\"http://www.hancom.co.kr/hwpml/2011/paragraph\" xmlns:hc=\"http://www.hancom.co.kr/hwpml/2011/core\">"
                + "<hp:p><hp:run><hp:t>한글 리치 문서 제목</hp:t></hp:run></hp:p>"
                + "<hp:p><hp:run><hp:pic><hp:curSz width=\"15000\" height=\"7500\"/><hc:img binaryItemIDRef=\"image1\"/></hp:pic></hp:run></hp:p>"
                + "<hp:p><hp:run><hp:tbl><hp:sz width=\"40000\" height=\"6000\"/>"
                + "<hp:tr><hp:tc borderFillIDRef=\"1\"><hp:cellSpan colSpan=\"2\" rowSpan=\"1\"/><hp:subList><hp:p><hp:run><hp:t>표 병합 헤더</hp:t></hp:run></hp:p></hp:subList></hp:tc></hp:tr>"
                + "<hp:tr><hp:tc borderFillIDRef=\"1\"><hp:subList><hp:p><hp:run><hp:t>항목 A</hp:t></hp:run></hp:p></hp:subList></hp:tc>"
                + "<hp:tc borderFillIDRef=\"1\"><hp:subList><hp:p><hp:run><hp:t>값 100%</hp:t></hp:run></hp:p></hp:subList></hp:tc></hp:tr>"
                + "</hp:tbl></hp:run></hp:p>"
                + "<hp:p><hp:run><hp:rect><hp:lineShape style=\"SOLID\" color=\"#0381FE\"/><hp:drawText><hp:subList><hp:p><hp:run><hp:t>글상자 템플릿 프레임</hp:t></hp:run></hp:p></hp:subList></hp:drawText></hp:rect></hp:run></hp:p>"
                + "</hs:sec>";
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new FileOutputStream(hwpx))) {
            zos.putNextEntry(new java.util.zip.ZipEntry("Contents/content.hpf")); zos.write(hpf.getBytes(java.nio.charset.StandardCharsets.UTF_8)); zos.closeEntry();
            zos.putNextEntry(new java.util.zip.ZipEntry("Contents/header.xml")); zos.write(hdr.getBytes(java.nio.charset.StandardCharsets.UTF_8)); zos.closeEntry();
            zos.putNextEntry(new java.util.zip.ZipEntry("Contents/section0.xml")); zos.write(sec.getBytes(java.nio.charset.StandardCharsets.UTF_8)); zos.closeEntry();
            zos.putNextEntry(new java.util.zip.ZipEntry("BinData/image1.png")); zos.write(pngBos.toByteArray()); zos.closeEntry();
        }
        String html = HwpRich.renderHwpx(hwpx.getAbsolutePath(), null);
        assertTrue("HWPX HTML includes base64 PNG image", html.contains("data:image/png;base64,"));
        assertTrue("HWPX HTML includes merged table cell", html.contains("colspan=\"2\""));
        assertTrue("HWPX HTML includes shaded fill color", html.contains("#DBEAFE"));
        assertTrue("HWPX HTML includes template box frame", html.contains("class=\"hwp-box\""));
        a.openFileAtPath(hwpx.getAbsolutePath());
        layout(a, 412, 915);
        screenshot(a, "hwpx-rich-rendered", 412, 915);
        c.pause().stop().destroy();
    }

    @Test public void securityHardeningChecks() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();

        // 1. ViewerFileProvider blocks private app data (shared_prefs/databases/files) and path traversal
        File cacheOk = new File(ctx.getCacheDir(), "shared_ok.pdf");
        assertTrue(ViewerFileProvider.isAllowedFile(ctx, cacheOk));
        File dataDir = new File(ctx.getApplicationInfo().dataDir);
        File secretPrefs = new File(new File(dataDir, "shared_prefs"), "samsung_viewer_native_prefs.xml");
        assertFalse(ViewerFileProvider.isAllowedFile(ctx, secretPrefs));
        File traversal = new File(ctx.getCacheDir(), "../shared_prefs/samsung_viewer_native_prefs.xml");
        assertFalse(ViewerFileProvider.isAllowedFile(ctx, traversal));
        assertFalse(ViewerFileProvider.isWriteAllowed(cacheOk));
        ViewerFileProvider.allowWriteTarget(cacheOk);
        assertTrue(ViewerFileProvider.isWriteAllowed(cacheOk));

        // 2. XXE payload inside ZIP XML is rejected by Docs.loadXml
        File xxeZip = new File(ctx.getCacheDir(), "xxe_test.docx");
        String xxeXml = "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><doc>&xxe;</doc>";
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new FileOutputStream(xxeZip))) {
            zos.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            zos.write(xxeXml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(xxeZip)) {
            assertNull("XXE DOCTYPE must be rejected", Docs.loadXml(zf, "word/document.xml"));
        }
        xxeZip.delete();

        // 3. Updater URL allowlist
        assertEquals("9.5 A1A1", Updater.VERSION);
        assertTrue(Updater.newer("9.5 A1A1", "9.5.11"));
        assertTrue(Updater.newer("v9.5-A1A2", Updater.VERSION));
        assertFalse(Updater.newer("9.5.12", Updater.VERSION));
        assertEquals("9.5 A1A1", Updater.nextVersion("9.5.11"));
        assertEquals("9.5 A1B1", Updater.nextVersion("9.5 A1A9"));
        assertEquals("9.5 A1A1", Updater.displayVersion("v9.5-A1A1"));
        assertTrue(Updater.isTrustedReleaseUrl("https://github.com/Bundelldebug/viewer-android/releases/download/v9.5.11/SamsungViewer_Android.apk"));
        assertTrue(Updater.isTrustedReleaseUrl("https://objects.githubusercontent.com/release/SamsungViewer_Android.apk"));
        assertFalse(Updater.isTrustedReleaseUrl("http://github.com/Bundelldebug/viewer-android/releases/download/v9.5.11/SamsungViewer_Android.apk"));
        assertFalse(Updater.isTrustedReleaseUrl("https://evil.example.com/SamsungViewer_Android.apk"));
    }
}
