package com.mgba.bingbaihanji.core.emulation;

import java.nio.ByteBuffer;

/** 一帧视频数据。buffer 在 releaseFrame 前有效。 */
public final class FrameData {
    public final ByteBuffer buffer;
    public final int width;
    public final int height;
    public final int format;
    public final long sequence;

    public FrameData(ByteBuffer buffer, int width, int height, int format, long sequence) {
        this.buffer = buffer;
        this.width = width;
        this.height = height;
        this.format = format;
        this.sequence = sequence;
    }
}
