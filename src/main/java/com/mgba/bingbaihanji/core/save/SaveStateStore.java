package com.mgba.bingbaihanji.core.save;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * 即时存档槽位的文件管理。
 *
 * <p>槽位 1–10 对应 UI 上的 10 个槽，{@link #AUTO_SLOT} 是退出时自动保存的隐藏槽。
 * 文件名为 {@code slotN.state}，与 SRAM/RTC 放在同一个游戏目录下。
 */
public final class SaveStateStore {

    /** UI 可见槽位数。 */
    public static final int SLOT_COUNT = 10;

    /** 自动存档槽（UI 不展示）。 */
    public static final int AUTO_SLOT = 10;

    private final Path gameDirectory;

    /**
     * @param gameDirectory 单个 ROM 的存档目录
     */
    public SaveStateStore(Path gameDirectory) {
        this.gameDirectory = gameDirectory;
    }

    /**
     * 槽位文件路径。
     *
     * @param slot 0–10
     * @return 文件路径
     * @throws IllegalArgumentException 槽位越界
     */
    public Path slotFile(int slot) {
        if (slot < 0 || slot > AUTO_SLOT) {
            throw new IllegalArgumentException("槽位越界: " + slot);
        }
        return gameDirectory.resolve("slot" + slot + ".state");
    }

    /**
     * 槽位是否有存档。
     *
     * @param slot 槽位号
     * @return 文件存在时为 true
     */
    public boolean exists(int slot) {
        return Files.isRegularFile(slotFile(slot));
    }

    /**
     * 槽位文件的最后修改时间。
     *
     * @param slot 槽位号
     * @return Unix 毫秒；没有存档时返回 0
     */
    public long timestamp(int slot) {
        try {
            return Files.getLastModifiedTime(slotFile(slot)).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * 读取槽位原始字节。
     *
     * @param slot 槽位号
     * @return 文件内容
     * @throws NoSuchFileException 槽位为空
     * @throws IOException         读取失败
     */
    public byte[] read(int slot) throws IOException {
        return Files.readAllBytes(slotFile(slot));
    }

    /**
     * 原子写入槽位。
     *
     * @param slot 槽位号
     * @param raw  MGST 容器字节
     * @throws IOException 写入失败
     */
    public void write(int slot, byte[] raw) throws IOException {
        AtomicFiles.write(slotFile(slot), raw);
    }

    /**
     * 删除槽位（文件不存在时不报错）。
     *
     * @param slot 槽位号
     * @throws IOException 删除失败
     */
    public void delete(int slot) throws IOException {
        Files.deleteIfExists(slotFile(slot));
    }

    /** 该 ROM 的存档目录。 */
    public Path gameDirectory() {
        return gameDirectory;
    }
}
