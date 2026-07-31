package net.vulkanmod.vulkan.raytracing;

import net.vulkanmod.Initializer;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.Device;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * GPU timestamps around terrain layers using the ray-query pipeline. The number
 * includes terrain rasterization because RT currently runs inline in its fragment
 * shader; comparing the value with Minecraft Only exposes the actual RT delta.
 */
public final class RtGpuProfiler {
    private static final int PROFILED_LAYER_COUNT = 4;
    private static final int QUERIES_PER_LAYER = 2;
    private static final int QUERIES_PER_FRAME = PROFILED_LAYER_COUNT * QUERIES_PER_LAYER;
    private static final float SMOOTHING = 0.15F;

    private static long queryPool = VK_NULL_HANDLE;
    private static int frameCount;
    private static int[] recordedLayerMasks;
    private static float[] layerGpuMs = new float[PROFILED_LAYER_COUNT];
    private static float terrainGpuMs;
    private static boolean hasSample;
    private static boolean supported;

    private RtGpuProfiler() {
    }

    public static void create(int frames) {
        cleanUp();
        Device device = Vulkan.getDevice();
        supported = device != null && device.supportsGraphicsTimestamps();
        if (!supported) {
            Initializer.LOGGER.warn("RT GPU profiler disabled: graphics timestamps are unsupported");
            return;
        }

        frameCount = Math.max(1, frames);
        recordedLayerMasks = new int[frameCount];
        try (MemoryStack stack = stackPush()) {
            VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .queryType(VK_QUERY_TYPE_TIMESTAMP)
                    .queryCount(frameCount * QUERIES_PER_FRAME);
            LongBuffer handle = stack.mallocLong(1);
            int result = vkCreateQueryPool(Vulkan.getVkDevice(), info, null, handle);
            if (result != VK_SUCCESS) {
                supported = false;
                throw new IllegalStateException("Failed to create RT timestamp query pool: " + result);
            }
            queryPool = handle.get(0);
        }
    }

    public static void beginFrame(VkCommandBuffer commandBuffer, int frame) {
        if (!supported || queryPool == VK_NULL_HANDLE || frame < 0 || frame >= frameCount) {
            return;
        }

        readCompletedFrame(frame);
        int queryBase = frame * QUERIES_PER_FRAME;
        vkCmdResetQueryPool(commandBuffer, queryPool, queryBase, QUERIES_PER_FRAME);
        recordedLayerMasks[frame] = 0;
    }

    public static void beginTerrainLayer(
            VkCommandBuffer commandBuffer,
            TerrainRenderType layer,
            int frame
    ) {
        int layerIndex = profiledLayerIndex(layer);
        if (!isValid(frame, layerIndex)) {
            return;
        }
        int query = frame * QUERIES_PER_FRAME + layerIndex * QUERIES_PER_LAYER;
        vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, query);
    }

    public static void endTerrainLayer(
            VkCommandBuffer commandBuffer,
            TerrainRenderType layer,
            int frame
    ) {
        int layerIndex = profiledLayerIndex(layer);
        if (!isValid(frame, layerIndex)) {
            return;
        }
        int query = frame * QUERIES_PER_FRAME + layerIndex * QUERIES_PER_LAYER + 1;
        vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, query);
        recordedLayerMasks[frame] |= 1 << layerIndex;
    }

    public static float getTerrainGpuMs() {
        return terrainGpuMs;
    }

    public static float getLayerGpuMs(TerrainRenderType layer) {
        int index = profiledLayerIndex(layer);
        return index < 0 ? 0.0F : layerGpuMs[index];
    }

    public static boolean hasSample() {
        return hasSample;
    }

    public static void recreate(int frames) {
        create(frames);
    }

    public static void cleanUp() {
        if (queryPool != VK_NULL_HANDLE) {
            vkDestroyQueryPool(Vulkan.getVkDevice(), queryPool, null);
            queryPool = VK_NULL_HANDLE;
        }
        frameCount = 0;
        recordedLayerMasks = null;
        layerGpuMs = new float[PROFILED_LAYER_COUNT];
        terrainGpuMs = 0.0F;
        hasSample = false;
        supported = false;
    }

    private static void readCompletedFrame(int frame) {
        int mask = recordedLayerMasks[frame];
        float total = 0.0F;
        boolean readAny = false;
        float timestampPeriod = Vulkan.getDevice().timestampPeriod();

        try (MemoryStack stack = stackPush()) {
            ByteBuffer timestamps = stack.malloc(2 * Long.BYTES);
            for (int layer = 0; layer < PROFILED_LAYER_COUNT; layer++) {
                if ((mask & (1 << layer)) == 0) {
                    layerGpuMs[layer] = smooth(layerGpuMs[layer], 0.0F);
                    continue;
                }

                timestamps.clear();
                int query = frame * QUERIES_PER_FRAME + layer * QUERIES_PER_LAYER;
                int result = vkGetQueryPoolResults(
                        Vulkan.getVkDevice(),
                        queryPool,
                        query,
                        2,
                        timestamps,
                        Long.BYTES,
                        VK_QUERY_RESULT_64_BIT
                );
                if (result != VK_SUCCESS) {
                    continue;
                }

                long start = timestamps.getLong(0);
                long end = timestamps.getLong(Long.BYTES);
                float milliseconds = end >= start
                        ? (end - start) * timestampPeriod * 0.000001F
                        : 0.0F;
                layerGpuMs[layer] = smooth(layerGpuMs[layer], milliseconds);
                total += milliseconds;
                readAny = true;
            }
        }

        if (readAny) {
            terrainGpuMs = smooth(terrainGpuMs, total);
            hasSample = true;
        } else if (mask == 0) {
            terrainGpuMs = smooth(terrainGpuMs, 0.0F);
        }
    }

    private static float smooth(float previous, float current) {
        return !hasSample ? current : previous + (current - previous) * SMOOTHING;
    }

    private static boolean isValid(int frame, int layerIndex) {
        return supported
                && queryPool != VK_NULL_HANDLE
                && frame >= 0
                && frame < frameCount
                && layerIndex >= 0;
    }

    private static int profiledLayerIndex(TerrainRenderType layer) {
        return switch (layer) {
            case SOLID -> 0;
            case CUTOUT_MIPPED -> 1;
            case CUTOUT -> 2;
            case TRANSLUCENT -> 3;
            default -> -1;
        };
    }
}
