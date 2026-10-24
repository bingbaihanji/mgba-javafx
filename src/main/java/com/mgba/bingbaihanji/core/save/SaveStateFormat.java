package com.mgba.bingbaihanji.core.save;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * MGST 即时存档容器格式（大端）。
 *
 * <pre>
 * [4]   "MGST"
 * [4]   format version = 1
 * [8]   timestamp（Unix 毫秒）
 * [4+N] core name（长度前缀 UTF-8）
 * [4+N] core version（长度前缀 UTF-8）
 * [4+N] romId（长度前缀 ASCII，SHA-256 前 16 位）
 * [8]   data size
 * [N]   serialized core state
 * </pre>
 *
 * <p>加载时必须按 magic → version → romId → size 的顺序校验：
 * 任何一步都能把"读到了别人的存档"挡在前面，避免把随机字节喂给核心。
 */
public final class SaveStateFormat {

    /** 文件魔数。 */
    public static final byte[] MAGIC = {'M', 'G', 'S', 'T'};

    /** 当前支持的格式版本。 */
    public static final int FORMAT_VERSION = 1;

    /** 序列化数据上限（64MB），防止损坏的长度字段导致巨量分配。 */
    public static final int MAX_DATA_SIZE = 64 * 1024 * 1024;

    /** 字符串字段上限，理由同上。 */
    private static final int MAX_STRING_LENGTH = 4096;

    private SaveStateFormat() {
    }

    /**
     * 打包一个即时存档。
     *
     * @param coreName    核心名称，可为 null
     * @param coreVersion 核心版本，可为 null
     * @param romId       ROM 短 ID
     * @param timestamp   保存时间（Unix 毫秒）
     * @param state       核心序列化数据
     * @return 完整的文件字节
     */
    public static byte[] encode(String coreName, String coreVersion, String romId,
                                long timestamp, byte[] state) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(state.length + 64);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.write(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeLong(timestamp);
            writeString(out, coreName == null ? "" : coreName);
            writeString(out, coreVersion == null ? "" : coreVersion);
            writeString(out, romId == null ? "" : romId);
            out.writeLong(state.length);
            out.write(state);
        } catch (IOException e) {
            // ByteArrayOutputStream 不会失败；真走到这里说明内存已耗尽
            throw new IllegalStateException("序列化存档容器失败", e);
        }
        return bytes.toByteArray();
    }

    /**
     * 解析并校验一个即时存档文件。
     *
     * @param raw 文件字节
     * @return 解析结果
     * @throws SaveStateException magic/版本/长度任一校验失败
     */
    public static SaveStateFile decode(byte[] raw) throws SaveStateException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                throw new SaveStateException(SaveError.CORRUPTED, "不是 MGST 存档文件");
            }

            int version = in.readInt();
            if (version <= 0 || version > FORMAT_VERSION) {
                throw new SaveStateException(SaveError.VERSION_UNSUPPORTED,
                        "不支持的存档格式版本: " + version);
            }

            long timestamp = in.readLong();
            String coreName = readString(in);
            String coreVersion = readString(in);
            String romId = readString(in);

            // 用 available() 得到"头部之后还剩多少字节"，与声明的数据长度对照，
            // 多一字节少一字节都判为损坏
            long dataSize = in.readLong();
            long remaining = in.available();
            if (dataSize < 0 || dataSize > MAX_DATA_SIZE || dataSize != remaining) {
                throw new SaveStateException(SaveError.CORRUPTED,
                        "数据长度不匹配: 声明 " + dataSize + "，实际 " + remaining);
            }

            byte[] data = new byte[(int) dataSize];
            in.readFully(data);
            return new SaveStateFile(version, timestamp, coreName, coreVersion, romId, data);
        } catch (EOFException e) {
            throw new SaveStateException(SaveError.CORRUPTED, "存档文件不完整");
        } catch (IOException e) {
            throw new SaveStateException(SaveError.CORRUPTED, "读取存档失败: " + e.getMessage());
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }

    private static String readString(DataInputStream in) throws IOException, SaveStateException {
        int length = in.readInt();
        if (length < 0 || length > MAX_STRING_LENGTH) {
            throw new SaveStateException(SaveError.CORRUPTED, "字符串长度非法: " + length);
        }
        byte[] utf8 = new byte[length];
        in.readFully(utf8);
        return new String(utf8, StandardCharsets.UTF_8);
    }
}
