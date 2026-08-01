#version 460

#extension GL_EXT_ray_query : require

#include "light.glsl"
#include "fog.glsl"

layout(binding = 2) uniform sampler2D Sampler0;

layout(binding = 1) uniform UBO {
    vec4 FogColor;
    vec3 SunDirection;
    float ShadowSoftness;
    int ShadowRays;
    float ShadowStrength;
    float ShadowDistance;
    int RtDirectLighting;
    int RtViewMode;
    int RtDebugView;
    float SunLightStrength;
    float SkyLightStrength;
    float BlockLightStrength;
    int TerrainLayer;
    int WaterReflections;
    float WaterReflectionStrength;
    float WaterReflectionDistance;
    int BlockReflections;
    float BlockReflectionStrength;
    int RtMirrorSunlight;
    float RtMirrorSunlightStrength;
    float RtMirrorSunlightDistance;
    int RtIndirectLighting;
    float RtIndirectLightStrength;
    float RtIndirectLightDistance;
    int RtIndirectLightRays;
    float FogStart;
    float FogEnd;
    float AlphaCutout;
    int RtDynamicLightShadows;
    float RtDynamicLightStrength;
    int RtDynamicMaxLightsPerPixel;
    int RtDynamicShadowMaxLights;
    float RtDynamicShadowDistance;
    int RtSkyOcclusion;
    int RtSkyRays;
    float RtSkyDistance;
    int RtTemporalHistoryValid;
    int RtFrameIndex;
    float RtTemporalBlend;
    int RtSkyDenoiser;
};

layout(binding = 4) uniform accelerationStructureEXT TopLevelAS;
layout(std430, binding = 5) readonly buffer RtUvStorage {
    uint PackedRtUvs[];
};

struct RtPointLight {
    vec4 positionRadius;
    vec4 colorIntensity;
};

layout(std430, binding = 6) readonly buffer RtDynamicLightStorage {
    uvec4 RtDynamicLightHeader;
    ivec4 RtDynamicLightCells[8192];
    RtPointLight RtDynamicLights[];
};

layout(rgba16f, binding = 7) uniform image2D RtTemporalCurrent;
layout(rgba16f, binding = 8) uniform readonly image2D RtTemporalPrevious;
layout(r32ui, binding = 9) uniform uimage2D RtTemporalDepthOwner;
layout(rgba16f, binding = 10) uniform image2D RtIndirectCurrent;
layout(rgba16f, binding = 11) uniform readonly image2D RtIndirectPrevious;

layout(location = 0) in float vertexDistance;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec3 worldPosition;
layout(location = 4) in vec4 rtVertexColor;
layout(location = 5) in vec2 lightLevels;
layout(location = 6) in vec3 worldNormal;
layout(location = 7) in vec3 cameraIncident;
layout(location = 8) in float rtMaterialAttribute;
layout(location = 9) in vec4 rtPreviousClipPosition;
layout(location = 10) in float rtPreviousDistance;

layout(location = 0) out vec4 fragColor;

const vec2 RT_SHADOW_DISK[8] = vec2[](
    vec2(-0.55,  0.00),
    vec2( 0.55,  0.00),
    vec2( 0.00, -0.55),
    vec2( 0.00,  0.55),
    vec2(-0.60, -0.60),
    vec2( 0.60,  0.60),
    vec2(-0.60,  0.60),
    vec2( 0.60, -0.60)
);

const uint RT_HIT_ATTRIBUTE_STRIDE = 5u;
const int RT_MATERIAL_OPAQUE = 0;
const int RT_MATERIAL_WATER = 1;
const int RT_MATERIAL_LEAVES = 2;
const int RT_MATERIAL_LAVA = 3;
const int RT_MATERIAL_FIRE = 4;
const int RT_MATERIAL_REFLECTIVE = 6;
const int RT_MATERIAL_ENTITY = 7;

uint rtHitBase(uint instanceBase, uint primitiveIndex) {
    return instanceBase + 1u + primitiveIndex * RT_HIT_ATTRIBUTE_STRIDE;
}

vec2 unpackRtUv(uint packedUv) {
    return vec2(packedUv & 0xFFFFu, packedUv >> 16) * (1.0 / 32768.0);
}

float unpackRtSnorm8(uint value) {
    int signedValue = int(value & 0xFFu);
    if (signedValue > 127) {
        signedValue -= 256;
    }
    return clamp(float(signedValue) * (1.0 / 127.0), -1.0, 1.0);
}

vec3 unpackRtNormal(uint packedNormalMaterial) {
    vec3 normal = vec3(
        unpackRtSnorm8(packedNormalMaterial),
        unpackRtSnorm8(packedNormalMaterial >> 8u),
        unpackRtSnorm8(packedNormalMaterial >> 16u)
    );
    float lengthSquared = dot(normal, normal);
    return lengthSquared > 1.0e-6
        ? normal * inversesqrt(lengthSquared)
        : vec3(0.0, 1.0, 0.0);
}

int unpackRtMaterial(uint packedNormalMaterial) {
    return int((packedNormalMaterial >> 24u) & 0x7u);
}

float unpackRtEmission(uint packedNormalMaterial) {
    return float((packedNormalMaterial >> 27u) & 0xFu) * (1.0 / 15.0);
}

vec2 unpackRtLight(uint packedLightByte) {
    return vec2(packedLightByte & 0xFu, (packedLightByte >> 4u) & 0xFu) * (1.0 / 15.0);
}

vec2 interpolateRtLight(uint packedLights, vec2 barycentrics) {
    vec2 light0 = unpackRtLight(packedLights);
    vec2 light1 = unpackRtLight(packedLights >> 8u);
    vec2 light2 = unpackRtLight(packedLights >> 16u);
    return light0 * (1.0 - barycentrics.x - barycentrics.y)
        + light1 * barycentrics.x
        + light2 * barycentrics.y;
}

vec3 rtSkyColor(float daylight) {
    return mix(vec3(0.04, 0.055, 0.09), vec3(0.26, 0.32, 0.42), daylight);
}

vec3 rtSunColor(float sunHeight) {
    return mix(
        vec3(1.0, 0.55, 0.28),
        vec3(1.0, 0.96, 0.88),
        smoothstep(0.04, 0.40, sunHeight)
    );
}

vec3 rtBlockColor() {
    return vec3(1.0, 0.52, 0.22);
}

float rtEmissionBoost(int material) {
    return material == RT_MATERIAL_LAVA || material == RT_MATERIAL_FIRE ? 1.8 : 1.35;
}

vec3 rtMaterialDebugColor(int material) {
    if (material == RT_MATERIAL_WATER) return vec3(0.05, 0.35, 1.0);
    if (material == RT_MATERIAL_LEAVES) return vec3(0.08, 0.85, 0.16);
    if (material == RT_MATERIAL_LAVA) return vec3(1.0, 0.18, 0.0);
    if (material == RT_MATERIAL_FIRE) return vec3(1.0, 0.85, 0.05);
    if (material == RT_MATERIAL_REFLECTIVE) return vec3(0.35, 0.85, 1.0);
    if (material == RT_MATERIAL_ENTITY) return vec3(0.95, 0.55, 0.15);
    if (material == 5) return vec3(1.0, 0.05, 0.85);
    return material == RT_MATERIAL_OPAQUE ? vec3(0.45) : vec3(1.0, 0.0, 0.0);
}

vec3 rtDistanceDebugColor(float normalizedDistance) {
    if (normalizedDistance < 0.0) return vec3(0.05, 0.15, 1.0);
    float distanceValue = clamp(normalizedDistance, 0.0, 1.0);
    return distanceValue < 0.5
        ? mix(vec3(0.0, 1.0, 0.1), vec3(1.0, 0.9, 0.0), distanceValue * 2.0)
        : mix(vec3(1.0, 0.9, 0.0), vec3(1.0, 0.0, 0.0), (distanceValue - 0.5) * 2.0);
}

bool traceOcclusionRange(vec3 origin, vec3 direction, float maximumDistance) {
    rayQueryEXT query;
    rayQueryInitializeEXT(
        query,
        TopLevelAS,
        gl_RayFlagsTerminateOnFirstHitEXT,
        0xFF,
        origin,
        0.02,
        direction,
        maximumDistance
    );

    while (rayQueryProceedEXT(query)) {
        if (rayQueryGetIntersectionTypeEXT(query, false)
                != gl_RayQueryCandidateIntersectionTriangleEXT) {
            continue;
        }

        uint primitiveIndex = rayQueryGetIntersectionPrimitiveIndexEXT(query, false);
        uint uvBase = rayQueryGetIntersectionInstanceCustomIndexEXT(query, false);
        uint opaquePrimitiveCount = PackedRtUvs[uvBase];
        uint triangleUvBase = rtHitBase(uvBase, primitiveIndex);
        int candidateMaterial = unpackRtMaterial(PackedRtUvs[triangleUvBase + 3u]);

        // Fire is visible emissive geometry, not a solid occluder. In particular,
        // it must not intercept the shadow ray aimed at its own point light.
        if (candidateMaterial == RT_MATERIAL_FIRE) {
            continue;
        }

        bool acceptsIntersection = primitiveIndex < opaquePrimitiveCount;

        if (!acceptsIntersection) {
            vec2 uv0 = unpackRtUv(PackedRtUvs[triangleUvBase]);
            vec2 uv1 = unpackRtUv(PackedRtUvs[triangleUvBase + 1u]);
            vec2 uv2 = unpackRtUv(PackedRtUvs[triangleUvBase + 2u]);
            vec2 barycentrics = rayQueryGetIntersectionBarycentricsEXT(query, false);
            vec2 candidateUv = uv0 * (1.0 - barycentrics.x - barycentrics.y)
                + uv1 * barycentrics.x
                + uv2 * barycentrics.y;
            acceptsIntersection = textureLod(Sampler0, candidateUv, 0.0).a >= 0.5;
        }

        if (acceptsIntersection) {
            rayQueryConfirmIntersectionEXT(query);
        }
    }

    return rayQueryGetIntersectionTypeEXT(query, true)
        != gl_RayQueryCommittedIntersectionNoneEXT;
}

bool traceOcclusion(vec3 origin, vec3 direction) {
    return traceOcclusionRange(origin, direction, ShadowDistance);
}

vec3 offsetRayOrigin(vec3 position, vec3 normal);

uint rtRandomHash(uint value) {
    value ^= value >> 16u;
    value *= 0x7FEB352Du;
    value ^= value >> 15u;
    value *= 0x846CA68Bu;
    value ^= value >> 16u;
    return value;
}

float rtRandom(inout uint state) {
    state = rtRandomHash(state);
    return float(state & 0x00FFFFFFu) * (1.0 / 16777216.0);
}

vec3 rtCosineHemisphere(vec3 normal, inout uint randomState) {
    float randomA = rtRandom(randomState);
    float randomB = rtRandom(randomState);
    float radius = sqrt(randomA);
    float angle = 6.28318530718 * randomB;
    vec3 tangent = normalize(abs(normal.y) < 0.999
        ? cross(vec3(0.0, 1.0, 0.0), normal)
        : cross(vec3(1.0, 0.0, 0.0), normal));
    vec3 bitangent = cross(normal, tangent);
    return normalize(
        tangent * (radius * cos(angle))
        + bitangent * (radius * sin(angle))
        + normal * sqrt(max(1.0 - randomA, 0.0))
    );
}

float traceSkyVisibility(vec3 position, vec3 normal) {
    int rayCount = clamp(RtSkyRays, 1, 4);
    uint randomState = uint(gl_FragCoord.x)
        ^ (uint(gl_FragCoord.y) * 0x9E3779B9u)
        ^ (uint(RtFrameIndex) * 0x85EBCA6Bu);
    float visibleSamples = 0.0;
    vec3 rayOrigin = offsetRayOrigin(position + normal * 0.045, normal);
    for (int rayIndex = 0; rayIndex < rayCount; rayIndex++) {
        vec3 rayDirection = rtCosineHemisphere(normal, randomState);
        visibleSamples += traceOcclusionRange(rayOrigin, rayDirection, RtSkyDistance)
            ? 0.0
            : 1.0;
    }
    return visibleSamples / float(rayCount);
}

vec2 rtOctSignNotZero(vec2 value) {
    return vec2(
        value.x >= 0.0 ? 1.0 : -1.0,
        value.y >= 0.0 ? 1.0 : -1.0
    );
}

vec2 rtOctEncode(vec3 normal) {
    normal /= abs(normal.x) + abs(normal.y) + abs(normal.z);
    vec2 encoded = normal.xy;
    if (normal.z < 0.0) {
        encoded = (1.0 - abs(encoded.yx)) * rtOctSignNotZero(encoded.xy);
    }
    return encoded * 0.5 + 0.5;
}

vec3 rtOctDecode(vec2 encoded) {
    vec2 value = encoded * 2.0 - 1.0;
    vec3 normal = vec3(value, 1.0 - abs(value.x) - abs(value.y));
    if (normal.z < 0.0) {
        normal.xy = (1.0 - abs(normal.yx)) * rtOctSignNotZero(normal.xy);
    }
    return normalize(normal);
}

float filterPreviousSkyVisibility(
    vec2 pixelPosition,
    float surfaceDistance,
    vec3 surfaceNormal
) {
    ivec2 historySize = imageSize(RtTemporalPrevious);
    ivec2 basePixel = ivec2(floor(pixelPosition));
    int filterRadius = RtSkyDenoiser <= 0 ? 0 : RtSkyDenoiser;
    float weightedVisibility = 0.0;
    float totalWeight = 0.0;
    float distanceTolerance = max(0.20, surfaceDistance * 0.004);

    for (int offsetY = -2; offsetY <= 2; offsetY++) {
        for (int offsetX = -2; offsetX <= 2; offsetX++) {
            if (abs(offsetX) > filterRadius || abs(offsetY) > filterRadius) {
                continue;
            }
            ivec2 samplePixel = clamp(
                basePixel + ivec2(offsetX, offsetY),
                ivec2(0),
                historySize - ivec2(1)
            );
            vec4 previous = imageLoad(RtTemporalPrevious, samplePixel);
            if (previous.g <= 0.0) {
                continue;
            }
            float distanceWeight = max(
                1.0 - abs(previous.g - surfaceDistance) / distanceTolerance,
                0.0
            );
            vec3 previousNormal = rtOctDecode(previous.ba);
            float normalWeight = pow(max(dot(previousNormal, surfaceNormal), 0.0), 24.0);
            vec2 sampleDelta = vec2(samplePixel) + vec2(0.5) - pixelPosition;
            float spatialWeight = exp(-dot(sampleDelta, sampleDelta) * 0.65);
            float weight = distanceWeight * normalWeight * spatialWeight;
            weightedVisibility += previous.r * weight;
            totalWeight += weight;
        }
    }

    return totalWeight > 0.0001
        ? weightedVisibility / totalWeight
        : -1.0;
}

float accumulateSkyVisibility(
    float currentVisibility,
    float surfaceDistance,
    vec3 surfaceNormal
) {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 historySize = imageSize(RtTemporalCurrent);
    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, historySize))) {
        return currentVisibility;
    }

    float accumulated = currentVisibility;
    bool validReprojection = rtPreviousClipPosition.w > 0.0001;
    vec2 previousNdc = validReprojection
        ? rtPreviousClipPosition.xy / rtPreviousClipPosition.w
        : vec2(2.0);
    validReprojection = validReprojection
        && all(lessThanEqual(abs(previousNdc), vec2(1.01)));
    vec2 previousUv = vec2(
        previousNdc.x * 0.5 + 0.5,
        0.5 - previousNdc.y * 0.5
    );
    vec2 previousPixelPosition = previousUv * vec2(historySize);

    if (RtTemporalHistoryValid != 0
            && RtTemporalBlend > 0.0
            && validReprojection) {
        float previousVisibility = filterPreviousSkyVisibility(
            clamp(
                previousPixelPosition,
                vec2(0.5),
                vec2(historySize) - vec2(0.5)
            ),
            rtPreviousDistance,
            surfaceNormal
        );
        if (previousVisibility >= 0.0) {
            accumulated = mix(
                currentVisibility,
                previousVisibility,
                clamp(RtTemporalBlend, 0.0, 0.99975586)
            );
        }
    }
    uint fragmentDepth = floatBitsToUint(clamp(gl_FragCoord.z, 0.0, 1.0));
    uint previousClosestDepth = imageAtomicMin(RtTemporalDepthOwner, pixel, fragmentDepth);
    if (fragmentDepth <= previousClosestDepth) {
        imageStore(
            RtTemporalCurrent,
            pixel,
            vec4(accumulated, surfaceDistance, rtOctEncode(surfaceNormal))
        );
    }
    return accumulated;
}

vec3 reflectionSky(vec3 direction) {
    float horizon = smoothstep(-0.15, 0.55, direction.y);
    float daylight = smoothstep(-0.02, 0.08, SunDirection.y);
    vec3 horizonColor = mix(vec3(0.035, 0.045, 0.075), vec3(0.52, 0.68, 0.88), daylight);
    vec3 zenithColor = mix(vec3(0.012, 0.018, 0.045), vec3(0.16, 0.34, 0.68), daylight);
    vec3 sky = mix(horizonColor, zenithColor, horizon);
    float sunDisk = pow(max(dot(direction, normalize(SunDirection)), 0.0), 768.0) * daylight;
    return sky + vec3(1.0, 0.82, 0.58) * sunDisk * 3.0;
}

vec3 evaluateDynamicRtLights(
    vec3 position,
    vec3 normal,
    bool twoSided,
    bool traceShadows,
    out uint evaluatedLightCount,
    out uint shadowRayCount
);

// Returns the radiance from local lights after one idealised, deliberately
// slightly rough, reflection at a metallic block.  The rough lobe is important:
// block lights are points, so a perfectly sharp mirror would almost never be
// sampled by the single indirect-light ray available per pixel.
vec3 evaluateReflectedDynamicRtLights(
    vec3 position,
    vec3 normal,
    vec3 incomingDirection,
    bool traceShadows
);

vec3 traceReflection(
    vec3 origin,
    vec3 direction,
    float maximumDistance,
    out float normalizedHitDistance,
    out vec3 reflectedDynamicLight
) {
    normalizedHitDistance = -1.0;
    reflectedDynamicLight = vec3(0.0);
    rayQueryEXT query;
    rayQueryInitializeEXT(
        query,
        TopLevelAS,
        gl_RayFlagsNoneEXT,
        0xFF,
        origin,
        0.03,
        direction,
        maximumDistance
    );

    while (rayQueryProceedEXT(query)) {
        if (rayQueryGetIntersectionTypeEXT(query, false)
                != gl_RayQueryCandidateIntersectionTriangleEXT) {
            continue;
        }

        uint primitiveIndex = rayQueryGetIntersectionPrimitiveIndexEXT(query, false);
        uint uvBase = rayQueryGetIntersectionInstanceCustomIndexEXT(query, false);
        uint opaquePrimitiveCount = PackedRtUvs[uvBase];
        bool acceptsIntersection = primitiveIndex < opaquePrimitiveCount;

        if (!acceptsIntersection) {
            uint triangleUvBase = rtHitBase(uvBase, primitiveIndex);
            vec2 uv0 = unpackRtUv(PackedRtUvs[triangleUvBase]);
            vec2 uv1 = unpackRtUv(PackedRtUvs[triangleUvBase + 1u]);
            vec2 uv2 = unpackRtUv(PackedRtUvs[triangleUvBase + 2u]);
            vec2 barycentrics = rayQueryGetIntersectionBarycentricsEXT(query, false);
            vec2 candidateUv = uv0 * (1.0 - barycentrics.x - barycentrics.y)
                + uv1 * barycentrics.x
                + uv2 * barycentrics.y;
            acceptsIntersection = textureLod(Sampler0, candidateUv, 0.0).a >= 0.5;
        }

        if (acceptsIntersection) {
            rayQueryConfirmIntersectionEXT(query);
        }
    }

    if (rayQueryGetIntersectionTypeEXT(query, true)
            == gl_RayQueryCommittedIntersectionNoneEXT) {
        return reflectionSky(direction);
    }

    uint primitiveIndex = rayQueryGetIntersectionPrimitiveIndexEXT(query, true);
    uint uvBase = rayQueryGetIntersectionInstanceCustomIndexEXT(query, true);
    bool hitCutout = primitiveIndex >= PackedRtUvs[uvBase];
    uint triangleUvBase = rtHitBase(uvBase, primitiveIndex);
    vec2 uv0 = unpackRtUv(PackedRtUvs[triangleUvBase]);
    vec2 uv1 = unpackRtUv(PackedRtUvs[triangleUvBase + 1u]);
    vec2 uv2 = unpackRtUv(PackedRtUvs[triangleUvBase + 2u]);
    vec2 barycentrics = rayQueryGetIntersectionBarycentricsEXT(query, true);
    vec2 hitUv = uv0 * (1.0 - barycentrics.x - barycentrics.y)
        + uv1 * barycentrics.x
        + uv2 * barycentrics.y;
    uint packedNormalMaterial = PackedRtUvs[triangleUvBase + 3u];
    uint packedLights = PackedRtUvs[triangleUvBase + 4u];
    vec2 hitLightLevels = interpolateRtLight(packedLights, barycentrics);
    vec3 hitNormal = unpackRtNormal(packedNormalMaterial);
    if (dot(hitNormal, -direction) < 0.0) {
        hitNormal = -hitNormal;
    }
    int hitMaterial = unpackRtMaterial(packedNormalMaterial);
    // Entity proxies do not carry the terrain texture atlas UVs. A stable neutral
    // albedo is preferable to sampling an arbitrary, occasionally transparent,
    // terrain-atlas texel and making a mob vanish from the reflection.
    vec3 hitColor = hitMaterial == RT_MATERIAL_ENTITY
        ? vec3(0.62, 0.58, 0.52)
        : textureLod(Sampler0, hitUv, 0.0).rgb;
    float hitEmission = unpackRtEmission(packedNormalMaterial);
    float hitDistance = rayQueryGetIntersectionTEXT(query, true);
    normalizedHitDistance = hitDistance / max(maximumDistance, 0.001);
    if (RtDebugView == 8) {
        return vec3(0.0);
    }
    vec3 hitPosition = origin + direction * hitDistance;
    vec3 sunDirection = normalize(SunDirection);
    float daylight = smoothstep(-0.02, 0.08, sunDirection.y);
    float surfaceToLight = hitCutout || hitMaterial == RT_MATERIAL_LEAVES
        ? abs(dot(hitNormal, sunDirection))
        : max(dot(hitNormal, sunDirection), 0.0);
    bool hitShadowed = false;
    if (daylight * surfaceToLight > 0.01) {
        vec3 shadowNormal = dot(hitNormal, sunDirection) < 0.0 ? -hitNormal : hitNormal;
        vec3 shadowOrigin = offsetRayOrigin(
            hitPosition + shadowNormal * 0.04 + sunDirection * 0.01,
            shadowNormal
        );
        hitShadowed = traceOcclusion(shadowOrigin, sunDirection);
    }

    float skyLevel = clamp(hitLightLevels.y, 0.0, 1.0);
    float blockLevel = pow(clamp(hitLightLevels.x, 0.0, 1.0), 1.35);
    vec3 directSun = rtSunColor(clamp(sunDirection.y, 0.0, 1.0))
        * (0.82 * daylight * surfaceToLight * (hitShadowed ? 0.0 : 1.0) * SunLightStrength);
    vec3 hitLighting = RtViewMode == 1
        ? vec3(0.002) + directSun
        : vec3(0.015)
            + rtSkyColor(daylight) * skyLevel * SkyLightStrength
            + directSun
            + rtBlockColor() * (0.90 * blockLevel * BlockLightStrength);
    uint ignoredEvaluatedLights;
    uint ignoredShadowRays;
    if (hitMaterial == RT_MATERIAL_REFLECTIVE) {
        // A ray that reaches a mirror receives light only from the reflected
        // direction. This makes the same path work both in a visible mirror and
        // in the diffuse one-bounce GI path used to illuminate nearby blocks.
        reflectedDynamicLight = evaluateReflectedDynamicRtLights(
            hitPosition,
            hitNormal,
            direction,
            true
        );
        hitLighting += reflectedDynamicLight;
    } else {
        hitLighting += evaluateDynamicRtLights(
            hitPosition,
            hitNormal,
            hitCutout,
            false,
            ignoredEvaluatedLights,
            ignoredShadowRays
        );
    }
    vec3 reflectedSurface = hitColor * hitLighting
        + hitColor * hitEmission * rtEmissionBoost(hitMaterial);
    float distanceFade = smoothstep(
        maximumDistance * 0.70,
        maximumDistance,
        hitDistance
    );
    return mix(reflectedSurface, reflectionSky(direction), distanceFade);
}

vec3 traceMirrorSunlight(vec3 position, vec3 receiverNormal) {
    vec3 sunDirection = normalize(SunDirection);
    float daylight = smoothstep(-0.02, 0.08, sunDirection.y);
    if (daylight <= 0.001) {
        return vec3(0.0);
    }

    vec3 redirectedLight = vec3(0.0);
    // Minecraft's terrain faces are axis-aligned. For each sun-facing axis,
    // trace the exact reverse path receiver -> mirror -> sun. Unlike a random
    // GI sample this deterministically finds the narrow specular path that
    // creates a usable sunlight spot below an iron-block mirror.
    for (int axis = 0; axis < 3; axis++) {
        float sunComponent = sunDirection[axis];
        if (abs(sunComponent) < 0.035) {
            continue;
        }

        vec3 mirrorNormal = vec3(0.0);
        mirrorNormal[axis] = sunComponent >= 0.0 ? 1.0 : -1.0;
        vec3 mirrorToReceiver = normalize(reflect(-sunDirection, mirrorNormal));
        vec3 receiverToMirror = -mirrorToReceiver;
        float receiverCosine = max(dot(receiverNormal, receiverToMirror), 0.0);
        if (receiverCosine <= 0.001) {
            continue;
        }

        rayQueryEXT query;
        rayQueryInitializeEXT(
            query,
            TopLevelAS,
            gl_RayFlagsNoneEXT,
            0xFF,
            offsetRayOrigin(position + receiverNormal * 0.035, receiverNormal),
            0.03,
            receiverToMirror,
            RtMirrorSunlightDistance
        );

        while (rayQueryProceedEXT(query)) {
            if (rayQueryGetIntersectionTypeEXT(query, false)
                    != gl_RayQueryCandidateIntersectionTriangleEXT) {
                continue;
            }

            uint primitiveIndex = rayQueryGetIntersectionPrimitiveIndexEXT(query, false);
            uint uvBase = rayQueryGetIntersectionInstanceCustomIndexEXT(query, false);
            uint opaquePrimitiveCount = PackedRtUvs[uvBase];
            bool acceptsIntersection = primitiveIndex < opaquePrimitiveCount;
            if (!acceptsIntersection) {
                uint triangleUvBase = rtHitBase(uvBase, primitiveIndex);
                vec2 uv0 = unpackRtUv(PackedRtUvs[triangleUvBase]);
                vec2 uv1 = unpackRtUv(PackedRtUvs[triangleUvBase + 1u]);
                vec2 uv2 = unpackRtUv(PackedRtUvs[triangleUvBase + 2u]);
                vec2 barycentrics = rayQueryGetIntersectionBarycentricsEXT(query, false);
                vec2 candidateUv = uv0 * (1.0 - barycentrics.x - barycentrics.y)
                    + uv1 * barycentrics.x
                    + uv2 * barycentrics.y;
                acceptsIntersection = textureLod(Sampler0, candidateUv, 0.0).a >= 0.5;
            }
            if (acceptsIntersection) {
                rayQueryConfirmIntersectionEXT(query);
            }
        }

        if (rayQueryGetIntersectionTypeEXT(query, true)
                == gl_RayQueryCommittedIntersectionNoneEXT) {
            continue;
        }

        uint primitiveIndex = rayQueryGetIntersectionPrimitiveIndexEXT(query, true);
        uint uvBase = rayQueryGetIntersectionInstanceCustomIndexEXT(query, true);
        uint triangleUvBase = rtHitBase(uvBase, primitiveIndex);
        uint packedNormalMaterial = PackedRtUvs[triangleUvBase + 3u];
        int mirrorMaterial = unpackRtMaterial(packedNormalMaterial);
        vec3 hitNormal = unpackRtNormal(packedNormalMaterial);
        if (mirrorMaterial != RT_MATERIAL_REFLECTIVE
                || abs(dot(hitNormal, mirrorNormal)) < 0.985) {
            continue;
        }

        float mirrorDistance = rayQueryGetIntersectionTEXT(query, true);
        vec3 mirrorPosition = position + receiverToMirror * mirrorDistance;
        vec3 sunRayOrigin = offsetRayOrigin(
            mirrorPosition + mirrorNormal * 0.04 + sunDirection * 0.01,
            mirrorNormal
        );
        if (traceOcclusionRange(sunRayOrigin, sunDirection, ShadowDistance)) {
            continue;
        }

        vec2 uv0 = unpackRtUv(PackedRtUvs[triangleUvBase]);
        vec2 uv1 = unpackRtUv(PackedRtUvs[triangleUvBase + 1u]);
        vec2 uv2 = unpackRtUv(PackedRtUvs[triangleUvBase + 2u]);
        vec2 barycentrics = rayQueryGetIntersectionBarycentricsEXT(query, true);
        vec2 hitUv = uv0 * (1.0 - barycentrics.x - barycentrics.y)
            + uv1 * barycentrics.x
            + uv2 * barycentrics.y;
        vec3 mirrorTint = mix(vec3(1.0), textureLod(Sampler0, hitUv, 0.0).rgb, 0.25);
        float mirrorFacing = abs(dot(mirrorNormal, sunDirection));
        redirectedLight += mirrorTint * rtSunColor(clamp(sunDirection.y, 0.0, 1.0))
            * (0.82 * daylight * receiverCosine * smoothstep(0.04, 0.22, mirrorFacing));
    }
    return redirectedLight;
}

vec3 traceIndirectLight(vec3 position, vec3 normal, bool mirrorDynamicOnly) {
    // A small stratified set makes a narrow "surface -> mirror -> torch" path
    // show up promptly instead of waiting for a single random ray to find it.
    // This is opt-in and its RGB result is still temporally accumulated below.
    int indirectSampleCount = clamp(RtIndirectLightRays, 1, 8);
    uint baseRandomState = uint(gl_FragCoord.x) * 0x68BC21EBu
        ^ uint(gl_FragCoord.y) * 0x02E5BE93u
        ^ uint(RtFrameIndex) * 0x9E3779B9u;
    vec3 accumulatedLight = vec3(0.0);
    vec3 rayOrigin = offsetRayOrigin(position + normal * 0.04, normal);
    for (int sampleIndex = 0; sampleIndex < indirectSampleCount; sampleIndex++) {
        uint randomState = baseRandomState
            ^ (uint(sampleIndex + 1) * 0x85EBCA6Bu);
        vec3 direction = rtCosineHemisphere(normal, randomState);
        float ignoredHitDistance;
        vec3 reflectedDynamicLight;
        vec3 sampleLight = traceReflection(
            rayOrigin,
            direction,
            RtIndirectLightDistance,
            ignoredHitDistance,
            reflectedDynamicLight
        );
        if (mirrorDynamicOnly) {
            // The diagnostic reports the unaveraged energy so an occasional
            // valid mirror path is obvious instead of nearly black.
            accumulatedLight += reflectedDynamicLight;
        } else {
            // A point emitter is sampled through a finite rough mirror lobe.
            // Compensate the sparse one-bounce estimate so torch colour remains
            // visible after the temporal average, rather than being diluted by
            // the samples that did not hit the reflecting block.
            accumulatedLight += sampleLight + reflectedDynamicLight * 3.0;
        }
    }
    return mirrorDynamicOnly
        ? accumulatedLight
        : accumulatedLight / float(indirectSampleCount);
}

vec3 filterPreviousIndirectLight(
    vec2 pixelPosition,
    float surfaceDistance,
    out float totalWeight
) {
    ivec2 historySize = imageSize(RtIndirectPrevious);
    ivec2 basePixel = ivec2(floor(pixelPosition));
    vec3 weightedLight = vec3(0.0);
    totalWeight = 0.0;
    float distanceTolerance = max(0.25, surfaceDistance * 0.006);

    for (int offsetY = -1; offsetY <= 1; offsetY++) {
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            ivec2 samplePixel = clamp(
                basePixel + ivec2(offsetX, offsetY),
                ivec2(0),
                historySize - ivec2(1)
            );
            vec4 previous = imageLoad(RtIndirectPrevious, samplePixel);
            if (previous.a <= 0.0) {
                continue;
            }
            float distanceWeight = max(
                1.0 - abs(previous.a - surfaceDistance) / distanceTolerance,
                0.0
            );
            vec2 sampleDelta = vec2(samplePixel) + vec2(0.5) - pixelPosition;
            float spatialWeight = exp(-dot(sampleDelta, sampleDelta) * 0.70);
            float weight = distanceWeight * spatialWeight;
            weightedLight += min(previous.rgb, vec3(12.0)) * weight;
            totalWeight += weight;
        }
    }
    return totalWeight > 0.0001 ? weightedLight / totalWeight : vec3(0.0);
}

vec3 accumulateIndirectLight(vec3 currentLight, float surfaceDistance) {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 historySize = imageSize(RtIndirectCurrent);
    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, historySize))) {
        return currentLight;
    }

    vec3 accumulated = currentLight;
    bool validReprojection = rtPreviousClipPosition.w > 0.0001;
    vec2 previousNdc = validReprojection
        ? rtPreviousClipPosition.xy / rtPreviousClipPosition.w
        : vec2(2.0);
    validReprojection = validReprojection
        && all(lessThanEqual(abs(previousNdc), vec2(1.01)));
    vec2 previousUv = vec2(
        previousNdc.x * 0.5 + 0.5,
        0.5 - previousNdc.y * 0.5
    );
    vec2 previousPixelPosition = previousUv * vec2(historySize);

    if (RtTemporalHistoryValid != 0
            && RtTemporalBlend > 0.0
            && validReprojection) {
        float historyWeight;
        vec3 previousLight = filterPreviousIndirectLight(
            clamp(previousPixelPosition, vec2(0.5), vec2(historySize) - vec2(0.5)),
            rtPreviousDistance,
            historyWeight
        );
        if (historyWeight > 0.0001) {
            // Keep fresh samples influential enough to follow moving lights and
            // prevent long bright trails from high-energy emissive hits.
            float blend = min(clamp(RtTemporalBlend, 0.0, 0.96), 0.94);
            accumulated = mix(currentLight, previousLight, blend);
        }
    }

    uint fragmentDepth = floatBitsToUint(clamp(gl_FragCoord.z, 0.0, 1.0));
    uint previousClosestDepth = imageAtomicMin(RtTemporalDepthOwner, pixel, fragmentDepth);
    if (fragmentDepth <= previousClosestDepth) {
        imageStore(
            RtIndirectCurrent,
            pixel,
            vec4(max(accumulated, vec3(0.0)), max(surfaceDistance, 0.0001))
        );
    }
    return accumulated;
}

float traceSunOcclusion(vec3 origin, vec3 sunDirection, float softness) {
    int sampleCount = clamp(ShadowRays, 1, 8);
    if (sampleCount <= 1 || softness <= 0.001) {
        return traceOcclusion(origin, sunDirection) ? 1.0 : 0.0;
    }

    vec3 referenceAxis = abs(sunDirection.y) < 0.99
        ? vec3(0.0, 1.0, 0.0)
        : vec3(1.0, 0.0, 0.0);
    vec3 tangent = normalize(cross(referenceAxis, sunDirection));
    vec3 bitangent = cross(sunDirection, tangent);
    float angularRadius = softness * 0.08;
    float occlusion = 0.0;

    for (int sampleIndex = 0; sampleIndex < 8; ++sampleIndex) {
        if (sampleIndex >= sampleCount) {
            break;
        }
        vec2 diskSample = RT_SHADOW_DISK[sampleIndex] * angularRadius;
        vec3 sampleDirection = normalize(
            sunDirection + tangent * diskSample.x + bitangent * diskSample.y
        );
        occlusion += traceOcclusion(origin, sampleDirection) ? 1.0 : 0.0;
    }

    return occlusion / float(sampleCount);
}

vec3 offsetRayOrigin(vec3 position, vec3 normal) {
    const float originThreshold = 1.0 / 32.0;
    const float floatScale = 1.0 / 65536.0;
    const int integerScale = 256;

    ivec3 integerOffset = ivec3(integerScale * normal);
    vec3 integerPosition = intBitsToFloat(
        floatBitsToInt(position)
            + mix(integerOffset, -integerOffset, lessThan(position, vec3(0.0)))
    );

    return mix(
        integerPosition,
        position + floatScale * normal,
        lessThan(abs(position), vec3(originThreshold))
    );
}

uint rtDynamicCellHash(ivec3 cell) {
    return (uint(cell.x) * 73856093u
        ^ uint(cell.y) * 19349663u
        ^ uint(cell.z) * 83492791u) & 8191u;
}

int rtFindDynamicCell(ivec3 cell) {
    uint slot = rtDynamicCellHash(cell);
    for (int probe = 0; probe < 32; probe++) {
        ivec4 entry = RtDynamicLightCells[slot];
        if (entry.w == -1) {
            return -1;
        }
        if (all(equal(entry.xyz, cell))) {
            return entry.w;
        }
        slot = (slot + 1u) & 8191u;
    }
    return -1;
}

const ivec3 RT_DYNAMIC_CELL_OFFSETS[27] = ivec3[](
    ivec3( 0,  0,  0),
    ivec3(-1,  0,  0), ivec3( 1,  0,  0),
    ivec3( 0, -1,  0), ivec3( 0,  1,  0),
    ivec3( 0,  0, -1), ivec3( 0,  0,  1),
    ivec3(-1, -1,  0), ivec3(-1,  1,  0),
    ivec3( 1, -1,  0), ivec3( 1,  1,  0),
    ivec3(-1,  0, -1), ivec3(-1,  0,  1),
    ivec3( 1,  0, -1), ivec3( 1,  0,  1),
    ivec3( 0, -1, -1), ivec3( 0, -1,  1),
    ivec3( 0,  1, -1), ivec3( 0,  1,  1),
    ivec3(-1, -1, -1), ivec3(-1, -1,  1),
    ivec3(-1,  1, -1), ivec3(-1,  1,  1),
    ivec3( 1, -1, -1), ivec3( 1, -1,  1),
    ivec3( 1,  1, -1), ivec3( 1,  1,  1)
);

vec3 evaluateDynamicRtLights(
    vec3 position,
    vec3 normal,
    bool twoSided,
    bool traceShadows,
    out uint evaluatedLightCount,
    out uint shadowRayCount
) {
    vec3 accumulatedLight = vec3(0.0);
    evaluatedLightCount = 0u;
    shadowRayCount = 0u;
    ivec3 surfaceCell = ivec3(floor(position * (1.0 / 32.0)));
    uint totalLightCount = RtDynamicLightHeader.x;
    uint selectedLightIndices[128];
    float selectedContributions[128];
    uint selectedLightCount = 0u;
    uint selectedLightLimit = RtDynamicMaxLightsPerPixel <= 0
        ? 128u
        : uint(clamp(RtDynamicMaxLightsPerPixel, 1, 128));
    bool unlimitedLights = RtDynamicMaxLightsPerPixel <= 0;

    for (int cellOffsetIndex = 0; cellOffsetIndex < 27; cellOffsetIndex++) {
        int packedRange = rtFindDynamicCell(
            surfaceCell + RT_DYNAMIC_CELL_OFFSETS[cellOffsetIndex]
        );
        if (packedRange == -1) {
            continue;
        }
        uint rangeBits = uint(packedRange);
        uint lightStart = rangeBits & 0xFFFFFu;
        uint lightCount = rangeBits >> 20u;
        for (uint cellLightIndex = 0u; cellLightIndex < lightCount; cellLightIndex++) {
            uint lightIndex = lightStart + cellLightIndex;
            if (lightIndex >= totalLightCount) {
                break;
            }

            RtPointLight pointLight = RtDynamicLights[lightIndex];
            vec4 positionRadius = pointLight.positionRadius;
            vec4 colorIntensity = pointLight.colorIntensity;
            vec3 surfaceToLightVector = positionRadius.xyz - position;
            float distanceToLight = length(surfaceToLightVector);
            float lightRadius = positionRadius.w;
            if (distanceToLight <= 0.02 || distanceToLight >= lightRadius || lightRadius <= 0.0) {
                continue;
            }

            vec3 lightDirection = surfaceToLightVector / distanceToLight;
            float normalWeight = twoSided
                ? abs(dot(normal, lightDirection))
                : max(dot(normal, lightDirection), 0.0);
            float radialFalloff = max(1.0 - distanceToLight / lightRadius, 0.0);
            float attenuation = radialFalloff * radialFalloff
                * (1.5 / (1.0 + 0.018 * distanceToLight * distanceToLight));
            float contribution = normalWeight * attenuation
                * colorIntensity.a * RtDynamicLightStrength;
            if (contribution <= 0.002) {
                continue;
            }

            if (unlimitedLights) {
                bool shadowed = false;
                bool shadowBudgetAvailable = RtDynamicShadowMaxLights <= 0
                    || shadowRayCount < uint(RtDynamicShadowMaxLights);
                bool shadowDistanceAvailable = distanceToLight <= RtDynamicShadowDistance;
                if (traceShadows
                        && RtDynamicLightShadows != 0
                        && shadowBudgetAvailable
                        && shadowDistanceAvailable) {
                    vec3 originNormal = dot(normal, lightDirection) < 0.0 ? -normal : normal;
                    vec3 lightRayOrigin = offsetRayOrigin(
                        position + originNormal * 0.04 + lightDirection * 0.01,
                        originNormal
                    );
                    float traceDistance = max(distanceToLight - 0.55, 0.03);
                    shadowed = traceOcclusionRange(lightRayOrigin, lightDirection, traceDistance);
                    shadowRayCount++;
                }
                if (!shadowed) {
                    accumulatedLight += colorIntensity.rgb * contribution;
                }
                evaluatedLightCount++;
                continue;
            }

            bool appendSelectedLight = selectedLightCount < selectedLightLimit;
            bool replaceWeakestLight = !appendSelectedLight
                && (contribution > selectedContributions[selectedLightCount - 1u]
                    || (contribution == selectedContributions[selectedLightCount - 1u]
                        && lightIndex < selectedLightIndices[selectedLightCount - 1u]));
            if (appendSelectedLight || replaceWeakestLight) {
                uint insertionIndex = 0u;
                if (appendSelectedLight) {
                    insertionIndex = selectedLightCount;
                    selectedLightCount++;
                } else {
                    insertionIndex = selectedLightCount - 1u;
                }
                while (insertionIndex > 0u) {
                    uint previousIndex = insertionIndex - 1u;
                    float previousContribution = selectedContributions[previousIndex];
                    uint previousLightIndex = selectedLightIndices[previousIndex];
                    bool belongsBeforePrevious = contribution > previousContribution
                        || (contribution == previousContribution && lightIndex < previousLightIndex);
                    if (!belongsBeforePrevious) break;
                    selectedContributions[insertionIndex] = previousContribution;
                    selectedLightIndices[insertionIndex] = previousLightIndex;
                    insertionIndex = previousIndex;
                }
                selectedContributions[insertionIndex] = contribution;
                selectedLightIndices[insertionIndex] = lightIndex;
            }
        }
    }

    // Shade only after all neighboring cells have been inspected. Selecting the
    // strongest contributions makes the result independent of cell traversal order.
    for (uint selectedIndex = 0u; selectedIndex < selectedLightCount; selectedIndex++) {
        RtPointLight pointLight = RtDynamicLights[selectedLightIndices[selectedIndex]];
        vec3 surfaceToLightVector = pointLight.positionRadius.xyz - position;
        float distanceToLight = length(surfaceToLightVector);
        vec3 lightDirection = surfaceToLightVector / distanceToLight;
        bool shadowed = false;
        bool shadowBudgetAvailable = RtDynamicShadowMaxLights <= 0
            || shadowRayCount < uint(RtDynamicShadowMaxLights);
        bool shadowDistanceAvailable = distanceToLight <= RtDynamicShadowDistance;
        if (traceShadows
                && RtDynamicLightShadows != 0
                && shadowBudgetAvailable
                && shadowDistanceAvailable) {
            vec3 originNormal = dot(normal, lightDirection) < 0.0 ? -normal : normal;
            vec3 lightRayOrigin = offsetRayOrigin(
                position + originNormal * 0.04 + lightDirection * 0.01,
                originNormal
            );
            float traceDistance = max(distanceToLight - 0.55, 0.03);
            shadowed = traceOcclusionRange(lightRayOrigin, lightDirection, traceDistance);
            shadowRayCount++;
        }
        if (!shadowed) {
            accumulatedLight += pointLight.colorIntensity.rgb
                * selectedContributions[selectedIndex];
        }
    }
    if (!unlimitedLights) {
        evaluatedLightCount = selectedLightCount;
    }
    return accumulatedLight;
}

vec3 evaluateReflectedDynamicRtLights(
    vec3 position,
    vec3 normal,
    vec3 incomingDirection,
    bool traceShadows
) {
    vec3 accumulatedLight = vec3(0.0);
    vec3 reflectedDirection = normalize(reflect(incomingDirection, normal));
    ivec3 surfaceCell = ivec3(floor(position * (1.0 / 32.0)));
    uint totalLightCount = RtDynamicLightHeader.x;

    for (int cellOffsetIndex = 0; cellOffsetIndex < 27; cellOffsetIndex++) {
        int packedRange = rtFindDynamicCell(
            surfaceCell + RT_DYNAMIC_CELL_OFFSETS[cellOffsetIndex]
        );
        if (packedRange == -1) {
            continue;
        }
        uint rangeBits = uint(packedRange);
        uint lightStart = rangeBits & 0xFFFFFu;
        uint lightCount = rangeBits >> 20u;
        for (uint cellLightIndex = 0u; cellLightIndex < lightCount; cellLightIndex++) {
            uint lightIndex = lightStart + cellLightIndex;
            if (lightIndex >= totalLightCount) {
                break;
            }

            RtPointLight pointLight = RtDynamicLights[lightIndex];
            vec3 mirrorToLight = pointLight.positionRadius.xyz - position;
            float distanceToLight = length(mirrorToLight);
            float lightRadius = pointLight.positionRadius.w;
            if (distanceToLight <= 0.02 || distanceToLight >= lightRadius || lightRadius <= 0.0) {
                continue;
            }

            vec3 lightDirection = mirrorToLight / distanceToLight;
            // Iron blocks are treated as a polished but not mathematically
            // perfect mirror. This produces a stable, visible torch bounce with
            // one temporal sample while retaining a clear reflected direction.
            float specularLobe = pow(
                max(dot(reflectedDirection, lightDirection), 0.0),
                4.0
            );
            if (specularLobe <= 0.001) {
                continue;
            }

            float radialFalloff = max(1.0 - distanceToLight / lightRadius, 0.0);
            float attenuation = radialFalloff * radialFalloff
                * (1.5 / (1.0 + 0.018 * distanceToLight * distanceToLight));
            float contribution = specularLobe * attenuation
                * pointLight.colorIntensity.a * RtDynamicLightStrength;
            if (contribution <= 0.002) {
                continue;
            }

            bool shadowed = false;
            if (traceShadows && RtDynamicLightShadows != 0) {
                vec3 originNormal = dot(normal, lightDirection) < 0.0 ? -normal : normal;
                vec3 lightRayOrigin = offsetRayOrigin(
                    position + originNormal * 0.04 + lightDirection * 0.01,
                    originNormal
                );
                shadowed = traceOcclusionRange(
                    lightRayOrigin,
                    lightDirection,
                    max(distanceToLight - 0.55, 0.03)
                );
            }
            if (!shadowed) {
                accumulatedLight += pointLight.colorIntensity.rgb * contribution;
            }
        }
    }
    return accumulatedLight;
}

void main() {
    vec4 texel = texture(Sampler0, texCoord0);
    vec4 color = texel * vertexColor;
    // The RT terrain pipeline is used for the compact opaque/cutout layer.
    // Keep cutout texels transparent even if a stale layer uniform is observed.
    float alphaThreshold = TerrainLayer == 3 ? AlphaCutout : max(AlphaCutout, 0.5);
    if (RtDebugView == 7) {
        bool alphaAccepted = color.a >= alphaThreshold;
        fragColor = vec4(alphaAccepted ? vec3(0.05, 1.0, 0.1) : vec3(1.0, 0.02, 0.02), 1.0);
        return;
    }
    if (color.a < alphaThreshold) {
        discard;
    }

    bool translucentLayer = TerrainLayer == 3;
    bool rtOnly = RtViewMode == 1;
    int primaryAttribute = int(round(clamp(rtMaterialAttribute, 0.0, 127.0)));
    int primaryMaterial = primaryAttribute & 0x7;
    float primaryEmission = float((primaryAttribute >> 3) & 0xF) * (1.0 / 15.0);
    if (RtDebugView == 1) {
        vec3 debugNormal = length(worldNormal) > 0.5
            ? normalize(worldNormal)
            : normalize(cross(dFdx(worldPosition), dFdy(worldPosition)));
        fragColor = vec4(debugNormal * 0.5 + 0.5, 1.0);
        return;
    }
    if (RtDebugView == 2) {
        fragColor = vec4(rtMaterialDebugColor(primaryMaterial), 1.0);
        return;
    }
    if (RtDebugView == 3) {
        fragColor = vec4(0.05, lightLevels.y * 0.65, lightLevels.y, 1.0);
        return;
    }
    if (RtDebugView == 4) {
        fragColor = vec4(lightLevels.x, lightLevels.x * 0.35, 0.02, 1.0);
        return;
    }
    if (RtDebugView == 5) {
        fragColor = vec4(primaryEmission, primaryEmission * 0.12, primaryEmission * 0.02, 1.0);
        return;
    }
    bool reflectiveWater = translucentLayer
        && primaryMaterial == RT_MATERIAL_WATER
        && WaterReflections != 0;
    bool reflectiveBlock = primaryMaterial == RT_MATERIAL_REFLECTIVE
        && BlockReflections != 0;
    bool reflectiveSurface = reflectiveWater || reflectiveBlock;
    if (RtDebugView == 8 && !reflectiveSurface) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    if (translucentLayer && !reflectiveSurface && !rtOnly && RtDebugView == 0) {
        fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
        return;
    }

    if (reflectiveSurface
            && RtDebugView != 6
            && RtDebugView != 9
            && RtDebugView != 10
            && RtDebugView != 11
            && RtDebugView != 12
            && RtDebugView != 13
            && RtDebugView != 14) {
        vec3 surfaceNormal = length(worldNormal) > 0.5
            ? normalize(worldNormal)
            : normalize(cross(dFdx(worldPosition), dFdy(worldPosition)));
        vec3 incident = normalize(cameraIncident);
        if (dot(surfaceNormal, incident) > 0.0) {
            surfaceNormal = -surfaceNormal;
        }
        vec3 reflectionDirection = normalize(reflect(incident, surfaceNormal));
        vec3 reflectionOrigin = offsetRayOrigin(worldPosition + surfaceNormal * 0.035, surfaceNormal);
        float normalizedReflectionDistance;
        vec3 ignoredReflectedDynamicLight;
        vec3 reflectedColor = traceReflection(
            reflectionOrigin,
            reflectionDirection,
            WaterReflectionDistance,
            normalizedReflectionDistance,
            ignoredReflectedDynamicLight
        );
        if (RtDebugView == 8) {
            fragColor = vec4(rtDistanceDebugColor(normalizedReflectionDistance), 1.0);
            return;
        }
        float viewCosine = clamp(dot(-incident, surfaceNormal), 0.0, 1.0);
        float fresnelBase = reflectiveWater ? 0.08 : 0.18;
        float fresnel = fresnelBase + (1.0 - fresnelBase) * pow(1.0 - viewCosine, 5.0);
        float reflectionMix = rtOnly
            ? 1.0
            : clamp((reflectiveWater ? WaterReflectionStrength : BlockReflectionStrength) * fresnel, 0.0, 1.0);
        color.rgb = mix(color.rgb, reflectedColor, reflectionMix);
        if (rtOnly) {
            color.a = 1.0;
        }
        fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
        return;
    }

    vec3 sunDirection = normalize(SunDirection);
    float daylight = smoothstep(-0.02, 0.08, sunDirection.y);
    vec3 derivativeNormal = normalize(cross(dFdx(worldPosition), dFdy(worldPosition)));
    vec3 geometricNormal = length(worldNormal) > 0.5 ? normalize(worldNormal) : derivativeNormal;
    if (dot(derivativeNormal, geometricNormal) < 0.0) {
        derivativeNormal = -derivativeNormal;
    }
    if (RtDebugView == 12) {
        vec3 mirrorSunlight = traceMirrorSunlight(worldPosition, geometricNormal);
        fragColor = vec4(mirrorSunlight / (vec3(1.0) + mirrorSunlight), 1.0);
        return;
    }
    if (RtDebugView == 13) {
        vec3 mirrorDynamicLight = traceIndirectLight(worldPosition, geometricNormal, true);
        fragColor = vec4(mirrorDynamicLight / (vec3(1.0) + mirrorDynamicLight), 1.0);
        return;
    }
    if (RtDebugView == 14) {
        vec3 indirectLight = traceIndirectLight(worldPosition, geometricNormal, false);
        fragColor = vec4(indirectLight / (vec3(1.0) + indirectLight), 1.0);
        return;
    }
    bool rtLighting = RtDirectLighting != 0 || rtOnly;
    bool twoSidedSurface = TerrainLayer == 1
        || TerrainLayer == 2
        || primaryMaterial == RT_MATERIAL_LEAVES;
    float surfaceToLight = rtLighting
        ? (twoSidedSurface
            ? abs(dot(geometricNormal, sunDirection))
            : max(dot(geometricNormal, sunDirection), 0.0))
        : abs(dot(derivativeNormal, sunDirection));
    float shadowWeight = daylight * smoothstep(0.08, 0.20, surfaceToLight);
    vec3 originNormal = rtLighting ? geometricNormal : derivativeNormal;
    originNormal *= dot(originNormal, sunDirection) < 0.0 ? -1.0 : 1.0;
    vec3 rayOrigin = offsetRayOrigin(
        worldPosition + originNormal * 0.04 + sunDirection * 0.01,
        originNormal
    );
    float occlusion = 0.0;
    if (shadowWeight > 0.001) {
        occlusion = traceSunOcclusion(rayOrigin, sunDirection, ShadowSoftness);
    }
    float visibility = mix(1.0, 1.0 - ShadowStrength * occlusion, shadowWeight);
    if (RtDebugView == 6) {
        fragColor = vec4(vec3(1.0 - occlusion), 1.0);
        return;
    }
    uint evaluatedDynamicLights;
    uint dynamicShadowRays;
    vec3 dynamicLighting = evaluateDynamicRtLights(
        worldPosition,
        geometricNormal,
        twoSidedSurface,
        true,
        evaluatedDynamicLights,
        dynamicShadowRays
    );
    if (RtDebugView == 9) {
        fragColor = vec4(dynamicLighting / (vec3(1.0) + dynamicLighting), 1.0);
        return;
    }
    if (RtDebugView == 10) {
        float budgetScale = RtDynamicShadowMaxLights <= 0
            ? 16.0
            : max(float(RtDynamicShadowMaxLights), 1.0);
        float budgetLoad = clamp(float(dynamicShadowRays) / budgetScale, 0.0, 1.0);
        float unshadowedRatio = evaluatedDynamicLights == 0u
            ? 0.0
            : 1.0 - float(dynamicShadowRays) / float(evaluatedDynamicLights);
        vec3 budgetColor = mix(vec3(0.02, 0.35, 0.04), vec3(1.0, 0.04, 0.01), budgetLoad);
        budgetColor = mix(budgetColor, vec3(0.08, 0.15, 1.0), clamp(unshadowedRatio, 0.0, 1.0) * 0.45);
        fragColor = vec4(budgetColor, 1.0);
        return;
    }

    float skyVisibility = 1.0;
    if (RtSkyOcclusion != 0) {
        skyVisibility = accumulateSkyVisibility(
            traceSkyVisibility(worldPosition, geometricNormal),
            vertexDistance,
            geometricNormal
        );
    }
    if (RtDebugView == 11) {
        fragColor = vec4(vec3(skyVisibility), 1.0);
        return;
    }

    if (rtLighting) {
        float sunHeight = clamp(sunDirection.y, 0.0, 1.0);
        float skyLevel = clamp(lightLevels.y, 0.0, 1.0);
        float blockLevel = pow(clamp(lightLevels.x, 0.0, 1.0), 1.35);
        vec3 directSun = rtSunColor(sunHeight)
            * (0.82 * daylight * surfaceToLight * visibility * SunLightStrength);
        vec3 tracedSky = rtSkyColor(daylight) * skyVisibility * SkyLightStrength;
        vec3 lighting = rtOnly
            ? vec3(0.002) + tracedSky * 0.72 + directSun
            : vec3(0.015)
                + tracedSky * skyLevel
                + directSun
                + rtBlockColor() * (0.90 * blockLevel * BlockLightStrength);
        if (RtMirrorSunlight != 0
                && !twoSidedSurface
                && (occlusion > 0.01 || surfaceToLight < 0.01)) {
            lighting += traceMirrorSunlight(worldPosition, geometricNormal)
                * RtMirrorSunlightStrength;
        }
        if (RtIndirectLighting != 0 && !twoSidedSurface) {
            vec3 indirectLight = accumulateIndirectLight(
                traceIndirectLight(worldPosition, geometricNormal, false),
                vertexDistance
            );
            lighting += indirectLight
                * RtIndirectLightStrength;
        }
        lighting += dynamicLighting;
        vec3 materialTint = rtOnly ? vec3(1.0) : rtVertexColor.rgb;
        vec3 baseColor = texel.rgb * materialTint;
        vec3 emittedLight = baseColor * primaryEmission * rtEmissionBoost(primaryMaterial);
        color = vec4(baseColor * lighting + emittedLight, texel.a * rtVertexColor.a);
    } else {
        color.rgb = color.rgb * visibility
            + texel.rgb * rtVertexColor.rgb * dynamicLighting;
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
