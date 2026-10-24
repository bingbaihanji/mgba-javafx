package com.mgba.bingbaihanji.core.save;

import com.mgba.bingbaihanji.core.emulation.EmulatorCore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 单个游戏的存档会话：统一管理即时存档与电池存档。
 *
 * <h2>与原版 mGBA 的兼容</h2>
 * <ul>
 *   <li><b>电池存档</b>：读写 ROM 同目录下的 {@code <ROM>.sav}，保留文件末尾 16 字节的
 *       RTC 数据（mGBA 的布局是「SRAM + RTC 结构」）；</li>
 *   <li><b>快捷存档</b>：槽位 1–9 对应 ROM 同目录的 {@code <ROM>.ss1 … .ss9}，
 *       格式是 mGBA 的 PNG 自定义块（{@code gbAs}/{@code gbAx}）；</li>
 *   <li>应用自己的 {@code %APPDATA%} 目录仍保留：自动槽位、以及旧版 MGST 存档的读取兼容。</li>
 * </ul>
 *
 * <p>同一个显示名的不同 ROM 仍然按 {@code .mgba-rom} 标记区分存档目录
 * （只影响应用目录里的自动槽位与旧格式文件）。
 */
public final class GameSaveSession {

    /** 目录归属标记文件名，内容为完整 SHA-256。 */
    private static final String MARKER_FILE = ".mgba-rom";

    private final EmulatorCore core;

    private final RomIdentity identity;

    /** ROM 文件路径（用于定位同目录的 .sav / .ssN），可能为 null。 */
    private final Path romPath;

    private final Path gameDirectory;

    private final SaveStateStore states;

    private final BatterySaveStore battery;

    /** 读档前的状态备份（撤销读档用，只保留最近一次）。 */
    private byte[] backupState;

    private GameSaveSession(EmulatorCore core, RomIdentity identity, Path romPath, Path gameDirectory) {
        this.core = core;
        this.identity = identity;
        this.romPath = romPath;
        this.gameDirectory = gameDirectory;
        this.states = new SaveStateStore(gameDirectory);
        this.battery = new BatterySaveStore(gameDirectory, romPath);
    }

    /**
     * 打开（必要时创建）该 ROM 的存档目录。
     *
     * <p>必须在 {@code loadGame()} 成功之后调用——SRAM/RTC 的大小要等核心加载完 ROM 才知道。
     *
     * @param core     已加载 ROM 的核心
     * @param identity ROM 身份
     * @param saveRoot 应用存档根目录（自动槽位与旧格式用）
     * @param romPath  ROM 文件路径；同目录的 .sav/.ssN 与 mGBA 互通
     * @return 存档会话
     * @throws IOException 目录或标记文件创建失败
     */
    public static GameSaveSession open(EmulatorCore core, RomIdentity identity,
                                       Path saveRoot, Path romPath) throws IOException {
        Path directory = resolveDirectory(saveRoot, identity);
        return new GameSaveSession(core, identity, romPath, directory);
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
     * @param slot 0–{@link SaveStateStore#SLOT_COUNT}；{@link SaveStateStore#AUTO_SLOT} 为自动槽
     * @return 操作结果
     */
    public SaveError saveState(int slot) {
        if (slot == SaveStateStore.AUTO_SLOT) {
            return saveLegacySlot(slot);
        }
        return saveMegaSlot(slot);
    }

    /** 保存为 mGBA 的 .ssN（槽位 0 对应 ss1）。 */
    private SaveError saveMegaSlot(int slot) {
        byte[] named = serializeNamedState();
        if (named == null) {
            return SaveError.CORE_REJECTED;
        }

        MegaStateFile.Payload payload;
        try {
            payload = MegaStateFile.splitNamedState(named, core.sramSize());
        } catch (IOException e) {
            System.err.println("无法拆分核心状态，改用应用目录格式: " + e.getMessage());
            return saveLegacySlot(slot);
        }

        Path target = megaStateFile(slot);
        if (target == null) {
            return saveLegacySlot(slot);
        }

        try {
            // 写入 ROM 短 ID，读档时能挡住"拿错 ROM 的存档"
            MegaStateFile.Payload tagged = new MegaStateFile.Payload(
                    payload.coreState(), payload.extdata(), identity.romId());
            byte[] png = MegaStateFile.writePng(tagged, ScreenshotWriter.capture(core));
            AtomicFiles.write(target, png);
            return SaveError.OK;
        } catch (IOException | RuntimeException e) {
            // ROM 目录不可写等情况：退回应用目录的 MGST，至少存档不会丢
            System.err.println("写入 mGBA 存档失败，改用应用目录格式: " + e.getMessage());
            return saveLegacySlot(slot);
        }
    }

    /** 旧的 MGST 格式（自动槽位与兜底）。 */
    private SaveError saveLegacySlot(int slot) {
        byte[] named = serializeNamedState();
        if (named == null) {
            return SaveError.CORE_REJECTED;
        }

        byte[] raw = SaveStateFormat.encode(
                core.coreName(), core.coreVersion(), identity.romId(),
                System.currentTimeMillis(), named);
        try {
            states.write(slot, raw);
        } catch (IOException e) {
            return SaveError.FILE_ERROR;
        }

        try {
            java.awt.image.BufferedImage thumbnail = ScreenshotWriter.capture(core);
            if (thumbnail != null) {
                ScreenshotWriter.writePng(thumbnail, legacyThumbnailFile(slot));
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("保存存档缩略图失败: " + e.getMessage());
        }
        return SaveError.OK;
    }

    /** 核心序列化（mGBA named state：核心状态 + 扩展数据）。 */
    private byte[] serializeNamedState() {
        long size = core.serializeSize();
        if (size <= 0 || size > Integer.MAX_VALUE) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect((int) size);
        if (!core.serialize(buffer)) {
            return null;
        }
        buffer.position(0);
        byte[] state = new byte[(int) size];
        buffer.get(state, 0, state.length);
        return state;
    }

    /**
     * 槽位对应的 mGBA 存档文件：{@code <ROM 同目录>/<ROM 名>.ssN}（槽位 0 → ss1）。
     *
     * @param slot 槽位号（0 起）
     * @return 文件路径；自动槽或没有 ROM 路径时返回 null
     */
    private Path megaStateFile(int slot) {
        if (romPath == null || slot < 0 || slot >= SaveStateStore.SLOT_COUNT) {
            return null;
        }
        Path absolute = romPath.toAbsolutePath();
        Path directory = absolute.getParent();
        if (directory == null) {
            return null;
        }
        return directory.resolve(identity.displayName() + ".ss" + (slot + 1));
    }

    private Path legacyStateFile(int slot) {
        return states.slotFile(slot);
    }

    private Path legacyThumbnailFile(int slot) {
        return legacyStateFile(slot).resolveSibling("slot" + slot + ".png");
    }

    /**
     * 槽位预览图路径：mGBA 存档本身就是一张 PNG，直接用它做缩略图。
     *
     * @param slot 槽位号
     * @return 图片路径（可能不存在）
     */
    public Path thumbnailFile(int slot) {
        Path mega = megaStateFile(slot);
        if (mega != null && Files.isRegularFile(mega)) {
            return mega;
        }
        return legacyThumbnailFile(slot);
    }

    /**
     * 删除槽位的存档与缩略图。
     *
     * @param slot 槽位号
     * @return 操作结果
     */
    public SaveError deleteState(int slot) {
        try {
            Path mega = megaStateFile(slot);
            if (mega != null) {
                Files.deleteIfExists(mega);
            }
            states.delete(slot);
            Files.deleteIfExists(legacyThumbnailFile(slot));
            return SaveError.OK;
        } catch (IOException e) {
            return SaveError.FILE_ERROR;
        }
    }

    /**
     * 从槽位恢复状态。
     *
     * @param slot 0–{@link SaveStateStore#SLOT_COUNT}；{@link SaveStateStore#AUTO_SLOT} 为自动槽
     * @return 操作结果
     */
    public SaveError loadState(int slot) {
        // 读档前先备份当前状态，支持"撤销读档"
        byte[] previousBackup = backupState;
        byte[] current = serializeNamedState();
        if (current != null) {
            backupState = current;
        }

        SaveError result;
        Path mega = megaStateFile(slot);
        if (mega != null && Files.isRegularFile(mega)) {
            try {
                result = loadMegaState(mega);
            } catch (IOException e) {
                result = SaveError.FILE_ERROR;
            }
        } else {
            result = loadLegacyState(slot);
        }

        if (result != SaveError.OK) {
            // 读档失败：保留上一次还能用的备份，不要被本次覆盖
            backupState = previousBackup;
        }
        return result;
    }

    /** 是否有可撤销的读档备份。 */
    public boolean hasBackup() {
        return backupState != null;
    }

    /**
     * 恢复到读档前的状态（撤销读档），成功后备份即被消费。
     *
     * @return 操作结果
     */
    public SaveError restoreBackup() {
        byte[] backup = backupState;
        if (backup == null) {
            return SaveError.NO_BACKUP;
        }

        ByteBuffer buffer = ByteBuffer.allocateDirect(backup.length);
        buffer.put(backup).flip();
        if (!core.unserialize(buffer, backup.length)) {
            return SaveError.CORE_REJECTED;
        }

        // 备份里也带电池存档，一并恢复（拆分失败就只恢复核心状态）
        try {
            MegaStateFile.Payload payload = MegaStateFile.splitNamedState(backup, core.sramSize());
            applySavedata(payload.extdata());
        } catch (IOException ignored) {
            // 不影响撤销本身
        }

        backupState = null;
        return SaveError.OK;
    }

    /** 把状态里内嵌的电池存档写回核心（与 mGBA GUI 的行为一致）。 */
    private void applySavedata(Map<Integer, byte[]> extdata) {
        byte[] savedata = extdata.get(MegaStateFile.EXTDATA_SAVEDATA);
        if (savedata != null && savedata.length > 0) {
            ByteBuffer sram = ByteBuffer.allocateDirect(savedata.length);
            sram.put(savedata).flip();
            core.loadSram(sram, savedata.length);
        }
    }

    /** 加载 mGBA 的 .ssN（PNG 或老式 named state）。 */
    private SaveError loadMegaState(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        byte[] coreState;
        Map<Integer, byte[]> extdata = Map.of();

        if (MegaStateFile.isPng(data)) {
            try {
                MegaStateFile.Payload payload = MegaStateFile.readPng(data);
                // 新格式带 ROM 短 ID；旧 mGBA 存档没有，按 mGBA 的做法不做限制
                if (payload.romId() != null && !payload.romId().isBlank()
                        && !identity.romId().equalsIgnoreCase(payload.romId())) {
                    return SaveError.ROM_MISMATCH;
                }
                coreState = payload.coreState();
                extdata = payload.extdata();
            } catch (IOException e) {
                return SaveError.CORRUPTED;
            }
        } else {
            // 少数版本/工具写出的是未封装的 named state：整体交给核心
            coreState = data;
        }

        ByteBuffer buffer = ByteBuffer.allocateDirect(coreState.length);
        buffer.put(coreState).flip();
        if (!core.unserialize(buffer, coreState.length)) {
            return SaveError.CORE_REJECTED;
        }

        // 与 mGBA GUI 一样，把状态里的电池存档一并恢复
        applySavedata(extdata);
        return SaveError.OK;
    }

    /** 加载旧版 MGST 存档。 */
    private SaveError loadLegacyState(int slot) {
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
     * 槽位是否有存档（mGBA 文件或旧格式）。
     *
     * @param slot 槽位号
     * @return 有存档时为 true
     */
    public boolean hasState(int slot) {
        Path mega = megaStateFile(slot);
        if (mega != null && Files.isRegularFile(mega)) {
            return true;
        }
        return states.exists(slot);
    }

    /**
     * 槽位存档的保存时间。
     *
     * @param slot 槽位号
     * @return Unix 毫秒；空槽返回 0
     */
    public long stateTimestamp(int slot) {
        Path mega = megaStateFile(slot);
        if (mega != null && Files.isRegularFile(mega)) {
            try {
                return Files.getLastModifiedTime(mega).toMillis();
            } catch (IOException ignored) {
                // 落到旧格式的时间
            }
        }
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
