package com.mgba.bingbaihanji.platform.desktop.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自定义着色器兼容层：ES 2.0 必须被改成能链接的桌面着色器，桌面着色器必须原样保留。
 */
class CustomShaderAdapterTest {

    @Test
    void convertsEs2ShaderToDesktop330() {
        String es = """
                precision mediump float;
                varying vec2 vTexCoord;
                uniform sampler2D uTexture;
                void main() {
                    gl_FragColor = texture2D(uTexture, vTexCoord);
                }
                """;

        String desktop = CustomShaderAdapter.toDesktopFragmentShader(es);

        assertTrue(desktop.startsWith("#version 330 core"));
        assertTrue(desktop.contains("out vec4 fragColor;"));
        assertTrue(desktop.contains("in vec2 vUV;"));
        assertTrue(desktop.contains("texture(uTexture, vUV)"));
        assertTrue(desktop.contains("fragColor = texture"));
        assertFalse(desktop.contains("precision"));
        assertFalse(desktop.contains("varying"));
        assertFalse(desktop.contains("texture2D"));
        assertFalse(desktop.contains("gl_FragColor"));
    }

    @Test
    void leavesDesktopShaderUnchanged() {
        String desktop = """
                #version 330 core
                in vec2 vUV;
                uniform sampler2D uTex;
                out vec4 fragColor;
                void main() {
                    fragColor = texture(uTex, vUV);
                }
                """;

        assertEquals(desktop, CustomShaderAdapter.toDesktopFragmentShader(desktop));
    }
}
