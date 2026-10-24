package com.mgba.bingbaihanji.view

import com.bingbaihanji.xuan.dsl.xuan
import com.mgba.bingbaihanji.core.emulation.EmulationLoop
import com.mgba.bingbaihanji.core.emulation.GbaButton
import com.mgba.bingbaihanji.mgbaplatform.emulator.JniEmulatorCore
import javafx.application.Platform
import javafx.scene.control.*
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

    var core: JniEmulatorCore? = null
    var loop: EmulationLoop? = null
    var gameWidth = 240
    var gameHeight = 160
    var romLoaded = false
    var paused = false
    var speedMultiplier = 4.0

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

    fun loadRom(file: File) {
        if (romLoaded) {
            loop?.stop()
            core?.unloadGame()
            core?.close()
        }
        try {
            val c = JniEmulatorCore()
            val coreDll = findCoreDll()
            val systemDir = systemDirectory()

            if (c.initialize(coreDll.absolutePath, systemDir.absolutePath) != 0) {
                showError("核心初始化失败", c.lastError() ?: "未知错误")
                return
            }
            if (c.loadGame(file.absolutePath) != 0) {
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
                javafx.stage.Stage.getWindows().firstOrNull()?.let {
                    if (it is javafx.stage.Stage) it.title = "mGBA Desktop - ${file.name}"
                }
            }
            println("ROM 已加载: ${file.name}")
        } catch (e: Exception) {
            showError("加载异常", e.message ?: "未知错误")
            e.printStackTrace()
        }
    }

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

    onScene { scene ->
        // 菜单栏（toolkit 已就绪）
        val menuBar = MenuBar()

        val fileMenu = Menu("文件(_F)")
        fileMenu.items.addAll(
            MenuItem("打开 ROM(_O)").apply {
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
                    if (file != null && file.exists()) loadRom(file)
                }
            },
            SeparatorMenuItem(),
            MenuItem("退出(_X)").apply {
                accelerator = KeyCombination.valueOf("Shortcut+Q")
                setOnAction { loop?.stop(); core?.close(); Platform.exit() }
            }
        )

        val emuMenu = Menu("模拟(_E)")
        val speedGroup = ToggleGroup()
        val speedMenu = Menu("速度(_S)")
        for (speed in listOf(0.5, 1.0, 2.0, 4.0, 8.0, 16.0)) {
            speedMenu.items.add(RadioMenuItem("${speed}x").apply {
                toggleGroup = speedGroup
                isSelected = speed == speedMultiplier
                setOnAction { speedMultiplier = speed; loop?.setSpeedMultiplier(speed) }
            })
        }
        emuMenu.items.addAll(
            MenuItem("暂停/继续(_P)").apply {
                setOnAction {
                    if (!romLoaded) return@setOnAction
                    paused = !paused
                    if (paused) loop?.stop() else loop?.start()
                }
            },
            MenuItem("复位(_R)").apply {
                accelerator = KeyCombination.valueOf("Shortcut+R")
                setOnAction { if (romLoaded) { loop?.stop(); loop?.start() } }
            },
            SeparatorMenuItem(),
            speedMenu,
            MenuItem("切换快进").apply {
                setOnAction {
                    if (!romLoaded) return@setOnAction
                    val cur = loop?.getSpeedMultiplier() ?: 1.0
                    loop?.setSpeedMultiplier(if (cur > 1.0) 1.0 else speedMultiplier)
                }
            }
        )

        val audioMenu = Menu("音频(_A)")
        val volumeGroup = ToggleGroup()
        val volumeMenu = Menu("音量(_V)")
        for (pct in listOf(25, 50, 75, 100)) {
            volumeMenu.items.add(RadioMenuItem("${pct}%").apply {
                toggleGroup = volumeGroup
                isSelected = pct == 100
            })
        }
        audioMenu.items.addAll(
            MenuItem("静音(_M)").apply { accelerator = KeyCombination.valueOf("M") },
            SeparatorMenuItem(),
            volumeMenu
        )

        val videoMenu = Menu("视频(_V)")
        videoMenu.items.addAll(
            CheckMenuItem("拉伸填充"),
            SeparatorMenuItem(),
            MenuItem("全屏(_F)").apply {
                accelerator = KeyCombination.valueOf("F11")
                setOnAction {
                    val stage = javafx.stage.Stage.getWindows().firstOrNull() as? javafx.stage.Stage
                    stage?.isFullScreen = !(stage?.isFullScreen ?: false)
                }
            }
        )

        val helpMenu = Menu("帮助(_H)")
        helpMenu.items.add(MenuItem("关于(_A)").apply {
            setOnAction {
                Alert(Alert.AlertType.INFORMATION).apply {
                    title = "关于"
                    headerText = "mGBA Desktop"
                    contentText = "基于 xuan (JavaFX + OpenGL) 的 mGBA 桌面端\n核心: mGBA Libretro\n版本: 1.0-SNAPSHOT"
                }.showAndWait()
            }
        })

        menuBar.menus.addAll(fileMenu, emuMenu, audioMenu, videoMenu, helpMenu)

        // 菜单栏放 overlay 顶部（xuan 的 overlay 是叠在画布上的透明 Pane）
        overlay.children.add(VBox(menuBar))

        // 键盘输入
        scene.addEventFilter(KeyEvent.KEY_PRESSED) { e ->
            if (e.code == KeyCode.SPACE && romLoaded) {
                val cur = loop?.getSpeedMultiplier() ?: 1.0
                loop?.setSpeedMultiplier(if (cur > 1.0) 1.0 else speedMultiplier)
                e.consume(); return@addEventFilter
            }
            keyMap[e.code]?.let { btn ->
                if (core != null) { core?.press(btn, 1); e.consume() }
            }
        }
        scene.addEventFilter(KeyEvent.KEY_RELEASED) { e ->
            keyMap[e.code]?.let { btn ->
                if (core != null) { core?.release(btn, 1); e.consume() }
            }
        }
    }
}

private fun findCoreDll(): File {
    return listOf(
        File("build/native/windows-x64/dist/mgba_libretro.dll"),
        File("native/dist/mgba_libretro.dll")
    ).firstOrNull { it.exists() } ?: error("找不到 mgba_libretro.dll")
}

private fun systemDirectory(): File {
    val dir = File(System.getenv("APPDATA") ?: ".", "bingbaihanji-mgba/system")
    dir.mkdirs()
    return dir
}

private fun showError(title: String, message: String) {
    Platform.runLater {
        Alert(Alert.AlertType.ERROR).apply { this.title = title; headerText = title; contentText = message }.showAndWait()
    }
}

private fun com.bingbaihanji.xuan.renderer.Gc.drawFrame(
    buffer: java.nio.ByteBuffer, w: Int, h: Int, format: Int,
    dx: Float, dy: Float, dw: Float, dh: Float
) {
    val pixels = IntArray(w * h)
    buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buffer.rewind()
    when (format) {
        2 -> for (i in 0 until w * h) {
            val s = buffer.short.toInt() and 0xFFFF
            val r = ((s shr 11) and 0x1F) shl 3; val g = ((s shr 5) and 0x3F) shl 2; val b = (s and 0x1F) shl 3
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        1 -> for (i in 0 until w * h) { pixels[i] = buffer.int or (0xFF shl 24) }
        else -> for (i in 0 until w * h) {
            val s = buffer.short.toInt() and 0xFFFF
            val r = ((s shr 10) and 0x1F) shl 3; val g = ((s shr 5) and 0x1F) shl 3; val b = (s and 0x1F) shl 3
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    drawImage(pixels, w, h, dx, dy, dw, dh)
}
