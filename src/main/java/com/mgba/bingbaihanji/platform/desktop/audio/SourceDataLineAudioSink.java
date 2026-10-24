package com.mgba.bingbaihanji.platform.desktop.audio;

import com.mgba.bingbaihanji.core.audio.AudioSink;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * 基于 {@link SourceDataLine} 的音频输出设备。
 *
 * <p>输入为 16-bit 立体声 PCM。若声卡不支持核心报告的采样率
 * （mGBA 约 65536 Hz），自动降级到 48000 Hz 并做线性插值重采样。
 */
public final class SourceDataLineAudioSink implements AudioSink {

    /** 目标采样率，核心采样率不被声卡支持时使用。 */
    private static final float FALLBACK_SAMPLE_RATE = 48000f;

    /** 重采样时每次写入的帧数。 */
    private static final int WRITE_BATCH = 1024;

    private final float coreSampleRate;
    private SourceDataLine line;
    private float outputSampleRate;
    private boolean needsResample;
    private volatile boolean started;
    private volatile float volume = 1.0f;

    public SourceDataLineAudioSink(double coreSampleRate) {
        this.coreSampleRate = (float) coreSampleRate;
    }

    @Override
    public void start() {
        if (started) return;
        try {
            openLine();
            line.start();
            started = true;
        } catch (LineUnavailableException e) {
            throw new RuntimeException("无法打开音频设备", e);
        }
    }

    @Override
    public void pause() {
        if (line != null && started) {
            line.stop();
            line.flush();
        }
    }

    @Override
    public void resume() {
        if (line != null && started) {
            line.start();
        }
    }

    @Override
    public void write(short[] pcm, int frames) {
        if (line == null || !started) return;

        if (needsResample) {
            writeResampled(pcm, frames);
        } else {
            // 直接写入（16-bit LE 立体声）
            byte[] bytes = new byte[frames * 4];
            for (int i = 0; i < frames * 2; i++) {
                short s = pcm[i];
                bytes[i * 2] = (byte) (s & 0xFF);
                bytes[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
            }
            // 应用音量
            if (volume < 0.999f) {
                applyVolume(bytes, frames * 2);
            }
            line.write(bytes, 0, bytes.length);
        }
    }

    @Override
    public void setVolume(float volume) {
        this.volume = Math.max(0f, Math.min(1f, volume));
    }

    @Override
    public void close() {
        started = false;
        if (line != null) {
            line.stop();
            line.close();
            line = null;
        }
    }

    // ── 内部 ──────────────────────────────────────────────────

    private void openLine() throws LineUnavailableException {
        AudioFormat coreFormat = new AudioFormat(coreSampleRate, 16, 2, true, false);
        AudioFormat outputFormat;

        SourceDataLine l = tryOpen(coreFormat);
        if (l != null) {
            line = l;
            outputSampleRate = coreSampleRate;
            needsResample = false;
            return;
        }

        // 核心采样率不支持，降级到 48000 Hz
        AudioFormat fallbackFormat = new AudioFormat(FALLBACK_SAMPLE_RATE, 16, 2, true, false);
        l = tryOpen(fallbackFormat);
        if (l == null) {
            throw new LineUnavailableException("无法打开任何音频格式");
        }
        line = l;
        outputSampleRate = FALLBACK_SAMPLE_RATE;
        needsResample = true;
    }

    private SourceDataLine tryOpen(AudioFormat format) {
        try {
            SourceDataLine l = (SourceDataLine) AudioSystem.getLine(
                    new javax.sound.sampled.DataLine.Info(SourceDataLine.class, format));
            l.open(format);
            setLineVolume(l);
            return l;
        } catch (LineUnavailableException | IllegalArgumentException e) {
            return null;
        }
    }

    private void setLineVolume(SourceDataLine l) {
        if (l.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl gain = (FloatControl) l.getControl(FloatControl.Type.MASTER_GAIN);
            // 0.0 dB = 1.0 音量
            float dB = (float) (20.0 * Math.log10(Math.max(0.001, volume)));
            gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), dB)));
        }
    }

    private void writeResampled(short[] pcm, int frames) {
        // 线性插值重采样
        double ratio = (double) coreSampleRate / outputSampleRate;
        int outFrames = (int) (frames / ratio);
        if (outFrames <= 0) return;

        byte[] bytes = new byte[outFrames * 4];
        for (int i = 0; i < outFrames; i++) {
            double srcPos = i * ratio;
            int srcIdx = (int) srcPos;
            double frac = srcPos - srcIdx;
            if (srcIdx >= frames - 1) srcIdx = frames - 2;
            if (srcIdx < 0) srcIdx = 0;

            for (int ch = 0; ch < 2; ch++) {
                int s0 = pcm[srcIdx * 2 + ch];
                int s1 = pcm[(srcIdx + 1) * 2 + ch];
                short val = (short) (s0 + (s1 - s0) * frac);
                bytes[i * 4 + ch * 2] = (byte) (val & 0xFF);
                bytes[i * 4 + ch * 2 + 1] = (byte) ((val >> 8) & 0xFF);
            }
        }
        if (volume < 0.999f) {
            applyVolume(bytes, outFrames * 2);
        }
        line.write(bytes, 0, bytes.length);
    }

    /** 对 16-bit LE PCM 字节数组应用音量增益。 */
    private void applyVolume(byte[] bytes, int sampleCount) {
        for (int i = 0; i < sampleCount; i++) {
            int off = i * 2;
            short s = (short) ((bytes[off] & 0xFF) | (bytes[off + 1] << 8));
            s = (short) (s * volume);
            bytes[off] = (byte) (s & 0xFF);
            bytes[off + 1] = (byte) ((s >> 8) & 0xFF);
        }
    }
}
