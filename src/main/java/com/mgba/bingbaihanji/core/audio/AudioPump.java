package com.mgba.bingbaihanji.core.audio;

import com.mgba.bingbaihanji.core.emulation.AudioSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 音频消费线程：从 {@link AudioSource} 的音频环读取 PCM 帧，写入 {@link AudioSink}。
 *
 * <p>线程模型：内部启动 daemon 线程 {@code mgba-audio}。
 * 暂停时丢弃积压数据（避免恢复后播旧音频）。
 * 音频环空闲时短暂休眠（2ms）等待新数据。
 */
public final class AudioPump {

    /** 每次从音频环读取的帧数（1 帧 = 4 字节立体声 16-bit）。 */
    private static final int READ_BATCH_FRAMES = 1024;

    private final AudioSource source;
    private final AudioSink sink;
    private final ByteBuffer pcmBuffer;
    private final short[] pcmArray;

    private volatile boolean running;
    private volatile boolean paused;
    private Thread thread;

    public AudioPump(AudioSource source, AudioSink sink) {
        this.source = source;
        this.sink = sink;
        this.pcmBuffer = ByteBuffer.allocateDirect(READ_BATCH_FRAMES * 4).order(ByteOrder.LITTLE_ENDIAN);
        this.pcmArray = new short[READ_BATCH_FRAMES * 2];
    }

    /** 启动音频泵。已在运行时为空操作。 */
    public void start() {
        if (running) return;
        running = true;
        paused = false;
        sink.start();
        thread = new Thread(this::pumpLoop, "mgba-audio");
        thread.setDaemon(true);
        thread.start();
    }

    /** 停止音频泵并等待线程退出。 */
    public void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    /** 暂停播放并丢弃积压数据。 */
    public void pause() {
        paused = true;
        sink.pause();
    }

    /** 恢复播放。 */
    public void resume() {
        paused = false;
        sink.resume();
    }

    public boolean isRunning() {
        return running;
    }

    // ── 内部 ──────────────────────────────────────────────────

    private void pumpLoop() {
        while (running) {
            if (paused) {
                discardBacklog();
                sleep(10);
                continue;
            }

            pcmBuffer.clear();
            int frames = source.readAudio(pcmBuffer, READ_BATCH_FRAMES);
            if (frames <= 0) {
                sleep(2);
                continue;
            }

            copyToShortArray(frames);
            sink.write(pcmArray, frames);
        }
    }

    /** 将 direct buffer 的 PCM 数据复制到 short[]（供 AudioSink 写入）。 */
    private void copyToShortArray(int frames) {
        pcmBuffer.rewind();
        for (int i = 0; i < frames * 2; i++) {
            pcmArray[i] = pcmBuffer.getShort();
        }
    }

    /** 暂停时丢弃音频环中的积压数据。 */
    private void discardBacklog() {
        for (int i = 0; i < 4; i++) {
            pcmBuffer.clear();
            if (source.readAudio(pcmBuffer, READ_BATCH_FRAMES) <= 0) break;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
