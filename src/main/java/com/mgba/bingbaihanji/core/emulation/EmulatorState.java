package com.mgba.bingbaihanji.core.emulation;

public enum EmulatorState {
    UNINITIALIZED(0),
    INITIALIZED(1),
    GAME_LOADED(2),
    RUNNING(3),
    ERROR(4);

    public final int code;

    EmulatorState(int code) {
        this.code = code;
    }

    public static EmulatorState fromCode(int code) {
        for (EmulatorState s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知状态码: " + code);
    }
}
