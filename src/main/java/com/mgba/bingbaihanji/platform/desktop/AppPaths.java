package com.mgba.bingbaihanji.platform.desktop;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 桌面端配置与数据目录。
 *
 * <p>所有运行期数据都放在程序目录，也就是 fat jar 所在目录（开发环境直接运行时
 * 为当前工作目录），不在用户目录（AppData、临时目录等）创建任何文件。
 *
 * <p>打包启动器会通过系统属性 {@code mgba.home} 指定程序目录；没有该属性时
 * 按代码位置推断：从 jar 启动取 jar 的父目录，其余情况取当前工作目录。
 */
public final class AppPaths {

    /** 程序目录系统属性名，由 {@code Launcher} 设置。 */
    public static final String HOME_PROPERTY = "mgba.home";

    private AppPaths() {
    }

    /** 程序目录（jar 同级目录）。 */
    public static Path homeDirectory() {
        String override = System.getProperty(HOME_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath().normalize();
        }
        try {
            Path location = Paths.get(AppPaths.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(location)) {
                return location.toAbsolutePath().normalize().getParent();
            }
        } catch (Exception ignored) {
            // 无代码位置信息（特殊类加载器）：退回工作目录
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    /** 根配置目录（与 jar 同级，不保证存在）。 */
    public static Path configDirectory() {
        return homeDirectory();
    }

    /** 存档目录，每个 ROM 一个子目录。 */
    public static Path savesDirectory() {
        return ensure(homeDirectory().resolve("saves"));
    }

    /** BIOS 等系统文件目录。 */
    public static Path systemDirectory() {
        return ensure(homeDirectory().resolve("system"));
    }

    /** 金手指配置目录。 */
    public static Path cheatsDirectory() {
        return ensure(homeDirectory().resolve("cheats"));
    }

    /** 用户自定义着色器目录。 */
    public static Path shadersDirectory() {
        return ensure(homeDirectory().resolve("shaders"));
    }

    /** 截图目录。 */
    public static Path screenshotsDirectory() {
        return ensure(homeDirectory().resolve("screenshots"));
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
