package com.mgba.bingbaihanji.core.save;

import com.mgba.bingbaihanji.core.emulation.EmulatorCore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 单个游戏的存档会话：统一管理即时存档与电池存档。
 *
 * <p>创建时确定游戏目录：优先用 ROM 显示名；若该目录已被另一个 ROM 占用
 * （{@code .mgba-rom} 标记里的 SHA-256 对不上），改用 {@code <romId>-<显示名>}，
 * 避免两个同名 ROM 互相覆盖存档。
 */
public final class GameSaveSession {

    /** 目录归属标记文件名，内容为完整 SHA-256。 */
    private static final String MARKER_FILE = ".mgba-rom";

    private final EmulatorCore core;

    private final RomIdentity identity;

    private final Path gameDirectory;

    private final SaveStateStore states;

    private final BatterySaveStore battery;

    private GameSaveSession(EmulatorCore core, RomIdentity identity, Path gameDirectory) {
        this.core = core;
        this.identity = identity;
        this.gameDirectory = gameDirectory;
        this.states = new SaveStateStore(gameDirectory);
        this.battery = new BatterySaveStore(gameDirectory);
    }

    /**
     * 打开（必要时创建）该 ROM 的存档目录。
     *
     * <p>必须在 {@code loadGame()} 成功之后调用——SRAM/RTC 的大小要等核心加载完 ROM 才知道。
     *
     * @param core     已加载 ROM 的核心
     * @param identity ROM 身份
     * @param saveRoot 存档根目录
     * @return 存档会话
     * @throws IOException 目录或标记文件创建失败
     */
    public static GameSaveSession open(EmulatorCore core, RomIdentity identity, Path saveRoot)
            throws IOException {
        Path directory = resolveDirectory(saveRoot, identity);
        return new GameSaveSession(core, identity, directory);
    }

    private static Path resolveDirectory(Path saveRoot, RomIdentity identity) throws IOException {
        Path directory = saveRoot.resolve(identity.directoryName());
        Path marker = directory.resolve(MARKER_FILE);

        if (Files.isRegularFile(marker)) {
            String existing = Files.readString(marker, StandardCharsets.UTF_8).strip();
            if (!existing.equalsIgnoreCase(identity.sha256())) {
                // 同名不同 ROM：加短 ID 前缀另开一个目录
                directory = saveRoot.resolve(identity.romId() + "-" + identity.directoryName());
            }
        }

        Files.createDirectories(directory);
        Path targetMarker = directory.resolve(MARKER_FILE);
        if (!Files.isRegularFile(targetMarker)) {
            AtomicFiles.write(targetMarker, identity.sha256().getBytes(StandardCharsets.UTF_8));
        }
        return directory;
    }

    // ── 电池存档 ──────────────────────────────────────────────

    /**
     * 从磁盘恢复 SRAM / RTC。文件不存在属正常情况。
     *
     * @return 实际加载的文件数
     * @throws IOException 读取失败
     */
    public int loadBattery() throws IOException {
        return battery.load(core);
    }

    /**
     * 把 SRAM / RTC 写回磁盘。
     *
     * @return 实际写出的文件数
     * @throws IOException 写入失败
     */
    public int saveBattery() throws IOException {
        return battery.save(core);
    }

    // ── 即时存档 ──────────────────────────────────────────────

    /**
     * 把当前状态保存到槽位。
     *
     * @param slot 0–{@link SaveStateStore#AUTO_SLOT}
     * @return 操作结果
     */
    public SaveError saveState(int slot) {
        long size = core.serializeSize();
        if (size <= 0 || size > Integer.MAX_VALUE) {
            return SaveError.CORE_REJECTED;
        }

        ByteBuffer buffer = ByteBuffer.allocateDirect((int) size);
        if (!core.serialize(buffer)) {
            return SaveError.CORE_REJECTED;
        }
        buffer.position(0);
        byte[] state = new byte[(int) size];
        buffer.get(state, 0, state.length);

        byte[] raw = SaveStateFormat.encode(
                core.coreName(), core.coreVersion(), identity.romId(),
                System.currentTimeMillis(), state);
        try {
            states.write(slot, raw);
        } catch (IOException e) {
            return SaveError.FILE_ERROR;
        }

        // 缩略图是"锦上添花"：失败不影响存档本身
        try {
            java.awt.image.BufferedImage thumbnail = ScreenshotWriter.capture(core);
            if (thumbnail != null) {
                ScreenshotWriter.writePng(thumbnail, thumbnailFile(slot));
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("保存存档缩略图失败: " + e.getMessage());
        }
        return SaveError.OK;
    }

    /**
     * 槽位缩略图文件路径（{@code slotN.png}，与存档同目录）。
     *
     * @param slot 槽位号
     * @return 文件路径
     */
    public Path thumbnailFile(int slot) {
        return states.slotFile(slot).resolveSibling("slot" + slot + ".png");
    }

    /**
     * 删除槽位的存档与缩略图。
     *
     * @param slot 槽位号
     * @return 操作结果
     */
    public SaveError deleteState(int slot) {
        try {
            states.delete(slot);
            Files.deleteIfExists(thumbnailFile(slot));
            return SaveError.OK;
        } catch (IOException e) {
            return SaveError.FILE_ERROR;
        }
    }

    /**
     * 从槽位恢复状态。
     *
     * @param slot 0–{@link SaveStateStore#AUTO_SLOT}
     * @return 操作结果
     */
    public SaveError loadState(int slot) {
        byte[] raw;
        try {
            raw = states.read(slot);
        } catch (java.nio.file.NoSuchFileException e) {
            return SaveError.SLOT_EMPTY;
        } catch (IOException e) {
            return SaveError.FILE_ERROR;
        }

        SaveStateFile file;
        try {
            file = SaveStateFormat.decode(raw);
        } catch (SaveStateException e) {
            return e.reason();
        }

        if (!identity.romId().equalsIgnoreCase(file.romId())) {
            return SaveError.ROM_MISMATCH;
        }

        ByteBuffer buffer = ByteBuffer.allocateDirect(file.data().length);
        buffer.put(file.data()).flip();
        return core.unserialize(buffer, file.data().length)
                ? SaveError.OK
                : SaveError.CORE_REJECTED;
    }

    /**
     * 槽位是否有存档。
     *
     * @param slot 槽位号
     * @return 有存档时为 true
     */
    public boolean hasState(int slot) {
        return states.exists(slot);
    }

    /**
     * 槽位存档的保存时间。
     *
     * @param slot 槽位号
     * @return Unix 毫秒；空槽返回 0
     */
    public long stateTimestamp(int slot) {
        return states.timestamp(slot);
    }

    /** 该游戏的存档目录。 */
    public Path gameDirectory() {
        return gameDirectory;
    }

    /** ROM 身份。 */
    public RomIdentity identity() {
        return identity;
    }
}
