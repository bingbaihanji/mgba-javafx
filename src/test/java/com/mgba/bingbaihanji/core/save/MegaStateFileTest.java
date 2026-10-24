package com.mgba.bingbaihanji.core.save;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * mGBA 状态封装：named state 的拆分与 PNG 往返。
 */
class MegaStateFileTest {

    @Test
    void splitsNamedStateAndRoundTripsPng() throws IOException {
        byte[] coreState = new byte[100];
        for (int i = 0; i < coreState.length; i++) {
            coreState[i] = (byte) i;
        }
        byte[] savedata = {1, 2, 3, 4, 5, 6, 7, 8};
        byte[] rtc = {9, 10, 11, 12};

        // 2 个扩展数据项 + 1 个终止项，每项 16 字节
        int tableSize = 16 * 3;
        int firstItem = coreState.length + tableSize;
        ByteBuffer named = ByteBuffer.allocate(firstItem + savedata.length + rtc.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        named.put(coreState);
        putEntry(named, MegaStateFile.EXTDATA_SAVEDATA, savedata.length, firstItem);
        putEntry(named, MegaStateFile.EXTDATA_RTC, rtc.length, firstItem + savedata.length);
        putEntry(named, 0, 0, 0);
        named.put(savedata).put(rtc);

        MegaStateFile.Payload payload = MegaStateFile.splitNamedState(named.array(), savedata.length);

        assertArrayEquals(coreState, payload.coreState());
        assertArrayEquals(savedata, payload.extdata().get(MegaStateFile.EXTDATA_SAVEDATA));
        assertArrayEquals(rtc, payload.extdata().get(MegaStateFile.EXTDATA_RTC));

        BufferedImage screenshot = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        byte[] png = MegaStateFile.writePng(payload, screenshot);
        MegaStateFile.Payload back = MegaStateFile.readPng(png);

        assertArrayEquals(coreState, back.coreState());
        assertArrayEquals(savedata, back.extdata().get(MegaStateFile.EXTDATA_SAVEDATA));
        assertArrayEquals(rtc, back.extdata().get(MegaStateFile.EXTDATA_RTC));
        assertEquals(coreState.length, back.coreState().length);

        // ROM 短 ID 写在 tEXt 块里，读回来要一致
        MegaStateFile.Payload tagged = new MegaStateFile.Payload(
                payload.coreState(), payload.extdata(), "0123456789abcdef");
        byte[] taggedPng = MegaStateFile.writePng(tagged, screenshot);
        assertEquals("0123456789abcdef", MegaStateFile.readPng(taggedPng).romId());
    }

    private static void putEntry(ByteBuffer buffer, int tag, int size, long offset) {
        buffer.putInt(tag);
        buffer.putInt(size);
        buffer.putLong(offset);
    }
}
