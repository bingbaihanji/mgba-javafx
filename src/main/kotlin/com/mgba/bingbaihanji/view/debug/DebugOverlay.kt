package com.mgba.bingbaihanji.view.debug

import javafx.scene.control.Label
import javafx.scene.layout.VBox

/**
 * 调试信息浮层：按分类分行显示，每类一种颜色。
 *
 * <p>用 JavaFX 标签而不是 GL 文本：xuan 的 `drawText` 需要额外加载字体文件，
 * 而这里只是调试信息，用系统等宽字体足够，还能单独控制每行的颜色与显隐。
 */
class DebugOverlay : VBox(2.0) {

    /** 每个分类一个标签，标签内部可以有多行。 */
    private val labels = LinkedHashMap<DebugCategory, Label>()

    init {
        // 背景完全透明：只留文字，不遮挡游戏画面
        style = "-fx-background-color: transparent;"
        // 调试浮层不参与鼠标事件，避免挡住视频区域的点击
        isPickOnBounds = false
        isMouseTransparent = true
        isVisible = false
        // 长路径等文本折行，而不是撑出窗口
        maxWidth = 620.0

        for (category in DebugCategory.entries) {
            val label = Label().apply {
                // 透明背景下加一层很淡的文字阴影，亮色画面上也看得清
                style = "-fx-text-fill: ${category.color};" +
                        " -fx-font-family: 'Consolas', 'Courier New', monospace;" +
                        " -fx-font-size: 11px;" +
                        " -fx-effect: dropshadow(gaussian, rgba(0, 0, 0, 0.85), 2, 0.2, 1, 1);"
                isWrapText = true
                maxWidth = 620.0
            }
            labels[category] = label
            children.add(label)
        }
    }

    /**
     * 更新显示内容。
     *
     * @param values  分类 → 文本（null/空串表示当前没有数据）
     * @param enabled 分类 → 是否显示
     */
    fun update(values: Map<DebugCategory, String?>, enabled: Map<DebugCategory, Boolean>) {
        for ((category, label) in labels) {
            val text = values[category]
            label.text = text ?: ""
            val visible = (enabled[category] ?: true) && !text.isNullOrBlank()
            label.isVisible = visible
            label.isManaged = visible
        }
    }
}
