package com.mgba.bingbaihanji.core.cheat;

/**
 * 解析后的金手指。
 *
 * @param type         识别出的类型
 * @param displayCode  规范显示形式（多行用 \n 分隔）
 * @param libretroCode 传给 mGBA libretro 核心的代码（多行用 \n 分隔）
 */
public record ParsedCheat(CheatType type, String displayCode, String libretroCode) {
}
