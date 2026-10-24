package com.mgba.bingbaihanji.core.save;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * ROM 身份：完整 SHA-256、短 ID、显示名与存档目录名。
 *
 * <p>SRAM / RTC 用完整 SHA-256 校验（同一显示名的两个 ROM 不会互相覆盖电池存档），
 * 即时存档与金手指用前 16 位短 ID（文件里记一行就够）。
 */
public final class RomIdentity {

    /** 短 ID 长度（SHA-256 十六进制前 16 位）。 */
    private static final int ROM_ID_LENGTH = 16;

    /** 完整 SHA-256，小写十六进制。 */
    private final String sha256;

    /** 前 16 位十六进制，小写。 */
    private final String romId;

    /** 不含扩展名的文件名。 */
    private final String displayName;

    /** 用作目录名的安全文件名。 */
    private final String directoryName;

    /** ROM 文件大小（字节）。 */
    private final long fileSize;

    private RomIdentity(String sha256, String romId, String displayName,
                        String directoryName, long fileSize) {
        this.sha256 = sha256;
        this.romId = romId;
        this.displayName = displayName;
        this.directoryName = directoryName;
        this.fileSize = fileSize;
    }

    /**
     * 读取 ROM 文件并计算身份。
     *
     * @param rom ROM 文件
     * @return 身份对象
     * @throws IOException 文件读取失败
     */
    public static RomIdentity of(Path rom) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // 所有 Java 实现都必须提供 SHA-256，走到这里说明运行环境损坏
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }

        long size = 0L;
        byte[] chunk = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(rom)) {
            int read;
            while ((read = in.read(chunk)) > 0) {
                digest.update(chunk, 0, read);
                size += read;
            }
        }

        String sha = HexFormat.of().formatHex(digest.digest());
        String name = rom.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String displayName = dot > 0 ? name.substring(0, dot) : name;
        return new RomIdentity(
                sha,
                sha.substring(0, ROM_ID_LENGTH),
                displayName,
                sanitizeDirectoryName(displayName),
                size);
    }

    /**
     * 把 ROM 名转成可用的目录名：去掉 Windows 非法字符、结尾的点和空格。
     *
     * @param name 原始显示名
     * @return 安全目录名，至少为 {@code "rom"}
     */
    static String sanitizeDirectoryName(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean illegal = c < 0x20
                    || c == '<' || c == '>' || c == ':' || c == '"'
                    || c == '/' || c == '\\' || c == '|' || c == '?' || c == '*';
            sb.append(illegal ? '_' : c);
        }
        String cleaned = sb.toString().strip();
        while (cleaned.endsWith(".") || cleaned.endsWith(" ")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).strip();
        }
        if (cleaned.isEmpty()) {
            return "rom";
        }
        // 保留名（CON / PRN / AUX / NUL / COM1…）加下划线前缀，避免建目录失败
        String upper = cleaned.toUpperCase(Locale.ROOT);
        boolean reserved = upper.equals("CON") || upper.equals("PRN") || upper.equals("AUX")
                || upper.equals("NUL") || upper.matches("COM[1-9]") || upper.matches("LPT[1-9]");
        return reserved ? "_" + cleaned : cleaned;
    }

    /** 完整 SHA-256（小写十六进制）。 */
    public String sha256() {
        return sha256;
    }

    /** 短 ID：SHA-256 前 16 位（小写十六进制）。 */
    public String romId() {
        return romId;
    }

    /** 不含扩展名的文件名。 */
    public String displayName() {
        return displayName;
    }

    /** 存档目录名（已清洗非法字符）。 */
    public String directoryName() {
        return directoryName;
    }

    /** ROM 文件大小（字节）。 */
    public long fileSize() {
        return fileSize;
    }
}
