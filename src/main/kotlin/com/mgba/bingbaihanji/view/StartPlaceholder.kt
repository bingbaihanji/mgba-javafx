package com.mgba.bingbaihanji.view

import com.mgba.bingbaihanji.view.theme.UiTheme
import javafx.beans.binding.Bindings
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.effect.DropShadow
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color

/**
 * 未加载 ROM 时显示在画面中央的占位页。
 *
 * <p>底层 GL 画布在无帧可画时只有纯色背景，看起来像"黑窗口"。
 * 这里用项目 logo + 一句提示 + 打开按钮占位，加载 ROM 后由控制器隐藏。
 */
class StartPlaceholder(onOpenRom: () -> Unit) : StackPane() {

    /** 占位图。按目标宽度预缩放，避免把 1760×1280 的原图整幅留在内存里。 */
    private val logo = ImageView(
        Image(
            StartPlaceholder::class.java.getResource("/image/logo.png")!!.toExternalForm(),
            720.0, 0.0, true, true, false
        )
    ).apply {
        isPreserveRatio = true
        isSmooth = true
        effect = DropShadow(32.0, Color.web("#6C5CE7", 0.55))
    }

    private val title = Label("尚未加载 ROM").apply {
        styleClass.add("placeholder-title")
        isWrapText = true
        alignment = Pos.CENTER
    }

    private val hint = Label("在「文件 → 打开 ROM」中选择 Game Boy / Game Boy Advance 游戏").apply {
        styleClass.add("placeholder-hint")
        isWrapText = true
        alignment = Pos.CENTER
    }

    private val openButton = Button("打开 ROM…").apply {
        styleClass.add("placeholder-button")
        setOnAction { onOpenRom() }
    }

    private val card = VBox(12.0, logo, title, hint, openButton).apply {
        alignment = Pos.CENTER
        // 只让卡片本身接收鼠标，卡片外的空白仍然透给下面的画布
        isPickOnBounds = false
        maxWidth = 560.0
        VBox.setMargin(openButton, Insets(10.0, 0.0, 0.0, 0.0))
    }

    init {
        styleClass.add("start-placeholder")
        UiTheme.applyStyleSheet(this)
        isPickOnBounds = false

        children.add(card)
        StackPane.setAlignment(card, Pos.CENTER)

        // 窗口变窄或变矮时按比例缩小 logo，避免占位页被裁切
        val available = Bindings.min(
            widthProperty().multiply(0.42),
            heightProperty().multiply(0.50)
        )
        logo.fitWidthProperty().bind(Bindings.max(140.0, Bindings.min(340.0, available)))
    }
}
