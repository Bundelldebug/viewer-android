package com.samsung.oneui.viewer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.os.Environment;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class FileOps {
    private FileOps() {}

    public static final Set<String> WEB = new HashSet<>(Arrays.asList(
            ".jpg", ".jpeg", ".jpe", ".jfif", ".png", ".gif", ".webp", ".avif", ".bmp", ".svg", ".ico", ".apng"
    ));

    public static final Set<String> EXTRA_IMG = new HashSet<>(Arrays.asList(
            ".tif", ".tiff", ".heic", ".heif", ".jxr", ".wdp", ".dng", ".cr2", ".nef", ".arw", ".raf", ".orf",
            ".rw2", ".pef", ".srw", ".nrw", ".sr2", ".srf", ".3fr", ".erf", ".kdc", ".mrw", ".dcr", ".dib",
            ".cur", ".dds", ".jxl", ".jp2", ".j2k"
    ));

    public static final Set<String> MEDIA = new HashSet<>(Arrays.asList(
            ".mp4", ".m4v", ".webm", ".ogv", ".mp3", ".wav", ".ogg", ".m4a", ".flac", ".aac", ".opus", ".mov", ".mkv"
    ));

    public static String kindOf(String path) {
        String e = Util.extOf(path);
        if (".pdf".equals(e)) return "pdf";
        if (WEB.contains(e) || EXTRA_IMG.contains(e) || Codecs.CUSTOM.contains(e)) return "image";
        if (Docs.EXTS.contains(e)) return "doc";
        if (MEDIA.contains(e)) return "media";
        return "";
    }

    public static String mimeOf(String ext) {
        if (ext == null) return "application/octet-stream";
        switch (ext.toLowerCase(Locale.ROOT)) {
            case ".jpg":
            case ".jpeg":
            case ".jpe":
            case ".jfif":
                return "image/jpeg";
            case ".png":
            case ".apng":
                return "image/png";
            case ".gif":
                return "image/gif";
            case ".webp":
                return "image/webp";
            case ".avif":
                return "image/avif";
            case ".bmp":
                return "image/bmp";
            case ".svg":
                return "image/svg+xml";
            case ".ico":
                return "image/x-icon";
            default:
                return "application/octet-stream";
        }
    }

    private static String fmtDate(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(ms));
    }

    public static Map<String, Object> stat(String path) {
        try {
            if (path == null || path.isEmpty()) return Util.D("error", "파일을 찾을 수 없습니다.");
            File f = new File(path);
            if (!f.exists()) return Util.D("error", "파일을 찾을 수 없습니다.");
            if (f.isDirectory()) {
                int files = 0, dirs = 0;
                File[] children = f.listFiles();
                if (children != null) {
                    for (File c : children) {
                        if (c.isDirectory()) dirs++;
                        else files++;
                    }
                }
                return Util.D(
                        "isDir", true,
                        "name", f.getName(),
                        "path", f.getAbsolutePath(),
                        "modified", fmtDate(f.lastModified()),
                        "files", files,
                        "dirs", dirs
                );
            }
            return Util.D(
                    "isDir", false,
                    "name", f.getName(),
                    "path", f.getAbsolutePath(),
                    "size", f.length(),
                    "modified", fmtDate(f.lastModified()),
                    "created", fmtDate(f.lastModified())
            );
        } catch (Exception e) {
            return Util.D("error", e.getMessage());
        }
    }

    public static Map<String, Object> list(String path, String sort) {
        List<Object> items = new ArrayList<>();
        try {
            if (path == null || path.isEmpty()) return Util.D("dir", "", "items", items);
            File f = new File(path);
            File dir = f.isDirectory() ? f : f.getParentFile();
            if (dir == null || !dir.exists()) return Util.D("dir", "", "items", items);
            File[] raw = dir.listFiles();
            List<File> files = new ArrayList<>();
            if (raw != null) {
                for (File child : raw) {
                    if (child.isFile() && !kindOf(child.getName()).isEmpty()) {
                        files.add(child);
                    }
                }
            }
            if ("date".equals(sort)) {
                Collections.sort(files, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return Long.compare(b.lastModified(), a.lastModified());
                    }
                });
            } else if ("size".equals(sort)) {
                Collections.sort(files, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return Long.compare(b.length(), a.length());
                    }
                });
            } else {
                Collections.sort(files, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return Util.NATURAL_SORT.compare(a.getName(), b.getName());
                    }
                });
            }
            for (File fi : files) {
                items.add(Util.D(
                        "path", fi.getAbsolutePath(),
                        "name", fi.getName(),
                        "kind", kindOf(fi.getName()),
                        "size", fi.length(),
                        "modified", fmtDate(fi.lastModified())
                ));
            }
            return Util.D("dir", dir.getAbsolutePath(), "items", items);
        } catch (Exception e) {
            return Util.D("dir", "", "items", items, "error", e.getMessage());
        }
    }

    // Folder & file browser for Android storage + app workspace
    public static Map<String, Object> browse(String dirPath, File appWorkspaceDir) {
        List<Object> dirs = new ArrayList<>();
        List<Object> files = new ArrayList<>();
        List<Object> roots = new ArrayList<>();
        try {
            if (appWorkspaceDir != null && appWorkspaceDir.exists()) {
                roots.add(Util.D("name", "샘플 및 작업 폴더", "path", appWorkspaceDir.getAbsolutePath()));
            }
            File extRoot = Environment.getExternalStorageDirectory();
            if (extRoot != null && extRoot.exists()) {
                roots.add(Util.D("name", "내장 저장공간", "path", extRoot.getAbsolutePath()));
                String[][] common = new String[][]{
                        {"Download", "다운로드"},
                        {"Pictures", "사진 (Pictures)"},
                        {"DCIM", "카메라 (DCIM)"},
                        {"Documents", "문서 (Documents)"}
                };
                for (String[] c : common) {
                    File cf = new File(extRoot, c[0]);
                    if (cf.exists() && cf.isDirectory()) {
                        roots.add(Util.D("name", c[1], "path", cf.getAbsolutePath()));
                    }
                }
            }
            File target = (dirPath == null || dirPath.isEmpty())
                    ? (appWorkspaceDir != null ? appWorkspaceDir : extRoot)
                    : new File(dirPath);
            if (target == null || !target.exists() || !target.isDirectory()) {
                target = appWorkspaceDir != null ? appWorkspaceDir : extRoot;
            }
            File[] children = target != null ? target.listFiles() : null;
            List<File> dList = new ArrayList<>();
            List<File> fList = new ArrayList<>();
            if (children != null) {
                for (File ch : children) {
                    if (ch.getName().startsWith(".")) continue;
                    if (ch.isDirectory()) dList.add(ch);
                    else if (!kindOf(ch.getName()).isEmpty()) fList.add(ch);
                }
            }
            Comparator<File> cmp = new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Util.NATURAL_SORT.compare(a.getName(), b.getName());
                }
            };
            Collections.sort(dList, cmp);
            Collections.sort(fList, cmp);
            for (File d : dList) {
                dirs.add(Util.D("name", d.getName(), "path", d.getAbsolutePath(), "modified", fmtDate(d.lastModified())));
            }
            for (File f : fList) {
                files.add(Util.D(
                        "name", f.getName(),
                        "path", f.getAbsolutePath(),
                        "kind", kindOf(f.getName()),
                        "size", f.length(),
                        "modified", fmtDate(f.lastModified())
                ));
            }
            String parent = (target != null && target.getParentFile() != null && target.getParentFile().canRead())
                    ? target.getParentFile().getAbsolutePath() : "";
            return Util.D(
                    "ok", true,
                    "dir", target != null ? target.getAbsolutePath() : "",
                    "parent", parent,
                    "roots", roots,
                    "dirs", dirs,
                    "files", files
            );
        } catch (Exception e) {
            return Util.D("ok", false, "error", e.getMessage(), "roots", roots, "dirs", dirs, "files", files);
        }
    }

    private static Bitmap applyExifOrientation(Bitmap bmp, String path) {
        if (bmp == null) return null;
        try {
            ExifInterface exif = new ExifInterface(path);
            int ori = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            Matrix m = new Matrix();
            switch (ori) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                    m.setScale(-1, 1);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    m.setRotate(180);
                    break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    m.setScale(1, -1);
                    break;
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    m.setRotate(90);
                    m.postScale(-1, 1);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_90:
                    m.setRotate(90);
                    break;
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    m.setRotate(-90);
                    m.postScale(-1, 1);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    m.setRotate(-90);
                    break;
                default:
                    return bmp;
            }
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (rotated != bmp) bmp.recycle();
            return rotated;
        } catch (Exception e) {
            return bmp;
        }
    }

    private static byte[] decodeBitmapToPng(String path, int maxSide) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int sample = 1;
        if (maxSide > 0 && Math.max(bounds.outWidth, bounds.outHeight) > maxSide) {
            while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > maxSide * 2) {
                sample *= 2;
            }
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeFile(path, opts);
        if (bmp == null) {
            bmp = Codecs.decode(path);
        } else {
            bmp = applyExifOrientation(bmp, path);
        }
        if (maxSide > 0 && Math.max(bmp.getWidth(), bmp.getHeight()) > maxSide) {
            double sc = (double) maxSide / Math.max(bmp.getWidth(), bmp.getHeight());
            int nw = Math.max(1, (int) Math.round(bmp.getWidth() * sc));
            int nh = Math.max(1, (int) Math.round(bmp.getHeight() * sc));
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true);
            if (scaled != bmp) bmp.recycle();
            bmp = scaled;
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
            return bos.toByteArray();
        } finally {
            bmp.recycle();
        }
    }

    public static Map<String, Object> read(String path) {
        if (path == null || path.isEmpty()) return Util.D("error", "파일을 찾을 수 없습니다.");
        File fi = new File(path);
        if (!fi.exists()) return Util.D("error", "파일을 찾을 수 없습니다.");
        try {
            String ext = Util.extOf(path);
            byte[] data;
            String mime;
            boolean converted = false;
            if (Codecs.CUSTOM.contains(ext) && !".ico".equals(ext)) {
                data = Codecs.toPng(path);
                mime = "image/png";
                converted = true;
            } else if (EXTRA_IMG.contains(ext)) {
                data = decodeBitmapToPng(path, 0);
                mime = "image/png";
                converted = true;
            } else {
                data = Util.readAllBytes(fi);
                mime = mimeOf(ext);
                if ("application/octet-stream".equals(mime)) {
                    String m = Codecs.mimeFromMagic(data);
                    if (m != null) {
                        mime = m;
                    } else {
                        data = decodeBitmapToPng(path, 0);
                        mime = "image/png";
                        converted = true;
                    }
                }
            }
            return Util.D(
                    "name", fi.getName(),
                    "path", fi.getAbsolutePath(),
                    "mime", mime,
                    "data", Util.b64Encode(data),
                    "size", fi.length(),
                    "modified", fmtDate(fi.lastModified()),
                    "converted", converted,
                    "ext", ext
            );
        } catch (Exception e) {
            return Util.D("error", "이미지를 열 수 없습니다: " + e.getMessage());
        }
    }

    public static Map<String, Object> readAs(String path) {
        if (path == null || path.isEmpty()) return Util.D("error", "파일을 찾을 수 없습니다.");
        File fi = new File(path);
        if (!fi.exists()) return Util.D("error", "파일을 찾을 수 없습니다.");
        try {
            byte[] data = decodeBitmapToPng(path, 0);
            return Util.D(
                    "name", fi.getName(),
                    "path", fi.getAbsolutePath(),
                    "mime", "image/png",
                    "data", Util.b64Encode(data),
                    "size", fi.length(),
                    "modified", fmtDate(fi.lastModified()),
                    "converted", true,
                    "ext", Util.extOf(path)
            );
        } catch (Exception e) {
            return Util.D("error", "이 형식은 열 수 없습니다: " + e.getMessage());
        }
    }

    public static String thumb(String path, int size) {
        if (size < 32) size = 32;
        if (size > 512) size = 512;
        try {
            File f = new File(path);
            if (!f.exists()) return null;
            String ext = Util.extOf(path);
            if (".svg".equals(ext)) {
                byte[] svgBytes = Util.readAllBytes(f);
                return "data:image/svg+xml;base64," + Util.b64Encode(svgBytes);
            }
            Bitmap src;
            if (Codecs.CUSTOM.contains(ext)) {
                Bitmap full = Codecs.decode(path);
                double sc = Math.min(1.0, (double) size / Math.max(full.getWidth(), full.getHeight()));
                int tw = Math.max(1, (int) Math.round(full.getWidth() * sc));
                int th = Math.max(1, (int) Math.round(full.getHeight() * sc));
                src = Bitmap.createScaledBitmap(full, tw, th, true);
                if (src != full) full.recycle();
            } else {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, bounds);
                int sample = 1;
                while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > size * 2) {
                    sample *= 2;
                }
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                Bitmap decoded = BitmapFactory.decodeFile(path, opts);
                if (decoded == null) return null;
                decoded = applyExifOrientation(decoded, path);
                double sc = Math.min(1.0, (double) size / Math.max(decoded.getWidth(), decoded.getHeight()));
                int tw = Math.max(1, (int) Math.round(decoded.getWidth() * sc));
                int th = Math.max(1, (int) Math.round(decoded.getHeight() * sc));
                src = Bitmap.createScaledBitmap(decoded, tw, th, true);
                if (src != decoded) decoded.recycle();
            }
            try {
                boolean alpha = ".png".equals(ext) || ".gif".equals(ext) || ".ico".equals(ext) || ".webp".equals(ext);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                if (alpha) {
                    src.compress(Bitmap.CompressFormat.PNG, 90, bos);
                    return "data:image/png;base64," + Util.b64Encode(bos.toByteArray());
                } else {
                    Bitmap flat = flatten(src);
                    flat.compress(Bitmap.CompressFormat.JPEG, 78, bos);
                    flat.recycle();
                    return "data:image/jpeg;base64," + Util.b64Encode(bos.toByteArray());
                }
            } finally {
                src.recycle();
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static Map<String, Object> info(String path) {
        Map<String, Object> d = new LinkedHashMap<>();
        try {
            File fi = new File(path);
            if (!fi.exists()) {
                d.put("error", "파일을 찾을 수 없습니다.");
                return d;
            }
            d.put("name", fi.getName());
            d.put("path", fi.getAbsolutePath());
            d.put("dir", fi.getParent());
            d.put("size", fi.length());
            d.put("created", fmtDate(fi.lastModified()));
            d.put("modified", fmtDate(fi.lastModified()));
            String ext = Util.extOf(path);
            d.put("ext", ext.replace(".", "").toUpperCase(Locale.ROOT));

            if ("image".equals(kindOf(path))) {
                List<Object> meta = AiMeta.read(path);
                if (!meta.isEmpty()) d.put("meta", meta);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, bounds);
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    d.put("pixelW", bounds.outWidth);
                    d.put("pixelH", bounds.outHeight);
                    d.put("colorModel", ".png".equals(ext) || ".webp".equals(ext) || ".ico".equals(ext) ? "RGB + 알파" : "RGB");
                    d.put("depth", 32);
                }
                try {
                    ExifInterface exif = new ExifInterface(path);
                    String make = exif.getAttribute(ExifInterface.TAG_MAKE);
                    String model = exif.getAttribute(ExifInterface.TAG_MODEL);
                    if (make != null || model != null) {
                        d.put("camera", ((make != null ? make : "") + " " + (model != null ? model : "")).trim());
                    }
                    String lens = exif.getAttribute("LensModel");
                    if (lens != null) d.put("lens", lens);
                    String taken = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
                    if (taken == null) taken = exif.getAttribute(ExifInterface.TAG_DATETIME);
                    if (taken != null) {
                        d.put("taken", taken.length() >= 19 ? taken.substring(0, 10).replace(':', '-') + " " + taken.substring(11, 19) : taken);
                    }
                    String exp = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
                    if (exp != null) {
                        double ev = Util.toDouble(exp, 0);
                        if (ev > 0) {
                            d.put("exposure", ev >= 1 ? String.format(Locale.US, "%.1f초", ev) : "1/" + Math.round(1.0 / ev) + "초");
                        }
                    }
                    String fn = exif.getAttribute(ExifInterface.TAG_F_NUMBER);
                    if (fn != null) d.put("aperture", "f/" + fn);
                    String fl = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH);
                    if (fl != null) {
                        if (fl.contains("/")) {
                            String[] p = fl.split("/");
                            double n = Util.toDouble(p[0], 0), den = Util.toDouble(p[1], 1);
                            if (den > 0) d.put("focal", String.format(Locale.US, "%.1fmm", n / den));
                        } else {
                            d.put("focal", fl + "mm");
                        }
                    }
                    String iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS);
                    if (iso != null) d.put("iso", iso);
                    String sw = exif.getAttribute(ExifInterface.TAG_SOFTWARE);
                    if (sw != null) d.put("software", sw);
                    float[] latLong = new float[2];
                    if (exif.getLatLong(latLong)) {
                        d.put("gps", String.format(Locale.US, "%.5f, %.5f", latLong[0], latLong[1]));
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            d.put("error", e.getMessage());
        }
        return d;
    }

    public static Map<String, Object> save(String path, String base64, String mime, int quality) {
        try {
            if (path == null || path.isEmpty() || base64 == null || base64.isEmpty()) {
                return Util.D("success", false, "error", "저장할 데이터가 없습니다.");
            }
            byte[] data = Util.b64Decode(base64);
            String ext = Util.extOf(path);
            String want = ".png".equals(ext) ? "image/png" : (".jpg".equals(ext) || ".jpeg".equals(ext)) ? "image/jpeg" : ".webp".equals(ext) ? "image/webp" : "";
            byte[] outBytes = data;
            if (want.isEmpty() || !want.equals(mime)) {
                Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (src == null) return Util.D("success", false, "error", "이미지를 디코딩할 수 없습니다.");
                try {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    int q = quality <= 0 ? 90 : Math.min(100, quality);
                    switch (ext) {
                        case ".png":
                            src.compress(Bitmap.CompressFormat.PNG, 100, bos);
                            outBytes = bos.toByteArray();
                            break;
                        case ".jpg":
                        case ".jpeg": {
                            Bitmap flat = flatten(src);
                            flat.compress(Bitmap.CompressFormat.JPEG, q, bos);
                            flat.recycle();
                            outBytes = bos.toByteArray();
                            break;
                        }
                        case ".webp":
                            src.compress(Bitmap.CompressFormat.WEBP, q, bos);
                            outBytes = bos.toByteArray();
                            break;
                        case ".bmp":
                            outBytes = Codecs.encodeBmp(src);
                            break;
                        case ".gif":
                            outBytes = Codecs.encodeGif(src);
                            break;
                        case ".tif":
                        case ".tiff":
                            outBytes = Codecs.encodeTiff(src);
                            break;
                        case ".ico":
                            outBytes = Codecs.encodeIco(src);
                            break;
                        default:
                            src.compress(Bitmap.CompressFormat.PNG, 100, bos);
                            outBytes = bos.toByteArray();
                            break;
                    }
                } finally {
                    src.recycle();
                }
            }
            File target = new File(path);
            File tmp = new File(path + ".tmp");
            Util.writeAllBytes(tmp, outBytes);
            if (target.exists()) target.delete();
            if (!tmp.renameTo(target)) {
                Util.writeAllBytes(target, outBytes);
                tmp.delete();
            }
            return Util.D("success", true, "path", target.getAbsolutePath(), "size", outBytes.length);
        } catch (Exception e) {
            return Util.D("success", false, "error", e.getMessage());
        }
    }

    private static Bitmap flatten(Bitmap src) {
        Bitmap flat = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(flat);
        c.drawColor(Color.WHITE);
        c.drawBitmap(src, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
        return flat;
    }

    public static Map<String, Object> delete(String path) {
        try {
            if (path == null || path.isEmpty()) return Util.D("success", false, "error", "파일을 찾을 수 없습니다.");
            File f = new File(path);
            if (!f.exists()) return Util.D("success", false, "error", "파일을 찾을 수 없습니다.");
            if (!f.delete()) return Util.D("success", false, "error", "파일을 삭제할 권한이 없습니다.");
            return Util.D("success", true);
        } catch (Exception e) {
            return Util.D("success", false, "error", e.getMessage());
        }
    }

    public static Map<String, Object> rename(String path, String newName) {
        try {
            if (path == null || path.isEmpty()) return Util.D("success", false, "error", "파일을 찾을 수 없습니다.");
            File f = new File(path);
            if (!f.exists()) return Util.D("success", false, "error", "파일을 찾을 수 없습니다.");
            if (newName == null || newName.trim().isEmpty() || newName.contains("/") || newName.contains("\\")) {
                return Util.D("success", false, "error", "사용할 수 없는 이름입니다.");
            }
            File dest = new File(f.getParentFile(), newName.trim());
            if (dest.exists() && !dest.getAbsolutePath().equalsIgnoreCase(f.getAbsolutePath())) {
                return Util.D("success", false, "error", "같은 이름의 파일이 이미 있습니다.");
            }
            if (!f.renameTo(dest)) {
                Util.writeAllBytes(dest, Util.readAllBytes(f));
                f.delete();
            }
            return Util.D("success", true, "path", dest.getAbsolutePath(), "name", dest.getName());
        } catch (Exception e) {
            return Util.D("success", false, "error", e.getMessage());
        }
    }

    public static Map<String, Object> copyTo(String path, String folder, boolean move) {
        try {
            File src = new File(path);
            File dir = new File(folder);
            if (!src.exists() || !dir.exists() || !dir.isDirectory()) {
                return Util.D("success", false, "error", "경로를 찾을 수 없습니다.");
            }
            String base = src.getName();
            String ext = Util.extOf(base);
            String stem = ext.isEmpty() ? base : base.substring(0, base.length() - ext.length());
            File dest = new File(dir, base);
            int n = 1;
            while (dest.exists()) {
                dest = new File(dir, stem + " (" + (n++) + ")" + ext);
            }
            if (move && src.renameTo(dest)) {
                return Util.D("success", true, "path", dest.getAbsolutePath());
            }
            Util.writeAllBytes(dest, Util.readAllBytes(src));
            if (move) src.delete();
            return Util.D("success", true, "path", dest.getAbsolutePath());
        } catch (Exception e) {
            return Util.D("success", false, "error", e.getMessage());
        }
    }

    // Direct Bitmap saving for Pure Native Android Editor
    public static Map<String, Object> saveBitmap(String path, Bitmap src, int quality) {
        try {
            if (path == null || path.isEmpty() || src == null) {
                return Util.D("success", false, "error", "저장할 이미지가 없습니다.");
            }
            String ext = Util.extOf(path);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int q = quality <= 0 ? 92 : Math.min(100, quality);
            byte[] outBytes;
            switch (ext) {
                case ".png":
                    src.compress(Bitmap.CompressFormat.PNG, 100, bos);
                    outBytes = bos.toByteArray();
                    break;
                case ".jpg":
                case ".jpeg": {
                    Bitmap flat = flatten(src);
                    flat.compress(Bitmap.CompressFormat.JPEG, q, bos);
                    flat.recycle();
                    outBytes = bos.toByteArray();
                    break;
                }
                case ".webp":
                    src.compress(Bitmap.CompressFormat.WEBP, q, bos);
                    outBytes = bos.toByteArray();
                    break;
                case ".bmp":
                    outBytes = Codecs.encodeBmp(src);
                    break;
                case ".gif":
                    outBytes = Codecs.encodeGif(src);
                    break;
                case ".tif":
                case ".tiff":
                    outBytes = Codecs.encodeTiff(src);
                    break;
                case ".ico":
                    outBytes = Codecs.encodeIco(src);
                    break;
                default:
                    src.compress(Bitmap.CompressFormat.PNG, 100, bos);
                    outBytes = bos.toByteArray();
                    break;
            }
            File target = new File(path);
            File tmp = new File(path + ".tmp");
            Util.writeAllBytes(tmp, outBytes);
            if (target.exists()) target.delete();
            if (!tmp.renameTo(target)) {
                Util.writeAllBytes(target, outBytes);
                tmp.delete();
            }
            return Util.D("success", true, "path", target.getAbsolutePath(), "size", outBytes.length);
        } catch (Exception e) {
            return Util.D("success", false, "error", e.getMessage());
        }
    }

    // Non-Rooted Android MediaStore query (Images, Videos, Audio, Downloads)
    public static final class MediaItem {
        public final android.net.Uri uri;
        public final String name;
        public final long size;
        public final long dateModifiedSec;
        public final String mime;
        public final String localPath; // May be accessible or null on scoped storage

        public MediaItem(android.net.Uri uri, String name, long size, long dateModifiedSec, String mime, String localPath) {
            this.uri = uri;
            this.name = name;
            this.size = size;
            this.dateModifiedSec = dateModifiedSec;
            this.mime = mime;
            this.localPath = localPath;
        }
    }

    public static List<MediaItem> queryMediaStore(android.content.Context ctx, String category) {
        List<MediaItem> list = new ArrayList<>();
        try {
            android.net.Uri collection;
            if ("images".equals(category)) {
                collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            } else if ("videos".equals(category)) {
                collection = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            } else if ("audio".equals(category)) {
                collection = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            } else if ("downloads".equals(category) && android.os.Build.VERSION.SDK_INT >= 29) {
                collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            } else {
                collection = android.provider.MediaStore.Files.getContentUri("external");
            }
            String[] proj = new String[]{
                    android.provider.MediaStore.MediaColumns._ID,
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                    android.provider.MediaStore.MediaColumns.SIZE,
                    android.provider.MediaStore.MediaColumns.DATE_MODIFIED,
                    android.provider.MediaStore.MediaColumns.MIME_TYPE,
                    android.provider.MediaStore.MediaColumns.DATA
            };
            android.database.Cursor c = ctx.getContentResolver().query(
                    collection, proj, null, null,
                    android.provider.MediaStore.MediaColumns.DATE_MODIFIED + " DESC"
            );
            if (c != null) {
                try {
                    int idCol = c.getColumnIndex(android.provider.MediaStore.MediaColumns._ID);
                    int nameCol = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME);
                    int sizeCol = c.getColumnIndex(android.provider.MediaStore.MediaColumns.SIZE);
                    int dateCol = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATE_MODIFIED);
                    int mimeCol = c.getColumnIndex(android.provider.MediaStore.MediaColumns.MIME_TYPE);
                    int dataCol = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA);
                    int count = 0;
                    while (c.moveToNext() && count < 300) {
                        long id = idCol >= 0 ? c.getLong(idCol) : 0;
                        String name = nameCol >= 0 ? c.getString(nameCol) : ("file_" + id);
                        if (name == null || kindOf(name).isEmpty()) continue;
                        long size = sizeCol >= 0 ? c.getLong(sizeCol) : 0;
                        long date = dateCol >= 0 ? c.getLong(dateCol) : 0;
                        String mime = mimeCol >= 0 ? c.getString(mimeCol) : "";
                        String dataPath = dataCol >= 0 ? c.getString(dataCol) : null;
                        android.net.Uri itemUri = android.content.ContentUris.withAppendedId(collection, id);
                        list.add(new MediaItem(itemUri, name, size, date, mime, dataPath));
                        count++;
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {}
        return list;
    }
}
