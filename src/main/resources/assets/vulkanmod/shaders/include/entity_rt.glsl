#ifndef VULKANMOD_ENTITY_RT_GLSL
#define VULKANMOD_ENTITY_RT_GLSL

#extension GL_EXT_ray_query : require

bool rt_entity_shadow(vec3 origin, vec3 direction, float maxDistance) {
    rayQueryEXT query;
    rayQueryInitializeEXT(query, TopLevelAS, gl_RayFlagsTerminateOnFirstHitEXT,
            0xff, origin, 0.01, direction, maxDistance);
    while (rayQueryProceedEXT(query)) { }
    return rayQueryGetIntersectionTypeEXT(query, true) != gl_RayQueryCommittedIntersectionNoneEXT;
}

const ivec3 RT_ENTITY_LIGHT_CELL_OFFSETS[27] = ivec3[](
    ivec3( 0,  0,  0), ivec3(-1,  0,  0), ivec3( 1,  0,  0),
    ivec3( 0, -1,  0), ivec3( 0,  1,  0), ivec3( 0,  0, -1), ivec3( 0,  0,  1),
    ivec3(-1, -1, -1), ivec3(-1, -1,  1), ivec3(-1,  1, -1), ivec3(-1,  1,  1),
    ivec3( 1, -1, -1), ivec3( 1, -1,  1), ivec3( 1,  1, -1), ivec3( 1,  1,  1),
    ivec3(-1, -1,  0), ivec3(-1,  1,  0), ivec3( 1, -1,  0), ivec3( 1,  1,  0),
    ivec3(-1,  0, -1), ivec3(-1,  0,  1), ivec3( 1,  0, -1), ivec3( 1,  0,  1),
    ivec3( 0, -1, -1), ivec3( 0, -1,  1), ivec3( 0,  1, -1), ivec3( 0,  1,  1)
);

uint rt_entity_light_cell_hash(ivec3 cell) {
    return (uint(cell.x) * 73856093u
        ^ uint(cell.y) * 19349663u
        ^ uint(cell.z) * 83492791u) & 8191u;
}

int rt_entity_find_light_cell(ivec3 cell) {
    uint hash = rt_entity_light_cell_hash(cell);
    for (uint probe = 0u; probe < 32u; probe++) {
        ivec4 entry = RtDynamicLightCells[(hash + probe) & 8191u];
        if (entry.w == -1) return -1;
        if (all(equal(entry.xyz, cell))) return entry.w;
    }
    return -1;
}

vec3 rt_entity_light(vec3 worldPosition, vec3 normal, vec3 baseColor) {
    vec3 n = normalize(normal);
    vec3 sun = normalize(SunDirection);
    float ndotl = max(dot(n, sun), 0.0);
    float visibility = 1.0;
    if (RtDirectLighting != 0 && ndotl > 0.001 && ShadowStrength > 0.001) {
        vec3 origin = worldPosition + n * 0.01;
        if (rt_entity_shadow(origin, sun, ShadowDistance)) {
            visibility = 1.0 - ShadowStrength;
        }
    }
    float sky = max(0.0, SkyLightStrength) * 0.35;
    vec3 dynamicLight = vec3(0.0);
    uint shadowRays = 0u;
    uint totalLights = RtDynamicLightHeader.x;
    uint selectedLightIndices[128];
    float selectedContributions[128];
    uint selectedLightCount = 0u;
    uint selectedLightLimit = RtDynamicMaxLightsPerPixel <= 0
        ? 128u
        : uint(clamp(RtDynamicMaxLightsPerPixel, 1, 128));
    bool unlimitedLights = RtDynamicMaxLightsPerPixel <= 0;
    ivec3 surfaceCell = ivec3(floor(worldPosition * (1.0 / 32.0)));
    for (int cellOffsetIndex = 0; cellOffsetIndex < 27; cellOffsetIndex++) {
        int packedRange = rt_entity_find_light_cell(surfaceCell + RT_ENTITY_LIGHT_CELL_OFFSETS[cellOffsetIndex]);
        if (packedRange == -1) continue;
        uint rangeBits = uint(packedRange);
        uint lightStart = rangeBits & 0xFFFFFu;
        uint lightCount = rangeBits >> 20u;
        for (uint cellLightIndex = 0u; cellLightIndex < lightCount; cellLightIndex++) {
            uint lightIndex = lightStart + cellLightIndex;
            if (lightIndex >= totalLights) break;
            RtPointLight pointLight = RtDynamicLights[lightIndex];
            vec3 toLight = pointLight.positionRadius.xyz - worldPosition;
            float distanceToLight = length(toLight);
            float radius = pointLight.positionRadius.w;
            if (distanceToLight <= 0.02 || distanceToLight >= radius || radius <= 0.0) continue;
            vec3 lightDirection = toLight / distanceToLight;
            float surfaceWeight = abs(dot(n, lightDirection));
            if (surfaceWeight <= 0.001) continue;
            float radialFalloff = max(1.0 - distanceToLight / radius, 0.0);
            float contribution = surfaceWeight * radialFalloff * radialFalloff
                * (1.5 / (1.0 + 0.018 * distanceToLight * distanceToLight))
                * pointLight.colorIntensity.a * RtDynamicLightStrength;
            if (contribution <= 0.002) continue;

            if (unlimitedLights) {
                bool shadowed = false;
                bool withinShadowBudget = RtDynamicShadowMaxLights <= 0
                    || shadowRays < uint(RtDynamicShadowMaxLights);
                if (RtDynamicLightShadows != 0 && withinShadowBudget
                        && distanceToLight <= RtDynamicShadowDistance) {
                    vec3 shadowOrigin = worldPosition + n * 0.04 + lightDirection * 0.01;
                    shadowed = rt_entity_shadow(
                        shadowOrigin,
                        lightDirection,
                        max(distanceToLight - 0.05, 0.03)
                    );
                    shadowRays++;
                }
                if (!shadowed) {
                    dynamicLight += pointLight.colorIntensity.rgb * contribution;
                }
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

    for (uint selectedIndex = 0u; selectedIndex < selectedLightCount; selectedIndex++) {
        RtPointLight pointLight = RtDynamicLights[selectedLightIndices[selectedIndex]];
        vec3 toLight = pointLight.positionRadius.xyz - worldPosition;
        float distanceToLight = length(toLight);
        vec3 lightDirection = toLight / distanceToLight;
        bool shadowed = false;
        bool withinShadowBudget = RtDynamicShadowMaxLights <= 0
            || shadowRays < uint(RtDynamicShadowMaxLights);
        if (RtDynamicLightShadows != 0 && withinShadowBudget
                && distanceToLight <= RtDynamicShadowDistance) {
            vec3 shadowOrigin = worldPosition + n * 0.04 + lightDirection * 0.01;
            shadowed = rt_entity_shadow(shadowOrigin, lightDirection, max(distanceToLight - 0.05, 0.03));
            shadowRays++;
        }
        if (!shadowed) {
            dynamicLight += pointLight.colorIntensity.rgb
                * selectedContributions[selectedIndex];
        }
    }
    return baseColor * (sky + max(0.0, SunLightStrength) * ndotl * visibility + dynamicLight);
}

#endif
