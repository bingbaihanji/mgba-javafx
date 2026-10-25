package com.mgba.bingbaihanji.platform.desktop;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mgba.bingbaihanji.core.save.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 主窗口状态的读写（程序目录下的 {@code window-state.json}）。
 *
 * <p>文件不存在时立即写入一份默认值，保证用户第一次启动后就能在 jar 同级目录
 * 看到配置文件。文件损坏时同样回退并重写默认值——窗口位置丢了不影响使用。
 */
public final class WindowStateStore {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private final Path file;

    /** 使用默认位置（程序目录下）。 */
    public WindowStateStore() {
        this(AppPaths.configDirectory().resolve("window-state.json"));
    }

    /**
     * @param file 状态文件路径
     */
    public WindowStateStore(Path file) {
        this.file = file;
    }

    /**
     * 读取窗口状态；文件不存在或无法解析时创建默认文件。
     *
     * @return 窗口状态
     */
    public WindowState loadOrCreate() {
        if (!Files.isRegularFile(file)) {
            WindowState defaults = new WindowState();
            saveQuietly(defaults);
            return defaults;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            WindowState state = MAPPER.readValue(json, WindowState.class);
            return sanitize(state);
        } catch (IOException | RuntimeException e) {
            System.err.println("读取窗口状态失败: " + file + " — " + e.getMessage());
            WindowState defaults = new WindowState();
            saveQuietly(defaults);
            return defaults;
        }
    }

    /**
     * 保存窗口状态。
     *
     * @param state 窗口状态
     * @throws IOException 写入失败
     */
    public void save(WindowState state) throws IOException {
        AtomicFiles.write(file, MAPPER.writeValueAsString(state).getBytes(StandardCharsets.UTF_8));
    }

    /** 文件路径。 */
    public Path file() {
        return file;
    }

    private void saveQuietly(WindowState state) {
        try {
            save(state);
        } catch (IOException e) {
            System.err.println("写入窗口状态失败: " + file + " — " + e.getMessage());
        }
    }

    /** 手改过的 JSON 可能给出负数或 NaN，拉回合法范围。 */
    private static WindowState sanitize(WindowState state) {
        WindowState result = state == null ? new WindowState() : state;
        if (!isValidSize(result.width)) {
            result.width = WindowState.DEFAULT_WIDTH;
        }
        if (!isValidSize(result.height)) {
            result.height = WindowState.DEFAULT_HEIGHT;
        }
        if (result.x != null && !Double.isFinite(result.x)) {
            result.x = null;
        }
        if (result.y != null && !Double.isFinite(result.y)) {
            result.y = null;
        }
        return result;
    }

    private static boolean isValidSize(double value) {
        return Double.isFinite(value) && value > 0;
    }
}
