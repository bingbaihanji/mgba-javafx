package com.mgba.bingbaihanji.core.audio;

/**
 * 十段图形均衡器（峰值型 biquad）+ 前置增益。
 *
 * <p>在音频泵把 PCM 写入声卡之前原地处理，按频段提升/衰减：
 * 31 / 62 / 125 / 250 / 500 / 1k / 2k / 4k / 8k / 16k Hz，
 * 每段增益范围 {@code [-12, +12]} dB，另有同样范围的前置增益。
 *
 * <p>线程模型：设置方法可在任意线程（JavaFX 线程的对话框）调用，
 * {@link #process} 只在音频泵线程调用。参数用 copy-on-write 数组 + 脏标记发布，
 * 系数在 {@code process} 内按需重算（重算很便宜，不必加锁）。
 *
 * <p>关闭（{@link #setEnabled}{@code (false)}）时 {@code process} 直接返回，
 * 不做任何乘加，零开销直通。
 */
public final class GraphicEqualizer {

    /** 频段数。 */
    public static final int BAND_COUNT = 10;

    /** 各频段中心频率（Hz），与界面上的刻度一一对应。 */
    public static final double[] BAND_FREQUENCIES_HZ = {
            31.25, 62.5, 125, 250, 500, 1000, 2000, 4000, 8000, 16000
    };

    /** 单段（及前置）增益下限（dB）。 */
    public static final double MIN_GAIN_DB = -12.0;

    /** 单段（及前置）增益上限（dB）。 */
    public static final double MAX_GAIN_DB = 12.0;

    /** 峰值滤波器 Q 值，约等于 1 倍频程带宽（图形均衡器的常用取值）。 */
    private static final double BAND_Q = 1.41;

    /** 立体声两声道各一条 biquad 链（直接 II 型转置）。 */
    private final Biquad[] left = new Biquad[BAND_COUNT];

    private final Biquad[] right = new Biquad[BAND_COUNT];

    private volatile boolean enabled = false;

    private volatile double preampDb = 0.0;

    /** copy-on-write：写方整体替换，读方一次取引用。 */
    private volatile double[] bandGainsDb = newBands(0.0);

    private volatile double sampleRate = 48000.0;

    /** 参数变化后置位；{@link #process} 内重算系数。 */
    private volatile boolean coefficientsDirty = true;

    public GraphicEqualizer() {
        for (int i = 0; i < BAND_COUNT; ++i) {
            left[i] = new Biquad();
            right[i] = new Biquad();
        }
    }

    /**
     * RBJ Cookbook 峰值均衡器系数。
     *
     * @return {@code [b0, b1, b2, a1, a2]}（已按 a0 归一）
     */
    private static double[] peaking(double freq, double fs, double gainDb, double q) {
        double a = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * freq / fs;
        double cosW0 = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * q);

        double b0 = 1.0 + alpha * a;
        double b1 = -2.0 * cosW0;
        double b2 = 1.0 - alpha * a;
        double a0 = 1.0 + alpha / a;
        double a1 = -2.0 * cosW0;
        double a2 = 1.0 - alpha / a;

        return new double[]{b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
    }

    private static double clampGain(double db) {
        if (!Double.isFinite(db)) {
            return 0.0;
        }
        return Math.max(MIN_GAIN_DB, Math.min(MAX_GAIN_DB, db));
    }

    private static short clampSample(double v) {
        if (v > 32767.0) {
            return 32767;
        }
        if (v < -32768.0) {
            return -32768;
        }
        return (short) v;
    }

    private static double[] newBands(double value) {
        double[] bands = new double[BAND_COUNT];
        java.util.Arrays.fill(bands, value);
        return bands;
    }

    /** 是否启用；关闭时声音直通。 */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getPreampDb() {
        return preampDb;
    }

    /** 前置增益（dB），落在 {@code [MIN_GAIN_DB, MAX_GAIN_DB]} 内。 */
    public void setPreampDb(double db) {
        this.preampDb = clampGain(db);
        this.coefficientsDirty = true;
    }

    /**
     * 设置单个频段增益（dB）。
     *
     * @param band 频段下标 0–9
     * @param db   增益
     */
    public void setBandGainDb(int band, double db) {
        if (band < 0 || band >= BAND_COUNT) {
            return;
        }
        double[] next = bandGainsDb.clone();
        next[band] = clampGain(db);
        bandGainsDb = next;
        coefficientsDirty = true;
    }

    /** 当前各频段增益（dB）的副本。 */
    public double[] getBandGainsDb() {
        return bandGainsDb.clone();
    }

    // ── 内部 ──────────────────────────────────────────────────

    /**
     * 一次性设置全部频段增益（dB）。
     *
     * @param gains 长度至少 {@link #BAND_COUNT} 的数组，多余部分忽略
     */
    public void setBandGainsDb(double[] gains) {
        double[] next = newBands(0.0);
        int n = Math.min(BAND_COUNT, gains == null ? 0 : gains.length);
        for (int i = 0; i < n; ++i) {
            next[i] = clampGain(gains[i]);
        }
        bandGainsDb = next;
        coefficientsDirty = true;
    }

    /** 全部参数归零（直通曲线），开关状态不变。 */
    public void resetFlat() {
        preampDb = 0.0;
        bandGainsDb = newBands(0.0);
        coefficientsDirty = true;
    }

    /**
     * 设置采样率（Hz）。核心报告的采样率随 ROM/核心变化，加载时同步一次。
     *
     * @param hz 采样率
     */
    public void setSampleRate(double hz) {
        if (hz > 0 && hz != sampleRate) {
            sampleRate = hz;
            coefficientsDirty = true;
        }
    }

    /**
     * 就地处理一段交错立体声 16-bit PCM。
     *
     * @param pcm    交错存放 L,R 的样本缓冲，处理后原地覆盖
     * @param frames 帧数（每帧 2 个样本）
     */
    public void process(short[] pcm, int frames) {
        if (!enabled || frames <= 0) {
            return;
        }
        if (coefficientsDirty) {
            rebuildCoefficients();
        }
        double preamp = Math.pow(10.0, preampDb / 20.0);
        for (int i = 0; i < frames; ++i) {
            int li = i * 2;
            int ri = li + 1;
            double l = pcm[li] * preamp;
            double r = pcm[ri] * preamp;
            for (int b = 0; b < BAND_COUNT; ++b) {
                l = left[b].process(l);
                r = right[b].process(r);
            }
            pcm[li] = clampSample(l);
            pcm[ri] = clampSample(r);
        }
    }

    private void rebuildCoefficients() {
        coefficientsDirty = false;
        double fs = sampleRate;
        double[] gains = bandGainsDb;
        for (int i = 0; i < BAND_COUNT; ++i) {
            double[] c = peaking(BAND_FREQUENCIES_HZ[i], fs, gains[i], BAND_Q);
            left[i].setCoefficients(c);
            right[i].setCoefficients(c);
        }
    }

    /** 单条 biquad（直接 II 型转置），双声道各自持有独立状态。 */
    private static final class Biquad {

        private double b0 = 1.0;

        private double b1;

        private double b2;

        private double a1;

        private double a2;

        private double s1;

        private double s2;

        void setCoefficients(double[] c) {
            b0 = c[0];
            b1 = c[1];
            b2 = c[2];
            a1 = c[3];
            a2 = c[4];
        }

        double process(double x) {
            double y = b0 * x + s1;
            s1 = b1 * x - a1 * y + s2;
            s2 = b2 * x - a2 * y;
            return y;
        }
    }
}
