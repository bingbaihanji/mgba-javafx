package com.mgba.bingbaihanji.platform.desktop.video;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 Android（GLSL ES 2.0 / 3.0）写法的片段着色器转换成桌面 GLSL 330 core。
 *
 * <h2>为什么必须转换</h2>
 * <p>不带 {@code #version} 的着色器会被驱动按 <b>GLSL 110</b> 编译
 * （{@code varying}、{@code texture2D}、{@code gl_FragColor} 在 110 里都合法），
 * 而本管线的顶点着色器是 330 core。两者版本不一致时链接结果取决于驱动：
 * 有的直接报错（我们能看到并回退），有的"链接成功"但片段着色器里的
 * {@code vTexCoord} 根本没有对应的顶点输出——采样坐标是未定义值，
 * 画面就是全黑。**所以不能把 ES 源码原样交给桌面 GL。**
 *
 * <h2>做的映射</h2>
 * <ul>
 *   <li>删除 {@code precision lowp/mediump/highp ...;} 与 {@code #extension GL_OES_*}；</li>
 *   <li>{@code texture2D} → {@code texture}；</li>
 *   <li>{@code attribute}/{@code varying} → {@code in}（只处理片段着色器）；</li>
 *   <li>{@code vTexCoord} → {@code vUV}（本管线顶点着色器的输出名）；</li>
 *   <li>{@code gl_FragColor} → {@code fragColor}，并补上 {@code out vec4 fragColor;}；</li>
 *   <li>ES 版本声明换成 {@code #version 330 core}；没有版本则补一行。</li>
 * </ul>
 *
 * <p>{@code uTexture} 这类采样器名不必改：GL 的 sampler uniform 默认就是 0 号纹理单元，
 * 而本管线只往 0 号单元绑纹理。
 *
 * <p>已经是桌面 GLSL 330 写法的文件经过本方法后保持不变（幂等）。
 */
public final class CustomShaderAdapter {

    /** 版本声明行。 */
    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^[ \\t]*#version[^\\n]*");

    /** 已有的输出声明。 */
    private static final Pattern FRAG_COLOR_DECL =
            Pattern.compile("(?m)^[ \\t]*out[ \\t]+vec4[ \\t]+fragColor[ \\t]*;");

    private CustomShaderAdapter() {
    }

    /**
     * 转换片段着色器源码。
     *
     * @param source 原始文件内容（ES 2.0/3.0 或桌面 GLSL）
     * @return 可直接编译的 GLSL 330 core 源码
     */
    public static String toDesktopFragmentShader(String source) {
        String text = source;
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }

        // ES 专有：精度限定与移动端扩展
        text = text.replaceAll("(?m)^[ \\t]*precision[ \\t]+(?:lowp|mediump|highp)[ \\t]+\\w+[ \\t]*;[ \\t]*$", "");
        text = text.replaceAll("(?m)^[ \\t]*#extension[ \\t]+GL_OES_[^\\n]*$", "");
        text = text.replaceAll("(?m)^[ \\t]*#extension[ \\t]+GL_EXT_shader_framebuffer_fetch[^\\n]*$", "");

        // 名称映射
        text = replaceWord(text, "texture2D", "texture");
        text = replaceWord(text, "vTexCoord", "vUV");
        text = replaceWord(text, "attribute", "in");
        text = replaceWord(text, "varying", "in");

        boolean usesFragColor = Pattern.compile("\\bgl_FragColor\\b").matcher(text).find();
        if (usesFragColor) {
            text = replaceWord(text, "gl_FragColor", "fragColor");
        }

        // 版本声明：ES 换成 330 core；缺失则补上
        Matcher version = VERSION_LINE.matcher(text);
        if (version.find()) {
            String line = version.group();
            if (line.contains(" es")) {
                text = text.substring(0, version.start()) + "#version 330 core"
                        + text.substring(version.end());
            }
        } else {
            text = "#version 330 core\n" + text;
        }

        // 输出变量声明必须紧跟在版本行之后
        if (usesFragColor && !FRAG_COLOR_DECL.matcher(text).find()) {
            Matcher v = VERSION_LINE.matcher(text);
            if (v.find()) {
                text = text.substring(0, v.end()) + "\nout vec4 fragColor;" + text.substring(v.end());
            }
        }

        return text;
    }

    /** 按整词替换，避免 {@code texture2DProj} 之类的名字被误伤。 */
    private static String replaceWord(String text, String from, String to) {
        return Pattern.compile("\\b" + Pattern.quote(from) + "\\b").matcher(text).replaceAll(to);
    }
}
