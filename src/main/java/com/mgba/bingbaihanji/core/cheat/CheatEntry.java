package com.mgba.bingbaihanji.core.cheat;

/**
 * 一条用户金手指（持久化 DTO）。
 *
 * <p>只存用户原始输入，不存解析结果：类型与 libretro 形式都是派生数据，
 * 每次应用时重新解析，避免存储格式与解析规则不一致。
 */
public final class CheatEntry {

    private String name;

    private String code;

    private boolean enabled = true;

    /** Jackson 反序列化用。 */
    public CheatEntry() {
    }

    public CheatEntry(String name, String code, boolean enabled) {
        this.name = name;
        this.code = code;
        this.enabled = enabled;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
