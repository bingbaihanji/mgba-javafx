#version 330 core
// ============================================================
// Grayscale —— 桌面版灰度（GLSL 330 core）
//
// 效果：
//   1. 按 Rec.709 亮度权重转灰（比简单平均更接近人眼感受）；
//   2. 轻微 S 曲线，避免中间调灰蒙蒙一片。
//
// 用于 mGBA Desktop：视频 → 着色器 → 加载自定义着色器...
// 输入：vUV / vColor / uTex    输出：fragColor
// ============================================================

in vec2 vUV;
in vec4 vColor;
uniform sampler2D uTex;
out vec4 fragColor;

void main() {
    vec3 color = texture(uTex, vUV).rgb * vColor.rgb;

    // Rec.709 亮度
    float gray = dot(color, vec3(0.2126, 0.7152, 0.0722));

    // 轻微提对比 + 柔和 S 曲线，去掉"蒙了一层灰"的感觉
    gray = clamp((gray - 0.5) * 1.08 + 0.5, 0.0, 1.0);
    gray = mix(gray, gray * gray * (3.0 - 2.0 * gray), 0.12);

    fragColor = vec4(vec3(gray), 1.0);
}
