package net.vulkanmod.vulkan.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import net.vulkanmod.compat.external.ExternalRenderPathSupport;
import net.vulkanmod.compat.external.ExternalTerrainRenderBridge;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.util.MappedBuffer;

import java.util.function.Supplier;

public class Uniforms {

    public static Object2ReferenceOpenHashMap<String, Supplier<Integer>> vec1i_uniformMap = new Object2ReferenceOpenHashMap<>();

    public static Object2ReferenceOpenHashMap<String, Supplier<Float>> vec1f_uniformMap = new Object2ReferenceOpenHashMap<>();
    public static Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> vec2f_uniformMap = new Object2ReferenceOpenHashMap<>();
    public static Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> vec3f_uniformMap = new Object2ReferenceOpenHashMap<>();
    public static Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> vec4f_uniformMap = new Object2ReferenceOpenHashMap<>();

    public static Object2ReferenceOpenHashMap<String, Supplier<MappedBuffer>> mat4f_uniformMap = new Object2ReferenceOpenHashMap<>();

    public static void setupDefaultUniforms() {

        mat4f_uniformMap.put("ModelViewMat", VRenderSystem::getModelViewMatrix);
        mat4f_uniformMap.put("ProjMat", VRenderSystem::getProjectionMatrix);
        mat4f_uniformMap.put("MVP", VRenderSystem::getMVP);
        mat4f_uniformMap.put("TextureMat", VRenderSystem::getTextureMatrix);
        if (ExternalRenderPathSupport.isExternalLodBridgeEnabled()) {
            mat4f_uniformMap.put("ExternalLodCombinedMatrix", ExternalTerrainRenderBridge::getCombinedMatrix);
        }

        vec1i_uniformMap.put("EndPortalLayers", () -> 15);
        vec1i_uniformMap.put("FogShape", () -> RenderSystem.getShaderFogShape().getIndex());
        vec1i_uniformMap.put("ShadowRays", () -> {
            int rays = Initializer.CONFIG.rayTracingShadowRays;
            if (rays <= 1) return 1;
            if (rays <= 2) return 2;
            if (rays <= 4) return 4;
            return 8;
        });
        vec1i_uniformMap.put("RtDirectLighting", () -> Initializer.CONFIG.rayTracingDirectLighting ? 1 : 0);
        vec1i_uniformMap.put("RtViewMode", () -> Math.max(0, Math.min(2, Initializer.CONFIG.rayTracingViewMode)));
        vec1i_uniformMap.put("RtDebugView", () -> Math.max(0, Math.min(9, Initializer.CONFIG.rayTracingDebugView)));
        vec1i_uniformMap.put("RtDynamicLightShadows", () -> Initializer.CONFIG.rayTracingDynamicLightShadows ? 1 : 0);
        vec1i_uniformMap.put("TerrainLayer", () -> VRenderSystem.terrainLayer);
        vec1i_uniformMap.put("WaterReflections", () -> Initializer.CONFIG.rayTracingWaterReflections ? 1 : 0);

        vec1f_uniformMap.put("FogStart", RenderSystem::getShaderFogStart);
        vec1f_uniformMap.put("FogEnd", RenderSystem::getShaderFogEnd);
        vec1f_uniformMap.put("LineWidth", RenderSystem::getShaderLineWidth);
        vec1f_uniformMap.put("GameTime", RenderSystem::getShaderGameTime);
        vec1f_uniformMap.put("GlintAlpha", RenderSystem::getShaderGlintAlpha);
        vec1f_uniformMap.put("AlphaCutout", () -> VRenderSystem.alphaCutout);
        vec1f_uniformMap.put("ShadowSoftness", () -> Initializer.CONFIG.rayTracingShadowRays <= 1
                ? 0.0F
                : Math.max(0.0F, Math.min(1.0F, Initializer.CONFIG.rayTracingShadowSoftness / 100.0F)));
        vec1f_uniformMap.put("ShadowStrength", () -> Math.max(
                0.0F,
                Math.min(1.0F, Initializer.CONFIG.rayTracingShadowDarkness / 100.0F)
        ));
        vec1f_uniformMap.put("ShadowDistance", () -> (float) Math.max(
                32,
                Math.min(256, Initializer.CONFIG.rayTracingShadowDistance)
        ));
        vec1f_uniformMap.put("SunLightStrength", () -> Math.max(
                0.0F,
                Math.min(2.0F, Initializer.CONFIG.rayTracingSunLight / 100.0F)
        ));
        vec1f_uniformMap.put("SkyLightStrength", () -> Math.max(
                0.0F,
                Math.min(2.0F, Initializer.CONFIG.rayTracingSkyLight / 100.0F)
        ));
        vec1f_uniformMap.put("BlockLightStrength", () -> Math.max(
                0.0F,
                Math.min(2.0F, Initializer.CONFIG.rayTracingBlockLight / 100.0F)
        ));
        vec1f_uniformMap.put("RtDynamicLightStrength", () -> Math.max(
                0.0F,
                Math.min(2.0F, Initializer.CONFIG.rayTracingDynamicLightStrength / 100.0F)
        ));
        vec1f_uniformMap.put("WaterReflectionStrength", () -> Math.max(
                0.0F,
                Math.min(1.0F, Initializer.CONFIG.rayTracingWaterReflectionStrength / 100.0F)
        ));
        vec1f_uniformMap.put("WaterReflectionDistance", () -> (float) Math.max(
                16,
                Math.min(256, Initializer.CONFIG.rayTracingWaterReflectionDistance)
        ));

        vec2f_uniformMap.put("ScreenSize", VRenderSystem::getScreenSize);

        vec3f_uniformMap.put("Light0_Direction", () -> VRenderSystem.lightDirection0);
        vec3f_uniformMap.put("Light1_Direction", () -> VRenderSystem.lightDirection1);
        vec3f_uniformMap.put("ChunkOffset", () -> VRenderSystem.ChunkOffset);
        vec3f_uniformMap.put("WorldOrigin", () -> VRenderSystem.WorldOrigin);
        vec3f_uniformMap.put("CameraPosition", () -> VRenderSystem.CameraPosition);
        vec3f_uniformMap.put("SunDirection", () -> VRenderSystem.SunDirection);

        vec4f_uniformMap.put("ColorModulator", VRenderSystem::getShaderColor);
        vec4f_uniformMap.put("FogColor", VRenderSystem::getShaderFogColor);
        if (ExternalRenderPathSupport.isExternalLodBridgeEnabled()) {
            vec4f_uniformMap.put("ExternalLodModelOffsetAndYOffset", ExternalTerrainRenderBridge::getModelOffsetAndYOffset);
            vec4f_uniformMap.put("ExternalLodRenderParams", ExternalTerrainRenderBridge::getRenderParams);
        }

    }
}
