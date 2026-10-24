package com.mgba.bingbaihanji.core.cheat;

/** 金手指代码类型。 */
public enum CheatType {

    /** GameShark / Action Replay：{@code AAAAAAAA VVVVVVVV}。 */
    GAME_SHARK("GameShark"),

    /** CodeBreaker：{@code AAAAAAAA VVVV}。 */
    CODE_BREAKER("CodeBreaker"),

    /** Raw / VBA 直写：{@code AAAAAAAA:VV}、{@code :VVVV}、{@code :VVVVVVVV}。 */
    RAW("Raw");

    private final String label;

    CheatType(String label) {
        this.label = label;
    }

    /** 面向用户的类型名。 */
    public String label() {
        return label;
    }
}
