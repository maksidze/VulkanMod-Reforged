package net.vulkanmod.vulkan.raytracing;

import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** Compact material metadata stored in the unused high byte of terrain normals. */
public final class RtMaterial {
    public static final int OPAQUE = 0;
    public static final int WATER = 1;
    public static final int LEAVES = 2;
    public static final int LAVA = 3;
    public static final int FIRE = 4;
    public static final int EMISSIVE = 5;

    private RtMaterial() {
    }

    public static int encodeBlock(BlockState state) {
        int material = OPAQUE;

        if (state.getBlock() instanceof LeavesBlock) {
            material = LEAVES;
        } else if (state.getBlock() instanceof BaseFireBlock) {
            material = FIRE;
        }

        int emission = Mth.clamp(state.getLightEmission(), 0, 15);
        if (material == OPAQUE && emission > 0) {
            material = EMISSIVE;
        }

        return (material & 0x7) | (emission << 3);
    }

    public static int encodeFluid(BlockState state, FluidState fluidState) {
        int material = OPAQUE;
        if (fluidState.is(FluidTags.WATER)) {
            material = WATER;
        } else if (fluidState.is(FluidTags.LAVA)) {
            material = LAVA;
        }

        int emission = Mth.clamp(state.getLightEmission(), 0, 15);
        return (material & 0x7) | (emission << 3);
    }
}
