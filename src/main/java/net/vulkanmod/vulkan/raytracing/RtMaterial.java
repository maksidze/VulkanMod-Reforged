package net.vulkanmod.vulkan.raytracing;

import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.core.registries.BuiltInRegistries;
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
    public static final int REFLECTIVE = 6;

    private RtMaterial() {
    }

    public static int encodeBlock(BlockState state) {
        int material = OPAQUE;

        if (state.getBlock() instanceof LeavesBlock) {
            material = LEAVES;
        } else if (state.getBlock() instanceof BaseFireBlock) {
            material = FIRE;
        } else if (isReflectiveBlock(state)) {
            material = REFLECTIVE;
        }

        int emission = Mth.clamp(state.getLightEmission(), 0, 15);
        if (material == OPAQUE && emission > 0) {
            material = EMISSIVE;
        }

        return (material & 0x7) | (emission << 3);
    }

    /**
     * A deliberately small set of naturally glossy vanilla surfaces. Keeping the
     * classification selective avoids firing a reflection ray for every terrain
     * pixel while still covering the materials players expect to be reflective.
     */
    private static boolean isReflectiveBlock(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return id.endsWith("_ice")
                || id.equals("ice")
                || id.equals("glass")
                || id.endsWith("_glass")
                || id.endsWith("_glass_pane")
                || id.endsWith("_glazed_terracotta")
                || id.endsWith("_metal_block")
                || id.equals("iron_block")
                || id.equals("gold_block")
                || id.equals("diamond_block")
                || id.equals("emerald_block")
                || id.equals("netherite_block")
                || id.endsWith("_copper_block")
                || id.endsWith("_copper")
                || id.startsWith("exposed_") && id.endsWith("_copper")
                || id.startsWith("weathered_") && id.endsWith("_copper")
                || id.startsWith("oxidized_") && id.endsWith("_copper");
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
