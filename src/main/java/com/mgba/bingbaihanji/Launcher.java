package com.mgba.bingbaihanji;

import com.mgba.bingbaihanji.view.MgbaAppKt;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * fat jar 的启动入口。
 *
 * <p>职责只有三件事，做完再交给 Kotlin 的界面入口：
 * <ol>
 *   <li>把 JavaFX 的原生库缓存从 {@code ~/.openjfx} 改到程序目录下的
 *       {@code .mgba-javafx/javafx}；</li>
 *   <li>把打包在 jar 里的 {@code mgba_frontend.dll} / {@code mgba_libretro.dll}
 *       解压到程序目录的 {@code native/win32-x64/}；</li>
 *   <li>把程序目录与解压目录告诉后续代码（系统属性 {@code mgba.home} /
 *       {@code mgba.native.dir}）。</li>
 * </ol>
 *
 * <p>程序目录就是 jar 所在目录；从 IDE 直接运行时取当前工作目录。
 * 所有运行期数据（设置、存档、截图、缓存等）都只写在这里，不使用临时目录或
 * AppData 等用户目录。目录不可写时直接报错退出，不做降级。
 *
 * <p>从 IDE 直接运行（classpath 指向 {@code target/classes} 目录）时不走解压流程，
 * 继续使用开发目录里的 DLL。
 */
public final class Launcher {

    /** 随 jar 一起打包的原生库。 */
    private static final List<String> NATIVE_FILES = List.of(
            "mgba_frontend.dll",
            "mgba_libretro.dll"
    );

    /** jar 内原生库资源目录。 */
    private static final String NATIVE_RESOURCE_DIR = "/native/win32-x64/";

    private Launcher() {
    }

    public static void main(String[] args) throws Exception {
        Path location = codeLocation();
        boolean fromJar = location != null && Files.isRegularFile(location);
        Path homeDirectory = fromJar
                ? location.getParent()
                : Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();

        // 所有数据都落在程序目录，必须先于任何 AppPaths 调用与 JavaFX 初始化设置
        System.setProperty("mgba.home", homeDirectory.toString());

        Path cacheDirectory;
        try {
            cacheDirectory = createCacheDirectory(homeDirectory);
        } catch (IOException e) {
            fatal("无法在程序目录创建缓存文件夹：\n" + homeDirectory
                    + "\n\n请把程序放到有写入权限的目录后重试。");
            return;
        }
        // 必须在任何 JavaFX 类初始化之前设置
        System.setProperty("javafx.cachedir", cacheDirectory.resolve("javafx").toString());

        if (fromJar) {
            try {
                Path nativeDirectory = extractNatives(homeDirectory);
                System.setProperty("mgba.native.dir", nativeDirectory.toString());
            } catch (IOException e) {
                fatal("无法把原生库解压到程序目录：\n" + homeDirectory
                        + "\n\n请把程序放到有写入权限的目录后重试。");
                return;
            }
        }

        LaunchOptions.parse(args);

        // Kotlin 的顶层 main 没有参数（JVM 入口用的 String[] 版本是编译器生成的合成方法，
        // javac 不允许直接调用），这里调用无参版本即可
        MgbaAppKt.main();
    }

    /** 当前代码来源：jar 文件（打包后）或 classes 目录（IDE）。 */
    private static Path codeLocation() {
        try {
            return Path.of(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /** 缓存目录固定在程序目录下，不使用临时目录。 */
    private static Path createCacheDirectory(Path homeDirectory) throws IOException {
        Path directory = homeDirectory.resolve(".mgba-javafx");
        Files.createDirectories(directory);
        return directory;
    }

    /**
     * 解压原生库到程序目录。
     *
     * @return 实际使用的原生库目录
     */
    private static Path extractNatives(Path homeDirectory) throws IOException {
        Path target = homeDirectory.resolve("native").resolve("win32-x64");
        Files.createDirectories(target);
        for (String name : NATIVE_FILES) {
            extract(NATIVE_RESOURCE_DIR + name, target.resolve(name));
        }
        return target;
    }

    /** 无法在程序目录写入时给出可见提示并退出。 */
    private static void fatal(String message) {
        System.err.println(message);
        try {
            javax.swing.JOptionPane.showMessageDialog(
                    null, message, "mGBA javafx", javax.swing.JOptionPane.ERROR_MESSAGE);
        } catch (Throwable ignored) {
            // 无图形环境时只保留 stderr 输出
        }
        System.exit(1);
    }

    /** 原子解压单个资源：先写 .tmp 再改名，避免半截文件被加载。 */
    private static void extract(String resource, Path target) throws IOException {
        try (InputStream input = Launcher.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("jar 中缺少原生库: " + resource);
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
