package com.mgba.bingbaihanji.view.debug

/**
 * 调试信息的分类。
 *
 * [key] 用于持久化（{@code settings.json} 的 {@code debugSections}），
 * 不要随意改名；[color] 是这一类文字的颜色。
 */
enum class DebugCategory(val key: String, val label: String, val color: String) {

    FPS("fps", "FPS", "#7FD18B"),
    SPEED("speed", "速度", "#FFD166"),
    SHADER("shader", "着色器", "#4FC3F7"),
    RESOLUTION("resolution", "分辨率", "#B39DDB"),
    AUDIO("audio", "音频", "#F48FB1"),
    SAVE("save", "存档", "#A5D6A7"),
    ROM("rom", "ROM", "#E0E0E0"),
    CORE("core", "核心", "#90CAF9"),
    SYSTEM("system", "系统", "#81D4FA"),
    MEMORY("memory", "内存", "#FFAB91")
}
