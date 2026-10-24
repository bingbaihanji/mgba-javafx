package com.mgba.bingbaihanji.view

import com.mgba.bingbaihanji.core.audio.AudioPump
import com.mgba.bingbaihanji.core.emulation.EmulationLoop
import com.mgba.bingbaihanji.core.emulation.GbaButton
import com.mgba.bingbaihanji.mgbaplatform.emulator.JniEmulatorCore
import com.mgba.bingbaihanji.platform.desktop.audio.SourceDataLineAudioSink
import com.mgba.bingbaihanji.view.menu.GameMenuBar
import com.mgba.bingbaihanji.view.menu.MenuActions
import com.mgba.bingbaihanji.view.status.GameStatusBar
import com.mgba.bingbaihanji.view.video.GameVideoRenderer
import com.bingbaihanji.xuan.renderer.Gc
import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.stage.FileChooser
import java.io.File

/**
 * 组件组装与业务协调器。
 *
 * 负责管理模拟器生命周期（ROM 加载、暂停、复位、倍速等），
 * 连接菜单栏、视频渲染器和状态栏。
 */
class XuanMgbaController : MenuActions {

    // ── UI 组件 ───────────────────────────────────────────────
    val rootPane = BorderPane()
    private val menuBar = GameMenuBar(this)
    private val statusBar = GameStatusBar()
    private val videoRenderer = GameVideoRenderer()

    // ── 模拟器状态 ────────────────────────────────────────────
    private var core: JniEmulatorCore? = null
    private var loop: EmulationLoop? = null
    private var audioPump: AudioPump? = null
    private var audioSink: SourceDataLineAudioSink? = null
    private var romLoaded = false
    private var paused = false
    private var speedMultiplier = 4.0
    private var volume = 1.0f

    init {
        rootPane.top = menuBar
        rootPane.bottom = statusBar
        // center 留空，让底层 GL 画布透出
        rootPane.center = javafx.scene.layout.StackPane().apply {
            isPickOnBounds = false
        }
        rootPane.isPickOnBounds = false
    }

    // ── 渲染（GL 线程调用）─────────────────────────────────────

    fun render(gc: Gc, width: Float, height: Float) {
        videoRenderer.render(gc, if (romLoaded) core else null, width, height)
    }

    // ── 输入（JavaFX 线程调用）─────────────────────────────────

    fun handleKeyPressed(e: KeyEvent) {
        if (e.code == KeyCode.SPACE && romLoaded) {
            toggleFastForward()
            e.consume()
            return
        }
        KEY_MAP[e.code]?.let { btn ->
            core?.press(btn, SOURCE_KEYBOARD)
            e.consume()
        }
    }

    fun handleKeyReleased(e: KeyEvent) {
        KEY_MAP[e.code]?.let { btn ->
            core?.release(btn, SOURCE_KEYBOARD)
            e.consume()
        }
    }

    // ── MenuActions 实现 ──────────────────────────────────────

    override fun onOpenRom() {
        FileChooser().apply {
            title = "选择 ROM"
            extensionFilters.addAll(ROM_FILTERS)
        }.showOpenDialog(null)?.let { loadRom(it) }
    }

    override fun onExit() {
        shutdown()
        Platform.exit()
    }

    override fun onTogglePause() {
        if (!romLoaded) return
        paused = !paused
        if (paused) {
            loop?.stop()
            audioPump?.pause()
        } else {
            loop?.start()
            audioPump?.resume()
        }
    }

    override fun onReset() {
        if (!romLoaded) return
        loop?.stop()
        loop?.start()
    }

    override fun onSpeedChanged(speed: Double) {
        speedMultiplier = speed
        loop?.setSpeedMultiplier(speed)
        statusBar.setSpeed(speed)
    }

    override fun onToggleFastForward() {
        toggleFastForward()
    }

    override fun onToggleMute() {
        audioSink?.setVolume(0f)
    }

    override fun onVolumeChanged(volume: Float) {
        this.volume = volume
        audioSink?.setVolume(volume)
    }

    override fun onToggleFullscreen() {
        (javafx.stage.Stage.getWindows().firstOrNull() as? javafx.stage.Stage)?.let {
            it.isFullScreen = !it.isFullScreen
        }
    }

    // ── 私有 ──────────────────────────────────────────────────

    private fun toggleFastForward() {
        if (!romLoaded) return
        val cur = loop?.speedMultiplier ?: 1.0
        val newSpeed = if (cur > 1.0) 1.0 else speedMultiplier
        loop?.setSpeedMultiplier(newSpeed)
        statusBar.setSpeed(newSpeed)
    }

    private fun loadRom(file: File) {
        shutdown()

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
            sink.setVolume(volume)
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

            // 更新状态栏
            Platform.runLater {
                statusBar.setRomName(file.name)
                statusBar.setCoreInfo(c.coreName() ?: "", c.coreVersion() ?: "")
                statusBar.setSpeed(speedMultiplier)
                val (w, h) = videoRenderer.resolution()
                statusBar.setResolution(w, h)
            }

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

    private fun shutdown() {
        loop?.stop()
        audioPump?.stop()
        audioSink?.close()
        core?.unloadGame()
        core?.close()
        loop = null
        audioPump = null
        audioSink = null
        core = null
        romLoaded = false
    }

    companion object {
        /** 输入源 ID：键盘。 */
        private const val SOURCE_KEYBOARD = 1

        /** GBA 按键映射。 */
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

        /** ROM 文件扩展名过滤器。 */
        private val ROM_FILTERS = listOf(
            FileChooser.ExtensionFilter("GB/GBA ROM", "*.gba", "*.gbc", "*.gb", "*.agb"),
            FileChooser.ExtensionFilter("ZIP 压缩包", "*.zip"),
            FileChooser.ExtensionFilter("所有文件", "*.*")
        )
    }
}

// ── 工具函数 ──────────────────────────────────────────────────

/** 定位 mgba_libretro.dll。 */
internal fun findCoreDll(): File =
    listOf(
        File("build/native/windows-x64/dist/mgba_libretro.dll"),
        File("native/dist/mgba_libretro.dll")
    ).firstOrNull { it.exists() }
        ?: error("找不到 mgba_libretro.dll，请先运行 scripts/build-native-windows.ps1")

/** 系统目录（BIOS 等），位于 %APPDATA%/bingbaihanji-mgba/system。 */
internal fun systemDirectory(): File =
    File(System.getenv("APPDATA") ?: ".", "bingbaihanji-mgba/system").apply { mkdirs() }

/** 弹出错误对话框（自动 dispatch 到 JavaFX 线程）。 */
internal fun showError(title: String, message: String) {
    Platform.runLater {
        Alert(Alert.AlertType.ERROR).apply {
            this.title = title
            headerText = title
            contentText = message
        }.showAndWait()
    }
}
