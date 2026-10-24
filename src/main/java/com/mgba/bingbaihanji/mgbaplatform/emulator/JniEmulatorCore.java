package com.mgba.bingbaihanji.mgbaplatform.emulator;

import com.mgba.bingbaihanji.core.emulation.AvInfo;
import com.mgba.bingbaihanji.core.emulation.EmulatorCore;
import com.mgba.bingbaihanji.core.emulation.EmulatorState;
import com.mgba.bingbaihanji.core.emulation.FrameData;
import com.mgba.bingbaihanji.core.emulation.GbaButton;

import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReentrantLock;

/**
 * EmulatorCore 的 JNI 实现。所有 native 调用共享一把 ReentrantLock，
 * 保证同一时刻只有一个 native 入口在执行。
 */
public final class JniEmulatorCore implements EmulatorCore {

    private final long handle;

    private final ReentrantLock lock = new ReentrantLock();

    private volatile boolean closed;

    public JniEmulatorCore() {
        this.handle = NativeEmulator.nativeCreate();
        if (this.handle == 0) {
            throw new IllegalStateException("nativeCreate 失败");
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("EmulatorCore 已关闭");
        }
    }

    // ── 生命周期 ──────────────────────────────────────────────

    @Override
    public int initialize(String coreLibraryPath, String systemDirectory) {
        checkOpen();
        lock.lock();
        try {
            return NativeEmulator.nativeInitialize(handle, coreLibraryPath, systemDirectory);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int loadGame(String romPath) {
        checkOpen();
        lock.lock();
        try {
            return NativeEmulator.nativeLoadGame(handle, romPath);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int runOnce() {
        checkOpen();
        lock.lock();
        try {
            return NativeEmulator.nativeRunOnce(handle);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int unloadGame() {
        checkOpen();
        lock.lock();
        try {
            return NativeEmulator.nativeUnloadGame(handle);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int shutdown() {
        checkOpen();
        lock.lock();
        try {
            return NativeEmulator.nativeShutdown(handle);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        lock.lock();
        try {
            NativeEmulator.nativeDestroy(handle);
            closed = true;
        } finally {
            lock.unlock();
        }
    }

    // ── 状态 ──────────────────────────────────────────────────

    @Override
    public String lastError() {
        checkOpen();
        return NativeEmulator.nativeLastError(handle);
    }

    @Override
    public String coreName() {
        checkOpen();
        return NativeEmulator.nativeCoreName(handle);
    }

    @Override
    public String coreVersion() {
        checkOpen();
        return NativeEmulator.nativeCoreVersion(handle);
    }

    @Override
    public EmulatorState state() {
        checkOpen();
        return EmulatorState.fromCode(NativeEmulator.nativeState(handle));
    }

    @Override
    public long frameCount() {
        checkOpen();
        return NativeEmulator.nativeFrameCount(handle);
    }

    @Override
    public long audioFrameCount() {
        checkOpen();
        return NativeEmulator.nativeAudioFrameCount(handle);
    }

    @Override
    public int pixelFormat() {
        checkOpen();
        return NativeEmulator.nativePixelFormat(handle);
    }

    @Override
    public AvInfo avInfo() {
        checkOpen();
        double[] info = new double[2];
        NativeEmulator.nativeGetAvInfo(handle, info);
        return new AvInfo(info[0], info[1]);
    }

    // ── 帧 ────────────────────────────────────────────────────

    @Override
    public FrameData acquireFrame() {
        checkOpen();
        long[] meta = new long[4];
        ByteBuffer buf = NativeEmulator.nativeAcquireFrame(handle, meta);
        if (buf == null) {
            return null;
        }
        return new FrameData(buf, (int) meta[0], (int) meta[1], (int) meta[2], meta[3]);
    }

    @Override
    public void releaseFrame() {
        checkOpen();
        NativeEmulator.nativeReleaseFrame(handle);
    }

    @Override
    public long framesDropped() {
        checkOpen();
        return NativeEmulator.nativeFramesDropped(handle);
    }

    // ── 音频 ──────────────────────────────────────────────────

    @Override
    public int readAudio(ByteBuffer buffer, int maxFrames) {
        checkOpen();
        return NativeEmulator.nativeReadAudio(handle, buffer, maxFrames);
    }

    @Override
    public int audioQueued() {
        checkOpen();
        return NativeEmulator.nativeAudioQueued(handle);
    }

    @Override
    public long audioUnderruns() {
        checkOpen();
        return NativeEmulator.nativeAudioUnderruns(handle);
    }

    @Override
    public long audioOverflows() {
        checkOpen();
        return NativeEmulator.nativeAudioOverflows(handle);
    }

    @Override
    public void resetAudioStats() {
        checkOpen();
        NativeEmulator.nativeResetAudioStats(handle);
    }

    // ── 输入 ──────────────────────────────────────────────────

    @Override
    public void press(GbaButton button, int sourceId) {
        checkOpen();
        NativeEmulator.nativeInputPress(handle, button.id, sourceId);
    }

    @Override
    public void release(GbaButton button, int sourceId) {
        checkOpen();
        NativeEmulator.nativeInputRelease(handle, button.id, sourceId);
    }

    @Override
    public void releaseSource(int sourceId) {
        checkOpen();
        NativeEmulator.nativeInputReleaseSource(handle, sourceId);
    }

    @Override
    public void resetInput() {
        checkOpen();
        NativeEmulator.nativeInputReset(handle);
    }

    // ── 金手指 ────────────────────────────────────────────────

    @Override
    public void cheatReset() {
        checkOpen();
        NativeEmulator.nativeCheatReset(handle);
    }

    @Override
    public boolean cheatSet(int index, boolean enabled, String code) {
        checkOpen();
        return NativeEmulator.nativeCheatSet(handle, index, enabled, code);
    }

    // ── SRAM / RTC ────────────────────────────────────────────

    @Override
    public int sramSize() {
        checkOpen();
        return NativeEmulator.nativeSramSize(handle);
    }

    @Override
    public boolean saveSram(ByteBuffer buffer) {
        checkOpen();
        lock.lock();
        try {
            return NativeEmulator.nativeSaveSram(handle, buffer);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean loadSram(ByteBuffer buffer, int size) {
        checkOpen();
        lock.lock();
        try {
            return NativeEmulator.nativeLoadSram(handle, buffer, size);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int rtcSize() {
        checkOpen();
        return NativeEmulator.nativeRtcSize(handle);
    }

    @Override
    public boolean saveRtc(ByteBuffer buffer) {
        checkOpen();
        lock.lock();
        try {
            return NativeEmulator.nativeSaveRtc(handle, buffer);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean loadRtc(ByteBuffer buffer, int size) {
        checkOpen();
        lock.lock();
        try {
            return NativeEmulator.nativeLoadRtc(handle, buffer, size);
        } finally {
            lock.unlock();
        }
    }

    // ── 即时存档 ──────────────────────────────────────────────

    @Override
    public long serializeSize() {
        checkOpen();
        return NativeEmulator.nativeSerializeSize(handle);
    }

    @Override
    public boolean serialize(ByteBuffer buffer) {
        checkOpen();
        lock.lock();
        try {
            return NativeEmulator.nativeSerialize(handle, buffer);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean unserialize(ByteBuffer buffer, int size) {
        checkOpen();
        lock.lock();
        try {
            return NativeEmulator.nativeUnserialize(handle, buffer, size);
        } finally {
            lock.unlock();
        }
    }

    // ── 核心选项 ──────────────────────────────────────────────

    @Override
    public int optionCount() {
        checkOpen();
        return NativeEmulator.nativeOptionCount(handle);
    }

    @Override
    public String optionKey(int index) {
        checkOpen();
        return NativeEmulator.nativeOptionKey(handle, index);
    }

    @Override
    public String optionValues(int index) {
        checkOpen();
        return NativeEmulator.nativeOptionValues(handle, index);
    }

    @Override
    public int optionCurrent(int index) {
        checkOpen();
        return NativeEmulator.nativeOptionCurrent(handle, index);
    }

    @Override
    public boolean setOption(int index, int valueIndex) {
        checkOpen();
        lock.lock();
        try {
            return NativeEmulator.nativeSetOption(handle, index, valueIndex);
        } finally {
            lock.unlock();
        }
    }
}
