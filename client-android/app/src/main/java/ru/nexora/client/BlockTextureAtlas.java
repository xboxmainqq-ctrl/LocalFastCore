package ru.nexora.client;

import android.graphics.Bitmap;

/**
 * Built-in 16x16 pixel texture atlas used as the fallback material source.
 * Tile coordinates are stable so resource-pack textures can replace individual
 * tiles later without changing block definitions or mesh generation.
 */
final class BlockTextureAtlas {
    static final int TILE_SIZE = 16;
    static final int COLUMNS = 4;
    static final int ROWS = 4;
    static final int WIDTH = TILE_SIZE * COLUMNS;
    static final int HEIGHT = TILE_SIZE * ROWS;
    static final int TILE_COUNT = COLUMNS * ROWS;

    static final int GRASS_TOP = 0;
    static final int GRASS_SIDE = 1;
    static final int DIRT = 2;
    static final int STONE = 3;
    static final int SAND = 4;
    static final int GRAVEL = 5;
    static final int LOG_SIDE = 6;
    static final int LOG_TOP = 7;
    static final int LEAVES = 8;
    static final int WATER = 9;
    static final int SNOW = 10;
    static final int BEDROCK = 11;
    static final int PLANKS = 12;
    static final int CLAY = 13;
    static final int DEEPSLATE = 14;
    static final int GRASS_BOTTOM = 15;

    private static final int[][] PALETTES = {
            {0xff559b3f,0xff68ad49,0xff78bd55,0xff478a36},
            {0xff755039,0xff806044,0xff6e4a34,0xff78b957},
            {0xff76543b,0xff896344,0xff98704e,0xff68472f},
            {0xff777e87,0xff9299a3,0xffa4aab1,0xff686f79},
            {0xffc7ae70,0xffe2ce8d,0xffead9a4,0xffb89d60},
            {0xff77756f,0xff99958b,0xffaaa69b,0xff625f5a},
            {0xff795334,0xff91683e,0xffa47a49,0xff68472d},
            {0xffb18a58,0xffc69d68,0xff8e663d,0xffe0bd82},
            {0xff477f43,0xff57994d,0xff3e713b,0xff68a95a},
            {0xff3276ad,0xff438fc8,0xff5ba5d8,0xff286497},
            {0xffcbdde8,0xffedf7fb,0xffdcebf2,0xffb7cedc},
            {0xff252a30,0xff34383f,0xff484d54,0xff1c2026},
            {0xff987044,0xffad8455,0xffc09662,0xff805b39},
            {0xff85827b,0xff9a9690,0xffaaa69f,0xff74716b},
            {0xff414751,0xff555b65,0xff666d78,0xff343a43},
            {0xff76543b,0xff896344,0xff98704e,0xff68472f}
    };

    private BlockTextureAtlas() {
    }

    static int tileFor(String blockId, int face) {
        String id = blockId == null ? "" : blockId.toLowerCase(java.util.Locale.ROOT);
        if (id.contains("grass_block") || id.endsWith(":grass")) {
            return face == 0 ? GRASS_TOP : face == 2 ? GRASS_BOTTOM : GRASS_SIDE;
        }
        if (id.contains("podzol") || id.contains("mycelium") || id.contains("dirt") || id.contains("mud")) return DIRT;
        if (id.contains("deepslate")) return DEEPSLATE;
        if (id.contains("stone") || id.contains("cobble") || id.contains("ore")) return STONE;
        if (id.contains("sand")) return SAND;
        if (id.contains("gravel")) return GRAVEL;
        if (id.contains("oak_log") || id.contains("spruce_log") || id.contains("birch_log") || id.endsWith("_log")) {
            return face == 0 || face == 2 ? LOG_TOP : LOG_SIDE;
        }
        if (id.contains("leaves") || id.contains("moss")) return LEAVES;
        if (id.contains("water")) return WATER;
        if (id.contains("snow") || id.contains("ice")) return SNOW;
        if (id.contains("bedrock")) return BEDROCK;
        if (id.contains("planks")) return PLANKS;
        if (id.contains("clay")) return CLAY;
        return STONE;
    }

    static float[] uvForTile(int tile) {
        int safe = Math.max(0, Math.min(TILE_COUNT - 1, tile));
        int x = safe % COLUMNS;
        int y = safe / COLUMNS;
        float inset = 0.5f / TILE_SIZE;
        return new float[] {
                x / (float) COLUMNS + inset / COLUMNS,
                y / (float) ROWS + inset / ROWS,
                (x + 1) / (float) COLUMNS - inset / COLUMNS,
                (y + 1) / (float) ROWS - inset / ROWS
        };
    }

    static Bitmap createBitmap() {
        int[] pixels = new int[WIDTH * HEIGHT];
        for (int tile = 0; tile < TILE_COUNT; tile++) {
            int[] palette = PALETTES[tile];
            int ox = (tile % COLUMNS) * TILE_SIZE;
            int oy = (tile / COLUMNS) * TILE_SIZE;
            for (int y = 0; y < TILE_SIZE; y++) {
                for (int x = 0; x < TILE_SIZE; x++) {
                    int noise = mix(tile * 0x45d9f3b ^ x * 0x119de1f3 ^ y * 0x3449);
                    int pick = (noise >>> 28) & 3;
                    if (tile == GRASS_SIDE && y < 4) pick = (x + y) % 3 == 0 ? 2 : 3;
                    if (tile == LOG_TOP) {
                        int dx = x - 7, dy = y - 7;
                        int ring = (int) Math.sqrt(dx * dx + dy * dy) / 2;
                        pick = ring & 3;
                    }
                    if (tile == WATER) pick = (y % 5 == 0) ? 2 : pick;
                    pixels[(oy + y) * WIDTH + ox + x] = palette[pick];
                }
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
        return bitmap;
    }

    private static int mix(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        return value ^ (value >>> 16);
    }
}
