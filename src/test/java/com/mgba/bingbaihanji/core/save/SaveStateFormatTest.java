package com.mgba.bingbaihanji.core.save;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MGST 容器格式的核心校验：能原样往返，且能挡住损坏/版本不符的输入。
 */
class SaveStateFormatTest {

    @Test
    void roundTripPreservesAllFields() throws Exception {
        byte[] state = new byte[]{1, 2, 3, 4, 5};
        byte[] raw = SaveStateFormat.encode("mGBA", "0.10.3", "0123456789abcdef",
                1_700_000_000_000L, state);

        SaveStateFile file = SaveStateFormat.decode(raw);

        assertEquals(SaveStateFormat.FORMAT_VERSION, file.formatVersion());
        assertEquals(1_700_000_000_000L, file.timestamp());
        assertEquals("mGBA", file.coreName());
        assertEquals("0.10.3", file.coreVersion());
        assertEquals("0123456789abcdef", file.romId());
        assertArrayEquals(state, file.data());
    }

    @Test
    void rejectsForeignMagic() {
        SaveStateException error = assertThrows(SaveStateException.class,
                () -> SaveStateFormat.decode("XXXXjunk".getBytes()));
        assertEquals(SaveError.CORRUPTED, error.reason());
    }

    @Test
    void rejectsTruncatedPayload() {
        byte[] raw = SaveStateFormat.encode("mGBA", "0.10.3", "0123456789abcdef", 1L,
                new byte[]{9, 9, 9, 9});
        byte[] truncated = Arrays.copyOf(raw, raw.length - 2);

        SaveStateException error = assertThrows(SaveStateException.class,
                () -> SaveStateFormat.decode(truncated));
        assertEquals(SaveError.CORRUPTED, error.reason());
    }
}
