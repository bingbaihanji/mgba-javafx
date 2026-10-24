package com.mgba.bingbaihanji.core.option;

import java.util.List;
import java.util.Locale;

/**
 * 一条核心选项（来自 Libretro SET_VARIABLES）。
 *
 * @param index   核心内的选项下标（设置时用）
 * @param key     原始键名，如 {@code mgba_color_correction}
 * @param values  可选值
 * @param current 当前选中的值下标
 */
public record CoreOption(int index, String key, List<String> values, int current) {

    /**
     * 面向用户显示的名称：去掉 {@code mgba_} 前缀、下划线换空格、首字母大写。
     *
     * @return 例如 {@code mgba_color_correction} → {@code Color correction}
     */
    public String displayName() {
        String name = key.startsWith("mgba_") ? key.substring("mgba_".length()) : key;
        name = name.replace('_', ' ').strip();
        if (name.isEmpty()) {
            return key;
        }
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }
}
