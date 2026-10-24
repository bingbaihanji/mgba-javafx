package com.mgba.bingbaihanji.core.emulation;

/** 输入写入端，线程安全。 */
public interface InputSink {

    /** @param sourceId 0=VIRTUAL, 1=KEYBOARD, 2=GAMEPAD */
    void press(GbaButton button, int sourceId);

    void release(GbaButton button, int sourceId);

    void releaseSource(int sourceId);

    void resetInput();

    void cheatReset();

    boolean cheatSet(int index, boolean enabled, String code);
}
