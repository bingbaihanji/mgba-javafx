package com.mgba.bingbaihanji.view.menu

import javafx.scene.control.*
import javafx.scene.input.KeyCombination

/**
 * 游戏菜单栏：文件 / 模拟 / 音频 / 视频 / 帮助。
 *
 * 通过 [MenuActions] 回调接口与控制器通信，不直接依赖模拟器状态。
 */
class GameMenuBar(private val actions: MenuActions) : MenuBar() {

    init {
        menus.addAll(buildFileMenu(), buildEmulationMenu(), buildAudioMenu(), buildVideoMenu(), buildHelpMenu())
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
            speedMenu.items.add(RadioMenuItem("${speed}x").apply {
                toggleGroup = speedGroup
                isSelected = speed == 4.0
                setOnAction { actions.onSpeedChanged(speed) }
            })
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
                buildSaveStateMenu()
            )
        }
    }

    /** 即时存档子菜单：10 个可见槽位 + 自动槽位。 */
    private fun buildSaveStateMenu(): Menu {
        val saveSlots = Menu("保存到槽位").apply {
            for (slot in 1..SAVE_SLOT_COUNT) {
                items.add(MenuItem("槽位 $slot").apply {
                    setOnAction { actions.onSaveState(slot - 1) }
                })
            }
        }
        val loadSlots = Menu("读取槽位").apply {
            for (slot in 1..SAVE_SLOT_COUNT) {
                items.add(MenuItem("槽位 $slot").apply {
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
            volumeMenu.items.add(RadioMenuItem("${pct}%").apply {
                toggleGroup = volumeGroup
                isSelected = pct == 100
                setOnAction { actions.onVolumeChanged(pct / 100f) }
            })
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

    private fun buildVideoMenu() = Menu("视频(_V)").apply {
        items.addAll(
            MenuItem("全屏(_F)").apply {
                accelerator = KeyCombination.valueOf("F11")
                setOnAction { actions.onToggleFullscreen() }
            }
        )
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
}
