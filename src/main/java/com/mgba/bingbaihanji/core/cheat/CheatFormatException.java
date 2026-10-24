package com.mgba.bingbaihanji.core.cheat;

/** 金手指代码格式错误。消息直接面向用户，说明是哪一行、哪里不合法。 */
public final class CheatFormatException extends Exception {

    public CheatFormatException(String message) {
        super(message);
    }
}
