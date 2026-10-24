package com.mgba.bingbaihanji.view.save

import com.mgba.bingbaihanji.core.save.GameSaveSession
import com.mgba.bingbaihanji.core.save.SaveStateStore
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
    private val onMessage: (String) -> Unit
) {

    private val timeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    private val slots = FXCollections.observableArrayList(
        (0 until SaveStateStore.SLOT_COUNT).toList()
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
            dialogPane.prefWidth = 640.0
        }
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
            dialog.close()
        } else {
            listView.refresh()
        }
    }

    private fun delete(slot: Int) {
        val confirmation = Alert(
            Alert.AlertType.CONFIRMATION,
            "确定删除槽位 ${slot + 1} 的存档与缩略图？",
            ButtonType.OK, ButtonType.CANCEL
        ).apply { initOwner(owner) }.showAndWait().orElse(ButtonType.CANCEL)
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
            style = "-fx-text-fill: #777777; -fx-font-size: 11px;"
        }

        private val previewBox = StackPane(placeholder, preview).apply {
            prefWidth = 160.0
            prefHeight = 120.0
            minWidth = 160.0
            minHeight = 120.0
            style = "-fx-background-color: #1E1E2A; -fx-background-radius: 3;"
        }

        private val titleLabel = Label().apply {
            style = "-fx-font-size: 13px; -fx-font-weight: bold;"
        }

        private val timeLabel = Label().apply {
            style = "-fx-text-fill: #888888; -fx-font-size: 11px;"
        }

        private val saveButton = Button("保存")
        private val loadButton = Button("读取")
        private val deleteButton = Button("删除")

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
            titleLabel.text = "槽位 ${slot + 1}"
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
