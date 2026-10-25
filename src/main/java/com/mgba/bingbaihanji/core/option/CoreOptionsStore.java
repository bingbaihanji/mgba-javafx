package com.mgba.bingbaihanji.core.option;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mgba.bingbaihanji.core.save.AtomicFiles;
import com.mgba.bingbaihanji.platform.desktop.AppPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 核心选项的持久化（{@code core-options.json}）。
 *
 * <p>存的是「键 → 值文本」而不是值下标：选项的可选值顺序可能随核心版本变化，
 * 下标会指到别的值；文本匹配找不到时跳过该选项，退化为默认值。
 */
public final class CoreOptionsStore {

    private static final TypeReference<Map<String, String>> MAP_TYPE =
            new TypeReference<>() {
            };

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private final Path file;

    /** 使用默认位置。 */
    public CoreOptionsStore() {
        this(AppPaths.configDirectory().resolve("core-options.json"));
    }

    /**
     * @param file JSON 文件路径
     */
    public CoreOptionsStore(Path file) {
        this.file = file;
    }

    /**
     * 读取保存的选项。
     *
     * @return 键 → 值文本；文件不存在或损坏时为空表
     */
    public Map<String, String> load() {
        if (!Files.isRegularFile(file)) {
            return new HashMap<>();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, String> options = MAPPER.readValue(json, MAP_TYPE);
            return options == null ? new HashMap<>() : options;
        } catch (IOException | RuntimeException e) {
            System.err.println("读取核心选项失败: " + file + " — " + e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 原子写入选项。
     *
     * @param options 键 → 值文本
     * @throws IOException 写入失败
     */
    public void save(Map<String, String> options) throws IOException {
        AtomicFiles.write(file, MAPPER.writeValueAsString(options).getBytes(StandardCharsets.UTF_8));
    }

    /** 文件路径。 */
    public Path file() {
        return file;
    }
}
