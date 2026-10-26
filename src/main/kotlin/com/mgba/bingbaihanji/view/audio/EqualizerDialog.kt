package com.mgba.bingbaihanji.view.audio

import com.mgba.bingbaihanji.core.audio.GraphicEqualizer
import com.mgba.bingbaihanji.core.audio.spectrum.SpectrumAnalyzer
import com.mgba.bingbaihanji.core.audio.spectrum.SpectrumTubeView
import com.mgba.bingbaihanji.core.settings.AppSettings
import com.mgba.bingbaihanji.view.theme.UiTheme
import javafx.animation.AnimationTimer
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.Window

/**
 * 均衡器对话框：前置增益 + 10 个频段滑杆，调整过程**实时生效**。
 *
 * <p>滑杆一动就写进 [GraphicEqualizer]，声卡上的声音立刻变；
 * 「确定」把当前曲线写回设置并持久化，「取消」把均衡器恢复成打开时的样子。
 * 顶部用 [SpectrumTubeView] 显示实时频谱，方便对照着调。
 */
class EqualizerDialog(
    private val owner: Window?,
    private val equalizer: GraphicEqualizer,
    private val analyzer: SpectrumAnalyzer?,
    private val current: AppSettings,
    private val onApply: (AppSettings) -> Unit
) {

    /** 打开时的参数快照，「取消」时原样恢复。 */
    private val snapshotEnabled = equalizer.isEnabled()
    private val snapshotPreamp = equalizer.preampDb
    private val snapshotBands = equalizer.bandGainsDb

    private val enableCheck = CheckBox("启用均衡器").apply {
        isSelected = equalizer.isEnabled()
    }

    private val preampSlider = Slider(
        GraphicEqualizer.MIN_GAIN_DB, GraphicEqualizer.MAX_GAIN_DB, equalizer.preampDb
    ).apply {
        blockIncrement = 1.0
        majorTickUnit = 6.0
        isShowTickMarks = true
        prefWidth = 260.0
        maxWidth = Double.MAX_VALUE
    }

    private val preampLabel = Label(formatDb(equalizer.preampDb)).apply {
        minWidth = 56.0
        alignment = Pos.CENTER_RIGHT
    }

    private val bandSliders = ArrayList<Slider>(GraphicEqualizer.BAND_COUNT)
    private val bandLabels = ArrayList<Label>(GraphicEqualizer.BAND_COUNT)

    /** 频谱预览（18.5:1 平铺试管）。 */
    private val preview = SpectrumTubeView(PREVIEW_WIDTH, PREVIEW_WIDTH / 18.5)

    private var previewTimer: AnimationTimer? = null

    fun show() {
        val bandsBox = buildBandsBox()
        val content = VBox(12.0).apply {
            padding = Insets(4.0, 4.0, 2.0, 4.0)
            children.addAll(
                enableCheck,
                buildPreviewBox(),
                buildPreampRow(),
                Label("频段增益（dB）").apply { styleClass.add("section-title") },
                bandsBox,
                Label("提示：滑杆调整实时生效，「确定」保存曲线，「取消」放弃改动。").apply {
                    styleClass.add("hint-label")
                }
            )
        }

        val dialog = Dialog<ButtonType>().apply {
            initOwner(owner)
            title = "均衡器"
            headerText = "音频均衡器"
            dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
            dialogPane.content = content
            dialogPane.prefWidth = 780.0
        }
        UiTheme.applyDialog(dialog, UiTheme.ICON_SETTINGS)

        // 滑杆实时写入均衡器；确定/取消再分别决定持久化或回滚
        enableCheck.selectedProperty().addListener { _, _, selected ->
            equalizer.isEnabled = selected
            setBandsDisabled(!selected)
        }
        setBandsDisabled(!enableCheck.isSelected)

        preampSlider.valueProperty().addListener { _, _, value ->
            val db = value.toDouble()
            equalizer.preampDb = db
            preampLabel.text = formatDb(db)
        }
        bandSliders.forEachIndexed { index, slider ->
            slider.valueProperty().addListener { _, _, value ->
                val db = value.toDouble()
                equalizer.setBandGainDb(index, db)
                bandLabels[index].text = formatDb(db)
            }
        }

        startPreview()

        val result = dialog.showAndWait()
        stopPreview()
        preview.dispose()

        if (result.isPresent && result.get() == ButtonType.OK) {
            val updated = current.copy()
            updated.eqEnabled = enableCheck.isSelected
            updated.eqPreampDb = preampSlider.value
            updated.eqBandGainsDb = bandSliders.map { it.value }
            onApply(updated)
        } else {
            // 取消：均衡器回到打开时的曲线
            equalizer.isEnabled = snapshotEnabled
            equalizer.preampDb = snapshotPreamp
            equalizer.setBandGainsDb(snapshotBands)
        }
    }

    // ── 构建 ──────────────────────────────────────────────────

    private fun buildPreviewBox(): Region {
        // SpectrumTubeView 已按 18.5:1 固定 pref 尺寸，交给 HBox 摆放即可
        return HBox(preview).apply {
            alignment = Pos.CENTER
            style = "-fx-background-color: #171C23; -fx-background-radius: 4;"
        }
    }

    private fun buildPreampRow() = HBox(12.0).apply {
        alignment = Pos.CENTER_LEFT
        children.addAll(
            Label("前置增益").apply {
                minWidth = 72.0
                styleClass.add("field-label")
            },
            preampSlider,
            preampLabel
        )
        HBox.setHgrow(preampSlider, Priority.ALWAYS)
    }

    private fun buildBandsBox(): GridPane {
        val grid = GridPane().apply {
            hgap = 8.0
            vgap = 4.0
        }
        for (band in 0 until GraphicEqualizer.BAND_COUNT) {
            val gain = snapshotBands.getOrElse(band) { 0.0 }
            val slider = Slider(
                GraphicEqualizer.MIN_GAIN_DB, GraphicEqualizer.MAX_GAIN_DB, gain
            ).apply {
                orientation = Orientation.VERTICAL
                prefHeight = 132.0
                maxHeight = 132.0
                blockIncrement = 1.0
                majorTickUnit = 6.0
                isShowTickMarks = true
                tooltip = Tooltip("${freqLabel(band)} Hz")
            }
            val valueLabel = Label(formatDb(gain)).apply {
                style = "-fx-font-size: 10px; -fx-text-fill: #888888;"
                alignment = Pos.CENTER
                maxWidth = Double.MAX_VALUE
            }
            val freqLabel = Label(freqLabel(band)).apply {
                style = "-fx-font-size: 11px; -fx-text-fill: #CCCCCC;"
                alignment = Pos.CENTER
                maxWidth = Double.MAX_VALUE
            }
            bandSliders.add(slider)
            bandLabels.add(valueLabel)

            val column = VBox(2.0).apply {
                alignment = Pos.CENTER
                children.addAll(valueLabel, slider, freqLabel)
            }
            grid.add(column, band, 0)
            grid.columnConstraints.add(
                ColumnConstraints().apply {
                    hgrow = Priority.ALWAYS
                    minWidth = 48.0
                    halignment = javafx.geometry.HPos.CENTER
                }
            )
        }
        return grid
    }

    private fun setBandsDisabled(disabled: Boolean) {
        preampSlider.isDisable = disabled
        bandSliders.forEach { it.isDisable = disabled }
    }

    // ── 频谱预览 ──────────────────────────────────────────────

    private fun startPreview() {
        val source = analyzer ?: return
        val scratch = FloatArray(SpectrumAnalyzer.BAND_COUNT)
        val timer = object : AnimationTimer() {
            override fun handle(now: Long) {
                source.copyMagnitudes(scratch)
                preview.updateSpectrum(scratch)
            }
        }
        previewTimer = timer
        timer.start()
        preview.start()
    }

    private fun stopPreview() {
        previewTimer?.stop()
        previewTimer = null
    }

    private companion object {
        const val PREVIEW_WIDTH = 720.0

        fun formatDb(value: Double): String {
            val rounded = kotlin.math.round(value).toInt()
            return if (rounded > 0) "+$rounded dB" else "$rounded dB"
        }

        fun freqLabel(band: Int): String {
            val hz = GraphicEqualizer.BAND_FREQUENCIES_HZ[band]
            return if (hz >= 1000) "${(hz / 1000).toInt()}k" else "${hz.toInt()}"
        }
    }
}
