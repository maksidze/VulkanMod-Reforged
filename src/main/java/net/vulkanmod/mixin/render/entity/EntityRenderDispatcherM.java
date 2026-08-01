package net.vulkanmod.mixin.render.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LevelReader;
import net.vulkanmod.Initializer;
import net.vulkanmod.compat.render.GuiEntityRenderState;
import net.vulkanmod.vulkan.raytracing.RayTracingManager;
import net.vulkanmod.vulkan.raytracing.RtEntityGeometry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherM {
    @Inject(method = "render", at = @At("HEAD"))
    private <E extends Entity> void vulkanMod$beginRtMeshCapture(E entity, double x, double y, double z,
                                                                  float rotationYaw, float partialTicks,
                                                                  PoseStack poseStack, MultiBufferSource buffer,
                                                                  int packedLight, CallbackInfo ci) {
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        RtEntityGeometry.begin(entity, camera.getPosition().x, camera.getPosition().y, camera.getPosition().z);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private <E extends Entity> void vulkanMod$finishRtMeshCapture(E entity, double x, double y, double z,
                                                                   float rotationYaw, float partialTicks,
                                                                   PoseStack poseStack, MultiBufferSource buffer,
                                                                   int packedLight, CallbackInfo ci) {
        RtEntityGeometry.finish(entity);
    }
    @Inject(method = "renderShadow", at = @At("HEAD"), cancellable = true)
    private static void vulkanMod$skipVanillaEntityShadow(
            PoseStack poseStack,
            MultiBufferSource buffer,
            Entity entity,
            float opacity,
            float partialTick,
            LevelReader level,
            float radius,
            CallbackInfo ci
    ) {
        if (Initializer.CONFIG.rayTracingDirectLighting && RayTracingManager.getTopLevelHandle() != 0) {
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private <E extends Entity> void vulkanMod$deferGuiEntityRenderStateBoundary(E entity, double x, double y, double z,
                                                                                float rotationYaw, float partialTicks,
                                                                                PoseStack poseStack, MultiBufferSource buffer,
                                                                                int packedLight, CallbackInfo ci) {
        if (entity instanceof LivingEntity && GuiEntityRenderState.isGuiEntityPreview(packedLight)) {
            GuiEntityRenderState.prepareDeferredDraw();
        }
    }

}
