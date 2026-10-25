package com.mgba.bingbaihanji.view.save

import com.mgba.bingbaihanji.core.save.GameSaveSession
import com.mgba.bingbaihanji.core.save.SaveStateStore
import com.mgba.bingbaihanji.view.theme.UiTheme
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.Window
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 存档管理对话框：10 个槽位，每个槽位显示缩略图、保存时间，并可保存/读取/删除。
 *
 * <p>缩略图在保存即时存档时由核心当前帧生成（见 `GameSaveSession.saveState`），
 * 本类只负责展示；槽位文件与缩略图的读写全部在 Java 侧完成。
 */
class SaveSlotsDialog(
    private val owner: Window?,
    private val session: GameSaveSession,
    private val onMessage: (String) -> Unit,
    private val onLoaded: () -> Unit = {}
) {

    private val timeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    /** 普通槽位 1–9，外加隐藏的自动槽位。 */
    private val slots = FXCollections.observableArrayList(
        (0 until SaveStateStore.SLOT_COUNT).toList() + SaveStateStore.AUTO_SLOT
    )

    private lateinit var listView: ListView<Int>
    private lateinit var dialog: Dialog<ButtonType>

    /** 打开对话框（阻塞到用户关闭）。 */
    fun show() {
        listView = ListView(slots).apply {
            setCellFactory { SlotCell() }
            prefWidth = 580.0
            prefHeight = 460.0
        }

        dialog = Dialog<ButtonType>().apply {
            initOwner(owner)
            title = "存档管理"
            headerText = "保存时会自动记录当前画面作为缩略图"
            dialogPane.buttonTypes.add(ButtonType.CLOSE)
            dialogPane.content = listView
            dialogPane.prefWidth = 660.0
            isResizable = true
        }
        UiTheme.applyDialog(dialog, UiTheme.ICON_SAVE)
        dialog.showAndWait()
    }

    // ── 操作 ──────────────────────────────────────────────────

    private fun save(slot: Int) {
        val result = session.saveState(slot)
        onMessage(
            if (result.ok()) "已保存到槽位 ${slot + 1}"
            else "保存失败：${result.description()}"
        )
        listView.refresh()
    }

    private fun load(slot: Int) {
        val result = session.loadState(slot)
        onMessage(
            if (result.ok()) "已读取槽位 ${slot + 1}"
            else "读取失败：${result.description()}"
        )
        if (result.ok()) {
            onLoaded()
            dialog.close()
        } else {
            listView.refresh()
        }
    }

    private fun delete(slot: Int) {
        val alert = Alert(Alert.AlertType.CONFIRMATION).apply {
            initOwner(owner)
            title = "删除存档"
            headerText = "删除槽位 ${slot + 1}？"
            contentText = "存档与缩略图会一起删除，且无法恢复。"
        }
        UiTheme.applyAlert(alert, UiTheme.ICON_SAVE)
        val confirmation = alert.showAndWait().orElse(ButtonType.CANCEL)
        if (confirmation != ButtonType.OK) {
            return
        }
        val result = session.deleteState(slot)
        onMessage(
            if (result.ok()) "已删除槽位 ${slot + 1}"
            else "删除失败：${result.description()}"
        )
        listView.refresh()
    }

    private fun thumbnail(slot: Int): Image? {
        val file = session.thumbnailFile(slot).toFile()
        if (!file.isFile) {
            return null
        }
        return Image(file.toURI().toString(), 160.0, 120.0, true, true, false)
    }

    // ── 单元格 ────────────────────────────────────────────────

    private inner class SlotCell : ListCell<Int>() {

        private val preview = ImageView().apply {
            fitWidth = 160.0
            fitHeight = 120.0
            isPreserveRatio = true
        }

        private val placeholder = Label("无预览").apply {
            styleClass.add("hint-label")
        }

        private val previewBox = StackPane(placeholder, preview).apply {
            prefWidth = 160.0
            prefHeight = 120.0
            minWidth = 160.0
            minHeight = 120.0
            styleClass.add("slot-preview")
        }

        private val titleLabel = Label().apply {
            styleClass.add("cell-title")
        }

        private val timeLabel = Label().apply {
            styleClass.add("cell-time")
        }

        private val saveButton = Button("保存").apply { styleClass.add("slot-action") }
        private val loadButton = Button("读取").apply { styleClass.add("slot-action") }
        private val deleteButton = Button("删除").apply {
            styleClass.addAll("slot-action", "danger")
        }

        private val details = VBox(4.0, titleLabel, timeLabel)

        private val actions = HBox(6.0, saveButton, loadButton, deleteButton)

        private val row = HBox(12.0, previewBox, details, actions).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(6.0)
        }

        init {
            HBox.setHgrow(details, Priority.ALWAYS)
            saveButton.setOnAction { item?.let { save(it) } }
            loadButton.setOnAction { item?.let { load(it) } }
            deleteButton.setOnAction { item?.let { delete(it) } }
        }

        override fun updateItem(slot: Int?, empty: Boolean) {
            super.updateItem(slot, empty)
            if (empty || slot == null) {
                text = null
                graphic = null
                return
            }

            val exists = session.hasState(slot)
            titleLabel.text = if (slot == SaveStateStore.AUTO_SLOT) "自动槽位" else "槽位 ${slot + 1}"
            timeLabel.text = if (exists) {
                "保存于 " + timeFormat.format(Instant.ofEpochMilli(session.stateTimestamp(slot)))
            } else {
                "空"
            }
            preview.image = if (exists) thumbnail(slot) else null
            placeholder.isVisible = preview.image == null
            loadButton.isDisable = !exists
            deleteButton.isDisable = !exists

            text = null
            graphic = row
        }
    }
}
