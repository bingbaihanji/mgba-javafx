package com.mgba.bingbaihanji.view.video

import com.bingbaihanji.xuan.renderer.Gc
import com.mgba.bingbaihanji.core.emulation.FrameSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * 游戏视频渲染器：从 [FrameSource] 获取帧，转换像素格式后绘制到 GL 画布。
 *
 * 支持 RGB565（mGBA 默认）和 XRGB8888 格式，等比 letterbox 缩放。
 * 视频绘制在 [topOffset] 到 [canvasHeight - bottomOffset] 的中心区域内，
 * 顶部/底部区域填充背景色（对应菜单栏和状态栏）。
 */
class GameVideoRenderer {

    private var lastWidth = 0
    private var lastHeight = 0

    /**
     * 渲染一帧。
     *
     * @param gc          绘图上下文
     * @param frameSource 帧来源，null 时只画背景
     * @param canvasWidth  GL 画布宽度（设备像素）
     * @param canvasHeight GL 画布高度（设备像素）
     * @param topOffset    顶部不可用区域高度（菜单栏，设备像素）
     * @param bottomOffset 底部不可用区域高度（状态栏，设备像素）
     */
    fun render(
        gc: Gc, frameSource: FrameSource?,
        canvasWidth: Float, canvasHeight: Float,
        topOffset: Float = 0f, bottomOffset: Float = 0f
    ) {
        // 偏移量夹在画布范围内：窗口很矮时若偏移量之和超过画布高度，
        // 中心区域会变成负数，drawImage 收到负尺寸后什么都不画（静默消失）
        val safeTop = topOffset.coerceIn(0f, canvasHeight)
        val safeBottom = bottomOffset.coerceIn(0f, canvasHeight)
        val contentY = safeTop
        val contentH = (canvasHeight - safeTop - safeBottom).coerceAtLeast(0f)

        if (frameSource == null) {
            drawBackground(gc, canvasWidth, canvasHeight)
            return
        }

        val frame = frameSource.acquireFrame()
        if (frame == null) {
            drawBackground(gc, canvasWidth, canvasHeight)
            return
        }

        try {
            lastWidth = frame.width
            lastHeight = frame.height

            // 整个画布黑底（含菜单/状态栏后面）
            gc.fill = 0xFF1A1A2E.toInt()
            gc.fillRect(0f, 0f, canvasWidth, canvasHeight)

            if (contentH <= 0f) return

            // 在中心区域内 letterbox
            val scale = min(canvasWidth / frame.width, contentH / frame.height)
            val dw = frame.width * scale
            val dh = frame.height * scale
            val dx = (canvasWidth - dw) / 2f
            val dy = contentY + (contentH - dh) / 2f

            // 视频区域黑底
            gc.fill = 0xFF000000.toInt()
            gc.fillRect(0f, contentY, canvasWidth, contentH)

            drawFrame(gc, frame.buffer, frame.width, frame.height, frame.format, dx, dy, dw, dh)
        } finally {
            frameSource.releaseFrame()
        }
    }

    /** 当前帧的分辨率，无帧时返回 (0, 0)。 */
    fun resolution(): Pair<Int, Int> = lastWidth to lastHeight

    // ── 内部 ──────────────────────────────────────────────────

    private fun drawBackground(gc: Gc, w: Float, h: Float) {
        gc.fill = 0xFF1A1A2E.toInt()
        gc.fillRect(0f, 0f, w, h)
    }

    /**
     * 将帧缓冲像素格式转换后绘制到目标矩形。
     *
     * 格式码: 2=RGB565, 1=XRGB8888, 0=0RGB1555（按 RGB565 处理）。小端字节序。
     */
    private fun drawFrame(
        gc: Gc, buffer: ByteBuffer, w: Int, h: Int, format: Int,
        dx: Float, dy: Float, dw: Float, dh: Float
    ) {
        val pixels = IntArray(w * h)
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
        gc.drawImage(pixels, w, h, dx, dy, dw, dh)
    }
}
