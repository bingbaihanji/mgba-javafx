#version 330 core
// ============================================================
// Beauty HD —— 桌面版画面美化（GLSL 330 core）
//
// 效果：
//   1. Sharp bilinear：采样点向最近纹素中心收拢，放大后不发糊；
//   2. 轻度 unsharp mask：只提边缘，不做整体锐化；
//   3. 亮部柔光：只让高光轻微溢出，避免画面整体发灰；
//   4. Vibrance + 柔和 S 曲线：低饱和区域多提色，高饱和区域少动；
//   5. 极轻暗角：把视线收拢到画面中心。
//
// 用于 mGBA Desktop：视频 → 着色器 → 加载自定义着色器...
// 输入：vUV / vColor / uTex / uTexelSize    输出：fragColor
// ============================================================

in vec2 vUV;
in vec4 vColor;
uniform sampler2D uTex;
uniform vec2 uTexelSize;
out vec4 fragColor;

/** 采样纹理（UV 夹到 0–1，避免边缘采到重复像素）。 */
vec3 sampleAt(vec2 uv) {
    return texture(uTex, clamp(uv, vec2(0.0), vec2(1.0))).rgb * vColor.rgb;
}

void main() {
    vec2 texSize = 1.0 / uTexelSize;
    vec2 texel = uTexelSize;

    // ---- 1. Sharp bilinear：向纹素中心收拢 30% ----
    vec2 pixel = vUV * texSize;
    vec2 center = floor(pixel) + 0.5;
    vec2 sharpUV = clamp((center + (pixel - center) * 0.70) / texSize,
                         vec2(0.0), vec2(1.0));

    vec3 base = sampleAt(sharpUV);
    vec3 left = sampleAt(sharpUV - vec2(texel.x, 0.0));
    vec3 right = sampleAt(sharpUV + vec2(texel.x, 0.0));
    vec3 up = sampleAt(sharpUV + vec2(0.0, texel.y));
    vec3 down = sampleAt(sharpUV - vec2(0.0, texel.y));
    vec3 around = (left + right + up + down) * 0.25;

    // ---- 2. 轻度 unsharp mask ----
    vec3 color = clamp(base + (base - around) * 0.14, 0.0, 1.0);

    // ---- 3. 只有亮部参与的柔光 ----
    float luma = dot(color, vec3(0.299, 0.587, 0.114));
    color = min(color + around * (smoothstep(0.60, 1.0, luma) * 0.18), vec3(1.0));

    // ---- 4a. Vibrance：低饱和多提，高饱和少提 ----
    float maxC = max(color.r, max(color.g, color.b));
    float minC = min(color.r, min(color.g, color.b));
    float saturation = maxC - minC;
    float gray = dot(color, vec3(0.2126, 0.7152, 0.0722));
    color = clamp(mix(vec3(gray), color, 1.0 + mix(0.20, 0.05, saturation)), 0.0, 1.0);

    // ---- 4b. 柔和 S 曲线 ----
    vec3 curved = color * color * (3.0 - 2.0 * color);
    color = mix(color, curved, 0.14);

    // ---- 5. 极轻暗角 ----
    vec2 d = vUV - 0.5;
    color *= 1.0 - dot(d, d) * 0.10;

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
