package ru.nexora.client;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs a small, texture-only NEXORA resource pack from a user-selected ZIP.
 * Only known texture filenames are extracted; all other archive paths are ignored.
 */
final class NexoraResourcePackInstaller {
    private static final String TEXTURE_PREFIX = "textures/blocks/";
    private static final long MAX_FILE_BYTES = 4L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 128;
    private static final int MAX_DIMENSION = 1024;
    private static final Set<String> ALLOWED = Set.of(
            "grass_top.png", "grass_side.png", "dirt.png", "stone.png",
            "sand.png", "gravel.png", "log_side.png", "log_top.png",
            "leaves.png", "water.png", "snow.png", "bedrock.png",
            "planks.png", "clay.png", "deepslate.png");

    private NexoraResourcePackInstaller() {
    }

    static int install(Context context, Uri source) throws Exception {
        if (context == null || source == null) throw new IllegalArgumentException("Файл не выбран");
        File root = new File(context.getFilesDir(), "resourcepacks");
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("Не удалось создать папку паков");

        File staging = new File(root, ".staging-" + UUID.randomUUID());
        File active = new File(root, "active");
        File backup = new File(root, ".backup-" + UUID.randomUUID());
        if (!staging.mkdirs()) throw new IllegalStateException("Не удалось подготовить установку");

        int installed = 0;
        long totalBytes = 0L;
        Set<String> names = new HashSet<>();
        try (InputStream raw = context.getContentResolver().openInputStream(source)) {
            if (raw == null) throw new IllegalArgumentException("Не удалось открыть ZIP");
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
                ZipEntry entry;
                int entryCount = 0;
                byte[] buffer = new byte[8192];
                while ((entry = zip.getNextEntry()) != null) {
                    if (++entryCount > MAX_ENTRIES) throw new IllegalArgumentException("Слишком много файлов в ZIP");
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    validateArchivePath(name);
                    if (!name.startsWith(TEXTURE_PREFIX)) continue;
                    String textureName = name.substring(TEXTURE_PREFIX.length());
                    if (!ALLOWED.contains(textureName)) continue;
                    if (!names.add(textureName)) throw new IllegalArgumentException("Повтор текстуры: " + textureName);

                    File output = new File(new File(staging, "textures/blocks"), textureName);
                    File parent = output.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IllegalStateException("Не удалось создать папку текстур");
                    }
                    long fileBytes = 0L;
                    try (FileOutputStream out = new FileOutputStream(output)) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            fileBytes += read;
                            totalBytes += read;
                            if (fileBytes > MAX_FILE_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                                throw new IllegalArgumentException("Ресурс-пак превышает лимит размера");
                            }
                            out.write(buffer, 0, read);
                        }
                    }
                    if (!isValidPng(output)) throw new IllegalArgumentException("Повреждённая PNG: " + textureName);
                    installed++;
                }
            }
            if (installed == 0) throw new IllegalArgumentException(
                    "В ZIP нет поддерживаемых файлов textures/blocks/*.png");

            boolean hadActive = active.exists();
            if (hadActive && !active.renameTo(backup)) {
                throw new IllegalStateException("Не удалось сохранить предыдущий ресурс-пак");
            }
            if (!staging.renameTo(active)) {
                if (hadActive) backup.renameTo(active);
                throw new IllegalStateException("Не удалось активировать новый ресурс-пак");
            }
            if (hadActive) deleteRecursively(backup);
            return installed;
        } catch (Exception failure) {
            deleteRecursively(staging);
            throw failure;
        }
    }

    private static void validateArchivePath(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/")
                || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Недопустимый путь в ZIP");
        }
        String[] parts = path.split("/");
        for (String part : parts) {
            if (part.equals("..") || part.equals(".")) {
                throw new IllegalArgumentException("Путь за пределами ресурс-пака запрещён");
            }
        }
    }

    private static boolean isValidPng(File file) {
        if (!file.isFile() || file.length() < 8L) return false;
        byte[] signature = new byte[8];
        try (FileInputStream input = new FileInputStream(file)) {
            if (input.read(signature) != 8) return false;
        } catch (Exception ignored) {
            return false;
        }
        byte[] png = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        for (int i = 0; i < png.length; i++) if (signature[i] != png[i]) return false;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        return bounds.outWidth > 0 && bounds.outHeight > 0
                && bounds.outWidth <= MAX_DIMENSION && bounds.outHeight <= MAX_DIMENSION;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }
}
