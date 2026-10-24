package com.mgba.bingbaihanji.core.save;

import com.mgba.bingbaihanji.core.emulation.EmulatorCore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 电池存档（SRAM / RTC）读写。
 *
 * <p>JNI 侧要求 direct {@link ByteBuffer}，因此这里统一用 {@code allocateDirect}
 * 与核心交换数据，再和普通文件互拷。
 *
 * <p>读取时严格比对文件长度与核心声明的长度：长度不对的 .sav 宁可不用，
 * 也不能把缺一段的数据灌进核心——那会让游戏读到伪造的存档并可能当场崩溃。
 */
public final class BatterySaveStore {

    /** SRAM 文件名。 */
    private static final String SRAM_FILE = "game.sav";

    /** RTC 文件名。 */
    private static final String RTC_FILE = "game.rtc";

    private final Path gameDirectory;

    /**
     * @param gameDirectory 单个 ROM 的存档目录
     */
    public BatterySaveStore(Path gameDirectory) {
        this.gameDirectory = gameDirectory;
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

    // ── 内部 ──────────────────────────────────────────────────

    private boolean loadSram(EmulatorCore core) throws IOException {
        int size = core.sramSize();
        if (size <= 0) {
            return false;
        }
        Path file = gameDirectory.resolve(SRAM_FILE);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        byte[] data = Files.readAllBytes(file);
        if (data.length != size) {
            return false;
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(size);
        buffer.put(data).flip();
        return core.loadSram(buffer, size);
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

    private boolean saveSram(EmulatorCore core) throws IOException {
        int size = core.sramSize();
        if (size <= 0) {
            return false;
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(size);
        if (!core.saveSram(buffer)) {
            return false;
        }
        AtomicFiles.write(gameDirectory.resolve(SRAM_FILE), toArray(buffer, size));
        return true;
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
