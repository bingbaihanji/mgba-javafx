package com.mgba.bingbaihanji.view.debug

import javafx.scene.control.Label
import javafx.scene.layout.VBox

/**
 * 调试信息浮层：显示 FPS、倍速、分辨率、音频与内存等运行数据。
 *
 * <p>用 JavaFX 标签而不是 GL 文本：xuan 的 `drawText` 需要额外加载字体文件，
 * 而这里只是调试信息，用系统等宽字体足够，还能避免每帧重绘全部文字。
 */
class DebugOverlay : VBox(2.0) {

    private val label = Label().apply {
        style = "-fx-text-fill: #B8FFB8; -fx-font-family: 'Consolas', 'Courier New', monospace;" +
                " -fx-font-size: 11px;"
    }

    init {
        style = "-fx-background-color: rgba(0, 0, 0, 0.55); -fx-padding: 8;" +
                " -fx-background-radius: 4;"
        // 调试浮层不参与布局与鼠标事件，避免挡住视频区域的点击
        isPickOnBounds = false
        isMouseTransparent = true
        isVisible = false
        children.add(label)
    }

    /**
     * 更新显示内容。
     *
     * @param lines 每行一条统计信息
     */
    fun update(lines: List<String>) {
        label.text = lines.joinToString("\n")
    }
}
