package com.mgba.bingbaihanji.platform.desktop;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 桌面端配置与数据目录。
 *
 * <p>根目录为 {@code %APPDATA%/bingbaihanji-mgba}；没有 APPDATA 环境变量时
 * 退回 {@code ~/.config/bingbaihanji-mgba}（便于在非 Windows 环境跑测试）。
 *
 * <p>各子目录在首次访问时创建，调用方不必自己 mkdir。
 */
public final class AppPaths {

    /** 应用目录名。 */
    private static final String APP_DIR = "bingbaihanji-mgba";

    private AppPaths() {
    }

    /** 根配置目录（不保证存在）。 */
    public static Path configDirectory() {
        String appData = System.getenv("APPDATA");
        Path base = (appData != null && !appData.isBlank())
                ? Paths.get(appData)
                : Paths.get(System.getProperty("user.home"), ".config");
        return base.resolve(APP_DIR);
    }

    /** 存档目录，每个 ROM 一个子目录。 */
    public static Path savesDirectory() {
        return ensure(configDirectory().resolve("saves"));
    }

    /** BIOS 等系统文件目录。 */
    public static Path systemDirectory() {
        return ensure(configDirectory().resolve("system"));
    }

    /** 金手指配置目录。 */
    public static Path cheatsDirectory() {
        return ensure(configDirectory().resolve("cheats"));
    }

    /** 用户自定义着色器目录。 */
    public static Path shadersDirectory() {
        return ensure(configDirectory().resolve("shaders"));
    }

    /** 截图目录。 */
    public static Path screenshotsDirectory() {
        return ensure(configDirectory().resolve("screenshots"));
    }

    /** 创建目录（含父目录）；失败时抛出 {@link UncheckedIOException}。 */
    private static Path ensure(Path directory) {
        try {
            Files.createDirectories(directory);
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建目录: " + directory, e);
        }
    }
}
