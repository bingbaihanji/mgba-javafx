package com.mgba.bingbaihanji.core.save;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 原子写文件：先写同目录下的 {@code .tmp}，成功后再改名覆盖目标。
 *
 * <p>存档最怕"写到一半断电/崩溃"，目标文件变成一个长度对不上、
 * 谁也读不了的残片。改名在同一文件系统上是原子的，因此要么是旧内容、
 * 要么是完整的新内容。少数文件系统不支持原子改名时退回普通覆盖。
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /**
     * 原子写入。
     *
     * @param target 目标文件
     * @param data   完整内容
     * @throws IOException 写入或改名失败
     */
    public static void write(Path target, byte[] data) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(tmp, data);
            try {
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // 失败时不要留下 .tmp 残片
            Files.deleteIfExists(tmp);
        }
    }
}
