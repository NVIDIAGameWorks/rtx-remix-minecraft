// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform mat4 ProjMat;

in vec2 texCoord;
in vec4 vertexColor;
in vec3 viewPosition;

out vec4 fragColor;

vec2 projectedUv(vec3 direction) {
    vec4 clip = ProjMat * vec4(direction, 0.0);
    float safeW = clip.w < 0.0
        ? -max(abs(clip.w), 0.0001)
        : max(abs(clip.w), 0.0001);
    return clip.xy / safeW * 0.5 + 0.5;
}

float screenFade(vec2 uv) {
    vec2 edgeDistance = min(uv, vec2(1.0) - uv);
    return smoothstep(0.0, 0.04, min(edgeDistance.x, edgeDistance.y));
}

void main() {
    vec4 rainTexture = texture(Sampler0, texCoord);
    float coverage = rainTexture.a * vertexColor.a;
    if (coverage <= 0.01) {
        discard;
    }

    float profileX = clamp(texCoord.x, 0.0, 1.0) * 2.0 - 1.0;
    float profileZ = sqrt(max(0.0, 1.0 - profileX * profileX));

    vec3 positionDx = dFdx(viewPosition);
    vec3 positionDy = dFdy(viewPosition);
    vec2 uvDx = dFdx(texCoord);
    vec2 uvDy = dFdy(texCoord);
    float determinant = uvDx.x * uvDy.y - uvDy.x * uvDx.y;
    float inverseDeterminant = sign(determinant)
        / max(abs(determinant), 0.000001);
    vec3 tangent = normalize(
        (positionDx * uvDy.y - positionDy * uvDx.y) * inverseDeterminant);
    vec3 bitangent = normalize(
        (positionDy * uvDx.x - positionDx * uvDy.x) * inverseDeterminant);

    vec3 incident = normalize(viewPosition);
    vec3 sheetNormal = normalize(cross(tangent, bitangent));
    sheetNormal = faceforward(sheetNormal, incident, sheetNormal);

    vec2 rainTexel = 1.0 / vec2(textureSize(Sampler0, 0));
    float alphaBefore = texture(Sampler0, texCoord - vec2(0.0, rainTexel.y)).a;
    float alphaAfter = texture(Sampler0, texCoord + vec2(0.0, rainTexel.y)).a;
    float verticalSlope = clamp(
        (alphaAfter - alphaBefore) * 2.0,
        -0.45,
        0.45);

    vec3 lensNormal = normalize(
        tangent * profileX
        + bitangent * verticalSlope
        + sheetNormal * profileZ);
    lensNormal = faceforward(lensNormal, incident, lensNormal);

    const float AIR_TO_WATER_IOR = 1.0 / 1.333;
    vec3 refractedDirection = refract(incident, lensNormal, AIR_TO_WATER_IOR);

    vec2 framebufferSize = vec2(textureSize(Sampler1, 0));
    vec2 screenUv = vec2(
        gl_FragCoord.x / framebufferSize.x,
        1.0 - gl_FragCoord.y / framebufferSize.y);
    vec2 angularOffset = projectedUv(refractedDirection)
        - projectedUv(incident);
    angularOffset.y = -angularOffset.y;
    vec2 offsetPixels = clamp(
        angularOffset * framebufferSize * 0.08,
        vec2(-12.0),
        vec2(12.0));
    offsetPixels *= rainTexture.a * vertexColor.a;
    vec2 refractedUv = clamp(
        screenUv + offsetPixels / framebufferSize,
        vec2(0.0),
        vec2(1.0));

    vec3 sourceColor = texture(Sampler1, screenUv).rgb;
    vec3 refractedColor = texture(Sampler1, refractedUv).rgb;

    vec3 reflectedDirection = reflect(incident, lensNormal);
    vec2 reflectedUv = projectedUv(reflectedDirection);
    reflectedUv.y = 1.0 - reflectedUv.y;
    float reflectionValidity = step(reflectedDirection.z, -0.01)
        * screenFade(reflectedUv);
    vec3 reflectedColor = texture(
        Sampler1,
        clamp(reflectedUv, vec2(0.0), vec2(1.0))).rgb;
    reflectedColor = mix(sourceColor, reflectedColor, reflectionValidity);

    vec3 rainTint = vec3(0.76, 0.86, 1.0);
    vec3 transmissionColor = mix(sourceColor, refractedColor, vertexColor.a);
    transmissionColor = mix(
        transmissionColor,
        transmissionColor * rainTint,
        0.24 * rainTexture.a * vertexColor.a);

    float normalDotView = clamp(dot(-incident, lensNormal), 0.0, 1.0);
    const float WATER_F0 = 0.02037;
    float fresnel = WATER_F0
        + (1.0 - WATER_F0) * pow(1.0 - normalDotView, 5.0);
    float reflectionWeight = fresnel
        * smoothstep(0.01, 0.30, rainTexture.a)
        * vertexColor.a;
    vec3 color = mix(transmissionColor, reflectedColor, reflectionWeight);

    fragColor = vec4(color, 1.0);
}
