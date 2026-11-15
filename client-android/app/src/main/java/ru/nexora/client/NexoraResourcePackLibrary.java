package ru.nexora.client;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Local library of imported texture-only resource packs. */
final class NexoraResourcePackLibrary {
    private static final String CURRENT_FILE = "active-pack.txt";

    static final class Pack {
        final String id;
        final String name;
        Pack(String id, String name) { this.id = id; this.name = name; }
    }

    private NexoraResourcePackLibrary() { }

    static List<Pack> list(Context context) {
        File packs = packsDirectory(context);
        File[] dirs = packs.listFiles(File::isDirectory);
        List<Pack> result = new ArrayList<>();
        if (dirs != null) for (File dir : dirs) {
            if (!safeId(dir.getName())) continue;
            File textures = new File(dir, "textures/blocks");
            if (!textures.isDirectory()) continue;
            result.add(new Pack(dir.getName(), readName(dir)));
        }
        Collections.sort(result, Comparator.comparing(p -> p.name.toLowerCase(java.util.Locale.ROOT)));
        return result;
    }

    static String activeId(Context context) {
        File marker = new File(root(context), CURRENT_FILE);
        if (!marker.isFile()) return "";
        try {
            byte[] bytes = readBounded(marker, 128);
            String id = new String(bytes, StandardCharsets.UTF_8).trim();
            return safeId(id) && new File(packsDirectory(context), id).isDirectory() ? id : "";
        } catch (IOException ignored) { return ""; }
    }

    static void registerImportedActive(Context context, String displayName) throws IOException {
        File root = root(context);
        File active = new File(root, "active");
        if (!new File(active, "textures/blocks").isDirectory()) {
            throw new IOException("Нет установленной папки текстур");
        }
        File packs = packsDirectory(context);
        if (!packs.exists() && !packs.mkdirs()) throw new IOException("Не удалось создать библиотеку");
        String id = "pack-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        File destination = new File(packs, id);
        copyDirectory(active, destination);
        String cleanName = cleanDisplayName(displayName);\n        writeName(destination, cleanName.isEmpty()\n                ? "Ресурс-пак " + (list(context).size() + 1) : cleanName);
        writeCurrent(root, id);
    }

    static void activate(Context context, String id) throws IOException {
        if (!safeId(id)) throw new IOException("Некорректный пакет");
        File source = new File(packsDirectory(context), id);
        if (!new File(source, "textures/blocks").isDirectory()) throw new IOException("Пакет не найден");
        File root = root(context);
        File active = new File(root, "active");
        File staging = new File(root, ".active-staging-" + UUID.randomUUID());
        File backup = new File(root, ".active-backup-" + UUID.randomUUID());
        copyDirectory(source, staging);
        boolean hadActive = active.exists();
        if (hadActive && !active.renameTo(backup)) {
            deleteRecursively(staging);
            throw new IOException("Не удалось сохранить текущие текстуры");
        }
        if (!staging.renameTo(active)) {
            if (hadActive) backup.renameTo(active);
            deleteRecursively(staging);
            throw new IOException("Не удалось переключить ресурс-пак");
        }
        deleteRecursively(backup);
        writeCurrent(root, id);
    }

    static void delete(Context context, String id) throws IOException {
        if (!safeId(id)) throw new IOException("Некорректный пакет");
        File target = new File(packsDirectory(context), id);
        if (!target.isDirectory()) throw new IOException("Пакет не найден");
        boolean wasActive = id.equals(activeId(context));
        deleteRecursively(target);
        if (wasActive) {
            deleteRecursively(new File(root(context), "active"));
            File marker = new File(root(context), CURRENT_FILE);
            if (marker.exists() && !marker.delete()) throw new IOException("Не удалось сбросить активный пакет");
        }
    }

    private static File root(Context c) { return new File(c.getFilesDir(), "resourcepacks"); }
    private static File packsDirectory(Context c) { return new File(root(c), "packs"); }

    private static String cleanDisplayName(String value) {
        if (value == null) return "";
        String name = value.replace('\\\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
            name = name.substring(0, name.length() - 4);
        }
        name = name.replaceAll("[\\\\p{Cntrl}]", "").trim();
        if (name.length() > 48) name = name.substring(0, 48).trim();
        return name;
    }

    private static boolean safeId(String id) {
        return id != null && id.matches("pack-[a-f0-9]{12}");
    }

    private static String readName(File dir) {
        try {
            String name = new String(readBounded(new File(dir, "name.txt"), 256), StandardCharsets.UTF_8).trim();
            return name.isEmpty() ? dir.getName() : name;
        } catch (Exception ignored) { return dir.getName(); }
    }

    private static void writeName(File dir, String name) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(dir, "name.txt"))) {
            out.write(name.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeCurrent(File root, String id) throws IOException {
        File temporary = new File(root, CURRENT_FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(id.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
        File marker = new File(root, CURRENT_FILE);
        if (marker.exists() && !marker.delete()) throw new IOException("Не удалось обновить активный пакет");
        if (!temporary.renameTo(marker)) throw new IOException("Не удалось сохранить активный пакет");
    }

    private static byte[] readBounded(File file, int limit) throws IOException {
        if (!file.isFile() || file.length() > limit) throw new IOException("Некорректный файл");
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int offset = 0, read;
            while (offset < data.length && (read = in.read(data, offset, data.length - offset)) != -1) offset += read;
            if (offset != data.length) throw new IOException("Неполный файл");
        }
        return data;
    }

    private static void copyDirectory(File source, File target) throws IOException {
        if (!source.isDirectory() || !target.mkdirs()) throw new IOException("Не удалось скопировать ресурс-пак");
        File[] children = source.listFiles();
        if (children == null) throw new IOException("Не удалось прочитать ресурс-пак");
        for (File child : children) {
            File out = new File(target, child.getName());
            if (child.isDirectory()) copyDirectory(child, out);
            else {
                try (FileInputStream in = new FileInputStream(child);
                     FileOutputStream stream = new FileOutputStream(out)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) != -1) stream.write(buffer, 0, count);
                }
            }
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }
}
