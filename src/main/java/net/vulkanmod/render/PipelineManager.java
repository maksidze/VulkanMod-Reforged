package net.vulkanmod.render;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.vulkanmod.compat.external.ExternalRenderPathSupport;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.raytracing.RayTracingManager;

import java.util.function.Function;

import static net.vulkanmod.vulkan.shader.SPIRVUtils.compileShaderAbsoluteFile;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;

public abstract class PipelineManager {
    private static final String shaderPath = "/assets/vulkanmod/shaders/";
    public static VertexFormat TERRAIN_VERTEX_FORMAT;

    public static void setTerrainVertexFormat(VertexFormat format) {
        TERRAIN_VERTEX_FORMAT = format;
    }

    static GraphicsPipeline terrainShaderEarlyZ, terrainShader, terrainShaderRt, fastBlitPipeline, renderScaleBlitPipeline, externalLodPipeline;

    private static Function<TerrainRenderType, GraphicsPipeline> shaderGetter;
    private static boolean loggedRayQueryPipeline;

    public static void init() {
        setTerrainVertexFormat(CustomVertexFormat.COMPRESSED_TERRAIN);
        createBasicPipelines();
        setDefaultShader();
        ThreadBuilderPack.defaultTerrainBuilderConstructor();
    }

    public static void setDefaultShader() {
        setShaderGetter(renderType -> {
            boolean tracedLayer = renderType == TerrainRenderType.SOLID
                    || renderType == TerrainRenderType.CUTOUT_MIPPED
                    || renderType == TerrainRenderType.CUTOUT;
            if (tracedLayer && terrainShaderRt != null && RayTracingManager.getTopLevelHandle() != 0) {
                if (!loggedRayQueryPipeline) {
                    loggedRayQueryPipeline = true;
                    Initializer.LOGGER.info("RT terrain ray-query pipeline active: TLAS instances={}",
                            RayTracingManager.getInstanceCount());
                }
                return terrainShaderRt;
            }
            return terrainShader;
        });
    }

    private static void createBasicPipelines() {
        terrainShaderEarlyZ = createPipeline("terrain","terrain", "terrain_z", TERRAIN_VERTEX_FORMAT);
        terrainShader = createPipeline("terrain", "terrain", "terrain", TERRAIN_VERTEX_FORMAT);
        if (RayTracingManager.shouldEnableRayQueryPass()) {
            terrainShaderRt = createPipeline("terrain", "terrain", "terrain_rt", TERRAIN_VERTEX_FORMAT, true);
        }
        fastBlitPipeline = createPipeline("blit", "blit", "blit", CustomVertexFormat.NONE);
        renderScaleBlitPipeline = createPipeline("render_scale_blit", "render_scale_blit", "render_scale_blit", CustomVertexFormat.NONE);
        if (ExternalRenderPathSupport.shouldCreateExternalLodPipeline()) {
            externalLodPipeline = createPipeline("external_lod", "lod", "lod", CustomVertexFormat.EXTERNAL_LOD);
        }
    }

    private static GraphicsPipeline createPipeline(String baseName, String vertName, String fragName,VertexFormat vertexFormat) {
        return createPipeline(baseName, vertName, fragName, vertexFormat, false);
    }

    private static GraphicsPipeline createPipeline(
            String baseName,
            String vertName,
            String fragName,
            VertexFormat vertexFormat,
            boolean rayQuery
    ) {
        String pathB = String.format("basic/%s/%s", baseName, baseName);
        String pathV = String.format("basic/%s/%s", baseName, vertName);
        String pathF = String.format("basic/%s/%s", baseName, fragName);

        Pipeline.Builder pipelineBuilder = new Pipeline.Builder(vertexFormat, pathB);
        pipelineBuilder.parseBindingsJSON();
        if (rayQuery) {
            pipelineBuilder.setAccelerationStructure(4, VK_SHADER_STAGE_FRAGMENT_BIT);
            pipelineBuilder.setStorageBuffer(5, VK_SHADER_STAGE_FRAGMENT_BIT);
        }

        SPIRVUtils.SPIRV vertShaderSPIRV = compileShaderAbsoluteFile(String.format("%s%s.vsh", shaderPath, pathV), SPIRVUtils.ShaderKind.VERTEX_SHADER);
        SPIRVUtils.SPIRV fragShaderSPIRV = compileShaderAbsoluteFile(String.format("%s%s.fsh", shaderPath, pathF), SPIRVUtils.ShaderKind.FRAGMENT_SHADER);
        pipelineBuilder.setSPIRVs(vertShaderSPIRV, fragShaderSPIRV);

        return pipelineBuilder.createGraphicsPipeline();
    }

    public static GraphicsPipeline getTerrainShader(TerrainRenderType renderType) {
        return shaderGetter.apply(renderType);
    }

    public static void setShaderGetter(Function<TerrainRenderType, GraphicsPipeline> consumer) {
        shaderGetter = consumer;
    }

    public static GraphicsPipeline getTerrainDirectShader(RenderType renderType) {
        return terrainShader;
    }

    public static GraphicsPipeline getTerrainIndirectShader(RenderType renderType) {
        return terrainShaderEarlyZ;
    }

    public static GraphicsPipeline getFastBlitPipeline() { return fastBlitPipeline; }

    public static GraphicsPipeline getRenderScaleBlitPipeline() { return renderScaleBlitPipeline; }

    public static GraphicsPipeline getExternalLodPipeline() { return externalLodPipeline; }

    public static void destroyPipelines() {
        terrainShaderEarlyZ.cleanUp();
        terrainShader.cleanUp();
        if (terrainShaderRt != null) {
            terrainShaderRt.cleanUp();
            terrainShaderRt = null;
        }
        fastBlitPipeline.cleanUp();
        renderScaleBlitPipeline.cleanUp();
        if (externalLodPipeline != null) {
            externalLodPipeline.cleanUp();
        }
    }
}
