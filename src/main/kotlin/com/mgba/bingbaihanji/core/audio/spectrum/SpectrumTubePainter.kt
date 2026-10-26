package com.mgba.bingbaihanji.core.audio.spectrum

import com.bingbaihanji.xuan.renderer.Gc
import kotlin.math.max
import kotlin.math.min

/**
 * 频谱「发光试管」的 xuan [Gc] 绘制器。
 *
 * 状态栏频谱（主画布）与均衡器预览（[SpectrumTubeView] 自带的 GL 画布）
 * 共用这一份绘制代码：以中心线为轴上下对称的 64 根中空试管
 * （数据取 128 个频段的偶数下标）、绿 → 黄 → 红的横向渐变、四层半透明发光。
 *
 * 绘制走 [Gc] 的批处理管线：一帧内所有试管汇进同一次顶点提交，
 * 由 GPU 光栅化，而不是 JavaFX Canvas 在 FX 线程上逐条 strokeLine。
 *
 * 线程约束：全部方法只在 GL 线程（`onRender` / `onFrame`）里调用；
 * [update] 的目标幅度由音频线程产出、这里只读取快照。
 *
 * @param background 槽位底色（ARGB）。状态栏用与信息条一致的深灰，
 *                   对话框预览用更深的独立面板色。
 */
class SpectrumTubePainter(
    private val background: Int = STATUS_BAR_BACKGROUND
) {

    /**
     * 当前显示值（平滑动画用），长度是**完整频段数 128**。
     *
     * <p>单侧 64 根试管取偶数下标（0、2、…、126），所以这里必须存 128 个值；
     * 只存 64 个会让外侧一半试管永远拿不到数据。
     */
    private val display = FloatArray(BAND_COUNT) { MIN_DB }

    private val target = FloatArray(BAND_COUNT) { MIN_DB }

    /** 单帧下降速度（dB/秒），与原来的 JavaFX 版试管一致：约 0.6 秒从顶掉到底。 */
    private var fadeSpeed = 100f

    /**
     * 载入新一帧目标幅度并推进衰减动画。
     *
     * @param magnitudes  长度 ≥ 128 的频段幅度（dB，[-60, 0]）
     * @param deltaSeconds 距上一帧的秒数
     */
    fun update(magnitudes: FloatArray, deltaSeconds: Double) {
        val n = min(magnitudes.size, target.size)
        for (i in 0 until n) {
            target[i] = magnitudes[i]
        }
        val dt = deltaSeconds.coerceIn(0.0, 0.1).toFloat()
        val step = fadeSpeed * dt
        for (i in target.indices) {
            val t = target[i]
            val d = display[i]
            display[i] = if (t > d) t else max(t, d - step)
        }
    }

    /** 立即清为静音（不等衰减）。 */
    fun reset() {
        display.fill(MIN_DB)
        target.fill(MIN_DB)
    }

    /**
     * 把试管画进给定矩形。
     *
     * <p>整个槽位先铺一层底色；试管内容再按 18.5:1 的最佳宽高比**居中适配**——
     * 槽位比例不理想时左右或上下留空，而不是把试管拉变形。
     *
     * @param gc    绘图上下文（设备像素坐标）
     * @param x     矩形左上角 x
     * @param y     矩形左上角 y
     * @param w     矩形宽
     * @param h     矩形高
     * @param scale 逻辑像素 → 设备像素的缩放（描边宽度按它换算，保持与 JavaFX 版一致）
     */
    fun draw(gc: Gc, x: Float, y: Float, w: Float, h: Float, scale: Float = 1f) {
        if (w <= 1f || h <= 1f) {
            return
        }

        // 槽位底色
        gc.fill = background
        gc.fillRect(x, y, w, h)

        // 18.5:1 内容框，居中
        var contentW = w
        var contentH = contentW / ASPECT
        if (contentH > h) {
            contentH = h
            contentW = contentH * ASPECT
        }
        val cx = x + (w - contentW) * 0.5f
        val cy = y + (h - contentH) * 0.5f

        val centerX = cx + contentW * 0.5f
        val centerY = cy + contentH * 0.5f

        // 中心线
        gc.fill = CENTER_LINE
        gc.fillRect(cx, centerY - 0.5f, contentW, 1f)

        val bandWidth = contentW / (2f * BANDS)
        val maxBar = contentH * 0.5f * 0.85f
        val tubeWidth = bandWidth * 0.7f
        // 描边宽度是逻辑像素：高 DPI 下按缩放换算，否则发光会显得过细
        val lineScale = scale.coerceIn(0.75f, 3f)

        for (i in 0 until BANDS) {
            val dataIndex = i * 2
            if (dataIndex >= display.size) {
                break
            }
            val amplitude = ((display[dataIndex] - MIN_DB) / (0f - MIN_DB)).coerceIn(0f, 1f)
            val barHeight = amplitude * maxBar
            if (barHeight < 1f) {
                continue
            }
            val xRight = centerX + i * bandWidth + bandWidth * 0.5f
            drawTube(
                gc, xRight, centerY, barHeight, tubeWidth,
                colorForPosition(0.5f + i.toFloat() / BANDS * 0.5f), lineScale
            )
            val xLeft = centerX - i * bandWidth - bandWidth * 0.5f
            drawTube(
                gc, xLeft, centerY, barHeight, tubeWidth,
                colorForPosition(0.5f - i.toFloat() / BANDS * 0.5f), lineScale
            )
        }
    }

    /**
     * 一根试管：三层半透明发光 + 一层描边，上下对称的胶囊形。
     *
     * <p>发光层用**填充**胶囊而不是宽描边：视觉几乎一样，但每个图元的顶点数少一个数量级
     * （128 根试管 × 4 层仍能在同一次提交里画完）。
     * 最内层保留描边，才能维持原来的"中空试管"外观。
     */
    private fun drawTube(
        gc: Gc, x: Float, centerY: Float, height: Float, width: Float,
        color: Int, lineScale: Float
    ) {
        val half = width * 0.5f
        val top = centerY - height
        val total = height * 2f
        val left = x - half
        val grow = lineScale
        // 外发光 → 中发光 → 内发光
        glowLayer(gc, left - 1.8f * grow, top - 1.8f * grow, width + 3.6f * grow, total + 3.6f * grow, color, 0.15f)
        glowLayer(gc, left - 1.0f * grow, top - 1.0f * grow, width + 2.0f * grow, total + 2.0f * grow, color, 0.30f)
        glowLayer(gc, left - 0.4f * grow, top - 0.4f * grow, width + 0.8f * grow, total + 0.8f * grow, color, 0.55f)
        // 主线条（描边，保持中空）
        gc.stroke = color
        gc.lineWidth = 1.2f * lineScale
        gc.strokeRect(left, top, width, total, half)
    }

    private fun glowLayer(
        gc: Gc, x: Float, y: Float, w: Float, h: Float, color: Int, alpha: Float
    ) {
        gc.fill = withAlpha(color, alpha)
        gc.fillRect(x, y, w, h, minOf(w, h) * 0.5f)
    }

    /** 给颜色换上新的 alpha（Gc 的 `fill` / `stroke` 都是 ARGB，内部会预乘）。 */
    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255f).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    /** 绿 → 黄 → 橙 → 红，position ∈ [0, 1]。 */
    private fun colorForPosition(position: Float): Int {
        val p = position.coerceIn(0f, 1f)
        val rgb: IntArray = when {
            p < 0.25f -> lerpRgb(p / 0.25f, 0, 255, 0, 0, 255, 255)
            p < 0.5f -> lerpRgb((p - 0.25f) / 0.25f, 0, 255, 255, 255, 255, 0)
            p < 0.75f -> lerpRgb((p - 0.5f) / 0.25f, 255, 255, 0, 255, 165, 0)
            else -> lerpRgb((p - 0.75f) / 0.25f, 255, 165, 0, 255, 0, 0)
        }
        return (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
    }

    private fun lerpRgb(
        t: Float,
        r1: Int, g1: Int, b1: Int,
        r2: Int, g2: Int, b2: Int
    ): IntArray {
        val r = (r1 + (r2 - r1) * t).toInt()
        val g = (g1 + (g2 - g1) * t).toInt()
        val b = (b1 + (b2 - b1) * t).toInt()
        return intArrayOf(r, g, b)
    }

    companion object {
        /** 平铺试管的最佳宽高比（宽/高）。 */
        const val ASPECT = 18.5f

        /** 单侧试管数（128 个频段取偶数下标，左右镜像）。 */
        private const val BANDS = 64

        /** 完整频段数（与频谱分析器的输出长度一致）。 */
        private const val BAND_COUNT = 128

        private const val MIN_DB = -60f

        /** 状态栏槽位底色：与左侧信息条一致，整条状态栏不会被切成两种颜色。 */
        const val STATUS_BAR_BACKGROUND = 0xFF2B2B3D.toInt()

        /** 对话框预览底色：沿用原组件的深色面板。 */
        const val PREVIEW_BACKGROUND = 0xFF171C23.toInt()

        private const val CENTER_LINE = 0x506496C8
    }
}
