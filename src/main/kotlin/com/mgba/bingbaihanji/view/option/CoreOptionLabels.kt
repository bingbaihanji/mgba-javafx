package com.mgba.bingbaihanji.view.option

import com.mgba.bingbaihanji.core.option.CoreOption

/**
 * 核心选项的显示文案。
 *
 * <p>核心只给英文键名和原始值（如下拉框里的 {@code ON}/{@code disabled}），
 * 这里补上中文，只影响界面显示——回传给核心的仍是原始值下标。
 *
 * <p>未收录的键或值原样显示，核心升级后不会出现空白项。
 */
object CoreOptionLabels {

    /**
     * 选项名称：核心英文名 + 中文注释。
     *
     * <p>英文名取自 mGBA libretro 核心的选项定义，比按键名推导的形式更完整
     * （如 Game Boy Model 而不是 Gb model）。
     */
    fun optionName(option: CoreOption): String {
        val names = OPTION_NAMES[option.key()] ?: return option.displayName()
        return "${names.first}（${names.second}）"
    }

    /**
     * 下拉框里的值：保留核心的英文原文，后面用括号补中文。
     *
     * <p>例如 {@code enabled（启用）}、{@code auto_threshold（自动，按阈值）}；
     * 没有对应中文的值（数字、SGB 编号等）原样显示。
     *
     * @param key 选项键名
     * @param raw 核心给出的原始值
     */
    fun valueLabel(key: String, raw: String): String {
        val chinese = VALUE_LABELS[key]?.get(raw) ?: COMMON_VALUES[raw] ?: return raw
        return "$raw（$chinese）"
    }

    /** 键名 → 「英文名称 to 中文名称」。 */
    private val OPTION_NAMES = mapOf(
        "mgba_gb_model" to ("Game Boy Model (Restart)" to "Game Boy 机型"),
        "mgba_use_bios" to ("Use BIOS File if Found (Restart)" to "使用 BIOS 文件"),
        "mgba_skip_bios" to ("Skip BIOS Intro (Restart)" to "跳过 BIOS 开机画面"),
        "mgba_gb_colors" to ("Default Game Boy Palette" to "Game Boy 默认调色板"),
        "mgba_gb_colors_preset" to ("Hardware Preset Game Boy Palettes (Restart)" to "硬件预设调色板"),
        "mgba_sgb_borders" to ("Use Super Game Boy Borders (Restart)" to "Super Game Boy 边框"),
        "mgba_color_correction" to ("Color Correction" to "色彩校正"),
        "mgba_interframe_blending" to ("Interframe Blending" to "帧间混合"),
        "mgba_audio_low_pass_filter" to ("Audio Filter" to "音频低通滤波"),
        "mgba_audio_low_pass_range" to ("Audio Filter Level" to "滤波强度"),
        "mgba_allow_opposing_directions" to ("Allow Opposing Directional Input" to "允许同时按相反方向"),
        "mgba_solar_sensor_level" to ("Solar Sensor Level" to "太阳能传感器强度"),
        "mgba_force_gbp" to ("Game Boy Player Rumble (Restart)" to "Game Boy Player 震动"),
        "mgba_idle_optimization" to ("Idle Loop Removal" to "空闲循环优化"),
        "mgba_frameskip" to ("Frameskip" to "跳帧"),
        "mgba_frameskip_threshold" to ("Frameskip Threshold (%)" to "跳帧阈值"),
        "mgba_frameskip_interval" to ("Frameskip Interval" to "跳帧间隔")
    )

    /** 键名 → (原始值 → 中文)。这里放各选项自己的取值。 */
    private val VALUE_LABELS = mapOf(
        "mgba_gb_model" to mapOf(
            "Autodetect" to "自动检测"
        ),
        "mgba_use_bios" to mapOf(
            "ON" to "使用",
            "OFF" to "不使用"
        ),
        "mgba_skip_bios" to mapOf(
            "ON" to "跳过",
            "OFF" to "不跳过"
        ),
        "mgba_gb_colors" to mapOf(
            "Grayscale" to "灰度",
            "DMG Green" to "DMG 绿",
            "GBC Brown ↑" to "棕色 ↑",
            "GBC Red ↑A" to "红色 ↑A",
            "GBC Dark Brown ↑B" to "深棕 ↑B",
            "GBC Pale Yellow ↓" to "淡黄 ↓",
            "GBC Orange ↓A" to "橙色 ↓A",
            "GBC Yellow ↓B" to "黄色 ↓B",
            "GBC Blue ←" to "蓝色 ←",
            "GBC Dark Blue ←A" to "深蓝 ←A",
            "GBC Gray ←B" to "灰色 ←B",
            "GBC Green →" to "绿色 →",
            "GBC Dark Green →A" to "深绿 →A",
            "GBC Reverse →B" to "反色 →B"
        ),
        "mgba_gb_colors_preset" to mapOf(
            "0" to "默认预设",
            "1" to "仅 Game Boy Color 预设",
            "2" to "仅 Super Game Boy 预设",
            "3" to "使用全部可用预设"
        ),
        "mgba_sgb_borders" to mapOf(
            "ON" to "显示",
            "OFF" to "不显示"
        ),
        "mgba_color_correction" to mapOf(
            "OFF" to "关闭",
            "Auto" to "自动"
        ),
        "mgba_interframe_blending" to mapOf(
            "OFF" to "关闭",
            "mix" to "简单混合",
            "mix_smart" to "智能混合",
            "lcd_ghosting" to "LCD 拖影，精确",
            "lcd_ghosting_fast" to "LCD 拖影，快速"
        ),
        "mgba_audio_low_pass_filter" to mapOf(
            "disabled" to "禁用",
            "enabled" to "启用"
        ),
        "mgba_allow_opposing_directions" to mapOf(
            "no" to "不允许",
            "yes" to "允许"
        ),
        "mgba_solar_sensor_level" to mapOf(
            "sensor" to "使用设备光线传感器"
        ),
        "mgba_force_gbp" to mapOf(
            "ON" to "启用",
            "OFF" to "禁用"
        ),
        "mgba_idle_optimization" to mapOf(
            "Remove Known" to "移除已知空闲循环",
            "Detect and Remove" to "检测并移除",
            "Don't Remove" to "不移除"
        ),
        "mgba_frameskip" to mapOf(
            "disabled" to "禁用",
            "auto" to "自动",
            "auto_threshold" to "自动，按阈值",
            "fixed_interval" to "固定间隔"
        )
    )

    /** 多个选项共用的取值。 */
    private val COMMON_VALUES = mapOf(
        "ON" to "开",
        "OFF" to "关",
        "enabled" to "启用",
        "disabled" to "禁用",
        "yes" to "是",
        "no" to "否",
        "Auto" to "自动",
        "Autodetect" to "自动检测"
    )
}
