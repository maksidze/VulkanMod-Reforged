#version 460
#extension GL_EXT_ray_query : require

#include "fog.glsl"

layout(binding = 2) uniform sampler2D Sampler0;

layout(binding = 1) uniform UBO{
    vec4 ColorModulator;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    vec3 SunDirection;
    float ShadowStrength;
    float ShadowDistance;
    int RtDirectLighting;
    float SunLightStrength;
    float SkyLightStrength;
    int RtDynamicLightShadows;
    float RtDynamicLightStrength;
    int RtDynamicMaxLightsPerPixel;
    int RtDynamicShadowMaxLights;
    float RtDynamicShadowDistance;
};

layout(binding = 5) uniform accelerationStructureEXT TopLevelAS;

struct RtPointLight {
    vec4 positionRadius;
    vec4 colorIntensity;
};

layout(std430, binding = 6) readonly buffer RtDynamicLightStorage {
    uvec4 RtDynamicLightHeader;
    ivec4 RtDynamicLightCells[8192];
    RtPointLight RtDynamicLights[];
};

#include "entity_rt.glsl"

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec4 lightMapColor;
layout(location = 2) in vec4 overlayColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) in float vertexDistance;
layout(location = 5) in vec3 rtWorldPosition;
layout(location = 6) in vec3 rtNormal;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    // The legacy lightmap is deliberately not multiplied here: RT replaces it.
    color.rgb = rt_entity_light(rtWorldPosition, rtNormal, color.rgb);
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
