package com.mgba.bingbaihanji.core.save;

/**
 * 即时存档解析失败。
 *
 * <p>异常里带 {@link SaveError}，调用方直接把 {@link #reason()} 显示给用户即可。
 */
public final class SaveStateException extends Exception {

    private final SaveError reason;

    public SaveStateException(SaveError reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** 失败原因。 */
    public SaveError reason() {
        return reason;
    }
}
