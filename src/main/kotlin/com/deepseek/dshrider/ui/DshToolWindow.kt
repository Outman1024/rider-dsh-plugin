package com.deepseek.dshrider.ui

import com.deepseek.dshrider.conversation.DshConversation
import com.deepseek.dshrider.conversation.Entry
import com.deepseek.dshrider.session.DshProjectState
import com.deepseek.dshrider.settings.DshSettings
import com.deepseek.dshrider.stream.DshRuntime
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JToggleButton
import javax.swing.SwingUtilities

class DshToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DshToolWindowPanel(project)
        val content = com.intellij.ui.content.ContentFactory.getInstance()
            .createContent(panel.root, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class DshToolWindowPanel(private val project: Project) {

    private val runtime: DshRuntime = project.getService(DshRuntime::class.java)

    private val transcriptBox = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val scrollPane = JBScrollPane(
        transcriptBox,
        JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
        JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
    ).apply {
        border = BorderFactory.createEmptyBorder()
    }

    private val statusLabel = JBLabel("未连接")
    private val sessionLabel = JBLabel("")
    private val inputArea = JBTextArea(3, 0).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val sendButton = JButton("发送")

    private val viewByEntryId = HashMap<String, EntryView>()
    private var currentConversation: DshConversation? = null
    private var currentConvListener: DshConversation.Listener? = null

    private val runtimeListener = object : DshRuntime.Listener {
        override fun onConversationChanged(conversation: DshConversation) {
            ApplicationManager.getApplication().invokeLater { rebuild(conversation) }
        }
    }

    val root: JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        border = JBUI.Borders.empty(8)
        add(buildTopBar(), BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(buildBottomArea(), BorderLayout.SOUTH)
    }

    init {
        sendButton.addActionListener { send() }
        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    send()
                }
            }
        })
        runtime.addListener(runtimeListener)
        val conv = runtime.ensureConversation()
        rebuild(conv)
    }

    private fun buildTopBar(): JComponent {
        val bar = JBPanel<JBPanel<*>>(BorderLayout())
        val left = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JBLabel("DeepSeek Harness").apply { font = font.deriveFont(java.awt.Font.BOLD) })
            add(statusLabel)
        }
        val right = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            add(JButton("新会话").apply {
                toolTipText = "为本项目新建一个 harness 会话"
                addActionListener { newSession() }
            })
            add(JButton("打开网页").apply { addActionListener { runtime.openWeb() } })
            add(JButton("清空视图").apply {
                toolTipText = "只清空此处的显示，不影响远程会话"
                addActionListener { clearView() }
            })
        }
        bar.add(left, BorderLayout.WEST)
        bar.add(right, BorderLayout.EAST)
        return bar
    }

    private fun buildBottomArea(): JComponent {
        val bottom = JBPanel<JBPanel<*>>(BorderLayout())
        val inputRow = JBPanel<JBPanel<*>>(BorderLayout(4, 0)).apply {
            border = JBUI.Borders.emptyTop(6)
        }
        inputRow.add(JBScrollPane(inputArea).apply {
            preferredSize = Dimension(0, 60)
        }, BorderLayout.CENTER)
        inputRow.add(sendButton, BorderLayout.EAST)

        val statusRow = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(4)
        }
        statusRow.add(sessionLabel, BorderLayout.WEST)
        statusRow.add(JBLabel("Enter 发送，Shift+Enter 换行").apply {
            foreground = JBColor.GRAY
        }, BorderLayout.EAST)

        bottom.add(inputRow, BorderLayout.NORTH)
        bottom.add(statusRow, BorderLayout.SOUTH)
        return bottom
    }

    // ------------------------------------------------------------------

    private fun rebuild(conv: DshConversation) {
        currentConvListener?.let { currentConversation?.removeListener(it) }
        currentConversation = conv
        viewByEntryId.clear()
        transcriptBox.removeAll()
        val listener = ConvListener()
        conv.addListener(listener)
        currentConvListener = listener
        conv.snapshot().forEach { appendView(it) }
        updateStatusRow(conv)
        refreshTranscript()
    }

    private inner class ConvListener : DshConversation.Listener {
        override fun onEntryAdded(index: Int, entry: Entry) {
            ApplicationManager.getApplication().invokeLater { appendView(entry) }
        }

        override fun onEntryChanged(index: Int, entry: Entry) {
            ApplicationManager.getApplication().invokeLater {
                updateView(entry)
                refreshTranscript()
            }
        }

        override fun onStatus(text: String, connected: Boolean) {
            ApplicationManager.getApplication().invokeLater { updateStatusRow(currentConversation) }
        }
    }

    private fun updateStatusRow(conv: DshConversation?) {
        val base = DshSettings.getInstance().baseUrl
        val state = DshProjectState.getInstance(project)
        val shortSession = state.sessionId.takeIf { it.isNotEmpty() }?.take(12) ?: "（尚无会话）"
        sessionLabel.text = "会话: $shortSession   ·   $base"
        statusLabel.text = if (conv?.connected == true) "已连接" else "未连接"
        statusLabel.foreground = if (conv?.connected == true) JBColor(0x2E7D32.toInt(), 0x81C784.toInt()) else JBColor.GRAY
        refreshTranscript()
    }

    private fun appendView(entry: Entry) {
        val view = createView(entry)
        viewByEntryId[entry.id] = view
        transcriptBox.add(view.row)
        updateView(entry)
        refreshTranscript()
    }

    private fun updateView(entry: Entry) {
        viewByEntryId[entry.id]?.refresh(entry)
    }

    private fun refreshTranscript() {
        transcriptBox.revalidate()
        transcriptBox.repaint()
        SwingUtilities.invokeLater {
            val bar = scrollPane.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    // ------------------------------------------------------------------
    // Entry rendering
    // ------------------------------------------------------------------

    private fun createView(entry: Entry): EntryView = when (entry) {
        is Entry.User -> UserView(entry)
        is Entry.Assistant -> AssistantView(entry)
        is Entry.Tool -> ToolView(entry)
        is Entry.System -> SystemView(entry)
        is Entry.Approval -> ApprovalView(entry)
        is Entry.QuestionEntry -> QuestionView(entry)
    }

    private abstract class EntryView {
        abstract val row: JComponent
        open fun refresh(entry: Entry) {}
    }

    private fun bubble(color: Color): JBPanel<JBPanel<*>> = JBPanel<JBPanel<*>>(BorderLayout(6, 2)).apply {
        background = color
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border(), 1, true),
            JBUI.Borders.empty(8, 10),
        )
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    }

    private fun rowOf(inner: JComponent, labelText: String): JPanel {
        val row = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 0)
            // BoxLayout (Y) would otherwise clamp the row to its preferred height.
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        row.add(JBLabel(labelText).apply {
            font = font.deriveFont(java.awt.Font.BOLD, font.size2D - 1f)
            foreground = JBColor(0x777777.toInt(), 0x9E9E9E.toInt())
        }, BorderLayout.NORTH)
        row.add(inner, BorderLayout.CENTER)
        return row
    }

    private inner class UserView(entry: Entry.User) : EntryView() {
        private val textArea = bubbleText(entry.text)
        private val bubble = bubble(JBColor(0xEAF3FF.toInt(), 0x24344D.toInt())).apply {
            add(textArea, BorderLayout.CENTER)
        }
        override val row: JComponent = rowOf(bubble, "你${entry.contextLabel?.let { " · $it" } ?: ""}")
    }

    private inner class AssistantView(entry: Entry.Assistant) : EntryView() {
        private val reasoningArea = JBTextArea(0, 0).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            foreground = JBColor(0x8A8A8A.toInt(), 0x9A9A9A.toInt())
            font = font.deriveFont(java.awt.Font.ITALIC, font.size2D - 1f)
            border = JBUI.Borders.empty(2, 0, 4, 0)
        }
        private val textArea = bubbleText("")
        private val statusLine = JBLabel("").apply {
            foreground = JBColor(0x999999.toInt(), 0x8A8A8A.toInt())
        }
        private val body = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(reasoningArea, BorderLayout.NORTH)
            add(textArea, BorderLayout.CENTER)
            add(statusLine, BorderLayout.SOUTH)
        }
        private val bubble = bubble(JBColor(0xF4F4F4.toInt(), 0x2E3440.toInt())).apply {
            add(body, BorderLayout.CENTER)
        }
        override val row: JComponent = rowOf(bubble, "DeepSeek")

        override fun refresh(entry: Entry) {
            entry as Entry.Assistant
            val showReasoning = DshSettings.getInstance().showReasoning && entry.reasoning.isNotEmpty()
            reasoningArea.text = if (showReasoning) "思考: ${entry.reasoning}" else ""
            reasoningArea.isVisible = showReasoning
            textArea.text = entry.text
            statusLine.text = entry.status
            statusLine.isVisible = entry.status.isNotEmpty()
        }
    }

    private inner class ToolView(entry: Entry.Tool) : EntryView() {
        private val label = JBLabel("")
        private val bubble = bubble(JBColor(0xF7F3E8.toInt(), 0x3A3320.toInt())).apply {
            add(label, BorderLayout.CENTER)
        }
        override val row: JComponent = rowOf(bubble, "工具")
        override fun refresh(entry: Entry) {
            entry as Entry.Tool
            label.text = "🔧 ${entry.name.ifBlank { "工具调用" }} — ${entry.status}"
        }
    }

    private inner class SystemView(entry: Entry.System) : EntryView() {
        private val label = JBLabel(entry.text).apply {
            foreground = if (entry.kind == "error") JBColor(0xC62828.toInt(), 0xEF9A9A.toInt()) else JBColor.GRAY
        }
        override val row: JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 0)
            add(label, BorderLayout.CENTER)
        }
    }

    private inner class ApprovalView(entry: Entry.Approval) : EntryView() {
        private val statusLabel = JBLabel("")
        private val allowButton = JButton("允许一次")
        private val rejectButton = JButton("拒绝")
        private val buttons = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(allowButton)
            add(rejectButton)
        }
        private val body = JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
            isOpaque = false
            add(statusLabel, BorderLayout.NORTH)
            add(buttons, BorderLayout.SOUTH)
        }
        private val bubble = bubble(JBColor(0xFFF8E1.toInt(), 0x3A3320.toInt())).apply {
            add(body, BorderLayout.CENTER)
        }
        override val row: JComponent = rowOf(bubble, "需要授权")

        init {
            allowButton.addActionListener {
                setBusy(true)
                runtime.respondApproval(entry, "allowed-once") { error ->
                    ApplicationManager.getApplication().invokeLater {
                        setBusy(false)
                        if (error != null) statusLabel.text = "操作失败: $error"
                    }
                }
            }
            rejectButton.addActionListener {
                setBusy(true)
                runtime.respondApproval(entry, "rejected") { error ->
                    ApplicationManager.getApplication().invokeLater {
                        setBusy(false)
                        if (error != null) statusLabel.text = "操作失败: $error"
                    }
                }
            }
        }

        private fun setBusy(busy: Boolean) {
            allowButton.isEnabled = !busy
            rejectButton.isEnabled = !busy
        }

        override fun refresh(entry: Entry) {
            entry as Entry.Approval
            val head = buildString {
                append("工具: ")
                append(entry.toolName.ifBlank { "未知工具" })
                entry.reason?.takeIf { it.isNotBlank() }?.let { append("  —  $it") }
            }
            when (entry.state) {
                "pending" -> {
                    statusLabel.text = head
                    buttons.isVisible = true
                    allowButton.isEnabled = true
                    rejectButton.isEnabled = true
                }
                else -> {
                    val text = when (entry.state) {
                        "allowed-once" -> "已允许"
                        "rejected" -> "已拒绝"
                        "cancelled" -> "已取消"
                        else -> "已处理（${entry.state}）"
                    }
                    statusLabel.text = "$head  [$text]"
                    buttons.isVisible = false
                }
            }
        }
    }

    private inner class QuestionView(entry: Entry.QuestionEntry) : EntryView() {
        private val container = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        private val submitButton = JButton("提交答案")
        private val answerControls = LinkedHashMap<String, List<JToggleButton>>()
        private val bubble = bubble(JBColor(0xE8F5E9.toInt(), 0x24332A.toInt()))

        override val row: JComponent = rowOf(bubble, "DeepSeek 提问")

        init {
            bubble.layout = BorderLayout()
            val body = JBPanel<JBPanel<*>>(BorderLayout(0, 6)).apply {
                isOpaque = false
                add(container, BorderLayout.NORTH)
                add(submitButton, BorderLayout.SOUTH)
            }
            bubble.add(body, BorderLayout.CENTER)
            submitButton.addActionListener {
                val selected = LinkedHashMap<String, List<String>>()
                answerControls.forEach { (id, boxes) ->
                    selected[id] = boxes.filter { it.isSelected }.map { it.text }
                }
                submitButton.isEnabled = false
                runtime.answerQuestions(entry, selected) { error ->
                    ApplicationManager.getApplication().invokeLater {
                        if (error != null) {
                            submitButton.isEnabled = true
                            container.add(JBLabel("提交失败: $error"), 0)
                            refreshTranscript()
                        }
                    }
                }
            }
        }

        override fun refresh(entry: Entry) {
            entry as Entry.QuestionEntry
            container.removeAll()
            answerControls.clear()
            if (entry.state != "pending") {
                container.add(JBLabel("已处理（${entry.state}）"))
                submitButton.isVisible = false
                return
            }
            submitButton.isVisible = true
            submitButton.isEnabled = true
            for (question in entry.questions) {
                val text = listOfNotNull(
                    question.header,
                    question.question.ifBlank { null },
                ).joinToString("：")
                if (text.isNotEmpty()) {
                    container.add(JBLabel("<html><b>${escapeHtml(text)}</b></html>"))
                }
                if (question.planApprove != null) {
                    val approve = JCheckBox("批准计划")
                    answerControls[question.id] = listOf(approve)
                    container.add(approve)
                } else if (question.options.isEmpty()) {
                    container.add(JBLabel("（请在 DeepSeek 工具窗口或网页中回答）"))
                } else {
                    val group = if (question.multiSelect) null else ButtonGroup()
                    val boxes = question.options.map { opt ->
                        val box = if (question.multiSelect) JCheckBox(opt.label) else JRadioButton(opt.label)
                        if (!question.multiSelect) group?.add(box)
                        box.toolTipText = opt.description
                        container.add(box)
                        box
                    }
                    answerControls[question.id] = boxes
                    if (!question.multiSelect && boxes.isNotEmpty()) boxes.first().isSelected = true
                }
            }
        }

        private fun escapeHtml(s: String): String = s
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    private fun bubbleText(text: String): JTextArea = JBTextArea(text, 0, 0).apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        border = BorderFactory.createEmptyBorder()
    }

    // ------------------------------------------------------------------

    private fun send() {
        val text = inputArea.text.trim()
        if (text.isEmpty()) return
        inputArea.text = ""
        val conv = runtime.ensureConversation()
        runtime.sendPrompt(text, null) { error ->
            if (error != null) {
                ApplicationManager.getApplication().invokeLater {
                    conv.addSystem("发送失败: $error", "error")
                }
            }
        }
    }

    private fun newSession() {
        val conv = runtime.ensureConversation()
        runtime.newSession { error ->
            ApplicationManager.getApplication().invokeLater {
                if (error == null) conv.addSystem("已创建新会话", "info") else conv.addSystem("创建新会话失败: $error", "error")
            }
        }
    }

    private fun clearView() {
        currentConversation?.clear()
        transcriptBox.removeAll()
        viewByEntryId.clear()
        refreshTranscript()
    }
}
