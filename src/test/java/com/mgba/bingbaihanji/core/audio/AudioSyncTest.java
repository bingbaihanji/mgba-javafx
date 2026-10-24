package com.mgba.bingbaihanji.core.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 音频同步的核心行为：按产出/播放速率差微调，且修正量有上限、无设备时不动。
 */
class AudioSyncTest {

    @Test
    void slowsDownWhenEmulatorOutpacesPlayback() {
        AudioSync sync = new AudioSync();
        sync.sample(1_000_000, 1_000_000); // 建立基准

        double correction = 1.0;
        for (int i = 1; i <= 5; i++) {
            // 产出 65536 帧，声卡只播放 65000 帧：模拟器更快，需要减速
            correction = sync.sample(1_000_000 + i * 65536L, 1_000_000 + i * 65000L);
        }
        assertTrue(correction < 1.0, "模拟器更快时应降低速度");
        assertTrue(correction >= 0.99 - 1e-9, "修正量必须限制在 ±1%");
    }

    @Test
    void speedsUpWhenPlaybackOutpacesEmulator() {
        AudioSync sync = new AudioSync();
        sync.sample(1_000_000, 1_000_000);

        double correction = 1.0;
        for (int i = 1; i <= 5; i++) {
            correction = sync.sample(1_000_000 + i * 65536L, 1_000_000 + i * 66000L);
        }
        assertTrue(correction > 1.0, "声卡更快时应提高速度");
    }

    @Test
    void staysNeutralWithoutAudioDevice() {
        AudioSync sync = new AudioSync();
        for (int i = 0; i < 10; i++) {
            sync.sample(100 * i, -1);
        }
        assertEquals(1.0, sync.correction(), 1e-9);
    }
}
