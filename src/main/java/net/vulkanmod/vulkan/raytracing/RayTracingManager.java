package net.vulkanmod.vulkan.raytracing;

import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.RayTracingBuffer;
import net.vulkanmod.vulkan.queue.CommandPool;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Builds the first hardware ray tracing scene representation used by VulkanMod.
 *
 * <p>The current milestone includes opaque chunk geometry only. Builds are
 * deliberately synchronous so that lifecycle and synchronization are easy to
 * validate before the structures are consumed by a ray-query render pass.</p>
 */
public final class RayTracingManager {
    private static final int COMPRESSED_TERRAIN_STRIDE = 20;
    private static final float POSITION_SCALE = 1.0f / 2048.0f;
    private static final float POSITION_OFFSET = 4.0f;

    private static RayTracingManager INSTANCE;

    private final Map<RenderSection, PendingGeometry> pendingBuilds = new IdentityHashMap<>();
    private final Set<RenderSection> pendingRemovals =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<RenderSection, AccelerationStructure> bottomLevels = new IdentityHashMap<>();

    private AccelerationStructure topLevel;
    private boolean enabled;

    private RayTracingManager() {
        this.enabled = DeviceManager.isRayTracingEnabled()
                && Boolean.parseBoolean(System.getProperty("vulkanmod.rayTracing.buildStructures", "true"));
        if (this.enabled) {
            Initializer.LOGGER.info("RT acceleration-structure builder initialized (SOLID terrain prototype)");
        }
    }

    public static void createInstance() {
        if (INSTANCE != null) {
            INSTANCE.cleanUp();
        }
        INSTANCE = new RayTracingManager();
    }

    public static boolean isEnabled() {
        return INSTANCE != null && INSTANCE.enabled;
    }

    public static void queueTerrainSection(RenderSection section, List<ByteBuffer> compressedLayers, int stride) {
        if (!isEnabled()) {
            return;
        }
        if (compressedLayers == null || compressedLayers.isEmpty()) {
            removeSection(section);
            return;
        }
        if (stride != COMPRESSED_TERRAIN_STRIDE) {
            Initializer.LOGGER.warn("Skipping RT geometry with unsupported terrain vertex stride {}", stride);
            removeSection(section);
            return;
        }

        long geometrySignature = geometrySignature(compressedLayers, stride);
        PendingGeometry queued = INSTANCE.pendingBuilds.get(section);
        if (queued != null && queued.signature == geometrySignature) {
            return;
        }

        AccelerationStructure current = INSTANCE.bottomLevels.get(section);
        if (current != null
                && current.geometrySignature == geometrySignature
                && current.sectionX == section.xOffset()
                && current.sectionY == section.yOffset()
                && current.sectionZ == section.zOffset()) {
            if (queued != null) {
                INSTANCE.pendingBuilds.remove(section);
                queued.free();
            }
            INSTANCE.pendingRemovals.remove(section);
            return;
        }

        PendingGeometry geometry;
        try {
            geometry = decodeTerrainQuads(compressedLayers, stride, geometrySignature);
        } catch (RuntimeException exception) {
            Initializer.LOGGER.error("Failed to decode terrain section geometry for RT; removing its BLAS", exception);
            removeSection(section);
            return;
        }
        PendingGeometry old = INSTANCE.pendingBuilds.put(section, geometry);
        if (old != null) {
            old.free();
        }
        INSTANCE.pendingRemovals.remove(section);
    }

    public static void removeSection(RenderSection section) {
        if (INSTANCE == null || !INSTANCE.enabled) {
            return;
        }

        PendingGeometry pending = INSTANCE.pendingBuilds.remove(section);
        if (pending != null) {
            pending.free();
        }
        if (INSTANCE.bottomLevels.containsKey(section)) {
            INSTANCE.pendingRemovals.add(section);
        } else {
            INSTANCE.pendingRemovals.remove(section);
        }
    }

    public static void clearSections() {
        if (INSTANCE == null) {
            return;
        }

        INSTANCE.pendingBuilds.values().forEach(PendingGeometry::free);
        INSTANCE.pendingBuilds.clear();
        INSTANCE.pendingRemovals.addAll(INSTANCE.bottomLevels.keySet());
    }

    public static void processPendingBuilds() {
        if (isEnabled()) {
            INSTANCE.processPendingBuildsInternal();
        }
    }

    public static long getTopLevelHandle() {
        return INSTANCE == null || INSTANCE.topLevel == null ? VK_NULL_HANDLE : INSTANCE.topLevel.handle;
    }

    public static int getInstanceCount() {
        return INSTANCE == null ? 0 : INSTANCE.bottomLevels.size();
    }

    public static void destroyInstance() {
        if (INSTANCE != null) {
            INSTANCE.cleanUp();
            INSTANCE = null;
        }
    }

    private void processPendingBuildsInternal() {
        if (this.pendingBuilds.isEmpty() && this.pendingRemovals.isEmpty()) {
            return;
        }

        int removed = 0;
        int built = 0;
        List<RayTracingBuffer> temporaryBuffers = new ArrayList<>();
        List<PendingGeometry> consumedGeometry = new ArrayList<>();

        try {
            destroyTopLevel();

            for (RenderSection section : this.pendingRemovals) {
                AccelerationStructure old = this.bottomLevels.remove(section);
                if (old != null) {
                    old.destroy();
                    removed++;
                }
            }
            this.pendingRemovals.clear();

            if (this.pendingBuilds.isEmpty()) {
                if (!this.bottomLevels.isEmpty()) {
                    buildTopLevelOnly(temporaryBuffers);
                }
                Initializer.LOGGER.info(
                        "RT acceleration structures updated: BLAS built=0 removed={} totalBLAS={} TLAS instances={}",
                        removed, this.bottomLevels.size(), this.bottomLevels.size()
                );
                return;
            }

            Map<RenderSection, PendingGeometry> builds = new IdentityHashMap<>(this.pendingBuilds);
            this.pendingBuilds.clear();
            consumedGeometry.addAll(builds.values());

            CommandPool.CommandBuffer commandBuffer = DeviceManager.getGraphicsQueue().beginCommands();
            VkCommandBuffer vkCommandBuffer = commandBuffer.getHandle();
            recordHostWriteBarrier(vkCommandBuffer);

            for (Map.Entry<RenderSection, PendingGeometry> entry : builds.entrySet()) {
                RenderSection section = entry.getKey();
                PendingGeometry geometry = entry.getValue();

                AccelerationStructure old = this.bottomLevels.remove(section);
                if (old != null) {
                    old.destroy();
                }

                AccelerationStructure bottomLevel = recordBottomLevelBuild(
                        vkCommandBuffer, geometry, temporaryBuffers
                );
                bottomLevel.sectionX = section.xOffset();
                bottomLevel.sectionY = section.yOffset();
                bottomLevel.sectionZ = section.zOffset();
                bottomLevel.geometrySignature = geometry.signature;
                this.bottomLevels.put(section, bottomLevel);
                built++;
            }
            recordAccelerationStructureBarrier(vkCommandBuffer);
            if (!this.bottomLevels.isEmpty()) {
                this.topLevel = recordTopLevelBuild(vkCommandBuffer, temporaryBuffers);
            }

            submitAndWait(commandBuffer);

            Initializer.LOGGER.info(
                    "RT acceleration structures updated: BLAS built={} removed={} totalBLAS={} TLAS instances={}",
                    built, removed, this.bottomLevels.size(), this.bottomLevels.size()
            );
        } catch (Throwable throwable) {
            Initializer.LOGGER.error(
                    "RT acceleration-structure build failed; disabling the prototype and keeping raster rendering active",
                    throwable
            );
            this.enabled = false;
            destroyAllStructures();
        } finally {
            temporaryBuffers.forEach(RayTracingBuffer::freeBuffer);
            consumedGeometry.forEach(PendingGeometry::free);
        }
    }

    private void buildTopLevelOnly(List<RayTracingBuffer> temporaryBuffers) {
        CommandPool.CommandBuffer commandBuffer = DeviceManager.getGraphicsQueue().beginCommands();
        VkCommandBuffer vkCommandBuffer = commandBuffer.getHandle();
        recordHostWriteBarrier(vkCommandBuffer);
        this.topLevel = recordTopLevelBuild(vkCommandBuffer, temporaryBuffers);
        submitAndWait(commandBuffer);
    }

    private AccelerationStructure recordBottomLevelBuild(
            VkCommandBuffer commandBuffer,
            PendingGeometry geometryData,
            List<RayTracingBuffer> temporaryBuffers
    ) {
        RayTracingBuffer vertexBuffer = new RayTracingBuffer(
                geometryData.vertices.remaining(),
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                MemoryTypes.HOST_MEM
        );
        vertexBuffer.upload(geometryData.vertices);
        temporaryBuffers.add(vertexBuffer);

        RayTracingBuffer indexBuffer = new RayTracingBuffer(
                geometryData.indices.remaining(),
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                MemoryTypes.HOST_MEM
        );
        indexBuffer.upload(geometryData.indices);
        temporaryBuffers.add(indexBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometries =
                    VkAccelerationStructureGeometryKHR.calloc(1, stack);
            VkAccelerationStructureGeometryKHR geometry = geometries.get(0);
            geometry.sType$Default();
            geometry.geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR);
            geometry.flags(VK_GEOMETRY_OPAQUE_BIT_KHR);

            VkAccelerationStructureGeometryTrianglesDataKHR triangles = geometry.geometry().triangles();
            triangles.sType$Default();
            triangles.vertexFormat(VK_FORMAT_R32G32B32_SFLOAT);
            triangles.vertexData().deviceAddress(vertexBuffer.getDeviceAddress());
            triangles.vertexStride(Float.BYTES * 3L);
            triangles.maxVertex(geometryData.vertexCount - 1);
            triangles.indexType(VK_INDEX_TYPE_UINT32);
            triangles.indexData().deviceAddress(indexBuffer.getDeviceAddress());
            triangles.transformData().deviceAddress(NULL);

            VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfos =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            VkAccelerationStructureBuildGeometryInfoKHR buildInfo = buildInfos.get(0);
            buildInfo.sType$Default();
            buildInfo.type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
            buildInfo.flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR);
            buildInfo.mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR);
            buildInfo.pGeometries(geometries);

            VkAccelerationStructureBuildSizesInfoKHR sizeInfo =
                    VkAccelerationStructureBuildSizesInfoKHR.calloc(stack);
            sizeInfo.sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(
                    Vulkan.getVkDevice(),
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    buildInfo,
                    stack.ints(geometryData.primitiveCount),
                    sizeInfo
            );

            AccelerationStructure result = createAccelerationStructure(
                    VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
                    sizeInfo.accelerationStructureSize()
            );
            buildInfo.dstAccelerationStructure(result.handle);

            RayTracingBuffer scratch = createScratchBuffer(sizeInfo.buildScratchSize());
            temporaryBuffers.add(scratch);
            buildInfo.scratchData().deviceAddress(alignedScratchAddress(scratch));

            VkAccelerationStructureBuildRangeInfoKHR.Buffer range =
                    VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            range.get(0).primitiveCount(geometryData.primitiveCount);
            PointerBuffer ranges = stack.pointers(range.address());
            vkCmdBuildAccelerationStructuresKHR(commandBuffer, buildInfos, ranges);
            return result;
        }
    }

    private AccelerationStructure recordTopLevelBuild(
            VkCommandBuffer commandBuffer,
            List<RayTracingBuffer> temporaryBuffers
    ) {
        int instanceCount = this.bottomLevels.size();
        VkAccelerationStructureInstanceKHR.Buffer instances =
                VkAccelerationStructureInstanceKHR.calloc(instanceCount);

        int index = 0;
        for (AccelerationStructure bottomLevel : this.bottomLevels.values()) {
            VkAccelerationStructureInstanceKHR instance = instances.get(index);
            VkTransformMatrixKHR transform = instance.transform();
            transform.matrix(0, 1.0f);
            transform.matrix(1, 0.0f);
            transform.matrix(2, 0.0f);
            transform.matrix(3, bottomLevel.sectionX);
            transform.matrix(4, 0.0f);
            transform.matrix(5, 1.0f);
            transform.matrix(6, 0.0f);
            transform.matrix(7, bottomLevel.sectionY);
            transform.matrix(8, 0.0f);
            transform.matrix(9, 0.0f);
            transform.matrix(10, 1.0f);
            transform.matrix(11, bottomLevel.sectionZ);
            instance.instanceCustomIndex(index);
            instance.mask(0xFF);
            instance.instanceShaderBindingTableRecordOffset(0);
            instance.flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR);
            instance.accelerationStructureReference(bottomLevel.deviceAddress);
            index++;
        }

        int instanceBytes = Math.multiplyExact(instanceCount, VkAccelerationStructureInstanceKHR.SIZEOF);
        RayTracingBuffer instanceBuffer = new RayTracingBuffer(
                instanceBytes,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                MemoryTypes.HOST_MEM
        );
        ByteBuffer instanceData = MemoryUtil.memByteBuffer(instances.address(), instanceBytes);
        instanceBuffer.upload(instanceData);
        instances.free();
        temporaryBuffers.add(instanceBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometries =
                    VkAccelerationStructureGeometryKHR.calloc(1, stack);
            VkAccelerationStructureGeometryKHR geometry = geometries.get(0);
            geometry.sType$Default();
            geometry.geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);

            VkAccelerationStructureGeometryInstancesDataKHR instanceGeometry = geometry.geometry().instances();
            instanceGeometry.sType$Default();
            instanceGeometry.arrayOfPointers(false);
            instanceGeometry.data().deviceAddress(instanceBuffer.getDeviceAddress());

            VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfos =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            VkAccelerationStructureBuildGeometryInfoKHR buildInfo = buildInfos.get(0);
            buildInfo.sType$Default();
            buildInfo.type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
            buildInfo.flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR);
            buildInfo.mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR);
            buildInfo.pGeometries(geometries);

            VkAccelerationStructureBuildSizesInfoKHR sizeInfo =
                    VkAccelerationStructureBuildSizesInfoKHR.calloc(stack);
            sizeInfo.sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(
                    Vulkan.getVkDevice(),
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    buildInfo,
                    stack.ints(instanceCount),
                    sizeInfo
            );

            AccelerationStructure result = createAccelerationStructure(
                    VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR,
                    sizeInfo.accelerationStructureSize()
            );
            buildInfo.dstAccelerationStructure(result.handle);

            RayTracingBuffer scratch = createScratchBuffer(sizeInfo.buildScratchSize());
            temporaryBuffers.add(scratch);
            buildInfo.scratchData().deviceAddress(alignedScratchAddress(scratch));

            VkAccelerationStructureBuildRangeInfoKHR.Buffer range =
                    VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
            range.get(0).primitiveCount(instanceCount);
            PointerBuffer ranges = stack.pointers(range.address());
            recordHostWriteBarrier(commandBuffer);
            vkCmdBuildAccelerationStructuresKHR(commandBuffer, buildInfos, ranges);
            return result;
        }
    }

    private AccelerationStructure createAccelerationStructure(int type, long size) {
        RayTracingBuffer storage = new RayTracingBuffer(
                checkedBufferSize(size),
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR,
                MemoryTypes.GPU_MEM
        );

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureCreateInfoKHR createInfo =
                    VkAccelerationStructureCreateInfoKHR.calloc(stack);
            createInfo.sType$Default();
            createInfo.type(type);
            createInfo.buffer(storage.getId());
            createInfo.offset(0L);
            createInfo.size(size);

            LongBuffer handle = stack.mallocLong(1);
            Vulkan.checkResult(
                    vkCreateAccelerationStructureKHR(Vulkan.getVkDevice(), createInfo, null, handle),
                    "Failed to create acceleration structure"
            );

            VkAccelerationStructureDeviceAddressInfoKHR addressInfo =
                    VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack);
            addressInfo.sType$Default();
            addressInfo.accelerationStructure(handle.get(0));
            long deviceAddress = vkGetAccelerationStructureDeviceAddressKHR(Vulkan.getVkDevice(), addressInfo);
            return new AccelerationStructure(handle.get(0), deviceAddress, storage);
        } catch (Throwable throwable) {
            storage.freeBuffer();
            throw throwable;
        }
    }

    private RayTracingBuffer createScratchBuffer(long requiredSize) {
        int alignment = DeviceManager.getRayTracingCapabilities().minScratchOffsetAlignment();
        long allocationSize = Math.addExact(requiredSize, Math.max(0, alignment - 1));
        return new RayTracingBuffer(
                checkedBufferSize(allocationSize),
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                MemoryTypes.GPU_MEM
        );
    }

    private long alignedScratchAddress(RayTracingBuffer scratch) {
        long address = scratch.getDeviceAddress();
        int alignment = DeviceManager.getRayTracingCapabilities().minScratchOffsetAlignment();
        long remainder = Long.remainderUnsigned(address, alignment);
        return remainder == 0L ? address : address + alignment - remainder;
    }

    private static int checkedBufferSize(long size) {
        if (size <= 0L || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Unsupported RT buffer size: " + size);
        }
        return (int) size;
    }

    private static void recordHostWriteBarrier(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default();
            barrier.get(0).srcAccessMask(VK_ACCESS_HOST_WRITE_BIT);
            barrier.get(0).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            vkCmdPipelineBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_HOST_BIT,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    0,
                    barrier,
                    null,
                    null
            );
        }
    }

    private static void recordAccelerationStructureBarrier(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default();
            barrier.get(0).srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
            barrier.get(0).dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            vkCmdPipelineBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    0,
                    barrier,
                    null,
                    null
            );
        }
    }

    private static void submitAndWait(CommandPool.CommandBuffer commandBuffer) {
        DeviceManager.getGraphicsQueue().submitCommands(commandBuffer);
        Synchronization.INSTANCE.addCommandBuffer(commandBuffer);
        Synchronization.INSTANCE.waitFences();
    }

    private void destroyTopLevel() {
        if (this.topLevel != null) {
            this.topLevel.destroy();
            this.topLevel = null;
        }
    }

    private void destroyAllStructures() {
        destroyTopLevel();
        this.bottomLevels.values().forEach(AccelerationStructure::destroy);
        this.bottomLevels.clear();
        this.pendingBuilds.values().forEach(PendingGeometry::free);
        this.pendingBuilds.clear();
        this.pendingRemovals.clear();
    }

    private void cleanUp() {
        destroyAllStructures();
        this.enabled = false;
    }

    private static long geometrySignature(List<ByteBuffer> compressedLayers, int stride) {
        long hash = 0xcbf29ce484222325L;
        for (ByteBuffer compressedLayer : compressedLayers) {
            ByteBuffer source = compressedLayer.duplicate();
            int vertexCount = source.remaining() / stride;
            hash ^= vertexCount;
            hash *= 0x100000001b3L;
            int sourceStart = source.position();
            for (int vertex = 0; vertex < vertexCount; vertex++) {
                int positionOffset = sourceStart + vertex * stride;
                for (int componentByte = 0; componentByte < 6; componentByte++) {
                    hash ^= source.get(positionOffset + componentByte) & 0xFFL;
                    hash *= 0x100000001b3L;
                }
            }
        }
        return hash;
    }

    private static PendingGeometry decodeTerrainQuads(
            List<ByteBuffer> compressedLayers,
            int stride,
            long geometrySignature
    ) {
        int vertexCount = 0;
        for (ByteBuffer compressedLayer : compressedLayers) {
            ByteBuffer source = compressedLayer.duplicate();
            int byteCount = source.remaining();
            if (byteCount == 0 || byteCount % stride != 0) {
                throw new IllegalArgumentException("Invalid compressed terrain vertex buffer size: " + byteCount);
            }
            int layerVertexCount = byteCount / stride;
            if ((layerVertexCount & 3) != 0) {
                throw new IllegalArgumentException(
                        "Terrain layer vertex count is not divisible by four: " + layerVertexCount
                );
            }
            vertexCount = Math.addExact(vertexCount, layerVertexCount);
        }

        int primitiveCount = vertexCount / 2;
        ByteBuffer vertices = MemoryUtil.memAlloc(Math.multiplyExact(vertexCount, Float.BYTES * 3))
                .order(ByteOrder.nativeOrder());
        ByteBuffer indices = MemoryUtil.memAlloc(Math.multiplyExact(primitiveCount * 3, Integer.BYTES))
                .order(ByteOrder.nativeOrder());

        int vertexBase = 0;
        for (ByteBuffer compressedLayer : compressedLayers) {
            ByteBuffer source = compressedLayer.duplicate().order(ByteOrder.nativeOrder());
            int layerVertexCount = source.remaining() / stride;
            int sourceStart = source.position();
            for (int vertex = 0; vertex < layerVertexCount; vertex++) {
                int offset = sourceStart + vertex * stride;
                vertices.putFloat(source.getShort(offset) * POSITION_SCALE + POSITION_OFFSET);
                vertices.putFloat(source.getShort(offset + 2) * POSITION_SCALE + POSITION_OFFSET);
                vertices.putFloat(source.getShort(offset + 4) * POSITION_SCALE + POSITION_OFFSET);
            }

            for (int vertex = 0; vertex < layerVertexCount; vertex += 4) {
                int base = vertexBase + vertex;
                indices.putInt(base);
                indices.putInt(base + 1);
                indices.putInt(base + 2);
                indices.putInt(base);
                indices.putInt(base + 2);
                indices.putInt(base + 3);
            }
            vertexBase += layerVertexCount;
        }

        vertices.flip();
        indices.flip();
        return new PendingGeometry(vertices, indices, vertexCount, primitiveCount, geometrySignature);
    }

    private record PendingGeometry(
            ByteBuffer vertices,
              ByteBuffer indices,
              int vertexCount,
              int primitiveCount,
              long signature
    ) {
        void free() {
            MemoryUtil.memFree(this.vertices);
            MemoryUtil.memFree(this.indices);
        }
    }

    private static final class AccelerationStructure {
        private final long handle;
        private final long deviceAddress;
        private final RayTracingBuffer storage;
          private int sectionX;
          private int sectionY;
          private int sectionZ;
          private long geometrySignature;

        private AccelerationStructure(long handle, long deviceAddress, RayTracingBuffer storage) {
            this.handle = handle;
            this.deviceAddress = deviceAddress;
            this.storage = storage;
        }

        private void destroy() {
            vkDestroyAccelerationStructureKHR(Vulkan.getVkDevice(), this.handle, null);
            this.storage.freeBuffer();
        }
    }
}
