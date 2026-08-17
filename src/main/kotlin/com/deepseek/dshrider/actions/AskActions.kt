package com.deepseek.dshrider.actions

import com.deepseek.dshrider.settings.DshSettings
import com.deepseek.dshrider.stream.DshRuntime
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile

/** Shared prompt-assembly and submission helpers for the send actions. */
object AskSupport {

    /** Ask the user what to do; returns the question or null when cancelled. */
    fun askQuestion(project: Project, title: String, promptText: String): String? {
        val settings = DshSettings.getInstance()
        val answer = Messages.showInputDialog(
            project,
            promptText,
            title,
            Messages.getQuestionIcon(),
            settings.defaultQuestion,
            null,
        )
        return answer?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Assemble the full message body from a code snippet context. */
    fun buildCodeContext(
        projectName: String?,
        filePath: String,
        startLine: Int,
        endLine: Int,
        language: String,
        code: String,
        question: String,
        maxCodeChars: Int,
    ): String {
        val location = if (startLine > 0 && endLine >= startLine) {
            "（第 $startLine–$endLine 行）"
        } else ""
        val trimmed = truncate(code, maxCodeChars)
        val sb = StringBuilder()
        sb.append("**来自 Rider 的上下文**\n")
        if (!projectName.isNullOrBlank()) sb.append("项目: ").append(projectName).append("\n")
        sb.append("文件: `").append(filePath).append("`").append(location).append("\n\n")
        if (language.isNotBlank()) {
            sb.append("```").append(language.lowercase()).append("\n")
        } else {
            sb.append("```\n")
        }
        sb.append(trimmed).append("\n```\n\n")
        sb.append(question)
        return sb.toString()
    }

    /** Assemble the message body for a whole-file reference. */
    fun buildFileContext(
        projectName: String?,
        filePath: String,
        fileContent: String?,
        language: String,
        question: String,
        maxCodeChars: Int,
    ): String {
        val sb = StringBuilder()
        sb.append("**来自 Rider 的上下文**\n")
        if (!projectName.isNullOrBlank()) sb.append("项目: ").append(projectName).append("\n")
        sb.append("文件: `").append(filePath).append("`\n\n")
        if (fileContent != null) {
            val trimmed = truncate(fileContent, maxCodeChars)
            if (language.isNotBlank()) sb.append("```").append(language.lowercase()).append("\n")
            else sb.append("```\n")
            sb.append(trimmed).append("\n```\n\n")
        } else {
            sb.append("（该文件存在于 Rider 项目内，如需查看具体代码，请直接读取此路径。）\n\n")
        }
        sb.append(question)
        return sb.toString()
    }

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "\n…（内容过长，已截断）"

    fun submit(project: Project, messageText: String, contextLabel: String?, onDone: (String?) -> Unit) {
        val runtime = project.getService(DshRuntime::class.java)
        runtime.ensureConversation()
        runtime.sendPrompt(messageText, contextLabel, onDone)
    }

    fun languageOf(project: Project, file: VirtualFile?): String {
        if (file == null) return ""
        return try {
            com.intellij.psi.PsiManager.getInstance(project).findFile(file)?.language?.displayName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun projectName(project: Project): String = project.name

    fun notifyError(project: Project, message: String) {
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("Dsh.Notification")
            .createNotification(
                "DeepSeek Harness: $message",
                com.intellij.notification.NotificationType.ERROR,
            )
            .notify(project)
    }

    fun notifyInfo(project: Project, message: String) {
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("Dsh.Notification")
            .createNotification(message, com.intellij.notification.NotificationType.INFORMATION)
            .notify(project)
    }
}

/** Send the editor selection (or the current line when nothing is selected). */
class AskSelectionAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.EDITOR) != null && e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor: Editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val settings = DshSettings.getInstance()
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val document = editor.document

        val selection = editor.selectionModel
        val start: Int
        val end: Int
        if (selection.hasSelection()) {
            start = selection.selectionStart
            end = selection.selectionEnd
        } else {
            start = document.getLineStartOffset(document.getLineNumber(editor.caretModel.offset))
            end = document.getLineEndOffset(document.getLineNumber(editor.caretModel.offset))
        }
        val code = try { document.getText(com.intellij.openapi.util.TextRange(start, end)) } catch (_: Exception) { "" }
        val startLine = document.getLineNumber(start) + 1
        val endLine = document.getLineNumber(end) + 1

        val hasSelection = selection.hasSelection()
        val promptText = if (hasSelection) {
            "将选中的代码发送给 DeepSeek Harness。想让它做什么？"
        } else {
            "将当前行的代码发送给 DeepSeek Harness。想让它做什么？"
        }
        val question = AskSupport.askQuestion(project, "Ask DeepSeek", promptText) ?: return

        val filePath = file?.path ?: "(未命名文件)"
        val message = AskSupport.buildCodeContext(
            projectName = AskSupport.projectName(project),
            filePath = filePath,
            startLine = startLine,
            endLine = endLine,
            language = AskSupport.languageOf(project, file),
            code = code,
            question = question,
            maxCodeChars = settings.maxCodeChars,
        )
        AskSupport.submit(project, message, "$filePath:$startLine") { error ->
            if (error != null) AskSupport.notifyError(project, error)
        }
    }
}

/** Send the current file (or the file selected in the project view). */
class AskFileAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null &&
            (e.getData(CommonDataKeys.VIRTUAL_FILE) != null || e.getData(CommonDataKeys.EDITOR) != null)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = DshSettings.getInstance()
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: e.getData(CommonDataKeys.EDITOR)?.let { com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(it.document) }
            ?: return

        val question = AskSupport.askQuestion(project, "Ask DeepSeek about File", "把文件 $file.name 的上下文发送给 DeepSeek Harness。想让它做什么？")
            ?: return

        val content = if (settings.includeFileContent) {            try {
                String(file.contentsToByteArray(), Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        } else null

        val message = AskSupport.buildFileContext(
            projectName = AskSupport.projectName(project),
            filePath = file.path,
            fileContent = content,
            language = AskSupport.languageOf(project, file),
            question = question,
            maxCodeChars = settings.maxCodeChars,
        )
        AskSupport.submit(project, message, file.path) { error ->
            if (error != null) AskSupport.notifyError(project, error)
        }
    }
}

/** Open the harness Web GUI in the default browser. */
class OpenWebAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.getService(DshRuntime::class.java).openWeb()
    }
}

/** Create a fresh harness session for the current project. */
class NewSessionAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val runtime = project.getService(DshRuntime::class.java)
        runtime.ensureConversation()
        runtime.newSession { error ->
            if (error == null) AskSupport.notifyInfo(project, "已为本项目创建新的 DeepSeek 会话")
            else AskSupport.notifyError(project, error)
        }
    }
}
