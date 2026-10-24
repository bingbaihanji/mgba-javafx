package com.mgba.bingbaihanji.view

import com.mgba.bingbaihanji.core.audio.AudioPump
import com.mgba.bingbaihanji.core.emulation.EmulationLoop
import com.mgba.bingbaihanji.core.emulation.GbaButton
import com.mgba.bingbaihanji.core.save.GameSaveSession
import com.mgba.bingbaihanji.core.save.RomIdentity
import com.mgba.bingbaihanji.core.save.SaveStateStore
import com.mgba.bingbaihanji.mgbaplatform.emulator.JniEmulatorCore
import com.mgba.bingbaihanji.platform.desktop.AppPaths
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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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

    /** 当前 ROM 文件，复位时重新加载用。 */
    private var currentRom: File? = null

    /** 存档会话（电池存档 + 即时存档）。自动存档线程会读它，因此标记 volatile。 */
    @Volatile
    private var saveSession: GameSaveSession? = null

    /** 周期保存电池存档的调度器。 */
    private var autoSaveExecutor: ScheduledExecutorService? = null

    /** 退出流程已经开始，避免关窗和菜单退出重复执行。 */
    private var exiting = false

    init {
        rootPane.top = menuBar
        rootPane.bottom = statusBar
        // center 留空，让底层 GL 画布透出
        rootPane.center = javafx.scene.layout.StackPane().apply {
            isPickOnBounds = false
        }
        rootPane.isPickOnBounds = false
    }

    /**
     * 把根容器挂到 xuan 的 overlay 上。
     *
     * overlay 是普通 [javafx.scene.layout.Pane]，而 Pane 只会按子节点的
     * preferred size 摆放（不像 StackPane 那样拉伸填满）。所以必须把根容器
     * 的 pref 尺寸绑定到 overlay 尺寸，否则 BorderPane 会缩成只有菜单栏
     * 那么大，视频中心区域的高度直接变成 0。
     */
    fun attachToOverlay(overlay: javafx.scene.layout.Pane) {
        rootPane.prefWidthProperty().bind(overlay.widthProperty())
        rootPane.prefHeightProperty().bind(overlay.heightProperty())
        overlay.children.add(rootPane)
    }

    // ── 渲染（GL 线程调用）─────────────────────────────────────

    /**
     * 渲染一帧。
     *
     * [Gc] 的坐标与 [Gc.width]/[Gc.height] 都是设备像素，而 JavaFX 的布局尺寸
     * 是逻辑像素，两者在高 DPI 下差一个缩放系数。这里用根容器的实际宽度求比值，
     * 把菜单栏/状态栏的逻辑高度换算成设备像素后交给渲染器避让。
     */
    fun render(gc: Gc) {
        val canvasWidth = gc.width.toFloat()
        val canvasHeight = gc.height.toFloat()
        val scale = if (rootPane.width > 0) canvasWidth / rootPane.width.toFloat() else 1f
        val topPx = (menuBar.layoutBounds.height * scale).toFloat()
        val bottomPx = (statusBar.layoutBounds.height * scale).toFloat()
        videoRenderer.render(gc, if (romLoaded) core else null, canvasWidth, canvasHeight, topPx, bottomPx)
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
        if (exiting) return
        exiting = true
        saveBeforeExit()
        shutdown()
        Platform.exit()
    }

    /**
     * 窗口关闭前的收尾（由场景的 onCloseRequest 调用）。
     *
     * <p>这里不调 [Platform.exit]：关窗流程本来就会结束应用，再调一次会重复触发关闭。
     */
    fun onWindowClosing() {
        if (exiting) return
        exiting = true
        saveBeforeExit()
        shutdown()
    }

    override fun onTogglePause() {
        if (!romLoaded) return
        paused = !paused
        if (paused) {
            loop?.stop()
            audioPump?.pause()
            saveBatteryQuietly()
            statusBar.showMessage("已暂停")
        } else {
            loop?.start()
            audioPump?.resume()
            statusBar.showMessage("已继续")
        }
    }

    override fun onReset() {
        val c = core ?: return
        val rom = currentRom ?: return
        if (!romLoaded) return

        loop?.stop()
        saveBatteryQuietly()

        // 真正的复位：卸载后重新加载同一 ROM，核心状态回到开机
        if (c.unloadGame() != 0) {
            statusBar.showMessage("复位失败：${c.lastError() ?: "核心错误"}")
            loop?.start()
            return
        }
        if (c.loadGame(rom.absolutePath) != 0) {
            statusBar.showMessage("复位失败：${c.lastError() ?: "无法重新加载 ROM"}")
            return
        }
        runCatching { saveSession?.loadBattery() }

        loop?.start()
        statusBar.showMessage("已复位")
    }

    // ── 即时存档 ──────────────────────────────────────────────

    override fun onSaveState(slot: Int) {
        val session = saveSession
        if (session == null) {
            statusBar.showMessage("请先加载 ROM")
            return
        }
        val result = session.saveState(slot)
        statusBar.showMessage(
            if (result.ok()) "已保存到槽位 ${slot + 1}"
            else "保存失败：${result.description()}"
        )
    }

    override fun onLoadState(slot: Int) {
        val session = saveSession
        if (session == null) {
            statusBar.showMessage("请先加载 ROM")
            return
        }
        val result = session.loadState(slot)
        statusBar.showMessage(
            if (result.ok()) "已读取槽位 ${slot + 1}"
            else "读取失败：${result.description()}"
        )
    }

    override fun onSaveAutoState() {
        val session = saveSession
        if (session == null) {
            statusBar.showMessage("请先加载 ROM")
            return
        }
        val result = session.saveState(SaveStateStore.AUTO_SLOT)
        statusBar.showMessage(
            if (result.ok()) "已保存到自动槽位"
            else "保存失败：${result.description()}"
        )
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
            currentRom = file

            // 存档会话必须在 loadGame 之后建立：SRAM/RTC 的大小此时才确定
            val identity = RomIdentity.of(file.toPath())
            val session = GameSaveSession.open(c, identity, AppPaths.savesDirectory())
            saveSession = session
            val restoredBattery = session.loadBattery()
            startAutoSave()

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
                if (restoredBattery > 0) {
                    statusBar.showMessage("已恢复电池存档")
                }
            }

            Platform.runLater {
                (javafx.stage.Stage.getWindows().firstOrNull() as? javafx.stage.Stage)?.let {
                    it.title = "mGBA Desktop - ${file.name}"
                }
            }
        } catch (e: Exception) {
            showError("加载异常", e.message ?: e.javaClass.simpleName)
            e.printStackTrace()
            shutdown()
        }
    }

    /** 退出前保存：自动槽位 + 电池存档。 */
    private fun saveBeforeExit() {
        val session = saveSession ?: return
        runCatching { session.saveState(SaveStateStore.AUTO_SLOT) }
        runCatching { session.saveBattery() }
    }

    /** 周期保存电池存档：核心调用可能失败（比如正在卸载），静默忽略。 */
    private fun saveBatteryQuietly() {
        runCatching { saveSession?.saveBattery() }
    }

    /**
     * 启动每 60 秒一次的电池存档。
     *
     * <p>只用单线程调度器：SRAM 写入本身很小，且核心调用已由
     * {@link JniEmulatorCore} 内部锁串行化，不需要并发。
     */
    private fun startAutoSave() {
        stopAutoSave()
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "mgba-autosave").apply { isDaemon = true }
        }
        executor.scheduleAtFixedRate(
            { saveBatteryQuietly() },
            AUTO_SAVE_SECONDS, AUTO_SAVE_SECONDS, TimeUnit.SECONDS
        )
        autoSaveExecutor = executor
    }

    private fun stopAutoSave() {
        autoSaveExecutor?.shutdownNow()
        autoSaveExecutor = null
    }

    private fun shutdown() {
        stopAutoSave()
        runCatching { loop?.stop() }
        runCatching { audioPump?.stop() }
        runCatching { audioSink?.close() }
        // 先写电池存档，再卸载核心
        saveBatteryQuietly()
        runCatching { core?.unloadGame() }
        runCatching { core?.close() }
        loop = null
        audioPump = null
        audioSink = null
        core = null
        saveSession = null
        currentRom = null
        romLoaded = false
        paused = false
    }

    companion object {
        /** 输入源 ID：键盘。 */
        private const val SOURCE_KEYBOARD = 1

        /** 电池存档的自动保存间隔（秒）。 */
        private const val AUTO_SAVE_SECONDS = 60L

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
internal fun systemDirectory(): File = AppPaths.systemDirectory().toFile()

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
