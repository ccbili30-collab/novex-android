package com.openminis.app.data.creative;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/** Android file-provider fixture. Only generated test data is exposed, never user files. */
public class RepositoryFixtureProvider extends ContentProvider {
    private File root() {
        File file = new File(getContext().getCacheDir(), "repository-fixtures"); file.mkdirs();
        try { return file.getCanonicalFile(); } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    private File file(String id) throws FileNotFoundException {
        try {
            File base = root().getCanonicalFile();
            File result = id.equals("root") ? base : new File(base, id).getCanonicalFile();
            if (!result.equals(base) && !result.getPath().startsWith(base.getPath() + File.separator)) throw new SecurityException();
            return result;
        } catch (java.io.IOException e) { throw new FileNotFoundException(); }
    }
    private void erase(File file) { File[] children = file.listFiles(); if (children != null) for (File child : children) erase(child); file.delete(); }
    private void write(String name, String body) {
        try { File target = file(name); target.getParentFile().mkdirs(); try (FileOutputStream out = new FileOutputStream(target)) { out.write(body.getBytes(StandardCharsets.UTF_8)); } }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (method.equals("clear") || method.equals("seed")) erase(root());
        if (method.equals("seed")) {
            if ("bulk".equals(arg)) for (int i = 0; i < 60; i++) write(i + ".txt", "第 " + i + " 份资料");
            else {
                write("世界/历史.txt", "第一章\n海边王国始于第三纪元。\n第二章\n城主是白榆。");
                write("人物.txt", "白榆：海边王国的城主。"); write("素材.bin", "binary"); write("空文件.txt", "");
            }
        }
        return new Bundle();
    }
    private String type(File file) { return file.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : file.getName().endsWith(".txt") ? "text/plain" : "application/octet-stream"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String id = DocumentsContract.getDocumentId(uri);
        if (id.equals("blocked.txt")) throw new SecurityException("fixture metadata denied");
        String[] columns = projection == null ? new String[]{"document_id", "_display_name", "mime_type", "_size", "flags"} : projection;
        MatrixCursor result = new MatrixCursor(columns);
        try {
            File selected = file(id);
            File[] entries = "children".equals(uri.getLastPathSegment()) ? selected.listFiles() : new File[]{selected};
            if (entries == null) entries = new File[0];
            Arrays.sort(entries, Comparator.comparing(File::getName));
            for (File entry : entries) {
                Object[] row = new Object[columns.length];
                for (int i = 0; i < columns.length; i++) switch (columns[i]) {
                    case "document_id": row[i] = entry.equals(root()) ? "root" : root().toURI().relativize(entry.toURI()).getPath().replaceAll("/$", ""); break;
                    case "_display_name": row[i] = entry.equals(root()) ? "项目资料" : entry.getName(); break;
                    case "mime_type": row[i] = type(entry); break;
                    case "_size": row[i] = entry.length(); break;
                    case "flags": row[i] = 0; break;
                }
                result.addRow(row);
            }
            return result;
        } catch (FileNotFoundException error) { throw new IllegalArgumentException(error); }
    }
    @Override public String getType(Uri uri) { try { return type(file(DocumentsContract.getDocumentId(uri))); } catch (Exception error) { return "application/octet-stream"; } }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException { return ParcelFileDescriptor.open(file(DocumentsContract.getDocumentId(uri)), ParcelFileDescriptor.MODE_READ_ONLY); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
}
