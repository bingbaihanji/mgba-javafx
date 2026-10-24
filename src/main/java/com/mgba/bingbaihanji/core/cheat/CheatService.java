package com.mgba.bingbaihanji.core.cheat;

import com.mgba.bingbaihanji.core.emulation.EmulatorCore;
import com.mgba.bingbaihanji.core.save.RomIdentity;
import com.mgba.bingbaihanji.platform.desktop.AppPaths;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 金手指业务：增删改、持久化、应用到核心。
 *
 * <p>mGBA 的 {@code retro_cheat_set} 会忽略 index 与 enabled，把所有代码追加到
 * 同一个集合里，因此这里每次都先 {@code cheatReset()} 再重放全部<b>启用</b>的条目。
 */
public final class CheatService {

    private final EmulatorCore core;

    private final CheatStore store;

    private final List<CheatEntry> entries;

    private CheatService(EmulatorCore core, CheatStore store, List<CheatEntry> entries) {
        this.core = core;
        this.store = store;
        this.entries = entries;
    }

    /**
     * 打开某个 ROM 的金手指集合。
     *
     * @param core     已加载 ROM 的核心
     * @param identity ROM 身份（用短 ID 命名文件）
     * @return 服务实例
     */
    public static CheatService open(EmulatorCore core, RomIdentity identity) {
        Path file = AppPaths.cheatsDirectory().resolve(identity.romId() + ".json");
        CheatStore store = new CheatStore(file);
        return new CheatService(core, store, store.load());
    }

    /** 可变的金手指列表（UI 直接操作它，改动后调用 {@link #persist()} 与 {@link #apply()}）。 */
    public List<CheatEntry> entries() {
        return entries;
    }

    /**
     * 添加一条金手指。
     *
     * @param name 名称
     * @param code 用户输入代码
     * @return 新条目
     * @throws CheatFormatException 代码格式不合法
     */
    public CheatEntry add(String name, String code) throws CheatFormatException {
        ParsedCheat parsed = CheatCodeParser.parse(code);
        CheatEntry entry = new CheatEntry(
                (name == null || name.isBlank()) ? parsed.type().label() : name.strip(),
                code.strip(),
                true);
        entries.add(entry);
        return entry;
    }

    /**
     * 删除一条金手指。
     *
     * @param entry 条目
     * @return 是否真的删掉了
     */
    public boolean remove(CheatEntry entry) {
        return entries.remove(entry);
    }

    /** 持久化到磁盘；失败时只打印日志，不影响游戏运行。 */
    public void persist() {
        try {
            store.save(entries);
        } catch (IOException e) {
            System.err.println("保存金手指失败: " + e.getMessage());
        }
    }

    /**
     * 把全部启用的金手指应用到核心。
     *
     * <p>解析失败的条目跳过：列表可能在旧版本里存过不合法代码，不能因此让整个应用失败。
     */
    public void apply() {
        core.cheatReset();
        int index = 0;
        for (CheatEntry entry : entries) {
            if (!entry.isEnabled()) {
                continue;
            }
            try {
                ParsedCheat parsed = CheatCodeParser.parse(entry.getCode());
                core.cheatSet(index++, true, parsed.libretroCode());
            } catch (CheatFormatException e) {
                System.err.println("跳过非法金手指「" + entry.getName() + "」: " + e.getMessage());
            }
        }
    }

    /** 金手指 JSON 文件路径。 */
    public Path file() {
        return store.file();
    }
}
