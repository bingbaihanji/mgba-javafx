package com.mgba.bingbaihanji.core.emulation;

import java.nio.ByteBuffer;

/** 音频来源，由音频线程消费。 */
public interface AudioSource {

    /**
     * 从音频环读取 PCM 帧（立体声 int16）。
     *
     * @param buffer direct ByteBuffer，容量 ≥ maxFrames × 4
     * @return 实际读取的帧数
     */
    int readAudio(ByteBuffer buffer, int maxFrames);

    int audioQueued();

    long audioUnderruns();

    long audioOverflows();

    void resetAudioStats();
}
