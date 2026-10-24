package com.mgba.bingbaihanji.core.emulation;

import java.nio.ByteBuffer;

/**
 * 模拟器核心的平台无关契约。
 * 同时是 FrameSource / AudioSource / InputSink 的提供者。
 */
public interface EmulatorCore extends FrameSource, AudioSource, InputSink {

    // ── 生命周期 ──────────────────────────────────────────────

    int initialize(String coreLibraryPath, String systemDirectory);

    int loadGame(String romPath);

    /** 执行一帧。 */
    int runOnce();

    int unloadGame();

    int shutdown();

    void close();

    // ── 状态查询 ──────────────────────────────────────────────

    String lastError();

    String coreName();

    String coreVersion();

    EmulatorState state();

    long frameCount();

    long audioFrameCount();

    int pixelFormat();

    AvInfo avInfo();

    // ── SRAM / RTC ────────────────────────────────────────────

    int sramSize();

    boolean saveSram(ByteBuffer buffer);

    boolean loadSram(ByteBuffer buffer, int size);

    int rtcSize();

    boolean saveRtc(ByteBuffer buffer);

    boolean loadRtc(ByteBuffer buffer, int size);

    // ── 即时存档 ──────────────────────────────────────────────

    long serializeSize();

    boolean serialize(ByteBuffer buffer);

    boolean unserialize(ByteBuffer buffer, int size);

    // ── 核心选项 ──────────────────────────────────────────────

    int optionCount();

    String optionKey(int index);

    String optionValues(int index);

    int optionCurrent(int index);

    boolean setOption(int index, int valueIndex);
}
