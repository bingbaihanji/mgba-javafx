package com.mgba.bingbaihanji.core.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mgba.bingbaihanji.core.save.AtomicFiles;
import com.mgba.bingbaihanji.platform.desktop.AppPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 设置文件的读写（{@code %APPDATA%/bingbaihanji-mgba/settings.json}）。
 *
 * <p>文件缺失、损坏或字段非法时回退到默认值：设置丢了可以重新点，
 * 打不开程序不行。
 */
public final class SettingsStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    /** 使用默认位置。 */
    public SettingsStore() {
        this(AppPaths.configDirectory().resolve("settings.json"));
    }

    /**
     * @param file 设置文件路径
     */
    public SettingsStore(Path file) {
        this.file = file;
    }

    /**
     * 读取设置。
     *
     * @return 设置对象；文件不存在或损坏时返回默认值
     */
    public AppSettings load() {
        if (!Files.isRegularFile(file)) {
            return new AppSettings();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            AppSettings settings = GSON.fromJson(json, AppSettings.class);
            return settings == null ? new AppSettings() : sanitize(settings);
        } catch (IOException | RuntimeException e) {
            System.err.println("读取设置失败: " + file + " — " + e.getMessage());
            return new AppSettings();
        }
    }

    /**
     * 保存设置。
     *
     * @param settings 设置
     * @throws IOException 写入失败
     */
    public void save(AppSettings settings) throws IOException {
        AtomicFiles.write(file, GSON.toJson(settings).getBytes(StandardCharsets.UTF_8));
    }

    /** 设置文件路径。 */
    public Path file() {
        return file;
    }

    /** 把明显越界的值拉回合法范围（手改过 JSON 的情况）。 */
    private static AppSettings sanitize(AppSettings settings) {
        settings.gameSpeed = Math.clamp(settings.gameSpeed, 0.25, 16.0);
        settings.soundVolume = Math.clamp(settings.soundVolume, 0, 100);
        if (settings.shaderPreset == null) {
            settings.shaderPreset = "OFF";
        }
        if (settings.customShaderPath == null) {
            settings.customShaderPath = "";
        }
        return settings;
    }
}
