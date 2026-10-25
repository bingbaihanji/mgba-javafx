package com.mgba.bingbaihanji.core.save;

import com.mgba.bingbaihanji.core.emulation.EmulatorCore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;

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
 * <h2>GBA 存档类型与大小</h2>
 * <p>libretro 核心在刚加载 ROM 时还没确定存档类型，{@code RETRO_MEMORY_SAVE_RAM}
 * 会按最大值 Flash 1M（128 KB）报告；游戏第一次访问存档芯片后才变成实际大小
 * （SRAM 32 KB、Flash 64 KB 等）。如果按 128 KB 做长度校验，就会把正常的
 * 32 KB {@code .sav} 拒掉，随后用空白 SRAM 覆盖原存档。因此这里按文件本身的
 * 已知存档大小加载，并把它放进 0xFF 填充的完整缓冲区交给核心。
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

    /** 核心未检测出类型时报告的 GBA 存档上限（Flash 1M）。 */
    private static final int GBA_AUTODETECT_SIZE = 0x20000;

    /**
     * GBA / GB 常见电池存档大小。
     *
     * <p>核心在自动检测阶段的报告值不可靠，只能用文件自己的大小判断；
     * 只接受这些确定值，避免把别的文件（或截断文件）当存档读进来。
     */
    private static final Set<Integer> KNOWN_SAVE_SIZES =
            Set.of(512, 1024, 2048, 8192, 32768, 65536, 131072);

    private final Path gameDirectory;

    private final Path romPath;

    /**
     * 本次会话实际加载/写出的 SRAM 大小。
     *
     * <p>核心仍处于自动检测阶段（报告 128 KB）时用它决定写文件的大小，
     * 避免把 32 KB 的存档扩成 128 KB。
     */
    private int preferredSramSize;

    /**
     * @param gameDirectory 应用存档目录
     * @param romPath       ROM 文件路径（用于同目录 .sav），可为 null
     */
    public BatterySaveStore(Path gameDirectory, Path romPath) {
        this.gameDirectory = gameDirectory;
        this.romPath = romPath;
    }

    /**
     * 文件长度是否可以安全地当作存档加载。
     *
     * <p>除核心报告的大小外，还接受常见存档大小：核心自动检测阶段报的是
     * 128 KB，实际 SRAM/Flash 往往更小。
     */
    static boolean isLoadableSize(long length, int coreSize) {
        if (length <= 0) {
            return false;
        }
        if (length == coreSize || length == (long) coreSize + RTC_FOOTER_SIZE) {
            return true;
        }
        if (length < coreSize) {
            return KNOWN_SAVE_SIZES.contains((int) length)
                    || (length > RTC_FOOTER_SIZE
                    && KNOWN_SAVE_SIZES.contains((int) (length - RTC_FOOTER_SIZE)));
        }
        return false;
    }

    /**
     * 实际交给核心的 SRAM 字节数（不含 RTC 尾巴）。
     *
     * @param fileLength 文件长度
     * @param coreSize   核心缓冲区大小
     * @return 字节数；无法识别时返回 -1
     */
    static int payloadSize(int fileLength, int coreSize) {
        if (fileLength == coreSize || fileLength == coreSize + RTC_FOOTER_SIZE) {
            return coreSize;
        }
        if (KNOWN_SAVE_SIZES.contains(fileLength)) {
            return fileLength;
        }
        if (fileLength > RTC_FOOTER_SIZE && KNOWN_SAVE_SIZES.contains(fileLength - RTC_FOOTER_SIZE)) {
            return fileLength - RTC_FOOTER_SIZE;
        }
        return -1;
    }

    // ── SRAM ──────────────────────────────────────────────────

    /** 把 direct buffer 的前 {@code size} 字节拷成数组（核心写入不移动 position）。 */
    private static byte[] toArray(ByteBuffer buffer, int size) {
        byte[] data = new byte[size];
        buffer.position(0);
        buffer.get(data, 0, size);
        return data;
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

    private boolean loadSram(EmulatorCore core) throws IOException {
        int coreSize = core.sramSize();
        if (coreSize <= 0) {
            return false;
        }

        Path source = chooseNewest(coreSize, gameDirectory.resolve(SRAM_FILE), romSaveFile());
        if (source == null) {
            return false;
        }

        byte[] data = Files.readAllBytes(source);
        int payloadSize = payloadSize(data.length, coreSize);
        if (payloadSize <= 0) {
            return false;
        }

        // 核心的缓冲区在自动检测阶段按 128 KB 分配；用 0xFF 填满，
        // 模拟未使用区域（与核心初始化时的 SRAM 状态一致）。
        ByteBuffer buffer = ByteBuffer.allocateDirect(coreSize);
        byte[] initial = new byte[coreSize];
        Arrays.fill(initial, (byte) 0xFF);
        buffer.put(initial);
        buffer.position(0);
        buffer.put(data, 0, payloadSize);
        buffer.position(0);

        boolean loaded = core.loadSram(buffer, coreSize);
        if (loaded) {
            preferredSramSize = payloadSize;
        }
        return loaded;
    }

    private boolean saveSram(EmulatorCore core) throws IOException {
        int coreSize = core.sramSize();
        if (coreSize <= 0) {
            return false;
        }
        // 核心还没检测出存档类型：没有加载过存档就没有可写的数据，
        // 不要凭空写出 128 KB 覆盖原文件。
        if (coreSize == GBA_AUTODETECT_SIZE && preferredSramSize <= 0) {
            return false;
        }
        // 检测出实际大小后以核心为准；只有仍处于自动检测阶段才沿用已加载文件的大小
        int writeSize = coreSize;
        if (coreSize == GBA_AUTODETECT_SIZE && preferredSramSize > 0) {
            writeSize = Math.min(coreSize, preferredSramSize);
        }

        ByteBuffer buffer = ByteBuffer.allocateDirect(coreSize);
        if (!core.saveSram(buffer)) {
            return false;
        }
        byte[] data = toArray(buffer, writeSize);
        preferredSramSize = writeSize;

        // 应用目录：始终保存
        AtomicFiles.write(gameDirectory.resolve(SRAM_FILE), data);

        // ROM 目录：镜像一份，保留原有的 RTC 尾巴
        Path romFile = romSaveFile();
        if (romFile != null) {
            try {
                byte[] tail = new byte[0];
                if (Files.isRegularFile(romFile)) {
                    long romSaveLength = Files.size(romFile);
                    if (romSaveLength == (long) writeSize + RTC_FOOTER_SIZE) {
                        byte[] existing = Files.readAllBytes(romFile);
                        tail = Arrays.copyOfRange(existing, writeSize, existing.length);
                    }
                }
                byte[] mirrored = new byte[writeSize + tail.length];
                System.arraycopy(data, 0, mirrored, 0, writeSize);
                System.arraycopy(tail, 0, mirrored, writeSize, tail.length);
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
     * @param coreSize 核心当前报告的存档缓冲区大小（自动检测阶段是 128 KB）
     * @param candidates 候选文件
     * @return 选中的文件；都不合法时返回 null
     */
    private Path chooseNewest(int coreSize, Path... candidates) throws IOException {
        Path best = null;
        long bestTime = Long.MIN_VALUE;
        for (Path candidate : candidates) {
            if (candidate == null || !Files.isRegularFile(candidate)) {
                continue;
            }
            long length = Files.size(candidate);
            if (!isLoadableSize(length, coreSize)) {
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

    // ── RTC（GB）──────────────────────────────────────────────

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
}
