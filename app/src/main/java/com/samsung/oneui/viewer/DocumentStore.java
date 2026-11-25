package com.samsung.oneui.viewer;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.*;

/** Stable private working copies with explicit links back to documents granted by the user. */
final class DocumentStore {
    final Context context;
    final File root;
    final Map<String, String> sources = new LinkedHashMap<>();
    DocumentStore(Context ctx) {
        context = ctx; root = new File(ctx.getFilesDir(), "opened"); root.mkdirs();
        Object saved = Util.jsonParse(ctx.getSharedPreferences("documents", 0).getString("sources", "{}"));
        if (saved instanceof Map) for (Map.Entry<?, ?> e : ((Map<?, ?>)saved).entrySet()) sources.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
    }
    void retain(Uri uri, int flags) {
        if((flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)==0)return;
        try { context.getContentResolver().takePersistableUriPermission(uri, flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)); } catch (SecurityException ignored) {}
    }
    String register(Uri uri, String displayName) throws IOException {
        String name = displayName == null ? "파일" : displayName.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "파일";
        String key = java.util.UUID.nameUUIDFromBytes(uri.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        File dir = new File(root, key); if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("작업 폴더 생성 실패");
        String path = new File(dir, name).getAbsolutePath(); sources.put(path, uri.toString());
        context.getSharedPreferences("documents", 0).edit().putString("sources", Util.jsonStringify(sources)).apply(); return path;
    }
    File importFile(Uri uri) throws IOException {
        if ("file".equals(uri.getScheme())) return new File(uri.getPath());
        String name = null;
        try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) { if (c != null && c.moveToFirst()) name = c.getString(0); }
        if(name==null || name.trim().isEmpty())name="파일";
        if(Util.extOf(name).isEmpty()) {
            String mime=context.getContentResolver().getType(uri);
            String extension=mime==null?null:android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if(extension!=null && !extension.isEmpty())name+="."+extension;
        }
        String path = register(uri, name); copyInto(path); return new File(path);
    }
    void ensureLocal(String path) throws IOException { if (!new File(path).exists() && sources.containsKey(path)) copyInto(path); }
    private void copyInto(String path) throws IOException {
        File target = new File(path), tmp = new File(path + ".import");
        try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(sources.get(path))); OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("문서를 읽을 수 없습니다.");
            byte[] buffer = new byte[32768]; int n; while ((n = in.read(buffer)) != -1) {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("파일 열기가 취소됐습니다.");out.write(buffer, 0, n);}
        }catch(IOException error){tmp.delete();throw error;
        }
        java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    boolean hasSource(String path) { return sources.containsKey(path); }
    Map<String, Object> rename(String path, String name) {
        try {
            if (!hasSource(path)) return FileOps.rename(path, name);
            Uri uri = Uri.parse(sources.get(path)), renamed;
            if (DocumentsContract.isDocumentUri(context, uri)) renamed = DocumentsContract.renameDocument(context.getContentResolver(), uri, name);
            else { ContentValues values = new ContentValues(); values.put(OpenableColumns.DISPLAY_NAME, name); if (context.getContentResolver().update(uri, values, null, null) < 1) throw new IOException("원본의 이름을 바꿀 권한이 없습니다."); renamed = uri; }
            if (renamed == null) throw new IOException("원본의 이름을 바꿀 수 없습니다.");
            String target = register(renamed, name); java.nio.file.Files.move(new File(path).toPath(), new File(target).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            sources.remove(path); context.getSharedPreferences("documents",0).edit().putString("sources",Util.jsonStringify(sources)).apply();
            return Util.D("success",true,"path",target);
        } catch (Exception e) { return Util.D("success",false,"error",e.getMessage()); }
    }
    Map<String, Object> delete(String path) {
        try {
            if (hasSource(path)) {
                Uri uri = Uri.parse(sources.get(path));
                boolean deleted = DocumentsContract.isDocumentUri(context, uri) ? DocumentsContract.deleteDocument(context.getContentResolver(), uri) : context.getContentResolver().delete(uri,null,null) > 0;
                if (!deleted) throw new IOException("원본을 삭제할 권한이 없습니다.");
                sources.remove(path); context.getSharedPreferences("documents",0).edit().putString("sources",Util.jsonStringify(sources)).apply();
            }
            return FileOps.delete(path);
        } catch (Exception e) { return Util.D("success",false,"error",e.getMessage()); }
    }
    void publish(String path) throws IOException {
        if (!hasSource(path)) return;
        try (InputStream in = new FileInputStream(path); OutputStream out = context.getContentResolver().openOutputStream(Uri.parse(sources.get(path)), "wt")) {
            if (out == null) throw new IOException("원본 위치에 쓸 수 없습니다. 다른 이름으로 저장해 주세요.");
            byte[] buffer = new byte[32768]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
    }
    static final class Entry {
        Uri uri; String name; boolean directory; long size, modified;
    }
    List<Entry> children(Uri tree, String parentId) {
        List<Entry> entries = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] projection = { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED };
        try (Cursor c = context.getContentResolver().query(children, projection, null, null, null)) {
            while (c != null && c.moveToNext()) {
                Entry entry = new Entry(); entry.uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)); entry.name = c.getString(1);
                entry.directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2)); entry.size = c.getLong(3); entry.modified = c.getLong(4);
                if (entry.directory || !FileOps.kindOf(entry.name).isEmpty()) entries.add(entry);
            }
        }
        entries.sort((a, b) -> a.directory != b.directory ? (a.directory ? -1 : 1) : Util.NATURAL_SORT.compare(a.name, b.name)); return entries;
    }
}
