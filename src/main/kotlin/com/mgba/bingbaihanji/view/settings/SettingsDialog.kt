package com.mgba.bingbaihanji.view.settings

import com.mgba.bingbaihanji.core.settings.AppSettings
import com.mgba.bingbaihanji.platform.desktop.video.ShaderPreset
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.Slider
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
        prefWidth = 240.0
    }

    private val volumeLabel = Label("${current.soundVolume}%")

    private val soundCheck = CheckBox("启用声音").apply { isSelected = current.enableSound }

    private val stretchCheck = CheckBox("拉伸填充（关闭时等比缩放并留黑边）").apply {
        isSelected = current.stretchToFit
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
            hgap = 12.0
            vgap = 12.0
            padding = Insets(8.0)
            add(Label("模拟倍速"), 0, 0)
            add(speedBox, 1, 0)
            add(Label("音量"), 0, 1)
            add(HBox(8.0, volumeSlider, volumeLabel), 1, 1)
            add(Label("声音"), 0, 2)
            add(soundCheck, 1, 2)
            add(Label("显示"), 0, 3)
            add(stretchCheck, 1, 3)
            add(Label("着色器"), 0, 4)
            add(shaderBox, 1, 4)
            add(Label("存档"), 0, 5)
            add(autoSaveCheck, 1, 5)
            add(Label("调试"), 0, 6)
            add(debugCheck, 1, 6)
            columnConstraints.addAll(
                javafx.scene.layout.ColumnConstraints().apply { minWidth = 72.0 },
                javafx.scene.layout.ColumnConstraints().apply {
                    hgrow = Priority.ALWAYS
                    prefWidth = 300.0
                }
            )
        }

        val result = Dialog<ButtonType>().apply {
            initOwner(owner)
            title = "设置"
            dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
            dialogPane.content = grid
            dialogPane.prefWidth = 460.0
        }.showAndWait().orElse(ButtonType.CANCEL)

        if (result != ButtonType.OK) {
            return
        }

        val updated = current.copy()
        updated.gameSpeed = speedBox.value ?: current.gameSpeed
        updated.soundVolume = volumeSlider.value.toInt()
        updated.enableSound = soundCheck.isSelected
        updated.stretchToFit = stretchCheck.isSelected
        updated.autoSaveOnExit = autoSaveCheck.isSelected
        updated.showDebugOverlay = debugCheck.isSelected
        updated.shaderPreset = (shaderBox.value ?: ShaderPreset.OFF).name
        onApply(updated)
    }

    /** 把任意倍速吸附到下拉框里最接近的选项。 */
    private fun nearestSpeed(speed: Double): Double =
        listOf(0.5, 1.0, 2.0, 4.0, 8.0, 16.0).minByOrNull { kotlin.math.abs(it - speed) } ?: 1.0
}
