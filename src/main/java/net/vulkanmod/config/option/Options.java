package net.vulkanmod.config.option;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.*;
import net.minecraft.network.chat.Component;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.GraphicsModeCompatibility;
import net.vulkanmod.config.PerformancePreset;
import net.vulkanmod.config.PerformancePresetApplier;
import net.vulkanmod.config.RenderScale;
import net.vulkanmod.config.gui.OptionBlock;

import net.vulkanmod.config.video.VideoModeManager;
import net.vulkanmod.config.video.VideoModeSet;
import net.vulkanmod.config.video.WindowMode;
import net.vulkanmod.render.chunk.build.light.LightMode;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

public abstract class Options {
    public static boolean fullscreenDirty = false;
    static Config config = Initializer.CONFIG;
    static Minecraft minecraft = Minecraft.getInstance();
    static Window window = minecraft.getWindow();
    static net.minecraft.client.Options minecraftOptions = minecraft.options;

    private static void markPerformancePresetCustom() {
        config.performancePreset = PerformancePreset.CUSTOM.id;
    }

    public static List<OptionPage> getOptionPages() {
        List<OptionPage> optionPages = new ArrayList<>();

        optionPages.add(new OptionPage(
                Component.translatable("vulkanmod.options.pages.video").getString(),
                Options.getVideoOpts()));

        optionPages.add(new OptionPage(
                Component.translatable("vulkanmod.options.pages.graphics").getString(),
                Options.getGraphicsOpts()));

        optionPages.add(new OptionPage(
                Component.translatable("vulkanmod.options.pages.rayTracing").getString(),
                Options.getRayTracingOpts()));

        optionPages.add(new OptionPage(
                Component.translatable("vulkanmod.options.pages.optimizations").getString(),
                Options.getOptimizationOpts()));

        optionPages.add(new OptionPage(
                Component.translatable("vulkanmod.options.pages.other").getString(),
                Options.getOtherOpts()));

        return optionPages;
    }

    public static OptionBlock[] getVideoOpts() {
        var videoMode = config.videoMode;
        var videoModeSet = VideoModeManager.getFromVideoMode(videoMode);

        if (videoModeSet == null) {
            videoModeSet = VideoModeSet.getDummy();
            videoMode = videoModeSet.getVideoMode(-1);
        }

        VideoModeManager.selectedVideoMode = videoMode;
        var refreshRates = videoModeSet.getRefreshRates();

        CyclingOption<Integer> RefreshRate = (CyclingOption<Integer>) new CyclingOption<>(
                Component.translatable("vulkanmod.options.refreshRate"),
                refreshRates.toArray(new Integer[0]),
                (value) -> {
                    VideoModeManager.selectedVideoMode.refreshRate = value;
                    VideoModeManager.applySelectedVideoMode();

                    if (minecraftOptions.fullscreen().get())
                        fullscreenDirty = true;
                },
                () -> VideoModeManager.selectedVideoMode.refreshRate)
                .setTranslator(refreshRate -> Component.nullToEmpty(refreshRate.toString()));

        Option<VideoModeSet> resolutionOption = new CyclingOption<>(
                Component.translatable("options.fullscreen.resolution"),
                VideoModeManager.getVideoResolutions(),
                (value) -> {
                    VideoModeManager.selectedVideoMode = value.getVideoMode(RefreshRate.getNewValue());
                    VideoModeManager.applySelectedVideoMode();

                    if (minecraftOptions.fullscreen().get())
                        fullscreenDirty = true;
                },
                () -> {
                    var selectedVideoMode = VideoModeManager.selectedVideoMode;
                    var selectedVideoModeSet = VideoModeManager.getFromVideoMode(selectedVideoMode);

                    return selectedVideoModeSet != null ? selectedVideoModeSet : VideoModeSet.getDummy();
                })
                .setTranslator(resolution -> Component.nullToEmpty(resolution.toString()));

        resolutionOption.setOnChange(() -> {
            var newVideoMode = resolutionOption.getNewValue();
            var newRefreshRates = newVideoMode.getRefreshRates().toArray(new Integer[0]);

            RefreshRate.setValues(newRefreshRates);
            RefreshRate.setNewValue(newRefreshRates[newRefreshRates.length - 1]);
        });

        // Fork mapping: fullscreen=true -> exclusive; else windowedFullscreen flag -> borderless
        var windowModeOption = new CyclingOption<>(Component.translatable("vulkanmod.options.windowMode"),
                WindowMode.values(),
                value -> {
                    minecraftOptions.fullscreen().set(value == WindowMode.EXCLUSIVE_FULLSCREEN);
                    config.windowedFullscreen = (value == WindowMode.WINDOWED_FULLSCREEN);
                    fullscreenDirty = true;
                },
                () -> minecraftOptions.fullscreen().get() ? WindowMode.EXCLUSIVE_FULLSCREEN
                        : config.windowedFullscreen ? WindowMode.WINDOWED_FULLSCREEN
                        : WindowMode.WINDOWED)
                .setTranslator(value -> Component.translatable(WindowMode.getComponentName(value)));

        resolutionOption.setActivationFn(() -> windowModeOption.getNewValue() == WindowMode.EXCLUSIVE_FULLSCREEN);
        RefreshRate.setActivationFn(() -> windowModeOption.getNewValue() == WindowMode.EXCLUSIVE_FULLSCREEN);

        windowModeOption.setOnChange(() -> {
            resolutionOption.updateActiveState();
            RefreshRate.updateActiveState();
        });

        return new OptionBlock[]{
                new OptionBlock("", new Option<?>[]{
                        windowModeOption,
                        resolutionOption,
                        RefreshRate,
                        new RangeOption(Component.translatable("options.framerateLimit"),
                                10, 260, 10,
                                value -> Component.nullToEmpty(value == 260 ?
                                        Component.translatable("options.framerateLimit.max").getString() :
                                        String.valueOf(value)),
                                value -> {
                                    minecraftOptions.framerateLimit().set(value);
                                    window.setFramerateLimit(value);
                                },
                                () -> minecraftOptions.framerateLimit().get()),
                        new SwitchOption(Component.translatable("options.vsync"),
                                value -> {
                                    minecraftOptions.enableVsync().set(value);
                                    window.updateVsync(value);
                                },
                                () -> minecraftOptions.enableVsync().get()),
                }),
                new OptionBlock("", new Option<?>[]{
                        new RangeOption(Component.translatable("options.guiScale"),
                                0, window.calculateScale(0, minecraft.isEnforceUnicode()), 1,
                                value -> Component.translatable((value == 0)
                                        ? "options.guiScale.auto"
                                        : String.valueOf(value)),
                                value -> {
                                    minecraftOptions.guiScale().set(value);
                                    minecraft.resizeDisplay();
                                },
                                () -> (minecraftOptions.guiScale().get())),
                        new RangeOption(Component.translatable("options.gamma"),
                                0, 100, 1,
                                value -> Component.translatable(switch (value) {
                                    case 0 -> "options.gamma.min";
                                    case 50 -> "options.gamma.default";
                                    case 100 -> "options.gamma.max";
                                    default -> String.valueOf(value);
                                }),
                                value -> minecraftOptions.gamma().set(value * 0.01),
                                () -> (int) (minecraftOptions.gamma().get() * 100.0)),
                }),
                new OptionBlock("", new Option<?>[]{
                        new SwitchOption(Component.translatable("options.viewBobbing"),
                                (value) -> minecraftOptions.bobView().set(value),
                                () -> minecraftOptions.bobView().get()),
                        new CyclingOption<>(Component.translatable("options.attackIndicator"),
                                AttackIndicatorStatus.values(),
                                value -> minecraftOptions.attackIndicator().set(value),
                                () -> minecraftOptions.attackIndicator().get())
                                .setTranslator(value -> Component.translatable(value.getKey())),
                        new SwitchOption(Component.translatable("options.autosaveIndicator"),
                                value -> minecraftOptions.showAutosaveIndicator().set(value),
                                () -> minecraftOptions.showAutosaveIndicator().get()),
                })
        };
    }

    public static OptionBlock[] getGraphicsOpts() {
        return new OptionBlock[]{
                new OptionBlock("", new Option<?>[]{
                        new RangeOption(Component.translatable("options.renderDistance"),
                                2, 32, 1,
                                (value) -> {
                                    markPerformancePresetCustom();
                                    minecraftOptions.renderDistance().set(value);
                                },
                                () -> minecraftOptions.renderDistance().get())
                                .setImpact(PerformanceImpact.HIGH),
                        new RangeOption(Component.translatable("options.simulationDistance"),
                                5, 32, 1,
                                (value) -> {
                                    markPerformancePresetCustom();
                                    minecraftOptions.simulationDistance().set(value);
                                },
                                () -> minecraftOptions.simulationDistance().get()),
                        new CyclingOption<>(Component.translatable("options.prioritizeChunkUpdates"),
                                PrioritizeChunkUpdates.values(),
                                value -> minecraftOptions.prioritizeChunkUpdates().set(value),
                                () -> minecraftOptions.prioritizeChunkUpdates().get())
                                .setTranslator(value -> Component.translatable(value.getKey())),
                }),
                new OptionBlock("", new Option<?>[]{
                        new CyclingOption<>(Component.translatable("options.graphics"),
                                GraphicsModeCompatibility.supportedModes(),
                                value -> {
                                    markPerformancePresetCustom();
                                    minecraftOptions.graphicsMode().set(GraphicsModeCompatibility.coerce(value));
                                },
                                () -> GraphicsModeCompatibility.coerce(minecraftOptions.graphicsMode().get()))
                                .setTranslator(graphicsMode -> Component.translatable(graphicsMode.getKey())),
                        new CyclingOption<>(Component.translatable("options.particles"),
                                new ParticleStatus[]{ParticleStatus.MINIMAL, ParticleStatus.DECREASED, ParticleStatus.ALL},
                                value -> {
                                    markPerformancePresetCustom();
                                    minecraftOptions.particles().set(value);
                                },
                                () -> minecraftOptions.particles().get())
                                .setTranslator(particlesMode -> Component.translatable(particlesMode.getKey()))
                                .setImpact(PerformanceImpact.MEDIUM),
                        new CyclingOption<>(Component.translatable("options.renderClouds"),
                                CloudStatus.values(),
                                value -> {
                                    markPerformancePresetCustom();
                                    minecraftOptions.cloudStatus().set(value);
                                },
                                () -> minecraftOptions.cloudStatus().get())
                                .setTranslator(value -> Component.translatable(value.getKey())),
                        new CyclingOption<>(Component.translatable("options.ao"),
                                new Integer[]{LightMode.FLAT, LightMode.SMOOTH, LightMode.SUB_BLOCK},
                                (value) -> {
                                    markPerformancePresetCustom();

                                    if (value > LightMode.FLAT)
                                        minecraftOptions.ambientOcclusion().set(true);
                                    else
                                        minecraftOptions.ambientOcclusion().set(false);

                                    config.ambientOcclusion = value;

                                    minecraft.levelRenderer.allChanged();
                                },
                                () -> config.ambientOcclusion)
                                .setTranslator(value -> Component.translatable(switch (value) {
                                    case LightMode.FLAT -> "options.off";
                                    case LightMode.SMOOTH -> "options.on";
                                    case LightMode.SUB_BLOCK -> "vulkanmod.options.ao.subBlock";
                                    default -> "vulkanmod.options.unknown";
                                }))
                                .setTooltip(Component.translatable("vulkanmod.options.ao.subBlock.tooltip"))
                                .setImpact(PerformanceImpact.LOW),
                        new RangeOption(Component.translatable("options.biomeBlendRadius"),
                                0, 7, 1,
                                value -> {
                                    int v = value * 2 + 1;
                                    return Component.nullToEmpty("%d x %d".formatted(v, v));
                                },
                                (value) -> {
                                    markPerformancePresetCustom();
                                    minecraftOptions.biomeBlendRadius().set(value);
                                    minecraft.levelRenderer.allChanged();
                                },
                                () -> minecraftOptions.biomeBlendRadius().get()),
                }),
                new OptionBlock("", new Option<?>[]{
                        new SwitchOption(Component.translatable("options.entityShadows"),
                                value -> {
                                    markPerformancePresetCustom();
                                    minecraftOptions.entityShadows().set(value);
                                },
                                () -> minecraftOptions.entityShadows().get())
                                .setImpact(PerformanceImpact.LOW),
                        new RangeOption(Component.translatable("options.entityDistanceScaling"),
                                50, 500, 25,
                                value -> {
                                    markPerformancePresetCustom();
                                    minecraftOptions.entityDistanceScaling().set(value * 0.01);
                                },
                                () -> (int) Math.round(minecraftOptions.entityDistanceScaling().get() * 100.0))
                                .setImpact(PerformanceImpact.HIGH),
                        new CyclingOption<>(Component.translatable("options.mipmapLevels"),
                                new Integer[]{0, 1, 2, 3, 4},
                                value -> {
                                    markPerformancePresetCustom();
                                    minecraftOptions.mipmapLevels().set(value);
                                    minecraft.updateMaxMipLevel(value);
                                    minecraft.delayTextureReload();
                                },
                                () -> minecraftOptions.mipmapLevels().get())
                                .setTranslator(value -> Component.nullToEmpty(value.toString()))
                                .setImpact(PerformanceImpact.LOW)
                })
        };
    }

    public static OptionBlock[] getOptimizationOpts() {
        return new OptionBlock[]{
                new OptionBlock("", new Option<?>[]{
                        new CyclingOption<>(Component.translatable("vulkanmod.options.performancePreset"),
                                PerformancePreset.values(),
                                value -> PerformancePresetApplier.apply(value, config, minecraft),
                                () -> PerformancePreset.byId(config.performancePreset))
                                .setTranslator(value -> Component.translatable(value.translationKey))
                                .setTooltip(Component.translatable("vulkanmod.options.performancePreset.tooltip"))
                }),
                new OptionBlock("", new Option[]{
                        new CyclingOption<>(Component.translatable("vulkanmod.options.advCulling"),
                                new Integer[]{1, 2, 3, 10},
                                value -> {
                                    markPerformancePresetCustom();
                                    config.advCulling = value;
                                },
                                () -> config.advCulling)
                                .setTranslator(value -> Component.translatable(switch (value) {
                                    case 1 -> "vulkanmod.options.advCulling.aggressive";
                                    case 2 -> "vulkanmod.options.advCulling.normal";
                                    case 3 -> "vulkanmod.options.advCulling.conservative";
                                    case 10 -> "options.off";
                                    default -> "vulkanmod.options.unknown";
                                }))
                                .setTooltip(Component.translatable("vulkanmod.options.advCulling.tooltip"))
                                .setImpact(PerformanceImpact.HIGH),
                        new SwitchOption(Component.translatable("vulkanmod.options.entityCulling"),
                                value -> {
                                    markPerformancePresetCustom();
                                    config.entityCulling = value;
                                },
                                () -> config.entityCulling)
                                .setTooltip(Component.translatable("vulkanmod.options.entityCulling.tooltip"))
                                .setImpact(PerformanceImpact.HIGH),
                        new SwitchOption(Component.translatable("vulkanmod.options.blockEntityCulling"),
                                value -> {
                                    markPerformancePresetCustom();
                                    config.blockEntityCulling = value;
                                },
                                () -> config.blockEntityCulling)
                                .setTooltip(Component.translatable("vulkanmod.options.blockEntityCulling.tooltip"))
                                .setImpact(PerformanceImpact.HIGH),
                        new SwitchOption(Component.translatable("vulkanmod.options.leavesCulling"),
                                value -> {
                                    markPerformancePresetCustom();
                                    config.leavesCulling = value;
                                    minecraft.levelRenderer.allChanged();
                                },
                                () -> config.leavesCulling)
                                .setTooltip(Component.translatable("vulkanmod.options.leavesCulling.tooltip"))
                                .setImpact(PerformanceImpact.HIGH),
                        new SwitchOption(Component.translatable("vulkanmod.options.uniqueOpaqueLayer"),
                                value -> {
                                    markPerformancePresetCustom();
                                    config.uniqueOpaqueLayer = value;
                                    minecraft.levelRenderer.allChanged();
                                },
                                () -> config.uniqueOpaqueLayer)
                                .setTooltip(Component.translatable("vulkanmod.options.uniqueOpaqueLayer.tooltip"))
                                .setImpact(PerformanceImpact.HIGH),
                        new SwitchOption(Component.translatable("vulkanmod.options.indirectDraw"),
                                value -> {
                                    markPerformancePresetCustom();
                                    config.indirectDraw = value && DeviceManager.supportsFastIndirectDraw();
                                },
                                () -> config.indirectDraw && DeviceManager.supportsFastIndirectDraw())
                                .setTooltip(Component.translatable("vulkanmod.options.indirectDraw.tooltip"))
                                .setImpact(PerformanceImpact.HIGH),
                        new SwitchOption(Component.translatable("vulkanmod.options.adaptiveChunkUploads"),
                                value -> {
                                    markPerformancePresetCustom();
                                    config.adaptiveChunkUploads = value;
                                },
                                () -> config.adaptiveChunkUploads)
                                .setTooltip(Component.translatable("vulkanmod.options.adaptiveChunkUploads.tooltip")),
                        new CyclingOption<>(Component.translatable("vulkanmod.options.particleCulling"),
                                new Integer[]{0, 1, 2, 3},
                                value -> {
                                    markPerformancePresetCustom();
                                    config.particleCulling = value;
                                },
                                () -> config.particleCulling)
                                .setTranslator(value -> Component.translatable(switch (value) {
                                    case 0 -> "options.off";
                                    case 1 -> "vulkanmod.options.particleCulling.quality";
                                    case 2 -> "vulkanmod.options.particleCulling.balanced";
                                    case 3 -> "vulkanmod.options.particleCulling.performance";
                                    default -> "vulkanmod.options.unknown";
                                }))
                                .setTooltip(Component.translatable("vulkanmod.options.particleCulling.tooltip")),
                })
        };

    }

    public static OptionBlock[] getRayTracingOpts() {
        CyclingOption<Integer> viewMode = new CyclingOption<>(
                Component.translatable("vulkanmod.options.rayTracing.viewMode"),
                new Integer[]{0, 1, 2},
                value -> {
                    config.rayTracingViewMode = sanitizeRtViewMode(value);
                    if (minecraft.levelRenderer != null) {
                        minecraft.levelRenderer.allChanged();
                    }
                },
                () -> sanitizeRtViewMode(config.rayTracingViewMode)
        );
        viewMode
                .setTranslator(value -> Component.translatable(switch (value) {
                    case 1 -> "vulkanmod.options.rayTracing.viewMode.rtOnly";
                    case 2 -> "vulkanmod.options.rayTracing.viewMode.minecraftOnly";
                    default -> "vulkanmod.options.rayTracing.viewMode.composite";
                }))
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.viewMode.tooltip"))
                .setActivationFn(DeviceManager::isRayQueryEnabled);

        CyclingOption<Integer> debugView = new CyclingOption<>(
                Component.translatable("vulkanmod.options.rayTracing.debugView"),
                new Integer[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
                value -> {
                    config.rayTracingDebugView = sanitizeRtDebugView(value);
                    if (minecraft.levelRenderer != null) {
                        minecraft.levelRenderer.allChanged();
                    }
                },
                () -> sanitizeRtDebugView(config.rayTracingDebugView)
        );
        debugView
                .setTranslator(value -> Component.translatable(switch (value) {
                    case 1 -> "vulkanmod.options.rayTracing.debugView.normals";
                    case 2 -> "vulkanmod.options.rayTracing.debugView.materials";
                    case 3 -> "vulkanmod.options.rayTracing.debugView.skyLight";
                    case 4 -> "vulkanmod.options.rayTracing.debugView.blockLight";
                    case 5 -> "vulkanmod.options.rayTracing.debugView.emission";
                    case 6 -> "vulkanmod.options.rayTracing.debugView.shadowVisibility";
                    case 7 -> "vulkanmod.options.rayTracing.debugView.alphaCutout";
                    case 8 -> "vulkanmod.options.rayTracing.debugView.reflectionDistance";
                    case 9 -> "vulkanmod.options.rayTracing.debugView.dynamicLights";
                    case 10 -> "vulkanmod.options.rayTracing.debugView.dynamicShadowBudget";
                    case 11 -> "vulkanmod.options.rayTracing.debugView.skyOcclusion";
                    default -> "options.off";
                }))
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.debugView.tooltip"))
                .setActivationFn(DeviceManager::isRayQueryEnabled);

        CyclingOption<Integer> shadowRays = new CyclingOption<>(
                Component.translatable("vulkanmod.options.rayTracing.shadowRays"),
                new Integer[]{1, 2, 4, 8},
                value -> {
                    config.rayTracingShadowRays = sanitizeShadowRayCount(value);
                    if (config.rayTracingShadowRays == 1) {
                        config.rayTracingShadowSoftness = 0;
                    }
                },
                () -> sanitizeShadowRayCount(config.rayTracingShadowRays)
        );
        shadowRays
                .setTranslator(value -> Component.literal(value.toString()))
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.shadowRays.tooltip"))
                .setImpact(PerformanceImpact.HIGH)
                .setActivationFn(DeviceManager::isRayQueryEnabled);

        RangeOption shadowSoftness = new RangeOption(
                Component.translatable("vulkanmod.options.rayTracing.shadowSoftness"),
                0,
                100,
                5,
                value -> Component.literal(value + "%"),
                value -> config.rayTracingShadowSoftness = config.rayTracingShadowRays > 1
                        ? Math.max(0, Math.min(100, value))
                        : 0,
                () -> config.rayTracingShadowRays > 1
                        ? Math.max(0, Math.min(100, config.rayTracingShadowSoftness))
                        : 0
        );
        shadowSoftness
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.shadowSoftness.tooltip"))
                .setActivationFn(() -> DeviceManager.isRayQueryEnabled() && shadowRays.getNewValue() > 1);

        shadowRays.setOnChange(() -> {
            if (shadowRays.getNewValue() == 1) {
                shadowSoftness.setNewValue(0);
            }
            shadowSoftness.updateActiveState();
        });

        RangeOption shadowDarkness = new RangeOption(
                Component.translatable("vulkanmod.options.rayTracing.shadowDarkness"),
                0,
                100,
                5,
                value -> Component.literal(value + "%"),
                value -> config.rayTracingShadowDarkness = Math.max(0, Math.min(100, value)),
                () -> Math.max(0, Math.min(100, config.rayTracingShadowDarkness))
        );
        shadowDarkness
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.shadowDarkness.tooltip"))
                .setActivationFn(DeviceManager::isRayQueryEnabled);

        CyclingOption<Integer> shadowDistance = new CyclingOption<>(
                Component.translatable("vulkanmod.options.rayTracing.shadowDistance"),
                new Integer[]{32, 64, 128, 256, 512, 0},
                value -> config.rayTracingShadowDistance = sanitizeUnlimitedDistance(value, 32, 512),
                () -> sanitizeUnlimitedDistance(config.rayTracingShadowDistance, 32, 512)
        );
        shadowDistance
                .setTranslator(Options::translateUnlimitedDistance)
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.shadowDistance.tooltip"))
                .setImpact(PerformanceImpact.MEDIUM)
                .setActivationFn(DeviceManager::isRayQueryEnabled);

        SwitchOption directLighting = new SwitchOption(
                Component.translatable("vulkanmod.options.rayTracing.directLighting"),
                value -> {
                    config.rayTracingDirectLighting = value;
                    if (minecraft.levelRenderer != null) {
                        minecraft.levelRenderer.allChanged();
                    }
                },
                () -> config.rayTracingDirectLighting
        );
        directLighting
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.directLighting.tooltip"))
                .setActivationFn(DeviceManager::isRayQueryEnabled);

        RangeOption sunLight = new RangeOption(
                Component.translatable("vulkanmod.options.rayTracing.sunLight"),
                0, 200, 10,
                value -> Component.literal(value + "%"),
                value -> config.rayTracingSunLight = Math.max(0, Math.min(200, value)),
                () -> Math.max(0, Math.min(200, config.rayTracingSunLight))
        );
        sunLight
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.sunLight.tooltip"))
                .setActivationFn(() -> DeviceManager.isRayQueryEnabled() && directLighting.getNewValue());

        RangeOption skyLight = new RangeOption(
                Component.translatable("vulkanmod.options.rayTracing.skyLight"),
                0, 200, 10,
                value -> Component.literal(value + "%"),
                value -> config.rayTracingSkyLight = Math.max(0, Math.min(200, value)),
                () -> Math.max(0, Math.min(200, config.rayTracingSkyLight))
        );
        skyLight
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.skyLight.tooltip"))
                .setActivationFn(() -> DeviceManager.isRayQueryEnabled() && directLighting.getNewValue());

        RangeOption blockLight = new RangeOption(
                Component.translatable("vulkanmod.options.rayTracing.blockLight"),
                0, 200, 10,
                value -> Component.literal(value + "%"),
                value -> config.rayTracingBlockLight = Math.max(0, Math.min(200, value)),
                () -> Math.max(0, Math.min(200, config.rayTracingBlockLight))
        );
        blockLight
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.blockLight.tooltip"))
                .setActivationFn(() -> DeviceManager.isRayQueryEnabled() && directLighting.getNewValue());

        directLighting.setOnChange(() -> {
            sunLight.updateActiveState();
            skyLight.updateActiveState();
            blockLight.updateActiveState();
        });

        SwitchOption skyOcclusion = new SwitchOption(
                Component.translatable("vulkanmod.options.rayTracing.skyOcclusion"),
                value -> config.rayTracingSkyOcclusion = value,
                () -> config.rayTracingSkyOcclusion
        );
        skyOcclusion
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.skyOcclusion.tooltip"))
                .setImpact(PerformanceImpact.HIGH)
                .setActivationFn(DeviceManager::isRayQueryEnabled);

        CyclingOption<Integer> skyRays = new CyclingOption<>(
                Component.translatable("vulkanmod.options.rayTracing.skyRays"),
                new Integer[]{1, 2, 4},
                value -> config.rayTracingSkyRays = sanitizeSkyRayCount(value),
                () -> sanitizeSkyRayCount(config.rayTracingSkyRays)
        );
        skyRays
                .setTranslator(value -> Component.literal(value.toString()))
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.skyRays.tooltip"))
                .setImpact(PerformanceImpact.HIGH)
                .setActivationFn(() -> DeviceManager.isRayQueryEnabled() && skyOcclusion.getNewValue());

        CyclingOption<Integer> skyDistance = new CyclingOption<>(
                Component.translatable("vulkanmod.options.rayTracing.skyDistance"),
                new Integer[]{16, 32, 64, 128, 256, 0},
                value -> config.rayTracingSkyDistance = sanitizeUnlimitedDistance(value, 16, 256),
                () -> sanitizeUnlimitedDistance(config.rayTracingSkyDistance, 16, 256)
        );
        skyDistance
                .setTranslator(Options::translateUnlimitedDistance)
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.skyDistance.tooltip"))
                .setImpact(PerformanceImpact.MEDIUM)
                .setActivationFn(() -> DeviceManager.isRayQueryEnabled() && skyOcclusion.getNewValue());

        CyclingOption<Integer> temporalFrames = new CyclingOption<>(
                Component.translatable("vulkanmod.options.rayTracing.temporalFrames"),
                new Integer[]{1, 4, 8, 16, 32, 64, 0},
                value -> config.rayTracingTemporalFrames = sanitizeTemporalFrames(value),
                () -> sanitizeTemporalFrames(config.rayTracingTemporalFrames)
        );
        temporalFrames
                .setTranslator(value -> {
                    if (value == 0) {
                        return Component.translatable("vulkanmod.options.rayTracing.unlimited");
                    }
                    if (value == 1) {
                        return Component.translatable("options.off");
                    }
                    return Component.literal(value.toString());
                })
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.temporalFrames.tooltip"))
                .setActivationFn(() -> DeviceManager.isRayQueryEnabled() && skyOcclusion.getNewValue());

        CyclingOption<Integer> skyDenoiser = new CyclingOption<>(
                Component.translatable("vulkanmod.options.rayTracing.skyDenoiser"),
                new Integer[]{0, 1, 2},
                value -> config.rayTracingSkyDenoiser = Math.max(0, Math.min(2, value)),
                () -> Math.max(0, Math.min(2, config.rayTracingSkyDenoiser))
        );
        skyDenoiser
                .setTranslator(value -> Component.translatable(switch (value) {
                    case 1 -> "vulkanmod.options.rayTracing.skyDenoiser.fast";
                    case 2 -> "vulkanmod.options.rayTracing.skyDenoiser.quality";
                    default -> "options.off";
                }))
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.skyDenoiser.tooltip"))
                .setImpact(PerformanceImpact.MEDIUM)
                .setActivationFn(() -> DeviceManager.isRayQueryEnabled() && skyOcclusion.getNewValue());

        skyOcclusion.setOnChange(() -> {
            skyRays.updateActiveState();
            skyDistance.updateActiveState();
            temporalFrames.updateActiveState();
            skyDenoiser.updateActiveState();
        });

        CyclingOption<Integer> entityProxyUpdateInterval = new CyclingOption<>(
                Component.translatable("vulkanmod.options.rayTracing.entityProxyUpdateInterval"),
                new Integer[]{1, 2, 4, 10, 20},
                value -> config.rayTracingEntityProxyUpdateInterval = Math.max(1, Math.min(20, value)),
                () -> Math.max(1, Math.min(20, config.rayTracingEntityProxyUpdateInterval))
        );
        entityProxyUpdateInterval
                .setTranslator(value -> value == 1
                        ? Component.translatable("vulkanmod.options.rayTracing.entityProxyUpdateInterval.frame")
                        : Component.literal(value + " frames"))
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.entityProxyUpdateInterval.tooltip"))
                .setImpact(PerformanceImpact.HIGH)
                .setActivationFn(DeviceManager::isRayQueryEnabled);

        SwitchOption dynamicLights = new SwitchOption(
                Component.translatable("vulkanmod.options.rayTracing.dynamicLights"),
                value -> config.rayTracingDynamicLights = value,
                () -> config.rayTracingDynamicLights
        );
        dynamicLights
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.dynamicLights.tooltip"))
                .setImpact(PerformanceImpact.HIGH)
                .setActivationFn(DeviceManager::isRayQueryEnabled);

        CyclingOption<Integer> dynamicLightCount = new CyclingOption<>(
                Component.translatable("vulkanmod.options.rayTracing.dynamicLightCount"),
                new Integer[]{1, 2, 4, 16, 32, 64, 128, 256, 512, 0},
                value -> config.rayTracingDynamicLightCount = sanitizeDynamicLightCount(value),
                () -> sanitizeDynamicLightCount(config.rayTracingDynamicLightCount)
        );
        dynamicLightCount
                .setTranslator(value -> value == 0
                        ? Component.translatable("vulkanmod.options.rayTracing.dynamicLightCount.unlimited")
                        : Component.literal(value.toString()))
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.dynamicLightCount.tooltip"))
                .setImpact(PerformanceImpact.HIGH)
                .setActivationFn(() -> DeviceManager.isRayQueryEnabled() && dynamicLights.getNewValue());

        CyclingOption<Integer> dynamicLightDistance = new CyclingOption<>(
                Component.translatable("vulkanmod.options.rayTracing.dynamicLightDistance"),
                new Integer[]{8, 16, 32, 64, 128, 256, 0},
                value -> config.rayTracingDynamicLightDistance = sanitizeUnlimitedDistance(value, 8, 256),
                () -> sanitizeUnlimitedDistance(config.rayTracingDynamicLightDistance, 8, 256)
        );
        dynamicLightDistance
                .setTranslator(Options::translateUnlimitedDistance)
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.dynamicLightDistance.tooltip"))
                .setImpact(PerformanceImpact.MEDIUM)
                .setActivationFn(() -> DeviceManager.isRayQueryEnabled() && dynamicLights.getNewValue());

        CyclingOption<Integer> dynamicMaxLightsPerPixel = new CyclingOption<>(
                Component.translatable("vulkanmod.options.rayTracing.dynamicMaxLightsPerPixel"),
                new Integer[]{1, 2, 4, 8, 16, 32, 64, 128, 0},
                value -> config.rayTracingDynamicMaxLightsPerPixel = sanitizeDynamicMaxLightsPerPixel(value),
                () -> sanitizeDynamicMaxLightsPerPixel(config.rayTracingDynamicMaxLightsPerPixel)
        );
        dynamicMaxLightsPerPixel
                .setTranslator(value -> value == 0
                        ? Component.translatable("vulkanmod.options.rayTracing.unlimited")
                        : Component.literal(value.toString()))
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.dynamicMaxLightsPerPixel.tooltip"))
                .setImpact(PerformanceImpact.HIGH)
                .setActivationFn(() -> DeviceManager.isRayQueryEnabled() && dynamicLights.getNewValue());

        SwitchOption dynamicLightShadows = new SwitchOption(
                Component.translatable("vulkanmod.options.rayTracing.dynamicLightShadows"),
                value -> config.rayTracingDynamicLightShadows = value,
                () -> config.rayTracingDynamicLightShadows
        );
        dynamicLightShadows
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.dynamicLightShadows.tooltip"))
                .setImpact(PerformanceImpact.HIGH)
                .setActivationFn(() -> DeviceManager.isRayQueryEnabled() && dynamicLights.getNewValue());

        CyclingOption<Integer> dynamicShadowMaxLights = new CyclingOption<>(
                Component.translatable("vulkanmod.options.rayTracing.dynamicShadowMaxLights"),
                new Integer[]{1, 2, 4, 8, 16, 32, 64, 0},
                value -> config.rayTracingDynamicShadowMaxLights = sanitizeDynamicShadowMaxLights(value),
                () -> sanitizeDynamicShadowMaxLights(config.rayTracingDynamicShadowMaxLights)
        );
        dynamicShadowMaxLights
                .setTranslator(value -> value == 0
                        ? Component.translatable("vulkanmod.options.rayTracing.unlimited")
                        : Component.literal(value.toString()))
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.dynamicShadowMaxLights.tooltip"))
                .setImpact(PerformanceImpact.HIGH)
                .setActivationFn(() -> DeviceManager.isRayQueryEnabled()
                        && dynamicLights.getNewValue()
                        && dynamicLightShadows.getNewValue());

        CyclingOption<Integer> dynamicShadowDistance = new CyclingOption<>(
                Component.translatable("vulkanmod.options.rayTracing.dynamicShadowDistance"),
                new Integer[]{8, 16, 32, 64, 128, 256, 0},
                value -> config.rayTracingDynamicShadowDistance = sanitizeUnlimitedDistance(value, 8, 256),
                () -> sanitizeUnlimitedDistance(config.rayTracingDynamicShadowDistance, 8, 256)
        );
        dynamicShadowDistance
                .setTranslator(Options::translateUnlimitedDistance)
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.dynamicShadowDistance.tooltip"))
                .setImpact(PerformanceImpact.HIGH)
                .setActivationFn(() -> DeviceManager.isRayQueryEnabled()
                        && dynamicLights.getNewValue()
                        && dynamicLightShadows.getNewValue());

        RangeOption dynamicLightStrength = new RangeOption(
                Component.translatable("vulkanmod.options.rayTracing.dynamicLightStrength"),
                0, 200, 10,
                value -> Component.literal(value + "%"),
                value -> config.rayTracingDynamicLightStrength = Math.max(0, Math.min(200, value)),
                () -> Math.max(0, Math.min(200, config.rayTracingDynamicLightStrength))
        );
        dynamicLightStrength
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.dynamicLightStrength.tooltip"))
                .setActivationFn(() -> DeviceManager.isRayQueryEnabled() && dynamicLights.getNewValue());

        dynamicLights.setOnChange(() -> {
            dynamicLightCount.updateActiveState();
            dynamicLightDistance.updateActiveState();
            dynamicMaxLightsPerPixel.updateActiveState();
            dynamicLightShadows.updateActiveState();
            dynamicShadowMaxLights.updateActiveState();
            dynamicShadowDistance.updateActiveState();
            dynamicLightStrength.updateActiveState();
        });

        dynamicLightShadows.setOnChange(() -> {
            dynamicShadowMaxLights.updateActiveState();
            dynamicShadowDistance.updateActiveState();
        });

        SwitchOption waterReflections = new SwitchOption(
                Component.translatable("vulkanmod.options.rayTracing.waterReflections"),
                value -> config.rayTracingWaterReflections = value,
                () -> config.rayTracingWaterReflections
        );
        waterReflections
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.waterReflections.tooltip"))
                .setImpact(PerformanceImpact.HIGH)
                .setActivationFn(DeviceManager::isRayQueryEnabled);

        RangeOption waterReflectionStrength = new RangeOption(
                Component.translatable("vulkanmod.options.rayTracing.waterReflectionStrength"),
                0, 100, 5,
                value -> Component.literal(value + "%"),
                value -> config.rayTracingWaterReflectionStrength = Math.max(0, Math.min(100, value)),
                () -> Math.max(0, Math.min(100, config.rayTracingWaterReflectionStrength))
        );
        waterReflectionStrength
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.waterReflectionStrength.tooltip"))
                .setActivationFn(() -> DeviceManager.isRayQueryEnabled() && waterReflections.getNewValue());

        CyclingOption<Integer> waterReflectionDistance = new CyclingOption<>(
                Component.translatable("vulkanmod.options.rayTracing.waterReflectionDistance"),
                new Integer[]{16, 32, 64, 128, 256, 512, 0},
                value -> config.rayTracingWaterReflectionDistance = sanitizeUnlimitedDistance(value, 16, 512),
                () -> sanitizeUnlimitedDistance(config.rayTracingWaterReflectionDistance, 16, 512)
        );
        waterReflectionDistance
                .setTranslator(Options::translateUnlimitedDistance)
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.waterReflectionDistance.tooltip"))
                .setImpact(PerformanceImpact.MEDIUM)
                .setActivationFn(() -> DeviceManager.isRayQueryEnabled() && waterReflections.getNewValue());

        waterReflections.setOnChange(() -> {
            waterReflectionStrength.updateActiveState();
            waterReflectionDistance.updateActiveState();
        });

        SwitchOption blockReflections = new SwitchOption(
                Component.translatable("vulkanmod.options.rayTracing.blockReflections"),
                value -> config.rayTracingBlockReflections = value,
                () -> config.rayTracingBlockReflections
        );
        blockReflections
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.blockReflections.tooltip"))
                .setImpact(PerformanceImpact.HIGH)
                .setActivationFn(DeviceManager::isRayQueryEnabled);

        RangeOption blockReflectionStrength = new RangeOption(
                Component.translatable("vulkanmod.options.rayTracing.blockReflectionStrength"),
                0, 100, 5,
                value -> Component.literal(value + "%"),
                value -> config.rayTracingBlockReflectionStrength = Math.max(0, Math.min(100, value)),
                () -> Math.max(0, Math.min(100, config.rayTracingBlockReflectionStrength))
        );
        blockReflectionStrength
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.blockReflectionStrength.tooltip"))
                .setActivationFn(() -> DeviceManager.isRayQueryEnabled() && blockReflections.getNewValue());

        blockReflections.setOnChange(blockReflectionStrength::updateActiveState);

        SwitchOption mirrorSunlight = new SwitchOption(
                Component.translatable("vulkanmod.options.rayTracing.mirrorSunlight"),
                value -> config.rayTracingMirrorSunlight = value,
                () -> config.rayTracingMirrorSunlight
        );
        mirrorSunlight
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.mirrorSunlight.tooltip"))
                .setImpact(PerformanceImpact.HIGH)
                .setActivationFn(DeviceManager::isRayQueryEnabled);

        RangeOption mirrorSunlightStrength = new RangeOption(
                Component.translatable("vulkanmod.options.rayTracing.mirrorSunlightStrength"),
                0, 200, 5,
                value -> Component.literal(value + "%"),
                value -> config.rayTracingMirrorSunlightStrength = Math.max(0, Math.min(200, value)),
                () -> Math.max(0, Math.min(200, config.rayTracingMirrorSunlightStrength))
        );
        mirrorSunlightStrength
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.mirrorSunlightStrength.tooltip"))
                .setActivationFn(() -> DeviceManager.isRayQueryEnabled() && mirrorSunlight.getNewValue());

        CyclingOption<Integer> mirrorSunlightDistance = new CyclingOption<>(
                Component.translatable("vulkanmod.options.rayTracing.mirrorSunlightDistance"),
                new Integer[]{16, 32, 64, 96, 128, 256, 0},
                value -> config.rayTracingMirrorSunlightDistance = sanitizeUnlimitedDistance(value, 16, 256),
                () -> sanitizeUnlimitedDistance(config.rayTracingMirrorSunlightDistance, 16, 256)
        );
        mirrorSunlightDistance
                .setTranslator(Options::translateUnlimitedDistance)
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.mirrorSunlightDistance.tooltip"))
                .setImpact(PerformanceImpact.HIGH)
                .setActivationFn(() -> DeviceManager.isRayQueryEnabled() && mirrorSunlight.getNewValue());

        mirrorSunlight.setOnChange(() -> {
            mirrorSunlightStrength.updateActiveState();
            mirrorSunlightDistance.updateActiveState();
        });

        SwitchOption indirectLighting = new SwitchOption(
                Component.translatable("vulkanmod.options.rayTracing.indirectLighting"),
                value -> config.rayTracingIndirectLighting = value,
                () -> config.rayTracingIndirectLighting
        );
        indirectLighting
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.indirectLighting.tooltip"))
                .setImpact(PerformanceImpact.HIGH)
                .setActivationFn(DeviceManager::isRayQueryEnabled);

        RangeOption indirectLightStrength = new RangeOption(
                Component.translatable("vulkanmod.options.rayTracing.indirectLightStrength"),
                0, 200, 5,
                value -> Component.literal(value + "%"),
                value -> config.rayTracingIndirectLightStrength = Math.max(0, Math.min(200, value)),
                () -> Math.max(0, Math.min(200, config.rayTracingIndirectLightStrength))
        );
        indirectLightStrength
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.indirectLightStrength.tooltip"))
                .setActivationFn(() -> DeviceManager.isRayQueryEnabled() && indirectLighting.getNewValue());

        CyclingOption<Integer> indirectLightDistance = new CyclingOption<>(
                Component.translatable("vulkanmod.options.rayTracing.indirectLightDistance"),
                new Integer[]{8, 16, 32, 48, 64, 128, 256, 0},
                value -> config.rayTracingIndirectLightDistance = sanitizeUnlimitedDistance(value, 8, 256),
                () -> sanitizeUnlimitedDistance(config.rayTracingIndirectLightDistance, 8, 256)
        );
        indirectLightDistance
                .setTranslator(Options::translateUnlimitedDistance)
                .setTooltip(Component.translatable("vulkanmod.options.rayTracing.indirectLightDistance.tooltip"))
                .setImpact(PerformanceImpact.HIGH)
                .setActivationFn(() -> DeviceManager.isRayQueryEnabled() && indirectLighting.getNewValue());

        indirectLighting.setOnChange(() -> {
            indirectLightStrength.updateActiveState();
            indirectLightDistance.updateActiveState();
        });

        return new OptionBlock[]{
                new OptionBlock("", new Option<?>[]{
                        viewMode,
                        debugView,
                        shadowRays,
                        shadowSoftness,
                        shadowDarkness,
                        shadowDistance,
                        directLighting,
                        sunLight,
                        skyLight,
                        blockLight,
                        skyOcclusion,
                        skyRays,
                        skyDistance,
                        temporalFrames,
                        skyDenoiser,
                        entityProxyUpdateInterval,
                        dynamicLights,
                        dynamicLightCount,
                        dynamicLightDistance,
                        dynamicMaxLightsPerPixel,
                        dynamicLightShadows,
                        dynamicShadowMaxLights,
                        dynamicShadowDistance,
                        dynamicLightStrength,
                        waterReflections,
                        waterReflectionStrength,
                        waterReflectionDistance,
                        blockReflections,
                        blockReflectionStrength,
                        mirrorSunlight,
                        mirrorSunlightStrength,
                        mirrorSunlightDistance,
                        indirectLighting,
                        indirectLightStrength,
                        indirectLightDistance
                })
        };
    }

    private static int sanitizeShadowRayCount(int value) {
        if (value <= 1) return 1;
        if (value <= 2) return 2;
        if (value <= 4) return 4;
        return 8;
    }

    private static int sanitizeSkyRayCount(int value) {
        if (value <= 1) return 1;
        if (value <= 2) return 2;
        return 4;
    }

    private static int sanitizeTemporalFrames(int value) {
        if (value <= 0) return 0;
        if (value <= 1) return 1;
        if (value <= 4) return 4;
        if (value <= 8) return 8;
        if (value <= 16) return 16;
        if (value <= 32) return 32;
        return 64;
    }

    private static int sanitizeRtViewMode(int value) {
        return Math.max(0, Math.min(2, value));
    }

    private static int sanitizeRtDebugView(int value) {
        return Math.max(0, Math.min(11, value));
    }

    private static int sanitizeDynamicLightCount(int value) {
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

    private static int sanitizeDynamicShadowMaxLights(int value) {
        if (value <= 0) return 0;
        if (value <= 1) return 1;
        if (value <= 2) return 2;
        if (value <= 4) return 4;
        if (value <= 8) return 8;
        if (value <= 16) return 16;
        if (value <= 32) return 32;
        return 64;
    }

    private static int sanitizeDynamicMaxLightsPerPixel(int value) {
        if (value <= 0) return 0;
        if (value <= 1) return 1;
        if (value <= 2) return 2;
        if (value <= 4) return 4;
        if (value <= 8) return 8;
        if (value <= 16) return 16;
        if (value <= 32) return 32;
        if (value <= 64) return 64;
        return 128;
    }

    private static int sanitizeUnlimitedDistance(int value, int minimum, int maximum) {
        if (value <= 0) return 0;
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static Component translateUnlimitedDistance(Integer value) {
        return value == 0
                ? Component.translatable("vulkanmod.options.rayTracing.unlimited")
                : Component.literal(value + " blocks");
    }

    public static OptionBlock[] getOtherOpts() {
        return new OptionBlock[]{
                new OptionBlock("", new Option[]{
                        new RangeOption(Component.translatable("vulkanmod.options.renderScale"),
                                RenderScale.MIN, RenderScale.MAX, RenderScale.STEP,
                                value -> Component.nullToEmpty(value + "%"),
                                value -> {
                                    config.renderScale = RenderScale.clamp(value);
                                    minecraft.resizeDisplay();
                                },
                                () -> RenderScale.clamp(config.renderScale))
                                .setTooltip(Component.translatable("vulkanmod.options.renderScale.tooltip")),
                        new RangeOption(Component.translatable("vulkanmod.options.frameQueue"),
                                2, 5, 1,
                                value -> {
                                    markPerformancePresetCustom();
                                    config.frameQueueSize = value;
                                    Renderer.scheduleSwapChainUpdate();
                                }, () -> config.frameQueueSize)
                                .setTooltip(Component.translatable("vulkanmod.options.frameQueue.tooltip")),
                        new SwitchOption(Component.translatable("vulkanmod.options.textureAnimations"),
                                value -> {
                                    config.textureAnimations = value;
                                },
                                () -> config.textureAnimations),
                        new CyclingOption<>(Component.translatable("vulkanmod.options.deviceSelector"),
                                IntStream.range(-1, DeviceManager.suitableDevices.size()).boxed().toArray(Integer[]::new),
                                value -> config.device = value,
                                () -> config.device)
                                .setTranslator(value -> Component.translatable((value == -1)
                                        ? "vulkanmod.options.deviceSelector.auto"
                                        : DeviceManager.suitableDevices.get(value).deviceName)
                                )
                                .setTooltip(Component.nullToEmpty("%s: %s".formatted(
                                        Component.translatable("vulkanmod.options.deviceSelector.tooltip").getString(),
                                        DeviceManager.device.deviceName
                                ))
                        )
                })
        };

    }
}
