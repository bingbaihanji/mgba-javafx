package com.mgba.bingbaihanji.view.cheat

import com.mgba.bingbaihanji.core.cheat.CheatCodeParser
import com.mgba.bingbaihanji.core.cheat.CheatEntry
import com.mgba.bingbaihanji.core.cheat.CheatFormatException
import com.mgba.bingbaihanji.core.cheat.CheatService
import com.mgba.bingbaihanji.view.theme.UiTheme
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.ColumnConstraints
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Window

/**
 * 金手指管理对话框：列表 + 启用开关 + 添加/删除。
 *
 * <p>界面只负责收集输入与展示，代码解析、持久化和应用到核心都在
 * [CheatService] 里完成。
 */
class CheatDialog(
    private val owner: Window?,
    private val service: CheatService
) {

    /** 列表数据源与 [CheatService.entries] 保持同步。 */
    private val items = FXCollections.observableArrayList(service.entries())

    /** 打开对话框（阻塞到用户关闭）。 */
    fun show() {
        val listView = ListView(items).apply {
            setCellFactory { CheatCell() }
            prefHeight = 320.0
            placeholder = Label("尚未添加金手指")
        }

        val addButton = Button("添加(_A)").apply { setOnAction { onAdd() } }
        val removeButton = Button("删除(_D)").apply {
            setOnAction {
                val selected = listView.selectionModel.selectedItem ?: return@setOnAction
                service.remove(selected)
                items.remove(selected)
                persistAndApply()
            }
        }
        val hint = Label("支持 GameShark / CodeBreaker / Raw；多行代码作为一组生效").apply {
            styleClass.add("hint-label")
        }

        val buttons = HBox(8.0, addButton, removeButton)

        val root = VBox(8.0, listView, buttons, hint).apply {
            padding = Insets(4.0)
        }

        val dialog = Dialog<ButtonType>().apply {
            initOwner(owner)
            title = "金手指"
            headerText = "金手指（修改后自动保存并立即生效）"
            dialogPane.buttonTypes.add(ButtonType.CLOSE)
            dialogPane.content = root
            dialogPane.prefWidth = 680.0
        }
        UiTheme.applyDialog(dialog, UiTheme.ICON_APP)
        dialog.showAndWait()

        // 关闭时再兜底保存一次，防止最后一步切换开关没落盘
        persistAndApply()
    }

    /** 添加流程：输入名称与代码，校验通过后加入列表。 */
    private fun onAdd() {
        val nameField = TextField()
        val codeArea = TextArea().apply {
            prefRowCount = 4
            promptText = "例：820051A0 0063"
        }

        val grid = GridPane().apply {
            hgap = 8.0
            vgap = 8.0
            add(Label("名称"), 0, 0)
            add(nameField, 1, 0)
            add(Label("代码"), 0, 1)
            add(codeArea, 1, 1)
            columnConstraints.addAll(
                ColumnConstraints().apply { minWidth = 56.0 },
                ColumnConstraints().apply {
                    hgrow = Priority.ALWAYS
                    prefWidth = 380.0
                }
            )
        }

        val dialog = Dialog<ButtonType>().apply {
            initOwner(owner)
            title = "添加金手指"
            headerText = "新增一条金手指"
            dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
            dialogPane.content = grid
            dialogPane.prefWidth = 500.0
        }
        UiTheme.applyDialog(dialog, UiTheme.ICON_APP)

        val result = dialog.showAndWait().orElse(ButtonType.CANCEL)
        if (result != ButtonType.OK) {
            return
        }

        try {
            val entry = service.add(nameField.text, codeArea.text)
            items.add(entry)
            persistAndApply()
        } catch (e: CheatFormatException) {
            val alert = Alert(Alert.AlertType.ERROR).apply {
                initOwner(owner)
                title = "格式错误"
                headerText = "金手指格式错误"
                contentText = e.message
            }
            UiTheme.applyAlert(alert)
            alert.showAndWait()
        }
    }

    private fun persistAndApply() {
        service.persist()
        service.apply()
    }

    /** 单元格：复选框 + 名称/代码 + 类型标签。 */
    private inner class CheatCell : ListCell<CheatEntry>() {

        private val check = CheckBox()
        private val nameLabel = Label()
        private val codeLabel = Label()
        private val typeLabel = Label()
        private val spacer = Region()
        private val box = HBox(
            10.0,
            check,
            VBox(2.0, nameLabel, codeLabel),
            spacer,
            typeLabel
        )

        init {
            codeLabel.styleClass.add("cell-subtitle")
            typeLabel.styleClass.add("cell-badge")
            HBox.setHgrow(spacer, Priority.ALWAYS)
            box.padding = Insets(4.0, 8.0, 4.0, 8.0)
            check.setOnAction {
                val entry = item ?: return@setOnAction
                entry.isEnabled = check.isSelected
                persistAndApply()
            }
        }

        override fun updateItem(entry: CheatEntry?, empty: Boolean) {
            super.updateItem(entry, empty)
            if (empty || entry == null) {
                text = null
                graphic = null
                return
            }
            nameLabel.text = entry.name
            codeLabel.text = entry.code.replace("\n", " / ")
            typeLabel.text = try {
                CheatCodeParser.parse(entry.code).type().label()
            } catch (e: CheatFormatException) {
                "无效"
            }
            check.isSelected = entry.isEnabled
            text = null
            graphic = box
        }
    }
}
