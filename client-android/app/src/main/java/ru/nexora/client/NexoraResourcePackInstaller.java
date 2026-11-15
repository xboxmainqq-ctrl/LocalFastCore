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
 * Only known texture filenames are extracted; every ZIP entry is still bounded and validated.
 */
final class NexoraResourcePackInstaller {
    private static final long MAX_FILE_BYTES = 4L * 1024L * 1024L;
    private static final long MAX_PACK_EXPANDED_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_ENTRY_EXPANDED_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 4096;
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
                    if (++entryCount > MAX_ENTRIES) {
                        throw new IllegalArgumentException("Слишком много файлов в ZIP");
                    }
                    String name = entry.getName();
                    validateArchivePath(name, entry.isDirectory());
                    if (entry.isDirectory()) continue;

                    String textureName = supportedTextureName(name);
                    boolean extract = textureName != null && !names.contains(textureName);
                    File output = null;
                    FileOutputStream out = null;
                    if (extract) {
                        output = new File(new File(staging, "textures/blocks"), textureName);
                        File parent = output.getParentFile();
                        if (!parent.isDirectory() && !parent.mkdirs()) {
                            throw new IllegalStateException("Не удалось создать папку текстур");
                        }
                        out = new FileOutputStream(output);
                    }

                    long entryBytes = 0L;
                    try {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            entryBytes += read;
                            totalBytes += read;
                            if (entryBytes > MAX_ENTRY_EXPANDED_BYTES
                                    || totalBytes > MAX_PACK_EXPANDED_BYTES) {
                                throw new IllegalArgumentException("Ресурс-пак превышает лимит распакованного размера");
                            }
                            if (out != null) {
                                if (entryBytes > MAX_FILE_BYTES) {
                                    throw new IllegalArgumentException("Текстура превышает лимит 4 МиБ: "
                                            + textureName);
                                }
                                out.write(buffer, 0, read);
                            }
                        }
                    } finally {
                        if (out != null) out.close();
                    }

                    if (extract) {
                        if (!isValidPng(output)) {
                            throw new IllegalArgumentException("Повреждённая PNG: " + textureName);
                        }
                        names.add(textureName);
                        installed++;
                    }
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
     * Accepts Bedrock-style textures/blocks paths, optionally inside a ZIP root folder.
     * Java Edition assets/minecraft paths are intentionally not supported.
     */
    static String supportedTextureName(String path) {
        String marker = "textures/blocks/";
        int start = path == null ? -1 : path.lastIndexOf(marker);
        if (start < 0) return null;
        String file = path.substring(start + marker.length());
        if (file.indexOf('/') >= 0) return null;
        file = file.toLowerCase(java.util.Locale.ROOT);
        if (ALLOWED.contains(file)) return file;

        switch (file) {
            case "log_oak.png":
            case "log_spruce.png":
            case "log_birch.png":
            case "log_jungle.png":
            case "log_acacia.png":
            case "log_big_oak.png":
            case "log_dark_oak.png":
            case "log_mangrove.png":
            case "log_cherry.png":
            case "crimson_stem.png":
            case "warped_stem.png": return "log_side.png";
            case "log_oak_top.png":
            case "log_spruce_top.png":
            case "log_birch_top.png":
            case "log_jungle_top.png":
            case "log_acacia_top.png":
            case "log_big_oak_top.png":
            case "log_dark_oak_top.png":
            case "log_mangrove_top.png":
            case "log_cherry_top.png":
            case "crimson_stem_top.png":
            case "warped_stem_top.png": return "log_top.png";
            case "leaves_oak.png":
            case "leaves_spruce.png":
            case "leaves_birch.png":
            case "leaves_jungle.png":
            case "leaves_acacia.png":
            case "leaves_big_oak.png":
            case "leaves_dark_oak.png":
            case "leaves_mangrove.png":
            case "leaves_cherry.png": return "leaves.png";
            case "water_still.png":
            case "water_flow.png": return "water.png";
            case "snow.png":
            case "snow_side.png": return "snow.png";
            case "planks_oak.png":
            case "planks_spruce.png":
            case "planks_birch.png":
            case "planks_jungle.png":
            case "planks_acacia.png":
            case "planks_big_oak.png":
            case "planks_dark_oak.png":
            case "planks_mangrove.png":
            case "planks_cherry.png": return "planks.png";
            default: return null;
        }
    }

    static void validateArchivePath(String path, boolean directory) {
        if (path == null || path.isEmpty() || path.startsWith("/")
                || path.matches("^[A-Za-z]:.*")
                || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Недопустимый путь в ZIP");
        }
        String checked = directory && path.endsWith("/")
                ? path.substring(0, path.length() - 1) : path;
        if (checked.isEmpty()) throw new IllegalArgumentException("Пустой путь в ZIP");
        String[] parts = checked.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || part.equals("..") || part.equals(".")) {
                throw new IllegalArgumentException("Путь за пределами ресурс-пака запрещён");
            }
            for (int i = 0; i < part.length(); i++) {
                char ch = part.charAt(i);
                if (ch < 0x20 || ch == 0x7f || ch == ':') {
                    throw new IllegalArgumentException("Недопустимый символ в пути ZIP");
                }
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
