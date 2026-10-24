package com.mgba.bingbaihanji.core.option;

import com.mgba.bingbaihanji.core.emulation.EmulatorCore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 核心选项的读取与设置。
 *
 * <p>选项由核心在 {@code retro_set_environment} 阶段注册，所以必须在核心初始化
 * （{@code initialize()} 成功）之后读取。
 *
 * <p>设置只改核心侧记录的当前值并标记"选项已更新"，核心在下一次
 * {@code retro_run()} 时才会读到新值；部分选项（如 GB 机型）需要重新加载游戏才生效。
 */
public final class CoreOptionService {

    private final EmulatorCore core;

    public CoreOptionService(EmulatorCore core) {
        this.core = core;
    }

    /**
     * 读取全部核心选项。
     *
     * @return 选项列表；核心未初始化时为空列表
     */
    public List<CoreOption> load() {
        int count = core.optionCount();
        List<CoreOption> options = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            String key = core.optionKey(i);
            String joined = core.optionValues(i);
            if (key == null || joined == null) {
                continue;
            }
            // 核心给的格式是 "Label;v1|v2"（v0 路径已去掉 Label），值前后可能带空格，
            // 显示时去掉；设置一律按下标，不会影响传给核心的原始值
            List<String> values = Arrays.stream(joined.split("\\|", -1))
                    .map(String::strip)
                    .toList();
            options.add(new CoreOption(i, key, values, core.optionCurrent(i)));
        }
        return options;
    }

    /**
     * 设置选项的当前值。
     *
     * @param index      选项下标（{@link CoreOption#index()}）
     * @param valueIndex 值下标
     * @return 核心是否接受
     */
    public boolean set(int index, int valueIndex) {
        return core.setOption(index, valueIndex);
    }
}
