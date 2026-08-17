package com.deepseek.dshrider.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

class DshSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private var baseUrlField: JBTextField? = null
    private var policyCombo: ComboBox<SessionPolicy>? = null
    private var pinnedField: JBTextField? = null
    private var includeFileContentCheck: JBCheckBox? = null
    private var maxCodeCharsField: JBTextField? = null
    private var defaultQuestionField: JBTextField? = null
    private var showReasoningCheck: JBCheckBox? = null
    private var autoOpenWebCheck: JBCheckBox? = null

    override fun getDisplayName(): String = "DeepSeek Harness"

    override fun createComponent(): JComponent {
        val settings = DshSettings.getInstance()
        baseUrlField = JBTextField(settings.baseUrl)
        policyCombo = ComboBox(SessionPolicy.entries.toTypedArray()).apply {
            selectedItem = settings.sessionPolicy
        }
        pinnedField = JBTextField(settings.pinnedSessionId)
        includeFileContentCheck = JBCheckBox("Attach whole file content when asking about a file", settings.includeFileContent)
        maxCodeCharsField = JBTextField(settings.maxCodeChars.toString())
        defaultQuestionField = JBTextField(settings.defaultQuestion)
        showReasoningCheck = JBCheckBox("Show the reasoning stream (思考过程)", settings.showReasoning)
        autoOpenWebCheck = JBCheckBox("Open the Web GUI after sending", settings.autoOpenWeb)

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("Harness base URL", baseUrlField!!, 1, false)
            .addLabeledComponent("Session policy", policyCombo!!, 1, false)
            .addLabeledComponent("Pinned session id (for pinned policy)", pinnedField!!, 1, false)
            .addComponent(includeFileContentCheck!!, 1)
            .addLabeledComponent("Max code chars per prompt", maxCodeCharsField!!, 1, false)
            .addLabeledComponent("Default question template", defaultQuestionField!!, 1, false)
            .addComponent(showReasoningCheck!!, 1)
            .addComponent(autoOpenWebCheck!!, 1)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(12)
            add(form, BorderLayout.NORTH)
        }
        return panel!!
    }

    override fun isModified(): Boolean {
        val s = DshSettings.getInstance()
        return baseUrlField?.text != s.baseUrl
            || policyCombo?.selectedItem != s.sessionPolicy
            || pinnedField?.text != s.pinnedSessionId
            || includeFileContentCheck?.isSelected != s.includeFileContent
            || maxCodeCharsField?.text?.toIntOrNull() != s.maxCodeChars
            || defaultQuestionField?.text != s.defaultQuestion
            || showReasoningCheck?.isSelected != s.showReasoning
            || autoOpenWebCheck?.isSelected != s.autoOpenWeb
    }

    override fun apply() {
        val s = DshSettings.getInstance()
        baseUrlField?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { s.baseUrl = it.trimEnd('/') }
        (policyCombo?.selectedItem as? SessionPolicy)?.let { s.sessionPolicyName = it.name }
        s.pinnedSessionId = pinnedField?.text?.trim() ?: ""
        s.includeFileContent = includeFileContentCheck?.isSelected ?: false
        s.maxCodeChars = maxCodeCharsField?.text?.toIntOrNull()?.coerceIn(100, 200000) ?: s.maxCodeChars
        s.defaultQuestion = defaultQuestionField?.text?.trim()?.takeIf { it.isNotEmpty() } ?: s.defaultQuestion
        s.showReasoning = showReasoningCheck?.isSelected ?: true
        s.autoOpenWeb = autoOpenWebCheck?.isSelected ?: false
    }

    override fun reset() {
        createComponent()
    }

    override fun disposeUIResources() {
        panel = null
        baseUrlField = null
        policyCombo = null
        pinnedField = null
        includeFileContentCheck = null
        maxCodeCharsField = null
        defaultQuestionField = null
        showReasoningCheck = null
        autoOpenWebCheck = null
    }
}
