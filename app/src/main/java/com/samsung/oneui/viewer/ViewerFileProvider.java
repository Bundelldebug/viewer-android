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

    private File resolveFile(Uri uri) throws FileNotFoundException {
        String p = uri.getQueryParameter("p");
        if (p == null || p.isEmpty()) throw new FileNotFoundException("Missing path");
        File f = new File(p);
        if (!f.exists()) throw new FileNotFoundException("File not found: " + p);
        return f;
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
        File file = resolveFile(uri);
        int fileMode = ParcelFileDescriptor.MODE_READ_ONLY;
        if ("w".equals(mode) || "wt".equals(mode)) {
            fileMode = ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE;
        } else if ("rw".equals(mode) || "rwt".equals(mode)) {
            fileMode = ParcelFileDescriptor.MODE_READ_WRITE;
        }
        return ParcelFileDescriptor.open(file, fileMode);
    }
}
