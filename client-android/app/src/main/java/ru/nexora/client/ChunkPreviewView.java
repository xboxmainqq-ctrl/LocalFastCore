package ru.nexora.client;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Lightweight isometric voxel preview for a 3x3 NEXORA chunk area. */
final class ChunkPreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path topPath = new Path();
    private final Path leftPath = new Path();
    private final Path rightPath = new Path();
    private final Map<Long, NexoraAndroidProtocolCodec.ChunkData> chunks = new HashMap<>();
    private int centerChunkX;
    private int centerChunkZ;
    private boolean centerSet;

    ChunkPreviewView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    void setCenter(int chunkX, int chunkZ) {
        centerChunkX = chunkX;
        centerChunkZ = chunkZ;
        centerSet = true;
        chunks.clear();
        invalidate();
    }

    void setChunk(NexoraAndroidProtocolCodec.ChunkData chunk) {
        if (!centerSet || Math.abs((long) chunk.chunkX() - centerChunkX) > 1
                || Math.abs((long) chunk.chunkZ() - centerChunkZ) > 1) return;
        chunks.put(key(chunk.chunkX(), chunk.chunkZ()), chunk);
        invalidate();
    }

    int receivedChunkCount() {
        return chunks.size();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xff101827);
        if (chunks.isEmpty() || getWidth() <= 0 || getHeight() <= 0) return;

        List<ProjectedBlock> blocks = new ArrayList<>();
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (NexoraAndroidProtocolCodec.ChunkData chunk : chunks.values()) {
            int offsetX = (chunk.chunkX() - centerChunkX + 1) * 16;
            int offsetZ = (chunk.chunkZ() - centerChunkZ + 1) * 16;
            for (NexoraAndroidProtocolCodec.ChunkBlock block : chunk.blocks()) {
                int gx = offsetX + block.localX();
                int gz = offsetZ + block.localZ();
                blocks.add(new ProjectedBlock(gx, block.y(), gz, block.blockId()));
                minY = Math.min(minY, block.y());
                maxY = Math.max(maxY, block.y());
            }
        }
        if (blocks.isEmpty()) return;
        blocks.sort(Comparator.comparingInt((ProjectedBlock block) -> block.x() + block.z())
                .thenComparingInt(ProjectedBlock::y));

        float tileWidth = Math.min(getWidth() / 48f, getHeight() / 28f);
        tileWidth = Math.max(2f, tileWidth);
        float halfW = tileWidth * 0.5f;
        float halfH = tileWidth * 0.25f;
        float vertical = tileWidth * 0.48f;
        float projectedWidth = 47f * tileWidth;
        float originX = (getWidth() - projectedWidth) * 0.5f;
        float projectedDepth = 47f * halfH;
        float verticalDepth = (maxY - minY + 1) * vertical;
        float originY = Math.max(8f, (getHeight() - projectedDepth - verticalDepth) * 0.5f)
                + verticalDepth;

        for (ProjectedBlock block : blocks) {
            float x = originX + (block.x() - block.z()) * halfW + projectedWidth * 0.5f;
            float y = originY + (block.x() + block.z()) * halfH
                    - (block.y() - minY) * vertical;
            drawCube(canvas, x, y, halfW, halfH, vertical, colorFor(block.blockId()));
        }
        paint.setColor(0xffaab8cc);
        paint.setTextSize(Math.max(10f, tileWidth * 1.4f));
        canvas.drawText("Чанков: " + chunks.size() + "/9", 12f, getHeight() - 12f, paint);
    }

    private void drawCube(Canvas canvas, float x, float y, float halfW,
                          float halfH, float depth, int color) {
        float bottomY = y + depth;
        topPath.reset();
        topPath.moveTo(x, y);
        topPath.lineTo(x + halfW, y + halfH);
        topPath.lineTo(x, y + halfH * 2f);
        topPath.lineTo(x - halfW, y + halfH);
        topPath.close();

        leftPath.reset();
        leftPath.moveTo(x - halfW, y + halfH);
        leftPath.lineTo(x, y + halfH * 2f);
        leftPath.lineTo(x, bottomY + halfH * 2f);
        leftPath.lineTo(x - halfW, bottomY + halfH);
        leftPath.close();

        rightPath.reset();
        rightPath.moveTo(x + halfW, y + halfH);
        rightPath.lineTo(x, y + halfH * 2f);
        rightPath.lineTo(x, bottomY + halfH * 2f);
        rightPath.lineTo(x + halfW, bottomY + halfH);
        rightPath.close();

        paint.setColor(darken(color, 0.68f));
        canvas.drawPath(leftPath, paint);
        paint.setColor(darken(color, 0.48f));
        canvas.drawPath(rightPath, paint);
        paint.setColor(color);
        canvas.drawPath(topPath, paint);
        paint.setColor(0x55304050);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.55f);
        canvas.drawPath(topPath, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private static int colorFor(String id) {
        if (id.contains("grass") || id.contains("leaves") || id.contains("moss")) return 0xff70b85f;
        if (id.contains("water")) return 0xff438fc8;
        if (id.contains("sand")) return 0xffd8c27b;
        if (id.contains("dirt") || id.contains("mud")) return 0xff98704e;
        if (id.contains("bedrock")) return 0xff34383f;
        if (id.contains("stone") || id.contains("cobble")) return 0xff9299a3;
        if (id.contains("snow") || id.contains("ice")) return 0xffdceaf2;
        int hash = id.hashCode();
        return 0xff000000 | (96 + ((hash >>> 16) & 0x7f)) << 16
                | (96 + ((hash >>> 8) & 0x7f)) << 8 | (96 + (hash & 0x7f));
    }

    private static int darken(int color, float factor) {
        int red = Math.round(((color >>> 16) & 0xff) * factor);
        int green = Math.round(((color >>> 8) & 0xff) * factor);
        int blue = Math.round((color & 0xff) * factor);
        return 0xff000000 | red << 16 | green << 8 | blue;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private record ProjectedBlock(int x, int y, int z, String blockId) {
    }
}
