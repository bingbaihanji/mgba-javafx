package com.mgba.bingbaihanji.view

import com.mgba.bingbaihanji.core.audio.AudioPump
import com.mgba.bingbaihanji.core.cheat.CheatService
import com.mgba.bingbaihanji.core.emulation.EmulationLoop
import com.mgba.bingbaihanji.core.emulation.GbaButton
import com.mgba.bingbaihanji.core.option.CoreOptionService
import com.mgba.bingbaihanji.core.option.CoreOptionsStore
import com.mgba.bingbaihanji.core.save.GameSaveSession
import com.mgba.bingbaihanji.core.save.RomIdentity
import com.mgba.bingbaihanji.core.save.SaveStateStore
import com.mgba.bingbaihanji.core.settings.AppSettings
import com.mgba.bingbaihanji.core.settings.SettingsStore
import com.mgba.bingbaihanji.mgbaplatform.emulator.JniEmulatorCore
import com.mgba.bingbaihanji.platform.desktop.AppPaths
import com.mgba.bingbaihanji.platform.desktop.audio.SourceDataLineAudioSink
import com.mgba.bingbaihanji.platform.desktop.video.ShaderPreset
import com.mgba.bingbaihanji.view.cheat.CheatDialog
import com.mgba.bingbaihanji.view.debug.DebugOverlay
import com.mgba.bingbaihanji.view.menu.GameMenuBar
import com.mgba.bingbaihanji.view.menu.MenuActions
import com.mgba.bingbaihanji.view.option.CoreOptionsDialog
import com.mgba.bingbaihanji.view.settings.SettingsDialog
import com.mgba.bingbaihanji.view.status.GameStatusBar
import com.mgba.bingbaihanji.view.video.GameVideoRenderer
import com.bingbaihanji.xuan.renderer.Gc
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Alert
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.StackPane
import javafx.stage.FileChooser
import javafx.util.Duration
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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

    /** BorderPane 的中心容器：透明，叠放调试浮层。 */
    private val centerStack = StackPane().apply { isPickOnBounds = false }

    /** 调试信息浮层。 */
    private val debugOverlay = DebugOverlay()

    // ── 模拟器状态 ────────────────────────────────────────────
    private var core: JniEmulatorCore? = null
    private var loop: EmulationLoop? = null
    private var audioPump: AudioPump? = null
    private var audioSink: SourceDataLineAudioSink? = null
    private var romLoaded = false
    private var paused = false
    private var speedMultiplier = 4.0
    private var volume = 1.0f

    /** 是否处于快进状态（与用户设定的常驻倍速分开）。 */
    private var fastForward = false

    /** 设置存储与当前设置。 */
    private val settingsStore = SettingsStore()
    private var settings = settingsStore.load()

    /** 核心选项的持久化（键 → 值文本）。 */
    private val coreOptionsStore = CoreOptionsStore()
    private var savedCoreOptions: Map<String, String> = coreOptionsStore.load()

    /** 当前 ROM 文件，复位时重新加载用。 */
    private var currentRom: File? = null

    /** 存档会话（电池存档 + 即时存档）。自动存档线程会读它，因此标记 volatile。 */
    @Volatile
    private var saveSession: GameSaveSession? = null

    /** 当前 ROM 的金手指服务。 */
    private var cheatService: CheatService? = null

    /** 周期保存电池存档的调度器。 */
    private var autoSaveExecutor: ScheduledExecutorService? = null

    /** 退出流程已经开始，避免关窗和菜单退出重复执行。 */
    private var exiting = false

    /** 当前着色器预设。 */
    private var shaderPreset = ShaderPreset.OFF

    /** 用户自定义着色器文件（切到 CUSTOM 时使用）。 */
    private var customShaderFile: File? = null

    // ── 调试信息采样 ──────────────────────────────────────────
    private val renderCount = AtomicLong()
    private var debugTimer: Timeline? = null
    private var lastSampleNanos = 0L
    private var lastGameFrames = 0L
    private var lastRenderFrames = 0L
    private var gameFps = 0.0
    private var drawFps = 0.0

    init {
        rootPane.top = menuBar
        rootPane.bottom = statusBar
        // center 留空，让底层 GL 画布透出
        centerStack.children.add(debugOverlay)
        StackPane.setAlignment(debugOverlay, Pos.TOP_LEFT)
        StackPane.setMargin(debugOverlay, Insets(8.0))
        rootPane.center = centerStack
        rootPane.isPickOnBounds = false

        // 着色器在 GL 线程编译，失败时切回 JavaFX 线程提示并恢复 Off
        videoRenderer.onShaderError = { message ->
            Platform.runLater {
                statusBar.showMessage("着色器加载失败：$message", 5.0)
                shaderPreset = ShaderPreset.OFF
                settings.shaderPreset = ShaderPreset.OFF.name
                persistSettings()
                menuBar.selectShaderPreset(ShaderPreset.OFF)
                videoRenderer.setShaderPreset(ShaderPreset.OFF)
            }
        }

        // 应用持久化设置（此时 JavaFX 控件已就绪）
        speedMultiplier = settings.gameSpeed
        volume = settings.effectiveVolume()
        videoRenderer.setStretchToFit(settings.stretchToFit)
        menuBar.selectSpeed(settings.gameSpeed)
        menuBar.selectVolume(settings.soundVolume)
        menuBar.selectDebugOverlay(settings.showDebugOverlay)

        val savedPreset = runCatching { ShaderPreset.valueOf(settings.shaderPreset) }
            .getOrDefault(ShaderPreset.OFF)
        shaderPreset = savedPreset
        customShaderFile = settings.customShaderPath.takeIf { it.isNotBlank() }?.let(::File)
        videoRenderer.setShaderPreset(savedPreset, customShaderFile)
        menuBar.selectShaderPreset(savedPreset)

        if (settings.showDebugOverlay) {
            onToggleDebugOverlay(true)
        }
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
        renderCount.incrementAndGet()
        val canvasWidth = gc.width.toFloat()
        val canvasHeight = gc.height.toFloat()
        val scale = if (rootPane.width > 0) canvasWidth / rootPane.width.toFloat() else 1f
        // 全屏时菜单栏/状态栏被隐藏（取消 managed），不应再为它们留空
        val topPx = if (menuBar.isManaged) (menuBar.layoutBounds.height * scale).toFloat() else 0f
        val bottomPx = if (statusBar.isManaged) (statusBar.layoutBounds.height * scale).toFloat() else 0f
        videoRenderer.render(gc, if (romLoaded) core else null, canvasWidth, canvasHeight, topPx, bottomPx)
    }

    // ── 输入（JavaFX 线程调用）─────────────────────────────────

    /**
     * 当前按下的键。JavaFX 不提供"自动重复"标志，按住空格会连发 KEY_PRESSED，
     * 没有这个集合的话快进会被反复开关。
     */
    private val pressedKeys = mutableSetOf<KeyCode>()

    fun handleKeyPressed(e: KeyEvent) {
        if (!pressedKeys.add(e.code)) {
            return
        }
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
        pressedKeys.remove(e.code)
        KEY_MAP[e.code]?.let { btn ->
            core?.release(btn, SOURCE_KEYBOARD)
            e.consume()
        }
    }

    /** 窗口失焦时释放全部按键与输入状态，避免出现"卡键"。 */
    fun releaseAllInput() {
        pressedKeys.clear()
        core?.resetInput()
    }

    // ── MenuActions 实现 ──────────────────────────────────────

    override fun onOpenRom() {
        FileChooser().apply {
            title = "选择 ROM"
            extensionFilters.addAll(ROM_FILTERS)
        }.showOpenDialog(rootPane.scene?.window)?.let { loadRom(it) }
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
            if (!paused) {
                loop?.start()
            }
            return
        }
        if (c.loadGame(rom.absolutePath) != 0) {
            // 卸载成功但重新加载失败：已经没有可运行的游戏，状态要与实际一致
            paused = true
            romLoaded = false
            statusBar.showMessage("复位失败：${c.lastError() ?: "无法重新加载 ROM"}")
            return
        }
        runCatching { saveSession?.loadBattery() }
        cheatService?.apply()
        CoreOptionService(c).apply(savedCoreOptions)

        // 暂停状态下复位后仍保持暂停，不要偷偷把模拟跑起来
        if (!paused) {
            loop?.start()
        }
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
        fastForward = false
        loop?.setSpeedMultiplier(speed)
        statusBar.setSpeed(speed)
        settings.gameSpeed = speed
        persistSettings()
    }

    override fun onToggleFastForward() {
        toggleFastForward()
    }

    // ── 着色器 ────────────────────────────────────────────────

    override fun onShaderChanged(preset: ShaderPreset) {
        if (preset == ShaderPreset.CUSTOM && customShaderFile == null) {
            // 从没选过文件：直接走选择流程
            onLoadCustomShader()
            return
        }
        shaderPreset = preset
        videoRenderer.setShaderPreset(preset, customShaderFile)
        settings.shaderPreset = preset.name
        persistSettings()
        statusBar.showMessage("着色器：${preset.label()}")
    }

    override fun onLoadCustomShader() {
        val file = FileChooser().apply {
            title = "选择片段着色器"
            extensionFilters.addAll(
                FileChooser.ExtensionFilter("GLSL 着色器", "*.glsl", "*.frag", "*.fs", "*.txt"),
                FileChooser.ExtensionFilter("所有文件", "*.*")
            )
        }.showOpenDialog(rootPane.scene?.window)

        if (file == null) {
            // 取消选择：把单选状态恢复到当前生效的预设
            menuBar.selectShaderPreset(shaderPreset)
            return
        }

        customShaderFile = file
        shaderPreset = ShaderPreset.CUSTOM
        videoRenderer.setShaderPreset(ShaderPreset.CUSTOM, file)
        menuBar.selectShaderPreset(ShaderPreset.CUSTOM)
        settings.customShaderPath = file.absolutePath
        settings.shaderPreset = ShaderPreset.CUSTOM.name
        persistSettings()
        statusBar.showMessage("已加载着色器：${file.name}")
    }

    // ── 调试信息 ──────────────────────────────────────────────

    override fun onToggleDebugOverlay(enabled: Boolean) {
        debugOverlay.isVisible = enabled
        settings.showDebugOverlay = enabled
        menuBar.selectDebugOverlay(enabled)
        persistSettings()
        if (!enabled) {
            debugTimer?.stop()
            debugTimer = null
            return
        }

        lastSampleNanos = System.nanoTime()
        lastGameFrames = core?.frameCount() ?: 0L
        lastRenderFrames = renderCount.get()
        gameFps = 0.0
        drawFps = 0.0

        debugTimer?.stop()
        debugTimer = Timeline(
            KeyFrame(Duration.millis(500.0), { refreshDebugOverlay() })
        ).apply {
            cycleCount = Timeline.INDEFINITE
            play()
        }
        refreshDebugOverlay()
    }

    /** 采样一次统计数据并刷新浮层（JavaFX 线程，每 500ms 一次）。 */
    private fun refreshDebugOverlay() {
        val now = System.nanoTime()
        val elapsed = (now - lastSampleNanos) / 1_000_000_000.0
        val current = core
        val gameFrames = current?.frameCount() ?: 0L
        val renderFrames = renderCount.get()

        if (elapsed > 0.05) {
            gameFps = (gameFrames - lastGameFrames) / elapsed
            drawFps = (renderFrames - lastRenderFrames) / elapsed
            lastSampleNanos = now
            lastGameFrames = gameFrames
            lastRenderFrames = renderFrames
        }

        val (width, height) = videoRenderer.resolution()
        val memoryMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
            .shr(20)

        val lines = mutableListOf<String>()
        lines += "FPS  %.1f / 绘制 %.1f".format(gameFps, drawFps)
        val speed = loop?.speedMultiplier ?: speedMultiplier
        lines += "速度  ${speed}x${if (paused) "（暂停）" else ""}"
        lines += "画面  ${width}×${height}"
        if (current != null) {
            val av = current.avInfo()
            lines += "音频  ${av.sampleRate.toInt()} Hz  队列 ${current.audioQueued()}"
            lines += "欠载  ${current.audioUnderruns()}  溢出 ${current.audioOverflows()}"
            lines += "丢帧  ${current.framesDropped()}"
            lines += "核心  ${current.coreName()} ${current.coreVersion()}"
        }
        lines += "内存  ${memoryMb} MB"
        currentRom?.let { lines += "ROM   ${it.name}" }

        debugOverlay.update(lines)
    }

    // ── 设置 ──────────────────────────────────────────────────

    override fun onOpenSettings() {
        SettingsDialog(rootPane.scene?.window, settings) { updated -> applySettings(updated) }.show()
    }

    /** 应用设置：同步到帧循环、音频、渲染器与菜单选中状态，并持久化。 */
    private fun applySettings(updated: AppSettings) {
        settings = updated

        speedMultiplier = updated.gameSpeed
        loop?.setSpeedMultiplier(updated.gameSpeed)
        statusBar.setSpeed(updated.gameSpeed)
        menuBar.selectSpeed(updated.gameSpeed)

        volume = updated.effectiveVolume()
        audioSink?.setVolume(volume)
        menuBar.selectVolume(updated.soundVolume)

        videoRenderer.setStretchToFit(updated.stretchToFit)

        var preset = runCatching { ShaderPreset.valueOf(updated.shaderPreset) }
            .getOrDefault(ShaderPreset.OFF)
        updated.customShaderPath.takeIf { it.isNotBlank() }?.let { customShaderFile = File(it) }
        if (preset == ShaderPreset.CUSTOM && customShaderFile == null) {
            // 选了自定义却没有文件：不要留一个永远编译失败的预设
            preset = ShaderPreset.OFF
            updated.shaderPreset = ShaderPreset.OFF.name
        }
        shaderPreset = preset
        videoRenderer.setShaderPreset(preset, customShaderFile)
        menuBar.selectShaderPreset(preset)

        if (debugOverlay.isVisible != updated.showDebugOverlay) {
            onToggleDebugOverlay(updated.showDebugOverlay)
        }

        persistSettings()
        statusBar.showMessage("设置已应用")
    }

    private fun persistSettings() {
        runCatching { settingsStore.save(settings) }
            .onFailure { System.err.println("保存设置失败: ${it.message}") }
    }

    override fun onOpenCoreOptions() {
        val current = core
        if (current == null) {
            statusBar.showMessage("请先加载 ROM")
            return
        }
        val service = CoreOptionService(current)
        val options = service.load()
        if (options.isEmpty()) {
            statusBar.showMessage("当前核心没有可配置选项")
            return
        }
        CoreOptionsDialog(rootPane.scene?.window, options) { selection ->
            var applied = 0
            selection.forEach { (index, value) ->
                if (service.set(index, value)) {
                    applied++
                }
            }
            // 记住全部选项，下次加载 ROM / 复位时自动恢复
            savedCoreOptions = service.snapshot()
            runCatching { coreOptionsStore.save(savedCoreOptions) }
            statusBar.showMessage("已应用 $applied 项核心选项（部分选项需重新打开 ROM）")
        }.show()
    }

    override fun onManageCheats() {
        val service = cheatService
        if (service == null) {
            statusBar.showMessage("请先加载 ROM")
            return
        }
        CheatDialog(rootPane.scene?.window, service).show()
    }

    override fun onToggleMute() {
        settings.enableSound = !settings.enableSound
        volume = settings.effectiveVolume()
        audioSink?.setVolume(volume)
        statusBar.showMessage(if (settings.enableSound) "声音已开启" else "已静音")
        persistSettings()
    }

    override fun onVolumeChanged(volume: Float) {
        this.volume = volume
        audioSink?.setVolume(volume)
        settings.soundVolume = (volume * 100).toInt()
        settings.enableSound = true
        persistSettings()
    }

    override fun onToggleFullscreen() {
        val stage = javafx.stage.Stage.getWindows().firstOrNull() as? javafx.stage.Stage ?: return
        stage.isFullScreen = !stage.isFullScreen
        setFullscreenLayout(stage.isFullScreen)
    }

    /**
     * 切换全屏时要同时收起/恢复菜单栏与状态栏。
     *
     * <p>用 {@code managed=false} 而不只是隐藏：只有这样才能让 BorderPane
     * 把整块区域让给中心内容，视频也才会铺满整个窗口。
     *
     * @param fullscreen 是否处于全屏
     */
    fun setFullscreenLayout(fullscreen: Boolean) {
        menuBar.isVisible = !fullscreen
        menuBar.isManaged = !fullscreen
        statusBar.isVisible = !fullscreen
        statusBar.isManaged = !fullscreen
    }

    // ── 私有 ──────────────────────────────────────────────────

    /**
     * 快进开关：在「用户设定的常驻倍速」与 [FAST_FORWARD_SPEED] 之间切换。
     *
     * 之前用"当前速度大于 1 就降回 1x"实现，常驻倍速被设成 4x 时按快进反而更慢，
     * 所以快进独立成一个状态。
     */
    private fun toggleFastForward() {
        if (!romLoaded) return
        fastForward = !fastForward
        val newSpeed = if (fastForward) FAST_FORWARD_SPEED else speedMultiplier
        loop?.setSpeedMultiplier(newSpeed)
        statusBar.setSpeed(newSpeed)
        statusBar.showMessage(if (fastForward) "快进：${FAST_FORWARD_SPEED}x" else "恢复 ${speedMultiplier}x")
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

            // 金手指：加载该 ROM 的列表并立即应用
            cheatService = CheatService.open(c, identity).also { it.apply() }

            // 恢复上次的核心选项（如色彩校正、帧混合）
            CoreOptionService(c).apply(savedCoreOptions)

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

    /** 退出前保存：按设置决定是否写自动槽位，电池存档总是保存。 */
    private fun saveBeforeExit() {
        val session = saveSession ?: return
        if (settings.autoSaveOnExit) {
            runCatching { session.saveState(SaveStateStore.AUTO_SLOT) }
        }
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
        cheatService = null
        currentRom = null
        romLoaded = false
        paused = false
    }

    companion object {
        /** 输入源 ID：键盘。 */
        private const val SOURCE_KEYBOARD = 1

        /** 电池存档的自动保存间隔（秒）。 */
        private const val AUTO_SAVE_SECONDS = 60L

        /** 快进倍速。 */
        private const val FAST_FORWARD_SPEED = 16.0

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

        /**
         * ROM 文件扩展名过滤器。
         *
         * 不提供 ZIP：本项目的 Libretro 桥没有实现 VFS，
         * 核心无法从压缩包里读取 ROM，选中只会得到一个加载失败。
         */
        private val ROM_FILTERS = listOf(
            FileChooser.ExtensionFilter("GB/GBA ROM", "*.gba", "*.gbc", "*.gb", "*.agb"),
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
