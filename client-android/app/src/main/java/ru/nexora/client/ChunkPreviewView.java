package ru.nexora.client;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Lightweight top-down 16x16 surface preview; not a full 3D renderer. */
final class ChunkPreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] heights = new int[256];
    private String[] blockIds = new String[256];
    private boolean hasChunk;

    ChunkPreviewView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    void setChunk(NexoraAndroidProtocolCodec.ChunkData chunk) {
        heights = chunk.heights().clone();
        blockIds = chunk.blockIds().clone();
        hasChunk = true;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cell = Math.min(getWidth(), getHeight()) / 16f;
        float left = (getWidth() - cell * 16f) / 2f;
        float top = (getHeight() - cell * 16f) / 2f;
        paint.setColor(0xff101827);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        if (!hasChunk || cell <= 0f) return;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int index = z * 16 + x;
                String id = blockIds[index];
                if (id == null) continue;
                paint.setColor(colorFor(id));
                float inset = Math.max(1f, cell * 0.035f);
                RectF tile = new RectF(left + x * cell + inset, top + z * cell + inset,
                        left + (x + 1) * cell - inset, top + (z + 1) * cell - inset);
                canvas.drawRoundRect(tile, cell * 0.08f, cell * 0.08f, paint);
            }
        }
    }

    private static int colorFor(String id) {
        if (id.contains("grass") || id.contains("leaves") || id.contains("moss")) return 0xff5e9d54;
        if (id.contains("water")) return 0xff438fc8;
        if (id.contains("sand")) return 0xffd8c27b;
        if (id.contains("dirt") || id.contains("mud")) return 0xff8a6546;
        if (id.contains("bedrock")) return 0xff34383f;
        if (id.contains("stone") || id.contains("cobble")) return 0xff858b94;
        if (id.contains("snow") || id.contains("ice")) return 0xffdceaf2;
        int hash = id.hashCode();
        return 0xff000000 | (96 + ((hash >>> 16) & 0x7f)) << 16
                | (96 + ((hash >>> 8) & 0x7f)) << 8 | (96 + (hash & 0x7f));
    }
}
