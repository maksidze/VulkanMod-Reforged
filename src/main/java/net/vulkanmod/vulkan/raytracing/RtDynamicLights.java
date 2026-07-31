package net.vulkanmod.vulkan.raytracing;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.RayTracingBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;

/**
 * Maintains emissive block sources by render section and uploads a spatially hashed
 * light list for terrain ray queries.
 */
public final class RtDynamicLights {
    public static final int HASH_TABLE_SIZE = 8192;
    public static final int MAX_BUFFER_LIGHTS = 65536;
    public static final float CELL_SIZE = 32.0F;

    private static final int HEADER_BYTES = 4 * Integer.BYTES;
    private static final int CELL_BYTES = 4 * Integer.BYTES;
    private static final int LIGHT_BYTES = 8 * Float.BYTES;
    private static final int CELL_TABLE_BYTES = HASH_TABLE_SIZE * CELL_BYTES;
    private static final int LIGHT_DATA_OFFSET = HEADER_BYTES + CELL_TABLE_BYTES;
    private static final int BUFFER_BYTES = LIGHT_DATA_OFFSET + MAX_BUFFER_LIGHTS * LIGHT_BYTES;
    private static final int MAX_CELL_LIGHTS = 0xFFF;

    private static final Map<RenderSection, List<SourceLight>> SECTION_LIGHTS = new IdentityHashMap<>();
    private static final ByteBuffer STAGING = MemoryUtil.memAlloc(BUFFER_BYTES).order(ByteOrder.nativeOrder());

    private static RayTracingBuffer[] lightBuffers;
    private static int activeLightCount;
    private static boolean warnedCapacity;

    private RtDynamicLights() {
    }

    public static SourceLight createBlockLight(BlockState state, BlockPos position) {
        int emission = state.getLightEmission();
        if (emission <= 0) {
            return null;
        }

        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String path = id == null ? "" : id.getPath();
        double x = position.getX() + 0.5;
        double y = position.getY() + sourceHeight(path);
        double z = position.getZ() + 0.5;
        if (path.contains("wall_torch") && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            x += facing.getStepX() * 0.28;
            z += facing.getStepZ() * 0.28;
        }

        return createSource(x, y, z, emission, path, false);
    }

    public static void updateSectionLights(RenderSection section, List<SourceLight> sources) {
        if (sources == null || sources.isEmpty()) {
            SECTION_LIGHTS.remove(section);
        } else {
            SECTION_LIGHTS.put(section, List.copyOf(sources));
        }
    }

    public static void removeSection(RenderSection section) {
        SECTION_LIGHTS.remove(section);
    }

    public static void update(ClientLevel level, Camera camera, float partialTick) {
        ensureBuffers();
        if (!Initializer.CONFIG.rayTracingDynamicLights || level == null || camera == null) {
            upload(List.of(), level == null ? 0.0 : level.getGameTime() + partialTick);
            return;
        }

        Vec3 cameraPosition = camera.getPosition();
        int configuredDistance = Initializer.CONFIG.rayTracingDynamicLightDistance;
        boolean unlimitedDistance = configuredDistance <= 0;
        double searchDistance = Math.max(8, Math.min(256, configuredDistance));
        double searchDistanceSquared = searchDistance * searchDistance;
        List<SourceLight> candidates = new ArrayList<>();

        for (List<SourceLight> sectionSources : SECTION_LIGHTS.values()) {
            for (SourceLight source : sectionSources) {
                if (unlimitedDistance || source.distanceSquared(cameraPosition) <= searchDistanceSquared) {
                    candidates.add(source);
                }
            }
        }

        for (Player player : level.players()) {
            SourceLight held = heldLight(player, partialTick);
            if (held != null
                    && (unlimitedDistance || held.distanceSquared(cameraPosition) <= searchDistanceSquared)) {
                candidates.add(held);
            }
        }

        int configuredLimit = sanitizeLightLimit(Initializer.CONFIG.rayTracingDynamicLightCount);
        int uploadLimit = configuredLimit == 0 ? MAX_BUFFER_LIGHTS : configuredLimit;
        if (candidates.size() > uploadLimit) {
            candidates.sort(Comparator.comparingDouble(
                    (SourceLight light) -> light.score(cameraPosition)
            ).reversed());
            candidates.subList(uploadLimit, candidates.size()).clear();
            if (configuredLimit == 0 && !warnedCapacity) {
                warnedCapacity = true;
                Initializer.LOGGER.warn(
                        "RT dynamic-light Unlimited mode reached the {}-source GPU buffer capacity",
                        MAX_BUFFER_LIGHTS
                );
            }
        }

        upload(candidates, level.getGameTime() + partialTick);
    }

    public static long getBufferHandle() {
        RayTracingBuffer buffer = currentBuffer();
        return buffer == null ? VK_NULL_HANDLE : buffer.getId();
    }

    public static long getBufferSize() {
        RayTracingBuffer buffer = currentBuffer();
        return buffer == null ? 0L : buffer.getBufferSize();
    }

    public static int getActiveLightCount() {
        return activeLightCount;
    }

    public static void cleanUp() {
        SECTION_LIGHTS.clear();
        activeLightCount = 0;
        freeBuffers();
    }

    private static void ensureBuffers() {
        int frameCount = Math.max(1, Renderer.getFramesNum());
        if (lightBuffers == null || lightBuffers.length != frameCount) {
            freeBuffers();
            lightBuffers = new RayTracingBuffer[frameCount];
            for (int frame = 0; frame < frameCount; frame++) {
                lightBuffers[frame] = new RayTracingBuffer(
                        BUFFER_BYTES,
                        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        MemoryTypes.HOST_MEM
                );
            }
            Initializer.LOGGER.info(
                    "RT dynamic-light spatial buffers initialized: frames={} capacity={} hashCells={} size={} KiB each",
                    frameCount,
                    MAX_BUFFER_LIGHTS,
                    HASH_TABLE_SIZE,
                    BUFFER_BYTES / 1024
            );
        }
    }

    private static RayTracingBuffer currentBuffer() {
        if (lightBuffers == null || lightBuffers.length == 0) {
            return null;
        }
        int frame = Math.max(0, Math.min(Renderer.getCurrentFrame(), lightBuffers.length - 1));
        return lightBuffers[frame];
    }

    private static void freeBuffers() {
        if (lightBuffers == null) {
            return;
        }
        for (RayTracingBuffer buffer : lightBuffers) {
            if (buffer != null) {
                buffer.freeBuffer();
            }
        }
        lightBuffers = null;
    }

    private static void upload(List<SourceLight> sources, double animationTime) {
        Map<CellKey, List<FrameLight>> cells = new HashMap<>();
        for (SourceLight source : sources) {
            FrameLight frameLight = source.atTime(animationTime);
            CellKey key = CellKey.from(frameLight.x, frameLight.y, frameLight.z);
            cells.computeIfAbsent(key, ignored -> new ArrayList<>()).add(frameLight);
        }

        MemoryUtil.memSet(MemoryUtil.memAddress(STAGING), 0, HEADER_BYTES);
        MemoryUtil.memSet(
                MemoryUtil.memAddress(STAGING) + HEADER_BYTES,
                0xFF,
                CELL_TABLE_BYTES
        );

        int lightIndex = 0;
        int occupiedCells = 0;
        for (Map.Entry<CellKey, List<FrameLight>> entry : cells.entrySet()) {
            if (lightIndex >= MAX_BUFFER_LIGHTS) {
                break;
            }
            entry.getValue().sort(Comparator.comparingDouble(
                    (FrameLight light) -> light.stablePriority
            ).reversed());
            int count = Math.min(
                    Math.min(entry.getValue().size(), MAX_CELL_LIGHTS),
                    MAX_BUFFER_LIGHTS - lightIndex
            );
            if (count <= 0 || !insertCell(entry.getKey(), lightIndex, count)) {
                continue;
            }
            occupiedCells++;
            for (int index = 0; index < count; index++) {
                writeLight(lightIndex++, entry.getValue().get(index));
            }
        }

        STAGING.putInt(0, lightIndex);
        STAGING.putInt(4, HASH_TABLE_SIZE);
        STAGING.putInt(8, occupiedCells);
        STAGING.putInt(12, 0);
        STAGING.position(0);
        STAGING.limit(LIGHT_DATA_OFFSET + lightIndex * LIGHT_BYTES);
        RayTracingBuffer buffer = currentBuffer();
        if (buffer != null) {
            buffer.upload(STAGING);
        }
        STAGING.clear();
        activeLightCount = lightIndex;
    }

    private static boolean insertCell(CellKey key, int start, int count) {
        int slot = cellHash(key.x, key.y, key.z) & (HASH_TABLE_SIZE - 1);
        int packedRange = start | (count << 20);
        for (int probe = 0; probe < 32; probe++) {
            int entryOffset = HEADER_BYTES + slot * CELL_BYTES;
            if (STAGING.getInt(entryOffset + 12) == -1) {
                STAGING.putInt(entryOffset, key.x);
                STAGING.putInt(entryOffset + 4, key.y);
                STAGING.putInt(entryOffset + 8, key.z);
                STAGING.putInt(entryOffset + 12, packedRange);
                return true;
            }
            slot = (slot + 1) & (HASH_TABLE_SIZE - 1);
        }
        return false;
    }

    private static void writeLight(int index, FrameLight light) {
        int offset = LIGHT_DATA_OFFSET + index * LIGHT_BYTES;
        STAGING.putFloat(offset, (float) light.x);
        STAGING.putFloat(offset + 4, (float) light.y);
        STAGING.putFloat(offset + 8, (float) light.z);
        STAGING.putFloat(offset + 12, light.radius);
        STAGING.putFloat(offset + 16, light.red);
        STAGING.putFloat(offset + 20, light.green);
        STAGING.putFloat(offset + 24, light.blue);
        STAGING.putFloat(offset + 28, light.intensity);
    }

    private static SourceLight heldLight(Player player, float partialTick) {
        ItemLight mainHand = itemLight(player.getMainHandItem());
        ItemLight offHand = itemLight(player.getOffhandItem());
        ItemLight selected;
        if (mainHand == null) {
            selected = offHand;
        } else if (offHand == null || mainHand.emission >= offHand.emission) {
            selected = mainHand;
        } else {
            selected = offHand;
        }
        if (selected == null) {
            return null;
        }

        Vec3 position = player.getEyePosition(partialTick)
                .add(player.getViewVector(partialTick).scale(0.42))
                .add(0.0, -0.32, 0.0);
        return createSource(
                position.x,
                position.y,
                position.z,
                selected.emission,
                selected.path,
                true
        );
    }

    private static ItemLight itemLight(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }

        int emission = 0;
        if (stack.getItem() instanceof BlockItem blockItem) {
            emission = blockItem.getBlock().defaultBlockState().getLightEmission();
        } else if (stack.is(Items.LAVA_BUCKET)) {
            emission = 15;
        } else if (stack.is(Items.BLAZE_ROD) || stack.is(Items.BLAZE_POWDER)) {
            emission = 12;
        } else if (stack.is(Items.MAGMA_CREAM)) {
            emission = 10;
        } else if (stack.is(Items.GLOW_BERRIES) || stack.is(Items.GLOW_INK_SAC)) {
            emission = 8;
        }
        if (emission <= 0) {
            return null;
        }

        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return new ItemLight(emission, id == null ? "" : id.getPath());
    }

    private static SourceLight createSource(
            double x,
            double y,
            double z,
            int emission,
            String path,
            boolean held
    ) {
        float[] color = lightColor(path);
        float normalizedEmission = Math.min(15, Math.max(1, emission)) / 15.0F;
        float radius = held
                ? 10.0F + emission * 1.35F
                : 2.5F + emission * 0.85F;
        boolean flickers = path.contains("torch")
                || path.contains("campfire")
                || path.contains("fire")
                || path.contains("candle");
        int phaseHash = cellHash((int) Math.floor(x * 7.0), (int) Math.floor(y * 11.0), (int) Math.floor(z * 13.0));
        float phase = (phaseHash & 0xFFFF) * (6.2831855F / 65536.0F);
        return new SourceLight(
                x, y, z, radius,
                color[0], color[1], color[2], normalizedEmission,
                held, flickers, phase
        );
    }

    private static double sourceHeight(String path) {
        if (path.contains("campfire")) return 0.72;
        if (path.contains("torch") || path.contains("lantern")) return 0.72;
        if (path.contains("candle")) return 0.52;
        if (path.contains("fire")) return 0.58;
        if (path.contains("lava")) return 0.82;
        return 0.5;
    }

    private static float[] lightColor(String path) {
        if (path.contains("soul")) return new float[]{0.20F, 0.65F, 1.00F};
        if (path.contains("redstone")) return new float[]{1.00F, 0.08F, 0.025F};
        if (path.contains("sea_lantern") || path.contains("conduit") || path.contains("prismarine")) {
            return new float[]{0.55F, 0.88F, 1.00F};
        }
        if (path.contains("end_rod") || path.contains("end_crystal")) {
            return new float[]{0.78F, 0.68F, 1.00F};
        }
        if (path.contains("lava") || path.contains("fire") || path.contains("magma") || path.contains("blaze")) {
            return new float[]{1.00F, 0.26F, 0.045F};
        }
        if (path.contains("glowstone") || path.contains("torch") || path.contains("lantern")
                || path.contains("campfire") || path.contains("candle")) {
            return new float[]{1.00F, 0.56F, 0.20F};
        }
        return new float[]{1.00F, 0.72F, 0.40F};
    }

    private static int sanitizeLightLimit(int value) {
        if (value <= 0) return 0;
        if (value <= 1) return 1;
        if (value <= 2) return 2;
        if (value <= 4) return 4;
        if (value <= 16) return 16;
        if (value <= 32) return 32;
        if (value <= 64) return 64;
        if (value <= 128) return 128;
        if (value <= 256) return 256;
        return 512;
    }

    private static int cellHash(int x, int y, int z) {
        return x * 73_856_093 ^ y * 19_349_663 ^ z * 83_492_791;
    }

    public record SourceLight(
            double x,
            double y,
            double z,
            float radius,
            float red,
            float green,
            float blue,
            float intensity,
            boolean held,
            boolean flickers,
            float phase
    ) {
        private double distanceSquared(Vec3 position) {
            double dx = x - position.x;
            double dy = y - position.y;
            double dz = z - position.z;
            return dx * dx + dy * dy + dz * dz;
        }

        private double score(Vec3 cameraPosition) {
            double heldPriority = held ? 8.0 : 1.0;
            return heldPriority * intensity * radius * radius / (distanceSquared(cameraPosition) + 4.0);
        }

        private FrameLight atTime(double time) {
            float stablePriority = intensity * radius * radius;
            if (!flickers) {
                return new FrameLight(
                        x, y, z, radius,
                        red, green, blue, intensity,
                        stablePriority
                );
            }
            double waveA = Math.sin(time * 0.37 + phase);
            double waveB = Math.sin(time * 1.13 + phase * 1.71);
            float brightness = (float) (0.96 + waveA * 0.025 + waveB * 0.012);
            float hueShift = (float) (waveB * 0.018);
            return new FrameLight(
                    x,
                    y + waveA * 0.012,
                    z,
                    radius * (0.985F + brightness * 0.015F),
                    red,
                    Math.max(0.0F, green * (1.0F + hueShift)),
                    Math.max(0.0F, blue * (1.0F - hueShift)),
                    intensity * brightness,
                    stablePriority
            );
        }
    }

    private record FrameLight(
            double x,
            double y,
            double z,
            float radius,
            float red,
            float green,
            float blue,
            float intensity,
            float stablePriority
    ) {
    }

    private record ItemLight(int emission, String path) {
    }

    private record CellKey(int x, int y, int z) {
        private static CellKey from(double x, double y, double z) {
            return new CellKey(
                    (int) Math.floor(x / CELL_SIZE),
                    (int) Math.floor(y / CELL_SIZE),
                    (int) Math.floor(z / CELL_SIZE)
            );
        }
    }
}
