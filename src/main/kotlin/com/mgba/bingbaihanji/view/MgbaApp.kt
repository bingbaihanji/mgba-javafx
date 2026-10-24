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

    val controller = XuanMgbaController()

    // 渲染（GL 线程）
    onRender {
        controller.render(this, width.toFloat(), height.toFloat())
    }

    // 场景搭建（JavaFX 线程）
    onScene { scene ->
        overlay.children.add(controller.rootPane)

        scene.addEventFilter(KeyEvent.KEY_PRESSED) { e -> controller.handleKeyPressed(e) }
        scene.addEventFilter(KeyEvent.KEY_RELEASED) { e -> controller.handleKeyReleased(e) }
    }
}
