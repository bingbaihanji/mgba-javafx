package com.mgba.bingbaihanji.core.audio.spectrum;

/**
 * 实时频谱分析：把 PCM 帧流做 FFT，映射为对数分布的频段幅度。
 *
 * <p>输出约定与 {@link AudioSpectrumVisualizer#updateSpectrum} 一致：
 * 长度 {@link #BAND_COUNT}（128）的数组，单位 dB，范围 {@code [-60, 0]}。
 *
 * <p>线程模型：{@link #feed} 只在音频泵线程调用；{@link #copyMagnitudes}
 * 可在任意线程（GL 线程 / JavaFX 线程）调用。分析结果整体替换引用发布，
 * 读者一次取走快照，不需要加锁。
 */
public final class SpectrumAnalyzer {

    /** 频段数（对数分布）。 */
    public static final int BAND_COUNT = 128;

    /** FFT 点数（2 的幂）。 */
    private static final int FFT_SIZE = 2048;

    /** 显示动态范围下限（dB），与 {@link AudioSpectrumVisualizer} 的约定一致。 */
    private static final float MIN_DB = -60f;

    /** 显示动态范围上限（dB）。 */
    private static final float MAX_DB = 0f;

    /** 频段覆盖的最低频率（Hz）；GBA 音源低频到这个量级就够看了。 */
    private static final double BAND_MIN_HZ = 40.0;

    /** 频段覆盖的最高频率（Hz）。 */
    private static final double BAND_MAX_HZ = 16000.0;

    /** 最近 [FFT_SIZE] 个单声道样本的环形历史；只在 feed 线程写。 */
    private final float[] history = new float[FFT_SIZE];

    /** Hann 窗。 */
    private final float[] window = new float[FFT_SIZE];

    /** FFT 工作区（只在 feed 线程用）。 */
    private final float[] re = new float[FFT_SIZE];

    private final float[] im = new float[FFT_SIZE];

    /**
     * 各频段内最强的单 bin 幅度（只在 feed 线程用）。
     *
     * <p>用峰值而不是带内平均：对数频段在高频侧会覆盖很多 bin，
     * 平均会把一条落在带内的单音按带宽稀释十几 dB，频谱看起来"高频全灭"。
     * 峰值口径才是"这个频段有多响"，也与 {@link AudioSpectrumVisualizer} 的约定一致。
     */
    private final double[] bandPeak = new double[BAND_COUNT];

    private volatile double sampleRate = 48000.0;

    private int historyPos = 0;

    private int historyFilled = 0;

    private volatile float[] magnitudes = silence();

    private volatile int sequence = 0;

    public SpectrumAnalyzer() {
        for (int i = 0; i < FFT_SIZE; ++i) {
            window[i] = 0.5f * (float) (1.0 - Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)));
        }
    }

    /** 原地迭代式 radix-2 FFT。 */
    private static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; ++i) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                float t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wRe = Math.cos(ang);
            double wIm = Math.sin(ang);
            int half = len >> 1;
            for (int i = 0; i < n; i += len) {
                double curRe = 1.0;
                double curIm = 0.0;
                for (int k = 0; k < half; ++k) {
                    int u = i + k;
                    int v = u + half;
                    float tRe = (float) (re[v] * curRe - im[v] * curIm);
                    float tIm = (float) (re[v] * curIm + im[v] * curRe);
                    re[v] = re[u] - tRe;
                    im[v] = im[u] - tIm;
                    re[u] += tRe;
                    im[u] += tIm;
                    double nRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nRe;
                }
            }
        }
    }

    private static float[] silence() {
        float[] out = new float[BAND_COUNT];
        java.util.Arrays.fill(out, MIN_DB);
        return out;
    }

    /**
     * 设置采样率（Hz），决定频段到 FFT bin 的映射。
     *
     * @param hz 采样率
     */
    public void setSampleRate(double hz) {
        if (hz > 0) {
            sampleRate = hz;
        }
    }

    /**
     * 喂入一段交错立体声 16-bit PCM（降混为单声道后进历史缓冲）。
     *
     * @param pcm    交错存放 L,R 的样本缓冲
     * @param frames 帧数（每帧 2 个样本）
     */
    public void feed(short[] pcm, int frames) {
        if (frames <= 0) {
            return;
        }
        for (int i = 0; i < frames; ++i) {
            int li = i * 2;
            float mono = (pcm[li] + pcm[li + 1]) * 0.5f / 32768f;
            history[historyPos] = mono;
            historyPos = (historyPos + 1) % FFT_SIZE;
            if (historyFilled < FFT_SIZE) {
                historyFilled++;
            }
        }
        if (historyFilled >= FFT_SIZE) {
            analyze();
        }
    }

    /**
     * 把最新一帧频谱快照拷贝到 {@code out}。
     *
     * @param out 目标数组，长度至少 {@link #BAND_COUNT}
     */
    public void copyMagnitudes(float[] out) {
        float[] src = magnitudes;
        int n = Math.min(out.length, src.length);
        System.arraycopy(src, 0, out, 0, n);
    }

    // ── 内部 ──────────────────────────────────────────────────

    /** 当前频谱快照的序列号，每次分析后自增，便于消费端跳过重复数据。 */
    public int sequence() {
        return sequence;
    }

    /** 清空为静音频谱（-60 dB）。 */
    public void clear() {
        magnitudes = silence();
        sequence++;
    }

    /** 取环里最近 [FFT_SIZE] 个样本，加窗、FFT、按频段聚合。 */
    private void analyze() {
        // 按时间顺序展开环（最旧在前）
        for (int i = 0; i < FFT_SIZE; ++i) {
            int idx = (historyPos + i) % FFT_SIZE;
            re[i] = history[idx] * window[i];
            im[i] = 0f;
        }
        fft(re, im);

        java.util.Arrays.fill(bandPeak, 0.0);
        double nyquist = sampleRate * 0.5;
        double logMin = Math.log(BAND_MIN_HZ);
        double logMax = Math.log(Math.min(BAND_MAX_HZ, nyquist * 0.98));
        double binHz = sampleRate / FFT_SIZE;

        // 只用前一半 bin（后一半是镜像）
        int half = FFT_SIZE / 2;
        for (int k = 1; k < half; ++k) {
            double freq = k * binHz;
            if (freq < BAND_MIN_HZ || freq > Math.min(BAND_MAX_HZ, nyquist * 0.98)) {
                continue;
            }
            int band = (int) ((Math.log(freq) - logMin) / (logMax - logMin) * BAND_COUNT);
            if (band < 0) {
                band = 0;
            }
            if (band >= BAND_COUNT) {
                band = BAND_COUNT - 1;
            }
            double mag = Math.sqrt((double) re[k] * re[k] + (double) im[k] * im[k]);
            if (mag > bandPeak[band]) {
                bandPeak[band] = mag;
            }
        }

        // Hann 窗相干增益 0.5：满量程正弦的 bin 峰值为 N/4，×4/N 后归一到 1.0（0 dB）
        float[] next = new float[BAND_COUNT];
        double norm = 4.0 / FFT_SIZE;
        for (int b = 0; b < BAND_COUNT; ++b) {
            double db = 20.0 * Math.log10(bandPeak[b] * norm + 1e-9);
            if (db < MIN_DB) {
                db = MIN_DB;
            }
            if (db > MAX_DB) {
                db = MAX_DB;
            }
            next[b] = (float) db;
        }
        magnitudes = next;
        sequence++;
    }
}
