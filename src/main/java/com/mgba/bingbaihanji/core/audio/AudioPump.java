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

    /** 音频设备是否可用；打开失败时置 false，游戏照常运行但不输出声音。 */
    private volatile boolean audioAvailable = true;

    /** 下一次循环是否丢弃积压（切换速度后避免先播放旧数据）。 */
    private volatile boolean flushRequested;

    /** 一次 flush 最多丢弃的批次数（16 × 1024 帧 = 音频环容量）。 */
    private static final int DISCARD_BATCHES = 16;

    private Thread thread;

    /** 音频设备打开失败时的回调（在泵线程调用，实现方自行切回 UI 线程）。 */
    private volatile Runnable onAudioUnavailable;

    public AudioPump(AudioSource source, AudioSink sink) {
        this.source = source;
        this.sink = sink;
        this.pcmBuffer = ByteBuffer.allocateDirect(READ_BATCH_FRAMES * 4).order(ByteOrder.LITTLE_ENDIAN);
        this.pcmArray = new short[READ_BATCH_FRAMES * 2];
    }

    /**
     * 启动音频泵。已在运行时为空操作。
     *
     * <p>音频设备的打开放在泵线程里做：一是打开设备可能阻塞，不该卡住调用方
     * （通常是 JavaFX 线程）；二是打不开设备时只退化为静音，不能让整局游戏失败。
     */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        paused = false;
        audioAvailable = true;
        thread = new Thread(this::pumpLoop, "mgba-audio");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 注册"音频设备打开失败"的回调。
     *
     * @param callback 回调（泵线程调用）
     */
    public void setOnAudioUnavailable(Runnable callback) {
        this.onAudioUnavailable = callback;
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

    /**
     * 请求丢弃音频环中的积压数据。
     *
     * <p>切换倍速后调用：快进期间缓冲区里堆的是旧音频，恢复原速时不该把它播出来。
     */
    public void flush() {
        flushRequested = true;
    }

    public boolean isRunning() {
        return running;
    }

    /** 音频设备是否成功打开（失败时本局静音）。 */
    public boolean isAudioAvailable() {
        return audioAvailable;
    }

    // ── 内部 ──────────────────────────────────────────────────

    private void pumpLoop() {
        try {
            sink.start();
        } catch (RuntimeException e) {
            audioAvailable = false;
            System.err.println("音频设备不可用，本次运行将静音: " + e.getMessage());
            Runnable callback = onAudioUnavailable;
            if (callback != null) {
                callback.run();
            }
        }

        while (running) {
            if (flushRequested) {
                flushRequested = false;
                discardBacklog();
            }
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
            if (audioAvailable) {
                sink.write(pcmArray, frames);
            }
        }
    }

    /** 将 direct buffer 的 PCM 数据复制到 short[]（供 AudioSink 写入）。 */
    private void copyToShortArray(int frames) {
        pcmBuffer.rewind();
        for (int i = 0; i < frames * 2; i++) {
            pcmArray[i] = pcmBuffer.getShort();
        }
    }

    /** 丢弃音频环中的积压数据（最多把整个环读空）。 */
    private void discardBacklog() {
        for (int i = 0; i < DISCARD_BATCHES; i++) {
            pcmBuffer.clear();
            if (source.readAudio(pcmBuffer, READ_BATCH_FRAMES) <= 0) {
                break;
            }
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
