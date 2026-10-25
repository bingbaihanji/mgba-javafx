package com.mgba.bingbaihanji.platform.desktop.input;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mgba.bingbaihanji.core.emulation.GbaButton;
import com.mgba.bingbaihanji.core.save.AtomicFiles;
import com.mgba.bingbaihanji.platform.desktop.AppPaths;
import javafx.scene.input.KeyCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 键盘 → GBA 按键的映射（{@code keymap.json}）。
 *
 * <p>持久化格式是「GbaButton 枚举名 → KeyCode 枚举名」，未知或失效的键名在读取时
 * 回退到默认值——手改配置文件写错一个名字不该让整份映射失效。
 *
 * <p>只保存映射数据，不参与输入分发；{@link #asKeyToButton()} 给控制器做反查表。
 */
public final class KeyMapping {

    /** 参与映射的按键（GBA 没有 X/Y）。 */
    public static final GbaButton[] MAPPABLE = {
            GbaButton.A, GbaButton.B, GbaButton.L, GbaButton.R,
            GbaButton.START, GbaButton.SELECT,
            GbaButton.UP, GbaButton.DOWN, GbaButton.LEFT, GbaButton.RIGHT
    };

    private static final TypeReference<Map<String, String>> MAP_TYPE =
            new TypeReference<>() {
            };

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    /** 默认映射。 */
    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("A", "X");
        DEFAULTS.put("B", "Z");
        DEFAULTS.put("L", "A");
        DEFAULTS.put("R", "S");
        DEFAULTS.put("START", "ENTER");
        DEFAULTS.put("SELECT", "BACK_SPACE");
        DEFAULTS.put("UP", "UP");
        DEFAULTS.put("DOWN", "DOWN");
        DEFAULTS.put("LEFT", "LEFT");
        DEFAULTS.put("RIGHT", "RIGHT");
    }

    private final Map<String, String> bindings;

    private KeyMapping(Map<String, String> bindings) {
        this.bindings = bindings;
    }

    /** 默认映射。 */
    public static KeyMapping defaults() {
        return new KeyMapping(new LinkedHashMap<>(DEFAULTS));
    }

    /** 从默认文件读取；文件不存在或损坏时返回默认映射。 */
    public static KeyMapping load() {
        return load(defaultFile());
    }

    /**
     * 从指定文件读取（便于测试）。
     *
     * @param file JSON 文件
     * @return 映射；文件不存在或损坏时为默认映射
     */
    public static KeyMapping load(Path file) {
        if (!Files.isRegularFile(file)) {
            return defaults();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, String> saved = MAPPER.readValue(json, MAP_TYPE);
            return fromSaved(saved);
        } catch (IOException | RuntimeException e) {
            System.err.println("读取按键映射失败: " + file + " — " + e.getMessage());
            return defaults();
        }
    }

    /** 用已保存的条目构造，缺失/非法的条目回退默认值。 */
    private static KeyMapping fromSaved(Map<String, String> saved) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (GbaButton button : MAPPABLE) {
            String fallback = DEFAULTS.get(button.name());
            String candidate = saved == null ? null : saved.get(button.name());
            merged.put(button.name(), isValidKeyName(candidate) ? candidate : fallback);
        }
        return new KeyMapping(merged);
    }

    private static Path defaultFile() {
        return AppPaths.configDirectory().resolve("keymap.json");
    }

    private static boolean isValidKeyName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        try {
            KeyCode.valueOf(name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 复制一份（对话框编辑用，取消时不影响当前映射）。 */
    public KeyMapping copy() {
        return new KeyMapping(new LinkedHashMap<>(bindings));
    }

    /**
     * 某个 GBA 按键当前绑定的键盘键。
     *
     * @param button GBA 按键
     * @return 键盘键；映射缺失时返回默认键，默认也无效时返回 null
     */
    public KeyCode keyFor(GbaButton button) {
        String name = bindings.get(button.name());
        if (isValidKeyName(name)) {
            return KeyCode.valueOf(name);
        }
        String fallback = DEFAULTS.get(button.name());
        return isValidKeyName(fallback) ? KeyCode.valueOf(fallback) : null;
    }

    /**
     * 修改绑定。
     *
     * @param button GBA 按键
     * @param key    新的键盘键
     */
    public void set(GbaButton button, KeyCode key) {
        if (key == null) {
            bindings.remove(button.name());
        } else {
            bindings.put(button.name(), key.name());
        }
    }

    /**
     * 找某个键盘键当前绑定的 GBA 按键（用于冲突检测）。
     *
     * @param key 键盘键
     * @return 已绑定的按键；没有则 null
     */
    public GbaButton buttonUsing(KeyCode key) {
        if (key == null) {
            return null;
        }
        for (GbaButton button : MAPPABLE) {
            if (key.equals(keyFor(button))) {
                return button;
            }
        }
        return null;
    }

    /** 恢复全部默认映射。 */
    public void resetToDefaults() {
        bindings.clear();
        bindings.putAll(DEFAULTS);
    }

    /**
     * 键 → GBA 按键的反查表（输入分发用）。
     *
     * @return 新的映射表
     */
    public Map<KeyCode, GbaButton> asKeyToButton() {
        Map<KeyCode, GbaButton> result = new EnumMap<>(KeyCode.class);
        for (GbaButton button : MAPPABLE) {
            KeyCode key = keyFor(button);
            if (key != null) {
                result.put(key, button);
            }
        }
        return result;
    }

    /**
     * 保存到默认文件。
     *
     * @throws IOException 写入失败
     */
    public void save() throws IOException {
        save(defaultFile());
    }

    /**
     * 保存到指定文件（便于测试）。
     *
     * @param file JSON 文件
     * @throws IOException 写入失败
     */
    public void save(Path file) throws IOException {
        AtomicFiles.write(file, MAPPER.writeValueAsString(bindings).getBytes(StandardCharsets.UTF_8));
    }
}
