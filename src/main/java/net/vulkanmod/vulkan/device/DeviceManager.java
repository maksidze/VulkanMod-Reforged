package net.vulkanmod.vulkan.device;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.queue.*;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static java.util.stream.Collectors.toSet;
import static net.vulkanmod.vulkan.queue.Queue.findQueueFamilies;
import static net.vulkanmod.vulkan.util.VUtil.asPointerBuffer;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryStack.stackGet;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

public abstract class DeviceManager {
    public static List<Device> availableDevices;
    public static List<Device> suitableDevices;

    public static VkPhysicalDevice physicalDevice;
    public static VkDevice vkDevice;

    public static Device device;
    private static boolean rayTracingEnabled;

    public static VkPhysicalDeviceProperties deviceProperties;
    public static VkPhysicalDeviceMemoryProperties memoryProperties;

    public static SurfaceProperties surfaceProperties;

    static GraphicsQueue graphicsQueue;
    static PresentQueue presentQueue;
    static TransferQueue transferQueue;
    static ComputeQueue computeQueue;

    public static void init(VkInstance instance) {
        try {
            DeviceManager.getSuitableDevices(instance);
            DeviceManager.pickPhysicalDevice();
            DeviceManager.createLogicalDevice();
        } catch (Exception e) {
            Initializer.LOGGER.info(getAvailableDevicesInfo());
            throw new RuntimeException(e);
        }
    }

    static List<Device> getAvailableDevices(VkInstance instance) {
        try (MemoryStack stack = stackPush()) {
            List<Device> devices = new ObjectArrayList<>();

            IntBuffer deviceCount = stack.ints(0);

            vkEnumeratePhysicalDevices(instance, deviceCount, null);

            if (deviceCount.get(0) == 0) {
                return List.of();
            }

            PointerBuffer ppPhysicalDevices = stack.mallocPointer(deviceCount.get(0));
            vkEnumeratePhysicalDevices(instance, deviceCount, ppPhysicalDevices);

            VkPhysicalDevice currentDevice;

            for (int i = 0; i < ppPhysicalDevices.capacity(); i++) {
                currentDevice = new VkPhysicalDevice(ppPhysicalDevices.get(i), instance);

                Device device = new Device(currentDevice);
                devices.add(device);
            }

            return devices;
        }
    }

    static void getSuitableDevices(VkInstance instance) {
        availableDevices = getAvailableDevices(instance);

        List<Device> devices = new ObjectArrayList<>();
        for (Device device : availableDevices) {
            if (isDeviceSuitable(device.physicalDevice)) {
                devices.add(device);
            }
        }

        suitableDevices = devices;
    }

    public static void pickPhysicalDevice() {
        try (MemoryStack stack = stackPush()) {

            int deviceIdx = Initializer.CONFIG.device;
            if (deviceIdx >= 0 && deviceIdx < suitableDevices.size())
                DeviceManager.device = suitableDevices.get(deviceIdx);
            else {
                DeviceManager.device = autoPickDevice();
                Initializer.CONFIG.device = -1;
            }

            physicalDevice = DeviceManager.device.physicalDevice;

            deviceProperties = device.properties;

            memoryProperties = VkPhysicalDeviceMemoryProperties.malloc();
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties);

            surfaceProperties = querySurfaceProperties(physicalDevice, stack);

            Initializer.LOGGER.info(
                    "Selected Vulkan device: {} ({}) driver {} Vulkan {} indirectDrawSupported={} fastIndirectDraw={} rayTracing={} rayQuery={}",
                    device.deviceName,
                    device.vendorIdString,
                    device.driverVersion,
                    device.vkVersion,
                    device.isDrawIndirectSupported(),
                    supportsFastIndirectDraw(),
                    device.rayTracingCapabilities().isPipelineSupported(),
                    device.rayTracingCapabilities().isRayQuerySupported()
            );
        }
    }

    public static boolean supportsFastIndirectDraw() {
        return device != null && device.isDrawIndirectSupported() && !device.isIntel();
    }

    static Device autoPickDevice() {
        ArrayList<Device> integratedGPUs = new ArrayList<>();
        ArrayList<Device> otherDevices = new ArrayList<>();
        Device discreteGpu = null;
        for (Device device : suitableDevices) {
            int deviceType = device.properties.deviceType();
            if (deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
                if (Vulkan.RAY_TRACING_REQUESTED && device.rayTracingCapabilities().isPipelineSupported()) {
                    return device;
                }
                if (discreteGpu == null) {
                    discreteGpu = device;
                }
            } else if (deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU)
                integratedGPUs.add(device);
            else
                otherDevices.add(device);
        }

        if (discreteGpu != null) {
            return discreteGpu;
        }
        if (!integratedGPUs.isEmpty()) {
            return integratedGPUs.get(0);
        }
        if (!otherDevices.isEmpty()) {
            return otherDevices.get(0);
        }
        throw new IllegalStateException("Failed to find a suitable GPU");
    }

    public static void createLogicalDevice() {
        try (MemoryStack stack = stackPush()) {

            net.vulkanmod.vulkan.queue.Queue.QueueFamilyIndices indices = findQueueFamilies(physicalDevice);

            int[] uniqueQueueFamilies = indices.unique();

            VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(uniqueQueueFamilies.length, stack);

            for (int i = 0; i < uniqueQueueFamilies.length; i++) {
                VkDeviceQueueCreateInfo queueCreateInfo = queueCreateInfos.get(i);
                queueCreateInfo.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO);
                queueCreateInfo.queueFamilyIndex(uniqueQueueFamilies[i]);
                queueCreateInfo.pQueuePriorities(stack.floats(1.0f));
            }

            VkPhysicalDeviceVulkan11Features deviceVulkan11Features = VkPhysicalDeviceVulkan11Features.calloc(stack);
            deviceVulkan11Features.sType$Default();
            deviceVulkan11Features.shaderDrawParameters(device.isDrawIndirectSupported());

            VkPhysicalDeviceFeatures2 deviceFeatures = VkPhysicalDeviceFeatures2.calloc(stack);
            deviceFeatures.sType$Default();
            deviceFeatures.features().samplerAnisotropy(device.availableFeatures.features().samplerAnisotropy());
            deviceFeatures.features().logicOp(device.availableFeatures.features().logicOp());

            deviceFeatures.features().multiDrawIndirect(device.isDrawIndirectSupported());

            if (device.availableFeatures.features().wideLines()) {
                deviceFeatures.features().wideLines(true);
                VRenderSystem.canSetLineWidth = true;
            }

            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack);
            createInfo.sType$Default();
            createInfo.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
            createInfo.pQueueCreateInfos(queueCreateInfos);
            createInfo.pEnabledFeatures(null);

            rayTracingEnabled = Vulkan.RAY_TRACING_REQUESTED
                    && device.rayTracingCapabilities().isPipelineSupported();

            long optionalFeatureChain = VK_NULL_HANDLE;

            if (Vulkan.DYNAMIC_RENDERING) {
                VkPhysicalDeviceDynamicRenderingFeaturesKHR dynamicRenderingFeaturesKHR = VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack);
                dynamicRenderingFeaturesKHR.sType$Default();
                dynamicRenderingFeaturesKHR.dynamicRendering(true);
                dynamicRenderingFeaturesKHR.pNext(optionalFeatureChain);
                optionalFeatureChain = dynamicRenderingFeaturesKHR.address();
            }

            Set<String> enabledExtensions = new LinkedHashSet<>(Vulkan.REQUIRED_EXTENSION);
            if (rayTracingEnabled) {
                optionalFeatureChain = device.rayTracingCapabilities()
                        .createEnabledFeatureChain(stack, optionalFeatureChain);
                enabledExtensions.addAll(device.rayTracingCapabilities().getEnabledExtensions());
                Initializer.LOGGER.info("Enabled RT device extensions: {}", enabledExtensions);
            }
            deviceVulkan11Features.pNext(optionalFeatureChain);
            deviceFeatures.pNext(deviceVulkan11Features.address());
            createInfo.pNext(deviceFeatures.address());

            createInfo.ppEnabledExtensionNames(asPointerBuffer(enabledExtensions));

            // Device layers are legacy and must remain disabled. Validation is enabled at instance level.
            createInfo.ppEnabledLayerNames(null);

            PointerBuffer pDevice = stack.pointers(VK_NULL_HANDLE);

            int res = vkCreateDevice(physicalDevice, createInfo, null, pDevice);
            Vulkan.checkResult(res, "Failed to create logical device");

            vkDevice = new VkDevice(pDevice.get(0), physicalDevice, createInfo, VK_API_VERSION_1_2);

            if (rayTracingEnabled) {
                Initializer.LOGGER.info(
                        "Hardware ray tracing enabled: maxRecursionDepth={} shaderGroupHandleSize={} maxGeometryCount={}",
                        device.rayTracingCapabilities().maxRayRecursionDepth(),
                        device.rayTracingCapabilities().shaderGroupHandleSize(),
                        device.rayTracingCapabilities().maxGeometryCount()
                );
            } else if (Vulkan.RAY_TRACING_REQUESTED) {
                Initializer.LOGGER.warn("Hardware ray tracing is unavailable on the selected Vulkan device; using raster fallback");
            }

            graphicsQueue = new GraphicsQueue(stack, indices.graphicsFamily);
            transferQueue = new TransferQueue(stack, indices.transferFamily);
            presentQueue = new PresentQueue(stack, indices.presentFamily);
            computeQueue = new ComputeQueue(stack, indices.computeFamily);
        }
    }

    private static PointerBuffer getRequiredExtensions() {
        PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();

        if (Vulkan.ENABLE_VALIDATION_LAYERS) {

            MemoryStack stack = stackGet();

            PointerBuffer extensions = stack.mallocPointer(glfwExtensions.capacity() + 1);

            extensions.put(glfwExtensions);
            extensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));

            return extensions.rewind();
        }

        return glfwExtensions;
    }

    private static boolean isDeviceSuitable(VkPhysicalDevice device) {
        try (MemoryStack stack = stackPush()) {
            Queue.QueueFamilyIndices indices = findQueueFamilies(device);

            VkExtensionProperties.Buffer availableExtensions = getAvailableExtension(stack, device);
            boolean extensionsSupported = availableExtensions.stream()
                    .map(VkExtensionProperties::extensionNameString)
                    .collect(toSet())
                    .containsAll(Vulkan.REQUIRED_EXTENSION);

            boolean swapChainAdequate = false;

            if (extensionsSupported) {
                SurfaceProperties surfaceProperties = querySurfaceProperties(device, stack);
                swapChainAdequate = surfaceProperties.formats.hasRemaining() && surfaceProperties.presentModes.hasRemaining();
            }

            VkPhysicalDeviceFeatures supportedFeatures = VkPhysicalDeviceFeatures.malloc(stack);
            vkGetPhysicalDeviceFeatures(device, supportedFeatures);
            boolean anisotropicFilterSupported = supportedFeatures.samplerAnisotropy();

            return indices.isSuitable() && extensionsSupported && swapChainAdequate;
        }
    }

    private static VkExtensionProperties.Buffer getAvailableExtension(MemoryStack stack, VkPhysicalDevice device) {
        IntBuffer extensionCount = stack.ints(0);
        vkEnumerateDeviceExtensionProperties(device, (String) null, extensionCount, null);

        VkExtensionProperties.Buffer availableExtensions = VkExtensionProperties.malloc(extensionCount.get(0), stack);
        vkEnumerateDeviceExtensionProperties(device, (String) null, extensionCount, availableExtensions);

        return availableExtensions;
    }

    public static int findDepthFormat(boolean use24BitsDepthFormat) {
        int[] formats = use24BitsDepthFormat ? new int[]
                {VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_X8_D24_UNORM_PACK32, VK_FORMAT_D32_SFLOAT, VK_FORMAT_D32_SFLOAT_S8_UINT}
                : new int[]{VK_FORMAT_D32_SFLOAT, VK_FORMAT_D32_SFLOAT_S8_UINT};

        return findSupportedFormat(
                VK_IMAGE_TILING_OPTIMAL,
                VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT,
                formats);
    }

    private static int findSupportedFormat(int tiling, int features, int... formatCandidates) {
        try (MemoryStack stack = stackPush()) {

            VkFormatProperties props = VkFormatProperties.calloc(stack);

            for (int format : formatCandidates) {

                vkGetPhysicalDeviceFormatProperties(physicalDevice, format, props);

                if (tiling == VK_IMAGE_TILING_LINEAR && (props.linearTilingFeatures() & features) == features) {
                    return format;
                } else if (tiling == VK_IMAGE_TILING_OPTIMAL && (props.optimalTilingFeatures() & features) == features) {
                    return format;
                }

            }
        }

        throw new RuntimeException("Failed to find supported format");
    }

    public static String getAvailableDevicesInfo() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("\n");

        if (availableDevices == null) {
            stringBuilder.append("\tDevice Manager not initialized");
            return stringBuilder.toString();
        }

        if (availableDevices.isEmpty()) {
            stringBuilder.append("\tNo available device found");
        }

        for (Device device : availableDevices) {
            stringBuilder.append("\tDevice: %s\n".formatted(device.deviceName));

            stringBuilder.append("\t\tVulkan Version: %s\n".formatted(device.vkVersion));

            stringBuilder.append("\t\t");
            var unsupportedExtensions = device.getUnsupportedExtensions(Vulkan.REQUIRED_EXTENSION);
            if (unsupportedExtensions.isEmpty()) {
                stringBuilder.append("All required extensions are supported\n");
            } else {
                stringBuilder.append("Unsupported extension: %s\n".formatted(unsupportedExtensions));
            }
        }

        return stringBuilder.toString();
    }

    public static void destroy() {
        graphicsQueue.cleanUp();
        transferQueue.cleanUp();
        computeQueue.cleanUp();

        vkDestroyDevice(vkDevice, null);
    }

    public static GraphicsQueue getGraphicsQueue() {
        return graphicsQueue;
    }

    public static PresentQueue getPresentQueue() {
        return presentQueue;
    }

    public static TransferQueue getTransferQueue() {
        return transferQueue;
    }

    public static ComputeQueue getComputeQueue() {
        return computeQueue;
    }

    public static boolean isRayTracingEnabled() {
        return rayTracingEnabled;
    }

    public static boolean isRayQueryEnabled() {
        return rayTracingEnabled
                && device != null
                && device.rayTracingCapabilities().isRayQuerySupported();
    }

    public static RayTracingCapabilities getRayTracingCapabilities() {
        return device == null ? null : device.rayTracingCapabilities();
    }

    public static SurfaceProperties querySurfaceProperties(VkPhysicalDevice device, MemoryStack stack) {

        long surface = Vulkan.getSurface();
        SurfaceProperties details = new SurfaceProperties();

        details.capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, surface, details.capabilities);

        IntBuffer count = stack.ints(0);

        vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, null);

        if (count.get(0) != 0) {
            details.formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, details.formats);
        }

        vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, count, null);

        if (count.get(0) != 0) {
            details.presentModes = stack.mallocInt(count.get(0));
            vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, count, details.presentModes);
        }

        return details;
    }

    public static class SurfaceProperties {
        public VkSurfaceCapabilitiesKHR capabilities;
        public VkSurfaceFormatKHR.Buffer formats;
        public IntBuffer presentModes;
    }

}
