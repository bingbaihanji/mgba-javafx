package com.mgba.bingbaihanji.platform.desktop.input;

import com.mgba.bingbaihanji.core.emulation.GbaButton;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 按键映射的持久化与容错：自定义绑定能往返，坏键名回退默认值。
 */
class KeyMappingTest {

    @TempDir
    Path directory;

    @Test
    void savesAndLoadsBindings() throws IOException {
        KeyMapping mapping = KeyMapping.defaults();
        mapping.set(GbaButton.A, KeyCode.Q);

        Path file = directory.resolve("keymap.json");
        mapping.save(file);
        KeyMapping loaded = KeyMapping.load(file);

        assertEquals(KeyCode.Q, loaded.keyFor(GbaButton.A));
        assertEquals(KeyCode.Z, loaded.keyFor(GbaButton.B));
        assertEquals(GbaButton.A, loaded.buttonUsing(KeyCode.Q));
    }

    @Test
    void fallsBackToDefaultsForUnknownKeyName() throws IOException {
        Path file = directory.resolve("keymap.json");
        Files.writeString(file, "{\"A\":\"NOT_A_KEY\",\"B\":\"K\"}");

        KeyMapping loaded = KeyMapping.load(file);

        assertEquals(KeyCode.X, loaded.keyFor(GbaButton.A));
        assertEquals(KeyCode.K, loaded.keyFor(GbaButton.B));
    }
}
