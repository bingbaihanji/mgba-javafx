package com.mgba.bingbaihanji.view.settings

import com.mgba.bingbaihanji.core.settings.AppSettings
import com.mgba.bingbaihanji.platform.desktop.video.ShaderPreset
import com.mgba.bingbaihanji.view.theme.UiTheme
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.layout.ColumnConstraints
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.stage.Window
import javafx.util.StringConverter

/**
 * 设置对话框：倍速、音频、显示与调试开关。
 *
 * <p>只在用户点"确定"时回传修改后的副本；取消不改动当前设置。
 */
class SettingsDialog(
    private val owner: Window?,
    private val current: AppSettings,
    private val onApply: (AppSettings) -> Unit
) {

    private val speedBox = ComboBox(
        FXCollections.observableArrayList(0.5, 1.0, 2.0, 4.0, 8.0, 16.0)
    ).apply {
        converter = object : StringConverter<Double>() {
            override fun toString(value: Double?): String = "${value}x"
            override fun fromString(string: String?): Double =
                string?.removeSuffix("x")?.toDoubleOrNull() ?: 1.0
        }
        value = nearestSpeed(current.gameSpeed)
    }

    private val volumeSlider = Slider(0.0, 100.0, current.soundVolume.toDouble()).apply {
        blockIncrement = 5.0
        majorTickUnit = 25.0
        isShowTickMarks = true
        prefWidth = 280.0
        maxWidth = Double.MAX_VALUE
    }

    private val volumeLabel = Label("${current.soundVolume}%").apply {
        minWidth = 42.0
    }

    private val soundCheck = CheckBox("启用声音").apply { isSelected = current.enableSound }

    private val stretchCheck = CheckBox("拉伸填充（关闭时等比缩放并留黑边）").apply {
        isSelected = current.stretchToFit
    }

    private val linearCheck = CheckBox("线性过滤（关闭后像素边缘更锐利）").apply {
        isSelected = current.linearFiltering
    }

    private val integerCheck = CheckBox("整数缩放（像素格整齐，窗口足够大时才生效）").apply {
        isSelected = current.integerScaling
    }

    private val autoSaveCheck = CheckBox("退出时保存到自动槽位").apply {
        isSelected = current.autoSaveOnExit
    }

    private val debugCheck = CheckBox("显示调试信息浮层").apply {
        isSelected = current.showDebugOverlay
    }

    private val shaderBox = ComboBox(FXCollections.observableArrayList(*ShaderPreset.values())).apply {
        converter = object : StringConverter<ShaderPreset>() {
            override fun toString(value: ShaderPreset?): String = value?.label() ?: ""
            override fun fromString(string: String?): ShaderPreset = ShaderPreset.OFF
        }
        value = runCatching { ShaderPreset.valueOf(current.shaderPreset) }
            .getOrDefault(ShaderPreset.OFF)
    }

    init {
        volumeSlider.valueProperty().addListener { _, _, value ->
            volumeLabel.text = "${value.toInt()}%"
        }
    }

    /** 打开对话框（阻塞到用户关闭）。 */
    fun show() {
        val grid = GridPane().apply {
            hgap = 14.0
            vgap = 8.0
            padding = Insets(4.0)
            columnConstraints.addAll(
                ColumnConstraints().apply { minWidth = 88.0 },
                ColumnConstraints().apply {
                    hgrow = Priority.ALWAYS
                    prefWidth = 320.0
                }
            )
        }

        // 按功能分组，避免一长串选项从上排到下
        var row = 0
        fun section(text: String) {
            grid.add(Label(text).apply { styleClass.add("section-title") }, 0, row++, 2, 1)
        }

        fun field(text: String, node: Node) {
            grid.add(Label(text).apply { styleClass.add("field-label") }, 0, row)
            grid.add(node, 1, row)
            row++
        }

        section("模拟")
        field("倍速", speedBox)

        section("音频")
        field(
            "音量",
            HBox(8.0, volumeSlider, volumeLabel).apply {
                alignment = Pos.CENTER_LEFT
                HBox.setHgrow(volumeSlider, Priority.ALWAYS)
            }
        )
        field("声音", soundCheck)

        section("画面")
        field("显示", stretchCheck)
        field("过滤", linearCheck)
        field("缩放", integerCheck)
        field("着色器", shaderBox)

        section("存档与调试")
        field("存档", autoSaveCheck)
        field("调试", debugCheck)

        val dialog = Dialog<ButtonType>().apply {
            initOwner(owner)
            title = "设置"
            headerText = "调整模拟速度、声音与画面显示"
            dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
            dialogPane.content = grid
            dialogPane.prefWidth = 520.0
        }
        UiTheme.applyDialog(dialog, UiTheme.ICON_SETTINGS)

        val result = dialog.showAndWait().orElse(ButtonType.CANCEL)
        if (result != ButtonType.OK) {
            return
        }

        val updated = current.copy()
        updated.gameSpeed = speedBox.value ?: current.gameSpeed
        updated.soundVolume = volumeSlider.value.toInt()
        updated.enableSound = soundCheck.isSelected
        updated.stretchToFit = stretchCheck.isSelected
        updated.linearFiltering = linearCheck.isSelected
        updated.integerScaling = integerCheck.isSelected
        updated.autoSaveOnExit = autoSaveCheck.isSelected
        updated.showDebugOverlay = debugCheck.isSelected
        updated.shaderPreset = (shaderBox.value ?: ShaderPreset.OFF).name
        onApply(updated)
    }

    /** 把任意倍速吸附到下拉框里最接近的选项。 */
    private fun nearestSpeed(speed: Double): Double =
        listOf(0.5, 1.0, 2.0, 4.0, 8.0, 16.0).minByOrNull { kotlin.math.abs(it - speed) } ?: 1.0
}
