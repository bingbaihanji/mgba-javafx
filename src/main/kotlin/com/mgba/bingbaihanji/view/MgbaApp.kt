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
        controller?.render(this)
            ?: run {
                fill = 0xFF1A1A2E.toInt()
                fillRect(0f, 0f, width.toFloat(), height.toFloat())
            }
    }

    // 场景搭建（JavaFX 线程）
    onScene { scene ->
        val c = XuanMgbaController()
        controller = c

        c.attachToOverlay(overlay)
        scene.addEventFilter(KeyEvent.KEY_PRESSED) { e -> c.handleKeyPressed(e) }
        scene.addEventFilter(KeyEvent.KEY_RELEASED) { e -> c.handleKeyReleased(e) }

        (scene.window as? javafx.stage.Stage)?.let { stage ->
            // 直接关窗时也要保存电池存档与自动槽位
            stage.setOnCloseRequest { c.onWindowClosing() }
            // 按 Esc 退出全屏时同样要恢复菜单栏与状态栏
            stage.fullScreenProperty().addListener { _, _, fullscreen ->
                c.setFullscreenLayout(fullscreen)
            }
            // 切走窗口时释放按键，避免回来后键一直按着
            stage.focusedProperty().addListener { _, _, focused ->
                if (!focused) {
                    c.releaseAllInput()
                }
            }
        }
    }
}
