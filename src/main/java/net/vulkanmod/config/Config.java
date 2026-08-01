package net.vulkanmod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.vulkanmod.config.video.VideoModeManager;
import net.vulkanmod.config.video.VideoModeSet;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

public class Config {
    public int frameQueueSize = 2;
    public VideoModeSet.VideoMode videoMode = VideoModeManager.getFirstAvailable().getVideoMode();
    public boolean windowedFullscreen = false;
    public int performancePreset = PerformancePreset.BALANCED.id;
    public int chunkUploadsPerFrame = PerformancePreset.BALANCED.chunkUploadsPerFrame;
    public boolean adaptiveChunkUploads = true;
    public int renderScale = RenderScale.DEFAULT;

    public int advCulling = 2;

    public boolean indirectDraw = true;

    public boolean uniqueOpaqueLayer = true;
    public boolean entityCulling = true;
    public boolean blockEntityCulling = true;
    public boolean leavesCulling = true;
    public int particleCulling = 2;
    public int device = -1;

    public int ambientOcclusion = 1;
    public boolean textureAnimations = true;
    public int rayTracingViewMode = 0;
    public int rayTracingDebugView = 0;
    public int rayTracingShadowRays = 4;
    public int rayTracingShadowSoftness = 25;
    public int rayTracingShadowDarkness = 62;
    public int rayTracingShadowDistance = 256;
    public boolean rayTracingDirectLighting = true;
    public int rayTracingSunLight = 100;
    public int rayTracingSkyLight = 100;
    public int rayTracingBlockLight = 100;
    public boolean rayTracingDynamicLights = true;
    public int rayTracingDynamicLightCount = 2;
    public int rayTracingDynamicLightDistance = 20;
    public int rayTracingDynamicMaxLightsPerPixel = 16;
    public boolean rayTracingDynamicLightShadows = true;
    public int rayTracingDynamicShadowMaxLights = 16;
    public int rayTracingDynamicShadowDistance = 32;
    public int rayTracingDynamicLightStrength = 100;
    public boolean rayTracingWaterReflections = true;
    public int rayTracingWaterReflectionStrength = 70;
    public int rayTracingWaterReflectionDistance = 128;
    public boolean rayTracingSkyOcclusion = true;
    public int rayTracingSkyRays = 1;
    public int rayTracingSkyDistance = 64;
    public int rayTracingTemporalFrames = 16;
    public int rayTracingSkyDenoiser = 1;
    public int rayTracingEntityProxyUpdateInterval = 1;

    public void write() {

        Path parent = CONFIG_PATH.getParent();
        if(parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                System.err.println("[VulkanMod] Failed to create config directory: " + parent + " - " + e.getMessage());
                return;
            }
        }

        try {
            Files.write(CONFIG_PATH, Collections.singleton(GSON.toJson(this)));
        } catch (IOException e) {
            System.err.println("[VulkanMod] Failed to write config: " + CONFIG_PATH + " - " + e.getMessage());
        }
    }

    private static Path CONFIG_PATH;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static Config load(Path path) {
        Config config;
        Config.CONFIG_PATH = path;

        if (Files.exists(path)) {
            try (FileReader fileReader = new FileReader(path.toFile())) {
                config = GSON.fromJson(fileReader, Config.class);
            }
            catch (IOException exception) {
                throw new RuntimeException(exception.getMessage());
            }
        }
        else {
            config = null;
        }

        return config;
    }
}
