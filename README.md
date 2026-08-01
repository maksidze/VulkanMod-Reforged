# Vulkan Reforged

An experimental RTX-focused fork of [VulkanMod](https://github.com/xCollateral/VulkanMod) for **Minecraft 1.21.1** and **NeoForge**.

Vulkan Reforged replaces Minecraft's OpenGL renderer with Vulkan and extends the upstream renderer with hardware ray tracing. The current renderer is hybrid: rasterization still provides primary visibility, while Vulkan ray queries add lighting, shadows, occlusion, and reflections.

> [!WARNING]
> This project is in alpha and is not ready for general gameplay. Expect visual bugs, incomplete scene coverage, compatibility problems, and significant performance changes between builds.

## Project goals

- Maintain a native NeoForge port of VulkanMod for Minecraft 1.21.1.
- Build a practical hybrid RTX renderer on top of Vulkan rather than a shader-pack compatibility layer.
- Add physically richer lighting while preserving a raster fallback for unsupported or unstable paths.
- Keep the renderer observable and tunable through in-game options, debug views, and GPU profiling.

In this README, **RTX** means real-time hardware ray tracing. This is not an official NVIDIA RTX product and the project is not affiliated with NVIDIA.

## Current status

Legend: ✅ implemented · 🧪 prototype / needs broader testing · ⬜ planned

### Base renderer and platform

- ✅ Minecraft 1.21.1 and NeoForge 21.1.x project setup.
- ✅ Vulkan renderer replacing Minecraft's default OpenGL rendering path.
- ✅ Vulkan chunk rendering, culling, and optimized chunk submission inherited from VulkanMod.
- ✅ GPU selection, graphics settings screen, performance presets, and render scale controls.
- 🧪 Compatibility layer for GUI rendering, external render paths, and OpenGL-style calls used by other mods.
- ⬜ Broad modpack compatibility testing and per-mod compatibility documentation.

### Hardware ray tracing

- ✅ Detection and device negotiation for Vulkan acceleration structures, ray-tracing pipelines, buffer device addresses, and ray queries.
- ✅ BLAS generation for loaded opaque terrain geometry and TLAS scene assembly.
- ✅ Incremental terrain updates when render sections are rebuilt or removed.
- ✅ Ray-query terrain shader integrated with the existing raster pipeline.
- ✅ Ray-traced sun shadows with configurable ray count, softness, darkness, and distance.
- ✅ Separate sun, sky, and block-light controls for the RT direct-lighting path.
- ✅ Dynamic lights for nearby emissive blocks and luminous held items, including spatial filtering and configurable shadow budgets.
- ✅ Ray-traced water reflections with configurable strength and trace distance.
- ✅ Stochastic sky visibility / occlusion with temporal accumulation and spatial denoising.
- 🧪 Animated `ModelPart` entities contribute captured mesh geometry to the RT scene and receive RT sun/sky/dynamic lighting; AABB is used only as a fallback.
- ✅ Composite, RT-only, Minecraft-only, and diagnostic debug views.
- ✅ RT GPU timing/profiling support and automatic raster fallback after an acceleration-structure build failure.

### Still to implement or improve

- ⬜ Add complete cutout and translucent scene geometry instead of limiting acceleration structures primarily to opaque terrain.
- ⬜ Extend exact RT geometry capture to block entities, item renderers, particles, and custom/modded entity renderers; preserve alpha-tested and transparent entity materials.
- ⬜ Introduce a richer material system: PBR parameters, emissive surfaces, normal maps, and material-aware reflection/lighting response.
- ⬜ Extend reflections beyond water and handle transparent/reflective surfaces consistently.
- ⬜ Add indirect diffuse illumination and multiple-bounce lighting. The current renderer is **not** a full path tracer.
- ⬜ Improve temporal reprojection for moving cameras and objects, including motion vectors, history validation, and ghosting control.
- ⬜ Move expensive acceleration-structure work away from synchronous build-and-wait behavior and reduce TLAS rebuild cost.
- ⬜ Add adaptive quality controls and production-ready performance presets for the RT features.
- ⬜ Add optional upscaling/frame-generation integrations where licensing and platform support permit; DLSS, FSR, and XeSS are not currently integrated.
- ⬜ Validate rendering and stability across NVIDIA RTX, AMD Radeon RX, and Intel Arc GPUs with current drivers.

## Requirements

### Raster renderer

- Minecraft 1.21.1.
- NeoForge 21.1.x.
- A Vulkan-capable GPU and current graphics driver.

### RTX features

The experimental RT path additionally requires a GPU and driver exposing the Vulkan ray-tracing features used by the project, including acceleration structures and ray queries. Owning an NVIDIA card with `RTX` in its name is not the only possible route; actual Vulkan feature support reported by the driver is what matters.

Unsupported hardware should continue to use the raster renderer, but this fallback is still under active testing.

## Installation

1. Install NeoForge for Minecraft 1.21.1.
2. Download a matching `VulkanMod_1.21.1-0.2.0-ALPHA.jar` build.
3. Put the JAR into the instance's `mods` directory.
4. Launch the NeoForge profile.

There are no stable releases yet. When testing development builds, keep a separate instance and include the game log, GPU model, and driver version in bug reports.

## Building from source

Use the included Gradle wrapper:

```powershell
.\gradlew.bat build
```

Build artifacts are written to `build/libs`.

## Attribution

Original project: [xCollateral/VulkanMod](https://github.com/xCollateral/VulkanMod)

Original authors and contributors: xCollateral and VulkanMod contributors.

[NeoForge fork](https://github.com/TrulyRin/VulkanMod-Reforged) maintainer: Rindw.

RTX layer added by: `maksidze`.

The RTX layer is an additional contribution to this fork and is not part of the official upstream VulkanMod project. Upstream code remains subject to its original license and attribution requirements.

The VulkanMod name, logos, CurseForge page, Modrinth page, Discord, and upstream donation links belong to their respective owners. This fork is not endorsed by or affiliated with the upstream VulkanMod maintainers.

## Support

[![Boosty](https://img.shields.io/badge/Boosty-F15F2C?style=for-the-badge&logo=boosty&logoColor=white)](https://boosty.to/maksidze)

## License

This project remains licensed under the **GNU Lesser General Public License v3.0 only**. See [LICENSE](LICENSE) for the LGPLv3 terms and [COPYING](COPYING) for the GPLv3 text referenced by the LGPLv3.

When publishing a binary, publish the exact corresponding source from the same tree, commit, or tag used to build it. Do not publish a JAR built from local uncommitted changes unless those exact changes are also available as source.
