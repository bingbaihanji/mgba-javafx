package com.mgba.bingbaihanji.view.menu

import com.mgba.bingbaihanji.platform.desktop.video.ShaderPreset
import com.mgba.bingbaihanji.view.debug.DebugCategory
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.input.Clipboard
import javafx.scene.input.DataFormat
import javafx.scene.input.KeyCombination
import javafx.scene.layout.VBox
import java.net.URI

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

    /** 存档槽位菜单项，打开子菜单时刷新文字（显示保存时间/空槽）。 */
    private val saveSlotItems = mutableListOf<MenuItem>()
    private val loadSlotItems = mutableListOf<MenuItem>()

    /** 调试信息分类的勾选项。 */
    private val debugSectionItems = LinkedHashMap<String, CheckMenuItem>()

    /**
     * 槽位标签提供者，由控制器设置。
     *
     * @param slot 0 起的槽位号
     */
    var saveSlotLabel: ((Int) -> String)? = null

    init {
        menus.addAll(
            buildFileMenu(),
            buildEmulationMenu(),
            buildAudioMenu(),
            buildVideoMenu(),
            buildSettingsMenu(),
            buildHelpMenu()
        )
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

    /**
     * 同步调试信息分类的勾选状态（不触发回调）。
     *
     * @param enabled 分类 key → 是否显示；缺省视为显示
     */
    fun applyDebugSections(enabled: Map<String, Boolean>) {
        for ((key, item) in debugSectionItems) {
            item.isSelected = enabled[key] ?: true
        }
    }

    // ── 文件 ──────────────────────────────────────────────────

    private fun buildFileMenu() = Menu("文件(_F)").apply {
        items.addAll(
            MenuItem("打开 ROM(_O)").apply {
                accelerator = KeyCombination.valueOf("Shortcut+O")
                setOnAction { actions.onOpenRom() }
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
                }
            )
        }
    }

    /** 即时存档子菜单：10 个可见槽位 + 自动槽位。 */
    private fun buildSaveStateMenu(): Menu {
        val saveSlots = Menu("保存到槽位").apply {
            for (slot in 1..SAVE_SLOT_COUNT) {
                val item = MenuItem("槽位 $slot").apply {
                    // 传统映射：F1–F10 读取，Shift+F1–F10 保存
                    accelerator = KeyCombination.valueOf("Shift+F$slot")
                    setOnAction { actions.onSaveState(slot - 1) }
                }
                saveSlotItems.add(item)
                items.add(item)
            }
            setOnShowing { refreshSlotLabels() }
        }
        val loadSlots = Menu("读取槽位").apply {
            for (slot in 1..SAVE_SLOT_COUNT) {
                val item = MenuItem("槽位 $slot").apply {
                    accelerator = KeyCombination.valueOf("F$slot")
                    setOnAction { actions.onLoadState(slot - 1) }
                }
                loadSlotItems.add(item)
                items.add(item)
            }
            setOnShowing { refreshSlotLabels() }
        }
        return Menu("即时存档(_S)").apply {
            items.addAll(
                saveSlots,
                loadSlots,
                MenuItem("撤销读档(_U)").apply {
                    accelerator = KeyCombination.valueOf("Ctrl+Z")
                    setOnAction { actions.onUndoLoadState() }
                },
                SeparatorMenuItem(),
                MenuItem("存档管理(_M)...").apply {
                    setOnAction { actions.onOpenSaveManager() }
                },
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
                MenuItem("截图(_T)").apply {
                    accelerator = KeyCombination.valueOf("F12")
                    setOnAction { actions.onTakeScreenshot() }
                },
                SeparatorMenuItem(),
                shaderMenu
            )
        }
    }

    /** 刷新槽位文字：有存档时显示保存时间，空槽显示"（空）"。 */
    private fun refreshSlotLabels() {
        val provider = saveSlotLabel ?: return
        saveSlotItems.forEachIndexed { index, item -> item.text = provider(index) }
        loadSlotItems.forEachIndexed { index, item -> item.text = provider(index) }
    }

    // ── 设置 ──────────────────────────────────────────────────

    private fun buildSettingsMenu(): Menu {
        val debugItem = CheckMenuItem("调试信息(_I)").apply {
            accelerator = KeyCombination.valueOf("F3")
            setOnAction { actions.onToggleDebugOverlay(isSelected) }
        }
        debugCheckItem = debugItem

        val debugSections = Menu("调试信息分类(_D)")
        for (category in DebugCategory.entries) {
            val item = CheckMenuItem(category.label).apply {
                isSelected = true
                setOnAction { actions.onToggleDebugSection(category.key, isSelected) }
            }
            debugSectionItems[category.key] = item
            debugSections.items.add(item)
        }

        return Menu("设置(_S)").apply {
            items.addAll(
                MenuItem("播放设置(_P)...").apply {
                    setOnAction { actions.onOpenSettings() }
                },
                MenuItem("按键映射(_K)...").apply {
                    setOnAction { actions.onOpenKeyMapping() }
                },
                MenuItem("核心选项(_O)...").apply {
                    setOnAction { actions.onOpenCoreOptions() }
                },
                SeparatorMenuItem(),
                debugItem,
                debugSections
            )
        }
    }

    // ── 帮助 ──────────────────────────────────────────────────

    private fun buildHelpMenu() = Menu("帮助(_H)").apply {
        items.add(MenuItem("关于(_A)").apply {
            setOnAction { showAboutDialog() }
        })
    }

    /** 关于对话框：版本信息与相关仓库链接。 */
    private fun showAboutDialog() {
        val links = listOf(
            "本项目" to "https://github.com/bingbaihanji/mgba-javafx",
            "mGBA" to "https://github.com/mgba-emu/mgba",
            "mGBA Libretro" to "https://github.com/libretro/mgba"
        )

        val content = VBox(6.0).apply {
            padding = Insets(12.0, 16.0, 8.0, 16.0)
            children.addAll(
                Label("基于 xuan (JavaFX + OpenGL) 的 mGBA 桌面端"),
                Label("核心: mGBA Libretro"),
                Label("版本: 1.0-SNAPSHOT"),
                Separator(),
                Label("相关仓库")
            )
            links.forEach { (name, url) ->
                children += Hyperlink("$name  $url").apply {
                    tooltip = Tooltip("在浏览器中打开 $url")
                    setOnAction { openUrl(url) }
                }
            }
        }

        Alert(Alert.AlertType.INFORMATION).apply {
            title = "关于"
            headerText = "mGBA javafx"
            dialogPane.content = content
        }.showAndWait()
    }

    /** 用系统默认浏览器打开链接；打不开时至少复制到剪贴板，方便手动访问。 */
    private fun openUrl(url: String) {
        try {
            java.awt.Desktop.getDesktop().browse(URI(url))
        } catch (_: Exception) {
            Clipboard.getSystemClipboard().setContent(mapOf(DataFormat.PLAIN_TEXT to url))
        }
    }
}

/** UI 可见的即时存档槽位数（与原版 mGBA 的 1–9 槽一致）。 */
private const val SAVE_SLOT_COUNT = 9

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

    /** 打开带缩略图的存档管理对话框。 */
    fun onOpenSaveManager()

    /** 撤销上一次读档，恢复到读档前的状态。 */
    fun onUndoLoadState()

    /** 打开金手指管理对话框。 */
    fun onManageCheats()

    /** 切换着色器预设。 */
    fun onShaderChanged(preset: ShaderPreset)

    /** 选择并加载自定义 GLSL 片段着色器。 */
    fun onLoadCustomShader()

    /** 保存当前画面为 PNG 截图。 */
    fun onTakeScreenshot()

    /** 开关调试信息浮层。 */
    fun onToggleDebugOverlay(enabled: Boolean)

    /** 开关某一类调试信息。 */
    fun onToggleDebugSection(key: String, enabled: Boolean)

    /** 打开设置对话框。 */
    fun onOpenSettings()

    /** 打开按键映射对话框。 */
    fun onOpenKeyMapping()

    /** 打开核心选项对话框。 */
    fun onOpenCoreOptions()
}
