package com.mgba.bingbaihanji.view.option

import com.mgba.bingbaihanji.core.option.CoreOption
import com.mgba.bingbaihanji.view.theme.UiTheme
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.layout.ColumnConstraints
import javafx.scene.layout.GridPane
import javafx.scene.layout.Priority
import javafx.stage.Window

/**
 * 核心选项对话框：把核心注册的选项（色彩校正、帧混合、GB 机型等）做成下拉框。
 *
 * <p>修改在点"确定"后通过 [onApply] 回传，参数是「选项下标 → 值下标」。
 */
class CoreOptionsDialog(
    private val owner: Window?,
    private val options: List<CoreOption>,
    private val onApply: (Map<Int, Int>) -> Unit
) {

    /** 每个选项对应的下拉框，确认时按下标取值。 */
    private val boxes = mutableMapOf<Int, ComboBox<String>>()

    /** 打开对话框（阻塞到用户关闭）。 */
    fun show() {
        val grid = GridPane().apply {
            hgap = 12.0
            vgap = 10.0
            padding = Insets(8.0)
            columnConstraints.addAll(
                ColumnConstraints().apply { minWidth = 160.0 },
                ColumnConstraints().apply {
                    hgrow = Priority.ALWAYS
                    prefWidth = 240.0
                }
            )
        }

        options.forEachIndexed { row, option ->
            val box = ComboBox(FXCollections.observableArrayList(option.values())).apply {
                selectionModel.select(option.current().coerceIn(0, (option.values().size - 1).coerceAtLeast(0)))
                prefWidth = 240.0
            }
            boxes[option.index()] = box
            grid.add(Label(option.displayName()), 0, row)
            grid.add(box, 1, row)
        }

        val scroll = ScrollPane(grid).apply {
            isFitToWidth = true
            prefViewportHeight = 360.0
            prefViewportWidth = 440.0
        }

        val hint = Label("部分选项（如 GB 机型）需要重新打开 ROM 才会完全生效").apply {
            styleClass.add("hint-label")
            isWrapText = true
        }

        val dialog = Dialog<ButtonType>().apply {
            initOwner(owner)
            title = "核心选项"
            headerText = "核心提供的可调参数"
            dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
            dialogPane.content = javafx.scene.layout.VBox(8.0, scroll, hint).apply { padding = Insets(4.0) }
            dialogPane.prefWidth = 580.0
        }
        UiTheme.applyDialog(dialog, UiTheme.ICON_SETTINGS)

        val result = dialog.showAndWait().orElse(ButtonType.CANCEL)

        if (result != ButtonType.OK) {
            return
        }

        val selected = boxes.mapNotNull { (index, box) ->
            val value = box.selectionModel.selectedIndex
            if (value >= 0) index to value else null
        }.toMap()
        onApply(selected)
    }
}
