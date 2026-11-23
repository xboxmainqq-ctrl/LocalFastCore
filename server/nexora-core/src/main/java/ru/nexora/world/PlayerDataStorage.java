package ru.nexora.world;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.CRC32C;

/** Versioned, checksummed and atomically replaced player-profile files. */
public final class PlayerDataStorage {
    private static final int MAGIC = 0x4e58504c; // NXPL
    private static final int FORMAT_VERSION = 4;
    private static final int MAX_FILE_BYTES = 1024;

    private final Path playersDirectory;

    public PlayerDataStorage(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Path root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        rejectSymlink(root);
        this.playersDirectory = root;
    }

    public void save(PlayerData player) throws IOException {
        Objects.requireNonNull(player, "player");
        Path target = playerPath(player.xuid());
        rejectSymlink(target);

        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream(128);
        try (DataOutputStream output = new DataOutputStream(bodyBytes)) {
            output.writeInt(MAGIC);
            output.writeByte(FORMAT_VERSION);
            writeString(output, player.xuid(), 64);
            writeString(output, player.displayName(), 128);
            writeString(output, player.worldName(), 128);
            output.writeFloat(player.x());
            output.writeFloat(player.y());
            output.writeFloat(player.z());
            output.writeFloat(player.yaw());
            output.writeFloat(player.pitch());
            output.writeLong(player.lastSeenEpochMillis());
            output.writeFloat(player.vitals().health());
            output.writeInt(player.vitals().hunger());
            output.writeFloat(player.vitals().saturation());
            output.writeFloat(player.vitals().exhaustion());
            output.writeInt(player.experiencePoints());
            output.writeShort(player.experienceRemainderMilli());
        }

        byte[] body = bodyBytes.toByteArray();
        CRC32C checksum = new CRC32C();
        checksum.update(body, 0, body.length);
        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream(body.length + Integer.BYTES);
        fileBytes.writeBytes(body);
        try (DataOutputStream output = new DataOutputStream(fileBytes)) {
            output.writeInt((int) checksum.getValue());
        }

        if (fileBytes.size() > MAX_FILE_BYTES) throw new IOException("NEXORA player profile exceeds size limit");
        Path backup = backupPath(target);
        rejectSymlink(backup);
        if (Files.exists(target)) {
            // Never rotate an unverified profile into the recovery slot.
            load(player.xuid());
            ru.nexora.persistence.AtomicFileWriter.writeAtomically(backup, Files.readAllBytes(target));
        }
        ru.nexora.persistence.AtomicFileWriter.writeAtomically(target, fileBytes.toByteArray());
    }

    public Optional<PlayerData> load(String xuid) throws IOException {
        Path file = playerPath(xuid);
        Path backup = backupPath(file);
        rejectSymlink(file);
        rejectSymlink(backup);
        if (!Files.exists(file)) {
            if (!Files.exists(backup)) return Optional.empty();
            PlayerData recovered = readProfile(backup, xuid);
            ru.nexora.persistence.AtomicFileWriter.writeAtomically(file, Files.readAllBytes(backup));
            return Optional.of(recovered);
        }
        try {
            return Optional.of(readProfile(file, xuid));
        } catch (IOException primaryFailure) {
            if (!Files.exists(backup)) throw primaryFailure;
            try {
                PlayerData recovered = readProfile(backup, xuid);
                ru.nexora.persistence.AtomicFileWriter.writeAtomically(file, Files.readAllBytes(backup));
                return Optional.of(recovered);
            } catch (IOException backupFailure) {
                primaryFailure.addSuppressed(backupFailure);
                throw primaryFailure;
            }
        }
    }

    private PlayerData readProfile(Path file, String xuid) throws IOException {
        long size = Files.size(file);
        if (size < 32 || size > MAX_FILE_BYTES) throw new IOException("Invalid NEXORA player profile size");
        byte[] bytes = Files.readAllBytes(file);
        int bodyLength = bytes.length - Integer.BYTES;
        int storedChecksum = ((bytes[bodyLength] & 0xff) << 24)
                | ((bytes[bodyLength + 1] & 0xff) << 16)
                | ((bytes[bodyLength + 2] & 0xff) << 8)
                | (bytes[bodyLength + 3] & 0xff);
        CRC32C checksum = new CRC32C();
        checksum.update(bytes, 0, bodyLength);
        if ((int) checksum.getValue() != storedChecksum) {
            throw new IOException("NEXORA player profile checksum mismatch: " + file);
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes, 0, bodyLength))) {
            if (input.readInt() != MAGIC) throw new IOException("Invalid NEXORA player profile magic");
            int version = input.readUnsignedByte();
            if (version < 1 || version > FORMAT_VERSION) {
                throw new IOException("Unsupported NEXORA player profile version: " + version);
            }
            String storedXuid = readString(input, 64);
            if (!storedXuid.equals(xuid)) throw new IOException("Player profile XUID does not match file name");
            try {
                String displayName = readString(input, 128);
                String worldName = readString(input, 128);
                float x = input.readFloat();
                float y = input.readFloat();
                float z = input.readFloat();
                float yaw = input.readFloat();
                float pitch = input.readFloat();
                long lastSeen = input.readLong();
                PlayerVitals vitals = version == 1 ? PlayerVitals.DEFAULT
                        : new PlayerVitals(input.readFloat(), input.readInt(), input.readFloat(), input.readFloat());
                int experiencePoints = version >= 3 ? input.readInt() : 0;
                int experienceRemainderMilli = version >= 4 ? input.readUnsignedShort() : 0;
                PlayerData player = new PlayerData(storedXuid, displayName, worldName,
                        x, y, z, yaw, pitch, lastSeen, vitals, experiencePoints, experienceRemainderMilli);
                if (input.available() != 0) throw new IOException("Trailing data in NEXORA player profile");
                return player;
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid NEXORA player profile values", exception);
            }
        }
    }

    private static Path backupPath(Path file) {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    private Path playerPath(String xuid) {
        if (xuid == null || !xuid.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid player XUID");
        }
        return playersDirectory.resolve(xuid + ".nxp");
    }

    private static void writeString(DataOutputStream output, String value, int maxBytes) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) throw new IOException("Player profile string exceeds size limit");
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int maxBytes) throws IOException {
        int length = input.readUnsignedShort();
        if (length > maxBytes) throw new IOException("Player profile string exceeds size limit");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Invalid UTF-8 in NEXORA player profile", exception);
        }
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("NEXORA player profile path must not be a symbolic link: " + path);
        }
    }
}
