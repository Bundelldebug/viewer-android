package com.samsung.oneui.viewer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Locale;

public class ViewerFileProvider extends ContentProvider {
    public static final String AUTHORITY = "com.samsung.oneui.viewer.fileprovider";
    private static final java.util.Set<String> WRITE_TARGETS = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public static void allowWriteTarget(File file) {
        if (file == null) return;
        try { WRITE_TARGETS.add(file.getCanonicalPath()); } catch (Exception ignored) {}
    }

    public static boolean isWriteAllowed(File file) {
        if (file == null) return false;
        try { return WRITE_TARGETS.contains(file.getCanonicalPath()); } catch (Exception ignored) { return false; }
    }

    public static boolean isAllowedFile(android.content.Context ctx, File file) {
        if (ctx == null || file == null) return false;
        try {
            String canon = file.getCanonicalPath();
            String sep = File.separator;
            // Explicitly block app private metadata directories
            File dataDir = ctx.getApplicationInfo() != null && ctx.getApplicationInfo().dataDir != null
                    ? new File(ctx.getApplicationInfo().dataDir) : null;
            if (dataDir != null) {
                String dataCanon = dataDir.getCanonicalPath();
                if (canon.equals(dataCanon)
                        || canon.startsWith(dataCanon + sep + "shared_prefs")
                        || canon.startsWith(dataCanon + sep + "databases")
                        || canon.startsWith(dataCanon + sep + "code_cache")
                        || canon.startsWith(dataCanon + sep + "no_backup")
                        || canon.startsWith(dataCanon + sep + "files")) {
                    return false;
                }
            }
            java.util.List<File> roots = new java.util.ArrayList<>();
            if (ctx.getCacheDir() != null) roots.add(ctx.getCacheDir());
            if (ctx.getExternalCacheDir() != null) roots.add(ctx.getExternalCacheDir());
            File extFiles = ctx.getExternalFilesDir(null);
            if (extFiles != null) roots.add(extFiles);
            try {
                File extRoot = android.os.Environment.getExternalStorageDirectory();
                if (extRoot != null) roots.add(extRoot);
            } catch (Throwable ignored) {}
            for (File r : roots) {
                if (r == null) continue;
                String rc = r.getCanonicalPath();
                if (canon.startsWith(rc + sep)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static Uri uriForFile(File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("file")
                .appendQueryParameter("p", file.getAbsolutePath())
                .appendPath(file.getName())
                .build();
    }

    public static Uri getUriForFile(android.content.Context ctx, File file) {
        return uriForFile(file);
    }

    private File resolveFile(Uri uri, boolean allowMissingWriteTarget) throws FileNotFoundException {
        String p = uri.getQueryParameter("p");
        if (p == null || p.isEmpty()) throw new FileNotFoundException("Missing path");
        File f = new File(p);
        android.content.Context ctx = getContext();
        if (ctx != null && !isAllowedFile(ctx, f)) {
            throw new SecurityException("Access denied outside allowed directories");
        }
        if (!f.exists()) {
            if (allowMissingWriteTarget && isWriteAllowed(f)) return f;
            throw new FileNotFoundException("File not found: " + p);
        }
        if (!f.isFile()) throw new FileNotFoundException("Not a regular file");
        return f;
    }

    private File resolveFile(Uri uri) throws FileNotFoundException {
        return resolveFile(uri, false);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = resolveFile(uri);
            if (projection == null) {
                projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            }
            String[] cols = new String[projection.length];
            Object[] values = new Object[projection.length];
            int i = 0;
            for (String col : projection) {
                if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                    cols[i] = OpenableColumns.DISPLAY_NAME;
                    values[i++] = file.getName();
                } else if (OpenableColumns.SIZE.equals(col)) {
                    cols[i] = OpenableColumns.SIZE;
                    values[i++] = file.length();
                }
            }
            MatrixCursor cursor = new MatrixCursor(cols, 1);
            cursor.addRow(values);
            return cursor;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        try {
            File file = resolveFile(uri);
            String ext = Util.extOf(file.getName()).replace(".", "").toLowerCase(Locale.ROOT);
            if ("pdf".equals(ext)) return "application/pdf";
            if ("hwp".equals(ext)) return "application/x-hwp";
            if ("hwpx".equals(ext)) return "application/vnd.hancom.hwpx";
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            return mime != null ? mime : "application/octet-stream";
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        boolean wantsWrite = mode != null && (mode.contains("w") || mode.contains("W"));
        File file = resolveFile(uri, wantsWrite);
        if (wantsWrite) {
            if (!isWriteAllowed(file)) {
                throw new SecurityException("Write access denied for this URI");
            }
            int fileMode = ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE;
            if (mode.contains("r")) fileMode = ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE;
            return ParcelFileDescriptor.open(file, fileMode);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
