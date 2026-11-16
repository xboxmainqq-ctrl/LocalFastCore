package ru.nexora.world;

@FunctionalInterface
public interface WorldGenerator {
    void generate(WorldChunk chunk, long seed);
}
