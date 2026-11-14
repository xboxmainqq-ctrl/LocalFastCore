package ru.nexora.client;

/**
 * Pixel-space UV regions for the NEXORA Skin Atlas v1.
 *
 * <p>Coordinates use a top-left origin and half-open pixel bounds. Convert
 * them to normalized UVs only at the texture-rendering boundary.</p>
 */
final class NexoraSkinAtlas {
    static final int WIDTH = 64;
    static final int HEIGHT = 64;

    enum Part {
        HEAD, TORSO, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG
    }

    enum Face {
        RIGHT, FRONT, LEFT, BACK, TOP, BOTTOM
    }

    record UvRect(int x0, int y0, int x1, int y1) {
        UvRect {
            if (x0 < 0 || y0 < 0 || x1 > WIDTH || y1 > HEIGHT
                    || x0 >= x1 || y0 >= y1) {
                throw new IllegalArgumentException("Skin UV rectangle is outside the 64x64 atlas");
            }
        }

        float u0() { return x0 / (float) WIDTH; }
        float v0() { return y0 / (float) HEIGHT; }
        float u1() { return x1 / (float) WIDTH; }
        float v1() { return y1 / (float) HEIGHT; }
    }

    private NexoraSkinAtlas() {
    }

    static UvRect rect(Part part, Face face) {
        return switch (part) {
            case HEAD -> switch (face) {
                case RIGHT -> new UvRect(0, 8, 8, 16);
                case FRONT -> new UvRect(8, 8, 16, 16);
                case LEFT -> new UvRect(16, 8, 24, 16);
                case BACK -> new UvRect(24, 8, 32, 16);
                case TOP -> new UvRect(8, 0, 16, 8);
                case BOTTOM -> new UvRect(16, 0, 24, 8);
            };
            case TORSO -> switch (face) {
                case RIGHT -> new UvRect(16, 20, 20, 32);
                case FRONT -> new UvRect(20, 20, 28, 32);
                case LEFT -> new UvRect(28, 20, 32, 32);
                case BACK -> new UvRect(32, 20, 40, 32);
                case TOP -> new UvRect(20, 16, 28, 20);
                case BOTTOM -> new UvRect(28, 16, 36, 20);
            };
            case RIGHT_LEG -> limbRect(face, 0, 4, 8, 12, 16, 20, 32);
            case RIGHT_ARM -> limbRect(face, 40, 44, 48, 52, 44, 48, 20);
            case LEFT_LEG -> limbRect(face, 16, 20, 24, 28, 20, 24, 52);
            case LEFT_ARM -> limbRect(face, 32, 36, 40, 44, 36, 40, 52);
        };
    }

    private static UvRect limbRect(Face face, int right, int front, int left,
                                   int back, int top, int bottom, int rowY) {
        return switch (face) {
            case RIGHT -> new UvRect(right, rowY, front, rowY + 12);
            case FRONT -> new UvRect(front, rowY, left, rowY + 12);
            case LEFT -> new UvRect(left, rowY, back, rowY + 12);
            case BACK -> new UvRect(back, rowY, back + (front - right), rowY + 12);
            case TOP -> new UvRect(top, rowY - 4, bottom, rowY);
            case BOTTOM -> new UvRect(bottom, rowY - 4, bottom + (front - right), rowY);
        };
    }
}
