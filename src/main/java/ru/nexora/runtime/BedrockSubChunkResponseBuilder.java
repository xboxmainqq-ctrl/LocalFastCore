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
        Map<ChunkPosition, Map<Integer, ChunkSection>> sectionSnapshots = new HashMap<>();
        Map<ChunkPosition, Map<Integer, ChunkSection>> fluidSectionSnapshots = new HashMap<>();
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

            Map<Integer, ChunkSection> sections = sectionSnapshots.computeIfAbsent(
                    chunkPosition, ignored -> groupBlocksBySection(world.blocksInChunk(chunkPosition)));
            ChunkSection section = sections.get(position.y());

            if (section == null || section.isEmpty()) {
                entries.add(BedrockSubChunkPacketCodec.Entry.allAir(offset));
            } else {
                Map<Integer, ChunkSection> fluidSections = fluidSectionSnapshots.computeIfAbsent(
                        chunkPosition, ignored -> groupBlocksBySection(world.fluidsInChunk(chunkPosition)));
                ChunkSection fluidSection = fluidSections.get(position.y());
                if (fluidSection == null) {
                    fluidSection = new ChunkSection();
                }
                ByteBuffer serialized = BedrockSubChunkTerrainCodec.encode(
                        section, fluidSection, position.y(), registry);
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

    /**
     * Groups one detached chunk snapshot into section-local palettes once.
     * Multiple requested sections in the same chunk then avoid rescanning
     * every block for every offset in a SubChunkRequest packet.
     */
    static Map<Integer, ChunkSection> groupBlocksBySection(Map<BlockPosition, BlockState> blocks) {
        Objects.requireNonNull(blocks, "blocks");
        Map<Integer, ChunkSection> sections = new HashMap<>();
        for (Map.Entry<BlockPosition, BlockState> block : blocks.entrySet()) {
            BlockPosition position = block.getKey();
            BlockState state = block.getValue();
            int sectionY = Math.floorDiv(position.y(), 16);
            sections.computeIfAbsent(sectionY, ignored -> new ChunkSection()).set(
                    position.localX(), Math.floorMod(position.y(), 16),
                    position.localZ(), state);
        }
        return Map.copyOf(sections);
    }
}
