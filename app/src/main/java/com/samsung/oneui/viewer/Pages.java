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
    // 1. HOME PAGE (Preserved App Icon Hero + Quick Actions + Recent Files)
    // =========================================================================
    static final class HomePage extends Page {
        private LinearLayout heroWrap;

        HomePage(MainActivity m) {
            super(m, "뷰어", true);
            headerIcons = new int[]{Icons.OPEN_FILE, Icons.MORE};
            setGrouped(true);
        }

        @Override void build() {
            Context c = getContext();
            boolean wide = main.tabletUi() || Ui.wideInner();

            // Hero card wrapper (avoids fold hinge automatically when foldBounds is set!)
            heroWrap = new LinearLayout(c);
            heroWrap.setOrientation(LinearLayout.VERTICAL);
            heroWrap.setGravity(Gravity.CENTER_HORIZONTAL);
            heroWrap.setPadding(Ui.dp(16), Ui.dp(6), Ui.dp(16), Ui.dp(8));
            applyFoldAvoidance(heroWrap);

            LinearLayout card = new LinearLayout(c);
            card.setTag("welcome-card");
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(Ui.dp(20), Ui.dp(22), Ui.dp(20), Ui.dp(20));
            GradientDrawable cardBg = Ui.round(Ui.card(), Ui.cardRadius());
            cardBg.setStroke(Math.max(1, Ui.dp(0.75f)), Ui.dark ? 0x1EFFFFFF : 0x10000000);
            card.setBackground(cardBg);
            card.setElevation(Ui.dpf(Ui.dark ? 2f : 4f));

            // Preserved Viewer App Icon (NEVER redrawn — loaded directly from assets/icon.png)
            FrameLayout iconFrame = new FrameLayout(c);
            iconFrame.setBackground(Ui.round(Ui.alpha(Ui.ACCENT, Ui.dark ? 0.16f : 0.10f), 22));
            ImageView iconView = new ImageView(c);
            iconView.setTag("welcome-icon");
            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Bitmap ic = appIcon(c);
            if (ic != null) iconView.setImageBitmap(ic);
            else iconView.setImageDrawable(VectorIcons.create(c, "folder", 36f, Ui.ACCENT));
            iconFrame.addView(iconView, new FrameLayout.LayoutParams(Ui.dp(60), Ui.dp(60), Gravity.CENTER));
            card.addView(iconFrame, new LinearLayout.LayoutParams(Ui.dp(76), Ui.dp(76)));

            TextView titleTv = Ui.text(c, "뷰어", 21f, Ui.bold, Ui.text());
            titleTv.setTag("welcome-title");
            titleTv.setGravity(Gravity.CENTER);
            titleTv.setLetterSpacing(-0.02f);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = Ui.dp(12);
            card.addView(titleTv, tlp);

            TextView subTv = Ui.text(c, "이미지 · PDF · 문서 · 미디어를 한 곳에서 보고 편집하세요", 13.5f, Ui.regular, Ui.text2());
            subTv.setSingleLine(false);
            subTv.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = Ui.dp(4);
            slp.bottomMargin = Ui.dp(18);
            card.addView(subTv, slp);

            // Primary action buttons (responsive wrap / row, >= 48dp touch height, zero ellipsis)
            LinearLayout actions = new LinearLayout(c);
            actions.setTag("welcome-actions");
            actions.setOrientation(LinearLayout.VERTICAL);

            TextView openBtn = actionPill(c, "파일 열기", true, v -> main.launchSystemFilePicker());
            openBtn.setTag("welcome-open");
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(50));
            olp.bottomMargin = Ui.dp(10);
            actions.addView(openBtn, olp);

            LinearLayout row2 = new LinearLayout(c);
            row2.setOrientation(LinearLayout.HORIZONTAL);
            TextView pasteBtn = actionPill(c, "붙여넣기", false, v -> main.pasteClipboardImage());
            pasteBtn.setTag("welcome-paste");
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, Ui.dp(48), 1f);
            plp.rightMargin = Ui.dp(8);
            row2.addView(pasteBtn, plp);

            TextView capBtn = actionPill(c, "화면 캡처", false, v -> main.launchScreenCapture());
            capBtn.setTag("welcome-capture");
            row2.addView(capBtn, new LinearLayout.LayoutParams(0, Ui.dp(48), 1f));
            actions.addView(row2, Widgets.MW);

            card.addView(actions, Widgets.MW);

            int maxCardW = Math.min(Ui.dp(520), Math.max(Ui.dp(260), Ui.screenW - main.sideInset() - Ui.dp(32)));
            if (main.foldBounds != null && main.foldBounds.height() > main.foldBounds.width()) {
                int leftPane = main.foldBounds.left - Ui.dp(28);
                int rightPane = Ui.screenW - main.sideInset() - main.foldBounds.right - Ui.dp(28);
                int pane = leftPane >= rightPane ? leftPane : rightPane;
                if (pane > Ui.dp(180)) maxCardW = Math.min(maxCardW, pane);
            }
            heroWrap.addView(card, new LinearLayout.LayoutParams(maxCardW, ViewGroup.LayoutParams.WRAP_CONTENT));
            content.addView(heroWrap, Widgets.MW);

            // Quick workspace tools group
            Widgets.Group quick = new Widgets.Group(c, "빠른 도구");
            quick.row(Icons.FOLDER, 0xFF0381FE, "폴더 열기", "저장소 탐색", true, v -> main.chooseFolder());
            quick.row(Icons.SKETCH, 0xFF7B61FF, "새 스케치 만들기", "빈 캔버스", true, v -> main.createBlankSketch());
            quick.row(Icons.CAMERA, 0xFF0FB59D, "사진 촬영 후 열기", null, true, v -> main.launchCameraCapture());
            quick.row(Icons.OPEN_FILE, 0xFFF26D21, "예제 파일 체험", "이미지·문서·PDF", true, v -> main.openSampleFiles());
            content.addView(quick, Widgets.MW);

            // Recent / Workspace files
            List<File> recents = main.collectRecentFiles();
            if (!recents.isEmpty()) {
                Widgets.Group rec = new Widgets.Group(c, "최근 파일");
                int limit = Math.min(recents.size(), wide ? 8 : 6);
                for (int i = 0; i < limit; i++) {
                    final File f = recents.get(i);
                    String kind = FileOps.kindOf(f.getName());
                    rec.row(iconForKind(kind), colorForKind(kind), f.getName(),
                            labelForKind(kind) + " · " + MainActivity.fmtBytes(f.length()),
                            true, v -> main.openFileAtPath(f.getAbsolutePath()));
                }
                content.addView(rec, Widgets.MW);
            }

            // Supported formats info caption
            content.addView(Widgets.caption(c,
                    "지원 형식: PNG · JPG · WebP · GIF · TIFF · BMP · ICO · PDF · DOCX · HWPX · XLSX · PPTX · HWP · TXT · CSV · MP4 · MP3"));
        }

        private void applyFoldAvoidance(LinearLayout wrap) {
            Rect fb = main.foldBounds;
            if (fb == null || fb.height() <= fb.width()) return;
            int availW = Ui.screenW - main.sideInset();
            int leftW = fb.left;
            int rightW = Math.max(0, availW - fb.right);
            if (leftW >= rightW) {
                wrap.setGravity(Gravity.START);
                wrap.setPadding(Ui.dp(16), Ui.dp(6), Math.max(Ui.dp(16), availW - fb.left + Ui.dp(12)), Ui.dp(8));
            } else {
                wrap.setGravity(Gravity.END);
                wrap.setPadding(fb.right + Ui.dp(12), Ui.dp(6), Ui.dp(16), Ui.dp(8));
            }
        }

        private TextView actionPill(Context c, String label, boolean primary, View.OnClickListener click) {
            TextView tv = Ui.text(c, label, 15f, Ui.semibold, primary ? 0xFFFFFFFF : Ui.text());
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
            tv.setMinHeight(Ui.dp(48));
            GradientDrawable bg = Ui.round(primary ? Ui.ACCENT : Ui.chip(), 100);
            if (!primary) bg.setStroke(Math.max(1, Ui.dp(0.75f)), Ui.dark ? 0x26FFFFFF : 0x14000000);
            tv.setBackground(bg);
            tv.setOnClickListener(click);
            Ui.press(tv, 0.96f);
            return tv;
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

        @Override void applyInsets() {
            int topSafe = Ui.statusInset + Ui.dp(68);
            int botSafe = main.bottomPadding();
            int rightSafe = main.sideInset();
            if (emptyOverlay != null) {
                int pL = Ui.dp(28), pT = topSafe + Ui.dp(16), pR = Ui.dp(28) + rightSafe, pB = botSafe + Ui.dp(16);
                if (emptyOverlay.getPaddingTop() != pT || emptyOverlay.getPaddingRight() != pR || emptyOverlay.getPaddingBottom() != pB) {
                    emptyOverlay.setPadding(pL, pT, pR, pB);
                }
            }
            if (subToolHost != null) {
                LayoutParams lp = (LayoutParams) subToolHost.getLayoutParams();
                int wantR = Ui.dp(14) + rightSafe;
                if (lp != null && (lp.topMargin != topSafe || lp.rightMargin != wantR)) {
                    lp.topMargin = topSafe;
                    lp.leftMargin = Ui.dp(14);
                    lp.rightMargin = wantR;
                    subToolHost.setLayoutParams(lp);
                }
            }
            if (main.nativeDocView != null) {
                int extraSub = (subToolHost != null && subToolHost.getVisibility() == VISIBLE) ? Ui.dp(50) : 0;
                main.nativeDocView.setSafeInsets(topSafe + extraSub, botSafe, rightSafe);
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
            super(m, "목록", true);
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
