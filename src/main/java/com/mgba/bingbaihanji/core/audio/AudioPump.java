package com.mgba.bingbaihanji.core.audio;

import com.mgba.bingbaihanji.core.emulation.AudioSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 音频消费线程：从 AudioSource 的音频环读取 PCM 帧，写入 AudioSink。
 * 线程名 mgba-audio。
 */
public final class AudioPump {

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

    public void start() {
        if (running) return;
        running = true;
        paused = false;
        sink.start();
        thread = new Thread(this::pumpLoop, "mgba-audio");
        thread.setDaemon(true);
        thread.start();
    }

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

    public void pause() {
        paused = true;
        sink.pause();
    }

    public void resume() {
        paused = false;
        sink.resume();
    }

    public boolean isRunning() {
        return running;
    }

    private void pumpLoop() {
        while (running) {
            if (paused) {
                // 暂停时丢弃积压
                discardBacklog();
                try { Thread.sleep(10); } catch (InterruptedException e) { return; }
                continue;
            }

            pcmBuffer.clear();
            int frames = source.readAudio(pcmBuffer, READ_BATCH_FRAMES);
            if (frames <= 0) {
                try { Thread.sleep(2); } catch (InterruptedException e) { return; }
                continue;
            }

            // copy direct buffer to short[]
            pcmBuffer.rewind();
            for (int i = 0; i < frames * 2; i++) {
                pcmArray[i] = pcmBuffer.getShort();
            }
            sink.write(pcmArray, frames);
        }
    }

    private void discardBacklog() {
        for (int i = 0; i < 4; i++) {
            pcmBuffer.clear();
            int n = source.readAudio(pcmBuffer, READ_BATCH_FRAMES);
            if (n <= 0) break;
        }
    }
}
