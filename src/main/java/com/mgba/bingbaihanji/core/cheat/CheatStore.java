package com.mgba.bingbaihanji.core.cheat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mgba.bingbaihanji.core.save.AtomicFiles;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 金手指列表的 JSON 持久化（每个 ROM 一个文件）。
 *
 * <p>文件损坏或格式不认识时返回空列表而不是抛异常：金手指丢了是小事，
 * 打不开游戏才是大事。
 */
public final class CheatStore {

    private static final Type LIST_TYPE = new TypeToken<List<CheatEntry>>() {
    }.getType();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    /**
     * @param file JSON 文件路径
     */
    public CheatStore(Path file) {
        this.file = file;
    }

    /**
     * 读取列表。
     *
     * @return 解析结果；文件不存在或损坏时为空列表
     */
    public List<CheatEntry> load() {
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            List<CheatEntry> entries = GSON.fromJson(json, LIST_TYPE);
            return entries == null ? new ArrayList<>() : entries;
        } catch (IOException | RuntimeException e) {
            System.err.println("读取金手指失败: " + file + " — " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 原子写入列表。
     *
     * @param entries 金手指列表
     * @throws IOException 写入失败
     */
    public void save(List<CheatEntry> entries) throws IOException {
        String json = GSON.toJson(entries, LIST_TYPE);
        AtomicFiles.write(file, json.getBytes(StandardCharsets.UTF_8));
    }

    /** JSON 文件路径。 */
    public Path file() {
        return file;
    }
}
