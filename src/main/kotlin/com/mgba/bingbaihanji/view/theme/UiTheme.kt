package com.mgba.bingbaihanji.view.theme

import javafx.scene.Parent
import javafx.scene.control.Alert
import javafx.scene.control.Dialog
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.stage.Stage

/**
 * 桌面端统一的深色界面主题。
 *
 * <p>JavaFX 默认对话框是浅色的，和 GL 画面的深色背景放在一起很突兀。
 * 这里把同一份样式表挂到每个 [javafx.scene.control.DialogPane] 上，并顺带设置
 * 标题栏与标题区的图标；设置 / 存档 / 关于使用 resources/image 下各自的图标，
 * 其余对话框统一用项目 logo。
 */
object UiTheme {

    /** 项目 logo，作为通用窗口图标。 */
    const val ICON_APP = "/image/logo.png"

    /** 设置对话框图标。 */
    const val ICON_SETTINGS = "/image/setting.png"

    /** 存档管理图标。 */
    const val ICON_SAVE = "/image/save.png"

    /** 关于对话框图标。 */
    const val ICON_ABOUT = "/image/about.png"

    private const val STYLESHEET = "/styles/theme.css"
    private const val DIALOG_STYLE_CLASS = "app-dialog"

    /** 主题样式表地址；资源缺失时为 null（此时至少保证功能可用）。 */
    private val stylesheetUrl: String? by lazy {
        UiTheme::class.java.getResource(STYLESHEET)?.toExternalForm()
    }

    /** 把主题样式挂到任意容器上（例如启动占位页）。 */
    fun applyStyleSheet(parent: Parent) {
        val url = stylesheetUrl ?: return
        if (url !in parent.stylesheets) {
            parent.stylesheets.add(url)
        }
    }

    /**
     * 给对话框套用深色主题与图标。
     *
     * @param dialog   任意 JavaFX 对话框（[Alert] 也适用）
     * @param iconPath resources 下的图标路径；默认使用项目 logo
     */
    fun applyDialog(dialog: Dialog<*>, iconPath: String = ICON_APP) {
        val pane = dialog.dialogPane
        if (DIALOG_STYLE_CLASS !in pane.styleClass) {
            pane.styleClass.add(DIALOG_STYLE_CLASS)
        }
        applyStyleSheet(pane)
        pane.graphic = headerIcon(iconPath)
        dialog.setOnShown {
            val stage = pane.scene?.window as? Stage ?: return@setOnShown
            stageIcon(iconPath)?.let { stage.icons.add(it) }
        }
    }

    /** [applyDialog] 的 Alert 版本。 */
    fun applyAlert(alert: Alert, iconPath: String = ICON_APP) = applyDialog(alert, iconPath)

    /** 标题区左侧的小图标。 */
    private fun headerIcon(path: String): ImageView? {
        val image = load(path, HEADER_ICON_SIZE * 2) ?: return null
        return ImageView(image).apply {
            fitWidth = HEADER_ICON_SIZE
            fitHeight = HEADER_ICON_SIZE
            isPreserveRatio = true
            isSmooth = true
        }
    }

    /** 标题栏 / 任务栏图标。 */
    private fun stageIcon(path: String): Image? = load(path, 64.0)

    /** 按资源路径加载图片；图片不存在时返回 null，不抛异常打断对话框。 */
    private fun load(path: String, requestedSize: Double): Image? {
        val url = UiTheme::class.java.getResource(path) ?: return null
        return Image(url.toExternalForm(), requestedSize, requestedSize, true, true, false)
    }

    private const val HEADER_ICON_SIZE = 40.0
}
