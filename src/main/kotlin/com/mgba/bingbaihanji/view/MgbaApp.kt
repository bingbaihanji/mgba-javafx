package com.mgba.bingbaihanji.view

import com.bingbaihanji.xuan.dsl.xuan
import com.mgba.bingbaihanji.LaunchOptions
import com.mgba.bingbaihanji.platform.desktop.AppPaths
import com.mgba.bingbaihanji.platform.desktop.WindowState
import com.mgba.bingbaihanji.platform.desktop.WindowStateStore
import javafx.application.Platform
import javafx.scene.image.Image
import javafx.scene.input.DragEvent
import javafx.scene.input.KeyEvent
import javafx.scene.input.TransferMode
import javafx.stage.Screen
import javafx.stage.Stage
import java.nio.file.Files
import kotlin.math.max

/** 窗口最小尺寸，避免把画面缩到看不见。 */
private const val MIN_WINDOW_WIDTH = 640.0
private const val MIN_WINDOW_HEIGHT = 480.0

/**
 * mGBA javafx 启动入口。
 *
 * 仅负责配置窗口参数和启动 xuan 应用，业务逻辑委托给 [XuanMgbaController]。
 */
fun main() {
    ensurePortableHome()
    startApp()
}

/**
 * 保证直接运行（不走 fat jar 启动器）时也不写用户目录。
 *
 * <p>Launcher 已经设置过这两个属性，这里只在缺失时补上：
 * IDE 里直接跑 [main] 时 JavaFX 默认会把缓存放到 {@code ~/.openjfx}。
 */
private fun ensurePortableHome() {
    val home = AppPaths.homeDirectory()
    if (System.getProperty(AppPaths.HOME_PROPERTY) == null) {
        System.setProperty(AppPaths.HOME_PROPERTY, home.toString())
    }
    if (System.getProperty("javafx.cachedir") == null) {
        val cache = home.resolve(".mgba-javafx").resolve("javafx")
        runCatching { Files.createDirectories(cache) }
            .onSuccess { System.setProperty("javafx.cachedir", cache.toString()) }
            .onFailure { System.err.println("无法创建 JavaFX 缓存目录: $cache — ${it.message}") }
    }
}

private fun startApp() = xuan {
    title = "mGBA javafx"
    width = WindowState.DEFAULT_WIDTH
    height = WindowState.DEFAULT_HEIGHT

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

        // 把 ROM 文件拖进窗口即可加载
        scene.addEventFilter(DragEvent.DRAG_OVER) { event ->
            val files = event.dragboard.takeIf { it.hasFiles() }?.files
            if (files != null && files.any { isRomFile(it) }) {
                event.acceptTransferModes(TransferMode.COPY)
            }
            event.consume()
        }
        scene.addEventFilter(DragEvent.DRAG_DROPPED) { event ->
            val rom = event.dragboard.takeIf { it.hasFiles() }?.files?.firstOrNull { isRomFile(it) }
            if (rom != null) {
                c.openRom(rom)
            }
            event.isDropCompleted = rom != null
            event.consume()
        }

        (scene.window as? Stage)?.let { stage ->
            // 窗口与任务栏图标
            stage.icons.add(Image("/image/logo.png"))

            // 上次的窗口大小 / 位置；没有配置文件时自动创建
            val windowState = WindowStateKeeper(stage)
            windowState.restore()

            // 直接关窗时也要保存电池存档、自动槽位与窗口状态
            stage.setOnCloseRequest {
                c.onWindowClosing()
                windowState.save()
            }
            stage.setOnHidden { windowState.save() }
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

        // 命令行 --rom 指定的文件：等窗口显示后再加载，避免卡在显示之前
        LaunchOptions.rom()?.let { rom ->
            Platform.runLater {
                val file = rom.toFile()
                if (file.isFile) {
                    c.openRom(file)
                } else {
                    showError("打开 ROM 失败", "文件不存在：\n$rom")
                }
            }
        }
    }
}

/**
 * 主窗口大小与位置的持久化。
 *
 * <p>只记录「非全屏、非最大化」时的正常尺寸；关闭时处于最大化就额外记一个标记，
 * 下次启动会恢复到最大化，取消最大化时仍回到之前的尺寸。
 */
private class WindowStateKeeper(private val stage: Stage) {

    private val store = WindowStateStore()

    private var normalWidth = WindowState.DEFAULT_WIDTH
    private var normalHeight = WindowState.DEFAULT_HEIGHT
    private var normalX: Double? = null
    private var normalY: Double? = null

    /** 恢复过程中属性变化不当作用户操作。 */
    private var restoring = true

    /** 合并同一轮布局里的多次属性变化。 */
    private var captureScheduled = false

    init {
        stage.xProperty().addListener { _, _, _ -> scheduleCapture() }
        stage.yProperty().addListener { _, _, _ -> scheduleCapture() }
        stage.widthProperty().addListener { _, _, _ -> scheduleCapture() }
        stage.heightProperty().addListener { _, _, _ -> scheduleCapture() }
    }

    /** 读取上次的状态并应用到窗口（在 show 之前调用）。 */
    fun restore() {
        val state = store.loadOrCreate()
        val screen = screenFor(state)
        val bounds = screen.visualBounds

        val width = state.width.coerceIn(MIN_WINDOW_WIDTH, max(MIN_WINDOW_WIDTH, bounds.width))
        val height = state.height.coerceIn(MIN_WINDOW_HEIGHT, max(MIN_WINDOW_HEIGHT, bounds.height))
        normalWidth = width
        normalHeight = height

        val savedX = state.x
        val savedY = state.y
        if (savedX != null && savedY != null) {
            // 限定在当前屏幕内，避免换显示器后窗口落在看不见的地方
            val x = savedX.coerceIn(bounds.minX, max(bounds.minX, bounds.maxX - width))
            val y = savedY.coerceIn(bounds.minY, max(bounds.minY, bounds.maxY - height))
            stage.x = x
            stage.y = y
            normalX = x
            normalY = y
        }
        stage.width = width
        stage.height = height
        if (state.maximized) {
            stage.isMaximized = true
        }

        restoring = false
    }

    /** 保存当前窗口状态。 */
    fun save() {
        captureNow()
        val state = WindowState().apply {
            width = normalWidth
            height = normalHeight
            x = normalX
            y = normalY
            maximized = stage.isMaximized
        }
        runCatching { store.save(state) }
            .onFailure { System.err.println("保存窗口状态失败: ${it.message}") }
    }

    /**
     * 属性变化后隔一轮再记录。
     *
     * <p>最大化/全屏会先触发位置变化、后更新 {@code maximized} 属性；
     * 立即读取会把最大化后的坐标当成"正常位置"。延后到本轮事件处理完，
     * 标记已经可靠，再决定是否记录。
     */
    private fun scheduleCapture() {
        if (restoring || captureScheduled) {
            return
        }
        captureScheduled = true
        Platform.runLater {
            captureScheduled = false
            captureNow()
        }
    }

    private fun captureNow() {
        if (stage.isFullScreen || stage.isMaximized) {
            return
        }
        if (stage.width.isFinite() && stage.width >= MIN_WINDOW_WIDTH) {
            normalWidth = stage.width
        }
        if (stage.height.isFinite() && stage.height >= MIN_WINDOW_HEIGHT) {
            normalHeight = stage.height
        }
        if (stage.x.isFinite()) {
            normalX = stage.x
        }
        if (stage.y.isFinite()) {
            normalY = stage.y
        }
    }

    /** 优先选择与上次位置有交集的屏幕，找不到就用主屏。 */
    private fun screenFor(state: WindowState): Screen {
        val x = state.x
        val y = state.y
        if (x != null && y != null) {
            val width = state.width.coerceAtLeast(1.0)
            val height = state.height.coerceAtLeast(1.0)
            Screen.getScreens()
                .firstOrNull { it.visualBounds.intersects(x, y, width, height) }
                ?.let { return it }
        }
        return Screen.getPrimary()
    }
}
