package net.vulkanmod.vulkan.raytracing;

import net.minecraft.world.entity.Entity;
import org.joml.Vector3f;

import java.util.IdentityHashMap;
import java.util.Map;

/** Captures the already-posed ModelPart quads for the RT entity BLAS. */
public final class RtEntityGeometry {
    private static final Map<Entity, Snapshot> SNAPSHOTS = new IdentityHashMap<>();
    private static final ThreadLocal<Capture> ACTIVE_CAPTURE = new ThreadLocal<>();

    private RtEntityGeometry() {}

    public static void begin(Entity entity, double cameraX, double cameraY, double cameraZ) {
        if (!RayTracingManager.isEnabled() || entity == null) return;
        ACTIVE_CAPTURE.set(new Capture(entity, (float) cameraX, (float) cameraY, (float) cameraZ));
    }

    public static void captureQuad(net.minecraft.client.model.geom.ModelPart.Vertex[] vertices) {
        Capture capture = ACTIVE_CAPTURE.get();
        if (capture == null || vertices.length != 4) return;
        for (net.minecraft.client.model.geom.ModelPart.Vertex vertex : vertices) {
            Vector3f position = vertex.pos;
            capture.add(position.x + capture.cameraX, position.y + capture.cameraY, position.z + capture.cameraZ);
        }
    }

    public static void finish(Entity entity) {
        Capture capture = ACTIVE_CAPTURE.get();
        ACTIVE_CAPTURE.remove();
        if (capture == null || capture.entity != entity || capture.count < 4) return;
        SNAPSHOTS.put(entity, new Snapshot(capture.copy(), capture.count));
    }

    public static Snapshot get(Entity entity) {
        return SNAPSHOTS.get(entity);
    }

    public static void remove(Entity entity) {
        SNAPSHOTS.remove(entity);
    }

    public record Snapshot(float[] positions, int vertexCount) {}

    private static final class Capture {
        private final Entity entity;
        private final float cameraX, cameraY, cameraZ;
        private float[] positions = new float[384];
        private int count;

        private Capture(Entity entity, float cameraX, float cameraY, float cameraZ) {
            this.entity = entity;
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.cameraZ = cameraZ;
        }

        private void add(float x, float y, float z) {
            int next = (count + 1) * 3;
            if (next > positions.length) {
                float[] grown = new float[positions.length * 2];
                System.arraycopy(positions, 0, grown, 0, positions.length);
                positions = grown;
            }
            int offset = count++ * 3;
            positions[offset] = x;
            positions[offset + 1] = y;
            positions[offset + 2] = z;
        }

        private float[] copy() {
            float[] result = new float[count * 3];
            System.arraycopy(positions, 0, result, 0, result.length);
            return result;
        }
    }
}
