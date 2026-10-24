package com.mgba.bingbaihanji.view

import com.bingbaihanji.xuan.dsl.xuan
import com.mgba.bingbaihanji.core.emulation.EmulationLoop
import com.mgba.bingbaihanji.core.emulation.GbaButton
import com.mgba.bingbaihanji.mgbaplatform.emulator.JniEmulatorCore
import javafx.application.Platform
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import java.io.File
import kotlin.math.min

/**
 * mGBA JavaFX 桌面端入口。
 *
 * 使用 xuan DSL 创建窗口，加载 ROM 后启动模拟器帧循环，
 * 通过 DynamicImage 将帧上传到 GL 纹理并绘制。
 */
fun main() = xuan {
    title = "mGBA Desktop"
    width = 960.0
    height = 640.0

    // ── 模拟器状态 ──────────────────────────────────────────
    var core: JniEmulatorCore? = null
    var loop: EmulationLoop? = null
    var gameWidth = 240
    var gameHeight = 160
    var romLoaded = false

    // 按键映射
    val keyMap = mapOf(
        KeyCode.X to GbaButton.A,
        KeyCode.Z to GbaButton.B,
        KeyCode.A to GbaButton.L,
        KeyCode.S to GbaButton.R,
        KeyCode.ENTER to GbaButton.START,
        KeyCode.RIGHT to GbaButton.SELECT,
        KeyCode.UP to GbaButton.UP,
        KeyCode.DOWN to GbaButton.DOWN,
        KeyCode.LEFT to GbaButton.LEFT,
        KeyCode.RIGHT to GbaButton.RIGHT
    )

    // ── 初始化 ──────────────────────────────────────────────
    onInit { gc ->
        // 尝试打开 ROM 文件对话框
        Platform.runLater {
            val file = javafx.stage.FileChooser().apply {
                title = "选择 ROM"
                extensionFilters.addAll(
                    javafx.stage.FileChooser.ExtensionFilter("GB/GBA ROM", "*.gba", "*.gbc", "*.gb", "*.agb"),
                    javafx.stage.FileChooser.ExtensionFilter("所有文件", "*.*")
                )
            }.showOpenDialog(null)

            if (file != null && file.exists()) {
                try {
                    val c = JniEmulatorCore()
                    // 初始化核心
                    val coreDll = findCoreDll()
                    val systemDir = systemDirectory()
                    val initResult = c.initialize(coreDll.absolutePath, systemDir.absolutePath)
                    if (initResult != 0) {
                        println("核心初始化失败: ${c.lastError()}")
                        return@runLater
                    }

                    val loadResult = c.loadGame(file.absolutePath)
                    if (loadResult != 0) {
                        println("加载 ROM 失败: ${c.lastError()}")
                        return@runLater
                    }

                    // 读取 AV 信息
                    val av = c.avInfo()
                    gameWidth = 240 // 默认 GBA 分辨率，后续从帧数据获取
                    gameHeight = 160

                    core = c

                    // 启动帧循环
                    val l = EmulationLoop(c)
                    l.setTargetFps(av.fps)
                    l.setSpeedMultiplier(4.0)
                    loop = l
                    l.start()

                    romLoaded = true
                    println("ROM 已加载: ${file.name}")
                    println("核心: ${c.coreName()} ${c.coreVersion()}")
                    println("FPS: ${av.fps}, 采样率: ${av.sampleRate}")
                } catch (e: Exception) {
                    println("加载失败: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }

    // ── 渲染 ────────────────────────────────────────────────
    onRender {
        val c = core
        if (c == null || !romLoaded) {
            // 未加载时显示黑底
            fill = 0xFF000000.toInt()
            fillRect(0f, 0f, width.toFloat(), height.toFloat())
            return@onRender
        }

        // 获取最新帧
        val frame = c.acquireFrame()
        if (frame != null) {
            gameWidth = frame.width
            gameHeight = frame.height

            // 计算 letterbox
            val scale = min(width / gameWidth.toFloat(), height / gameHeight.toFloat())
            val dw = gameWidth * scale
            val dh = gameHeight * scale
            val dx = (width - dw) / 2f
            val dy = (height - dh) / 2f

            // 绘制帧
            fill = 0xFF000000.toInt()
            fillRect(0f, 0f, width.toFloat(), height.toFloat())

            // 用 drawImage 直接画帧数据
            // 注意：帧数据是 RGB565 格式，需要转换
            // 暂时用简单方式：每帧新建 IntArray（后续用 DynamicImage 优化）
            drawFrame(frame.buffer, frame.width, frame.height, frame.format, dx, dy, dw, dh)

            c.releaseFrame()
        }
    }

    // ── 输入 ────────────────────────────────────────────────
    onScene { scene ->
        scene.addEventFilter(KeyEvent.KEY_PRESSED) { e ->
            val btn = keyMap[e.code]
            if (btn != null && core != null) {
                core?.press(btn, 1)
                e.consume()
            }
        }
        scene.addEventFilter(KeyEvent.KEY_RELEASED) { e ->
            val btn = keyMap[e.code]
            if (btn != null && core != null) {
                core?.release(btn, 1)
                e.consume()
            }
        }
    }
}

// ── 辅助函数 ──────────────────────────────────────────────────

private fun findCoreDll(): File {
    val dist = File("build/native/windows-x64/dist/mgba_libretro.dll")
    return if (dist.exists()) dist else error("找不到 mgba_libretro.dll")
}

private fun systemDirectory(): File {
    val dir = File(System.getenv("APPDATA") ?: ".", "bingbaihanji-mgba/system")
    dir.mkdirs()
    return dir
}

/**
 * 将帧数据绘制到目标矩形。
 * 帧格式: 2=RGB565, 1=XRGB8888, 0=0RGB1555(按RGB565处理)
 */
private fun com.bingbaihanji.xuan.renderer.Gc.drawFrame(
    buffer: java.nio.ByteBuffer,
    w: Int,
    h: Int,
    format: Int,
    dx: Float,
    dy: Float,
    dw: Float,
    dh: Float
) {
    // 转换为 ARGB IntArray
    val pixels = IntArray(w * h)
    buffer.rewind()
    when (format) {
        2 -> {
            // RGB565
            for (i in 0 until w * h) {
                val s = buffer.short.toInt() and 0xFFFF
                val r = ((s shr 11) and 0x1F) shl 3
                val g = ((s shr 5) and 0x3F) shl 2
                val b = (s and 0x1F) shl 3
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        1 -> {
            // XRGB8888
            for (i in 0 until w * h) {
                pixels[i] = buffer.int or (0xFF shl 24)
            }
        }
        else -> {
            // 0RGB1555 按 RGB565 处理
            for (i in 0 until w * h) {
                val s = buffer.short.toInt() and 0xFFFF
                val r = ((s shr 10) and 0x1F) shl 3
                val g = ((s shr 5) and 0x1F) shl 3
                val b = (s and 0x1F) shl 3
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
    drawImage(pixels, w, h, dx, dy, dw, dh)
}
