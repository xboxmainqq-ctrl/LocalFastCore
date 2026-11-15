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
                    String textureName = supportedTextureName(name);
                    if (textureName == null) continue;
                    // Several Java texture variants can map to one NEXORA atlas tile.
                    // Keep the first matching file to avoid ambiguous overwrites.
                    if (!names.add(textureName)) continue;

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

    /**
     * Accepts NEXORA aliases, Bedrock-style textures/blocks and standard
     * Java assets/minecraft/textures/block paths, optionally inside one ZIP root folder.
     */
    static String supportedTextureName(String path) {
        String normalized = path;
        String[] markers = {
                "assets/minecraft/textures/block/",
                "assets/minecraft/textures/blocks/",
                "textures/block/",
                "textures/blocks/"
        };
        int start = -1;
        for (String marker : markers) {
            int index = normalized.lastIndexOf(marker);
            if (index >= 0 && (start < 0 || index > start)) start = index + marker.length();
        }
        if (start < 0 || start >= normalized.length()) return null;
        String file = normalized.substring(start);
        if (file.indexOf('/') >= 0) return null;
        file = file.toLowerCase(java.util.Locale.ROOT);
        if (ALLOWED.contains(file)) return file;

        switch (file) {
            case "grass_block_top.png": return "grass_top.png";
            case "grass_block_side.png": return "grass_side.png";
            case "grass_block_bottom.png": return "dirt.png";
            case "oak_log.png":
            case "spruce_log.png":
            case "birch_log.png":
            case "jungle_log.png":
            case "acacia_log.png":
            case "dark_oak_log.png":
            case "mangrove_log.png":
            case "cherry_log.png":
            case "crimson_stem.png":
            case "warped_stem.png": return "log_side.png";
            case "oak_log_top.png":
            case "spruce_log_top.png":
            case "birch_log_top.png":
            case "jungle_log_top.png":
            case "acacia_log_top.png":
            case "dark_oak_log_top.png":
            case "mangrove_log_top.png":
            case "cherry_log_top.png":
            case "crimson_stem_top.png":
            case "warped_stem_top.png": return "log_top.png";
            case "oak_leaves.png":
            case "spruce_leaves.png":
            case "birch_leaves.png":
            case "jungle_leaves.png":
            case "acacia_leaves.png":
            case "dark_oak_leaves.png":
            case "mangrove_leaves.png":
            case "cherry_leaves.png": return "leaves.png";
            case "water_still.png":
            case "water_flow.png": return "water.png";
            case "snow.png":
            case "snow_block.png": return "snow.png";
            case "oak_planks.png":
            case "spruce_planks.png":
            case "birch_planks.png":
            case "jungle_planks.png":
            case "acacia_planks.png":
            case "dark_oak_planks.png":
            case "mangrove_planks.png":
            case "cherry_planks.png": return "planks.png";
            default: return null;
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
