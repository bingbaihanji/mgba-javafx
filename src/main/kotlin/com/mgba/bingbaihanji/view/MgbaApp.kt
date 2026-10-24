package com.mgba.bingbaihanji.view

import com.bingbaihanji.xuan.dsl.xuan
import javafx.scene.input.KeyEvent

/**
 * mGBA Desktop 启动入口。
 *
 * 仅负责配置窗口参数和启动 xuan 应用，业务逻辑委托给 [XuanMgbaController]。
 */
fun main() = xuan {
    title = "mGBA Desktop"
    width = 960.0
    height = 640.0

    // 延迟到 onScene 创建（那时 JavaFX toolkit 已就绪）
    var controller: XuanMgbaController? = null

    // 渲染（GL 线程）
    onRender {
        controller?.render(this, width.toFloat(), height.toFloat())
            ?: run {
                fill = 0xFF1A1A2E.toInt()
                fillRect(0f, 0f, width.toFloat(), height.toFloat())
            }
    }

    // 场景搭建（JavaFX 线程）
    onScene { scene ->
        val c = XuanMgbaController()
        controller = c

        overlay.children.add(c.rootPane)
        scene.addEventFilter(KeyEvent.KEY_PRESSED) { e -> c.handleKeyPressed(e) }
        scene.addEventFilter(KeyEvent.KEY_RELEASED) { e -> c.handleKeyReleased(e) }
    }
}
