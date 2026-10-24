package com.mgba.bingbaihanji.mgbaplatform.emulator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JNI 桥冒烟测试：验证 DLL 加载与 session 生命周期。
 */
class NativeEmulatorSmokeTest {

    @Test
    void 创建和销毁session() {
        long handle = NativeEmulator.nativeCreate();
        assertNotEquals(0, handle, "nativeCreate 应返回非零句柄");
        assertEquals(0, NativeEmulator.nativeState(handle), "新 session 状态应为 UNINITIALIZED");
        NativeEmulator.nativeDestroy(handle);
    }
}
