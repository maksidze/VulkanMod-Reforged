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
    float FogStart;
    float FogEnd;
    float AlphaCutout;
    int RtDynamicLightShadows;
    float RtDynamicLightStrength;
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

layout(location = 0) in float vertexDistance;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec3 worldPosition;
layout(location = 4) in vec4 rtVertexColor;
layout(location = 5) in vec2 lightLevels;
layout(location = 6) in vec3 worldNormal;
layout(location = 7) in vec3 cameraIncident;
layout(location = 8) in float rtMaterialAttribute;

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

    return rayQueryGetIntersectionTypeEXT(query, true)
        != gl_RayQueryCommittedIntersectionNoneEXT;
}

bool traceOcclusion(vec3 origin, vec3 direction) {
    return traceOcclusionRange(origin, direction, ShadowDistance);
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

vec3 offsetRayOrigin(vec3 position, vec3 normal);
vec3 evaluateDynamicRtLights(vec3 position, vec3 normal, bool twoSided, bool traceShadows);

vec3 traceReflection(vec3 origin, vec3 direction, out float normalizedHitDistance) {
    normalizedHitDistance = -1.0;
    rayQueryEXT query;
    rayQueryInitializeEXT(
        query,
        TopLevelAS,
        gl_RayFlagsTerminateOnFirstHitEXT,
        0xFF,
        origin,
        0.03,
        direction,
        WaterReflectionDistance
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
    vec3 hitColor = textureLod(Sampler0, hitUv, 0.0).rgb;
    uint packedNormalMaterial = PackedRtUvs[triangleUvBase + 3u];
    uint packedLights = PackedRtUvs[triangleUvBase + 4u];
    vec2 hitLightLevels = interpolateRtLight(packedLights, barycentrics);
    vec3 hitNormal = unpackRtNormal(packedNormalMaterial);
    if (dot(hitNormal, -direction) < 0.0) {
        hitNormal = -hitNormal;
    }
    int hitMaterial = unpackRtMaterial(packedNormalMaterial);
    float hitEmission = unpackRtEmission(packedNormalMaterial);
    float hitDistance = rayQueryGetIntersectionTEXT(query, true);
    normalizedHitDistance = hitDistance / max(WaterReflectionDistance, 0.001);
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
    hitLighting += evaluateDynamicRtLights(hitPosition, hitNormal, hitCutout, false);
    vec3 reflectedSurface = hitColor * hitLighting
        + hitColor * hitEmission * rtEmissionBoost(hitMaterial);
    float distanceFade = smoothstep(
        WaterReflectionDistance * 0.70,
        WaterReflectionDistance,
        hitDistance
    );
    return mix(reflectedSurface, reflectionSky(direction), distanceFade);
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

vec3 evaluateDynamicRtLights(vec3 position, vec3 normal, bool twoSided, bool traceShadows) {
    vec3 accumulatedLight = vec3(0.0);
    ivec3 surfaceCell = ivec3(floor(position * (1.0 / 32.0)));
    uint totalLightCount = RtDynamicLightHeader.x;

    for (int cellZ = -1; cellZ <= 1; cellZ++) {
        for (int cellY = -1; cellY <= 1; cellY++) {
            for (int cellX = -1; cellX <= 1; cellX++) {
                int packedRange = rtFindDynamicCell(surfaceCell + ivec3(cellX, cellY, cellZ));
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

                    bool shadowed = false;
                    if (traceShadows && RtDynamicLightShadows != 0) {
                        vec3 originNormal = dot(normal, lightDirection) < 0.0 ? -normal : normal;
                        vec3 lightRayOrigin = offsetRayOrigin(
                            position + originNormal * 0.04 + lightDirection * 0.01,
                            originNormal
                        );
                        float traceDistance = max(distanceToLight - 0.55, 0.03);
                        shadowed = traceOcclusionRange(lightRayOrigin, lightDirection, traceDistance);
                    }
                    if (!shadowed) {
                        accumulatedLight += colorIntensity.rgb * contribution;
                    }
                }
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
    if (RtDebugView == 8 && !reflectiveWater) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    if (translucentLayer && !reflectiveWater && !rtOnly && RtDebugView == 0) {
        fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
        return;
    }

    if (reflectiveWater && RtDebugView != 6 && RtDebugView != 9) {
        vec3 waterNormal = length(worldNormal) > 0.5
            ? normalize(worldNormal)
            : normalize(cross(dFdx(worldPosition), dFdy(worldPosition)));
        vec3 incident = normalize(cameraIncident);
        if (dot(waterNormal, incident) > 0.0) {
            waterNormal = -waterNormal;
        }
        vec3 reflectionDirection = normalize(reflect(incident, waterNormal));
        vec3 reflectionOrigin = offsetRayOrigin(worldPosition + waterNormal * 0.035, waterNormal);
        float normalizedReflectionDistance;
        vec3 reflectedColor = traceReflection(
            reflectionOrigin,
            reflectionDirection,
            normalizedReflectionDistance
        );
        if (RtDebugView == 8) {
            fragColor = vec4(rtDistanceDebugColor(normalizedReflectionDistance), 1.0);
            return;
        }
        float viewCosine = clamp(dot(-incident, waterNormal), 0.0, 1.0);
        float fresnel = 0.08 + 0.92 * pow(1.0 - viewCosine, 5.0);
        float reflectionMix = rtOnly
            ? 1.0
            : clamp(WaterReflectionStrength * fresnel, 0.0, 1.0);
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
    vec3 dynamicLighting = evaluateDynamicRtLights(
        worldPosition,
        geometricNormal,
        twoSidedSurface,
        true
    );
    if (RtDebugView == 9) {
        fragColor = vec4(dynamicLighting / (vec3(1.0) + dynamicLighting), 1.0);
        return;
    }

    if (rtLighting) {
        float sunHeight = clamp(sunDirection.y, 0.0, 1.0);
        float skyLevel = clamp(lightLevels.y, 0.0, 1.0);
        float blockLevel = pow(clamp(lightLevels.x, 0.0, 1.0), 1.35);
        vec3 directSun = rtSunColor(sunHeight)
            * (0.82 * daylight * surfaceToLight * visibility * SunLightStrength);
        vec3 lighting = rtOnly
            ? vec3(0.002) + directSun
            : vec3(0.015)
                + rtSkyColor(daylight) * skyLevel * SkyLightStrength
                + directSun
                + rtBlockColor() * (0.90 * blockLevel * BlockLightStrength);
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
