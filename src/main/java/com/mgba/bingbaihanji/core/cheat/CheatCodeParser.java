package com.mgba.bingbaihanji.core.cheat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 金手指代码解析（My Boy 兼容格式 → mGBA libretro 格式）。
 *
 * <h2>输入</h2>
 * <ul>
 *   <li>GameShark / Action Replay：16 位十六进制，如 {@code 820051A0 00000063}</li>
 *   <li>CodeBreaker：12 位十六进制，如 {@code 820051A0 0063}</li>
 *   <li>Raw / VBA：{@code 820051A0:63}、{@code :0063}、{@code :00000063}</li>
 * </ul>
 * 分隔符接受空格、{@code +}、{@code -} 与可选 {@code 0x} 前缀；支持多行，
 * 同一金手指的多行必须属于同一类型。
 *
 * <h2>mGBA libretro 的坑</h2>
 * <p>mGBA 的 {@code retro_cheat_set} 把代码按 <b>13 或 17 个字符</b>切行：
 * 只有恰好落在 13/17 位置的行才会被接受，其余长度<b>静默丢弃</b>。
 * 因此 1 字节 Raw 代码（11 字符）必须补空格到 13；
 * 多行之间用 {@code \n} 连接——换行符会被它当成第 13/17 位的分隔符。
 */
public final class CheatCodeParser {

    /** 合法十六进制串。 */
    private static final Pattern HEX = Pattern.compile("[0-9A-Fa-f]+");

    /** 地址位数。 */
    private static final int ADDRESS_DIGITS = 8;

    /** libretro 短行（CodeBreaker / Raw）长度。 */
    private static final int LIBRETRO_SHORT_LINE = 13;

    private CheatCodeParser() {
    }

    /**
     * 解析一段用户输入的金手指代码。
     *
     * @param input 用户输入（可多行）
     * @return 解析结果
     * @throws CheatFormatException 空输入、非法字符、位数不受支持或类型混用
     */
    public static ParsedCheat parse(String input) throws CheatFormatException {
        if (input == null) {
            throw new CheatFormatException("金手指代码为空");
        }

        List<String> lines = new ArrayList<>();
        for (String raw : input.split("\\R")) {
            String line = raw.trim();
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            throw new CheatFormatException("金手指代码为空");
        }

        CheatType type = null;
        List<String> display = new ArrayList<>(lines.size());
        List<String> libretro = new ArrayList<>(lines.size());

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            ParsedCheat parsed = parseLine(line, i + 1);
            if (type == null) {
                type = parsed.type();
            } else if (type != parsed.type()) {
                throw new CheatFormatException(
                        "第 " + (i + 1) + " 行的类型（" + parsed.type().label()
                                + "）与前面的 " + type.label() + " 不一致");
            }
            display.add(parsed.displayCode());
            libretro.add(parsed.libretroCode());
        }

        return new ParsedCheat(type, String.join("\n", display), String.join("\n", libretro));
    }

    // ── 内部 ──────────────────────────────────────────────────

    private static ParsedCheat parseLine(String line, int lineNumber) throws CheatFormatException {
        if (line.indexOf(':') >= 0) {
            return parseRaw(line, lineNumber);
        }
        return parseCompact(line, lineNumber);
    }

    /** 解析 {@code AAAAAAAA:VV} 形式（冒号形式一律按 Raw 处理）。 */
    private static ParsedCheat parseRaw(String line, int lineNumber) throws CheatFormatException {
        int colon = line.indexOf(':');
        if (line.indexOf(':', colon + 1) >= 0) {
            throw new CheatFormatException("第 " + lineNumber + " 行有多个冒号");
        }

        String addressText = cleanHex(line.substring(0, colon), lineNumber);
        String valueText = cleanHex(line.substring(colon + 1), lineNumber);

        if (addressText.isEmpty() || addressText.length() > ADDRESS_DIGITS) {
            throw new CheatFormatException(
                    "第 " + lineNumber + " 行地址必须是 1–8 位十六进制: " + addressText);
        }
        int valueWidth = valueText.length();
        if (valueWidth != 2 && valueWidth != 4 && valueWidth != 8) {
            throw new CheatFormatException(
                    "第 " + lineNumber + " 行数值必须是 2、4 或 8 位十六进制: " + valueText);
        }

        String address = padLeft(addressText, ADDRESS_DIGITS);
        String value = valueText.toUpperCase(Locale.ROOT);
        String canonical = address + ":" + value;
        // 1 字节 Raw 只有 11 字符，mGBA 会整行丢弃，补空格到 13
        String libretro = canonical.length() < LIBRETRO_SHORT_LINE
                ? padRight(canonical, LIBRETRO_SHORT_LINE)
                : canonical;
        return new ParsedCheat(CheatType.RAW, canonical, libretro);
    }

    /** 解析无冒号的紧凑形式：16 位 → GameShark，12 位 → CodeBreaker，10 位 → 1 字节 Raw。 */
    private static ParsedCheat parseCompact(String line, int lineNumber) throws CheatFormatException {
        String hex = cleanHex(line, lineNumber);
        switch (hex.length()) {
            case 16 -> {
                String address = hex.substring(0, 8).toUpperCase(Locale.ROOT);
                String value = hex.substring(8).toUpperCase(Locale.ROOT);
                String canonical = address + " " + value;
                return new ParsedCheat(CheatType.GAME_SHARK, canonical, canonical);
            }
            case 12 -> {
                String address = hex.substring(0, 8).toUpperCase(Locale.ROOT);
                String value = hex.substring(8).toUpperCase(Locale.ROOT);
                String canonical = address + " " + value;
                return new ParsedCheat(CheatType.CODE_BREAKER, canonical, canonical);
            }
            case 10 -> {
                String address = hex.substring(0, 8).toUpperCase(Locale.ROOT);
                String value = hex.substring(8).toUpperCase(Locale.ROOT);
                String canonical = address + ":" + value;
                return new ParsedCheat(CheatType.RAW, canonical,
                        padRight(canonical, LIBRETRO_SHORT_LINE));
            }
            default -> throw new CheatFormatException(
                    "第 " + lineNumber + " 行十六进制位数为 " + hex.length()
                            + "，只支持 10（Raw）、12（CodeBreaker）、16（GameShark）位");
        }
    }

    /**
     * 去掉 {@code 0x} 前缀与空白 / {@code +} / {@code -} 分隔符，并校验剩余字符全是十六进制。
     */
    private static String cleanHex(String text, int lineNumber) throws CheatFormatException {
        String cleaned = text.replaceAll("(?i)0x", "")
                .replaceAll("[\\s+\\-]", "");
        if (cleaned.isEmpty() || !HEX.matcher(cleaned).matches()) {
            throw new CheatFormatException(
                    "第 " + lineNumber + " 行包含非十六进制字符: " + text);
        }
        return cleaned;
    }

    private static String padLeft(String text, int width) {
        StringBuilder sb = new StringBuilder(text.toUpperCase(Locale.ROOT));
        while (sb.length() < width) {
            sb.insert(0, '0');
        }
        return sb.toString();
    }

    private static String padRight(String text, int width) {
        StringBuilder sb = new StringBuilder(text);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
