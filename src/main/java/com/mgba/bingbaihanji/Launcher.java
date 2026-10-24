package com.mgba.bingbaihanji;

import com.mgba.bingbaihanji.view.MgbaAppKt;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * fat jar 的启动入口。
 *
 * <p>职责只有三件事，做完再交给 Kotlin 的界面入口：
 * <ol>
 *   <li>把 JavaFX 的原生库缓存从 {@code ~/.openjfx} 改到 jar 同目录下的
 *       {@code .mgba-javafx/javafx}（或系统临时目录），不污染用户主目录；</li>
 *   <li>把打包在 jar 里的 {@code mgba_frontend.dll} / {@code mgba_libretro.dll}
 *       解压到 jar 同目录的 {@code native/win32-x64/}；目录不可写时退回临时目录；</li>
 *   <li>把解压目录告诉 JNI 加载器（系统属性 {@code mgba.native.dir}）。</li>
 * </ol>
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
        Path appDirectory = fromJar ? location.getParent() : null;

        Path cacheDirectory = createCacheDirectory(appDirectory);
        // 必须在任何 JavaFX 类初始化之前设置
        System.setProperty("javafx.cachedir", cacheDirectory.resolve("javafx").toString());

        if (fromJar) {
            Path nativeDirectory = extractNatives(appDirectory, cacheDirectory);
            System.setProperty("mgba.native.dir", nativeDirectory.toString());
        }

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

    /**
     * 缓存目录优先放在 jar 同目录；不可写时退回系统临时目录。
     */
    private static Path createCacheDirectory(Path appDirectory) throws IOException {
        if (appDirectory != null) {
            try {
                Path directory = appDirectory.resolve(".mgba-javafx");
                Files.createDirectories(directory);
                return directory;
            } catch (IOException ignored) {
                // jar 在只读目录：退到临时目录
            }
        }
        return Files.createTempDirectory("mgba-javafx-");
    }

    /**
     * 解压原生库。
     *
     * @return 实际使用的原生库目录
     */
    private static Path extractNatives(Path appDirectory, Path cacheDirectory) throws IOException {
        Path target = appDirectory.resolve("native").resolve("win32-x64");
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            // jar 目录不可写：解压到缓存目录
            target = cacheDirectory.resolve("native");
            Files.createDirectories(target);
        }

        for (String name : NATIVE_FILES) {
            extract(NATIVE_RESOURCE_DIR + name, target.resolve(name));
        }
        return target;
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
