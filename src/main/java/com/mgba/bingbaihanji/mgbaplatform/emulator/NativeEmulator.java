package com.mgba.bingbaihanji.mgbaplatform.emulator;

import java.nio.ByteBuffer;

/**
 * 原始 JNI 入口，对应 native/src/jni_bridge.c 的导出符号。
 *
 * 包名固定为 com.mgba.bingbaihanji.mgbaplatform.emulator，
 * C 侧符号为 Java_com_mgba_bingbaihanji_mgbaplatform_emulator_NativeEmulator_*，
 * 移动包会导致 JNI 链接失败。
 *
 * 业务代码应通过 JniEmulatorCore 调用，不要直接使用本类。
 */
public final class NativeEmulator {

    static {
        // 优先从已知路径加载（相对工作目录），找不到再走 java.library.path
        String[] candidates = {
            "build/native/windows-x64/dist/mgba_frontend.dll",
            "native/dist/mgba_frontend.dll"
        };
        boolean loaded = false;
        for (String path : candidates) {
            java.io.File f = new java.io.File(path);
            if (f.exists()) {
                System.load(f.getAbsolutePath());
                loaded = true;
                break;
            }
        }
        if (!loaded) {
            System.loadLibrary("mgba_frontend");
        }
    }

    private NativeEmulator() {
    }

    // ── 生命周期 ──────────────────────────────────────────────

    public static native long nativeCreate();

    public static native void nativeDestroy(long handle);

    /**
     * @param coreLibraryPath mgba_libretro.dll 的绝对路径
     * @param systemDirectory BIOS 等系统文件目录
     * @return 0=成功，非 0 为 MgbaResult 错误码
     */
    public static native int nativeInitialize(long handle, String coreLibraryPath, String systemDirectory);

    public static native int nativeLoadGame(long handle, String romPath);

    /** 执行一帧 retro_run()。 */
    public static native int nativeRunOnce(long handle);

    public static native int nativeUnloadGame(long handle);

    public static native int nativeShutdown(long handle);

    public static native String nativeLastError(long handle);

    public static native String nativeCoreName(long handle);

    public static native String nativeCoreVersion(long handle);

    /** @return MgbaState: 0=UNINITIALIZED, 1=INITIALIZED, 2=GAME_LOADED, 3=RUNNING, 4=ERROR */
    public static native int nativeState(long handle);

    // ── 帧计数 ────────────────────────────────────────────────

    public static native long nativeFrameCount(long handle);

    public static native long nativeAudioFrameCount(long handle);

    // ── 视频 ──────────────────────────────────────────────────

    /** @return 像素格式: 0=0RGB1555, 1=XRGB8888, 2=RGB565 */
    public static native int nativePixelFormat(long handle);

    /**
     * 获取最新一帧。返回 direct ByteBuffer（大小 = width×height×bpp）。
     *
     * @param outMeta 长度 ≥ 4 的数组，填充 [width, height, format, sequence]
     * @return direct ByteBuffer，在 releaseFrame 前有效；无帧时返回 null
     */
    public static native ByteBuffer nativeAcquireFrame(long handle, long[] outMeta);

    public static native void nativeReleaseFrame(long handle);

    public static native long nativeFramesDropped(long handle);

    // ── 音频 ──────────────────────────────────────────────────

    /**
     * 从音频环读取 PCM 帧。
     *
     * @param buffer   direct ByteBuffer，容量 ≥ maxFrames × 4（立体声 int16）
     * @param maxFrames 最多读取的帧数
     * @return 实际读取的帧数
     */
    public static native int nativeReadAudio(long handle, ByteBuffer buffer, int maxFrames);

    public static native int nativeAudioQueued(long handle);

    public static native long nativeAudioUnderruns(long handle);

    public static native long nativeAudioOverflows(long handle);

    public static native void nativeResetAudioStats(long handle);

    /**
     * 获取 AV 信息。
     *
     * @param outInfo 长度 ≥ 2 的数组，填充 [fps, sampleRate]
     */
    public static native void nativeGetAvInfo(long handle, double[] outInfo);

    // ── 输入 ──────────────────────────────────────────────────

    /**
     * @param buttonId Libretro RETRO_DEVICE_ID_JOYPAD_* (0=B,1=Y,2=SELECT,3=START,
     *                 4=UP,5=DOWN,6=LEFT,7=RIGHT,8=A,9=X,10=L,11=R)
     * @param sourceId 输入源: 0=VIRTUAL, 1=KEYBOARD, 2=GAMEPAD
     */
    public static native void nativeInputPress(long handle, int buttonId, int sourceId);

    public static native void nativeInputRelease(long handle, int buttonId, int sourceId);

    /** 释放某个输入源的全部按键。 */
    public static native void nativeInputReleaseSource(long handle, int sourceId);

    public static native void nativeInputReset(long handle);

    // ── 金手指 ────────────────────────────────────────────────

    public static native void nativeCheatReset(long handle);

    /**
     * @param code Libretro 格式金手指代码
     * @return true=设置成功
     */
    public static native boolean nativeCheatSet(long handle, int index, boolean enabled, String code);

    // ── SRAM / RTC ────────────────────────────────────────────

    public static native int nativeSramSize(long handle);

    public static native boolean nativeSaveSram(long handle, ByteBuffer buffer);

    public static native boolean nativeLoadSram(long handle, ByteBuffer buffer, int size);

    public static native int nativeRtcSize(long handle);

    public static native boolean nativeSaveRtc(long handle, ByteBuffer buffer);

    public static native boolean nativeLoadRtc(long handle, ByteBuffer buffer, int size);

    // ── 即时存档 ──────────────────────────────────────────────

    public static native long nativeSerializeSize(long handle);

    public static native boolean nativeSerialize(long handle, ByteBuffer buffer);

    public static native boolean nativeUnserialize(long handle, ByteBuffer buffer, int size);

    // ── 核心选项 ──────────────────────────────────────────────

    public static native int nativeOptionCount(long handle);

    public static native String nativeOptionKey(long handle, int index);

    /** @return 以 '|' 分隔的可选值列表 */
    public static native String nativeOptionValues(long handle, int index);

    public static native int nativeOptionCurrent(long handle, int index);

    public static native boolean nativeSetOption(long handle, int index, int valueIndex);
}
