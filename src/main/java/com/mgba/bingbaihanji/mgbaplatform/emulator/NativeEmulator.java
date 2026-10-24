package com.mgba.bingbaihanji.mgbaplatform.emulator;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * 原始 JNI 入口，对应 native/src/jni_bridge.c 的导出符号。
 *
 * <p><b>包名不可修改</b>——C 侧符号为
 * {@code Java_com_mgba_bingbaihanji_mgbaplatform_emulator_NativeEmulator_*}，
 * 移动包将导致 JNI 链接失败。
 *
 * <p>业务代码应通过 {@link JniEmulatorCore} 调用，不要直接使用本类。
 *
 * <p>所有方法的 {@code handle} 参数均为 {@link #nativeCreate()} 返回的会话句柄。
 * 同一时刻仅允许一个活跃会话。
 */
public final class NativeEmulator {

    static {
        loadLibrary();
    }

    private NativeEmulator() {
    }

    /** 加载 mgba_frontend.dll：优先从已知相对路径加载，找不到再走 java.library.path。 */
    private static void loadLibrary() {
        String[] candidates = {
                "build/native/windows-x64/dist/mgba_frontend.dll",
                "native/dist/mgba_frontend.dll"
        };
        for (String path : candidates) {
            File f = new File(path);
            if (f.exists()) {
                System.load(f.getAbsolutePath());
                return;
            }
        }
        System.loadLibrary("mgba_frontend");
    }

    // ════════════════════════════════════════════════════════════
    //  生命周期
    // ════════════════════════════════════════════════════════════

    /** 创建会话，返回句柄（非零）。 */
    public static native long nativeCreate();

    /** 销毁会话，释放全部资源。句柄失效后不可再调用其他方法。 */
    public static native void nativeDestroy(long handle);

    /** 加载 Libretro 核心库，返回 0=成功，非 0 为 MgbaResult 错误码。 */
    public static native int nativeInitialize(long handle, String coreLibraryPath, String systemDirectory);

    /** 加载 ROM 文件（绝对路径），返回 0=成功。 */
    public static native int nativeLoadGame(long handle, String romPath);

    /** 执行一帧 retro_run()，返回 0=成功。 */
    public static native int nativeRunOnce(long handle);

    /** 卸载当前游戏（retro_unload_game + retro_deinit），返回 0=成功。 */
    public static native int nativeUnloadGame(long handle);

    /** 关闭会话（卸载游戏 + 释放核心库），返回 0=成功。 */
    public static native int nativeShutdown(long handle);

    /** 最近一次错误的描述文本，无错误时返回 null。 */
    public static native String nativeLastError(long handle);

    /** 核心名称（如 "mGBA"），未初始化时返回 null。 */
    public static native String nativeCoreName(long handle);

    /** 核心版本号，未初始化时返回 null。 */
    public static native String nativeCoreVersion(long handle);

    /** 当前状态码：0=UNINITIALIZED, 1=INITIALIZED, 2=GAME_LOADED, 3=RUNNING, 4=ERROR。 */
    public static native int nativeState(long handle);

    // ════════════════════════════════════════════════════════════
    //  视频
    // ════════════════════════════════════════════════════════════

    /** 像素格式码：0=0RGB1555, 1=XRGB8888, 2=RGB565（mGBA 默认）。 */
    public static native int nativePixelFormat(long handle);

    /**
     * 获取最新一帧的 direct ByteBuffer，无帧时返回 null。
     * 在 {@link #nativeReleaseFrame} 调用前有效。
     *
     * @param outMeta 长度 ≥ 4，填充 [width, height, format, sequence]
     */
    public static native ByteBuffer nativeAcquireFrame(long handle, long[] outMeta);

    /** 释放 acquireFrame 获取的帧缓冲。 */
    public static native void nativeReleaseFrame(long handle);

    /** 累计丢帧数。 */
    public static native long nativeFramesDropped(long handle);

    /** 累计视频帧数。 */
    public static native long nativeFrameCount(long handle);

    // ════════════════════════════════════════════════════════════
    //  音频
    // ════════════════════════════════════════════════════════════

    /**
     * 从音频环读取 PCM 帧（立体声 16-bit LE）。
     *
     * @param buffer   direct ByteBuffer，容量 ≥ maxFrames × 4
     * @param maxFrames 最多读取的帧数
     * @return 实际读取的帧数
     */
    public static native int nativeReadAudio(long handle, ByteBuffer buffer, int maxFrames);

    /** 音频环中待播放的帧数。 */
    public static native int nativeAudioQueued(long handle);

    /** 累计音频欠载次数（消费端空读）。 */
    public static native long nativeAudioUnderruns(long handle);

    /** 累计音频溢出次数（生产端丢弃）。 */
    public static native long nativeAudioOverflows(long handle);

    /** 重置音频统计计数器。 */
    public static native void nativeResetAudioStats(long handle);

    /**
     * 获取 AV 信息。
     *
     * @param outInfo 长度 ≥ 2，填充 [fps, sampleRate]
     */
    public static native void nativeGetAvInfo(long handle, double[] outInfo);

    /** 累计音频帧数。 */
    public static native long nativeAudioFrameCount(long handle);

    // ════════════════════════════════════════════════════════════
    //  输入
    // ════════════════════════════════════════════════════════════

    /**
     * 按下按键。
     *
     * @param buttonId Libretro RETRO_DEVICE_ID_JOYPAD_* (0=B, 1=Y, 2=SELECT, 3=START,
     *                 4=UP, 5=DOWN, 6=LEFT, 7=RIGHT, 8=A, 9=X, 10=L, 11=R)
     * @param sourceId 输入源：0=VIRTUAL, 1=KEYBOARD, 2=GAMEPAD
     */
    public static native void nativeInputPress(long handle, int buttonId, int sourceId);

    /** 释放按键（指定输入源）。 */
    public static native void nativeInputRelease(long handle, int buttonId, int sourceId);

    /** 释放某个输入源的全部按键（焦点丢失时调用）。 */
    public static native void nativeInputReleaseSource(long handle, int sourceId);

    /** 释放全部输入源的全部按键。 */
    public static native void nativeInputReset(long handle);

    // ════════════════════════════════════════════════════════════
    //  金手指
    // ════════════════════════════════════════════════════════════

    /** 清除全部金手指设置。 */
    public static native void nativeCheatReset(long handle);

    /** 设置一条金手指代码，返回 true=成功。code 为 Libretro 格式。 */
    public static native boolean nativeCheatSet(long handle, int index, boolean enabled, String code);

    // ════════════════════════════════════════════════════════════
    //  SRAM / RTC
    // ════════════════════════════════════════════════════════════

    /** SRAM 字节数，无 SRAM 时返回 0。 */
    public static native int nativeSramSize(long handle);

    /** 将 SRAM 写入 buffer（调用前需按 sramSize 分配），返回 true=成功。 */
    public static native boolean nativeSaveSram(long handle, ByteBuffer buffer);

    /** 从 buffer 加载 SRAM 到核心，返回 true=成功。 */
    public static native boolean nativeLoadSram(long handle, ByteBuffer buffer, int size);

    /** RTC 字节数。 */
    public static native int nativeRtcSize(long handle);

    /** 将 RTC 数据写入 buffer，返回 true=成功。 */
    public static native boolean nativeSaveRtc(long handle, ByteBuffer buffer);

    /** 从 buffer 加载 RTC 数据到核心，返回 true=成功。 */
    public static native boolean nativeLoadRtc(long handle, ByteBuffer buffer, int size);

    // ════════════════════════════════════════════════════════════
    //  即时存档
    // ════════════════════════════════════════════════════════════

    /** 序列化状态的字节数。 */
    public static native long nativeSerializeSize(long handle);

    /** 将当前状态序列化到 buffer，返回 true=成功。 */
    public static native boolean nativeSerialize(long handle, ByteBuffer buffer);

    /** 从 buffer 反序列化状态，返回 true=成功。 */
    public static native boolean nativeUnserialize(long handle, ByteBuffer buffer, int size);

    // ════════════════════════════════════════════════════════════
    //  核心选项
    // ════════════════════════════════════════════════════════════

    /** 核心选项数量。 */
    public static native int nativeOptionCount(long handle);

    /** 指定选项的键名，无效索引返回 null。 */
    public static native String nativeOptionKey(long handle, int index);

    /** 指定选项的可选值列表（'|' 分隔），无效索引返回 null。 */
    public static native String nativeOptionValues(long handle, int index);

    /** 指定选项当前选中的值索引。 */
    public static native int nativeOptionCurrent(long handle, int index);

    /** 设置核心选项，返回 true=成功。 */
    public static native boolean nativeSetOption(long handle, int index, int valueIndex);
}
