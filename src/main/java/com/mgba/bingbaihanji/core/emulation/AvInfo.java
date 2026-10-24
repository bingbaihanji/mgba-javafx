package com.mgba.bingbaihanji.core.emulation;

/** 核心报告的音视频参数。 */
public final class AvInfo {
    public final double fps;
    public final double sampleRate;

    public AvInfo(double fps, double sampleRate) {
        this.fps = fps;
        this.sampleRate = sampleRate;
    }
}
