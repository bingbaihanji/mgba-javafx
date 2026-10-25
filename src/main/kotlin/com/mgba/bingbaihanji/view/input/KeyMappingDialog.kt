package com.mgba.bingbaihanji.view.input

import com.mgba.bingbaihanji.core.emulation.GbaButton
import com.mgba.bingbaihanji.platform.desktop.input.KeyMapping
import com.mgba.bingbaihanji.view.theme.UiTheme
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.*
import javafx.stage.Window
import java.util.*

/**
 * 按键映射对话框：点击某个动作对应的按钮，再按下一个键完成绑定。
 *
 * <p>编辑的是一份副本，只有点"确定"才回传；Esc 取消当前正在等待的绑定。
 */
class KeyMappingDialog(
    private val owner: Window?,
    current: KeyMapping,
    private val onApply: (KeyMapping) -> Unit
) {

    /** 正在等待按键的 GBA 按键；null 表示不在捕获状态。 */
    private var capturing: GbaButton? = null

    /** 编辑中的副本。 */
    private val draft = current.copy()

    private val keyButtons = EnumMap<GbaButton, Button>(GbaButton::class.java)

    private val message = Label().apply {
        styleClass.add("error-label")
    }

    /** 打开对话框（阻塞到用户关闭）。 */
    fun show() {
        val grid = GridPane().apply {
            hgap = 16.0
            vgap = 8.0
            padding = Insets(4.0)
            columnConstraints.addAll(
                ColumnConstraints().apply { minWidth = 80.0 },
                ColumnConstraints().apply {
                    hgrow = Priority.ALWAYS
                    prefWidth = 200.0
                }
            )
        }

        KeyMapping.MAPPABLE.forEachIndexed { row, button ->
            grid.add(Label(buttonLabel(button)), 0, row)
            val keyButton = Button().apply {
                maxWidth = Double.MAX_VALUE
                setOnAction { startCapture(button) }
            }
            keyButtons[button] = keyButton
            grid.add(keyButton, 1, row)
        }

        val reset = Button("恢复默认(_R)").apply {
            setOnAction {
                draft.resetToDefaults()
                capturing = null
                message.text = ""
                refreshButtons()
            }
        }
        val hint = Label("F1–F12、空格与 Esc 已被程序快捷键占用；Esc 同时用于取消本次更改").apply {
            styleClass.add("hint-label")
            isWrapText = true
        }

        val content = VBox(10.0, grid, HBox(12.0, reset, hint), message).apply {
            padding = Insets(6.0)
        }

        val dialog = Dialog<ButtonType>().apply {
            initOwner(owner)
            title = "按键映射"
            headerText = "点击右侧按钮后按下新键"
            dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
            dialogPane.content = content
            dialogPane.prefWidth = 540.0
        }
        UiTheme.applyDialog(dialog, UiTheme.ICON_APP)

        // 捕获阶段拦截按键：捕获期间不会触发按钮自身的空格/回车激活
        dialog.dialogPane.addEventFilter(KeyEvent.KEY_PRESSED) { event -> handleKey(event) }

        refreshButtons()
        val result = dialog.showAndWait().orElse(ButtonType.CANCEL)
        if (result == ButtonType.OK) {
            onApply(draft)
        }
    }

    // ── 内部 ──────────────────────────────────────────────────

    private fun startCapture(button: GbaButton) {
        capturing = button
        message.text = ""
        refreshButtons()
    }

    private fun handleKey(event: KeyEvent) {
        val target = capturing ?: return
        event.consume()

        if (event.code == KeyCode.ESCAPE) {
            capturing = null
            refreshButtons()
            return
        }
        if (isReserved(event.code)) {
            message.text = "${keyName(event.code)} 已被程序快捷键占用"
            return
        }

        val ownerButton = draft.buttonUsing(event.code)
        if (ownerButton != null && ownerButton != target) {
            message.text = "${keyName(event.code)} 已用于「${buttonLabel(ownerButton)}」"
            return
        }

        draft.set(target, event.code)
        capturing = null
        message.text = ""
        refreshButtons()
    }

    private fun refreshButtons() {
        for (button in KeyMapping.MAPPABLE) {
            val key = keyButtons[button] ?: continue
            key.text = if (capturing == button) {
                "请按键…"
            } else {
                val bound = draft.keyFor(button)
                if (bound == null) "未绑定" else keyName(bound)
            }
        }
    }

    private fun keyName(key: KeyCode): String = key.getName().ifBlank { key.name }

    private fun buttonLabel(button: GbaButton): String = when (button) {
        GbaButton.START -> "Start"
        GbaButton.SELECT -> "Select"
        GbaButton.UP -> "上"
        GbaButton.DOWN -> "下"
        GbaButton.LEFT -> "左"
        GbaButton.RIGHT -> "右"
        else -> button.name
    }

    /** 程序快捷键占用的键：功能键、空格、Esc 与纯修饰键。 */
    private fun isReserved(key: KeyCode): Boolean {
        if (key == KeyCode.SPACE || key == KeyCode.ESCAPE) {
            return true
        }
        if (key == KeyCode.SHIFT || key == KeyCode.CONTROL || key == KeyCode.ALT
            || key == KeyCode.META || key == KeyCode.WINDOWS || key == KeyCode.COMMAND
        ) {
            return true
        }
        return key.name.matches(Regex("F([1-9]|1[0-2])"))
    }
}
