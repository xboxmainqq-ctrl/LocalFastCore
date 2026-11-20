package ru.nexora.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Sequential, bounded, SHA-256 verified receiver for one Bedrock resource-pack ZIP. */
final class BedrockAndroidResourcePackDownload implements AutoCloseable {
    private final BedrockAndroidResourcePackDataInfo info;
    private final File file;
    private FileOutputStream output;
    private int nextChunk;
    private boolean finished;

    BedrockAndroidResourcePackDownload(File cacheDirectory,
                                       BedrockAndroidResourcePackDataInfo info) throws IOException {
        if (cacheDirectory == null || info == null) throw new IllegalArgumentException("Download arguments are required");
        if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) {
            throw new IOException("Не удалось создать временную папку загрузки");
        }
        this.info = info;
        this.file = File.createTempFile("nexora-bedrock-pack-", ".zip", cacheDirectory);
        this.output = new FileOutputStream(file);
    }

    synchronized int nextChunkIndex() {
        return nextChunk;
    }

    synchronized boolean isComplete() {
        return nextChunk == info.chunkCount();
    }

    synchronized void accept(BedrockAndroidResourcePackChunk chunk) throws IOException {
        if (finished || output == null || chunk == null
                || !info.resourceName().equals(chunk.resourceName())
                || chunk.chunkIndex() != nextChunk) {
            throw new IOException("Получен неожиданный фрагмент ресурс-пака");
        }
        long expectedOffset = (long) nextChunk * info.chunkSize();
        int expectedLength = (int) Math.min(info.chunkSize(), info.fileSize() - expectedOffset);
        byte[] data = chunk.data();
        if (chunk.offset() != expectedOffset || data.length != expectedLength) {
            throw new IOException("Нарушена последовательность частей ресурс-пака");
        }
        output.write(data);
        nextChunk++;
    }

    synchronized File finishAndVerify() throws IOException {
        if (finished || output == null || !isComplete()) {
            throw new IOException("Загрузка ресурс-пака ещё не завершена");
        }
        output.flush();
        output.getFD().sync();
        output.close();
        output = null;
        if (!file.isFile() || file.length() != info.fileSize()) {
            deleteAndThrow("Размер загруженного ресурс-пака не совпадает");
        }
        String actualHash = sha256(file);
        if (!info.fileHash().equals(actualHash)) {
            deleteAndThrow("Контрольная сумма ресурс-пака не совпадает");
        }
        finished = true;
        return file;
    }

    private void deleteAndThrow(String reason) throws IOException {
        file.delete();
        throw new IOException(reason);
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                hex.append(Character.forDigit(value & 0x0f, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 недоступен", impossible);
        }
    }

    @Override
    public synchronized void close() {
        if (output != null) {
            try { output.close(); } catch (IOException ignored) { }
            output = null;
        }
        if (!finished) file.delete();
    }
}
