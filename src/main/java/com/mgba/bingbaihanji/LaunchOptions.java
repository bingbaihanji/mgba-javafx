package com.mgba.bingbaihanji;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 启动参数。
 *
 * <p>支持三种写法，后出现的会覆盖前面的：
 * <pre>
 * java -jar mgba-javafx.jar --rom="D:/game.gba"
 * java -jar mgba-javafx.jar --rom D:/game.gba
 * java -jar mgba-javafx.jar D:/game.gba
 * </pre>
 *
 * <p>接口层只读 {@link #rom()}；解析失败或没有 ROM 时返回 {@code null}，
 * 由界面决定提示方式。相对路径按当前工作目录解析。
 */
public final class LaunchOptions {

    /** 启动时要打开的 ROM；null 表示没有。 */
    private static volatile Path rom;

    private LaunchOptions() {
    }

    /**
     * 解析命令行参数。
     *
     * @param args JVM 传入的参数，可为 null
     */
    public static void parse(String[] args) {
        Path parsed = null;
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg == null) {
                    continue;
                }
                if (arg.equals("--rom")) {
                    if (i + 1 < args.length) {
                        parsed = toPath(args[++i]);
                    }
                } else if (arg.startsWith("--rom=")) {
                    parsed = toPath(arg.substring("--rom=".length()));
                } else if (!arg.startsWith("-") && parsed == null) {
                    // 方便直接拖文件到 exe/jar，或将来做文件关联
                    parsed = toPath(arg);
                }
            }
        }
        rom = parsed;
    }

    /** 启动时要打开的 ROM；没有时为 null。 */
    public static Path rom() {
        return rom;
    }

    /** 去掉可能的引号，解析成绝对路径；空值或非法路径返回 null。 */
    private static Path toPath(String value) {
        if (value == null) {
            return null;
        }
        String text = value.strip();
        if (text.length() >= 2
                && ((text.startsWith("\"") && text.endsWith("\""))
                || (text.startsWith("'") && text.endsWith("'")))) {
            text = text.substring(1, text.length() - 1).strip();
        }
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Paths.get(text).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
