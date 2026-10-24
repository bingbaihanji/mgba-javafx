package com.mgba.bingbaihanji.core.emulation;

/**
 * 视频帧来源，由渲染线程消费。
 *
 * <p>底层是三缓冲帧池，**同一时刻只允许一个消费者**：渲染线程持续 acquire/release，
 * 截图（存档缩略图）偶尔也会取一帧。两个消费者必须用 {@link #frameLock()}
 * 串行化，否则一方刚取到的槽位会被另一方释放，生产端随即可能改写它。
 */
public interface FrameSource {

    /** 获取最新一帧，无帧时返回 null。 */
    FrameData acquireFrame();

    /** 释放 acquireFrame 获取的帧。 */
    void releaseFrame();

    /** 帧缓冲消费者锁：渲染与截图都要先拿它，再 acquire/release。 */
    Object frameLock();

    long framesDropped();
}
