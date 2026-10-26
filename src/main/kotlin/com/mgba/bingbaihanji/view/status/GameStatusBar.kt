package com.mgba.bingbaihanji.view.status

import com.mgba.bingbaihanji.view.theme.UiTheme
import javafx.animation.PauseTransition
import javafx.beans.value.ChangeListener
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.util.Duration

/**
 * 底部状态栏：[SplitPane] 左右分栏。
 *
 * 左侧是信息条（ROM 名称、核心信息、速度、分辨率、临时提示，以及静音 / 均衡器徽标）；
 * 右侧是音频频谱槽位——槽位本身透明，真正的试管由主 GL 画布的 `Gc`
 * 按本类发布的**场景坐标矩形**绘制，因此不必再跑一套 JavaFX 动画。
 *
 * 槽位矩形由 JavaFX 线程在布局变化时算好并整体发布（[spectrumSceneRect]），
 * GL 线程每次取一份完整快照。这里刻意不用 `layoutX` 累加：
 * `SplitPane` 的 item 由皮肤摆放，`layoutX` 并不等于它在场景里的位置
 * （实测恒为 0），用 `localToScene` 才是可靠的口径。
 *
 * 频谱平铺组件的最佳宽高比是 18.5:1。状态栏高度按窗口宽度推算
 * （右侧槽位宽度 ÷ 18.5 + 上下留白），并设了下限/上限；
 * 绘制侧还会再按 18.5:1 居中适配一次，拖动分栏也不会把试管拉变形。
 */
class GameStatusBar : SplitPane() {

    private val romLabel = Label("未加载 ROM")
    private val coreLabel = Label("")
    private val speedLabel = Label("")
    private val resolutionLabel = Label("")
    private val messageLabel = Label("")

    /** 静音徽标：只在静音时占位显示，避免"没声音又看不出原因"。 */
    private val mutedLabel = Label("已静音").apply {
        style = "-fx-text-fill: #E0A94A; -fx-font-size: 12px;"
        isVisible = false
        isManaged = false
        // 窗口很窄时 HBox 会压缩所有子节点，徽标必须拒绝被压掉
        minWidth = Region.USE_PREF_SIZE
    }

    /** 均衡器徽标：均衡器启用时显示，提示当前声音经过处理。 */
    private val equalizerLabel = Label("EQ").apply {
        style = "-fx-text-fill: #6FA8DC; -fx-font-size: 12px;"
        isVisible = false
        isManaged = false
        minWidth = Region.USE_PREF_SIZE
    }

    /** 右侧频谱槽位：透明占位，尺寸即 Gc 绘制区域。 */
    private val spectrumPane = Region().apply {
        style = "-fx-background-color: transparent;"
    }

    /**
     * 频谱槽位在场景坐标中的矩形 `[x, y, w, h]`（逻辑像素）。
     *
     * <p>布局变化时整体替换引用（copy-on-write），GL 线程一次取走完整快照，
     * 不会读到四个值来自不同布局的"撕裂"状态。
     */
    @Volatile
    private var spectrumSlot: DoubleArray? = null

    /** 当前提示消息的清除定时器。 */
    private var messageTimer: PauseTransition? = null

    /** 窗口宽度变化时重算高度（Scene 会在挂载/换窗口时换对象，所以保存监听器方便摘除）。 */
    private val sceneWidthListener = ChangeListener<Number> { _, _, width ->
        updateBarHeight(width.toDouble())
    }

    init {
        styleClass.add("game-status-bar")
        UiTheme.applyStyleSheet(this)

        val infoBar = HBox(18.0).apply {
            style = "-fx-background-color: #2B2B3D; -fx-padding: 4 12 4 12;"
            alignment = Pos.CENTER_LEFT
            children.addAll(
                messageLabel, mutedLabel, equalizerLabel,
                romLabel, coreLabel, speedLabel, resolutionLabel
            )
            HBox.setHgrow(romLabel, Priority.ALWAYS)
        }

        romLabel.style = "-fx-text-fill: #CCCCCC; -fx-font-size: 12px;"
        coreLabel.style = "-fx-text-fill: #888888; -fx-font-size: 12px;"
        speedLabel.style = "-fx-text-fill: #888888; -fx-font-size: 12px;"
        resolutionLabel.style = "-fx-text-fill: #888888; -fx-font-size: 12px;"
        messageLabel.style = "-fx-text-fill: #7FD18B; -fx-font-size: 12px;"

        style = "-fx-background-color: transparent; -fx-background: transparent;"
        items.addAll(infoBar, spectrumPane)
        setDividerPositions(INITIAL_DIVIDER)

        // 窗口缩放、拖动分栏、全屏切换、挂到场景……任何会移动槽位的布局变化都重算一次。
        // localToSceneTransform 覆盖 SplitPane 皮肤摆放 item 的方式（translate / relocate），
        // 其余监听器覆盖尺寸与自身位置变化。
        spectrumPane.localToSceneTransformProperty().addListener { _, _, _ -> updateSpectrumSlot() }
        spectrumPane.layoutBoundsProperty().addListener { _, _, _ -> updateSpectrumSlot() }
        widthProperty().addListener { _, _, _ -> updateSpectrumSlot() }
        heightProperty().addListener { _, _, _ -> updateSpectrumSlot() }
        layoutXProperty().addListener { _, _, _ -> updateSpectrumSlot() }
        layoutYProperty().addListener { _, _, _ -> updateSpectrumSlot() }
        sceneProperty().addListener { _, oldScene, newScene ->
            oldScene?.widthProperty()?.removeListener(sceneWidthListener)
            newScene?.widthProperty()?.addListener(sceneWidthListener)
            updateBarHeight(newScene?.width ?: 0.0)
            updateSpectrumSlot()
        }

        // 先给一个下限高度；挂到场景后按窗口宽度调整
        prefHeight = MIN_BAR_HEIGHT
    }

    /**
     * 按窗口宽度设置状态栏高度。
     *
     * <p>直接写 `prefHeight` 而不是只在 `computePrefHeight` 里算：`BorderPane`
     * 询问的是 `prefHeight(-1)`，拿不到宽度，首次布局会永远停在下限。
     * 窗口宽度变化会重新布局，高度随之更新。
     */
    private fun updateBarHeight(sceneWidth: Double) {
        prefHeight = heightFor(sceneWidth)
    }

    /**
     * 频谱槽位在场景坐标中的矩形 `[x, y, w, h]`（逻辑像素）。
     *
     * @param out 目标数组，长度至少 4
     * @return 槽位尚未完成布局或尺寸无效时返回 false
     */
    fun spectrumSceneRect(out: DoubleArray): Boolean {
        val rect = spectrumSlot ?: return false
        System.arraycopy(rect, 0, out, 0, 4)
        return true
    }

    /** 按当前布局重算槽位矩形（JavaFX 线程）。 */
    private fun updateSpectrumSlot() {
        val bounds = spectrumPane.localToScene(spectrumPane.boundsInLocal)
        spectrumSlot = if (bounds.width > 0.0 && bounds.height > 0.0) {
            doubleArrayOf(bounds.minX, bounds.minY, bounds.width, bounds.height)
        } else {
            null
        }
    }

    private fun heightFor(width: Double): Double {
        if (!width.isFinite() || width <= 0.0) {
            return MIN_BAR_HEIGHT
        }
        val rightWidth = width * (1.0 - INITIAL_DIVIDER)
        return (rightWidth / SPECTRUM_ASPECT + V_PADDING * 2)
            .coerceIn(MIN_BAR_HEIGHT, MAX_BAR_HEIGHT)
    }

    /**
     * 显示一条临时提示（如"已保存到槽位 3"），到时自动清除。
     *
     * @param text    提示文本
     * @param seconds 显示时长（秒）
     */
    fun showMessage(text: String, seconds: Double = 3.0) {
        messageLabel.text = text
        messageTimer?.stop()
        val timer = PauseTransition(Duration.seconds(seconds))
        timer.setOnFinished { messageLabel.text = "" }
        messageTimer = timer
        timer.play()
    }

    /** 更新静音徽标。 */
    fun setMuted(muted: Boolean) {
        mutedLabel.isVisible = muted
        mutedLabel.isManaged = muted
    }

    /** 更新均衡器徽标。 */
    fun setEqualizerActive(active: Boolean) {
        equalizerLabel.isVisible = active
        equalizerLabel.isManaged = active
    }

    /** 更新 ROM 名称。 */
    fun setRomName(name: String) {
        romLabel.text = name
    }

    /** 更新核心信息。 */
    fun setCoreInfo(name: String, version: String) {
        coreLabel.text = if (name.isNotEmpty()) "$name $version" else ""
    }

    /** 更新速度显示。 */
    fun setSpeed(speed: Double) {
        speedLabel.text = if (speed != 1.0) "速度: ${speed}x" else ""
    }

    /** 更新分辨率显示。 */
    fun setResolution(width: Int, height: Int) {
        resolutionLabel.text = if (width > 0) "${width}×${height}" else ""
    }

    private companion object {
        /** 频谱平铺组件的最佳宽高比（宽/高）。 */
        const val SPECTRUM_ASPECT = 18.5

        /** 默认分栏位置：左侧信息条约占 42%，给 ROM 名留出可读宽度。 */
        const val INITIAL_DIVIDER = 0.42

        /** 状态栏上下留白（×2 计入高度）。 */
        const val V_PADDING = 4.0

        /** 最小高度：保证 12px 文字与 18.5:1 的频谱都不至于挤成一条线。 */
        const val MIN_BAR_HEIGHT = 44.0

        /** 最大高度：超宽窗口上不让状态栏吃掉太多画面。 */
        const val MAX_BAR_HEIGHT = 84.0
    }
}
