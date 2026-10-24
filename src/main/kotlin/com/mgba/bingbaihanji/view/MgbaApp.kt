package com.mgba.bingbaihanji.view

import com.bingbaihanji.xuan.dsl.xuan
import com.mgba.bingbaihanji.core.emulation.EmulationLoop
import com.mgba.bingbaihanji.core.emulation.GbaButton
import com.mgba.bingbaihanji.mgbaplatform.emulator.JniEmulatorCore
import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.Menu
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuItem
import javafx.scene.control.RadioMenuItem
import javafx.scene.control.CheckMenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.ToggleGroup
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import java.io.File
import kotlin.math.min

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
    var paused = false
    var speedMultiplier = 4.0
    var muted = false
    var volume = 1.0f

    // 按键映射（修正：SELECT 用 BACK_SPACE，RIGHT 只映射方向）
    val keyMap = mapOf(
        KeyCode.X to GbaButton.A,
        KeyCode.Z to GbaButton.B,
        KeyCode.A to GbaButton.L,
        KeyCode.S to GbaButton.R,
        KeyCode.ENTER to GbaButton.START,
        KeyCode.BACK_SPACE to GbaButton.SELECT,
        KeyCode.UP to GbaButton.UP,
        KeyCode.DOWN to GbaButton.DOWN,
        KeyCode.LEFT to GbaButton.LEFT,
        KeyCode.RIGHT to GbaButton.RIGHT
    )

    // ── 加载 ROM 函数 ────────────────────────────────────────
    fun loadRom(file: File) {
        if (romLoaded) {
            // 关闭旧的
            loop?.stop()
            core?.unloadGame()
            core?.close()
        }
        try {
            val c = JniEmulatorCore()
            val coreDll = findCoreDll()
            val systemDir = systemDirectory()

            val initResult = c.initialize(coreDll.absolutePath, systemDir.absolutePath)
            if (initResult != 0) {
                showError("核心初始化失败", c.lastError() ?: "未知错误")
                return
            }

            val loadResult = c.loadGame(file.absolutePath)
            if (loadResult != 0) {
                showError("加载 ROM 失败", c.lastError() ?: "未知错误")
                return
            }

            val av = c.avInfo()
            core = c

            val l = EmulationLoop(c)
            l.setTargetFps(av.fps)
            l.setSpeedMultiplier(speedMultiplier)
            loop = l
            l.start()

            romLoaded = true
            paused = false

            Platform.runLater {
                // 更新窗口标题
                val stage = javafx.stage.Stage.getWindows().firstOrNull()
                if (stage is javafx.stage.Stage) {
                    stage.title = "mGBA Desktop - ${file.name}"
                }
            }
            println("ROM 已加载: ${file.name}")
            println("核心: ${c.coreName()} ${c.coreVersion()}")
            println("FPS: ${av.fps}, 采样率: ${av.sampleRate}")
        } catch (e: Exception) {
            showError("加载异常", e.message ?: "未知错误")
            e.printStackTrace()
        }
    }

    // ── 菜单栏 ──────────────────────────────────────────────
    val menuBar = MenuBar()

    // 文件菜单
    val fileMenu = Menu("文件(_F)")
    val openItem = MenuItem("打开 ROM(_O)").apply {
        accelerator = KeyCombination.valueOf("Shortcut+O")
        setOnAction {
            val file = FileChooser().apply {
                title = "选择 ROM"
                extensionFilters.addAll(
                    FileChooser.ExtensionFilter("GB/GBA ROM", "*.gba", "*.gbc", "*.gb", "*.agb"),
                    FileChooser.ExtensionFilter("ZIP 压缩包", "*.zip"),
                    FileChooser.ExtensionFilter("所有文件", "*.*")
                )
            }.showOpenDialog(null)
            if (file != null && file.exists()) {
                loadRom(file)
            }
        }
    }
    val exitItem = MenuItem("退出(_X)").apply {
        accelerator = KeyCombination.valueOf("Shortcut+Q")
        setOnAction {
            loop?.stop()
            core?.close()
            Platform.exit()
        }
    }
    fileMenu.items.addAll(openItem, SeparatorMenuItem(), exitItem)

    // 模拟菜单
    val emuMenu = Menu("模拟(_E)")
    val pauseItem = MenuItem("暂停/继续(_P)").apply {
        accelerator = KeyCombination.valueOf("P")
        setOnAction {
            if (!romLoaded) return@setOnAction
            paused = !paused
            if (paused) {
                loop?.stop()
            } else {
                loop?.start()
            }
        }
    }
    val resetItem = MenuItem("复位(_R)").apply {
        accelerator = KeyCombination.valueOf("Shortcut+R")
        setOnAction {
            if (!romLoaded) return@setOnAction
            // 重新加载 ROM
            core?.let { c ->
                // 简单复位：unload + load
                // 暂时只停止并重启帧循环
                loop?.stop()
                loop?.start()
            }
        }
    }

    // 速度子菜单
    val speedMenu = Menu("速度(_S)")
    val speedGroup = ToggleGroup()
    val speeds = listOf(0.5, 1.0, 2.0, 4.0, 8.0, 16.0)
    for (speed in speeds) {
        val item = RadioMenuItem("${speed}x").apply {
            toggleGroup = speedGroup
            isSelected = speed == speedMultiplier
            setOnAction {
                speedMultiplier = speed
                loop?.setSpeedMultiplier(speed)
            }
        }
        speedMenu.items.add(item)
    }
    // 快进切换
    val fastForwardItem = MenuItem("切换快进(_F)").apply {
        accelerator = KeyCombination.valueOf("Space")
        setOnAction {
            if (!romLoaded) return@setOnAction
            val current = loop?.getSpeedMultiplier() ?: 1.0
            val newSpeed = if (current > 1.0) 1.0 else speedMultiplier
            loop?.setSpeedMultiplier(newSpeed)
        }
    }

    emuMenu.items.addAll(pauseItem, resetItem, SeparatorMenuItem(), speedMenu, fastForwardItem)

    // 音频菜单
    val audioMenu = Menu("音频(_A)")
    val muteItem = MenuItem("静音/取消静音(_M)").apply {
        accelerator = KeyCombination.valueOf("M")
        setOnAction {
            muted = !muted
            // TODO: 应用到 AudioSink
        }
    }
    val volumeMenu = Menu("音量(_V)")
    val volumeGroup = ToggleGroup()
    for (pct in listOf(25, 50, 75, 100)) {
        val item = RadioMenuItem("${pct}%").apply {
            toggleGroup = volumeGroup
            isSelected = pct == 100
            setOnAction {
                volume = pct / 100f
                // TODO: 应用到 AudioSink
            }
        }
        volumeMenu.items.add(item)
    }
    audioMenu.items.addAll(muteItem, SeparatorMenuItem(), volumeMenu)

    // 视频菜单
    val videoMenu = Menu("视频(_V)")
    val stretchItem = CheckMenuItem("拉伸填充").apply {
        setOnAction {
            // TODO: 切换 stretchToFit
        }
    }
    val fullscreenItem = MenuItem("全屏(_F)").apply {
        accelerator = KeyCombination.valueOf("F11")
        setOnAction {
            val stage = javafx.stage.Stage.getWindows().firstOrNull() as? javafx.stage.Stage
            stage?.isFullScreen = !(stage?.isFullScreen ?: false)
        }
    }
    videoMenu.items.addAll(stretchItem, SeparatorMenuItem(), fullscreenItem)

    // 帮助菜单
    val helpMenu = Menu("帮助(_H)")
    val aboutItem = MenuItem("关于(_A)").apply {
        setOnAction {
            Alert(Alert.AlertType.INFORMATION).apply {
                title = "关于"
                headerText = "mGBA Desktop"
                contentText = "基于 xuan (JavaFX + OpenGL) 的 mGBA 桌面端\n" +
                        "核心: mGBA Libretro\n" +
                        "版本: 1.0-SNAPSHOT"
            }.showAndWait()
        }
    }
    helpMenu.items.add(aboutItem)

    menuBar.menus.addAll(fileMenu, emuMenu, audioMenu, videoMenu, helpMenu)

    // ── 渲染 ────────────────────────────────────────────────
    onRender {
        val c = core
        if (c == null || !romLoaded) {
            fill = 0xFF1A1A2E.toInt()
            fillRect(0f, 0f, width.toFloat(), height.toFloat())
            return@onRender
        }

        val frame = c.acquireFrame()
        if (frame != null) {
            gameWidth = frame.width
            gameHeight = frame.height

            val scale = min(width / gameWidth.toFloat(), height / gameHeight.toFloat())
            val dw = gameWidth * scale
            val dh = gameHeight * scale
            val dx = (width - dw) / 2f
            val dy = (height - dh) / 2f

            fill = 0xFF000000.toInt()
            fillRect(0f, 0f, width.toFloat(), height.toFloat())

            drawFrame(frame.buffer, frame.width, frame.height, frame.format, dx, dy, dw, dh)
            c.releaseFrame()
        }
    }

    // ── 场景搭建（菜单栏 + 输入）────────────────────────────────
    onScene { scene ->
        // 把菜单栏放到 overlay 顶部
        val root = scene.root
        if (root is javafx.scene.layout.Pane) {
            val vbox = VBox(menuBar)
            VBox.setVgrow(vbox, javafx.scene.layout.Priority.NEVER)
            root.children.add(0, vbox)
            // 让 GL 画布占剩余空间（菜单栏在最顶部）
        }

        // 键盘输入
        scene.addEventFilter(KeyEvent.KEY_PRESSED) { e ->
            // 快捷键先给菜单处理
            if (e.code == KeyCode.SPACE) {
                if (romLoaded) {
                    val current = loop?.getSpeedMultiplier() ?: 1.0
                    val newSpeed = if (current > 1.0) 1.0 else speedMultiplier
                    loop?.setSpeedMultiplier(newSpeed)
                }
                e.consume()
                return@addEventFilter
            }
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
    val candidates = listOf(
        File("build/native/windows-x64/dist/mgba_libretro.dll"),
        File("native/dist/mgba_libretro.dll")
    )
    return candidates.firstOrNull { it.exists() }
        ?: error("找不到 mgba_libretro.dll，请先运行构建脚本")
}

private fun systemDirectory(): File {
    val dir = File(System.getenv("APPDATA") ?: ".", "bingbaihanji-mgba/system")
    dir.mkdirs()
    return dir
}

private fun showError(title: String, message: String) {
    Platform.runLater {
        Alert(Alert.AlertType.ERROR).apply {
            this.title = title
            headerText = title
            contentText = message
        }.showAndWait()
    }
}

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
    val pixels = IntArray(w * h)
    buffer.rewind()
    when (format) {
        2 -> {
            for (i in 0 until w * h) {
                val s = buffer.short.toInt() and 0xFFFF
                val r = ((s shr 11) and 0x1F) shl 3
                val g = ((s shr 5) and 0x3F) shl 2
                val b = (s and 0x1F) shl 3
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        1 -> {
            for (i in 0 until w * h) {
                pixels[i] = buffer.int or (0xFF shl 24)
            }
        }
        else -> {
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
