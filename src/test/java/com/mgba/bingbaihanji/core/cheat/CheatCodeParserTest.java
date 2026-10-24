package com.mgba.bingbaihanji.core.cheat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 金手指解析的核心规则：类型识别、规范形式，以及 mGBA 要求的 13/17 字符行。
 */
class CheatCodeParserTest {

    @Test
    void recognizesAllThreeTypes() throws Exception {
        ParsedCheat gameShark = CheatCodeParser.parse("820051A0+00000063");
        assertEquals(CheatType.GAME_SHARK, gameShark.type());
        assertEquals("820051A0 00000063", gameShark.libretroCode());

        ParsedCheat codeBreaker = CheatCodeParser.parse("0x820051A0 0x0063");
        assertEquals(CheatType.CODE_BREAKER, codeBreaker.type());
        assertEquals("820051A0 0063", codeBreaker.libretroCode());

        ParsedCheat raw = CheatCodeParser.parse("820051A0:63");
        assertEquals(CheatType.RAW, raw.type());
        assertEquals("820051A0:63", raw.displayCode());
        // 1 字节 Raw 只有 11 字符，必须补到 13 才不会被 mGBA 丢弃
        assertEquals(13, raw.libretroCode().length());
    }

    @Test
    void rejectsUnsupportedLengthAndMixedTypes() {
        assertThrows(CheatFormatException.class,
                () -> CheatCodeParser.parse("820051A0 0000 63"));
        assertThrows(CheatFormatException.class,
                () -> CheatCodeParser.parse("820051A0 0063\n820051A0 00000063"));
    }
}
