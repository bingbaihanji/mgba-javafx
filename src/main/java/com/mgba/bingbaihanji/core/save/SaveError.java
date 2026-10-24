package com.mgba.bingbaihanji.core.save;

/**
 * 存档操作结果。
 *
 * <p>把失败原因分得比 "true/false" 细：UI 要能告诉用户到底是槽位空着、
 * 文件损坏，还是 ROM 对不上——这三者的处理方式完全不同。
 */
public enum SaveError {

    /** 操作成功。 */
    OK("成功"),

    /** 槽位没有存档。 */
    SLOT_EMPTY("槽位为空"),

    /** 文件损坏或格式无法识别。 */
    CORRUPTED("存档文件损坏"),

    /** 存档属于另一个 ROM。 */
    ROM_MISMATCH("存档与当前 ROM 不匹配"),

    /** 存档格式版本高于本程序支持的版本。 */
    VERSION_UNSUPPORTED("存档格式版本不受支持"),

    /** 读写文件失败。 */
    FILE_ERROR("存档文件读写失败"),

    /** 核心拒绝序列化或反序列化。 */
    CORE_REJECTED("核心拒绝该操作"),

    /** 当前没有加载 ROM。 */
    NO_GAME("尚未加载 ROM");

    private final String description;

    SaveError(String description) {
        this.description = description;
    }

    /** 面向用户的中文描述。 */
    public String description() {
        return description;
    }

    /** 是否成功。 */
    public boolean ok() {
        return this == OK;
    }
}
