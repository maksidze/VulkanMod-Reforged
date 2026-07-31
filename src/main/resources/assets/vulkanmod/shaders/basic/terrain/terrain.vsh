#version 460

#include "light.glsl"
#include "fog.glsl"

layout (binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    vec3 CameraPosition;
    mat4 RtPreviousMVP;
    vec3 RtPreviousCameraPosition;
};

layout (push_constant) uniform pushConstant {
    vec3 ChunkOffset;
    vec3 WorldOrigin;
};

layout (binding = 3) uniform sampler2D Sampler2;


layout (location = 0) out float vertexDistance;
layout (location = 1) out vec4 vertexColor;
layout (location = 2) out vec2 texCoord0;
layout (location = 3) out vec3 worldPosition;
layout (location = 4) out vec4 rtVertexColor;
layout (location = 5) out vec2 lightLevels;
layout (location = 6) out vec3 worldNormal;
layout (location = 7) out vec3 cameraIncident;
layout (location = 8) out float rtMaterialAttribute;
layout (location = 9) out vec4 rtPreviousClipPosition;
layout (location = 10) out float rtPreviousDistance;

//Compressed Vertex
layout (location = 0) in ivec4 Position;
layout (location = 1) in vec4 Color;
layout (location = 2) in uvec2 UV0;
layout (location = 3) in vec4 Normal;

const float UV_INV = 1.0 / 32768.0;
//const vec3 POSITION_INV = vec3(1.0 / 1024.0);
const vec3 POSITION_INV = vec3(1.0 / 2048.0);
const vec3 POSITION_OFFSET = vec3(4.0);

void main() {
    const vec3 baseOffset = bitfieldExtract(ivec3(gl_InstanceIndex) >> ivec3(0, 16, 8), 0, 8);
    const vec4 pos = vec4(fma(Position.xyz, POSITION_INV, ChunkOffset + baseOffset), 1.0);
    gl_Position = MVP * pos;

    vertexDistance = fog_distance(pos.xyz, 0);
    vertexColor = Color * sample_lightmap2(Sampler2, Position.a);
    texCoord0 = UV0 * UV_INV;
    worldPosition = fma(Position.xyz, POSITION_INV, WorldOrigin + baseOffset + POSITION_OFFSET);
    rtVertexColor = Color;
    lightLevels = vec2(
        bitfieldExtract(uint(Position.a), 4, 4),
        bitfieldExtract(uint(Position.a), 12, 4)
    ) * (1.0 / 15.0);
    worldNormal = Normal.xyz;
    cameraIncident = worldPosition - CameraPosition;
    // The normalized high byte carries a compact 7-bit RT material/emission attribute.
    rtMaterialAttribute = round(max(Normal.w, 0.0) * 127.0);
    vec3 previousCameraRelativePosition = worldPosition - RtPreviousCameraPosition;
    rtPreviousClipPosition = RtPreviousMVP * vec4(previousCameraRelativePosition, 1.0);
    rtPreviousDistance = length(previousCameraRelativePosition);
}

////Default Vertex
//layout(location = 0) in vec3 Position;
//layout(location = 1) in vec4 Color;
//layout(location = 2) in vec2 UV0;
//layout(location = 3) in ivec2 UV2;
//layout(location = 4) in vec3 Normal;
//
//void main() {
//    const vec3 baseOffset = bitfieldExtract(ivec3(gl_InstanceIndex) >> ivec3(0, 16, 8), 0, 8);
//    const vec4 pos = vec4(Position.xyz + baseOffset, 1.0);
//    gl_Position = MVP * pos;
//
//    vertexDistance = length((ModelViewMat * pos).xyz);
//    vertexColor = Color * minecraft_sample_lightmap(Sampler2, UV2);
//    texCoord0 = UV0;
//    //    normal = MVP * vec4(Normal, 0.0);
//}
