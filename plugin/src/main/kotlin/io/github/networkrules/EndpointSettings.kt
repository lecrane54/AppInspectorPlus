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
                row("Environment base URL:") {
                    textField()
                        .bindText(settings::baseUrl)
                        .columns(COLUMNS_LARGE)
                        .comment(
                            "What your environment base resolves to at runtime (e.g. <code>BaseUrl(context).secured</code>). " +
                                "Each service's own path, such as <code>v1.0/drivewise/api</code>, is read from the code that " +
                                "builds it and added after this.<br>" +
                                "Leave the path out (just <code>https://host/</code>), or leave this empty, to match any environment: " +
                                "the environment part becomes <code>*</code>. <code>*</code> works anywhere, e.g. " +
                                "<code>https://\${API_HOST}/mobileapps/*/</code>.<br>" +
                                "Used as the whole base URL for services whose base URL can't be found in code.",
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
        }
    }
}
