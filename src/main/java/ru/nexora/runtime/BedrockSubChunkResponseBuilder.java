package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;

/**
 * Builds protocol-2225 SubChunkPacket responses from NEXORA's loaded world.
 * The caller supplies the authenticated player's current dimension and the
 * exact version-matched Bedrock block runtime registry.
 */
public final class BedrockSubChunkResponseBuilder {
    private BedrockSubChunkResponseBuilder() {
    }

    public static List<BedrockGamePacket> buildPackets(
            World world,
            BedrockSubChunkRequestPacketCodec.Request request,
            int playerDimension,
            BedrockRuntimeBlockRegistry registry
    ) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(registry, "registry");
        if (playerDimension < -1 || playerDimension > 2) {
            throw new IllegalArgumentException("Unsupported player dimension");
        }

        List<BedrockSubChunkPacketCodec.Entry> entries = new ArrayList<>(request.offsets().size());
        Map<ChunkPosition, Map<BlockPosition, BlockState>> chunkSnapshots = new HashMap<>();
        List<BedrockSubChunkRequestPacketCodec.SubChunkPosition> positions =
                request.absolutePositions();

        for (int index = 0; index < positions.size(); index++) {
            BedrockSubChunkRequestPacketCodec.SubChunkPosition position = positions.get(index);
            BedrockSubChunkRequestPacketCodec.Offset requestedOffset = request.offsets().get(index);
            BedrockSubChunkPacketCodec.Offset offset = new BedrockSubChunkPacketCodec.Offset(
                    requestedOffset.x(), requestedOffset.y(), requestedOffset.z());

            if (request.dimension() != playerDimension) {
                entries.add(BedrockSubChunkPacketCodec.Entry.error(
                        offset, BedrockSubChunkPacketCodec.RESULT_WRONG_DIMENSION));
                continue;
            }

            ChunkPosition chunkPosition = new ChunkPosition(position.x(), position.z());
            if (!world.isChunkLoaded(chunkPosition)) {
                entries.add(BedrockSubChunkPacketCodec.Entry.error(
                        offset, BedrockSubChunkPacketCodec.RESULT_LEVEL_CHUNK_DOES_NOT_EXIST));
                continue;
            }

            int minSectionY = Math.floorDiv(world.height().minY(), 16);
            int maxSectionY = Math.floorDiv(world.height().maxYExclusive() - 1, 16);
            if (position.y() < minSectionY || position.y() > maxSectionY) {
                entries.add(BedrockSubChunkPacketCodec.Entry.error(
                        offset, BedrockSubChunkPacketCodec.RESULT_INDEX_OUT_OF_BOUNDS));
                continue;
            }

            ChunkSection section = new ChunkSection();
            Map<BlockPosition, BlockState> blocks = chunkSnapshots.computeIfAbsent(
                    chunkPosition, world::blocksInChunk);
            for (Map.Entry<BlockPosition, BlockState> block : blocks.entrySet()) {
                BlockPosition blockPosition = block.getKey();
                if (Math.floorDiv(blockPosition.y(), 16) == position.y()) {
                    section.set(blockPosition.localX(), Math.floorMod(blockPosition.y(), 16),
                            blockPosition.localZ(), block.getValue());
                }
            }

            if (section.isEmpty()) {
                entries.add(BedrockSubChunkPacketCodec.Entry.allAir(offset));
            } else {
                ByteBuffer serialized = BedrockSubChunkTerrainCodec.encode(
                        section, position.y(), registry);
                byte[] bytes = new byte[serialized.remaining()];
                serialized.get(bytes);
                entries.add(BedrockSubChunkPacketCodec.Entry.success(offset, bytes));
            }
        }

        List<BedrockGamePacket> packets = new ArrayList<>(
                (entries.size() + 63) / 64);
        BedrockSubChunkPacketCodec.Position center = new BedrockSubChunkPacketCodec.Position(
                request.center().x(), request.center().y(), request.center().z());
        for (int start = 0; start < entries.size(); start += 64) {
            int end = Math.min(start + 64, entries.size());
            packets.add(BedrockSubChunkPacketCodec.encodePacket(
                    request.dimension(), center, entries.subList(start, end)));
        }
        return List.copyOf(packets);
    }
}
