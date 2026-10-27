package com.mgba.bingbaihanji.core.audio;

/**
 * 均衡器预设。
 *
 * <p>前面几个是固定曲线（界面上只读，不能改），{@link #CUSTOM} 是用户自定义，
 * 曲线由对话框里的滑杆决定。
 *
 * <p>曲线长度与 {@link GraphicEqualizer#BAND_COUNT} 一致，依次对应
 * {@link GraphicEqualizer#BAND_FREQUENCIES_HZ} 的
 * 31 / 62 / 125 / 250 / 500 / 1k / 2k / 4k / 8k / 16k Hz。
 * 预设不含前置增益（恒为 0 dB），前置增益只在自定义下可调。
 */
public enum EqPreset {

    /** 平直：全 0 dB，直通曲线。 */
    FLAT("平直", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),

    /** 流行：中频突出，两端稍收。 */
    POP("流行", -2, -1, 0, 2, 4, 4, 2, 0, -1, -2),

    /** 摇滚：低频和高频一起推，中频挖空。 */
    ROCK("摇滚", 5, 4, 3, 1, -1, -1, 1, 3, 4, 5),

    /** 爵士：低频偏暖，中频放松，高频提亮。 */
    JAZZ("爵士", 3, 2, 1, 2, -1, -1, 0, 1, 3, 4),

    /** 古典：低频饱满、中频自然、高频通透。 */
    CLASSICAL("古典", 4, 3, 2, 1, 0, 0, -1, 1, 3, 4),

    /** 人声：压低频，抬 500 Hz–2 kHz 的清晰度频段。 */
    VOCAL("人声", -3, -2, -1, 2, 4, 4, 3, 1, 0, -1),

    /** 低音增强：只推低频，中高频不动。 */
    BASS_BOOST("低音增强", 7, 6, 5, 3, 1, 0, 0, 0, 0, 0),

    /** 高音增强：只推高频，低中频不动。 */
    TREBLE_BOOST("高音增强", 0, 0, 0, 0, 0, 0, 1, 3, 5, 7),

    /** 自定义：曲线由用户自己调，没有固定曲线。 */
    CUSTOM("自定义");

    private final String label;

    /** 固定曲线（dB）；自定义为空数组。 */
    private final double[] gains;

    EqPreset(String label, double... gains) {
        if (gains.length != 0 && gains.length != GraphicEqualizer.BAND_COUNT) {
            throw new IllegalArgumentException(
                    "预设曲线必须是 " + GraphicEqualizer.BAND_COUNT + " 段，实际 " + gains.length);
        }
        this.label = label;
        this.gains = gains;
    }

    /** 面向用户的名称。 */
    public String label() {
        return label;
    }

    /** 是否为自定义（没有固定曲线，滑杆可调）。 */
    public boolean isCustom() {
        return gains.length == 0;
    }

    /** 固定曲线（dB）的副本；{@link #isCustom} 时返回 {@code null}。 */
    public double[] gains() {
        return isCustom() ? null : gains.clone();
    }

    /**
     * 按枚举名解析，未知或空名回落到 {@link #CUSTOM}。
     *
     * <p>旧配置文件没有 {@code eqPreset} 字段时就是空名，落到自定义上，
     * 原有的 {@code eqPreampDb} / {@code eqBandGainsDb} 正好就是自定义曲线。
     *
     * @param name {@link #name()}，例如 {@code "ROCK"}
     * @return 对应预设，找不到则 {@link #CUSTOM}
     */
    public static EqPreset fromName(String name) {
        if (name != null) {
            for (EqPreset preset : values()) {
                if (preset.name().equals(name)) {
                    return preset;
                }
            }
        }
        return CUSTOM;
    }
}
