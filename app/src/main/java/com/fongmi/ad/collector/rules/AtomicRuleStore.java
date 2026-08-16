/* RULES.JSON 文件仓库：MediaStore/旧公共目录分别采用可恢复的原子替换。 */
package com.fongmi.ad.collector.rules;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import androidx.annotation.RequiresApi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class AtomicRuleStore {
    private static final String DIRECTORY = "m3u8-ad-audio";
    private static final String FILE_NAME = "RULES.JSON";
    private static final String PENDING_NAME = "RULES.PENDING.JSON";
    private static final String BACKUP_NAME = "RULES.BACKUP.JSON";
    private static final String MIME_TYPE = "application/json";
    private static final String RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/"
            + DIRECTORY + "/";

    private final Context context;
    private final File directory;
    private final File target;

    public AtomicRuleStore(Context context) {
        this.context = context.getApplicationContext();
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        directory = new File(downloads, DIRECTORY);
        target = new File(directory, FILE_NAME);
    }

    public File getTarget() {
        return target;
    }

    public synchronized RuleDocument load() throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return loadMediaStore();
        recoverLegacyWrite();
        if (!target.isFile()) return RuleDocument.empty();
        return RuleDocumentCodec.fromBytes(readLimited(new FileInputStream(target)));
    }

    public synchronized RuleDocument save(RuleDocument document) throws IOException {
        byte[] bytes = RuleDocumentCodec.toJson(document).getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveMediaStore(bytes);
        else saveLegacy(bytes);
        return document;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private RuleDocument loadMediaStore() throws IOException {
        ContentResolver resolver = context.getContentResolver();
        recoverMediaStore(resolver);
        Uri canonical = findMedia(resolver, FILE_NAME);
        if (canonical == null) return RuleDocument.empty();
        return RuleDocumentCodec.fromBytes(readMedia(resolver, canonical));
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private void saveMediaStore(byte[] bytes) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        recoverMediaStore(resolver);
        deleteMedia(resolver, findMedia(resolver, PENDING_NAME));
        Uri pending = insertMedia(resolver, PENDING_NAME, true);
        try {
            writeMedia(resolver, pending, bytes);
            RuleDocumentCodec.fromBytes(readMedia(resolver, pending));
        } catch (IOException | RuntimeException error) {
            deleteMedia(resolver, pending);
            throw asIo("规则临时文件写入失败", error);
        }

        Uri canonical = findMedia(resolver, FILE_NAME);
        deleteMedia(resolver, findMedia(resolver, BACKUP_NAME));
        if (canonical != null) renameMedia(resolver, canonical, BACKUP_NAME, false);
        try {
            renameMedia(resolver, pending, FILE_NAME, false);
            Uri committed = findMedia(resolver, FILE_NAME);
            if (committed == null) throw new IOException("规则文件提交后不存在");
            RuleDocumentCodec.fromBytes(readMedia(resolver, committed));
            deleteMedia(resolver, findMedia(resolver, BACKUP_NAME));
        } catch (IOException | RuntimeException error) {
            Uri committed = findMedia(resolver, FILE_NAME);
            if (committed != null && committed.equals(pending)) deleteMedia(resolver, committed);
            Uri rollback = findMedia(resolver, BACKUP_NAME);
            if (rollback != null) renameMedia(resolver, rollback, FILE_NAME, false);
            throw asIo("规则文件原子替换失败", error);
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private void recoverMediaStore(ContentResolver resolver) throws IOException {
        Uri canonical = findMedia(resolver, FILE_NAME);
        Uri pending = findMedia(resolver, PENDING_NAME);
        Uri backup = findMedia(resolver, BACKUP_NAME);
        if (canonical != null) {
            // 普通损坏不是写入中断，保留主文件和备份供用户检查，拒绝静默覆盖。
            if (!isValidMedia(resolver, canonical)) {
                throw new IOException("现有 RULES.JSON 无效，已保留原文件");
            }
            deleteMedia(resolver, pending);
            deleteMedia(resolver, backup);
            return;
        }
        if (pending != null && isValidMedia(resolver, pending)) {
            renameMedia(resolver, pending, FILE_NAME, false);
            deleteMedia(resolver, backup);
            return;
        }
        deleteMedia(resolver, pending);
        if (backup != null && isValidMedia(resolver, backup)) {
            renameMedia(resolver, backup, FILE_NAME, false);
        } else {
            deleteMedia(resolver, backup);
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static Uri findMedia(ContentResolver resolver, String name) {
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.Downloads._ID};
        String selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                + MediaStore.Downloads.RELATIVE_PATH + "=?";
        try (Cursor cursor = resolver.query(collection, projection, selection,
                new String[]{name, RELATIVE_PATH}, MediaStore.Downloads.DATE_MODIFIED + " DESC")) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            return ContentUris.withAppendedId(collection, cursor.getLong(0));
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static Uri insertMedia(ContentResolver resolver, String name, boolean pending)
            throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE);
        values.put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH);
        values.put(MediaStore.Downloads.IS_PENDING, pending ? 1 : 0);
        Uri result = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (result == null) throw new IOException("无法创建公开规则文件");
        return result;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static void renameMedia(ContentResolver resolver, Uri uri, String name,
                                    boolean pending) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.IS_PENDING, pending ? 1 : 0);
        if (resolver.update(uri, values, null, null) != 1) {
            throw new IOException("无法切换公开规则文件: " + name);
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static void writeMedia(ContentResolver resolver, Uri uri, byte[] bytes)
            throws IOException {
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "rw")) {
            if (descriptor == null) throw new IOException("无法打开公开规则文件");
            try (FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor())) {
                output.getChannel().truncate(0L);
                output.write(bytes);
                output.flush();
                output.getFD().sync();
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static byte[] readMedia(ContentResolver resolver, Uri uri) throws IOException {
        InputStream input = resolver.openInputStream(uri);
        if (input == null) throw new IOException("无法读取公开规则文件");
        return readLimited(input);
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static boolean isValidMedia(ContentResolver resolver, Uri uri) {
        try {
            RuleDocumentCodec.fromBytes(readMedia(resolver, uri));
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static void deleteMedia(ContentResolver resolver, Uri uri) {
        if (uri != null) resolver.delete(uri, null, null);
    }

    private void saveLegacy(byte[] bytes) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("无法创建规则目录: " + directory);
        }
        File temporary = new File(directory, PENDING_NAME);
        File backup = new File(directory, BACKUP_NAME);
        writeAndSync(temporary, bytes);
        RuleDocumentCodec.fromBytes(readLimited(new FileInputStream(temporary)));
        if (backup.exists() && !backup.delete()) throw new IOException("无法清理旧规则备份");
        if (target.exists() && !target.renameTo(backup)) throw new IOException("无法备份当前规则文件");
        if (!temporary.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target);
            throw new IOException("无法原子替换规则文件");
        }
        if (backup.exists() && !backup.delete()) throw new IOException("规则已保存但备份清理失败");
    }

    private void recoverLegacyWrite() throws IOException {
        File temporary = new File(directory, PENDING_NAME);
        File backup = new File(directory, BACKUP_NAME);
        if (!target.exists() && backup.isFile() && !backup.renameTo(target)) {
            throw new IOException("无法恢复规则备份");
        }
        if (temporary.exists() && !temporary.delete()) throw new IOException("无法清理规则临时文件");
        if (target.exists() && backup.exists()) {
            if (!isValidLegacy(target)) {
                throw new IOException("现有 RULES.JSON 无效，已保留原文件和备份");
            }
            if (!backup.delete()) throw new IOException("无法清理规则备份");
        }
    }

    private static boolean isValidLegacy(File file) {
        try {
            RuleDocumentCodec.fromBytes(readLimited(new FileInputStream(file)));
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = source.read(buffer)) != -1) {
                total += read;
                if (total > RuleDocumentCodec.MAX_BYTES) throw new IOException("规则文件超过 4 MiB");
                output.write(buffer, 0, read);
            }
            if (total == 0) throw new IOException("规则文件为空");
            return output.toByteArray();
        }
    }

    private static void writeAndSync(File file, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
    }

    private static IOException asIo(String message, Throwable cause) {
        return cause instanceof IOException ? (IOException) cause : new IOException(message, cause);
    }
}
