package com.mgba.bingbaihanji.core.audio;

/**
 * 音频输出设备。实现方负责把 PCM 立体声 int16 数据写入硬件。
 */
public interface AudioSink {

    void start();

    void pause();

    void resume();

    /**
     * 阻塞写入。
     *
     * @param pcm    立体声 int16 PCM 数据
     * @param frames 帧数（每帧 4 字节）
     */
    void write(short[] pcm, int frames);

    void setVolume(float volume);

    void close();
}
