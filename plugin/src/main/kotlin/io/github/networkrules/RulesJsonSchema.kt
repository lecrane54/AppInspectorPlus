package io.github.networkrules

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/**
 * Gives `network-inspector-rules/<team>/<file>.json` completion, hover docs and error highlighting
 * in the editor. Loaded only when the IDE's JSON plugin is present (see network-rules-json.xml).
 */
class RulesJsonSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> =
        listOf(RulesJsonSchemaProvider(project), ScenariosJsonSchemaProvider(project))
}

private class RulesJsonSchemaProvider(private val project: Project) : JsonSchemaFileProvider {
    override fun isAvailable(file: VirtualFile): Boolean {
        if (!file.extension.equals("json", ignoreCase = true)) return false
        val root = project.basePath?.let { "$it/${SharedRulesService.ROOT_DIR}/" } ?: return false
        // Exactly one folder level: <team>/<file>.json
        val rel = file.path.takeIf { it.startsWith(root) }?.removePrefix(root) ?: return false
        return rel.count { it == '/' } == 1
    }

    override fun getName() = "Network Inspector shared rules"
    override fun getSchemaFile(): VirtualFile? =
        JsonSchemaProviderFactory.getResourceFile(RulesJsonSchemaProviderFactory::class.java, "/schemas/network-inspector-rules.schema.json")
    override fun getSchemaType() = SchemaType.embeddedSchema
}

/** `network-inspector-rules/scenarios.json`. */
private class ScenariosJsonSchemaProvider(private val project: Project) : JsonSchemaFileProvider {
    override fun isAvailable(file: VirtualFile): Boolean =
        project.basePath?.let { file.path == "$it/${SharedRulesService.ROOT_DIR}/${ScenarioLogic.FILE}" } == true

    override fun getName() = "Network Inspector rule scenarios"
    override fun getSchemaFile(): VirtualFile? =
        JsonSchemaProviderFactory.getResourceFile(RulesJsonSchemaProviderFactory::class.java, "/schemas/network-inspector-scenarios.schema.json")
    override fun getSchemaType() = SchemaType.embeddedSchema
}
