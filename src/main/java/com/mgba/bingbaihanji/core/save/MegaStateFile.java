package com.mgba.bingbaihanji.core.save;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * mGBA 原版即时存档格式（PNG + 自定义块）。
 *
 * <h2>格式</h2>
 * <ul>
 *   <li>文件本身是一张 PNG（默认 240×160），带两个自定义块：</li>
 *   <li>{@code gbAs}：ze64 压缩的核心状态（长度 = 核心的 {@code stateSize}）；</li>
 *   <li>{@code gbAx}：每个扩展数据一项，内容为 {@code [tag LE32][size LE32][zlib 数据]}，
 *       常见 tag：2 = 电池存档、4 = RTC。</li>
 * </ul>
 *
 * <h2>为什么需要拆分</h2>
 * <p>libretro 的 {@code retro_serialize} 输出的是 mGBA 的 <b>named state</b>：
 * {@code [核心状态][扩展数据表][扩展数据…]}。而 PNG 里的 {@code gbAs} 只放
 * <b>核心状态</b>，两者不能直接互换——把完整的 named state 塞进 gbAs，
 * mGBA 解压时会因为缓冲区不够而失败。
 *
 * <p>{@link #splitNamedState} 负责按扩展数据表把两者拆开；
 * 表项的偏移量是绝对位置，因此扫描时必须校验整条链与文件总长。
 */
public final class MegaStateFile {

    /** PNG 文件签名。 */
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'
    };

    /** 扩展数据标签上限（与 mGBA 的 EXTDATA_MAX 一致）。 */
    private static final int EXTDATA_MAX = 0x103;

    /** 电池存档标签。 */
    public static final int EXTDATA_SAVEDATA = 2;

    /** RTC 标签。 */
    public static final int EXTDATA_RTC = 4;

    private MegaStateFile() {
    }

    /**
     * 拆分后的状态。
     *
     * @param coreState 核心状态（对应 PNG 的 gbAs）
     * @param extdata   扩展数据（tag → 原始数据）
     * @param romId     写入时记录的 ROM 短 ID（读取旧文件时可能为 null）
     */
    public record Payload(byte[] coreState, Map<Integer, byte[]> extdata, String romId) {
    }

    /** 我们在 PNG tEXt 块里记录 ROM 短 ID 用的关键字。 */
    private static final String ROM_ID_KEYWORD = "mGBA-romId";

    /** 文件是否是 PNG（mGBA 的即时存档）。 */
    public static boolean isPng(byte[] data) {
        if (data.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (data[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    // ── 读取 ──────────────────────────────────────────────────

    /**
     * 读取 mGBA PNG 存档。
     *
     * @param png PNG 文件字节
     * @return 核心状态与扩展数据
     * @throws IOException 不是 PNG、缺少 gbAs 或解压失败
     */
    public static Payload readPng(byte[] png) throws IOException {
        if (!isPng(png)) {
            throw new IOException("不是 PNG 存档");
        }
        byte[] coreState = null;
        String romId = null;
        Map<Integer, byte[]> extdata = new LinkedHashMap<>();

        int position = PNG_SIGNATURE.length;
        while (position + 12 <= png.length) {
            int length = readIntBE(png, position);
            String type = new String(png, position + 4, 4, StandardCharsets.ISO_8859_1);
            int dataStart = position + 8;
            if (length < 0 || dataStart + length + 4 > png.length) {
                throw new IOException("PNG 块长度非法: " + type);
            }
            switch (type) {
                case "gbAs" -> coreState = inflate(png, dataStart, length);
                case "gbAx" -> readExtdataChunk(png, dataStart, length, extdata);
                case "tEXt" -> romId = readRomIdText(png, dataStart, length, romId);
                default -> {
                    // IHDR/IDAT/IEND 等标准块与其它扩展都忽略
                }
            }
            position = dataStart + length + 4;
            if ("IEND".equals(type)) {
                break;
            }
        }

        if (coreState == null) {
            throw new IOException("PNG 存档里没有 gbAs 块");
        }
        return new Payload(coreState, extdata, romId);
    }

    /** 从 tEXt 块里读 ROM 短 ID；不是我们的关键字时返回原值。 */
    private static String readRomIdText(byte[] png, int start, int length, String current) {
        int nul = -1;
        for (int i = 0; i < length; i++) {
            if (png[start + i] == 0) {
                nul = i;
                break;
            }
        }
        if (nul <= 0) {
            return current;
        }
        String keyword = new String(png, start, nul, StandardCharsets.ISO_8859_1);
        if (!ROM_ID_KEYWORD.equals(keyword)) {
            return current;
        }
        return new String(png, start + nul + 1, length - nul - 1, StandardCharsets.ISO_8859_1);
    }

    /**
     * 拆分 libretro 输出的 named state。
     *
     * @param named                 named state 字节
     * @param expectedSavedataSize  核心声明的电池存档大小（用于校验，0 表示没有）
     * @return 核心状态与扩展数据
     * @throws IOException 找不到合法的扩展数据表
     */
    public static Payload splitNamedState(byte[] named, int expectedSavedataSize) throws IOException {
        int tableStart = findTableStart(named, expectedSavedataSize);
        if (tableStart < 0) {
            throw new IOException("无法识别 mGBA 状态结构");
        }

        Map<Integer, byte[]> extdata = new LinkedHashMap<>();
        int offset = tableStart;
        while (offset + 16 <= named.length) {
            int tag = readIntLE(named, offset);
            int size = readIntLE(named, offset + 4);
            long itemOffset = readLongLE(named, offset + 8);
            offset += 16;
            if (tag == 0) {
                break;
            }
            if (itemOffset < 0 || itemOffset + size > named.length) {
                throw new IOException("扩展数据越界: tag=" + tag);
            }
            byte[] item = new byte[size];
            System.arraycopy(named, (int) itemOffset, item, 0, size);
            extdata.put(tag, item);
        }

        byte[] coreState = new byte[tableStart];
        System.arraycopy(named, 0, coreState, 0, tableStart);
        return new Payload(coreState, extdata, null);
    }

    private static void readExtdataChunk(byte[] png, int start, int length,
                                         Map<Integer, byte[]> target) throws IOException {
        if (length < 8) {
            return;
        }
        int tag = readIntLE(png, start);
        int size = readIntLE(png, start + 4);
        if (tag <= 0 || tag >= EXTDATA_MAX || size < 0) {
            return;
        }
        byte[] data = inflate(png, start + 8, length - 8);
        if (data.length != size) {
            // 大小对不上说明数据被截断：宁可不加载，也别把坏数据交给核心
            throw new IOException("扩展数据大小不匹配: tag=" + tag);
        }
        target.put(tag, data);
    }

    /** 扫描 named state，找扩展数据表的起点（= 核心状态长度）。 */
    private static int findTableStart(byte[] named, int expectedSavedataSize) {
        int total = named.length;
        for (int start = 0; start + 16 <= total; start++) {
            if (validTable(named, start, total, expectedSavedataSize)) {
                return start;
            }
        }
        return -1;
    }

    /** 校验表结构：项数、第一条的绝对偏移、链式偏移、最后一项结束于文件末尾。 */
    private static boolean validTable(byte[] named, int start, int total, int expectedSavedataSize) {
        int offset = start;
        int entries = 0;
        long expectedOffset = -1;

        while (offset + 16 <= total && entries <= 8) {
            int tag = readIntLE(named, offset);
            int size = readIntLE(named, offset + 4);
            long itemOffset = readLongLE(named, offset + 8);
            offset += 16;

            if (tag == 0) {
                // 终止项：至少一项，且最后一项正好结束于文件末尾
                return entries > 0 && expectedOffset == total;
            }
            if (tag < 1 || tag >= EXTDATA_MAX || size <= 0 || itemOffset < 0) {
                return false;
            }
            if (entries == 0) {
                // 第一项必须紧跟在「所有表项 + 终止项」之后
                long tableEnd = (long) start + 16L * (countTableEntries(named, start, total) + 1);
                if (itemOffset != tableEnd) {
                    return false;
                }
            } else if (itemOffset != expectedOffset) {
                return false;
            }
            if (itemOffset + size > total) {
                return false;
            }
            if (tag == EXTDATA_SAVEDATA && expectedSavedataSize > 0 && size != expectedSavedataSize) {
                return false;
            }
            expectedOffset = itemOffset + size;
            ++entries;
        }
        return false;
    }

    private static int countTableEntries(byte[] named, int start, int total) {
        int offset = start;
        int entries = 0;
        while (offset + 16 <= total && entries <= 8) {
            int tag = readIntLE(named, offset);
            offset += 16;
            if (tag == 0) {
                return entries;
            }
            ++entries;
        }
        return -1;
    }

    // ── 写入 ──────────────────────────────────────────────────

    /**
     * 生成 mGBA PNG 存档。
     *
     * @param payload    核心状态与扩展数据
     * @param screenshot 预览图（通常为当前帧）；为 null 时写一张纯黑图
     * @return PNG 文件字节
     * @throws IOException 编码失败
     */
    public static byte[] writePng(Payload payload, BufferedImage screenshot) throws IOException {
        int width = screenshot != null ? screenshot.getWidth() : 240;
        int height = screenshot != null ? screenshot.getHeight() : 160;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(PNG_SIGNATURE);

        byte[] ihdr = new byte[13];
        writeIntBE(ihdr, 0, width);
        writeIntBE(ihdr, 4, height);
        ihdr[8] = 8;   // bit depth
        ihdr[9] = 2;   // color type: RGB
        ihdr[10] = 0;  // compression
        ihdr[11] = 0;  // filter
        ihdr[12] = 0;  // interlace
        writeChunk(out, "IHDR", ihdr);

        writeChunk(out, "IDAT", encodeImage(screenshot, width, height));

        // 记录 ROM 短 ID：mGBA 会忽略这个 tEXt 块，我们自己读档时用来校验
        if (payload.romId() != null && !payload.romId().isBlank()) {
            byte[] keyword = ROM_ID_KEYWORD.getBytes(StandardCharsets.ISO_8859_1);
            byte[] value = payload.romId().getBytes(StandardCharsets.ISO_8859_1);
            byte[] text = new byte[keyword.length + 1 + value.length];
            System.arraycopy(keyword, 0, text, 0, keyword.length);
            text[keyword.length] = 0;
            System.arraycopy(value, 0, text, keyword.length + 1, value.length);
            writeChunk(out, "tEXt", text);
        }

        writeChunk(out, "gbAs", deflate(payload.coreState()));

        for (Map.Entry<Integer, byte[]> entry : payload.extdata().entrySet()) {
            int tag = entry.getKey();
            if (tag <= 0 || tag >= EXTDATA_MAX) {
                continue;
            }
            byte[] item = entry.getValue();
            byte[] compressed = deflate(item);
            byte[] chunk = new byte[8 + compressed.length];
            writeIntLE(chunk, 0, tag);
            writeIntLE(chunk, 4, item.length);
            System.arraycopy(compressed, 0, chunk, 8, compressed.length);
            writeChunk(out, "gbAx", chunk);
        }

        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** 把截图编码成 PNG 的 RGB 扫描行数据（每行前缀一个 filter=0 字节）。 */
    private static byte[] encodeImage(BufferedImage image, int width, int height) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(height * (width * 3 + 1));
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            raw.write(0);
            if (image != null) {
                image.getRGB(0, y, width, 1, row, 0, width);
            }
            for (int x = 0; x < width; x++) {
                int rgb = image != null ? row[x] : 0;
                raw.write((rgb >> 16) & 0xFF);
                raw.write((rgb >> 8) & 0xFF);
                raw.write(rgb & 0xFF);
            }
        }
        return deflate(raw.toByteArray());
    }

    // ── 工具 ──────────────────────────────────────────────────

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data)
            throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
        byte[] length = new byte[4];
        writeIntBE(length, 0, data.length);
        out.write(length);
        out.write(typeBytes);
        out.write(data);

        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        byte[] crcBytes = new byte[4];
        writeIntBE(crcBytes, 0, (int) crc.getValue());
        out.write(crcBytes);
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 2));
        byte[] buffer = new byte[65536];
        while (!deflater.finished()) {
            int written = deflater.deflate(buffer);
            out.write(buffer, 0, written);
        }
        deflater.end();
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] data, int offset, int length) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(data, offset, length);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, length * 2));
        byte[] buffer = new byte[65536];
        try {
            while (!inflater.finished()) {
                int written = inflater.inflate(buffer);
                if (written == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw new IOException("压缩数据不完整");
                    }
                } else {
                    out.write(buffer, 0, written);
                }
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("解压失败: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    private static int readIntBE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static long readLongLE(byte[] data, int offset) {
        long value = 0;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | (data[offset + i] & 0xFFL);
        }
        return value;
    }

    private static void writeIntBE(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    private static void writeIntLE(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }
}
