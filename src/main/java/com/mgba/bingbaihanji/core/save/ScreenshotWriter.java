package com.mgba.bingbaihanji.core.save;

import com.mgba.bingbaihanji.core.emulation.EmulatorCore;
import com.mgba.bingbaihanji.core.emulation.FrameData;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 从核心的当前帧生成存档缩略图，并原子写成 PNG。
 *
 * <p>直接读核心发布的帧缓冲，不走 GL 回读：保存发生在 JavaFX 线程，
 * 而 GL 上下文在渲染线程上，跨线程读像素既不安全也不必要。
 *
 * <p>像素格式与视频管线一致：0=0RGB1555、1=XRGB8888、2=RGB565，小端字节序。
 */
public final class ScreenshotWriter {

    /** 截图文件名里的时间戳格式（毫秒也带上，连按不会互相覆盖）。 */
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private ScreenshotWriter() {
    }

    /**
     * 抓取当前帧。
     *
     * @param core 已加载 ROM 的核心
     * @return 图片；没有可用帧时返回 null
     */
    public static BufferedImage capture(EmulatorCore core) {
        // 与渲染线程共用消费者锁：截图期间帧槽不能被另一方释放
        synchronized (core.frameLock()) {
            return captureLocked(core);
        }
    }

    private static BufferedImage captureLocked(EmulatorCore core) {
        FrameData frame = core.acquireFrame();
        if (frame == null || frame.width <= 0 || frame.height <= 0) {
            if (frame != null) {
                core.releaseFrame();
            }
            return null;
        }

        try {
            int width = frame.width;
            int height = frame.height;
            int[] pixels = new int[width * height];
            ByteBuffer buffer = frame.buffer.order(ByteOrder.LITTLE_ENDIAN).rewind();

            switch (frame.format) {
                case 1 -> {
                    // XRGB8888
                    for (int i = 0; i < pixels.length; i++) {
                        pixels[i] = buffer.getInt() | 0xFF000000;
                    }
                }
                case 0 -> {
                    // 0RGB1555
                    for (int i = 0; i < pixels.length; i++) {
                        int s = buffer.getShort() & 0xFFFF;
                        int r = ((s >> 10) & 0x1F) << 3;
                        int g = ((s >> 5) & 0x1F) << 3;
                        int b = (s & 0x1F) << 3;
                        pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                }
                default -> {
                    // RGB565（mGBA 默认）
                    for (int i = 0; i < pixels.length; i++) {
                        int s = buffer.getShort() & 0xFFFF;
                        int r = ((s >> 11) & 0x1F) << 3;
                        int g = ((s >> 5) & 0x3F) << 2;
                        int b = (s & 0x1F) << 3;
                        pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                }
            }

            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, width, height, pixels, 0, width);
            return image;
        } finally {
            core.releaseFrame();
        }
    }

    /**
     * 截图并保存为 PNG。
     *
     * @param core      已加载 ROM 的核心
     * @param directory 输出目录（不存在会自动创建）
     * @param baseName  ROM 名（会自动清洗成合法文件名）
     * @return 实际写出的文件；没有可用帧时返回 null
     * @throws IOException 写入失败
     */
    public static Path save(EmulatorCore core, Path directory, String baseName) throws IOException {
        BufferedImage image = capture(core);
        if (image == null) {
            return null;
        }
        String safeName = RomIdentity.sanitizeDirectoryName(
                baseName == null || baseName.isBlank() ? "screenshot" : baseName);
        Path file = directory.resolve(safeName + "-" + FILE_STAMP.format(LocalDateTime.now()) + ".png");
        writePng(image, file);
        return file;
    }

    /**
     * 原子写入 PNG：先写 {@code .tmp}，再改名覆盖目标。
     *
     * @param image  图片
     * @param target 目标文件
     * @throws IOException 写入失败
     */
    public static void writePng(BufferedImage image, Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            if (!ImageIO.write(image, "png", tmp.toFile())) {
                throw new IOException("没有可用的 PNG 编码器");
            }
            try {
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
