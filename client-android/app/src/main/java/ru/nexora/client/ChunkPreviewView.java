package ru.nexora.client;

import android.content.Context;
import android.opengl.GLES20;
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

    void setCenter(int chunkX, int chunkZ) {
        centerChunkX = chunkX;
        centerChunkZ = chunkZ;
        centerSet = true;
        synchronized (chunks) {
            chunks.clear();
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
        private float yaw = 42f;
        private float elevation = 32f;
        private float distance = 72f;
        private float targetY = 64f;
        private boolean firstPersonMode;
        private int surfaceWidth = 1;
        private int surfaceHeight = 1;

        void setFirstPersonMode(boolean enabled) {
            firstPersonMode = enabled;
        }

        void markMeshDirty() {
            meshDirty = true;
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
            Matrix.setIdentityM(identity, 0);
            meshDirty = true;
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
            if (vertexBuffer == null || vertexCount == 0 || program == 0) return;

            float centerX = 24f;
            float centerZ = 24f;
            double yawRadians = Math.toRadians(yaw);
            double elevationRadians = Math.toRadians(elevation);
            if (firstPersonMode) {
                float eyeY = targetY + 1.6f;
                float lookX = -(float) Math.cos(yawRadians) * (float) Math.cos(elevationRadians);
                float lookY = (float) Math.sin(elevationRadians);
                float lookZ = -(float) Math.sin(yawRadians) * (float) Math.cos(elevationRadians);
                Matrix.setLookAtM(view, 0, centerX, eyeY, centerZ,
                        centerX + lookX, eyeY + lookY, centerZ + lookZ, 0f, 1f, 0f);
            } else {
                float horizontal = (float) (distance * Math.cos(elevationRadians));
                float eyeX = centerX + (float) Math.cos(yawRadians) * horizontal;
                float eyeY = targetY + (float) Math.sin(elevationRadians) * distance;
                float eyeZ = centerZ + (float) Math.sin(yawRadians) * horizontal;
                Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ,
                        centerX, targetY, centerZ, 0f, 1f, 0f);
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
            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(colorHandle);
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
