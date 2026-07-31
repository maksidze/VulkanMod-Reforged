package net.vulkanmod.mixin.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.Device;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.raytracing.RayTracingManager;
import net.vulkanmod.vulkan.raytracing.RtDynamicLights;
import net.vulkanmod.vulkan.raytracing.RtGpuProfiler;
import net.vulkanmod.vulkan.raytracing.RtTemporalResources;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static net.vulkanmod.Initializer.getVersion;

@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayM {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    private static long bytesToMegabytes(long bytes) {
        return 0;
    }

    @Shadow
    @Final
    private Font font;

    @Shadow
    protected abstract List<String> getGameInformation();

    @Shadow
    protected abstract List<String> getSystemInformation();

    @Redirect(method = "getSystemInformation", at = @At(value = "INVOKE", target = "Lcom/google/common/collect/Lists;newArrayList([Ljava/lang/Object;)Ljava/util/ArrayList;"))
    private ArrayList<String> redirectList(Object[] elements) {
        ArrayList<String> strings = new ArrayList<>();

        long maxMemory = Runtime.getRuntime().maxMemory();
        long totalMemory = Runtime.getRuntime().totalMemory();
        long freeMemory = Runtime.getRuntime().freeMemory();
        long usedMemory = totalMemory - freeMemory;

        Device device = Vulkan.getDevice();

        strings.add(String.format("Java: %s", System.getProperty("java.version")));
        strings.add(String.format("Mem: % 2d%% %03d/%03dMB", usedMemory * 100L / maxMemory, bytesToMegabytes(usedMemory), bytesToMegabytes(maxMemory)));
        strings.add(String.format("Allocated: % 2d%% %03dMB", totalMemory * 100L / maxMemory, bytesToMegabytes(totalMemory)));
        strings.add(String.format("Off-heap: " + getOffHeapMemory() + "MB"));
        strings.add("NativeMemory: %dMB".formatted(MemoryManager.getInstance().getNativeMemoryMB()));
        strings.add("DeviceMemory: %dMB".formatted(MemoryManager.getInstance().getAllocatedDeviceMemoryMB()));
        strings.add("");
        strings.add("VulkanMod " + getVersion());
        strings.add("CPU: " + vulkanMod$getCpuInfo());
        strings.add("GPU: " + device.deviceName);
        strings.add("Driver: " + device.driverVersion);
        strings.add("Vulkan: " + device.vkVersion);
        if (RayTracingManager.shouldEnableRayQueryPass()) {
            if (RtGpuProfiler.hasSample()) {
                strings.add(String.format(
                        "RT terrain GPU: %.3fms (solid %.2f, cutout %.2f, water %.2f)",
                        RtGpuProfiler.getTerrainGpuMs(),
                        RtGpuProfiler.getLayerGpuMs(TerrainRenderType.SOLID),
                        RtGpuProfiler.getLayerGpuMs(TerrainRenderType.CUTOUT_MIPPED)
                                + RtGpuProfiler.getLayerGpuMs(TerrainRenderType.CUTOUT),
                        RtGpuProfiler.getLayerGpuMs(TerrainRenderType.TRANSLUCENT)
                ));
            } else {
                strings.add("RT terrain GPU: waiting for sample");
            }
            strings.add(String.format(
                    "RT lights: %d, lights/pixel: %s, shadow/pixel: %s, shadow distance: %s",
                    RtDynamicLights.getActiveLightCount(),
                    vulkanMod$limitText(Initializer.CONFIG.rayTracingDynamicMaxLightsPerPixel),
                    vulkanMod$limitText(Initializer.CONFIG.rayTracingDynamicShadowMaxLights),
                    vulkanMod$distanceText(Initializer.CONFIG.rayTracingDynamicShadowDistance)
            ));
            strings.add(String.format(
                    "RT history: %s, stable: %d, sky denoiser: %s",
                    RtTemporalResources.isHistoryValid() ? "valid" : "reset",
                    RtTemporalResources.getStableFrames(),
                    vulkanMod$skyDenoiserText(Initializer.CONFIG.rayTracingSkyDenoiser)
            ));
        }
        strings.add("");
        strings.add("");

        Collections.addAll(strings, WorldRenderer.getInstance().getChunkAreaManager().getStats());

        return strings;
    }

    private long getOffHeapMemory() {
        return bytesToMegabytes(ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed());
    }

    @Unique
    private static String vulkanMod$getCpuInfo() {
        return "%s, %d logical processors".formatted(
                System.getProperty("os.arch", "unknown"),
                Runtime.getRuntime().availableProcessors());
    }

    @Unique
    private static String vulkanMod$limitText(int value) {
        return value <= 0 ? "Unlimited" : Integer.toString(value);
    }

    @Unique
    private static String vulkanMod$distanceText(int value) {
        return value <= 0 ? "Unlimited" : value + "m";
    }

    @Unique
    private static String vulkanMod$skyDenoiserText(int value) {
        return switch (value) {
            case 1 -> "Fast";
            case 2 -> "Quality";
            default -> "Off";
        };
    }
}
