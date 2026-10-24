package com.mgba.bingbaihanji.core.emulation;

public enum GbaButton {
    B(0),
    Y(1),
    SELECT(2),
    START(3),
    UP(4),
    DOWN(5),
    LEFT(6),
    RIGHT(7),
    A(8),
    X(9),
    L(10),
    R(11);

    public final int id;

    GbaButton(int id) {
        this.id = id;
    }

    public static GbaButton fromId(int id) {
        for (GbaButton b : values()) {
            if (b.id == id) {
                return b;
            }
        }
        throw new IllegalArgumentException("未知按键ID: " + id);
    }
}
