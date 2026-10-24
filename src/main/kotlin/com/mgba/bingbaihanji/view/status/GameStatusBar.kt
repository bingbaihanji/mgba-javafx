package com.mgba.bingbaihanji.view.status

import javafx.animation.PauseTransition
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.util.Duration

/**
 * 底部状态栏：显示 ROM 名称、核心信息、速度、分辨率，以及临时提示消息。
 */
class GameStatusBar : HBox() {

    private val romLabel = Label("未加载 ROM")
    private val coreLabel = Label("")
    private val speedLabel = Label("")
    private val resolutionLabel = Label("")
    private val messageLabel = Label("")

    /** 当前提示消息的清除定时器。 */
    private var messageTimer: PauseTransition? = null

    init {
        style = "-fx-background-color: #2B2B3D; -fx-padding: 4 12 4 12;"
        spacing = 24.0

        romLabel.style = "-fx-text-fill: #CCCCCC; -fx-font-size: 12px;"
        coreLabel.style = "-fx-text-fill: #888888; -fx-font-size: 12px;"
        speedLabel.style = "-fx-text-fill: #888888; -fx-font-size: 12px;"
        resolutionLabel.style = "-fx-text-fill: #888888; -fx-font-size: 12px;"
        messageLabel.style = "-fx-text-fill: #7FD18B; -fx-font-size: 12px;"

        children.addAll(messageLabel, romLabel, coreLabel, speedLabel, resolutionLabel)
        HBox.setHgrow(romLabel, Priority.ALWAYS)
    }

    /**
     * 显示一条临时提示（如"已保存到槽位 3"），到时自动清除。
     *
     * @param text    提示文本
     * @param seconds 显示时长（秒）
     */
    fun showMessage(text: String, seconds: Double = 3.0) {
        messageLabel.text = text
        messageTimer?.stop()
        val timer = PauseTransition(Duration.seconds(seconds))
        timer.setOnFinished { messageLabel.text = "" }
        messageTimer = timer
        timer.play()
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
