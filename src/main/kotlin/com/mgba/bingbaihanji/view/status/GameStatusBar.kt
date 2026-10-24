package com.mgba.bingbaihanji.view.status

import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority

/**
 * 底部状态栏：显示 ROM 名称、核心信息、速度、分辨率等。
 */
class GameStatusBar : HBox() {

    private val romLabel = Label("未加载 ROM")
    private val coreLabel = Label("")
    private val speedLabel = Label("")
    private val resolutionLabel = Label("")

    init {
        style = "-fx-background-color: #2B2B3D; -fx-padding: 4 12 4 12;"
        spacing = 24.0

        romLabel.style = "-fx-text-fill: #CCCCCC; -fx-font-size: 12px;"
        coreLabel.style = "-fx-text-fill: #888888; -fx-font-size: 12px;"
        speedLabel.style = "-fx-text-fill: #888888; -fx-font-size: 12px;"
        resolutionLabel.style = "-fx-text-fill: #888888; -fx-font-size: 12px;"

        children.addAll(romLabel, coreLabel, speedLabel, resolutionLabel)
        HBox.setHgrow(romLabel, Priority.ALWAYS)
    }

    /** 更新 ROM 名称。 */
    fun setRomName(name: String) {
        romLabel.text = name
    }

    /** 更新核心信息。 */
    fun setCoreInfo(name: String, version: String) {
        coreLabel.text = if (name.isNotEmpty()) "$name $version" else ""
    }

    /** 更新速度显示。 */
    fun setSpeed(speed: Double) {
        speedLabel.text = if (speed != 1.0) "速度: ${speed}x" else ""
    }

    /** 更新分辨率显示。 */
    fun setResolution(width: Int, height: Int) {
        resolutionLabel.text = if (width > 0) "${width}×${height}" else ""
    }
}
