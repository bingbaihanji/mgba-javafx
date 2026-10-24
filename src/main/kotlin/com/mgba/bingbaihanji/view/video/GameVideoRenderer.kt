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
 */
class GameVideoRenderer {

    private var lastWidth = 0
    private var lastHeight = 0

    /** 渲染一帧。无帧时绘制背景色。 */
    fun render(gc: Gc, frameSource: FrameSource?, canvasWidth: Float, canvasHeight: Float) {
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

            val scale = min(canvasWidth / frame.width, canvasHeight / frame.height)
            val dw = frame.width * scale
            val dh = frame.height * scale
            val dx = (canvasWidth - dw) / 2f
            val dy = (canvasHeight - dh) / 2f

            // 黑底 + letterbox
            gc.fill = 0xFF000000.toInt()
            gc.fillRect(0f, 0f, canvasWidth, canvasHeight)

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
