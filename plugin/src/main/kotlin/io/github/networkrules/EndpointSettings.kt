package io.github.networkrules

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

/**
 * Project settings for "Create Network Rule from Endpoint". Stored in .idea/networkInspectorRules.xml,
 * so a team that commits .idea shares the base URL.
 */
@Service(Service.Level.PROJECT)
@State(name = "NetworkRulesEndpoints", storages = [Storage("networkInspectorRules.xml")])
class EndpointSettings : SimplePersistentStateComponent<EndpointSettings.Data>(Data()) {
    class Data : BaseState() {
        var baseUrl by string("")
        var turnOnNewRules by property(true)
        var appPackage by string("")
        var broadcastAction by string(DevicePushLogic.DEFAULT_ACTION)
    }

    /** The debug app that has the Appium mocks, for **Push Rules to Device…** and the copied adb commands. */
    var appPackage: String
        get() = state.appPackage.orEmpty()
        set(value) {
            state.appPackage = value.trim()
        }

    /** The broadcast action the app's mocks receiver listens for (`MockRulesReceiver.ACTION`). */
    var broadcastAction: String
        get() = state.broadcastAction?.takeIf { it.isNotBlank() } ?: DevicePushLogic.DEFAULT_ACTION
        set(value) {
            state.broadcastAction = value.trim().ifEmpty { DevicePushLogic.DEFAULT_ACTION }
        }

    var baseUrl: String
        get() = state.baseUrl.orEmpty()
        set(value) {
            state.baseUrl = value.trim()
        }

    var turnOnNewRules: Boolean
        get() = state.turnOnNewRules
        set(value) {
            state.turnOnNewRules = value
        }

    companion object {
        fun getInstance(project: Project): EndpointSettings = project.service()
    }
}

/** Settings | Tools | Network Inspector Shared Rules */
class SharedRulesConfigurable(private val project: Project) : BoundConfigurable("Network Inspector Shared Rules") {
    override fun createPanel(): DialogPanel {
        val settings = EndpointSettings.getInstance(project)
        return panel {
            group("Create Rule from Retrofit Endpoint") {
                row("Base URL:") {
                    textField()
                        .bindText(settings::baseUrl)
                        .columns(COLUMNS_LARGE)
                        .comment(
                            "Protocol, host and port for new rules, e.g. <code>https://api.example.com/</code>. " +
                                "Any path here is ignored: rule paths start with <code>*</code> " +
                                "(e.g. <code>*/trips/*</code>), so they match whatever base path a service and environment use.<br>" +
                                "The host can be a rule variable, e.g. <code>https://\${API_HOST}/</code>, so each developer can use " +
                                "Personal Variable Values to point it elsewhere. Empty matches any host.",
                        )
                        .validationOnInput {
                            if (it.text.isNotBlank() && EndpointLogic.parseBaseUrl(it.text) == null) {
                                error("Use a URL starting with http:// or https://")
                            } else null
                        }
                }
                row {
                    checkBox("Turn new rules on right away").bindSelected(settings::turnOnNewRules)
                }
            }
            group("Appium Mocks on the Device") {
                row("App package:") {
                    textField()
                        .bindText(settings::appPackage)
                        .columns(COLUMNS_LARGE)
                        .comment(
                            "The debug app that has the Appium mocks interceptor (see <code>appium-mocks/</code>), e.g. <code>com.example.app.debug</code>. " +
                                "Used by <b>Push Rules to Device…</b> and in the copied adb commands.",
                        )
                        .validationOnInput {
                            if (it.text.isNotBlank() && !DevicePushLogic.VALID_PACKAGE.matches(it.text.trim())) error("Use a package name like com.example.app") else null
                        }
                }
                row("Broadcast action:") {
                    textField()
                        .bindText(settings::broadcastAction)
                        .columns(COLUMNS_LARGE)
                        .comment("The action the app's <code>MockRulesReceiver</code> listens for. Change it only if you renamed it in the app.")
                        .validationOnInput {
                            if (it.text.isNotBlank() && !DevicePushLogic.VALID_ACTION.matches(it.text.trim())) error("Use an action name like com.example.mockrules.CONTROL") else null
                        }
                }
            }
        }
    }
}
