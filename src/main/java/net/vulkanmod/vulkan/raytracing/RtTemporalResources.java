package net.vulkanmod.vulkan.raytracing;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Ping-pong history images for stochastic RT results. Until depth/motion-vector
 * reprojection is available, history is accepted only while the camera is still.
 */
public final class RtTemporalResources {
    public static final int CURRENT_IMAGE_SLOT = 10;
    public static final int PREVIOUS_IMAGE_SLOT = 11;
    public static final int INDIRECT_CURRENT_IMAGE_SLOT = 12;
    public static final int INDIRECT_PREVIOUS_IMAGE_SLOT = 13;
    public static final int DEPTH_OWNER_IMAGE_SLOT = 9;

    private static final VulkanImage[] HISTORY_IMAGES = new VulkanImage[2];
    private static final VulkanImage[] INDIRECT_HISTORY_IMAGES = new VulkanImage[2];
    private static final MappedBuffer CURRENT_MVP = new MappedBuffer(16 * Float.BYTES);
    private static final MappedBuffer PREVIOUS_MVP = new MappedBuffer(16 * Float.BYTES);
    private static final MappedBuffer PREVIOUS_CAMERA_POSITION = new MappedBuffer(3 * Float.BYTES);
    private static VulkanImage depthOwnerImage;

    private static int width;
    private static int height;
    private static int currentImageIndex;
    private static int frameIndex;
    private static boolean historyValid;
    private static boolean hasHistory;
    private static boolean cameraStationary;
    private static boolean hasCurrentTransform;
    private static int preparedFrameIndex = -1;
    private static int stableFrames;
    private static float currentCameraX;
    private static float currentCameraY;
    private static float currentCameraZ;
    private static Vec3 lastCameraPosition;
    private static float lastYaw;
    private static float lastPitch;
    private static ClientLevel lastLevel;

    private RtTemporalResources() {
    }

    public static void beginFrame(VkCommandBuffer commandBuffer, int requestedWidth, int requestedHeight) {
        ensureImages(commandBuffer, Math.max(1, requestedWidth), Math.max(1, requestedHeight));
        currentImageIndex = frameIndex & 1;
        frameIndex++;

        VTextureSelector.bindTexture(CURRENT_IMAGE_SLOT, getCurrentImage());
        VTextureSelector.bindTexture(PREVIOUS_IMAGE_SLOT, getPreviousImage());
        VTextureSelector.bindTexture(INDIRECT_CURRENT_IMAGE_SLOT, getCurrentIndirectImage());
        VTextureSelector.bindTexture(INDIRECT_PREVIOUS_IMAGE_SLOT, getPreviousIndirectImage());
        VTextureSelector.bindTexture(DEPTH_OWNER_IMAGE_SLOT, depthOwnerImage);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            depthOwnerImage.transitionImageLayout(
                    stack,
                    commandBuffer,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
            );
            VkClearColorValue clearDepth = VkClearColorValue.calloc(stack);
            clearDepth.uint32(0, 0xFFFFFFFF);
            clearDepth.uint32(1, 0xFFFFFFFF);
            clearDepth.uint32(2, 0xFFFFFFFF);
            clearDepth.uint32(3, 0xFFFFFFFF);
            VkImageSubresourceRange.Buffer clearRange = VkImageSubresourceRange.calloc(1, stack);
            clearRange.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            clearRange.baseMipLevel(0);
            clearRange.levelCount(1);
            clearRange.baseArrayLayer(0);
            clearRange.layerCount(1);
            vkCmdClearColorImage(
                    commandBuffer,
                    depthOwnerImage.getId(),
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    clearDepth,
                    clearRange
            );
            depthOwnerImage.transitionImageLayout(
                    stack,
                    commandBuffer,
                    VK_IMAGE_LAYOUT_GENERAL
            );

            VkMemoryBarrier.Buffer memoryBarrier = VkMemoryBarrier.calloc(1, stack);
            memoryBarrier.sType$Default();
            memoryBarrier.srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT);
            memoryBarrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            vkCmdPipelineBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_DEPENDENCY_BY_REGION_BIT,
                    memoryBarrier,
                    null,
                    null
            );
        }
    }

    public static void updateCamera(ClientLevel level, Camera camera) {
        if (level == null || camera == null) {
            historyValid = false;
            hasHistory = false;
            stableFrames = 0;
            lastLevel = null;
            lastCameraPosition = null;
            return;
        }

        Vec3 position = camera.getPosition();
        float yaw = camera.getYRot();
        float pitch = camera.getXRot();
        boolean sameCamera = lastLevel == level
                && lastCameraPosition != null
                && position.distanceToSqr(lastCameraPosition) < 1.0E-8
                && Math.abs(yaw - lastYaw) < 1.0E-4F
                && Math.abs(pitch - lastPitch) < 1.0E-4F;

        historyValid = hasHistory && lastLevel == level;
        cameraStationary = sameCamera;
        stableFrames = sameCamera ? Math.min(stableFrames + 1, 4096) : 1;
        hasHistory = true;
        lastLevel = level;
        lastCameraPosition = position;
        lastYaw = yaw;
        lastPitch = pitch;
    }

    public static VulkanImage getCurrentImage() {
        return HISTORY_IMAGES[currentImageIndex];
    }

    public static VulkanImage getPreviousImage() {
        return HISTORY_IMAGES[currentImageIndex ^ 1];
    }

    public static VulkanImage getCurrentIndirectImage() {
        return INDIRECT_HISTORY_IMAGES[currentImageIndex];
    }

    public static VulkanImage getPreviousIndirectImage() {
        return INDIRECT_HISTORY_IMAGES[currentImageIndex ^ 1];
    }

    public static int getHistoryValid() {
        return historyValid ? 1 : 0;
    }

    public static int getFrameIndex() {
        return frameIndex;
    }

    public static int getStableFrames() {
        return stableFrames;
    }

    public static boolean isHistoryValid() {
        return historyValid;
    }

    /**
     * Captures the camera-relative terrain transform once per rendered frame.
     * The shader uses the previous transform to find the old pixel belonging
     * to the same world-space surface.
     */
    public static void prepareReprojection(double cameraX, double cameraY, double cameraZ) {
        if (preparedFrameIndex == frameIndex) {
            return;
        }

        if (hasCurrentTransform) {
            MemoryUtil.memCopy(CURRENT_MVP.ptr, PREVIOUS_MVP.ptr, 16L * Float.BYTES);
            PREVIOUS_CAMERA_POSITION.putFloat(0, currentCameraX);
            PREVIOUS_CAMERA_POSITION.putFloat(4, currentCameraY);
            PREVIOUS_CAMERA_POSITION.putFloat(8, currentCameraZ);
        } else {
            MemoryUtil.memCopy(VRenderSystem.MVP.ptr, PREVIOUS_MVP.ptr, 16L * Float.BYTES);
            PREVIOUS_CAMERA_POSITION.putFloat(0, (float) cameraX);
            PREVIOUS_CAMERA_POSITION.putFloat(4, (float) cameraY);
            PREVIOUS_CAMERA_POSITION.putFloat(8, (float) cameraZ);
        }

        MemoryUtil.memCopy(VRenderSystem.MVP.ptr, CURRENT_MVP.ptr, 16L * Float.BYTES);
        currentCameraX = (float) cameraX;
        currentCameraY = (float) cameraY;
        currentCameraZ = (float) cameraZ;
        hasCurrentTransform = true;
        preparedFrameIndex = frameIndex;
    }

    public static MappedBuffer getPreviousMvp() {
        return PREVIOUS_MVP;
    }

    public static MappedBuffer getPreviousCameraPosition() {
        return PREVIOUS_CAMERA_POSITION;
    }

    /**
     * Unlimited selects a true running average while the camera remains still.
     */
    public static float getHistoryBlend() {
        int frames = Initializer.CONFIG.rayTracingTemporalFrames;
        if (frames <= 0) {
            if (!cameraStationary) {
                return 0.9375F;
            }
            int samples = Math.max(1, stableFrames);
            return (samples - 1.0F) / samples;
        }
        int clamped = Math.max(1, Math.min(64, frames));
        float blend = (clamped - 1.0F) / clamped;
        return cameraStationary ? blend : Math.min(blend, 0.9375F);
    }

    public static void invalidate() {
        historyValid = false;
        hasHistory = false;
        cameraStationary = false;
        hasCurrentTransform = false;
        preparedFrameIndex = -1;
        stableFrames = 0;
    }

    public static void cleanUp() {
        for (int index = 0; index < HISTORY_IMAGES.length; index++) {
            if (HISTORY_IMAGES[index] != null) {
                HISTORY_IMAGES[index].free();
                HISTORY_IMAGES[index] = null;
            }
            if (INDIRECT_HISTORY_IMAGES[index] != null) {
                INDIRECT_HISTORY_IMAGES[index].free();
                INDIRECT_HISTORY_IMAGES[index] = null;
            }
        }
        if (depthOwnerImage != null) {
            depthOwnerImage.free();
            depthOwnerImage = null;
        }
        width = 0;
        height = 0;
        frameIndex = 0;
        currentImageIndex = 0;
        historyValid = false;
        hasHistory = false;
        stableFrames = 0;
        lastCameraPosition = null;
        lastLevel = null;
    }

    private static void ensureImages(VkCommandBuffer commandBuffer, int requestedWidth, int requestedHeight) {
        if (HISTORY_IMAGES[0] != null && width == requestedWidth && height == requestedHeight) {
            return;
        }

        if (HISTORY_IMAGES[0] != null) {
            Vulkan.waitIdle();
            cleanUp();
        }

        width = requestedWidth;
        height = requestedHeight;
        for (int index = 0; index < HISTORY_IMAGES.length; index++) {
            HISTORY_IMAGES[index] = VulkanImage.builder(width, height)
                    .setFormat(VK_FORMAT_R16G16B16A16_SFLOAT)
                    .setUsage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .createVulkanImage();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                HISTORY_IMAGES[index].transitionImageLayout(
                        stack,
                        commandBuffer,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                );
                VkClearColorValue clearHistory = VkClearColorValue.calloc(stack);
                clearHistory.float32(0, 0.0F);
                clearHistory.float32(1, 0.0F);
                clearHistory.float32(2, 0.0F);
                clearHistory.float32(3, 0.0F);
                VkImageSubresourceRange.Buffer clearRange = VkImageSubresourceRange.calloc(1, stack);
                clearRange.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                clearRange.baseMipLevel(0);
                clearRange.levelCount(1);
                clearRange.baseArrayLayer(0);
                clearRange.layerCount(1);
                vkCmdClearColorImage(
                        commandBuffer,
                        HISTORY_IMAGES[index].getId(),
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        clearHistory,
                        clearRange
                );
                HISTORY_IMAGES[index].transitionImageLayout(
                        stack,
                        commandBuffer,
                        VK_IMAGE_LAYOUT_GENERAL
                );
            }

            INDIRECT_HISTORY_IMAGES[index] = VulkanImage.builder(width, height)
                    .setFormat(VK_FORMAT_R16G16B16A16_SFLOAT)
                    .setUsage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .createVulkanImage();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                INDIRECT_HISTORY_IMAGES[index].transitionImageLayout(
                        stack,
                        commandBuffer,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                );
                VkClearColorValue clearHistory = VkClearColorValue.calloc(stack);
                clearHistory.float32(0, 0.0F);
                clearHistory.float32(1, 0.0F);
                clearHistory.float32(2, 0.0F);
                clearHistory.float32(3, 0.0F);
                VkImageSubresourceRange.Buffer clearRange = VkImageSubresourceRange.calloc(1, stack);
                clearRange.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                clearRange.baseMipLevel(0);
                clearRange.levelCount(1);
                clearRange.baseArrayLayer(0);
                clearRange.layerCount(1);
                vkCmdClearColorImage(
                        commandBuffer,
                        INDIRECT_HISTORY_IMAGES[index].getId(),
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        clearHistory,
                        clearRange
                );
                INDIRECT_HISTORY_IMAGES[index].transitionImageLayout(
                        stack,
                        commandBuffer,
                        VK_IMAGE_LAYOUT_GENERAL
                );
            }
        }
        depthOwnerImage = VulkanImage.builder(width, height)
                .setFormat(VK_FORMAT_R32_UINT)
                .setUsage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                .createVulkanImage();
        Initializer.LOGGER.info("RT temporal history initialized: {}x{} RGBA16F ping-pong", width, height);
    }
}
