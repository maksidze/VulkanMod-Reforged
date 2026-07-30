package net.vulkanmod.vulkan.device;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceBufferDeviceAddressFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

/**
 * Hardware ray tracing capabilities exposed by a physical Vulkan device.
 *
 * <p>This class only negotiates the device functionality. Acceleration structure
 * construction and the ray tracing render passes are implemented separately.</p>
 */
public final class RayTracingCapabilities {
    public static final Set<String> REQUIRED_EXTENSIONS = Set.of(
            VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
            VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
            VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME,
            VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME
    );

    private final boolean extensionsSupported;
    private final boolean rayQueryExtensionSupported;

    private final VkPhysicalDeviceBufferDeviceAddressFeatures bufferDeviceAddressFeatures;
    private final VkPhysicalDeviceAccelerationStructureFeaturesKHR accelerationStructureFeatures;
    private final VkPhysicalDeviceRayTracingPipelineFeaturesKHR rayTracingPipelineFeatures;
    private final VkPhysicalDeviceRayQueryFeaturesKHR rayQueryFeatures;

    private VkPhysicalDeviceAccelerationStructurePropertiesKHR accelerationStructureProperties;
    private VkPhysicalDeviceRayTracingPipelinePropertiesKHR rayTracingPipelineProperties;

    private boolean pipelineSupported;
    private boolean rayQuerySupported;

    RayTracingCapabilities(Set<String> availableExtensions, VkPhysicalDeviceVulkan11Features featureChainTail) {
        this.extensionsSupported = availableExtensions.containsAll(REQUIRED_EXTENSIONS);
        this.rayQueryExtensionSupported = availableExtensions.contains(VK_KHR_RAY_QUERY_EXTENSION_NAME);

        if (!this.extensionsSupported) {
            this.bufferDeviceAddressFeatures = null;
            this.accelerationStructureFeatures = null;
            this.rayTracingPipelineFeatures = null;
            this.rayQueryFeatures = null;
            return;
        }

        this.bufferDeviceAddressFeatures = VkPhysicalDeviceBufferDeviceAddressFeatures.calloc();
        this.bufferDeviceAddressFeatures.sType$Default();

        this.accelerationStructureFeatures = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc();
        this.accelerationStructureFeatures.sType$Default();

        this.rayTracingPipelineFeatures = VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc();
        this.rayTracingPipelineFeatures.sType$Default();

        this.rayQueryFeatures = this.rayQueryExtensionSupported
                ? VkPhysicalDeviceRayQueryFeaturesKHR.calloc()
                : null;
        if (this.rayQueryFeatures != null) {
            this.rayQueryFeatures.sType$Default();
        }

        featureChainTail.pNext(this.bufferDeviceAddressFeatures.address());
        this.bufferDeviceAddressFeatures.pNext(this.accelerationStructureFeatures.address());
        this.accelerationStructureFeatures.pNext(this.rayTracingPipelineFeatures.address());
        if (this.rayQueryFeatures != null) {
            this.rayTracingPipelineFeatures.pNext(this.rayQueryFeatures.address());
        }
    }

    void finishQuery(VkPhysicalDevice physicalDevice) {
        if (!this.extensionsSupported) {
            return;
        }

        this.pipelineSupported = this.bufferDeviceAddressFeatures.bufferDeviceAddress()
                && this.accelerationStructureFeatures.accelerationStructure()
                && this.rayTracingPipelineFeatures.rayTracingPipeline();
        this.rayQuerySupported = this.pipelineSupported
                && this.rayQueryFeatures != null
                && this.rayQueryFeatures.rayQuery();

        if (!this.pipelineSupported) {
            return;
        }

        this.accelerationStructureProperties = VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc();
        this.accelerationStructureProperties.sType$Default();
        this.rayTracingPipelineProperties = VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc();
        this.rayTracingPipelineProperties.sType$Default();

        VkPhysicalDeviceProperties2 properties2 = VkPhysicalDeviceProperties2.calloc();
        properties2.sType$Default();
        properties2.pNext(this.accelerationStructureProperties.address());
        this.accelerationStructureProperties.pNext(this.rayTracingPipelineProperties.address());
        vkGetPhysicalDeviceProperties2(physicalDevice, properties2);
        properties2.free();
    }

    /** Returns a pNext chain containing the RT features that may be passed to vkCreateDevice. */
    long createEnabledFeatureChain(MemoryStack stack, long next) {
        if (!this.pipelineSupported) {
            return next;
        }

        if (this.rayQuerySupported) {
            VkPhysicalDeviceRayQueryFeaturesKHR rayQuery = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack);
            rayQuery.sType$Default();
            rayQuery.rayQuery(true);
            rayQuery.pNext(next);
            next = rayQuery.address();
        }

        VkPhysicalDeviceRayTracingPipelineFeaturesKHR rayTracingPipeline =
                VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack);
        rayTracingPipeline.sType$Default();
        rayTracingPipeline.rayTracingPipeline(true);
        rayTracingPipeline.pNext(next);
        next = rayTracingPipeline.address();

        VkPhysicalDeviceAccelerationStructureFeaturesKHR accelerationStructure =
                VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack);
        accelerationStructure.sType$Default();
        accelerationStructure.accelerationStructure(true);
        accelerationStructure.pNext(next);
        next = accelerationStructure.address();

        VkPhysicalDeviceBufferDeviceAddressFeatures bufferDeviceAddress =
                VkPhysicalDeviceBufferDeviceAddressFeatures.calloc(stack);
        bufferDeviceAddress.sType$Default();
        bufferDeviceAddress.bufferDeviceAddress(true);
        bufferDeviceAddress.pNext(next);
        return bufferDeviceAddress.address();
    }

    public Set<String> getEnabledExtensions() {
        if (!this.pipelineSupported) {
            return Set.of();
        }

        Set<String> extensions = new LinkedHashSet<>(REQUIRED_EXTENSIONS);
        if (this.rayQuerySupported) {
            extensions.add(VK_KHR_RAY_QUERY_EXTENSION_NAME);
        }
        return Set.copyOf(extensions);
    }

    public boolean isPipelineSupported() {
        return this.pipelineSupported;
    }

    public boolean isRayQuerySupported() {
        return this.rayQuerySupported;
    }

    public int maxRayRecursionDepth() {
        return this.rayTracingPipelineProperties == null
                ? 0
                : this.rayTracingPipelineProperties.maxRayRecursionDepth();
    }

    public int shaderGroupHandleSize() {
        return this.rayTracingPipelineProperties == null
                ? 0
                : this.rayTracingPipelineProperties.shaderGroupHandleSize();
    }

    public long maxGeometryCount() {
        return this.accelerationStructureProperties == null
                ? 0L
                : this.accelerationStructureProperties.maxGeometryCount();
    }

    public int minScratchOffsetAlignment() {
        return this.accelerationStructureProperties == null
                ? 1
                : this.accelerationStructureProperties.minAccelerationStructureScratchOffsetAlignment();
    }

    public void free() {
        if (this.rayTracingPipelineProperties != null) {
            this.rayTracingPipelineProperties.free();
        }
        if (this.accelerationStructureProperties != null) {
            this.accelerationStructureProperties.free();
        }
        if (this.rayQueryFeatures != null) {
            this.rayQueryFeatures.free();
        }
        if (this.rayTracingPipelineFeatures != null) {
            this.rayTracingPipelineFeatures.free();
        }
        if (this.accelerationStructureFeatures != null) {
            this.accelerationStructureFeatures.free();
        }
        if (this.bufferDeviceAddressFeatures != null) {
            this.bufferDeviceAddressFeatures.free();
        }
    }
}
