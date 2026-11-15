package ru.nexora.client;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.view.MotionEvent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Perspective voxel renderer for the bounded NEXORA chunk neighborhood.
 * Chunk decoding happens on the networking thread; mesh creation and all GL
 * calls are confined to the GLSurfaceView render thread.
 */
final class ChunkPreviewView extends GLSurfaceView {
    private final Map<Long, NexoraAndroidProtocolCodec.ChunkData> chunks = new HashMap<>();
    private final WorldRenderer renderer;
    private volatile int centerChunkX;
    private volatile int centerChunkZ;
    private volatile boolean centerSet;
    private float previousX;
    private float previousY;
    private float previousSpan;
    private boolean scaling;
    private volatile MovementListener movementListener;

    interface MovementListener {
        void onPositionChanged(double worldX, double worldY, double worldZ);
    }

    void setMovementListener(MovementListener listener) {
        movementListener = listener;
    }

    ChunkPreviewView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        renderer = new WorldRenderer();
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    void setFirstPersonMode(boolean enabled) {
        queueEvent(() -> renderer.setFirstPersonMode(enabled));
        requestRender();
    }

    void setPlayerSpawn(int worldX, int worldY, int worldZ) {
        queueEvent(() -> renderer.setPlayerSpawn(
                worldX - centerChunkX * 16 + 16 + 0.5f,
                worldY,
                worldZ - centerChunkZ * 16 + 16 + 0.5f));
        requestRender();
    }

    void movePlayer(float forward, float strafe) {
        queueEvent(() -> {
            renderer.movePlayer(forward, strafe);
            publishPlayerPosition();
        });
        requestRender();
    }

    void jumpPlayer() {
        queueEvent(renderer::jumpPlayer);
        requestRender();
    }

    void setServerPosition(double worldX, double worldY, double worldZ) {
        queueEvent(() -> renderer.applyServerPosition(
                (float) (worldX - centerChunkX * 16 + 16),
                (float) worldY,
                (float) (worldZ - centerChunkZ * 16 + 16)));
        requestRender();
    }

    void setRemotePlayerState(NexoraAndroidProtocolCodec.RemotePlayerState player) {
        queueEvent(() -> renderer.setRemotePlayerState(player));
        requestRender();
    }

    void removeRemotePlayer(java.util.UUID playerId) {
        queueEvent(() -> renderer.removeRemotePlayer(playerId));
        requestRender();
    }

    private void publishPlayerPosition() {
        MovementListener listener = movementListener;
        if (listener == null || !renderer.playerInitialized) return;
        listener.onPositionChanged(
                renderer.playerX + centerChunkX * 16 - 16,
                renderer.playerY,
                renderer.playerZ + centerChunkZ * 16 - 16);
    }

    void setCenter(int chunkX, int chunkZ) {
        centerChunkX = chunkX;
        centerChunkZ = chunkZ;
        centerSet = true;
        synchronized (chunks) {
            chunks.entrySet().removeIf(entry -> {
                int x = (int) (entry.getKey() >> 32);
                int z = (int) (long) entry.getKey();
                return Math.abs((long) x - chunkX) > 1 || Math.abs((long) z - chunkZ) > 1;
            });
        }
        queueEvent(renderer::markMeshDirty);
        requestRender();
    }

    void setChunk(NexoraAndroidProtocolCodec.ChunkData chunk) {
        if (!centerSet || Math.abs((long) chunk.chunkX() - centerChunkX) > 1
                || Math.abs((long) chunk.chunkZ() - centerChunkZ) > 1) {
            return;
        }
        synchronized (chunks) {
            chunks.put(key(chunk.chunkX(), chunk.chunkZ()), chunk);
        }
        queueEvent(renderer::markMeshDirty);
        requestRender();
    }

    void setBlockUpdate(NexoraAndroidProtocolCodec.BlockUpdate update) {
        int chunkX = Math.floorDiv(update.x(), 16);
        int chunkZ = Math.floorDiv(update.z(), 16);
        long chunkKey = key(chunkX, chunkZ);
        synchronized (chunks) {
            NexoraAndroidProtocolCodec.ChunkData current = chunks.get(chunkKey);
            if (current == null) return;
            java.util.ArrayList<NexoraAndroidProtocolCodec.ChunkBlock> blocks =
                    new java.util.ArrayList<>(current.blocks());
            int localX = Math.floorMod(update.x(), 16);
            int localZ = Math.floorMod(update.z(), 16);
            blocks.removeIf(block -> block.localX() == localX && block.y() == update.y()
                    && block.localZ() == localZ);
            if (!update.blockId().isEmpty() && !update.blockId().equals("minecraft:air")) {
                blocks.add(new NexoraAndroidProtocolCodec.ChunkBlock(
                        localX, update.y(), localZ, update.blockId()));
            }
            chunks.put(chunkKey, new NexoraAndroidProtocolCodec.ChunkData(
                    chunkX, chunkZ, java.util.List.copyOf(blocks)));
        }
        queueEvent(renderer::markMeshDirty);
        requestRender();
    }

    int receivedChunkCount() {
        synchronized (chunks) {
            return chunks.size();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (event.getPointerCount() > 1) {
            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_MOVE) {
                float span = pointerSpan(event);
                if (previousSpan > 0f) {
                    renderer.zoomBy((previousSpan - span) * 0.08f);
                    requestRender();
                }
                previousSpan = span;
                scaling = true;
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_UP) {
                previousSpan = 0f;
                scaling = false;
                previousX = event.getX(0);
                previousY = event.getY(0);
                return true;
            }
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                previousX = event.getX();
                previousY = event.getY();
                previousSpan = 0f;
                scaling = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!scaling && event.getPointerCount() == 1) {
                    float dx = event.getX() - previousX;
                    float dy = event.getY() - previousY;
                    renderer.orbitBy(dx * 0.45f, -dy * 0.35f);
                    requestRender();
                }
                previousX = event.getX();
                previousY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                previousSpan = 0f;
                scaling = false;
                return true;
            default:
                return true;
        }
    }

    private static float pointerSpan(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0f;
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private final class WorldRenderer implements Renderer {
        private static final int MAX_FLOATS = 1_800_000;
        private static final String VERTEX_SHADER =
                "attribute vec3 aPosition; attribute vec3 aColor; uniform mat4 uMvp;"
                + " varying vec3 vColor; void main(){ gl_Position=uMvp*vec4(aPosition,1.0);"
                + " vColor=aColor; }";
        private static final String FRAGMENT_SHADER =
                "precision mediump float; varying vec3 vColor;"
                + " void main(){ gl_FragColor=vec4(vColor,1.0); }";
        private static final String LABEL_VERTEX_SHADER =
                "attribute vec2 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord;"
                + " void main(){ gl_Position=vec4(aPosition,0.0,1.0); vTexCoord=aTexCoord; }";
        private static final String LABEL_FRAGMENT_SHADER =
                "precision mediump float; uniform sampler2D uTexture; varying vec2 vTexCoord;"
                + " void main(){ gl_FragColor=texture2D(uTexture,vTexCoord); }";

        private final float[] projection = new float[16];
        private final float[] view = new float[16];
        private final float[] mvp = new float[16];
        private final float[] identity = new float[16];
        private volatile boolean meshDirty = true;
        private FloatBuffer vertexBuffer;
        private int vertexCount;
        private int program;
        private int positionHandle;
        private int colorHandle;
        private int matrixHandle;
        private int labelProgram;
        private int labelPositionHandle;
        private int labelTexCoordHandle;
        private int labelTextureHandle;
        private final Map<java.util.UUID, PlayerLabelTexture> playerLabelTextures = new HashMap<>();
        private boolean playerLabelsDirty = true;
        private float yaw = 42f;
        private float elevation = 32f;
        private float distance = 72f;
        private float targetY = 64f;
        private float playerX = 24f;
        private float playerY = 65f;
        private float playerZ = 24f;
        private float verticalVelocity;
        private long lastPhysicsTickNanos;
        private boolean playerInitialized;
        private Set<BlockPosition> collisionBlocks = Set.of();
        private final Map<java.util.UUID, NexoraAndroidProtocolCodec.RemotePlayerState> remotePlayers =
                new HashMap<>();
        private final Map<java.util.UUID, RemoteAnimation> remoteAnimations = new HashMap<>();
        private FloatBuffer remotePlayerBuffer;
        private int remotePlayerVertexCount;
        private boolean remotePlayerMeshDirty = true;
        private boolean firstPersonMode;
        private int surfaceWidth = 1;
        private int surfaceHeight = 1;

        void setFirstPersonMode(boolean enabled) {
            firstPersonMode = enabled;
        }

        void markMeshDirty() {
            meshDirty = true;
        }

        void setPlayerSpawn(float x, float y, float z) {
            playerX = x;
            playerY = y;
            playerZ = z;
            verticalVelocity = 0f;
            playerInitialized = true;
        }

        void applyServerPosition(float x, float y, float z) {
            playerX = x;
            playerY = y;
            playerZ = z;
            playerInitialized = true;
        }

        void setRemotePlayerState(NexoraAndroidProtocolCodec.RemotePlayerState player) {
            long now = System.nanoTime();
            NexoraAndroidProtocolCodec.RemotePlayerState previous = remotePlayers.put(player.playerId(), player);
            RemoteAnimation animation = remoteAnimations.computeIfAbsent(
                    player.playerId(), ignored -> new RemoteAnimation());
            if (previous != null) {
                double dx = player.x() - previous.x();
                double dz = player.z() - previous.z();
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > 0.012) {
                    if (animation.lastMoveNanos != 0L) {
                        long elapsed = Math.max(0L, now - animation.lastMoveNanos);
                        if (elapsed < 350_000_000L) {
                            animation.phase += elapsed * 0.000000006;
                        }
                    }
                    animation.phase += distance * 2.2;
                    animation.lastMoveNanos = now;
                }
            }
            remotePlayerMeshDirty = true;
            if (previous == null || !previous.playerName().equals(player.playerName())) playerLabelsDirty = true;
        }

        void removeRemotePlayer(java.util.UUID playerId) {
            remoteAnimations.remove(playerId);
            if (remotePlayers.remove(playerId) != null) {
                remotePlayerMeshDirty = true;
                playerLabelsDirty = true;
            }
        }

        private static final class RemoteAnimation {
            private double phase;
            private long lastMoveNanos;

            private RemoteAnimation() {
            }
        }

        void movePlayer(float forward, float strafe) {
            if (!playerInitialized) return;
            double angle = Math.toRadians(yaw);
            float dx = (float) (-Math.cos(angle) * forward + Math.sin(angle) * strafe);
            float dz = (float) (-Math.sin(angle) * forward - Math.cos(angle) * strafe);
            float length = (float) Math.sqrt(dx * dx + dz * dz);
            if (length < 0.001f) return;
            dx = dx / length * 0.20f;
            dz = dz / length * 0.20f;
            if (canOccupy(playerX + dx, playerY, playerZ)) playerX += dx;
            if (canOccupy(playerX, playerY, playerZ + dz)) playerZ += dz;
        }

        void jumpPlayer() {
            if (playerInitialized && hasGroundBelow()) {
                verticalVelocity = 0.34f;
                lastPhysicsTickNanos = System.nanoTime();
            }
        }

        private boolean canOccupy(float x, float feetY, float z) {
            int minX = (int) Math.floor(x - 0.29f);
            int maxX = (int) Math.floor(x + 0.29f);
            int minY = (int) Math.floor(feetY + 0.05f);
            int maxY = (int) Math.floor(feetY + 1.75f);
            int minZ = (int) Math.floor(z - 0.29f);
            int maxZ = (int) Math.floor(z + 0.29f);
            for (int bx = minX; bx <= maxX; bx++) {
                for (int by = minY; by <= maxY; by++) {
                    for (int bz = minZ; bz <= maxZ; bz++) {
                        if (collisionBlocks.contains(new BlockPosition(bx, by, bz))) return false;
                    }
                }
            }
            return true;
        }

        private boolean hasGroundBelow() {
            int y = (int) Math.floor(playerY - 0.08f);
            for (int x = (int) Math.floor(playerX - 0.25f);
                 x <= (int) Math.floor(playerX + 0.25f); x++) {
                for (int z = (int) Math.floor(playerZ - 0.25f);
                     z <= (int) Math.floor(playerZ + 0.25f); z++) {
                    if (collisionBlocks.contains(new BlockPosition(x, y, z))) return true;
                }
            }
            return false;
        }

        void orbitBy(float yawDelta, float elevationDelta) {
            yaw += yawDelta;
            elevation = clamp(elevation + elevationDelta, 12f, 78f);
        }

        void zoomBy(float delta) {
            distance = clamp(distance + delta, 28f, 115f);
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0.055f, 0.075f, 0.11f, 1f);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glDepthFunc(GLES20.GL_LEQUAL);
            GLES20.glDisable(GLES20.GL_CULL_FACE);
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            colorHandle = GLES20.glGetAttribLocation(program, "aColor");
            matrixHandle = GLES20.glGetUniformLocation(program, "uMvp");
            labelProgram = createProgram(LABEL_VERTEX_SHADER, LABEL_FRAGMENT_SHADER);
            labelPositionHandle = GLES20.glGetAttribLocation(labelProgram, "aPosition");
            labelTexCoordHandle = GLES20.glGetAttribLocation(labelProgram, "aTexCoord");
            labelTextureHandle = GLES20.glGetUniformLocation(labelProgram, "uTexture");
            Matrix.setIdentityM(identity, 0);
            meshDirty = true;
            playerLabelsDirty = true;
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            surfaceWidth = Math.max(1, width);
            surfaceHeight = Math.max(1, height);
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
            float ratio = (float) surfaceWidth / surfaceHeight;
            Matrix.perspectiveM(projection, 0, 48f, ratio, 0.1f, 300f);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            if (meshDirty) rebuildMesh();
            if (playerInitialized && Math.abs(verticalVelocity) > 0.0001f) {
                long now = System.nanoTime();
                if (now - lastPhysicsTickNanos >= 50_000_000L) {
                    float previousPlayerY = playerY;
                    float nextY = playerY + verticalVelocity;
                    if (canOccupy(playerX, nextY, playerZ)) {
                        playerY = nextY;
                        verticalVelocity -= 0.035f;
                    } else {
                        verticalVelocity = 0f;
                    }
                    if (playerY < -64f) verticalVelocity = 0f;
                    lastPhysicsTickNanos = now;
                    if (Math.abs(playerY - previousPlayerY) > 0.0001f) {
                        ChunkPreviewView.this.publishPlayerPosition();
                    }
                    if (Math.abs(verticalVelocity) > 0.0001f) {
                        ChunkPreviewView.this.requestRender();
                    }
                } else {
                    ChunkPreviewView.this.requestRender();
                }
            }
            if (vertexBuffer == null || vertexCount == 0 || program == 0) return;

            float centerX = playerInitialized ? playerX : 24f;
            float centerZ = playerInitialized ? playerZ : 24f;
            double yawRadians = Math.toRadians(yaw);
            double elevationRadians = Math.toRadians(elevation);
            if (firstPersonMode) {
                float eyeX = playerInitialized ? playerX : centerX;
                float eyeY = playerInitialized ? playerY + 1.62f : targetY + 1.6f;
                float eyeZ = playerInitialized ? playerZ : centerZ;
                float lookX = -(float) Math.cos(yawRadians) * (float) Math.cos(elevationRadians);
                float lookY = (float) Math.sin(elevationRadians);
                float lookZ = -(float) Math.sin(yawRadians) * (float) Math.cos(elevationRadians);
                Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ,
                        eyeX + lookX, eyeY + lookY, eyeZ + lookZ, 0f, 1f, 0f);
            } else {
                float horizontal = (float) (distance * Math.cos(elevationRadians));
                float eyeX = centerX + (float) Math.cos(yawRadians) * horizontal;
                float eyeY = (playerInitialized ? playerY : targetY) + (float) Math.sin(elevationRadians) * distance;
                float eyeZ = centerZ + (float) Math.sin(yawRadians) * horizontal;
                Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ,
                        centerX, playerInitialized ? playerY + 0.8f : targetY, centerZ, 0f, 1f, 0f);
            }
            Matrix.multiplyMM(mvp, 0, projection, 0, view, 0);

            GLES20.glUseProgram(program);
            GLES20.glUniformMatrix4fv(matrixHandle, 1, false, mvp, 0);
            vertexBuffer.position(0);
            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glEnableVertexAttribArray(colorHandle);
            vertexBuffer.position(0);
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT,
                    false, 6 * 4, vertexBuffer);
            vertexBuffer.position(3);
            GLES20.glVertexAttribPointer(colorHandle, 3, GLES20.GL_FLOAT,
                    false, 6 * 4, vertexBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount);
            if (!remotePlayers.isEmpty()) {
                remotePlayerMeshDirty = true;
                ChunkPreviewView.this.postDelayed(ChunkPreviewView.this::requestRender, 80L);
            }
            if (remotePlayerMeshDirty) rebuildRemotePlayerMesh();
            if (remotePlayerBuffer != null && remotePlayerVertexCount > 0) {
                remotePlayerBuffer.position(0);
                GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT,
                        false, 6 * 4, remotePlayerBuffer);
                remotePlayerBuffer.position(3);
                GLES20.glVertexAttribPointer(colorHandle, 3, GLES20.GL_FLOAT,
                        false, 6 * 4, remotePlayerBuffer);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, remotePlayerVertexCount);
            }
            drawRemotePlayerLabels();
            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(colorHandle);
        }

        private void drawRemotePlayerLabels() {
            if (playerLabelsDirty) rebuildPlayerLabelTextures();
            if (playerLabelTextures.isEmpty() || labelProgram == 0) return;
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glUseProgram(labelProgram);
            GLES20.glUniform1i(labelTextureHandle, 0);
            GLES20.glEnableVertexAttribArray(labelPositionHandle);
            GLES20.glEnableVertexAttribArray(labelTexCoordHandle);
            float[] world = new float[4];
            float[] clip = new float[4];
            for (Map.Entry<java.util.UUID, NexoraAndroidProtocolCodec.RemotePlayerState> entry : remotePlayers.entrySet()) {
                PlayerLabelTexture label = playerLabelTextures.get(entry.getKey());
                if (label == null) continue;
                NexoraAndroidProtocolCodec.RemotePlayerState player = entry.getValue();
                world[0] = (float) (player.x() - centerChunkX * 16 + 16);
                world[1] = (float) player.y() + 2.05f;
                world[2] = (float) (player.z() - centerChunkZ * 16 + 16);
                world[3] = 1f;
                Matrix.multiplyMV(clip, 0, mvp, 0, world, 0);
                if (clip[3] <= 0.01f) continue;
                float centerX = clip[0] / clip[3];
                float centerY = clip[1] / clip[3];
                if (centerX < -1.3f || centerX > 1.3f || centerY < -1.3f || centerY > 1.3f) continue;
                float halfWidth = Math.min(0.55f, (float) label.width / surfaceWidth);
                float halfHeight = (float) label.height / surfaceHeight;
                float left = centerX - halfWidth;
                float right = centerX + halfWidth;
                float bottom = centerY;
                float top = centerY + halfHeight * 2f;
                float[] vertices = {left,bottom,0f,1f, right,bottom,1f,1f, right,top,1f,0f, left,bottom,0f,1f, right,top,1f,0f, left,top,0f,0f};
                FloatBuffer buffer = ByteBuffer.allocateDirect(vertices.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
                buffer.put(vertices).position(0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, label.textureId);
                GLES20.glVertexAttribPointer(labelPositionHandle, 2, GLES20.GL_FLOAT, false, 16, buffer);
                buffer.position(2);
                GLES20.glVertexAttribPointer(labelTexCoordHandle, 2, GLES20.GL_FLOAT, false, 16, buffer);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
            }
            GLES20.glDisableVertexAttribArray(labelPositionHandle);
            GLES20.glDisableVertexAttribArray(labelTexCoordHandle);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        }

        private void rebuildPlayerLabelTextures() {
            playerLabelsDirty = false;
            for (PlayerLabelTexture old : playerLabelTextures.values()) {
                GLES20.glDeleteTextures(1, new int[]{old.textureId}, 0);
            }
            playerLabelTextures.clear();
            for (NexoraAndroidProtocolCodec.RemotePlayerState player : remotePlayers.values()) {
                String name = player.playerName();
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setTextSize(25f);
                paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
                int width = Math.max(72, Math.min(260, (int) Math.ceil(paint.measureText(name)) + 28));
                int height = 42;
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                paint.setColor(Color.argb(205, 12, 19, 31));
                canvas.drawRoundRect(0f, 0f, width, height, 10f, 10f, paint);
                paint.setColor(Color.WHITE);
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(name, width * 0.5f, 29f, paint);
                int[] texture = new int[1];
                GLES20.glGenTextures(1, texture, 0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                bitmap.recycle();
                playerLabelTextures.put(player.playerId(), new PlayerLabelTexture(texture[0], width, height));
            }
        }

        private record PlayerLabelTexture(int textureId, int width, int height) {
        }

        private void rebuildRemotePlayerMesh() {
            remotePlayerMeshDirty = false;
            if (remotePlayers.isEmpty()) {
                remotePlayerBuffer = null;
                remotePlayerVertexCount = 0;
                return;
            }
            long now = System.nanoTime();
            MeshBuilder mesh = new MeshBuilder(120_000);
            // Original NEXORA appearance presets; no proprietary game skins are bundled.
            float[][] palettes = {
                    {0.18f,0.62f,0.96f, 0.12f,0.43f,0.72f, 0.88f,0.72f,0.58f, 0.12f,0.20f,0.34f},
                    {0.18f,0.68f,0.48f, 0.10f,0.38f,0.28f, 0.72f,0.48f,0.32f, 0.12f,0.10f,0.08f},
                    {0.76f,0.34f,0.28f, 0.42f,0.16f,0.18f, 0.94f,0.76f,0.60f, 0.22f,0.12f,0.08f},
                    {0.48f,0.38f,0.78f, 0.26f,0.20f,0.48f, 0.58f,0.38f,0.24f, 0.08f,0.08f,0.12f},
                    {0.82f,0.64f,0.22f, 0.46f,0.30f,0.10f, 0.96f,0.82f,0.68f, 0.24f,0.16f,0.08f}
            };
            for (NexoraAndroidProtocolCodec.RemotePlayerState player : remotePlayers.values()) {
                float[] palette = palettes[Math.max(0, Math.min(4, player.skinIndex()))];
                float[] bodyColor = {palette[0], palette[1], palette[2]};
                float[] limbColor = {palette[3], palette[4], palette[5]};
                float[] headColor = {palette[6], palette[7], palette[8]};
                float[] hairColor = {palette[9], palette[10], palette[11]};
                float x = (float) (player.x() - centerChunkX * 16 + 16);
                float y = (float) player.y();
                float z = (float) (player.z() - centerChunkZ * 16 + 16);
                float angle = (float) Math.toRadians(player.yaw());
                RemoteAnimation animation = remoteAnimations.computeIfAbsent(
                        player.playerId(), ignored -> new RemoteAnimation());
                long moveAge = animation.lastMoveNanos == 0L
                        ? Long.MAX_VALUE : Math.max(0L, now - animation.lastMoveNanos);
                float walkBlend = moveAge >= 350_000_000L ? 0f
                        : 1f - (float) moveAge / 350_000_000L;
                float phase = (float) (animation.phase
                        + (moveAge == Long.MAX_VALUE ? 0.0 : moveAge * 0.000000006));
                float swing = (float) Math.sin(phase) * 0.58f * walkBlend;
                float idle = (float) Math.sin(now * 0.000000002) * 0.012f;
                float bob = idle + Math.abs((float) Math.sin(phase * 2f)) * 0.035f * walkBlend;

                // Torso, head, arms and legs are separate articulated cuboids.
                addPlayerBox(mesh, x, y, z, angle,
                        0f, 1.13f + bob, 0f, 0.22f, 0.36f, 0.13f,
                        0f, 1.13f, 0f, 0f, bodyColor, 1.0f);
                addPlayerBox(mesh, x, y, z, angle,
                        0f, 1.62f + bob, 0f, 0.18f, 0.18f, 0.18f,
                        0f, 1.62f, 0f, 0f, headColor, 1.0f);
                addPlayerBox(mesh, x, y, z, angle,
                        0f, 1.785f + bob, 0f, 0.185f, 0.035f, 0.185f,
                        0f, 1.785f, 0f, 0f, hairColor, 0.96f);

                addPlayerBox(mesh, x, y, z, angle,
                        -0.30f, 1.16f + bob, 0f, 0.105f, 0.31f, 0.11f,
                        -0.30f, 1.44f + bob, 0f, swing, limbColor, 0.92f);
                addPlayerBox(mesh, x, y, z, angle,
                        0.30f, 1.16f + bob, 0f, 0.105f, 0.31f, 0.11f,
                        0.30f, 1.44f + bob, 0f, -swing, limbColor, 0.92f);

                addPlayerBox(mesh, x, y, z, angle,
                        -0.12f, 0.43f + bob, 0f, 0.12f, 0.37f, 0.13f,
                        -0.12f, 0.78f + bob, 0f, -swing, limbColor, 0.82f);
                addPlayerBox(mesh, x, y, z, angle,
                        0.12f, 0.43f + bob, 0f, 0.12f, 0.37f, 0.13f,
                        0.12f, 0.78f + bob, 0f, swing, limbColor, 0.82f);
                addPlayerBox(mesh, x, y, z, angle,
                        -0.12f, 0.10f + bob, -0.045f, 0.13f, 0.10f, 0.20f,
                        -0.12f, 0.18f + bob, 0f, -swing * 0.22f, limbColor, 0.78f);
                addPlayerBox(mesh, x, y, z, angle,
                        0.12f, 0.10f + bob, -0.045f, 0.13f, 0.10f, 0.20f,
                        0.12f, 0.18f + bob, 0f, swing * 0.22f, limbColor, 0.78f);
                if (mesh.full()) break;
            }
            float[] values = mesh.values();
            remotePlayerVertexCount = values.length / 6;
            ByteBuffer bytes = ByteBuffer.allocateDirect(values.length * 4)
                    .order(ByteOrder.nativeOrder());
            remotePlayerBuffer = bytes.asFloatBuffer();
            remotePlayerBuffer.put(values);
            remotePlayerBuffer.position(0);
        }

        private void addPlayerBox(MeshBuilder mesh, float baseX, float baseY, float baseZ,
                                  float yawRadians,
                                  float centerX, float centerY, float centerZ,
                                  float halfX, float halfY, float halfZ,
                                  float pivotX, float pivotY, float pivotZ, float pitch,
                                  float[] color, float shade) {
            float x0 = centerX - halfX, x1 = centerX + halfX;
            float y0 = centerY - halfY, y1 = centerY + halfY;
            float z0 = centerZ - halfZ, z1 = centerZ + halfZ;
            float[][] p = {
                    playerPoint(baseX, baseY, baseZ, yawRadians, x0,y0,z0,pivotX,pivotY,pivotZ,pitch),
                    playerPoint(baseX, baseY, baseZ, yawRadians, x0,y0,z1,pivotX,pivotY,pivotZ,pitch),
                    playerPoint(baseX, baseY, baseZ, yawRadians, x0,y1,z0,pivotX,pivotY,pivotZ,pitch),
                    playerPoint(baseX, baseY, baseZ, yawRadians, x0,y1,z1,pivotX,pivotY,pivotZ,pitch),
                    playerPoint(baseX, baseY, baseZ, yawRadians, x1,y0,z0,pivotX,pivotY,pivotZ,pitch),
                    playerPoint(baseX, baseY, baseZ, yawRadians, x1,y0,z1,pivotX,pivotY,pivotZ,pitch),
                    playerPoint(baseX, baseY, baseZ, yawRadians, x1,y1,z0,pivotX,pivotY,pivotZ,pitch),
                    playerPoint(baseX, baseY, baseZ, yawRadians, x1,y1,z1,pivotX,pivotY,pivotZ,pitch)
            };
            mesh.quad(p[2][0],p[2][1],p[2][2], p[3][0],p[3][1],p[3][2],
                    p[7][0],p[7][1],p[7][2], p[6][0],p[6][1],p[6][2], color, shade);
            mesh.quad(p[1][0],p[1][1],p[1][2], p[0][0],p[0][1],p[0][2],
                    p[4][0],p[4][1],p[4][2], p[5][0],p[5][1],p[5][2], color, shade * 0.5f);
            mesh.quad(p[0][0],p[0][1],p[0][2], p[1][0],p[1][1],p[1][2],
                    p[3][0],p[3][1],p[3][2], p[2][0],p[2][1],p[2][2], color, shade * 0.72f);
            mesh.quad(p[5][0],p[5][1],p[5][2], p[4][0],p[4][1],p[4][2],
                    p[6][0],p[6][1],p[6][2], p[7][0],p[7][1],p[7][2], color, shade * 0.82f);
            mesh.quad(p[4][0],p[4][1],p[4][2], p[0][0],p[0][1],p[0][2],
                    p[2][0],p[2][1],p[2][2], p[6][0],p[6][1],p[6][2], color, shade * 0.62f);
            mesh.quad(p[1][0],p[1][1],p[1][2], p[5][0],p[5][1],p[5][2],
                    p[7][0],p[7][1],p[7][2], p[3][0],p[3][1],p[3][2], color, shade * 0.68f);
        }

        private float[] playerPoint(float baseX, float baseY, float baseZ, float yawRadians,
                                    float x, float y, float z,
                                    float pivotX, float pivotY, float pivotZ, float pitch) {
            float localY = y - pivotY;
            float localZ = z - pivotZ;
            float rotatedY = localY * (float) Math.cos(pitch) - localZ * (float) Math.sin(pitch);
            float rotatedZ = localY * (float) Math.sin(pitch) + localZ * (float) Math.cos(pitch);
            float localX = x - pivotX;
            float rotatedX = pivotX + localX;
            rotatedY += pivotY;
            rotatedZ += pivotZ;
            float worldX = baseX + rotatedX * (float) Math.cos(yawRadians)
                    + rotatedZ * (float) Math.sin(yawRadians);
            float worldZ = baseZ - rotatedX * (float) Math.sin(yawRadians)
                    + rotatedZ * (float) Math.cos(yawRadians);
            return new float[]{worldX, baseY + rotatedY, worldZ};
        }

        private void rebuildMesh() {
            meshDirty = false;
            List<NexoraAndroidProtocolCodec.ChunkData> snapshot;
            synchronized (chunks) {
                snapshot = new ArrayList<>(chunks.values());
            }
            Set<BlockPosition> occupied = new HashSet<>();
            List<WorldBlock> blocks = new ArrayList<>();
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            for (NexoraAndroidProtocolCodec.ChunkData chunk : snapshot) {
                int offsetX = (chunk.chunkX() - centerChunkX + 1) * 16;
                int offsetZ = (chunk.chunkZ() - centerChunkZ + 1) * 16;
                for (NexoraAndroidProtocolCodec.ChunkBlock block : chunk.blocks()) {
                    int x = offsetX + block.localX();
                    int y = block.y();
                    int z = offsetZ + block.localZ();
                    WorldBlock worldBlock = new WorldBlock(x, y, z, block.blockId());
                    blocks.add(worldBlock);
                    occupied.add(new BlockPosition(x, y, z));
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
            collisionBlocks = java.util.Collections.unmodifiableSet(occupied);
            if (blocks.isEmpty()) {
                vertexBuffer = null;
                vertexCount = 0;
                targetY = 64f;
                return;
            }
            targetY = (minY + maxY) * 0.5f;
            MeshBuilder mesh = new MeshBuilder(MAX_FLOATS);
            for (WorldBlock block : blocks) {
                float x0 = block.x() - 0.5f;
                float x1 = block.x() + 0.5f;
                float y0 = block.y() - 0.5f;
                float y1 = block.y() + 0.5f;
                float z0 = block.z() - 0.5f;
                float z1 = block.z() + 0.5f;
                float[] color = colorFor(block.blockId());
                if (!occupied.contains(new BlockPosition(block.x(), block.y() + 1, block.z()))) {
                    mesh.quad(x0,y1,z0, x0,y1,z1, x1,y1,z1, x1,y1,z0, color, 1.00f);
                }
                if (!occupied.contains(new BlockPosition(block.x(), block.y() - 1, block.z()))) {
                    mesh.quad(x0,y0,z1, x0,y0,z0, x1,y0,z0, x1,y0,z1, color, 0.48f);
                }
                if (!occupied.contains(new BlockPosition(block.x() - 1, block.y(), block.z()))) {
                    mesh.quad(x0,y0,z0, x0,y0,z1, x0,y1,z1, x0,y1,z0, color, 0.72f);
                }
                if (!occupied.contains(new BlockPosition(block.x() + 1, block.y(), block.z()))) {
                    mesh.quad(x1,y0,z1, x1,y0,z0, x1,y1,z0, x1,y1,z1, color, 0.82f);
                }
                if (!occupied.contains(new BlockPosition(block.x(), block.y(), block.z() - 1))) {
                    mesh.quad(x1,y0,z0, x0,y0,z0, x0,y1,z0, x1,y1,z0, color, 0.62f);
                }
                if (!occupied.contains(new BlockPosition(block.x(), block.y(), block.z() + 1))) {
                    mesh.quad(x0,y0,z1, x1,y0,z1, x1,y1,z1, x0,y1,z1, color, 0.68f);
                }
                if (mesh.full()) break;
            }
            float[] values = mesh.values();
            vertexCount = values.length / 6;
            ByteBuffer bytes = ByteBuffer.allocateDirect(values.length * 4)
                    .order(ByteOrder.nativeOrder());
            vertexBuffer = bytes.asFloatBuffer();
            vertexBuffer.put(values);
            vertexBuffer.position(0);
        }

        private int createProgram(String vertexSource, String fragmentSource) {
            int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            int linked = GLES20.glCreateProgram();
            GLES20.glAttachShader(linked, vertex);
            GLES20.glAttachShader(linked, fragment);
            GLES20.glLinkProgram(linked);
            int[] status = new int[1];
            GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0);
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);
            if (status[0] == 0) {
                String error = GLES20.glGetProgramInfoLog(linked);
                GLES20.glDeleteProgram(linked);
                throw new IllegalStateException("NEXORA shader link failed: " + error);
            }
            return linked;
        }

        private int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] status = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                String error = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                throw new IllegalStateException("NEXORA shader compile failed: " + error);
            }
            return shader;
        }
    }

    private static final class MeshBuilder {
        private final int maxFloats;
        private float[] data = new float[65_536];
        private int size;

        MeshBuilder(int maxFloats) {
            this.maxFloats = maxFloats;
        }

        boolean full() {
            return size + 36 > maxFloats;
        }

        void quad(float ax, float ay, float az, float bx, float by, float bz,
                  float cx, float cy, float cz, float dx, float dy, float dz,
                  float[] color, float shade) {
            if (full()) return;
            ensureCapacity(size + 36);
            vertex(ax,ay,az,color,shade);
            vertex(bx,by,bz,color,shade);
            vertex(cx,cy,cz,color,shade);
            vertex(ax,ay,az,color,shade);
            vertex(cx,cy,cz,color,shade);
            vertex(dx,dy,dz,color,shade);
        }

        private void ensureCapacity(int required) {
            if (required <= data.length) return;
            int next = Math.min(maxFloats, Math.max(required, data.length * 2));
            data = java.util.Arrays.copyOf(data, next);
        }

        private void vertex(float x, float y, float z, float[] color, float shade) {
            data[size++] = x;
            data[size++] = y;
            data[size++] = z;
            data[size++] = color[0] * shade;
            data[size++] = color[1] * shade;
            data[size++] = color[2] * shade;
        }

        float[] values() {
            return java.util.Arrays.copyOf(data, size);
        }
    }

    private static float[] colorFor(String id) {
        int color;
        if (id.contains("grass") || id.contains("leaves") || id.contains("moss")) color = 0xff70b85f;
        else if (id.contains("water")) color = 0xff438fc8;
        else if (id.contains("sand")) color = 0xffd8c27b;
        else if (id.contains("dirt") || id.contains("mud")) color = 0xff98704e;
        else if (id.contains("bedrock")) color = 0xff34383f;
        else if (id.contains("oak_log") || id.contains("log")) color = 0xff795334;
        else if (id.contains("stone") || id.contains("cobble")) color = 0xff9299a3;
        else if (id.contains("snow") || id.contains("ice")) color = 0xffdceaf2;
        else {
            int hash = id.hashCode();
            color = 0xff000000 | (96 + ((hash >>> 16) & 0x7f)) << 16
                    | (96 + ((hash >>> 8) & 0x7f)) << 8 | (96 + (hash & 0x7f));
        }
        return new float[]{((color >>> 16) & 0xff) / 255f,
                ((color >>> 8) & 0xff) / 255f, (color & 0xff) / 255f};
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private record BlockPosition(int x, int y, int z) {
    }

    private record WorldBlock(int x, int y, int z, String blockId) {
    }
}
