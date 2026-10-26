package com.mgba.bingbaihanji.core.emulation;

import java.nio.ByteBuffer;

/** 一帧视频数据。buffer 在 releaseFrame 前有效。 */
public final class FrameData {

    /**
     * 像素数据（direct buffer，小端字节序）。
     *
     * <p>行与行紧密排列（原生帧池写入时已去掉 pitch 空隙），
     * 长度为 {@code width * height * 每像素字节数}：
     * {@link #format} 为 XRGB8888 时每像素 4 字节，其余格式 2 字节。
     * 指向帧池槽位，{@code releaseFrame} 之后随时可能被生产端改写，不可再读。
     */
    public final ByteBuffer buffer;

    /** 画面宽度（像素），GBA 为 240、GBC 为 160。 */
    public final int width;

    /** 画面高度（像素），GBA 为 160、GBC 为 144。 */
    public final int height;

    /**
     * Libretro 像素格式，决定 {@link #buffer} 的像素布局：
     * 0 = 0RGB1555，1 = XRGB8888，2 = RGB565（mGBA 默认）。
     */
    public final int format;

    /**
     * 帧序号，每写入一帧自增。
     *
     * <p>核心「重复上一帧」时不写入，序号保持不变，
     * 渲染端据此跳过重复的纹理上传。
     */
    public final long sequence;

    public FrameData(ByteBuffer buffer, int width, int height, int format, long sequence) {
        this.buffer = buffer;
        this.width = width;
        this.height = height;
        this.format = format;
        this.sequence = sequence;
    }
}
