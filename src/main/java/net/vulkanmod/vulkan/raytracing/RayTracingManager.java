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
import java.util.TreeMap;

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
    private static final int HIT_ATTRIBUTE_ENTRY_BYTES = Integer.BYTES;
    private static final int HIT_ATTRIBUTE_STRIDE = 5;
    private static final int DEFAULT_UV_BUFFER_MIB = 64;

    private static RayTracingManager INSTANCE;
    private static boolean loggedGeometrySample;
    private static boolean loggedBottomLevelSample;
    private static boolean loggedTopLevelSample;

    private final Map<RenderSection, PendingGeometry> pendingBuilds = new IdentityHashMap<>();
    private final Set<RenderSection> pendingRemovals =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<RenderSection, AccelerationStructure> bottomLevels = new IdentityHashMap<>();

    private AccelerationStructure topLevel;
    private RayTracingBuffer uvBuffer;
    private int uvHighWaterMark;
    private final TreeMap<Integer, Integer> freeUvRanges = new TreeMap<>();
    private long debugQueryPool;
    private boolean enabled;

    private RayTracingManager() {
        this.enabled = DeviceManager.isRayTracingEnabled()
                && Boolean.parseBoolean(System.getProperty("vulkanmod.rayTracing.buildStructures", "true"));
        if (this.enabled) {
            int configuredMib = Integer.getInteger(
                    "vulkanmod.rayTracing.uvBufferMiB",
                    DEFAULT_UV_BUFFER_MIB
            );
            int clampedMib = Math.max(4, Math.min(DEFAULT_UV_BUFFER_MIB, configuredMib));
            int uvBufferBytes = clampedMib * 1024 * 1024;
            this.uvBuffer = new RayTracingBuffer(
                    uvBufferBytes,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    MemoryTypes.HOST_MEM
            );
            Initializer.LOGGER.info(
                    "RT acceleration-structure builder initialized with {} MiB hit-attribute storage",
                    uvBufferBytes / (1024 * 1024)
            );
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

    public static boolean shouldEnableRayQueryPass() {
        return DeviceManager.isRayQueryEnabled()
                && Boolean.parseBoolean(System.getProperty("vulkanmod.rayTracing.buildStructures", "true"))
                && Boolean.parseBoolean(System.getProperty("vulkanmod.rayTracing.shadows", "true"));
    }

    public static void queueTerrainSection(
            RenderSection section,
            List<ByteBuffer> compressedLayers,
            int opaqueVertexCount,
            int stride
    ) {
        if (!isEnabled()) {
            return;
        }
        if (isSinglePlaneDebugEnabled()
                && (INSTANCE.topLevel != null
                || !INSTANCE.bottomLevels.isEmpty()
                || !INSTANCE.pendingBuilds.isEmpty())) {
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

        long geometrySignature = geometrySignature(compressedLayers, stride) ^ opaqueVertexCount;
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
            geometry = decodeTerrainQuads(
                    compressedLayers,
                    stride,
                    geometrySignature,
                    opaqueVertexCount,
                    section.xOffset(),
                    section.yOffset(),
                    section.zOffset()
            );
            if (!loggedGeometrySample && geometry.vertexCount > 0) {
                loggedGeometrySample = true;
                Initializer.LOGGER.info(
                        "RT geometry sample: section=({}, {}, {}) firstWorldVertex=({}, {}, {}) vertices={} primitives={}",
                        section.xOffset(), section.yOffset(), section.zOffset(),
                        geometry.vertices.getFloat(0),
                        geometry.vertices.getFloat(Float.BYTES),
                        geometry.vertices.getFloat(Float.BYTES * 2),
                        geometry.vertexCount,
                        geometry.primitiveCount
                );
            }
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
        if (isSinglePlaneDebugEnabled() && INSTANCE.topLevel != null) {
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

    public static long getTopLevelDeviceAddress() {
        return INSTANCE == null || INSTANCE.topLevel == null ? 0L : INSTANCE.topLevel.deviceAddress;
    }

    public static long getUvBufferHandle() {
        return INSTANCE == null || INSTANCE.uvBuffer == null
                ? VK_NULL_HANDLE
                : INSTANCE.uvBuffer.getId();
    }

    public static long getUvBufferSize() {
        return INSTANCE == null || INSTANCE.uvBuffer == null
                ? 0L
                : INSTANCE.uvBuffer.getBufferSize();
    }

    public static int getInstanceCount() {
        return INSTANCE == null ? 0 : INSTANCE.bottomLevels.size();
    }

    public static void destroyInstance() {
        if (INSTANCE != null) {
            INSTANCE.cleanUp();
            INSTANCE = null;
        }
        RtDynamicLights.cleanUp();
    }

    private void processPendingBuildsInternal() {
        if (this.pendingBuilds.isEmpty() && this.pendingRemovals.isEmpty()) {
            return;
        }

        if (shouldEnableRayQueryPass() && this.topLevel != null) {
            Vulkan.waitIdle();
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

            int buildBudget = Math.max(1, Integer.getInteger("vulkanmod.rayTracing.maxBuildsPerFrame", 32));
            Map<RenderSection, PendingGeometry> builds = new IdentityHashMap<>();
            var pendingIterator = this.pendingBuilds.entrySet().iterator();
            while (pendingIterator.hasNext() && builds.size() < buildBudget) {
                Map.Entry<RenderSection, PendingGeometry> entry = pendingIterator.next();
                builds.put(entry.getKey(), entry.getValue());
                pendingIterator.remove();
            }
            consumedGeometry.addAll(builds.values());

            CommandPool.CommandBuffer commandBuffer = DeviceManager.getGraphicsQueue().beginCommands();
            VkCommandBuffer vkCommandBuffer = commandBuffer.getHandle();
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
            recordUvHostWriteBarrier(vkCommandBuffer);
            recordAccelerationStructureBarrier(vkCommandBuffer);
            if (!this.bottomLevels.isEmpty()) {
                this.topLevel = recordTopLevelBuild(vkCommandBuffer, temporaryBuffers);
                recordTraceReadBarrier(vkCommandBuffer);
                if (isSinglePlaneDebugEnabled()) {
                    recordDebugSizeQueries(vkCommandBuffer);
                }
            }

            submitAndWait(commandBuffer);
            if (isSinglePlaneDebugEnabled()) {
                logDebugSizeQueries();
            }

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
        this.topLevel = recordTopLevelBuild(vkCommandBuffer, temporaryBuffers);
        recordTraceReadBarrier(vkCommandBuffer);
        submitAndWait(commandBuffer);
    }

    private AccelerationStructure recordBottomLevelBuild(
            VkCommandBuffer commandBuffer,
            PendingGeometry geometryData,
            List<RayTracingBuffer> temporaryBuffers
    ) {
        RayTracingBuffer vertexBuffer = uploadBuildInput(
                commandBuffer,
                geometryData.vertices,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                temporaryBuffers
        );

        RayTracingBuffer indexBuffer = uploadBuildInput(
                commandBuffer,
                geometryData.indices,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                temporaryBuffers
        );

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometries =
                    VkAccelerationStructureGeometryKHR.calloc(1, stack);
            VkAccelerationStructureGeometryKHR geometry = geometries.get(0);
            geometry.sType$Default();
            geometry.geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR);
            // Candidate intersections are confirmed in the fragment shader.
            // SOLID primitives are always accepted; CUTOUT_MIPPED primitives use
            // a stable coverage mask until per-triangle atlas UVs are available.
            geometry.flags(0);

            VkAccelerationStructureGeometryTrianglesDataKHR triangles = geometry.geometry().triangles();
            triangles.sType$Default();
            triangles.vertexFormat(VK_FORMAT_R32G32B32_SFLOAT);
            triangles.vertexData().deviceAddress(vertexBuffer.getDeviceAddress());
            triangles.vertexStride(Float.BYTES * 3L);
            triangles.maxVertex(geometryData.vertexCount - 1);
            if (isSinglePlaneDebugEnabled()) {
                triangles.indexType(VK_INDEX_TYPE_NONE_KHR);
                triangles.indexData().deviceAddress(NULL);
            } else {
                triangles.indexType(VK_INDEX_TYPE_UINT32);
                triangles.indexData().deviceAddress(indexBuffer.getDeviceAddress());
            }
            triangles.transformData().deviceAddress(NULL);

            VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfos =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            VkAccelerationStructureBuildGeometryInfoKHR buildInfo = buildInfos.get(0);
            buildInfo.sType$Default();
            buildInfo.type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
            buildInfo.flags(
                    VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                            | (isSinglePlaneDebugEnabled()
                            ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR
                            : 0)
            );
            buildInfo.mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR);
            buildInfo.geometryCount(1);
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
            int uvAllocationOffset = allocateUvRange(geometryData.uvData.remaining());
            try {
                this.uvBuffer.upload(geometryData.uvData, uvAllocationOffset);
                result.uvAllocationOffset = uvAllocationOffset;
                result.uvAllocationSize = geometryData.uvData.remaining();
                result.uvBaseEntry = uvAllocationOffset / HIT_ATTRIBUTE_ENTRY_BYTES;
            } catch (Throwable throwable) {
                freeUvRange(uvAllocationOffset, geometryData.uvData.remaining());
                result.destroy();
                throw throwable;
            }
            if (!loggedBottomLevelSample) {
                loggedBottomLevelSample = true;
                Initializer.LOGGER.info(
                        "RT BLAS sample: handle={} address={} vertexAddress={} indexAddress={} scratchAddress={}",
                        result.handle,
                        result.deviceAddress,
                        vertexBuffer.getDeviceAddress(),
                        indexBuffer.getDeviceAddress(),
                        buildInfo.scratchData().deviceAddress()
                );
            }
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
            transform.matrix(3, 0.0f);
            transform.matrix(4, 0.0f);
            transform.matrix(5, 1.0f);
            transform.matrix(6, 0.0f);
            transform.matrix(7, 0.0f);
            transform.matrix(8, 0.0f);
            transform.matrix(9, 0.0f);
            transform.matrix(10, 1.0f);
            transform.matrix(11, 0.0f);
            instance.instanceCustomIndex(bottomLevel.uvBaseEntry);
            instance.mask(0xFF);
            instance.instanceShaderBindingTableRecordOffset(0);
            instance.flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR);
            instance.accelerationStructureReference(bottomLevel.deviceAddress);
            index++;
        }

        int instanceBytes = Math.multiplyExact(instanceCount, VkAccelerationStructureInstanceKHR.SIZEOF);
        ByteBuffer instanceData = MemoryUtil.memByteBuffer(instances.address(), instanceBytes);
        RayTracingBuffer instanceBuffer = uploadBuildInput(
                commandBuffer,
                instanceData,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                temporaryBuffers
        );
        instances.free();

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
            buildInfo.flags(
                    VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                            | (isSinglePlaneDebugEnabled()
                            ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR
                            : 0)
            );
            buildInfo.mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR);
            buildInfo.geometryCount(1);
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
            vkCmdBuildAccelerationStructuresKHR(commandBuffer, buildInfos, ranges);
            if (!loggedTopLevelSample) {
                loggedTopLevelSample = true;
                Initializer.LOGGER.info(
                        "RT TLAS sample: handle={} address={} instanceAddress={} instanceAlignment={} scratchAddress={}",
                        result.handle,
                        result.deviceAddress,
                        instanceBuffer.getDeviceAddress(),
                        Long.remainderUnsigned(instanceBuffer.getDeviceAddress(), 16L),
                        buildInfo.scratchData().deviceAddress()
                );
            }
            return result;
        }
    }

    private RayTracingBuffer uploadBuildInput(
            VkCommandBuffer commandBuffer,
            ByteBuffer source,
            int usage,
            List<RayTracingBuffer> temporaryBuffers
    ) {
        int size = source.remaining();
        RayTracingBuffer staging = new RayTracingBuffer(size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, MemoryTypes.HOST_MEM);
        staging.upload(source);
        temporaryBuffers.add(staging);

        RayTracingBuffer deviceLocal = new RayTracingBuffer(
                size,
                usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                MemoryTypes.GPU_MEM
        );
        temporaryBuffers.add(deviceLocal);

        recordHostWriteBarrier(commandBuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack);
            copy.get(0).srcOffset(0L);
            copy.get(0).dstOffset(0L);
            copy.get(0).size(size);
            vkCmdCopyBuffer(commandBuffer, staging.getId(), deviceLocal.getId(), copy);
        }
        recordTransferWriteBarrier(commandBuffer);
        return deviceLocal;
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

    private int allocateUvRange(int requestedBytes) {
        int size = (requestedBytes + HIT_ATTRIBUTE_ENTRY_BYTES - 1) & -HIT_ATTRIBUTE_ENTRY_BYTES;
        for (var iterator = this.freeUvRanges.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<Integer, Integer> freeRange = iterator.next();
            if (freeRange.getValue() < size) {
                continue;
            }

            int offset = freeRange.getKey();
            int remainder = freeRange.getValue() - size;
            iterator.remove();
            if (remainder > 0) {
                this.freeUvRanges.put(offset + size, remainder);
            }
            return offset;
        }

        if (this.uvBuffer == null || this.uvHighWaterMark > this.uvBuffer.getBufferSize() - size) {
            throw new IllegalStateException(
                    "RT hit-attribute storage exhausted: requested=" + size
                            + " used=" + this.uvHighWaterMark
                            + " capacity=" + (this.uvBuffer == null ? 0 : this.uvBuffer.getBufferSize())
            );
        }

        int offset = this.uvHighWaterMark;
        this.uvHighWaterMark += size;
        return offset;
    }

    private void freeUvRange(int offset, int size) {
        if (offset < 0 || size <= 0) {
            return;
        }

        int mergedOffset = offset;
        int mergedSize = size;
        Map.Entry<Integer, Integer> lower = this.freeUvRanges.lowerEntry(offset);
        if (lower != null && lower.getKey() + lower.getValue() == offset) {
            mergedOffset = lower.getKey();
            mergedSize += lower.getValue();
            this.freeUvRanges.remove(lower.getKey());
        }

        Map.Entry<Integer, Integer> higher = this.freeUvRanges.ceilingEntry(mergedOffset);
        if (higher != null && mergedOffset + mergedSize == higher.getKey()) {
            mergedSize += higher.getValue();
            this.freeUvRanges.remove(higher.getKey());
        }

        if (mergedOffset + mergedSize == this.uvHighWaterMark) {
            this.uvHighWaterMark = mergedOffset;
            while (true) {
                Map.Entry<Integer, Integer> tail = this.freeUvRanges.lowerEntry(this.uvHighWaterMark);
                if (tail == null || tail.getKey() + tail.getValue() != this.uvHighWaterMark) {
                    break;
                }
                this.uvHighWaterMark = tail.getKey();
                this.freeUvRanges.remove(tail.getKey());
            }
        } else {
            this.freeUvRanges.put(mergedOffset, mergedSize);
        }
    }

    private static void recordHostWriteBarrier(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default();
            barrier.get(0).srcAccessMask(VK_ACCESS_HOST_WRITE_BIT);
            barrier.get(0).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            vkCmdPipelineBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_HOST_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0,
                    barrier,
                    null,
                    null
            );
        }
    }

    private static void recordUvHostWriteBarrier(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default();
            barrier.get(0).srcAccessMask(VK_ACCESS_HOST_WRITE_BIT);
            barrier.get(0).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            vkCmdPipelineBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_HOST_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0,
                    barrier,
                    null,
                    null
            );
        }
    }

    private static void recordTransferWriteBarrier(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default();
            barrier.get(0).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            barrier.get(0).dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            vkCmdPipelineBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
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

    private static void recordTraceReadBarrier(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default();
            barrier.get(0).srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
            barrier.get(0).dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            vkCmdPipelineBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0,
                    barrier,
                    null,
                    null
            );
        }
    }

    private void recordDebugSizeQueries(VkCommandBuffer commandBuffer) {
        if (this.debugQueryPool == VK_NULL_HANDLE) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkQueryPoolCreateInfo createInfo = VkQueryPoolCreateInfo.calloc(stack);
                createInfo.sType$Default();
                createInfo.queryType(VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR);
                createInfo.queryCount(2);
                LongBuffer handle = stack.mallocLong(1);
                Vulkan.checkResult(
                        vkCreateQueryPool(Vulkan.getVkDevice(), createInfo, null, handle),
                        "Failed to create RT diagnostic query pool"
                );
                this.debugQueryPool = handle.get(0);
            }
        }

        recordAccelerationStructureBarrier(commandBuffer);
        vkCmdResetQueryPool(commandBuffer, this.debugQueryPool, 0, 2);
        long bottomLevelHandle = this.bottomLevels.values().iterator().next().handle;
        vkCmdWriteAccelerationStructuresPropertiesKHR(
                commandBuffer,
                new long[]{bottomLevelHandle, this.topLevel.handle},
                VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR,
                this.debugQueryPool,
                0
        );
    }

    private void logDebugSizeQueries() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer results = stack.mallocLong(2);
            int result = vkGetQueryPoolResults(
                    Vulkan.getVkDevice(),
                    this.debugQueryPool,
                    0,
                    2,
                    results,
                    Long.BYTES,
                    VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT
            );
            Vulkan.checkResult(result, "Failed to read RT diagnostic size queries");
            Initializer.LOGGER.info(
                    "RT GPU build verification: BLAS compactedSize={} TLAS compactedSize={}",
                    results.get(0),
                    results.get(1)
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
        if (this.topLevel != null || !this.bottomLevels.isEmpty()) {
            Vulkan.waitIdle();
        }
        destroyAllStructures();
        if (this.debugQueryPool != VK_NULL_HANDLE) {
            vkDestroyQueryPool(Vulkan.getVkDevice(), this.debugQueryPool, null);
            this.debugQueryPool = VK_NULL_HANDLE;
        }
        if (this.uvBuffer != null) {
            this.uvBuffer.freeBuffer();
            this.uvBuffer = null;
        }
        this.freeUvRanges.clear();
        this.uvHighWaterMark = 0;
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
                for (int attributeByte = 6; attributeByte < 8; attributeByte++) {
                    hash ^= source.get(positionOffset + attributeByte) & 0xFFL;
                    hash *= 0x100000001b3L;
                }
                for (int attributeByte = 12; attributeByte < 20; attributeByte++) {
                    hash ^= source.get(positionOffset + attributeByte) & 0xFFL;
                    hash *= 0x100000001b3L;
                }
            }
        }
        return hash;
    }

    private static PendingGeometry decodeTerrainQuads(
            List<ByteBuffer> compressedLayers,
            int stride,
            long geometrySignature,
            int opaqueVertexCount,
            int sectionX,
            int sectionY,
            int sectionZ
    ) {
        if (isSinglePlaneDebugEnabled()) {
            return createDebugPlane(geometrySignature);
        }

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
        if (opaqueVertexCount < 0 || opaqueVertexCount > vertexCount || (opaqueVertexCount & 3) != 0) {
            throw new IllegalArgumentException("Invalid opaque RT vertex count: " + opaqueVertexCount);
        }
        ByteBuffer vertices = MemoryUtil.memAlloc(Math.multiplyExact(vertexCount, Float.BYTES * 3))
                .order(ByteOrder.nativeOrder());
        ByteBuffer indices = MemoryUtil.memAlloc(Math.multiplyExact(primitiveCount * 3, Integer.BYTES))
                .order(ByteOrder.nativeOrder());
        ByteBuffer uvData = MemoryUtil.memAlloc(
                Math.addExact(
                        Integer.BYTES,
                        Math.multiplyExact(primitiveCount * HIT_ATTRIBUTE_STRIDE, Integer.BYTES)
                )
        ).order(ByteOrder.nativeOrder());
        uvData.putInt(opaqueVertexCount / 2);

        int vertexBase = 0;
        for (ByteBuffer compressedLayer : compressedLayers) {
            ByteBuffer source = compressedLayer.duplicate().order(ByteOrder.nativeOrder());
            int layerVertexCount = source.remaining() / stride;
            int sourceStart = source.position();
            for (int vertex = 0; vertex < layerVertexCount; vertex++) {
                int offset = sourceStart + vertex * stride;
                vertices.putFloat(source.getShort(offset) * POSITION_SCALE + POSITION_OFFSET + sectionX);
                vertices.putFloat(source.getShort(offset + 2) * POSITION_SCALE + POSITION_OFFSET + sectionY);
                vertices.putFloat(source.getShort(offset + 4) * POSITION_SCALE + POSITION_OFFSET + sectionZ);
            }

            for (int vertex = 0; vertex < layerVertexCount; vertex += 4) {
                int base = vertexBase + vertex;
                indices.putInt(base);
                indices.putInt(base + 1);
                indices.putInt(base + 2);
                indices.putInt(base);
                indices.putInt(base + 2);
                indices.putInt(base + 3);

                int uv0 = packedUv(source, sourceStart + vertex * stride);
                int uv1 = packedUv(source, sourceStart + (vertex + 1) * stride);
                int uv2 = packedUv(source, sourceStart + (vertex + 2) * stride);
                int uv3 = packedUv(source, sourceStart + (vertex + 3) * stride);
                int vertex0Offset = sourceStart + vertex * stride;
                int vertex1Offset = sourceStart + (vertex + 1) * stride;
                int vertex2Offset = sourceStart + (vertex + 2) * stride;
                int vertex3Offset = sourceStart + (vertex + 3) * stride;
                int packedNormalMaterial = source.getInt(vertex0Offset + 16);
                int light0 = packedHitLight(source, vertex0Offset);
                int light1 = packedHitLight(source, vertex1Offset);
                int light2 = packedHitLight(source, vertex2Offset);
                int light3 = packedHitLight(source, vertex3Offset);
                uvData.putInt(uv0).putInt(uv1).putInt(uv2)
                        .putInt(packedNormalMaterial)
                        .putInt(packTriangleLights(light0, light1, light2));
                uvData.putInt(uv0).putInt(uv2).putInt(uv3)
                        .putInt(packedNormalMaterial)
                        .putInt(packTriangleLights(light0, light2, light3));
            }
            vertexBase += layerVertexCount;
        }

        vertices.flip();
        indices.flip();
        uvData.flip();
        return new PendingGeometry(
                vertices,
                indices,
                uvData,
                vertexCount,
                primitiveCount,
                opaqueVertexCount / 2,
                geometrySignature
        );
    }

    private static int packedUv(ByteBuffer source, int vertexOffset) {
        int u = Short.toUnsignedInt(source.getShort(vertexOffset + 12));
        int v = Short.toUnsignedInt(source.getShort(vertexOffset + 14));
        return u | (v << 16);
    }

    private static int packedHitLight(ByteBuffer source, int vertexOffset) {
        int packedLight = Short.toUnsignedInt(source.getShort(vertexOffset + 6));
        int blockLight = (packedLight >>> 4) & 0xF;
        int skyLight = (packedLight >>> 12) & 0xF;
        return blockLight | (skyLight << 4);
    }

    private static int packTriangleLights(int light0, int light1, int light2) {
        return light0 | (light1 << 8) | (light2 << 16);
    }

    private static boolean isSinglePlaneDebugEnabled() {
        return Boolean.parseBoolean(System.getProperty("vulkanmod.rayTracing.debugPlane", "false"));
    }

    private static PendingGeometry createDebugPlane(long geometrySignature) {
        ByteBuffer vertices = MemoryUtil.memAlloc(3 * Float.BYTES * 3).order(ByteOrder.nativeOrder());
        vertices.putFloat(0.0f).putFloat(0.0f).putFloat(0.0f);
        vertices.putFloat(16.0f).putFloat(0.0f).putFloat(0.0f);
        vertices.putFloat(0.0f).putFloat(0.0f).putFloat(16.0f);
        vertices.flip();

        ByteBuffer indices = MemoryUtil.memAlloc(Integer.BYTES).order(ByteOrder.nativeOrder());
        indices.putInt(0);
        indices.flip();
        ByteBuffer uvData = MemoryUtil.memAlloc(
                Integer.BYTES + HIT_ATTRIBUTE_STRIDE * Integer.BYTES
        ).order(ByteOrder.nativeOrder());
        uvData.putInt(1);
        uvData.putInt(0).putInt(0).putInt(0);
        uvData.putInt(0x00007F00);
        uvData.putInt(0x00FFFFFF);
        uvData.flip();
        return new PendingGeometry(vertices, indices, uvData, 3, 1, 1, geometrySignature);
    }

    private record PendingGeometry(
            ByteBuffer vertices,
              ByteBuffer indices,
              ByteBuffer uvData,
              int vertexCount,
              int primitiveCount,
              int opaquePrimitiveCount,
              long signature
    ) {
        void free() {
            MemoryUtil.memFree(this.vertices);
            MemoryUtil.memFree(this.indices);
            MemoryUtil.memFree(this.uvData);
        }
    }

    private final class AccelerationStructure {
        private final long handle;
        private final long deviceAddress;
        private final RayTracingBuffer storage;
          private int sectionX;
          private int sectionY;
          private int sectionZ;
          private long geometrySignature;
          private int uvBaseEntry;
          private int uvAllocationOffset = -1;
          private int uvAllocationSize;

        private AccelerationStructure(long handle, long deviceAddress, RayTracingBuffer storage) {
            this.handle = handle;
            this.deviceAddress = deviceAddress;
            this.storage = storage;
        }

        private void destroy() {
            vkDestroyAccelerationStructureKHR(Vulkan.getVkDevice(), this.handle, null);
            this.storage.freeBuffer();
            freeUvRange(this.uvAllocationOffset, this.uvAllocationSize);
            this.uvAllocationOffset = -1;
            this.uvAllocationSize = 0;
        }
    }
}
