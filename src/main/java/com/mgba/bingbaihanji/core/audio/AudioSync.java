package com.mgba.bingbaihanji.core.audio;

/**
 * 音频同步：比较「核心产出音频的速率」与「声卡实际播放的速率」，微调模拟速度。
 *
 * <h2>为什么不用音频环水位</h2>
 * <p>消费线程是“有数据就立刻读”，音频环几乎总是空的，水位不能反映时钟差。
 * 真正能反映偏差的是两个单调计数器在同样时间窗内的增量：
 * <ul>
 *   <li>核心累计产出的音频帧数（{@code audioFrameCount}）</li>
 *   <li>声卡累计播放的帧数（按重采样比例换算回核心帧）</li>
 * </ul>
 * 两者比值就是声卡时钟相对模拟器时钟的偏差；把它作为速度修正系数，
 * 模拟器与声卡就会锁在一起，不再周期性积压/断流。
 *
 * <p>修正量限制在 ±{@link #MAX_CORRECTION}，并按 {@link #SMOOTHING} 平滑，
 * 避免一秒一次的采样噪声让速度抖动。
 */
public final class AudioSync {

    /** 最大修正量（±1%）。 */
    private static final double MAX_CORRECTION = 0.01;

    /** 每秒向测量值靠拢的比例。 */
    private static final double SMOOTHING = 0.35;

    /** 当前修正系数，1.0 = 不调整。 */
    private double correction = 1.0;

    private long lastProducedFrames = -1;

    private long lastPlayedFrames = -1;

    /**
     * 采样一次。
     *
     * @param producedFrames 核心累计产出的音频帧数（单调递增）
     * @param playedFrames   声卡累计播放的帧数，已换算成核心帧；不可用时传负数
     * @return 新的修正系数
     */
    public double sample(long producedFrames, long playedFrames) {
        if (playedFrames < 0) {
            // 没有音频设备：不调整
            return correction;
        }
        if (lastProducedFrames < 0 || lastPlayedFrames < 0) {
            lastProducedFrames = producedFrames;
            lastPlayedFrames = playedFrames;
            return correction;
        }

        long producedDelta = producedFrames - lastProducedFrames;
        long playedDelta = playedFrames - lastPlayedFrames;
        lastProducedFrames = producedFrames;
        lastPlayedFrames = playedFrames;

        if (producedDelta <= 0 || playedDelta <= 0) {
            // 暂停或这一秒没有音频：保持当前修正
            return correction;
        }

        // 播放/产出：小于 1 说明模拟器跑得比声卡快，需要减速
        double measured = (double) playedDelta / producedDelta;
        measured = Math.max(1.0 - MAX_CORRECTION, Math.min(1.0 + MAX_CORRECTION, measured));
        correction = correction * (1.0 - SMOOTHING) + measured * SMOOTHING;
        correction = Math.max(1.0 - MAX_CORRECTION, Math.min(1.0 + MAX_CORRECTION, correction));
        return correction;
    }

    /** 恢复为 1.0（暂停、倍速、重新加载 ROM 时调用）。 */
    public void reset() {
        correction = 1.0;
        lastProducedFrames = -1;
        lastPlayedFrames = -1;
    }

    /** 当前修正系数。 */
    public double correction() {
        return correction;
    }
}
