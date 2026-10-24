package com.mgba.bingbaihanji.platform.desktop.video;

/** 视频后处理预设。 */
public enum ShaderPreset {

    /** 关闭后处理，直接采样。 */
    OFF("关闭"),

    /** LCD 像素网格。 */
    LCD("液晶网格"),

    /** 余弦扫描线。 */
    SCANLINES("扫描线"),

    /** 圆形网点掩模。 */
    DOT("网点"),

    /** 用户自带的 GLSL 片段着色器。 */
    CUSTOM("自定义");

    private final String label;

    ShaderPreset(String label) {
        this.label = label;
    }

    /** 面向用户的名称。 */
    public String label() {
        return label;
    }
}
