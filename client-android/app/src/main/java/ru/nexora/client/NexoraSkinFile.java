package ru.nexora.client;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Imports user-provided NEXORA skin images.
 *
 * <p>Skin atlas v1 is a 64x64 PNG. The imported image is kept in the app's
 * private files directory; it is not uploaded to a server yet.</p>
 */
final class NexoraSkinFile {
    static final int WIDTH = 64;
    static final int HEIGHT = 64;
    static final int MAX_FILE_BYTES = 256 * 1024;
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10
    };

    private NexoraSkinFile() {
    }

    static File importPng(Context context, Uri source) throws Exception {
        if (context == null || source == null) {
            throw new IllegalArgumentException("Файл не выбран");
        }

        byte[] bytes;
        try (InputStream input = context.getContentResolver().openInputStream(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IllegalArgumentException("Не удалось открыть PNG");
            }
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_FILE_BYTES) {
                    throw new IllegalArgumentException("PNG должен быть не больше 256 КБ");
                }
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }

        if (bytes.length < PNG_SIGNATURE.length) {
            throw new IllegalArgumentException("Файл не является PNG");
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (bytes[i] != PNG_SIGNATURE[i]) {
                throw new IllegalArgumentException("Файл не является PNG");
            }
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth != WIDTH || options.outHeight != HEIGHT) {
            throw new IllegalArgumentException("Размер скина должен быть ровно 64x64 пикселя");
        }
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (decoded == null) {
            throw new IllegalArgumentException("PNG повреждён или не поддерживается");
        }
        decoded.recycle();

        File directory = new File(context.getFilesDir(), "nexora-skins");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Не удалось создать папку скинов");
        }
        File destination = new File(directory, "player.png");
        File temporary = new File(directory, "player.png.tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.getFD().sync();
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IllegalStateException("Не удалось заменить старый скин");
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IllegalStateException("Не удалось сохранить PNG");
        }
        return destination;
    }
}
