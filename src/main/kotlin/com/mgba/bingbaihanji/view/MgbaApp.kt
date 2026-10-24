package com.mgba.bingbaihanji.view

import com.mgba.bingbaihanji.core.audio.AudioPump
import com.mgba.bingbaihanji.core.emulation.EmulationLoop
import com.mgba.bingbaihanji.core.emulation.GbaButton
import com.mgba.bingbaihanji.mgbaplatform.emulator.JniEmulatorCore
import com.mgba.bingbaihanji.platform.desktop.audio.SourceDataLineAudioSink
import com.bingbaihanji.xuan.dsl.xuan
import com.bingbaihanji.xuan.renderer.Gc
import javafx.application.Platform
import javafx.scene.control.*
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

// ── 按键映射 ──────────────────────────────────────────────────

private val KEY_MAP = mapOf(
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

private val ROM_FILTERS = listOf(
    FileChooser.ExtensionFilter("GB/GBA ROM", "*.gba", "*.gbc", "*.gb", "*.agb"),
    FileChooser.ExtensionFilter("ZIP 压缩包", "*.zip"),
    FileChooser.ExtensionFilter("所有文件", "*.*")
)

// ── 主入口 ────────────────────────────────────────────────────

fun main() = xuan {
    title = "mGBA Desktop"
    width = 960.0
    height = 640.0

    // ── 可变状态 ────────────────────────────────────────────
    var core: JniEmulatorCore? = null
    var loop: EmulationLoop? = null
    var audioPump: AudioPump? = null
    var audioSink: SourceDataLineAudioSink? = null
    var romLoaded = false
    var paused = false
    var speedMultiplier = 4.0

    // ── ROM 加载 ────────────────────────────────────────────
    fun loadRom(file: File) {
        // 释放旧的模拟器资源
        loop?.stop()
        audioPump?.stop()
        audioSink?.close()
        core?.unloadGame()
        core?.close()

        try {
            val c = JniEmulatorCore()
            val initResult = c.initialize(findCoreDll().absolutePath, systemDirectory().absolutePath)
            if (initResult != 0) {
                showError("核心初始化失败", c.lastError() ?: "错误码 $initResult")
                return
            }

            val loadResult = c.loadGame(file.absolutePath)
            if (loadResult != 0) {
                showError("加载 ROM 失败", c.lastError() ?: "错误码 $loadResult")
                return
            }

            val av = c.avInfo()
            core = c

            // 音频
            val sink = SourceDataLineAudioSink(av.sampleRate)
            audioSink = sink
            val pump = AudioPump(c, sink)
            audioPump = pump
            pump.start()

            // 帧循环
            val emuLoop = EmulationLoop(c)
            emuLoop.setTargetFps(av.fps)
            emuLoop.setSpeedMultiplier(speedMultiplier)
            loop = emuLoop
            emuLoop.start()

            romLoaded = true
            paused = false

            Platform.runLater {
                (javafx.stage.Stage.getWindows().firstOrNull() as? javafx.stage.Stage)?.let {
                    it.title = "mGBA Desktop - ${file.name}"
                }
            }
        } catch (e: Exception) {
            showError("加载异常", e.message ?: e.javaClass.simpleName)
            e.printStackTrace()
        }
    }

    // ── 渲染 ────────────────────────────────────────────────
    onRender {
        val c = core
        if (c == null || !romLoaded) {
            fill = 0xFF1A1A2E.toInt()
            fillRect(0f, 0f, width.toFloat(), height.toFloat())
            return@onRender
        }

        val frame = c.acquireFrame() ?: return@onRender
        try {
            val scale = min(width / frame.width.toFloat(), height / frame.height.toFloat())
            val dw = frame.width * scale
            val dh = frame.height * scale
            val dx = (width - dw) / 2f
            val dy = (height - dh) / 2f

            fill = 0xFF000000.toInt()
            fillRect(0f, 0f, width.toFloat(), height.toFloat())
            drawFrame(frame.buffer, frame.width, frame.height, frame.format, dx, dy, dw, dh)
        } finally {
            c.releaseFrame()
        }
    }

    // ── 场景（菜单 + 输入）──────────────────────────────────────
    onScene { scene ->
        val menuBar = MenuBar()

        // 文件
        val fileMenu = Menu("文件(_F)")
        fileMenu.items.addAll(
            MenuItem("打开 ROM(_O)").apply {
                accelerator = KeyCombination.valueOf("Shortcut+O")
                setOnAction {
                    FileChooser().apply {
                        title = "选择 ROM"
                        extensionFilters.addAll(ROM_FILTERS)
                    }.showOpenDialog(null)?.let { loadRom(it) }
                }
            },
            SeparatorMenuItem(),
            MenuItem("退出(_X)").apply {
                accelerator = KeyCombination.valueOf("Shortcut+Q")
                setOnAction {
                    loop?.stop(); audioPump?.stop(); audioSink?.close(); core?.close()
                    Platform.exit()
                }
            }
        )

        // 模拟
        val speedGroup = ToggleGroup()
        val speedMenu = Menu("速度(_S)")
        for (speed in listOf(0.5, 1.0, 2.0, 4.0, 8.0, 16.0)) {
            speedMenu.items.add(RadioMenuItem("${speed}x").apply {
                toggleGroup = speedGroup
                isSelected = speed == speedMultiplier
                setOnAction { speedMultiplier = speed; loop?.setSpeedMultiplier(speed) }
            })
        }
        val emuMenu = Menu("模拟(_E)")
        emuMenu.items.addAll(
            MenuItem("暂停/继续(_P)").apply {
                setOnAction {
                    if (!romLoaded) return@setOnAction
                    paused = !paused
                    if (paused) { loop?.stop(); audioPump?.pause() }
                    else { loop?.start(); audioPump?.resume() }
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
                    val cur = loop?.speedMultiplier ?: 1.0
                    loop?.setSpeedMultiplier(if (cur > 1.0) 1.0 else speedMultiplier)
                }
            }
        )

        // 音频
        val volumeGroup = ToggleGroup()
        val volumeMenu = Menu("音量(_V)")
        for (pct in listOf(25, 50, 75, 100)) {
            volumeMenu.items.add(RadioMenuItem("${pct}%").apply {
                toggleGroup = volumeGroup
                isSelected = pct == 100
                setOnAction { audioSink?.setVolume(pct / 100f) }
            })
        }
        val audioMenu = Menu("音频(_A)")
        audioMenu.items.addAll(
            MenuItem("静音(_M)").apply {
                accelerator = KeyCombination.valueOf("M")
                setOnAction { audioSink?.setVolume(0f) }
            },
            SeparatorMenuItem(),
            volumeMenu
        )

        // 视频
        val videoMenu = Menu("视频(_V)")
        videoMenu.items.addAll(
            MenuItem("全屏(_F)").apply {
                accelerator = KeyCombination.valueOf("F11")
                setOnAction {
                    (javafx.stage.Stage.getWindows().firstOrNull() as? javafx.stage.Stage)?.let {
                        it.isFullScreen = !it.isFullScreen
                    }
                }
            }
        )

        // 帮助
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
        overlay.children.add(VBox(menuBar))

        // 键盘输入
        scene.addEventFilter(KeyEvent.KEY_PRESSED) { e ->
            if (e.code == KeyCode.SPACE && romLoaded) {
                val cur = loop?.speedMultiplier ?: 1.0
                loop?.setSpeedMultiplier(if (cur > 1.0) 1.0 else speedMultiplier)
                e.consume()
                return@addEventFilter
            }
            KEY_MAP[e.code]?.let { btn ->
                core?.press(btn, 1)
                e.consume()
            }
        }
        scene.addEventFilter(KeyEvent.KEY_RELEASED) { e ->
            KEY_MAP[e.code]?.let { btn ->
                core?.release(btn, 1)
                e.consume()
            }
        }
    }
}

// ── 帧格式转换 ────────────────────────────────────────────────

/**
 * 将模拟器帧缓冲绘制到目标矩形（letterbox）。
 *
 * 格式码: 2=RGB565, 1=XRGB8888, 0=0RGB1555（按 RGB565 处理）。
 * 数据为小端字节序。
 */
private fun Gc.drawFrame(
    buffer: ByteBuffer, width: Int, height: Int, format: Int,
    dx: Float, dy: Float, dw: Float, dh: Float
) {
    val pixels = IntArray(width * height)
    buffer.order(ByteOrder.LITTLE_ENDIAN).rewind()

    when (format) {
        // XRGB8888
        1 -> for (i in pixels.indices) {
            pixels[i] = buffer.int or (0xFF shl 24)
        }
        // RGB565 / 0RGB1555
        else -> for (i in pixels.indices) {
            val s = buffer.short.toInt() and 0xFFFF
            val r = ((s shr 11) and 0x1F) shl 3
            val g = ((s shr 5) and 0x3F) shl 2
            val b = (s and 0x1F) shl 3
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    drawImage(pixels, width, height, dx, dy, dw, dh)
}

// ── 工具函数 ──────────────────────────────────────────────────

/** 定位 mgba_libretro.dll。 */
private fun findCoreDll(): File =
    listOf(
        File("build/native/windows-x64/dist/mgba_libretro.dll"),
        File("native/dist/mgba_libretro.dll")
    ).firstOrNull { it.exists() }
        ?: error("找不到 mgba_libretro.dll，请先运行 scripts/build-native-windows.ps1")

/** 系统目录（BIOS 等），位于 %APPDATA%/bingbaihanji-mgba/system。 */
private fun systemDirectory(): File =
    File(System.getenv("APPDATA") ?: ".", "bingbaihanji-mgba/system").apply { mkdirs() }

/** 弹出错误对话框（必须在 JavaFX 线程调用，此处自动 dispatch）。 */
private fun showError(title: String, message: String) {
    Platform.runLater {
        Alert(Alert.AlertType.ERROR).apply {
            this.title = title
            headerText = title
            contentText = message
        }.showAndWait()
    }
}
