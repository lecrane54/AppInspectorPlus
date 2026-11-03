package io.github.networkrules

import com.google.gson.JsonParser
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.psi.PsiElement
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

/**
 * Right-click a Retrofit interface method (or its @GET/@POST/… annotation) → a rule matching that
 * endpoint, with a placeholder response body built from the method's return type.
 */
class CreateRuleFromEndpointAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && !DumbService.isDumb(project) &&
            RetrofitEndpoints.at(elementAt(e)) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val element = elementAt(e) ?: return
        val settings = EndpointSettings.getInstance(project)
        val base = EndpointLogic.parseBaseUrl(settings.baseUrl)

        // Searching for where the service is built and resolving the response type's classes can take
        // a moment, so do it off the UI thread.
        val read = ProgressManager.getInstance().runProcessWithProgressSynchronously<EndpointRead?, Exception>(
            {
                ReadAction.compute<EndpointRead?, Exception> {
                    val endpoint = RetrofitEndpoints.at(element) ?: return@compute null
                    val body = endpoint.responseType?.let { RuleMapper.gson.toJson(SampleJson.of(it)) }.orEmpty()
                    EndpointRead(endpoint, body, endpoint.service?.let(ServiceBaseUrlResolver::find))
                }
            },
            "Reading Retrofit Endpoint",
            true,
            project,
        ) ?: return
        val endpoint = read.endpoint
        val serviceBase = read.serviceBase
        val criteria = if (serviceBase != null) {
            EndpointLogic.resolveService(base, serviceBase.parts, endpoint.path)
        } else {
            EndpointLogic.resolve(base, endpoint.path)
        }
        val baseNote = baseNote(endpoint, serviceBase, base)

        val service = SharedRulesService.getInstance(project)
        val dialog = CreateRuleDialog(
            project,
            name = EndpointLogic.ruleName(endpoint.method, endpoint.path),
            method = EndpointLogic.studioMethod(endpoint.method),
            criteria = criteria,
            body = read.body,
            baseNote = baseNote,
            destinations = service.allFilePaths(),
            turnOn = settings.turnOnNewRules,
        )
        if (!dialog.showAndGet()) return

        val rule = dialog.rule()
        val target = dialog.target()
        if (target == null) {
            service.addLocalRule(rule, dialog.turnOn())
            return
        }
        if (service.fileHasRule(target, rule.name.orEmpty())) {
            val answer = Messages.showYesNoDialog(
                project,
                "$target already has a rule named \"${rule.name}\". Replace it?",
                "Replace Rule?",
                "Replace",
                "Cancel",
                Messages.getWarningIcon(),
            )
            if (answer != Messages.YES) return
        }
        try {
            service.addRuleToFile(target, rule, dialog.turnOn())
        } catch (ex: IllegalStateException) {
            Messages.showErrorDialog(project, ex.message, "Can't Save Rule")
        }
    }

    /** One line under Host explaining where the URL came from. */
    private fun baseNote(endpoint: RetrofitEndpoint, found: ServiceBase?, environment: EndpointLogic.BaseUrl?): String {
        val esc = { s: String -> com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(s) }
        val service = endpoint.service?.name ?: "this service"
        if (found == null) {
            return if (environment == null) {
                "No base URL found in code for $service and none set, so host and base path match anything."
            } else {
                "No base URL found in code for $service; using the Settings base URL."
            }
        }
        val from = "Base URL <code>${esc(EndpointLogic.describe(found.parts))}</code> from ${esc(found.location)}."
        val envPart = (EndpointLogic.normalize(found.parts).firstOrNull() as? EndpointLogic.UrlPart.Dynamic)?.source
        return when {
            envPart == null -> from
            environment != null && environment.path != "/" -> "$from <code>${esc(envPart)}</code> uses the Settings base URL."
            else -> "$from <code>${esc(envPart)}</code> depends on the environment, so it's <code>*</code>."
        }
    }

    private class EndpointRead(val endpoint: RetrofitEndpoint, val body: String, val serviceBase: ServiceBase?)

    /** The PSI element at the caret, or the selected element (e.g. in the Structure view). */
    private fun elementAt(e: AnActionEvent): PsiElement? {
        val file = e.getData(CommonDataKeys.PSI_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)
        if (file != null && editor != null) return file.findElementAt(editor.caretModel.offset)
        return e.getData(CommonDataKeys.PSI_ELEMENT)
    }
}

private class CreateRuleDialog(
    private val project: Project,
    name: String,
    method: String,
    criteria: EndpointLogic.Endpoint,
    body: String,
    private val baseNote: String,
    destinations: List<String>,
    turnOn: Boolean,
) : DialogWrapper(project) {
    private val nameField = JBTextField(name)
    private val methodCombo = ComboBox(EndpointLogic.STUDIO_METHODS.toTypedArray()).apply { selectedItem = method }
    private val protocolCombo = ComboBox(arrayOf("HTTPS", "HTTP")).apply { selectedItem = criteria.protocol }
    private val hostField = JBTextField(criteria.host)
    private val portField = JBTextField(criteria.port, 6)
    private val pathField = JBTextField(criteria.path)
    private val queryField = JBTextField(criteria.query)
    private val statusField = JBTextField("200", 6)
    private val bodyArea = JBTextArea(body, 14, 60).apply {
        font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size)
    }
    private val targetCombo = ComboBox((listOf(LOCAL_ONLY) + destinations).toTypedArray()).apply { isEditable = true }
    private val turnOnBox = JBCheckBox("Turn the rule on now", turnOn)

    init {
        title = "Create Network Rule from Endpoint"
        setOKButtonText("Create")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") { cell(nameField).align(AlignX.FILL) }
        row("Method:") {
            cell(methodCombo)
            label("Protocol:")
            cell(protocolCombo)
        }
        row("Host:") {
            cell(hostField).align(AlignX.FILL).resizableColumn()
            label("Port:")
            cell(portField)
        }
        row("") {
            comment(baseNote)
        }
        row("") {
            link("Environment base URL settings…") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, SharedRulesConfigurable::class.java)
            }
        }
        row("Path:") { cell(pathField).align(AlignX.FILL).comment("<code>*</code> matches anything, e.g. a path parameter.") }
        row("Query:") { cell(queryField).align(AlignX.FILL).comment("Empty matches any query.") }
        row("Status code:") { cell(statusField).comment("Empty keeps the server's status code.") }
        row("Response body:") {}
        row {
            cell(JBScrollPane(bodyArea)).align(Align.FILL)
                .comment("Replaces the real response body. Built from the method's return type; edit as needed. Empty keeps the server's body.")
        }.resizableRow()
        row("Save to:") {
            cell(targetCombo).align(AlignX.FILL)
                .comment("Your Rules tab only (export it later), or a team file like <code>login/rules.json</code> to share it now.")
        }
        row { cell(turnOnBox) }
    }.apply { preferredSize = JBUI.size(640, 640) }

    override fun getPreferredFocusedComponent(): JComponent = bodyArea

    override fun doValidateAll(): List<ValidationInfo> {
        val problems = mutableListOf<ValidationInfo>()
        if (nameField.text.isBlank()) problems += ValidationInfo("Enter a rule name", nameField)
        else if (target() != null && nameField.text.trim().startsWith("[")) {
            problems += ValidationInfo("Don't start with [team]; the folder adds it", nameField)
        }
        val code = statusField.text.trim()
        if (code.isNotEmpty() && (code.toIntOrNull() ?: 0) !in 100..599) problems += ValidationInfo("Use a status code from 100 to 599", statusField)
        val body = bodyArea.text.trim()
        if ((body.startsWith("{") || body.startsWith("[")) && runCatching { JsonParser.parseString(body) }.isFailure) {
            problems += ValidationInfo("The body looks like JSON but doesn't parse", bodyArea).asWarning().withOKEnabled()
        }
        val t = target()
        if (t != null && !SharedRulesService.VALID_TARGET.matches(t)) problems += ValidationInfo("Use team/file.json, e.g. login/rules.json", targetCombo)
        return problems
    }

    /** null = only the developer's Rules tab. */
    fun target(): String? = (targetCombo.editor.item as? String)?.trim()?.removeSuffix("/")
        ?.takeIf { it.isNotEmpty() && it != LOCAL_ONLY }

    fun turnOn(): Boolean = turnOnBox.isSelected

    fun rule(): SharedRule {
        val code = statusField.text.trim()
        val body = bodyArea.text
        return SharedRule(
            name = nameField.text.trim(),
            criteria = SharedCriteria(
                protocol = protocolCombo.selectedItem as String,
                host = hostField.text.trim(),
                port = portField.text.trim(),
                path = pathField.text.trim(),
                query = queryField.text.trim(),
                method = methodCombo.selectedItem as String,
            ),
            statusCode = if (code.isEmpty()) null else SharedStatusCode(active = true, findCode = "", newCode = code),
            headers = mutableListOf(),
            body = if (body.isBlank()) mutableListOf() else mutableListOf(SharedBodyRule(type = "replace", body = RuleMapper.bodyTextToJson(body))),
        )
    }

    companion object {
        const val LOCAL_ONLY = "Only my Rules tab"
    }
}
