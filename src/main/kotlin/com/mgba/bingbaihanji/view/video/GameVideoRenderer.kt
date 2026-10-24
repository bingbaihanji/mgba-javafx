package com.mgba.bingbaihanji.view.video

import com.bingbaihanji.xuan.gl.ShaderProgram
import com.bingbaihanji.xuan.renderer.DynamicImage
import com.bingbaihanji.xuan.renderer.Gc
import com.mgba.bingbaihanji.core.emulation.FrameData
import com.mgba.bingbaihanji.core.emulation.FrameSource
import com.mgba.bingbaihanji.platform.desktop.video.CustomShaderAdapter
import com.mgba.bingbaihanji.platform.desktop.video.ShaderPreset
import com.mgba.bingbaihanji.platform.desktop.video.ShaderPresets
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.min

/**
 * 游戏视频渲染器：从 [FrameSource] 获取帧，上传为可更新纹理后绘制到 GL 画布。
 *
 * <p>帧数据直接以 RGB565（mGBA 默认）上传到 [DynamicImage]，
 * 不会每帧新建像素数组或纹理；序列号相同的重复帧跳过上传。
 *
 * <p>视频绘制在 [topOffset] 到 [canvasHeight - bottomOffset] 的中心区域内等比
 * letterbox，顶部/底部区域填充背景色（对应菜单栏和状态栏）。
 *
 * <p>线程约束：GL 资源（纹理、着色器）只在 [render] 里创建与释放；
 * 设置项通过 volatile 字段从 JavaFX 线程交给 GL 线程。
 */
class GameVideoRenderer {

    // ── 跨线程设置（FX 线程写，GL 线程读）────────────────────

    @Volatile
    private var pendingPreset = ShaderPreset.OFF

    @Volatile
    private var pendingCustomPath: File? = null

    @Volatile
    private var disposeRequested = false

    /** 是否拉伸填满中心区域（关闭时等比 letterbox）。 */
    @Volatile
    private var stretchToFit = false

    /** 纹理是否使用线性过滤（关闭后像素边缘更锐利）。 */
    @Volatile
    private var linearFiltering = true

    /** 纹理当前实际应用的过滤方式，避免每帧重复设置。 */
    private var appliedLinearFilter = true

    /** 着色器加载/编译失败回调（**GL 线程**调用，调用方自行切回 JavaFX 线程）。 */
    @Volatile
    var onShaderError: ((String) -> Unit)? = null

    // ── GL 线程私有状态 ───────────────────────────────────────

    private var activePreset = ShaderPreset.OFF
    private var activeCustomPath: File? = null
    private var shader: ShaderProgram? = null

    private var image: DynamicImage? = null
    private var imageWidth = 0
    private var imageHeight = 0
    private var imageFormat = -1
    private var lastSequence = -1L

    /** 非 RGB565 格式的转换缓冲，复用避免每帧分配。 */
    private var pixelScratch = IntArray(0)

    private var lastWidth = 0
    private var lastHeight = 0

    // ── 对外设置 ──────────────────────────────────────────────

    /**
     * 切换后处理预设（JavaFX 线程调用）。
     *
     * @param preset     预设
     * @param customFile 自定义 GLSL 文件，仅 [ShaderPreset.CUSTOM] 时使用
     */
    fun setShaderPreset(preset: ShaderPreset, customFile: File? = null) {
        pendingPreset = preset
        pendingCustomPath = customFile
    }

    /** 请求释放 GL 资源（JavaFX 线程调用，真正的释放发生在下一帧渲染时）。 */
    fun requestDispose() {
        disposeRequested = true
    }

    /**
     * 设置是否拉伸填满中心区域。
     *
     * @param enabled true 时忽略宽高比铺满；false 时等比缩放并留黑边
     */
    fun setStretchToFit(enabled: Boolean) {
        stretchToFit = enabled
    }

    /**
     * 设置纹理过滤方式（JavaFX 线程调用）。
     *
     * @param enabled true = 线性过滤（平滑）；false = 最近邻（像素清晰）
     */
    fun setLinearFiltering(enabled: Boolean) {
        linearFiltering = enabled
    }

    /** 当前帧的分辨率，无帧时返回 (0, 0)。 */
    fun resolution(): Pair<Int, Int> = lastWidth to lastHeight

    // ── 渲染 ──────────────────────────────────────────────────

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
        if (disposeRequested) {
            disposeRequested = false
            disposeResources(gc)
        }
        applyPendingShader(gc)

        // 偏移量夹在画布范围内：窗口很矮时若偏移量之和超过画布高度，
        // 中心区域会变成负数，drawImage 收到负尺寸后什么都不画（静默消失）
        val safeTop = topOffset.coerceIn(0f, canvasHeight)
        val safeBottom = bottomOffset.coerceIn(0f, canvasHeight)
        val contentY = safeTop
        val contentH = (canvasHeight - safeTop - safeBottom).coerceAtLeast(0f)

        val frame = frameSource?.acquireFrame()
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

            val dw: Float
            val dh: Float
            if (stretchToFit) {
                dw = canvasWidth
                dh = contentH
            } else {
                val scale = min(canvasWidth / frame.width, contentH / frame.height)
                dw = frame.width * scale
                dh = frame.height * scale
            }
            val dx = (canvasWidth - dw) / 2f
            val dy = contentY + (contentH - dh) / 2f

            // 视频区域黑底
            gc.fill = 0xFF000000.toInt()
            gc.fillRect(0f, contentY, canvasWidth, contentH)

            val image = updateImage(gc, frame)
            drawFrame(gc, image, frame.width, frame.height, dx, dy, dw, dh)
        } finally {
            frameSource.releaseFrame()
        }
    }

    // ── 内部 ──────────────────────────────────────────────────

    private fun drawBackground(gc: Gc, w: Float, h: Float) {
        gc.fill = 0xFF1A1A2E.toInt()
        gc.fillRect(0f, 0f, w, h)
    }

    /**
     * 按需创建/更新纹理。
     *
     * 尺寸或格式变化时重建纹理；序列号未变（重复帧）时跳过上传。
     */
    private fun updateImage(gc: Gc, frame: FrameData): DynamicImage {
        val format = when (frame.format) {
            1, 0 -> DynamicImage.PixelFormat.ARGB_8888
            else -> DynamicImage.PixelFormat.RGB_565
        }

        var current = image
        if (current == null
            || imageWidth != frame.width
            || imageHeight != frame.height
            || imageFormat != frame.format
        ) {
            current?.dispose()
            current = gc.createDynamicImage(frame.width, frame.height, format)
            image = current
            imageWidth = frame.width
            imageHeight = frame.height
            imageFormat = frame.format
            lastSequence = -1L
            // 新建的纹理统一显式设置一次过滤方式
            current.setLinearFilter(linearFiltering)
            appliedLinearFilter = linearFiltering
        }

        if (appliedLinearFilter != linearFiltering) {
            current.setLinearFilter(linearFiltering)
            appliedLinearFilter = linearFiltering
        }

        if (frame.sequence != lastSequence) {
            if (format == DynamicImage.PixelFormat.RGB_565) {
                current.update(frame.buffer)
            } else {
                current.update(convertToArgb(frame))
            }
            lastSequence = frame.sequence
        }
        return current
    }

    /**
     * 把 XRGB8888 / 0RGB1555 帧转换成 0xAARRGGBB。
     *
     * 数据是小端字节序；重复帧的缓冲区内容可能被复用，所以每次按序列号转换。
     */
    private fun convertToArgb(frame: FrameData): IntArray {
        val count = frame.width * frame.height
        if (pixelScratch.size != count) {
            pixelScratch = IntArray(count)
        }
        val buffer = frame.buffer.order(ByteOrder.LITTLE_ENDIAN).rewind()
        if (frame.format == 1) {
            // XRGB8888：高位 alpha 不可信，统一补不透明
            for (i in 0 until count) {
                pixelScratch[i] = buffer.int or (0xFF shl 24)
            }
        } else {
            // 0RGB1555：5:5:5
            for (i in 0 until count) {
                val s = buffer.short.toInt() and 0xFFFF
                val r = ((s shr 10) and 0x1F) shl 3
                val g = ((s shr 5) and 0x1F) shl 3
                val b = (s and 0x1F) shl 3
                pixelScratch[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return pixelScratch
    }

    private fun drawFrame(
        gc: Gc, image: DynamicImage, frameWidth: Int, frameHeight: Int,
        dx: Float, dy: Float, dw: Float, dh: Float
    ) {
        val active = shader
        if (active == null) {
            gc.drawDynamicImage(image, dx, dy, dw, dh)
            return
        }
        // uniform 是程序对象的状态：必须先 use() 再设，设置后即使稍后才提交也有效
        active.use()
        active.setUniform("uTexelSize", 1f / frameWidth, 1f / frameHeight)
        gc.drawDynamicImage(image, dx, dy, dw, dh, active)
    }

    /**
     * 处理预设切换：释放旧着色器，编译新着色器。
     *
     * 失败（文件读不到、GLSL 编译错误）时回退到关闭并通知 UI；
     * 同时把 pendingPreset 改回 OFF，避免每帧重复尝试编译。
     */
    private fun applyPendingShader(gc: Gc) {
        val requested = pendingPreset
        val requestedPath = pendingCustomPath
        val sameCustomFile = requested == ShaderPreset.CUSTOM
                && activePreset == ShaderPreset.CUSTOM
                && requestedPath?.absolutePath == activeCustomPath?.absolutePath
        if (requested == activePreset && (requested != ShaderPreset.CUSTOM || sameCustomFile)) {
            return
        }

        shader?.let { runCatching { it.dispose() } }
        shader = null
        activePreset = requested
        activeCustomPath = if (requested == ShaderPreset.CUSTOM) requestedPath else null

        if (requested == ShaderPreset.OFF) {
            gc.setCustomShader(null)
            return
        }

        val source: String?
        if (requested == ShaderPreset.CUSTOM) {
            val path = requestedPath
            if (path == null) {
                fallback(gc, "未选择自定义着色器文件")
                return
            }
            source = try {
                // Android 版着色器是 GLSL ES 写法，直接交给桌面 GL 可能"链接成功但采样坐标未定义"，
                // 画面全黑；这里统一转换成 330 core
                CustomShaderAdapter.toDesktopFragmentShader(
                    ShaderPresets.readCustomSource(path.toPath())
                )
            } catch (e: IOException) {
                fallback(gc, "读取着色器失败：${e.message}")
                return
            }
        } else {
            source = ShaderPresets.fragmentSource(requested)
        }

        if (source == null) {
            fallback(gc, "未知着色器预设")
            return
        }

        try {
            shader = gc.createImageShader(source)
        } catch (e: RuntimeException) {
            fallback(gc, e.message ?: "着色器编译失败")
        }
    }

    private fun fallback(gc: Gc, message: String) {
        activePreset = ShaderPreset.OFF
        pendingPreset = ShaderPreset.OFF
        shader = null
        gc.setCustomShader(null)
        System.err.println("[shader] " + message)
        onShaderError?.invoke(message)
    }

    private fun disposeResources(gc: Gc) {
        shader?.let { runCatching { it.dispose() } }
        shader = null
        image?.let { runCatching { it.dispose() } }
        image = null
        imageWidth = 0
        imageHeight = 0
        imageFormat = -1
        lastSequence = -1L
        activePreset = ShaderPreset.OFF
        activeCustomPath = null
        gc.setCustomShader(null)
    }
}
