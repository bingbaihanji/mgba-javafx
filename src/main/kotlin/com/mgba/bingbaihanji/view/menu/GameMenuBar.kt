package com.mgba.bingbaihanji.view.menu

import com.mgba.bingbaihanji.platform.desktop.video.ShaderPreset
import javafx.scene.control.*
import javafx.scene.input.KeyCombination

/**
 * 游戏菜单栏：文件 / 模拟 / 音频 / 视频 / 帮助。
 *
 * 通过 [MenuActions] 回调接口与控制器通信，不直接依赖模拟器状态。
 */
class GameMenuBar(private val actions: MenuActions) : MenuBar() {

    /** 着色器单选项，供控制器在加载自定义着色器后同步选中状态。 */
    private val shaderGroup = ToggleGroup()
    private val shaderRadios = mutableMapOf<ShaderPreset, RadioMenuItem>()

    /** 倍速/音量单选项，供设置载入后同步选中状态。 */
    private val speedRadios = mutableMapOf<Double, RadioMenuItem>()
    private val volumeRadios = mutableMapOf<Int, RadioMenuItem>()
    private var debugCheckItem: CheckMenuItem? = null

    init {
        menus.addAll(buildFileMenu(), buildEmulationMenu(), buildAudioMenu(), buildVideoMenu(), buildHelpMenu())
    }

    /**
     * 选中某个着色器预设（不触发回调）。
     *
     * @param preset 预设
     */
    fun selectShaderPreset(preset: ShaderPreset) {
        shaderRadios[preset]?.isSelected = true
    }

    /** 同步倍速单选状态（不触发回调）。 */
    fun selectSpeed(speed: Double) {
        speedRadios.entries.minByOrNull { kotlin.math.abs(it.key - speed) }?.value?.isSelected = true
    }

    /** 同步音量单选状态（不触发回调）。 */
    fun selectVolume(percent: Int) {
        volumeRadios.entries.minByOrNull { kotlin.math.abs(it.key - percent) }?.value?.isSelected = true
    }

    /** 同步"调试信息"勾选状态（不触发回调）。 */
    fun selectDebugOverlay(enabled: Boolean) {
        debugCheckItem?.isSelected = enabled
    }

    // ── 文件 ──────────────────────────────────────────────────

    private fun buildFileMenu() = Menu("文件(_F)").apply {
        items.addAll(
            MenuItem("打开 ROM(_O)").apply {
                accelerator = KeyCombination.valueOf("Shortcut+O")
                setOnAction { actions.onOpenRom() }
            },
            SeparatorMenuItem(),
            MenuItem("设置(_S)...").apply {
                setOnAction { actions.onOpenSettings() }
            },
            SeparatorMenuItem(),
            MenuItem("退出(_X)").apply {
                accelerator = KeyCombination.valueOf("Shortcut+Q")
                setOnAction { actions.onExit() }
            }
        )
    }

    // ── 模拟 ──────────────────────────────────────────────────

    private fun buildEmulationMenu(): Menu {
        val speedGroup = ToggleGroup()
        val speedMenu = Menu("速度(_S)")
        for (speed in listOf(0.5, 1.0, 2.0, 4.0, 8.0, 16.0)) {
            val item = RadioMenuItem("${speed}x").apply {
                toggleGroup = speedGroup
                isSelected = speed == 1.0
                setOnAction { actions.onSpeedChanged(speed) }
            }
            speedRadios[speed] = item
            speedMenu.items.add(item)
        }

        return Menu("模拟(_E)").apply {
            items.addAll(
                MenuItem("暂停/继续(_P)").apply {
                    setOnAction { actions.onTogglePause() }
                },
                MenuItem("复位(_R)").apply {
                    accelerator = KeyCombination.valueOf("Shortcut+R")
                    setOnAction { actions.onReset() }
                },
                SeparatorMenuItem(),
                speedMenu,
                MenuItem("切换快进").apply {
                    setOnAction { actions.onToggleFastForward() }
                },
                SeparatorMenuItem(),
                buildSaveStateMenu(),
                MenuItem("金手指(_C)...").apply {
                    setOnAction { actions.onManageCheats() }
                },
                MenuItem("核心选项(_O)...").apply {
                    setOnAction { actions.onOpenCoreOptions() }
                }
            )
        }
    }

    /** 即时存档子菜单：10 个可见槽位 + 自动槽位。 */
    private fun buildSaveStateMenu(): Menu {
        val saveSlots = Menu("保存到槽位").apply {
            for (slot in 1..SAVE_SLOT_COUNT) {
                items.add(MenuItem("槽位 $slot").apply {
                    // 传统映射：F1–F10 读取，Shift+F1–F10 保存
                    accelerator = KeyCombination.valueOf("Shift+F$slot")
                    setOnAction { actions.onSaveState(slot - 1) }
                })
            }
        }
        val loadSlots = Menu("读取槽位").apply {
            for (slot in 1..SAVE_SLOT_COUNT) {
                items.add(MenuItem("槽位 $slot").apply {
                    accelerator = KeyCombination.valueOf("F$slot")
                    setOnAction { actions.onLoadState(slot - 1) }
                })
            }
        }
        return Menu("即时存档(_S)").apply {
            items.addAll(
                saveSlots,
                loadSlots,
                SeparatorMenuItem(),
                MenuItem("保存到自动槽位").apply {
                    setOnAction { actions.onSaveAutoState() }
                }
            )
        }
    }

    // ── 音频 ──────────────────────────────────────────────────

    private fun buildAudioMenu(): Menu {
        val volumeGroup = ToggleGroup()
        val volumeMenu = Menu("音量(_V)")
        for (pct in listOf(25, 50, 75, 100)) {
            val item = RadioMenuItem("${pct}%").apply {
                toggleGroup = volumeGroup
                isSelected = pct == 100
                setOnAction { actions.onVolumeChanged(pct / 100f) }
            }
            volumeRadios[pct] = item
            volumeMenu.items.add(item)
        }

        return Menu("音频(_A)").apply {
            items.addAll(
                MenuItem("静音(_M)").apply {
                    accelerator = KeyCombination.valueOf("M")
                    setOnAction { actions.onToggleMute() }
                },
                SeparatorMenuItem(),
                volumeMenu
            )
        }
    }

    // ── 视频 ──────────────────────────────────────────────────

    private fun buildVideoMenu(): Menu {
        val shaderMenu = Menu("着色器(_S)")
        for (preset in ShaderPreset.values()) {
            val item = RadioMenuItem(preset.label()).apply {
                toggleGroup = shaderGroup
                isSelected = preset == ShaderPreset.OFF
                setOnAction { actions.onShaderChanged(preset) }
            }
            shaderRadios[preset] = item
            shaderMenu.items.add(item)
        }
        shaderMenu.items.add(SeparatorMenuItem())
        shaderMenu.items.add(MenuItem("加载自定义着色器...").apply {
            setOnAction { actions.onLoadCustomShader() }
        })

        return Menu("视频(_V)").apply {
            items.addAll(
                MenuItem("全屏(_F)").apply {
                    accelerator = KeyCombination.valueOf("F11")
                    setOnAction { actions.onToggleFullscreen() }
                },
                SeparatorMenuItem(),
                shaderMenu,
                SeparatorMenuItem(),
                CheckMenuItem("调试信息(_I)").apply {
                    accelerator = KeyCombination.valueOf("F3")
                    setOnAction { actions.onToggleDebugOverlay(isSelected) }
                    debugCheckItem = this
                }
            )
        }
    }

    // ── 帮助 ──────────────────────────────────────────────────

    private fun buildHelpMenu() = Menu("帮助(_H)").apply {
        items.add(MenuItem("关于(_A)").apply {
            setOnAction {
                Alert(Alert.AlertType.INFORMATION).apply {
                    title = "关于"
                    headerText = "mGBA Desktop"
                    contentText = "基于 xuan (JavaFX + OpenGL) 的 mGBA 桌面端\n核心: mGBA Libretro\n版本: 1.0-SNAPSHOT"
                }.showAndWait()
            }
        })
    }
}

/** UI 可见的即时存档槽位数（槽位 1–10）。 */
private const val SAVE_SLOT_COUNT = 10

/**
 * 菜单动作回调接口。由 [com.mgba.bingbaihanji.view.XuanMgbaController] 实现。
 */
interface MenuActions {
    fun onOpenRom()
    fun onExit()
    fun onTogglePause()
    fun onReset()
    fun onSpeedChanged(speed: Double)
    fun onToggleFastForward()
    fun onToggleMute()
    fun onVolumeChanged(volume: Float)
    fun onToggleFullscreen()

    /** 保存即时存档；[slot] 为 0 起的槽位号。 */
    fun onSaveState(slot: Int)

    /** 读取即时存档；[slot] 为 0 起的槽位号。 */
    fun onLoadState(slot: Int)

    /** 保存到隐藏的自动槽位。 */
    fun onSaveAutoState()

    /** 打开金手指管理对话框。 */
    fun onManageCheats()

    /** 切换着色器预设。 */
    fun onShaderChanged(preset: ShaderPreset)

    /** 选择并加载自定义 GLSL 片段着色器。 */
    fun onLoadCustomShader()

    /** 开关调试信息浮层。 */
    fun onToggleDebugOverlay(enabled: Boolean)

    /** 打开设置对话框。 */
    fun onOpenSettings()

    /** 打开核心选项对话框。 */
    fun onOpenCoreOptions()
}
