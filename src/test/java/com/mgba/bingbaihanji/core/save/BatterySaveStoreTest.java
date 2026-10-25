package com.mgba.bingbaihanji.core.save;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 电池存档大小识别：核心自动检测阶段报告 128 KB 时，仍应接受 32 KB 等常见存档。
 *
 * <p>回归背景：Fire Emblem 等 SRAM 游戏的原版 mGBA 存档是 32 KB，
 * 如果按自动检测阶段的 128 KB 做长度校验，存档会被跳过并被空白数据覆盖。
 */
class BatterySaveStoreTest {

    @Test
    void acceptsSmallerKnownSaveWhenCoreReportsAutodetectMaximum() {
        assertTrue(BatterySaveStore.isLoadableSize(32768, 131072));
        assertTrue(BatterySaveStore.isLoadableSize(32784, 131072));
        assertTrue(BatterySaveStore.isLoadableSize(65536, 131072));
        assertTrue(BatterySaveStore.isLoadableSize(131088, 131072));
        assertTrue(BatterySaveStore.isLoadableSize(8192, 131072));
        assertTrue(BatterySaveStore.isLoadableSize(512, 131072));
    }

    @Test
    void rejectsUnknownOrOversizedFiles() {
        assertFalse(BatterySaveStore.isLoadableSize(12345, 131072));
        assertFalse(BatterySaveStore.isLoadableSize(131072, 32768));
        assertFalse(BatterySaveStore.isLoadableSize(0, 131072));
    }

    @Test
    void payloadDropsRtcFooter() {
        assertEquals(32768, BatterySaveStore.payloadSize(32768, 131072));
        assertEquals(32768, BatterySaveStore.payloadSize(32784, 131072));
        assertEquals(131072, BatterySaveStore.payloadSize(131088, 131072));
        assertEquals(-1, BatterySaveStore.payloadSize(12345, 131072));
    }
}
