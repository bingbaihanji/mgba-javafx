package com.mgba.bingbaihanji.core.emulation;

import java.nio.ByteBuffer;

/** 视频帧来源，由渲染线程消费。 */
public interface FrameSource {

    /** 获取最新一帧，无帧时返回 null。 */
    FrameData acquireFrame();

    /** 释放 acquireFrame 获取的帧。 */
    void releaseFrame();

    long framesDropped();
}
