package com.mgba.bingbaihanji.core.settings;

/**
 * 应用设置（持久化 DTO）。
 *
 * <p>字段是 public 的：Gson 直接按字段名映射，序列化代码里没有任何注解，
 * 模型也不依赖具体 JSON 库——换掉 Gson 只需改 {@link SettingsStore}。
 */
public final class AppSettings {

    /** 默认倍速。 */
    public double gameSpeed = 4.0;

    /** 音量（0–100）。 */
    public int soundVolume = 100;

    /** 声音开关。 */
    public boolean enableSound = true;

    /** 是否拉伸填充（关闭时等比 letterbox）。 */
    public boolean stretchToFit = false;

    /** 退出时保存到自动槽位。 */
    public boolean autoSaveOnExit = true;

    /** 显示调试信息浮层。 */
    public boolean showDebugOverlay = false;

    /** 着色器预设名（{@code ShaderPreset} 的枚举名）。 */
    public String shaderPreset = "OFF";

    /** 自定义着色器文件路径，空串表示未选择。 */
    public String customShaderPath = "";

    /**
     * 复制一份设置（对话框确认时用，取消不能改动原对象）。
     *
     * @return 字段完全相同的副本
     */
    public AppSettings copy() {
        AppSettings copy = new AppSettings();
        copy.gameSpeed = gameSpeed;
        copy.soundVolume = soundVolume;
        copy.enableSound = enableSound;
        copy.stretchToFit = stretchToFit;
        copy.autoSaveOnExit = autoSaveOnExit;
        copy.showDebugOverlay = showDebugOverlay;
        copy.shaderPreset = shaderPreset;
        copy.customShaderPath = customShaderPath;
        return copy;
    }

    /**
     * 实际生效的音量。
     *
     * @return 关闭声音时为 0，否则为 {@link #soundVolume} 换算到 0–1
     */
    public float effectiveVolume() {
        if (!enableSound) {
            return 0f;
        }
        return Math.clamp(soundVolume, 0, 100) / 100f;
    }
}
