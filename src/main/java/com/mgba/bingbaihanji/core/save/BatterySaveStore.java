package com.mgba.bingbaihanji.core.save;

import com.mgba.bingbaihanji.core.emulation.EmulatorCore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 电池存档（SRAM / RTC）读写，兼容 mGBA 在 ROM 同目录写出的 {@code <ROM>.sav}。
 *
 * <h2>两种位置</h2>
 * <ul>
 *   <li>应用目录：{@code <jar 同级>/saves/<ROM>/game.sav}（始终保存，作为本程序的副本）</li>
 *   <li>ROM 同目录：{@code <ROM>.sav}（与原版 mGBA 互通）</li>
 * </ul>
 * 加载时取两者中**较新的、长度合法**的那一个；保存时两边都写。
 *
 * <h2>RTC 尾巴</h2>
 * <p>mGBA 的 GBA 电池存档布局是「SRAM + 16 字节 RTC 结构」，文件比 SRAM 大 16 字节。
 * 读取时忽略尾巴；写回 ROM 目录时如果原文件带尾巴，则原样保留（我们拿不到 GBA RTC，
 * 不能把它覆盖成垃圾）。core 的 RTC 接口只对 GB 生效，GB 仍单独写 {@code game.rtc}。
 */
public final class BatterySaveStore {

    /** 应用目录里的 SRAM 文件名。 */
    private static final String SRAM_FILE = "game.sav";

    /** 应用目录里的 RTC 文件名（GB）。 */
    private static final String RTC_FILE = "game.rtc";

    /** mGBA GBA .sav 末尾的 RTC 结构大小。 */
    private static final int RTC_FOOTER_SIZE = 16;

    private final Path gameDirectory;

    private final Path romPath;

    /**
     * @param gameDirectory 应用存档目录
     * @param romPath       ROM 文件路径（用于同目录 .sav），可为 null
     */
    public BatterySaveStore(Path gameDirectory, Path romPath) {
        this.gameDirectory = gameDirectory;
        this.romPath = romPath;
    }

    /**
     * 从磁盘加载 SRAM 与 RTC。
     *
     * <p>文件不存在是正常情况（第一次玩），不算错误。
     *
     * @param core 已加载 ROM 的核心
     * @return 实际加载成功的文件数（0–2）
     * @throws IOException 文件存在但读取失败
     */
    public int load(EmulatorCore core) throws IOException {
        int loaded = 0;
        if (loadSram(core)) {
            loaded++;
        }
        if (loadRtc(core)) {
            loaded++;
        }
        return loaded;
    }

    /**
     * 保存 SRAM 与 RTC 到磁盘。
     *
     * <p>核心声明的长度为 0（该 ROM 没有电池存档）时直接跳过。
     *
     * @param core 已加载 ROM 的核心
     * @return 实际写出的文件数（0–2）
     * @throws IOException 写入失败
     */
    public int save(EmulatorCore core) throws IOException {
        int saved = 0;
        if (saveSram(core)) {
            saved++;
        }
        if (saveRtc(core)) {
            saved++;
        }
        return saved;
    }

    // ── SRAM ──────────────────────────────────────────────────

    private boolean loadSram(EmulatorCore core) throws IOException {
        int size = core.sramSize();
        if (size <= 0) {
            return false;
        }

        Path source = chooseNewest(size, gameDirectory.resolve(SRAM_FILE), romSaveFile());
        if (source == null) {
            return false;
        }

        byte[] data = Files.readAllBytes(source);
        if (data.length < size) {
            return false;
        }

        ByteBuffer buffer = ByteBuffer.allocateDirect(size);
        // mGBA 的 .sav 可能多出 16 字节 RTC：只把前面的 SRAM 交给核心
        buffer.put(data, 0, size).flip();
        return core.loadSram(buffer, size);
    }

    private boolean saveSram(EmulatorCore core) throws IOException {
        int size = core.sramSize();
        if (size <= 0) {
            return false;
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(size);
        if (!core.saveSram(buffer)) {
            return false;
        }
        byte[] data = toArray(buffer, size);

        // 应用目录：始终保存
        AtomicFiles.write(gameDirectory.resolve(SRAM_FILE), data);

        // ROM 目录：镜像一份，保留原有的 RTC 尾巴
        Path romFile = romSaveFile();
        if (romFile != null) {
            try {
                byte[] tail = new byte[0];
                if (Files.isRegularFile(romFile) && Files.size(romFile) == size + RTC_FOOTER_SIZE) {
                    byte[] existing = Files.readAllBytes(romFile);
                    tail = Arrays.copyOfRange(existing, size, existing.length);
                }
                byte[] mirrored = new byte[size + tail.length];
                System.arraycopy(data, 0, mirrored, 0, size);
                System.arraycopy(tail, 0, mirrored, size, tail.length);
                AtomicFiles.write(romFile, mirrored);
            } catch (IOException e) {
                // ROM 目录不可写（Program Files 等）不应让游戏崩
                System.err.println("写入 ROM 目录 .sav 失败（仅保留应用目录副本）: " + e.getMessage());
            }
        }
        return true;
    }

    /**
     * 在两个候选文件里挑较新的、长度合法的那个。
     *
     * @param sramSize SRAM 大小
     * @param candidates 候选文件
     * @return 选中的文件；都不合法时返回 null
     */
    private Path chooseNewest(int sramSize, Path... candidates) throws IOException {
        Path best = null;
        long bestTime = Long.MIN_VALUE;
        for (Path candidate : candidates) {
            if (candidate == null || !Files.isRegularFile(candidate)) {
                continue;
            }
            long length = Files.size(candidate);
            if (length != sramSize && length != (long) sramSize + RTC_FOOTER_SIZE) {
                // 长度对不上：可能是别的游戏的存档，宁可不读
                continue;
            }
            long modified = Files.getLastModifiedTime(candidate).toMillis();
            if (modified > bestTime) {
                bestTime = modified;
                best = candidate;
            }
        }
        return best;
    }

    /** ROM 同目录的 .sav 路径。 */
    private Path romSaveFile() {
        if (romPath == null) {
            return null;
        }
        Path absolute = romPath.toAbsolutePath();
        Path directory = absolute.getParent();
        if (directory == null) {
            return null;
        }
        String name = absolute.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return directory.resolve(base + ".sav");
    }

    // ── RTC（GB）──────────────────────────────────────────────

    private boolean loadRtc(EmulatorCore core) throws IOException {
        int size = core.rtcSize();
        if (size <= 0) {
            return false;
        }
        Path file = gameDirectory.resolve(RTC_FILE);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        byte[] data = Files.readAllBytes(file);
        if (data.length != size) {
            return false;
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(size);
        buffer.put(data).flip();
        return core.loadRtc(buffer, size);
    }

    private boolean saveRtc(EmulatorCore core) throws IOException {
        int size = core.rtcSize();
        if (size <= 0) {
            return false;
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(size);
        if (!core.saveRtc(buffer)) {
            return false;
        }
        AtomicFiles.write(gameDirectory.resolve(RTC_FILE), toArray(buffer, size));
        return true;
    }

    /** 把 direct buffer 的前 {@code size} 字节拷成数组（核心写入不移动 position）。 */
    private static byte[] toArray(ByteBuffer buffer, int size) {
        byte[] data = new byte[size];
        buffer.position(0);
        buffer.get(data, 0, size);
        return data;
    }
}
