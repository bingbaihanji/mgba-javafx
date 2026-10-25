package com.mgba.bingbaihanji.platform.desktop.video;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 内置着色器的 GLSL 源码（GLSL 330 core）。
 *
 * <p>片段着色器与 xuan 的标准图像顶点着色器配对，可用输入：
 * {@code vUV}、{@code vColor}；{@code uTex} 为帧纹理，
 * {@code uTexelSize} 为 1/宽、1/高（由渲染器每帧设置）。
 *
 * <p>颜色是**预乘 alpha** 的：只对 {@code rgb} 做调制，{@code a} 保持原值，
 * 否则混合因子 ({@code ONE, ONE_MINUS_SRC_ALPHA}) 会把画面压暗。
 */
public final class ShaderPresets {

    /** 所有内置片段着色器共用的头部。 */
    private static final String HEADER = """
                                         #version 330 core
                                         in vec2 vUV;
                                         in vec4 vColor;
                                         uniform sampler2D uTex;
                                         uniform vec2 uTexelSize;
                                         out vec4 fragColor;
                                         """;

    private ShaderPresets() {
    }

    /**
     * 取预设对应的片段着色器源码。
     *
     * @param preset 预设
     * @return GLSL 源码；{@link ShaderPreset#OFF} 与 {@link ShaderPreset#CUSTOM} 返回 null
     */
    public static String fragmentSource(ShaderPreset preset) {
        return switch (preset) {
            case LCD -> HEADER + """
                                 void main() {
                                     vec4 c = texture(uTex, vUV) * vColor;
                                     vec2 size = 1.0 / uTexelSize;
                                     vec2 f = fract(vUV * size);
                                     // 每个像素边缘 10% 变暗，形成网格线
                                     float gx = smoothstep(0.00, 0.10, f.x) - smoothstep(0.90, 1.00, f.x);
                                     float gy = smoothstep(0.00, 0.10, f.y) - smoothstep(0.90, 1.00, f.y);
                                     float mask = 0.60 + 0.40 * gx * gy;
                                     fragColor = vec4(c.rgb * mask, c.a);
                                 }
                                 """;
            case SCANLINES -> HEADER + """
                                       void main() {
                                           vec4 c = texture(uTex, vUV) * vColor;
                                           float row = fract(vUV.y * (1.0 / uTexelSize.y));
                                           float scan = 0.72 + 0.28 * cos(row * 6.2831853);
                                           fragColor = vec4(c.rgb * scan, c.a);
                                       }
                                       """;
            case DOT -> HEADER + """
                                 void main() {
                                     vec4 c = texture(uTex, vUV) * vColor;
                                     vec2 p = fract(vUV * (1.0 / uTexelSize)) - 0.5;
                                     float mask = 1.0 - smoothstep(0.35, 0.55, length(p));
                                     fragColor = vec4(c.rgb * mix(0.45, 1.0, mask), c.a);
                                 }
                                 """;
            case OFF, CUSTOM -> null;
        };
    }

    /**
     * 读取用户自定义片段着色器。
     *
     * @param file GLSL 文件
     * @return 源码
     * @throws IOException 读取失败
     */
    public static String readCustomSource(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
