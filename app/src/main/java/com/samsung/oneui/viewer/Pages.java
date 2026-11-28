package com.samsung.oneui.viewer;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** All Viewer Android screens built on Prism Music Android's Page / Widgets / Glass architecture. */
final class Pages {
    private Pages() {}

    private static Bitmap cachedIcon;

    static Bitmap appIcon(Context c) {
        if (cachedIcon != null && !cachedIcon.isRecycled()) return cachedIcon;
        try (InputStream is = c.getAssets().open("icon.png")) {
            cachedIcon = BitmapFactory.decodeStream(is);
            return cachedIcon;
        } catch (Exception ignored) {
            return null;
        }
    }

    static int iconForKind(String kind) {
        if ("pdf".equals(kind)) return Icons.PDF;
        if ("doc".equals(kind)) return Icons.DOC;
        if ("media".equals(kind)) return Icons.MEDIA;
        return Icons.VIEW;
    }

    static int colorForKind(String kind) {
        if ("pdf".equals(kind)) return 0xFFE92D44;
        if ("doc".equals(kind)) return 0xFF0381FE;
        if ("media".equals(kind)) return 0xFF7B61FF;
        return 0xFF0FB59D;
    }

    static String labelForKind(String kind) {
        if ("pdf".equals(kind)) return "PDF";
        if ("doc".equals(kind)) return "문서";
        if ("media".equals(kind)) return "미디어";
        return "이미지";
    }

    // =========================================================================
    // 1. 최근 항목 (home): big title + app icon, two glass buttons, a "최근 항목" card with a file grid.
    //    Foldable unfolded: title / buttons on the left, the card as a tall sheet on the right.
    // =========================================================================
    static final class HomePage extends Page {
        HomePage(MainActivity m) {
            super(m, "뷰어", false);
            headerIcons = new int[0];
            setGrouped(true);
        }

        void toggleLayout() {
            SharedPreferences p = MainActivity.prefs(getContext());
            p.edit().putBoolean("home_list", !p.getBoolean("home_list", false)).apply();
            rebuild();
        }

        @Override float titleProgress() { return Ui.clamp((scroll.getScrollY() - Ui.dp(36)) / (float) Ui.dp(22), 0f, 1f); }

        @Override void build() {
            Context c = getContext();
            boolean railUi = main.sideInset() > 0;
            boolean tablet = main.tabletUi();
            boolean list = MainActivity.prefs(c).getBoolean("home_list", false);
            LinearLayout hero = buildHero(c, railUi);
            List<File> recents = main.collectRecentFiles();
            int cols = railUi ? 3 : (tablet ? 5 : 3);
            LinearLayout card = recentCard(main, "최근 항목", recents, cols, list, railUi ? new int[]{Icons.SEARCH} : new int[]{list ? Icons.GRID : Icons.GRID, Icons.MORE, Icons.SEARCH});
            card.setTag("welcome-card");
            if (railUi) {
                LinearLayout split = new LinearLayout(c);
                split.setOrientation(LinearLayout.HORIZONTAL);
                int availW = Math.max(Ui.dp(300), Ui.screenW - main.sideInset() - main.contentInsetPx());
                int availH = Math.max(Ui.dp(240), Ui.screenH - Ui.statusInset - Ui.dp(64) - Ui.navInset - Ui.dp(24));
                Rect fb = main.foldBounds;
                int leftW, gapW;
                if (fb != null && fb.height() > fb.width() && fb.left > Ui.dp(160) && fb.right < availW - Ui.dp(160)) {
                    // book-style hinge: buttons on the left half, the card starts right of the hinge
                    leftW = fb.left - Ui.dp(12);
                    gapW = fb.right + Ui.dp(12) - leftW;
                } else {
                    leftW = (int) (availW * 0.46f);
                    gapW = Ui.dp(4);
                }
                LinearLayout left = new LinearLayout(c);
                left.setOrientation(LinearLayout.VERTICAL);
                left.setGravity(Gravity.CENTER_VERTICAL);
                left.addView(hero, Widgets.MW);
                split.addView(left, new LinearLayout.LayoutParams(leftW, availH));
                card.setMinimumHeight(availH);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                clp.leftMargin = gapW;
                split.addView(card, clp);
                content.addView(split, Widgets.MW);
            } else {
                content.addView(hero, Widgets.MW);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                int side = tablet ? Math.max(Ui.dp(20), (Ui.screenW - main.contentInsetPx() - Ui.dp(860)) / 2) : Ui.dp(14);
                clp.setMargins(side, Ui.dp(22), side, 0);
                card.setMinimumHeight(Math.max(Ui.dp(260), Ui.screenH / 2 - Ui.dp(40)));
                content.addView(card, clp);
            }
        }

        private LinearLayout buildHero(Context c, boolean railUi) {
            LinearLayout hero = new LinearLayout(c);
            hero.setTag("welcome-hero");
            hero.setOrientation(LinearLayout.VERTICAL);
            int side = Ui.dp(railUi ? 22 : 20);
            hero.setPadding(side, Ui.dp(railUi ? 0 : 6), side, 0);

            LinearLayout top = new LinearLayout(c);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView titleTv = Ui.text(c, "뷰어", railUi ? 34f : 36f, Ui.bold, Ui.text());
            titleTv.setTag("welcome-title");
            titleTv.setLetterSpacing(-0.02f);
            texts.addView(titleTv);
            TextView subTv = Ui.text(c, "표시하고 편집할\n문서를 선택합니다.", 15.5f, Ui.regular, Ui.text2());
            subTv.setSingleLine(false);
            subTv.setLineSpacing(0, 1.08f);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = Ui.dp(4);
            texts.addView(subTv, slp);
            top.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // the app's own icon (never redrawn — assets/icon.png) in a glass lens ring
            int ring = Ui.dp(railUi ? 104 : 112);
            FrameLayout iconFrame = new FrameLayout(c);
            GradientDrawable lens = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Ui.dark ? 0x40FFFFFF : 0xF2FFFFFF, Ui.dark ? 0x14FFFFFF : 0x99FFFFFF});
            lens.setShape(GradientDrawable.OVAL);
            lens.setStroke(Math.max(1, Ui.dp(1.2f)), Ui.dark ? 0x40FFFFFF : 0x1F000000);
            iconFrame.setBackground(lens);
            iconFrame.setElevation(Ui.dpf(8));
            ImageView iconView = new ImageView(c);
            iconView.setTag("welcome-icon");
            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Bitmap ic = appIcon(c);
            if (ic != null) iconView.setImageBitmap(ic);
            else iconView.setImageDrawable(VectorIcons.create(c, "folder", 36f, Ui.ACCENT));
            iconFrame.addView(iconView, new FrameLayout.LayoutParams((int) (ring * 0.66f), (int) (ring * 0.66f), Gravity.CENTER));
            Ui.press(iconFrame, 0.95f);
            iconFrame.setOnClickListener(v -> main.launchSystemFilePicker());
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ring, ring);
            ilp.leftMargin = Ui.dp(10);
            top.addView(iconFrame, ilp);
            hero.addView(top, Widgets.MW);

            LinearLayout actions = new LinearLayout(c);
            actions.setTag("welcome-actions");
            actions.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.topMargin = Ui.dp(railUi ? 18 : 22);
            TextView newBtn = glassButton(c, "새로운 문서", v -> main.createBlankSketch());
            newBtn.setTag("welcome-new");
            LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(50));
            b1.bottomMargin = Ui.dp(10);
            actions.addView(newBtn, b1);
            TextView scanBtn = glassButton(c, "문서 스캔", v -> main.launchCameraCapture());
            scanBtn.setTag("welcome-scan");
            actions.addView(scanBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(50)));
            hero.addView(actions, alp);
            return hero;
        }
    }

    /** Frosted capsule button (home screen). */
    static TextView glassButton(Context c, String label, View.OnClickListener click) {
        TextView tv = Ui.text(c, label, 15.5f, Ui.medium, Ui.text());
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setMinHeight(Ui.dp(48));
        GradientDrawable bg = Ui.round(Ui.dark ? 0x26FFFFFF : 0xB3FFFFFF, 100);
        bg.setStroke(Math.max(1, Ui.dp(0.8f)), Ui.dark ? 0x2EFFFFFF : 0x14000000);
        tv.setBackground(bg);
        tv.setElevation(Ui.dpf(1.5f));
        tv.setOnClickListener(click);
        Ui.press(tv, 0.97f);
        return tv;
    }

    /**
     * Rounded "최근 항목"-style card: title row with a small glass button pill (grid / more / search), then a
     * thumbnail grid (or list) of files. Used by 최근 항목 and 공유됨.
     */
    static LinearLayout recentCard(MainActivity m, String title, List<File> files, int cols, boolean list, int[] buttons) {
        Context c = m;
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(20));
        GradientDrawable bg = Ui.round(Ui.dark ? 0xFF1C1C1F : 0xF7FFFFFF, 26);
        bg.setStroke(Math.max(1, Ui.dp(0.7f)), Ui.dark ? 0x1FFFFFFF : 0x0F000000);
        card.setBackground(bg);
        card.setElevation(Ui.dpf(Ui.dark ? 1f : 2f));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.text(c, title, 15f, Ui.semibold, Ui.text());
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (buttons != null && buttons.length > 0) {
            LinearLayout pill = new LinearLayout(c);
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setPadding(Ui.dp(4), 0, Ui.dp(4), 0);
            GradientDrawable pbg = Ui.round(Ui.dark ? 0x1FFFFFFF : 0xE6FFFFFF, 100);
            pbg.setStroke(Math.max(1, Ui.dp(0.8f)), Ui.dark ? 0x2EFFFFFF : 0x17000000);
            pill.setBackground(pbg);
            pill.setElevation(Ui.dpf(2));
            for (int ic : buttons) {
                Icons.IconView iv = new Icons.IconView(c, ic, 20, Ui.text());
                iv.setTag("card-btn-" + ic);
                iv.setOnClickListener(v -> {
                    RectF a = Glass.absRect(v);
                    if (ic == Icons.GRID) { if (m.homePageRef() != null) m.homePageRef().toggleLayout(); }
                    else if (ic == Icons.MORE) m.showOverflowGlassMenu(a);
                    else if (ic == Icons.SEARCH) m.onSearch();
                });
                Ui.press(iv, 0.88f);
                pill.addView(iv, new LinearLayout.LayoutParams(Ui.dp(42), Ui.dp(40)));
            }
            head.addView(pill, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(40)));
        }
        card.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(44)));

        LinearLayout grid = new LinearLayout(c);
        grid.setTag("recent-grid");
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = Ui.dp(16);
        card.addView(grid, glp);
        if (files.isEmpty()) {
            TextView e = Ui.text(c, "항목 없음", 15f, Ui.semibold, Ui.text2());
            e.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            elp.topMargin = Ui.dp(48);
            grid.addView(e, elp);
            return card;
        }
        int limit = Math.min(files.size(), 60);
        if (list) {
            for (int i = 0; i < limit; i++) grid.addView(fileRow(m, files.get(i)), Widgets.MW);
            return card;
        }
        LinearLayout row = null;
        for (int i = 0; i < limit; i++) {
            if (i % cols == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setBaselineAligned(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = Ui.dp(18);
                grid.addView(row, rlp);
            }
            row.addView(fileTile(m, files.get(i)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        int rest = limit % cols;
        if (rest != 0 && row != null) for (int i = rest; i < cols; i++) row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        return card;
    }

    private static final java.util.concurrent.ExecutorService THUMBS = java.util.concurrent.Executors.newFixedThreadPool(2);
    private static final android.util.LruCache<String, Bitmap> THUMB_CACHE = new android.util.LruCache<>(64);

    private static String baseName(String n) {
        int d = n.lastIndexOf('.');
        return d > 0 ? n.substring(0, d) : n;
    }

    private static String dateOf(File f) {
        return new java.text.SimpleDateFormat("yyyy. M. d.", Locale.KOREA).format(new java.util.Date(f.lastModified()));
    }

    /** Thumbnail (pictures: the picture; other files: a paper sheet with the kind's mark), name, date, size. */
    static View fileTile(MainActivity m, File f) {
        Context c = m;
        LinearLayout tile = new LinearLayout(c);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(Ui.dp(4), Ui.dp(2), Ui.dp(4), Ui.dp(2));
        String kind = FileOps.kindOf(f.getName());
        View thumb = thumbView(c, f, kind);
        tile.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(84), Ui.dp(84)));
        TextView name = Ui.text(c, baseName(f.getName()), 13f, Ui.medium, Ui.text());
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = Ui.dp(6);
        tile.addView(name, nlp);
        TextView meta = Ui.text(c, dateOf(f) + "\n" + MainActivity.fmtBytes(f.length()), 11.5f, Ui.regular, Ui.text2());
        meta.setGravity(Gravity.CENTER);
        meta.setSingleLine(false);
        meta.setLineSpacing(0, 1.05f);
        tile.addView(meta, Widgets.MW);
        tile.setOnClickListener(v -> m.openFileAtPath(f.getAbsolutePath()));
        Ui.press(tile, 0.95f);
        return tile;
    }

    static View fileRow(MainActivity m, File f) {
        Context c = m;
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Ui.dp(6), 0, Ui.dp(6));
        r.addView(thumbView(c, f, FileOps.kindOf(f.getName())), new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(12), 0, 0, 0);
        TextView name = Ui.text(c, baseName(f.getName()), 15f, Ui.medium, Ui.text());
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);
        texts.addView(Ui.text(c, dateOf(f) + " · " + MainActivity.fmtBytes(f.length()), 12f, Ui.regular, Ui.text2()));
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.setOnClickListener(v -> m.openFileAtPath(f.getAbsolutePath()));
        Ui.press(r, 0.98f);
        return r;
    }

    private static View thumbView(Context c, File f, String kind) {
        FrameLayout box = new FrameLayout(c);
        if ("image".equals(kind)) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            box.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            String key = f.getAbsolutePath() + "@" + f.lastModified();
            Bitmap cached = THUMB_CACHE.get(key);
            if (cached != null) iv.setImageBitmap(cached);
            else {
                iv.setImageDrawable(paperSheet(c, "IMG"));
                THUMBS.execute(() -> {
                    Bitmap b = decodeThumb(f, Ui.dp(96));
                    if (b == null) return;
                    THUMB_CACHE.put(key, b);
                    iv.post(() -> iv.setImageBitmap(b));
                });
            }
            return box;
        }
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        String ext = f.getName().contains(".") ? f.getName().substring(f.getName().lastIndexOf('.') + 1).toUpperCase(Locale.ROOT) : labelForKind(kind);
        iv.setImageDrawable(paperSheet(c, ext));
        box.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return box;
    }

    private static Bitmap decodeThumb(File f, int target) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int s = 1;
            while (o.outWidth / (s * 2) >= target && o.outHeight / (s * 2) >= target) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Throwable t) {
            return null;
        }
    }

    /** A small paper sheet with a folded corner and the file type underneath (document thumbnails). */
    private static android.graphics.drawable.Drawable paperSheet(Context c, String label) {
        return new android.graphics.drawable.Drawable() {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(android.graphics.Canvas cv) {
                android.graphics.Rect b = getBounds();
                float h = b.height() * 0.92f, w = h * 0.76f;
                float l = b.centerX() - w / 2f, t = b.top + (b.height() - h) / 2f, fold = w * 0.26f;
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(l, t); path.lineTo(l + w - fold, t); path.lineTo(l + w, t + fold); path.lineTo(l + w, t + h); path.lineTo(l, t + h); path.close();
                p.setStyle(android.graphics.Paint.Style.FILL);
                p.setColor(Ui.dark ? 0xFF2C2C30 : 0xFFF4F4F6);
                p.setShadowLayer(Ui.dpf(2), 0, Ui.dpf(1), 0x22000000);
                cv.drawPath(path, p);
                p.clearShadowLayer();
                p.setColor(Ui.dark ? 0xFF3A3A3F : 0xFFDCDCE0);
                android.graphics.Path f = new android.graphics.Path();
                f.moveTo(l + w - fold, t); f.lineTo(l + w - fold, t + fold); f.lineTo(l + w, t + fold); f.close();
                cv.drawPath(f, p);
                p.setColor(Ui.dark ? 0xFF55555C : 0xFFB9B9C0);
                float lx = l + w * 0.2f, lw = w * 0.6f;
                for (int i = 0; i < 4; i++) {
                    float y = t + h * (0.34f + i * 0.09f);
                    cv.drawRoundRect(lx, y, lx + (i == 3 ? lw * 0.6f : lw), y + Ui.dpf(1.6f), Ui.dpf(1), Ui.dpf(1), p);
                }
                p.setColor(Ui.dark ? 0xFF8E8E93 : 0xFF8E8E93);
                p.setTextAlign(android.graphics.Paint.Align.CENTER);
                p.setTypeface(Ui.semibold);
                p.setTextSize(Ui.sp(7.5f));
                String lb = label.length() > 5 ? label.substring(0, 5) : label;
                cv.drawText(lb, l + w / 2f, t + h - Ui.dpf(6), p);
            }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    // =========================================================================
    // 1b. 공유됨: files other apps sent or opened with the viewer.
    // =========================================================================
    static final class SharedPage extends Page {
        SharedPage(MainActivity m) {
            super(m, "공유됨", true);
            headerIcons = new int[0];
            setGrouped(true);
        }

        @Override void build() {
            Context c = getContext();
            List<File> files = new ArrayList<>();
            File dir = main.sharedDir();
            File[] fs = dir == null ? null : dir.listFiles();
            if (fs != null) for (File f : fs) if (f.isFile() && !f.getName().startsWith(".") && !FileOps.kindOf(f.getName()).isEmpty()) files.add(f);
            java.util.Collections.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            boolean railUi = main.sideInset() > 0;
            LinearLayout card = recentCard(main, "다른 앱에서 받은 항목", files, railUi || main.tabletUi() ? 5 : 3, false, null);
            card.setTag("shared-card");
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMargins(Ui.dp(14), Ui.dp(8), Ui.dp(14), 0);
            content.addView(card, clp);
            content.addView(Widgets.caption(c, "다른 앱에서 '공유' 또는 '열기'로 보낸 파일이 여기에 모입니다."));
        }
    }

    // =========================================================================
    // 2. VIEWER STAGE PAGE (Full-bleed Stage inside Glass.Backdrop + Floating Sub-tool Bar)
    // =========================================================================
    static final class StagePage extends Page {
        final FrameLayout stageContainer;
        final LinearLayout subToolHost;
        final LinearLayout subToolRow;
        final LinearLayout emptyOverlay;

        StagePage(MainActivity m) {
            super(m, "보기", false);
            headerIcons = new int[]{Icons.INFO, Icons.MORE};
            scroll.setVisibility(GONE);

            stageContainer = new FrameLayout(m);
            stageContainer.setBackgroundColor(Ui.dark ? 0xFF0A0B0F : 0xFFF2F3F7);
            addView(stageContainer, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // Empty placeholder shown only if user taps "보기" tab with no file opened yet
            emptyOverlay = new LinearLayout(m);
            emptyOverlay.setOrientation(LinearLayout.VERTICAL);
            emptyOverlay.setGravity(Gravity.CENTER);
            emptyOverlay.setPadding(Ui.dp(28), Ui.dp(28), Ui.dp(28), Ui.dp(28));
            Icons.IconView iv = new Icons.IconView(m, Icons.VIEW, 48, Ui.text3());
            emptyOverlay.addView(iv, new LinearLayout.LayoutParams(Ui.dp(56), Ui.dp(56)));
            TextView t = Ui.text(m, "열린 파일이 없어요", 18, Ui.bold, Ui.text());
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = Ui.dp(12);
            emptyOverlay.addView(t, tlp);
            TextView s = Ui.text(m, "파일을 열거나 홈에서 이미지를 선택해 보세요", 13.5f, Ui.regular, Ui.text2());
            s.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = Ui.dp(4);
            slp.bottomMargin = Ui.dp(18);
            emptyOverlay.addView(s, slp);
            TextView openBtn = Ui.text(m, "파일 열기", 15, Ui.semibold, 0xFFFFFFFF);
            openBtn.setGravity(Gravity.CENTER);
            openBtn.setPadding(Ui.dp(24), Ui.dp(12), Ui.dp(24), Ui.dp(12));
            openBtn.setBackground(Ui.round(Ui.ACCENT, 100));
            openBtn.setOnClickListener(v -> m.launchSystemFilePicker());
            Ui.press(openBtn, 0.95f);
            emptyOverlay.addView(openBtn);
            addView(emptyOverlay, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // Floating contextual sub-tool bar (shown directly below header when Crop / Markup / Alpha / Select / Doc Edit is active)
            subToolHost = new LinearLayout(m);
            subToolHost.setOrientation(LinearLayout.HORIZONTAL);
            subToolHost.setGravity(Gravity.CENTER_VERTICAL);
            subToolHost.setPadding(Ui.dp(8), Ui.dp(6), Ui.dp(8), Ui.dp(6));
            GradientDrawable sbg = Ui.round(Ui.barTint(), 100);
            sbg.setStroke(Math.max(1, Ui.dp(0.8f)), Ui.glassStroke());
            subToolHost.setBackground(sbg);
            subToolHost.setElevation(Ui.dpf(6));
            subToolHost.setVisibility(GONE);

            HorizontalScrollView hs = new HorizontalScrollView(m);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setOverScrollMode(OVER_SCROLL_NEVER);
            subToolRow = new LinearLayout(m);
            subToolRow.setOrientation(LinearLayout.HORIZONTAL);
            subToolRow.setGravity(Gravity.CENTER_VERTICAL);
            hs.addView(subToolRow);
            subToolHost.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LayoutParams flp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            flp.topMargin = Ui.statusInset + Ui.dp(68);
            flp.leftMargin = Ui.dp(14);
            flp.rightMargin = Ui.dp(14);
            addView(subToolHost, flp);
        }

        /** The markup palette sits at the bottom (above the document's tool capsules); other tool bars at the top. */
        boolean subAtBottom;

        void setSubAtBottom(boolean b) {
            if (b == subAtBottom) return;
            subAtBottom = b;
            LayoutParams lp = (LayoutParams) subToolHost.getLayoutParams();
            lp.gravity = (b ? Gravity.BOTTOM : Gravity.TOP) | Gravity.CENTER_HORIZONTAL;
            subToolHost.setLayoutParams(lp);
            applyInsets();
        }

        @Override void applyInsets() {
            int topSafe = Ui.statusInset + Ui.dp(68);
            int botSafe = main.bottomPadding();
            int rightSafe = main.sideInset();
            if (subToolHost != null && subAtBottom) {
                LayoutParams lp = (LayoutParams) subToolHost.getLayoutParams();
                int wantB = rightSafe > 0 ? Ui.navInset + Ui.dp(18) : botSafe - Ui.dp(4);
                if (lp != null && (lp.bottomMargin != wantB || lp.rightMargin != Ui.dp(14) + rightSafe || lp.topMargin != 0)) {
                    lp.topMargin = 0;
                    lp.bottomMargin = wantB;
                    lp.leftMargin = Ui.dp(14);
                    lp.rightMargin = Ui.dp(14) + rightSafe;
                    subToolHost.setLayoutParams(lp);
                }
            }
            if (emptyOverlay != null) {
                int pL = Ui.dp(28), pT = topSafe + Ui.dp(16), pR = Ui.dp(28) + rightSafe, pB = botSafe + Ui.dp(16);
                if (emptyOverlay.getPaddingTop() != pT || emptyOverlay.getPaddingRight() != pR || emptyOverlay.getPaddingBottom() != pB) {
                    emptyOverlay.setPadding(pL, pT, pR, pB);
                }
            }
            if (subToolHost != null && !subAtBottom) {
                LayoutParams lp = (LayoutParams) subToolHost.getLayoutParams();
                int wantR = Ui.dp(14) + rightSafe;
                if (lp != null && (lp.topMargin != topSafe || lp.rightMargin != wantR || lp.bottomMargin != 0)) {
                    lp.bottomMargin = 0;
                    lp.topMargin = topSafe;
                    lp.leftMargin = Ui.dp(14);
                    lp.rightMargin = wantR;
                    subToolHost.setLayoutParams(lp);
                }
            }
            if (main.nativeDocView != null) {
                int extraSub = (subToolHost != null && subToolHost.getVisibility() == VISIBLE && !subAtBottom) ? Ui.dp(50) : 0;
                main.nativeDocView.setSafeInsets(topSafe + extraSub, botSafe, rightSafe);
            }
            if (main.nativeMediaView != null) {
                main.nativeMediaView.setSafeInsets(botSafe, rightSafe);
            }
        }

        @Override void build() {}

        @Override float titleProgress() { return main.hasOpenFile() ? 1f : 0f; }
        @Override float blurProgress() { return main.hasOpenFile() ? 0.65f : 0f; }
    }

    // =========================================================================
    // 3. FILES / PAGES PAGE (Folder Explorer + PDF Pages + Annotations + Bookmarks)
    // =========================================================================
    static final class FilesPage extends Page {
        int filter = 0; // 0=전체, 1=이미지, 2=PDF, 3=문서, 4=미디어, 5=책갈피
        private Widgets.Chips chips;
        private LinearLayout listHost;

        FilesPage(MainActivity m) {
            super(m, "둘러보기", true);
            headerIcons = new int[]{Icons.FOLDER, Icons.SORT};
            setGrouped(true);
        }

        @Override void build() {
            Context c = getContext();
            chips = new Widgets.Chips(c, new String[]{"전체", "이미지", "PDF", "문서", "미디어", "책갈피"}, idx -> {
                filter = idx;
                populate();
            });
            chips.set(filter, false);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = Ui.dp(10);
            content.addView(chips, clp);

            listHost = new LinearLayout(c);
            listHost.setOrientation(LinearLayout.VERTICAL);
            content.addView(listHost, Widgets.MW);
            populate();
        }

        void refresh() {
            if (listHost != null) populate();
        }

        void setFilter(int idx) {
            filter = idx;
            if (chips != null) chips.set(idx, true);
            refresh();
        }

        private void populate() {
            listHost.removeAllViews();
            Context c = getContext();

            // If a PDF is open, show its pages right at the top for fast jumping & organizing!
            if ("pdf".equals(main.currentKind) && filter != 5 && !main.stageCanvasView.getPdfPages().isEmpty()) {
                List<MarkupModel.PdfPageItem> pages = main.stageCanvasView.getPdfPages();
                Widgets.Group pdfGrp = new Widgets.Group(c, "열린 PDF 페이지 (" + pages.size() + ")");
                pdfGrp.row(Icons.PAGES, Ui.ACCENT, "PDF 페이지 정리 · 삽입 · 삭제", null, true, v -> main.showPdfPageOrganizeDialog());
                int cur = main.stageCanvasView.getCurrentPdfPage();
                int limit = Math.min(pages.size(), 30);
                for (int i = 0; i < limit; i++) {
                    final int idx = i;
                    MarkupModel.PdfPageItem p = pages.get(i);
                    String val = (i == cur ? "현재 · " : "") + Math.round(p.displayWidthPt()) + "×" + Math.round(p.displayHeightPt())
                            + (p.anns.isEmpty() ? "" : " · 주석 " + p.anns.size());
                    pdfGrp.row(Icons.PDF, i == cur ? Ui.ACCENT : Ui.text2(), (i + 1) + " 페이지", val, false, v -> {
                        main.stageCanvasView.setCurrentPdfPage(idx);
                        main.switchToViewerTab();
                    });
                }
                listHost.addView(pdfGrp, Widgets.MW);
            }

            // Bookmarks view
            if (filter == 5) {
                Widgets.Group bmGrp = new Widgets.Group(c, "책갈피 (" + main.bookmarks.size() + ")");
                if (main.bookmarks.isEmpty()) {
                    bmGrp.row(Icons.BOOKMARK, Ui.text2(), "저장된 책갈피가 없어요", null, false, null);
                } else {
                    for (String path : new ArrayList<>(main.bookmarks)) {
                        File f = new File(path);
                        String k = FileOps.kindOf(f.getName());
                        bmGrp.row(iconForKind(k), colorForKind(k), f.getName(),
                                f.exists() ? MainActivity.fmtBytes(f.length()) : "없음",
                                true, v -> main.openFileAtPath(path));
                    }
                }
                listHost.addView(bmGrp, Widgets.MW);
                return;
            }

            // Folder files (or recent files if no folder loaded)
            List<MainActivity.FileEntry> src = new ArrayList<>();
            if (!main.folderItems.isEmpty()) {
                src.addAll(main.folderItems);
            } else {
                for (File f : main.collectRecentFiles()) {
                    String k = FileOps.kindOf(f.getName());
                    if (!k.isEmpty()) src.add(new MainActivity.FileEntry(f.getAbsolutePath(), f.getName(), k, f.length(), f.lastModified()));
                }
            }

            List<MainActivity.FileEntry> filtered = new ArrayList<>();
            for (MainActivity.FileEntry e : src) {
                if (filter == 0
                        || (filter == 1 && "image".equals(e.kind))
                        || (filter == 2 && "pdf".equals(e.kind))
                        || (filter == 3 && "doc".equals(e.kind))
                        || (filter == 4 && "media".equals(e.kind))) {
                    filtered.add(e);
                }
            }

            String heading = main.folderItems.isEmpty() ? "최근 작업 파일" : "폴더 파일";
            Widgets.Group filesGrp = new Widgets.Group(c, heading + " (" + filtered.size() + ")");
            if (filtered.isEmpty()) {
                filesGrp.row(Icons.OPEN_FILE, Ui.ACCENT, "파일 열기", "기기에서 선택", true, v -> main.launchSystemFilePicker());
                filesGrp.row(Icons.FOLDER, 0xFF7B61FF, "폴더 열기", "폴더 전체 탐색", true, v -> main.chooseFolder());
            } else {
                for (MainActivity.FileEntry e : filtered) {
                    boolean active = e.path.equalsIgnoreCase(main.currentPath);
                    String val = (active ? "열림 · " : "") + MainActivity.fmtBytes(e.size);
                    filesGrp.row(iconForKind(e.kind), active ? Ui.ACCENT : colorForKind(e.kind),
                            e.name, val, true, v -> main.openFileAtPath(e.path));
                }
            }
            listHost.addView(filesGrp, Widgets.MW);
        }
    }

    // =========================================================================
    // 4. SETTINGS PAGE (Full Prism Settings with Toggle & GlassSlider)
    // =========================================================================
    static final class SettingsPage extends Page {
        SettingsPage(MainActivity m) {
            super(m, "설정", true);
            headerIcons = new int[]{Icons.DOWNLOAD};
            setGrouped(true);
        }

        @Override void build() {
            Context c = getContext();
            SharedPreferences p = MainActivity.prefs(c);

            // 1) Appearance (Exact Prism controls)
            Widgets.Group app = new Widgets.Group(c, "외관");
            String curTheme = p.contains("theme") ? p.getString("theme", "system")
                    : (p.getBoolean("system_theme", true) ? "system" : (p.getBoolean("dark_theme", true) ? "dark" : "light"));
            String themeLabel = "dark".equals(curTheme) ? "다크" : ("light".equals(curTheme) ? "라이트" : "시스템 설정");
            app.row(Icons.APPEARANCE, Ui.ACCENT, "테마", themeLabel, true, v -> main.pickTheme(v));

            app.row(Icons.SHAPES, 0xFF7B61FF, "앱 스타일", Ui.gStyle ? "G 스타일" : "A 스타일", true, v -> main.pickStyle(v));

            String ak = Ui.accentKey(p);
            String aName = Ui.ACCENT_NAMES[0];
            for (int i = 0; i < Ui.ACCENT_KEYS.length; i++) if (Ui.ACCENT_KEYS[i].equals(ak)) aName = Ui.ACCENT_NAMES[i];
            app.row(Icons.ADJUST, Ui.ACCENT, "강조 색상", aName, true, v -> main.pickAccent(v));

            app.toggleRow(Icons.VIEW, 0xFF0FB59D, "글래스 굴절 효과", Ui.glass, on -> {
                p.edit().putBoolean("glass", on).apply();
                Ui.glass = on;
                main.refreshVisualTheme();
            });

            // Glass transparency slider (with lifting refractive lens thumb!)
            LinearLayout gtBox = new LinearLayout(c);
            gtBox.setOrientation(LinearLayout.VERTICAL);
            gtBox.setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(16), Ui.dp(6));
            TextView gtLbl = Ui.text(c, "글래스 투명도 · " + Math.round(Ui.glassT * 100) + "%", 14.5f, Ui.medium, Ui.text());
            gtBox.addView(gtLbl, Widgets.MW);
            Widgets.GlassSlider gtSlider = new Widgets.GlassSlider(c, Ui.glassT, 20);
            gtSlider.change = val -> {
                Ui.glassT = val;
                p.edit().putFloat("glass_t", val).apply();
                gtLbl.setText("글래스 투명도 · " + Math.round(val * 100) + "%");
                main.header.invalidate();
                main.dock.invalidate();
            };
            gtBox.addView(gtSlider, Widgets.MW);
            app.card.addView(gtBox, Widgets.MW);

            // Bottom dock raise slider
            int curRaise = p.getInt("raise_dp", Dock.RAISE_DEFAULT);
            LinearLayout rBox = new LinearLayout(c);
            rBox.setOrientation(LinearLayout.VERTICAL);
            rBox.setPadding(Ui.dp(16), Ui.dp(6), Ui.dp(16), Ui.dp(10));
            TextView rLbl = Ui.text(c, "하단 바 높이 · " + curRaise + "dp", 14.5f, Ui.medium, Ui.text());
            rBox.addView(rLbl, Widgets.MW);
            Widgets.GlassSlider rSlider = new Widgets.GlassSlider(c, Ui.clamp((curRaise - 8f) / 32f, 0f, 1f), 16);
            rSlider.change = val -> {
                int dpVal = Math.round(8 + val * 32);
                p.edit().putInt("raise_dp", dpVal).apply();
                rLbl.setText("하단 바 높이 · " + dpVal + "dp");
                main.dock.invalidate();
            };
            rBox.addView(rSlider, Widgets.MW);
            app.card.addView(rBox, Widgets.MW);

            app.row(Icons.SIDEBAR, 0xFF0381FE, "폴더블 펼침 화면",
                    Ui.unfoldTablet ? "태블릿 UI" : "폴더블 레일",
                    true, v -> {
                        boolean next = !Ui.unfoldTablet;
                        p.edit().putString("unfold_ui", next ? "tablet" : "fold").apply();
                        Ui.unfoldTablet = next;
                        main.recreateChromeForTheme();
                    });
            content.addView(app, Widgets.MW);

            // 2) Viewer Options
            Widgets.Group viewer = new Widgets.Group(c, "뷰어 및 내보내기");
            viewer.row(Icons.SAVE, 0xFF28B457, "JPEG 저장 품질", main.exportQuality + "%", true, v -> main.pickExportQuality(v));
            viewer.row(Icons.PDF, 0xFFE92D44, "PDF 내보내기 해상도", main.exportDpi + " dpi", true, v -> main.pickExportDpi(v));
            viewer.row(Icons.SLIDESHOW, 0xFFF26D21, "슬라이드쇼 간격", main.slideshowIntervalSec + "초", true, v -> main.pickSlideInterval(v));
            viewer.row(Icons.CLOCK, 0xFF7B61FF, "원본 백업 폴더", null, true, v -> main.showSavedCopies(new File(c.getFilesDir(), "backups"), "원본 백업"));
            viewer.row(Icons.CAPTURE, 0xFF0FB59D, "최근 화면 캡처", null, true, v -> main.showSavedCopies(new File(c.getFilesDir(), "captures"), "최근 캡처"));
            content.addView(viewer, Widgets.MW);

            // 3) Updates & About
            Widgets.Group about = new Widgets.Group(c, "정보 및 업데이트");
            about.toggleRow(Icons.DOWNLOAD, Ui.ACCENT, "자동 업데이트 확인", p.getBoolean(Updater.PREF_AUTO, true), on ->
                    p.edit().putBoolean(Updater.PREF_AUTO, on).apply());
            about.row(Icons.DOWNLOAD, 0xFF0381FE, "지금 업데이트 확인", "GitHub Releases", true, v -> Updater.checkInteractive(main));
            about.row(Icons.INFO, Ui.text2(), "뷰어 버전", Updater.VERSION, false, null);
            content.addView(about, Widgets.MW);
        }
    }

    // =========================================================================
    // 5. SEARCH PAGE (Morphs out of Dock Search Bubble: Files + Live PDF/OCR/Doc Search)
    // =========================================================================
    static final class SearchPage extends Page {
        private LinearLayout resultsBox;
        private String lastQ = "";

        SearchPage(MainActivity m) {
            super(m, "검색", true);
            headerIcons = new int[]{};
            setGrouped(true);
        }

        @Override void build() {
            resultsBox = new LinearLayout(getContext());
            resultsBox.setOrientation(LinearLayout.VERTICAL);
            content.addView(resultsBox, Widgets.MW);
            query(lastQ, false);
        }

        void query(String q, boolean submit) {
            lastQ = q == null ? "" : q.trim();
            if (resultsBox == null) return;
            resultsBox.removeAllViews();
            Context c = getContext();

            // 1) In-document / PDF / Image OCR search actions when a file is open
            if (main.hasOpenFile()) {
                Widgets.Group docGrp = new Widgets.Group(c, "열린 파일 내 텍스트 검색");
                String label = "doc".equals(main.currentKind) ? "문서 본문 검색"
                        : ("pdf".equals(main.currentKind) ? "PDF 텍스트 / OCR 검색" : "이미지 OCR 텍스트 검색");
                docGrp.row(Icons.OCR, Ui.ACCENT,
                        lastQ.isEmpty() ? label : "\"" + lastQ + "\" 검색 실행",
                        main.searchSummaryLabel(), true, v -> {
                            main.executeSearchOrOcr(lastQ);
                            main.onExitSearch();
                            main.switchToViewerTab();
                        });
                docGrp.row(Icons.DOC, 0xFF0FB59D, "전체 텍스트 추출 보기", null, true, v -> main.showFullExtractedTextDialog());
                if (!main.currentOcrBoxes.isEmpty()) {
                    docGrp.row(Icons.NEXT, 0xFF7B61FF, "다음 검색 결과로 이동", main.searchSummaryLabel(), true, v -> {
                        main.stepSearchOrOcr(1);
                        main.onExitSearch();
                        main.switchToViewerTab();
                    });
                }
                resultsBox.addView(docGrp, Widgets.MW);
                if (submit && !lastQ.isEmpty()) {
                    main.executeSearchOrOcr(lastQ);
                }
            }

            // 2) Filter folder & recent files
            List<MainActivity.FileEntry> pool = new ArrayList<>();
            if (!main.folderItems.isEmpty()) pool.addAll(main.folderItems);
            else {
                for (File f : main.collectRecentFiles()) {
                    String k = FileOps.kindOf(f.getName());
                    if (!k.isEmpty()) pool.add(new MainActivity.FileEntry(f.getAbsolutePath(), f.getName(), k, f.length(), f.lastModified()));
                }
            }
            String low = lastQ.toLowerCase(Locale.ROOT);
            List<MainActivity.FileEntry> hits = new ArrayList<>();
            for (MainActivity.FileEntry e : pool) {
                if (low.isEmpty() || e.name.toLowerCase(Locale.ROOT).contains(low) || labelForKind(e.kind).toLowerCase(Locale.ROOT).contains(low)) {
                    hits.add(e);
                }
            }

            Widgets.Group fileGrp = new Widgets.Group(c, "파일 검색 결과 (" + hits.size() + ")");
            if (hits.isEmpty()) {
                fileGrp.row(Icons.SEARCH, Ui.text2(), "일치하는 파일이 없어요", null, false, null);
            } else {
                int limit = Math.min(hits.size(), 25);
                for (int i = 0; i < limit; i++) {
                    MainActivity.FileEntry e = hits.get(i);
                    fileGrp.row(iconForKind(e.kind), colorForKind(e.kind), e.name,
                            MainActivity.fmtBytes(e.size), true, v -> {
                                main.onExitSearch();
                                main.openFileAtPath(e.path);
                            });
                }
            }
            resultsBox.addView(fileGrp, Widgets.MW);
        }
    }
}
