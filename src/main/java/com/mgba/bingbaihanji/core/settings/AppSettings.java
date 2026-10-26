package com.mgba.bingbaihanji.core.settings;

/**
 * 应用设置（持久化 DTO）。
 *
 * <p>字段是 public 的：Jackson 直接按字段名映射，序列化代码里没有任何注解，
 * 模型也不依赖具体 JSON 库——换掉 Jackson 只需改 {@link SettingsStore}。
 */
public final class AppSettings {

    /** 默认倍速（原速；倍速播放时音频会被静音，避免环形缓冲积压）。 */
    public double gameSpeed = 1.0;

    /** 音量（0–100）。 */
    public int soundVolume = 100;

    /** 声音开关。 */
    public boolean enableSound = true;

    /** 均衡器开关。 */
    public boolean eqEnabled = false;

    /** 均衡器前置增益（dB，-12 ~ +12）。 */
    public double eqPreampDb = 0.0;

    /**
     * 均衡器各频段增益（dB，-12 ~ +12），固定 10 个频段：
     * 31 / 62 / 125 / 250 / 500 / 1k / 2k / 4k / 8k / 16k Hz。
     *
     * <p>用列表而不是数组：旧配置缺项时按 0 dB 补齐即可。
     */
    public java.util.List<Double> eqBandGainsDb = defaultEqBands();

    /** 是否拉伸填充（关闭时等比 letterbox）。 */
    public boolean stretchToFit = false;

    /** 纹理线性过滤；关闭后放大时保持像素边缘清晰。 */
    public boolean linearFiltering = true;

    /** 整数缩放：只按整数倍放大，像素格整齐（窗口足够大时才生效）。 */
    public boolean integerScaling = false;

    /** 退出时保存到自动槽位。 */
    public boolean autoSaveOnExit = true;

    /** 显示调试信息浮层。 */
    public boolean showDebugOverlay = false;

    /** 着色器预设名（{@code ShaderPreset} 的枚举名）。 */
    public String shaderPreset = "OFF";

    /** 自定义着色器文件路径，空串表示未选择。 */
    public String customShaderPath = "";

    /** 上次打开 ROM 的目录（下次文件选择框从这里开始）。 */
    public String lastRomDirectory = "";

    /**
     * 调试信息的分类开关（键为分类 key，缺省视为开启）。
     *
     * <p>用 Map 而不是固定字段：分类增减时旧配置文件仍然可用。
     */
    public java.util.Map<String, Boolean> debugSections = new java.util.LinkedHashMap<>();

    /** 默认均衡曲线：10 段全 0 dB。 */
    private static java.util.List<Double> defaultEqBands() {
        java.util.List<Double> bands = new java.util.ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            bands.add(0.0);
        }
        return bands;
    }

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
        copy.eqEnabled = eqEnabled;
        copy.eqPreampDb = eqPreampDb;
        copy.eqBandGainsDb = new java.util.ArrayList<>(eqBandGainsDb);
        copy.stretchToFit = stretchToFit;
        copy.linearFiltering = linearFiltering;
        copy.integerScaling = integerScaling;
        copy.autoSaveOnExit = autoSaveOnExit;
        copy.showDebugOverlay = showDebugOverlay;
        copy.shaderPreset = shaderPreset;
        copy.customShaderPath = customShaderPath;
        copy.lastRomDirectory = lastRomDirectory;
        copy.debugSections = new java.util.LinkedHashMap<>(debugSections);
        return copy;
    }

}
