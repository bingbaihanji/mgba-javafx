package com.mgba.bingbaihanji.view

import com.mgba.bingbaihanji.core.audio.AudioPump
import com.mgba.bingbaihanji.core.audio.AudioSync
import com.mgba.bingbaihanji.core.cheat.CheatService
import com.mgba.bingbaihanji.core.emulation.EmulationLoop
import com.mgba.bingbaihanji.core.emulation.GbaButton
import com.mgba.bingbaihanji.core.option.CoreOptionService
import com.mgba.bingbaihanji.core.option.CoreOptionsStore
import com.mgba.bingbaihanji.core.save.GameSaveSession
import com.mgba.bingbaihanji.core.save.RomIdentity
import com.mgba.bingbaihanji.core.save.SaveStateStore
import com.mgba.bingbaihanji.core.save.ScreenshotWriter
import com.mgba.bingbaihanji.core.settings.AppSettings
import com.mgba.bingbaihanji.core.settings.SettingsStore
import com.mgba.bingbaihanji.mgbaplatform.emulator.JniEmulatorCore
import com.mgba.bingbaihanji.platform.desktop.AppPaths
import com.mgba.bingbaihanji.platform.desktop.audio.SourceDataLineAudioSink
import com.mgba.bingbaihanji.platform.desktop.input.KeyMapping
import com.mgba.bingbaihanji.platform.desktop.video.ShaderPreset
import com.mgba.bingbaihanji.view.cheat.CheatDialog
import com.mgba.bingbaihanji.view.debug.DebugCategory
import com.mgba.bingbaihanji.view.debug.DebugOverlay
import com.mgba.bingbaihanji.view.input.KeyMappingDialog
import com.mgba.bingbaihanji.view.menu.GameMenuBar
import com.mgba.bingbaihanji.view.menu.MenuActions
import com.mgba.bingbaihanji.view.option.CoreOptionsDialog
import com.mgba.bingbaihanji.view.save.SaveSlotsDialog
import com.mgba.bingbaihanji.view.settings.SettingsDialog
import com.mgba.bingbaihanji.view.status.GameStatusBar
import com.mgba.bingbaihanji.view.theme.UiTheme
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

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

    /** 未加载 ROM 时的占位页（logo + 打开按钮）。 */
    private val placeholder = StartPlaceholder { onOpenRom() }

    // ── 模拟器状态 ────────────────────────────────────────────
    private var core: JniEmulatorCore? = null
    private var loop: EmulationLoop? = null
    private var audioPump: AudioPump? = null
    private var audioSink: SourceDataLineAudioSink? = null
    private var romLoaded = false
    private var paused = false
    private var speedMultiplier = 1.0
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

    /** 键盘映射（持久化）与它的反查表。 */
    private var keyMapping = KeyMapping.load()
    private var keyToButton: Map<KeyCode, GbaButton> = keyMapping.asKeyToButton()

    /** 周期保存电池存档的调度器。 */
    private var autoSaveExecutor: ScheduledExecutorService? = null

    /** 音频同步：每秒按音频环水位微调模拟速度。 */
    private val audioSync = AudioSync()
    private var audioSyncTimer: Timeline? = null

    @Volatile
    private var lastAudioCorrection = 1.0

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

    /** 相邻两次渲染的间隔（指数滑动平均），GL 线程写、JavaFX 线程读。 */
    @Volatile
    private var lastRenderNanos = 0L

    @Volatile
    private var frameTimeMs = 0.0

    init {
        rootPane.top = menuBar
        rootPane.bottom = statusBar
        // center 留空，让底层 GL 画布透出
        centerStack.children.addAll(placeholder, debugOverlay)
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
        volume = settings.soundVolume / 100f
        videoRenderer.setStretchToFit(settings.stretchToFit)
        videoRenderer.setLinearFiltering(settings.linearFiltering)
        videoRenderer.setIntegerScaling(settings.integerScaling)
        menuBar.selectSpeed(settings.gameSpeed)
        menuBar.selectVolume(settings.soundVolume)
        menuBar.selectDebugOverlay(settings.showDebugOverlay)
        menuBar.applyDebugSections(debugSectionMap())

        val savedPreset = runCatching { ShaderPreset.valueOf(settings.shaderPreset) }
            .getOrDefault(ShaderPreset.OFF)
        shaderPreset = savedPreset
        customShaderFile = settings.customShaderPath.takeIf { it.isNotBlank() }?.let(::File)
        videoRenderer.setShaderPreset(savedPreset, customShaderFile)
        menuBar.selectShaderPreset(savedPreset)

        if (settings.showDebugOverlay) {
            onToggleDebugOverlay(true)
        }

        // 存档子菜单每次打开时显示槽位状态（有存档则带保存时间）
        menuBar.saveSlotLabel = { slot ->
            val timestamp = saveSession?.stateTimestamp(slot) ?: 0L
            if (timestamp <= 0L) {
                "槽位 ${slot + 1}（空）"
            } else {
                "槽位 ${slot + 1} · ${SAVE_TIME_FORMAT.format(Instant.ofEpochMilli(timestamp))}"
            }
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

        // 帧时间：指数滑动平均，避免数字每 500ms 跳一次
        val now = System.nanoTime()
        val previous = lastRenderNanos
        if (previous != 0L) {
            val interval = (now - previous) / 1_000_000.0
            frameTimeMs = if (frameTimeMs <= 0.0) interval else frameTimeMs * 0.9 + interval * 0.1
        }
        lastRenderNanos = now

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
        val button = keyToButton[e.code]
        if (button != null) {
            core?.press(button, SOURCE_KEYBOARD)
            e.consume()
            return
        }
        if (e.code == KeyCode.SPACE && romLoaded) {
            toggleFastForward()
            e.consume()
        }
    }

    fun handleKeyReleased(e: KeyEvent) {
        pressedKeys.remove(e.code)
        val button = keyToButton[e.code]
        if (button != null) {
            core?.release(button, SOURCE_KEYBOARD)
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
        val chooser = FileChooser().apply {
            title = "选择 ROM"
            extensionFilters.addAll(ROM_FILTERS)
            // 从上次打开 ROM 的目录开始
            val lastDirectory = settings.lastRomDirectory
            if (lastDirectory.isNotBlank()) {
                val directory = File(lastDirectory)
                if (directory.isDirectory) {
                    initialDirectory = directory
                }
            }
        }
        val file = chooser.showOpenDialog(rootPane.scene?.window) ?: return

        file.parent?.let { parent ->
            settings.lastRomDirectory = parent
            persistSettings()
        }
        loadRom(file)
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
            applyEffectiveVolume()
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
            refreshPlaceholder()
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
        // 复位后音频环里是复位前的旧声音，丢掉
        audioPump?.flush()
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
        if (result.ok()) {
            // 读档后音频环里还是读档前的旧声音
            audioPump?.flush()
        }
    }

    override fun onUndoLoadState() {
        val session = saveSession
        if (session == null) {
            statusBar.showMessage("请先加载 ROM")
            return
        }
        val result = session.restoreBackup()
        statusBar.showMessage(
            if (result.ok()) "已撤销读档"
            else "撤销失败：${result.description()}"
        )
        if (result.ok()) {
            audioPump?.flush()
        }
    }

    override fun onOpenSaveManager() {
        val session = saveSession
        if (session == null) {
            statusBar.showMessage("请先加载 ROM")
            return
        }
        SaveSlotsDialog(
            rootPane.scene?.window,
            session,
            onMessage = { message -> statusBar.showMessage(message) },
            onLoaded = { audioPump?.flush() }
        ).show()
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
        applyEffectiveVolume()
        // 切换速度后丢掉旧积压，避免恢复原速时先播一段快进期间缓冲的旧音频
        audioPump?.flush()
        statusBar.setSpeed(speed)
        if (speed > 1.0) {
            statusBar.showMessage("${speed}x（快进时音频静音）", 2.5)
        } else if (speed < 1.0) {
            statusBar.showMessage("${speed}x（慢放时音频可能断续）", 2.5)
        }
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

    override fun onTakeScreenshot() {
        val current = core
        if (current == null || !romLoaded) {
            statusBar.showMessage("请先加载 ROM")
            return
        }
        val baseName = currentRom?.nameWithoutExtension ?: "screenshot"
        try {
            val file = ScreenshotWriter.save(current, AppPaths.screenshotsDirectory(), baseName)
            statusBar.showMessage(
                if (file != null) "截图已保存：${file.fileName}" else "当前没有可截取的画面"
            )
        } catch (e: Exception) {
            statusBar.showMessage("截图失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun onLoadCustomShader() {
        val file = FileChooser().apply {
            title = "选择片段着色器"
            extensionFilters.addAll(
                FileChooser.ExtensionFilter("GLSL 着色器", "*.glsl", "*.frag", "*.fs", "*.txt"),
                FileChooser.ExtensionFilter("所有文件", "*.*")
            )
            // 默认打开上次选择的目录；没有就打开应用自己的着色器目录
            val startDirectory = customShaderFile?.parentFile ?: AppPaths.shadersDirectory().toFile()
            if (startDirectory.isDirectory) {
                initialDirectory = startDirectory
            }
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

    override fun onToggleDebugSection(key: String, enabled: Boolean) {
        settings.debugSections[key] = enabled
        persistSettings()
        if (debugOverlay.isVisible) {
            refreshDebugOverlay()
        }
    }

    /** 分类 key → 是否显示（配置里没有的按开启处理）。 */
    private fun debugSectionMap(): Map<String, Boolean> =
        DebugCategory.entries.associate { it.key to (settings.debugSections[it.key] ?: true) }

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

        val values = LinkedHashMap<DebugCategory, String?>()
        val speed = loop?.speedMultiplier ?: speedMultiplier
        val speedMode = when {
            paused -> "（暂停）"
            fastForward -> "（快进）"
            else -> ""
        }

        values[DebugCategory.FPS] = "FPS  游戏 %.1f / 绘制 %.1f   帧 %.1f ms\n丢帧 %d"
            .format(gameFps, drawFps, frameTimeMs, current?.framesDropped() ?: 0L)
        val syncText = if (abs(lastAudioCorrection - 1.0) >= 0.0005) {
            "（音频同步 %+.2f%%）".format((lastAudioCorrection - 1.0) * 100)
        } else {
            ""
        }
        values[DebugCategory.SPEED] = "速度  ${speed}x$speedMode$syncText"

        values[DebugCategory.SHADER] = when (shaderPreset) {
            ShaderPreset.OFF -> "着色器  关闭"
            ShaderPreset.CUSTOM -> "着色器  自定义 · ${customShaderFile?.name ?: "未选择"}"
            else -> "着色器  ${shaderPreset.label()}"
        }

        val (width, height) = videoRenderer.resolution()
        val (canvasWidth, canvasHeight) = videoRenderer.canvasSize()
        values[DebugCategory.RESOLUTION] = "画面  ${width}×${height}\n" +
                "画布  ${canvasWidth}×${canvasHeight}（设备像素）\n" +
                "窗口  ${rootPane.width.toInt()}×${rootPane.height.toInt()}"

        if (current != null) {
            val av = current.avInfo()
            values[DebugCategory.AUDIO] =
                "音频  ${av.sampleRate.toInt()} Hz   队列 ${current.audioQueued()}\n" +
                "欠载 ${current.audioUnderruns()}   溢出 ${current.audioOverflows()}" +
                "   音量 ${(volume * 100).toInt()}%"

            val usedSlots = (0 until SaveStateStore.SLOT_COUNT)
                .count { saveSession?.hasState(it) == true }
            values[DebugCategory.SAVE] =
                "存档  电池 ${current.sramSize()} B" +
                (if (current.rtcSize() > 0) "   RTC ${current.rtcSize()} B" else "") +
                "\n槽位  $usedSlots/${SaveStateStore.SLOT_COUNT} 有存档"

            values[DebugCategory.CORE] =
                "核心  ${current.coreName()} ${current.coreVersion()}\n" +
                "核心 FPS  %.2f".format(av.fps)
        }

        currentRom?.let { rom ->
            values[DebugCategory.ROM] = "ROM   ${rom.name}\n路径  ${rom.parent}"
        }

        values[DebugCategory.SYSTEM] =
            "系统  ${System.getProperty("os.name")} ${System.getProperty("os.arch")}\n" +
            "Java  ${System.getProperty("java.version")}" +
            "   JavaFX ${System.getProperty("javafx.version") ?: "-"}"

        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()).shr(20)
        val maxMb = runtime.maxMemory().shr(20)
        values[DebugCategory.MEMORY] = "内存  堆 ${usedMb}/${maxMb} MB"

        val enabled = DebugCategory.entries.associateWith { settings.debugSections[it.key] ?: true }
        debugOverlay.update(values, enabled)
    }

    // ── 设置 ──────────────────────────────────────────────────

    override fun onOpenKeyMapping() {
        KeyMappingDialog(rootPane.scene?.window, keyMapping) { updated ->
            // 换映射前先松开所有按住的键，避免"旧键仍处于按下状态"
            pressedKeys.clear()
            core?.resetInput()
            keyMapping = updated
            keyToButton = updated.asKeyToButton()
            runCatching { updated.save() }
                .onFailure { statusBar.showMessage("保存按键映射失败：${it.message}") }
            statusBar.showMessage("按键映射已更新")
        }.show()
    }

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

        volume = updated.soundVolume / 100f
        applyEffectiveVolume()
        menuBar.selectVolume(updated.soundVolume)

        videoRenderer.setStretchToFit(updated.stretchToFit)
        videoRenderer.setLinearFiltering(updated.linearFiltering)
        videoRenderer.setIntegerScaling(updated.integerScaling)

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
        volume = settings.soundVolume / 100f
        applyEffectiveVolume()
        statusBar.showMessage(if (settings.enableSound) "声音已开启" else "已静音")
        persistSettings()
    }

    override fun onVolumeChanged(volume: Float) {
        this.volume = volume
        settings.soundVolume = (volume * 100).toInt()
        settings.enableSound = true
        applyEffectiveVolume()
        persistSettings()
    }

    /**
     * 把音量同步到音频设备。
     *
     * <p>只有快进（速度 &gt; 1x）才强制静音：此时音频产量超过声卡消费速度，
     * 环形缓冲会积压然后大段丢帧，听到的是断续噪音。
     * **慢放（&lt; 1x）不静音**——之前对所有非 1x 速度一律静音，
     * 用户把速度设成 0.5x 后加载 ROM 就完全没有声音，看起来像音频坏了。
     */
    private fun applyEffectiveVolume() {
        val speed = loop?.speedMultiplier ?: speedMultiplier
        val effective = if (!settings.enableSound || speed > 1.0) 0f else volume
        audioSink?.setVolume(effective)
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
        applyEffectiveVolume()
        audioPump?.flush()
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
            val session = GameSaveSession.open(
                c, identity, AppPaths.savesDirectory(), file.toPath()
            )
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
            val pump = AudioPump(c, sink)
            // 设备打不开时明确提示，而不是让画面正常但完全没有声音
            pump.setOnAudioUnavailable {
                Platform.runLater {
                    statusBar.showMessage("音频设备打开失败，本次运行将静音", 6.0)
                }
            }
            audioPump = pump
            pump.start()

            // 帧循环
            val emuLoop = EmulationLoop(c)
            emuLoop.setTargetFps(av.fps)
            emuLoop.setSpeedMultiplier(speedMultiplier)
            loop = emuLoop
            emuLoop.start()
            applyEffectiveVolume()
            startAudioSync()

            romLoaded = true
            paused = false
            refreshPlaceholder()

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
                    it.title = "mGBA javafx - ${file.name}"
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

    /** 启动音频同步：每秒按音频环水位把模拟速度微调 ±1%。 */
    private fun startAudioSync() {
        stopAudioSync()
        audioSync.reset()
        loop?.setAudioCorrection(1.0)
        lastAudioCorrection = 1.0
        audioSyncTimer = Timeline(
            KeyFrame(Duration.seconds(1.0), { tickAudioSync() })
        ).apply {
            cycleCount = Timeline.INDEFINITE
            play()
        }
    }

    private fun stopAudioSync() {
        audioSyncTimer?.stop()
        audioSyncTimer = null
    }

    private fun tickAudioSync() {
        val current = core ?: return
        val emulation = loop ?: return
        val pump = audioPump
        val sink = audioSink
        // 暂停、倍速、音频设备不可用时不做修正
        if (pump == null || !pump.isAudioAvailable() || sink == null
            || paused || emulation.speedMultiplier != 1.0
        ) {
            audioSync.reset()
            emulation.setAudioCorrection(1.0)
            lastAudioCorrection = 1.0
            return
        }
        val correction = audioSync.sample(current.audioFrameCount(), sink.playedCoreFrames())
        emulation.setAudioCorrection(correction)
        lastAudioCorrection = correction
    }

    private fun shutdown() {
        stopAudioSync()
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
        refreshPlaceholder()
    }

    /** 未加载 ROM 时显示 logo 占位页，加载完成后让出画面区域。 */
    private fun refreshPlaceholder() {
        placeholder.isVisible = !romLoaded
    }

    companion object {
        /** 输入源 ID：键盘。 */
        private const val SOURCE_KEYBOARD = 1

        /** 电池存档的自动保存间隔（秒）。 */
        private const val AUTO_SAVE_SECONDS = 60L

        /** 快进倍速。 */
        private const val FAST_FORWARD_SPEED = 16.0

        /** 存档槽位时间显示格式。 */
        private val SAVE_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

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

/**
 * 定位 mgba_libretro.dll。
 *
 * fat jar 启动器会把 DLL 解压到 jar 同目录并设置 `mgba.native.dir`；
 * 开发环境则继续用仓库里的构建产物。
 */
internal fun findCoreDll(): File {
    System.getProperty("mgba.native.dir")?.takeIf { it.isNotBlank() }?.let { directory ->
        val extracted = File(directory, "mgba_libretro.dll")
        if (extracted.isFile) {
            return extracted
        }
    }
    return listOf(
        File("build/native/windows-x64/dist/mgba_libretro.dll"),
        File("native/dist/mgba_libretro.dll")
    ).firstOrNull { it.exists() }
        ?: error("找不到 mgba_libretro.dll：打包运行时缺少解压文件，开发环境请先运行 scripts/build-native-windows.ps1")
}

/** 系统目录（BIOS 等），位于 %APPDATA%/bingbaihanji-mgba/system。 */
internal fun systemDirectory(): File = AppPaths.systemDirectory().toFile()

/** 弹出错误对话框（自动 dispatch 到 JavaFX 线程）。 */
internal fun showError(title: String, message: String) {
    Platform.runLater {
        val alert = Alert(Alert.AlertType.ERROR).apply {
            this.title = title
            headerText = title
            contentText = message
        }
        UiTheme.applyAlert(alert)
        alert.showAndWait()
    }
}
