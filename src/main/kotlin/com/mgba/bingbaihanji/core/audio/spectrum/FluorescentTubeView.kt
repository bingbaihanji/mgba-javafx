package com.mgba.bingbaihanji.core.audio.spectrum

import com.bingbaihanji.xuan.glview.FXGLTransfer
import com.bingbaihanji.xuan.renderer.Gc
import javafx.scene.layout.StackPane

/**
 * 实时音频频谱可视化组件（xuan / OpenGL 版）。
 *
 * <p>不再使用 JavaFX [javafx.scene.canvas.Canvas]：那是在 FX 线程上逐条
 * `strokeLine` 的 CPU 绘制。这里自带一个 xuan 的 GL 画布
 * （[FXGLTransfer]），每帧在 GL 线程用 [Gc] 一次性提交全部试管顶点，由 GPU 光栅化。
 *
 * <p>为什么需要独立的 GL 画布：本组件通常放在对话框里，而对话框是独立的
 * JavaFX 窗口，不在主窗口那张 GL 画布的场景中，无法复用主画布的 `Gc`；
 * 所以由组件自己持有一份上下文，`dispose()` 时释放。
 *
 * <p>绘制代码与状态栏频谱共用 [FluorescentTube]，两边是同一套视觉。
 *
 * <p>线程模型：[updateSpectrum] 可在任意线程调用（整体替换数组引用发布），
 * 其余绘制状态只在 GL 线程读写。
 *
 * @param viewWidth  组件宽度（逻辑像素）
 * @param viewHeight 组件高度（逻辑像素），默认按 18.5:1 计算
 */
class FluorescentTubeView @JvmOverloads constructor(
    private val viewWidth: Double = DEFAULT_WIDTH,
    private val viewHeight: Double = viewWidth / FluorescentTube.ASPECT
) : StackPane(), AudioSpectrumVisualizer {

    /**
     * 组件自带的 GL 画布。
     *
     * <p>必须在 JavaFX 工具包启动之后构造（由应用界面保证）；
     * 帧率固定 60，预览不需要跟随主画布的无上限刷新率。
     */
    private val transfer = FXGLTransfer(fps = TARGET_FPS)

    private val painter = FluorescentTube(FluorescentTube.PREVIEW_BACKGROUND)

    /** 目标幅度（dB）；整体替换引用发布，GL 线程一次取走完整快照。 */
    @Volatile
    private var target: FloatArray = silence()

    /** 是否推进动画；[stop] 后画面冻结在最后一帧。 */
    @Volatile
    private var running = true

    /** 上一帧的纳秒时间戳（只在 GL 线程读写）。 */
    private var lastFrameNanos = 0L

    init {
        children.add(transfer.createGlFXView())

        // 固定尺寸：父容器按 pref 摆放，组件保持 18.5:1
        minWidth = viewWidth
        prefWidth = viewWidth
        maxWidth = viewWidth
        minHeight = viewHeight
        prefHeight = viewHeight
        maxHeight = viewHeight

        transfer.onError { failure ->
            System.err.println("[spectrum] GL 渲染失败: ${failure.message}")
        }
        transfer.onFrame { gc -> renderFrame(gc) }
    }

    /** 每帧绘制（GL 线程，帧已由 [FXGLTransfer] 开好）。 */
    private fun renderFrame(gc: Gc) {
        val w = gc.width.toFloat()
        val h = gc.height.toFloat()
        if (w <= 1f || h <= 1f) {
            return
        }

        val now = System.nanoTime()
        val deltaSeconds = if (lastFrameNanos == 0L) {
            0.0
        } else {
            (now - lastFrameNanos) / 1_000_000_000.0
        }
        lastFrameNanos = now

        if (running) {
            painter.update(target, deltaSeconds)
        }
        // 组件尺寸固定为 viewWidth，直接用设备宽度换算缩放，避免 GL 线程读 JavaFX 布局属性
        val scale = (w / viewWidth).toFloat()
        painter.draw(gc, 0f, 0f, w, h, scale)
    }

    /**
     * 更新频谱数据（任意线程）。
     *
     * @param magnitudes 频段幅度（dB，[-60, 0]），长度不足 128 时其余频段按静音处理
     */
    override fun updateSpectrum(magnitudes: FloatArray?) {
        val next = silence()
        if (magnitudes != null) {
            System.arraycopy(magnitudes, 0, next, 0, minOf(magnitudes.size, next.size))
        }
        target = next
    }

    /** 清空频谱：显示会平滑衰减到静音，而不是瞬间消失。 */
    override fun clear() {
        target = silence()
    }

    /** 恢复动画（组件初始就处于运行状态）。 */
    override fun start() {
        running = true
    }

    /** 冻结动画；画面停在最后一帧。 */
    override fun stop() {
        running = false
    }

    override fun isRunning(): Boolean = running

    /** 释放 GL 资源。组件不再使用后必须调用（通常在对话框关闭时）。 */
    override fun dispose() {
        running = false
        transfer.dispose()
    }

    private companion object {
        /** 默认宽度，与均衡器对话框的预览区一致。 */
        const val DEFAULT_WIDTH = 720.0

        /** 预览帧率；高于 60 只是白烧 GPU。 */
        const val TARGET_FPS = 60.0

        /** 128 个频段的静音值。 */
        fun silence(): FloatArray = FloatArray(SpectrumAnalyzer.BAND_COUNT) { MIN_DB }

        /** 显示动态范围下限（dB），与 [SpectrumAnalyzer] 一致。 */
        const val MIN_DB = -60f
    }
}
