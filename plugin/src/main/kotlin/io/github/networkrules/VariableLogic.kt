package io.github.networkrules

/**
 * What the Variables tab shows, kept free of Studio types so it can be unit tested.
 *
 * A variable is one of:
 *  - **Shared**: defined in one or more team files, so its value is committed. Each developer can
 *    set their own value on top, which stays on their machine.
 *  - **Local**: only on this machine (in `.idea/workspace.xml`, never committed). Variables made in
 *    Studio's own Rule Variables dialog are local too.
 *    A team file can *declare* a local variable (`"local": true`, a description, no value) so
 *    teammates are told the rules need it without its value being committed.
 *  - **Missing**: a rule uses `${NAME}` (or a team file declares it) but this developer has no value.
 *    Setting a value makes it local.
 */
object VariableLogic {
    val VALID_NAME = Regex("^[A-Za-z0-9_]+$")

    enum class Scope { SHARED, LOCAL, MISSING }

    data class Row(
        val name: String,
        val scope: Scope,
        /** The committed value (first file wins), for shared variables. */
        val sharedValue: String?,
        /** team/file.json paths that define it, for shared variables. */
        val files: List<String>,
        /** This developer's value: an override for shared variables, the value for local ones. */
        val yourValue: String?,
        /** How many rules in the Rules tab use it. */
        val usedBy: Int,
        /** What to put in it, from the team files. */
        val description: String? = null,
        /** team/file.json paths that declare it as local (no value). */
        val declaredIn: List<String> = emptyList(),
    ) {
        /** The value rules are sent to the device with. */
        val effective: String? get() = yourValue ?: sharedValue
    }

    class SharedDefinition(val value: String, val files: List<String>, val description: String? = null)

    class Declaration(val description: String?, val files: List<String>)

    /** Shared definitions across [files] (path → content): first file's value wins, every file listed. */
    fun sharedDefinitions(files: Map<String, SharedRulesFile>): Map<String, SharedDefinition> {
        val values = linkedMapOf<String, String>()
        val descriptions = linkedMapOf<String, String>()
        val sources = linkedMapOf<String, MutableList<String>>()
        for ((path, content) in files) for (v in content.variables.orEmpty()) {
            if (v.isDeclaredLocal) continue
            val name = v.name?.takeIf { it.isNotBlank() } ?: continue
            values.putIfAbsent(name, v.value.orEmpty())
            v.description?.takeIf { it.isNotBlank() }?.let { descriptions.putIfAbsent(name, it) }
            sources.getOrPut(name) { mutableListOf() } += path
        }
        return values.mapValues { (name, value) -> SharedDefinition(value, sources.getValue(name), descriptions[name]) }
    }

    /** Variables declared local (`"local": true`) across [files]: description from the first file that has one. */
    fun declarations(files: Map<String, SharedRulesFile>): Map<String, Declaration> {
        val descriptions = linkedMapOf<String, String?>()
        val sources = linkedMapOf<String, MutableList<String>>()
        for ((path, content) in files) for (v in content.variables.orEmpty()) {
            if (!v.isDeclaredLocal) continue
            val name = v.name?.takeIf { it.isNotBlank() } ?: continue
            if (descriptions[name] == null) descriptions[name] = v.description?.takeIf { it.isNotBlank() }
            sources.getOrPut(name) { mutableListOf() } += path
        }
        return descriptions.mapValues { (name, d) -> Declaration(d, sources.getValue(name)) }
    }

    /** The declaration entry written to a file: name, `local: true`, and a description. */
    fun declarationOf(name: String, description: String?) =
        SharedVariable(name = name, local = true, description = description?.takeIf { it.isNotBlank() })

    /**
     * One row per variable, sorted by name.
     * @param local this developer's values (overrides and local variables)
     * @param studio Studio's variable list, which may hold variables made in Studio's own dialog
     * @param used variable name → number of rules using it
     */
    fun rows(
        shared: Map<String, SharedDefinition>,
        local: Map<String, String>,
        studio: Map<String, String>,
        used: Map<String, Int>,
        declared: Map<String, Declaration> = emptyMap(),
    ): List<Row> {
        val names = (shared.keys + declared.keys + local.keys + studio.keys + used.keys).toSortedSet(String.CASE_INSENSITIVE_ORDER)
        return names.map { name ->
            val def = shared[name]
            val decl = declared[name]
            val count = used[name] ?: 0
            val declaredIn = decl?.files.orEmpty()
            when {
                def != null -> Row(name, Scope.SHARED, def.value, def.files, local[name]?.takeIf { it.isNotEmpty() }, count, def.description ?: decl?.description, declaredIn)
                name in local -> Row(name, Scope.LOCAL, null, emptyList(), local.getValue(name), count, decl?.description, declaredIn)
                name in studio -> Row(name, Scope.LOCAL, null, emptyList(), studio.getValue(name), count, decl?.description, declaredIn)
                else -> Row(name, Scope.MISSING, null, emptyList(), null, count, decl?.description, declaredIn)
            }
        }
    }

    /** Variables rules use (or team files declare) that this developer has no value for. */
    fun missing(rows: List<Row>): List<Row> = rows.filter { it.scope == Scope.MISSING && (it.usedBy > 0 || it.declaredIn.isNotEmpty()) }

    /**
     * `adb shell am broadcast` extras that give the Appium interceptor this developer's values
     * (local variables and personal overrides), plus the names that can't be passed because the
     * receiver splits on commas.
     */
    fun adbVarArgs(rows: List<Row>): Pair<String?, List<String>> {
        val values = rows.filter { it.yourValue != null }.associate { it.name to it.yourValue!! }
        val (ok, bad) = values.entries.partition { ',' !in it.value && '"' !in it.value }
        val joined = ok.joinToString(",") { "${it.key}=${it.value}" }
        return (if (ok.isEmpty()) null else "--es var \"$joined\"") to bad.map { it.key }
    }

    /**
     * The variables Studio should hold: every shared variable (with this developer's value when
     * set) and every local one. Variables made in Studio's own dialog aren't listed, so they're left alone.
     */
    fun studioValues(shared: Map<String, SharedDefinition>, local: Map<String, String>): LinkedHashMap<String, String> {
        val out = linkedMapOf<String, String>()
        for ((name, def) in shared) out[name] = local[name]?.takeIf { it.isNotEmpty() } ?: def.value
        for ((name, value) in local) if (name !in shared) out[name] = value
        return out
    }

    /** [file] with variable [name] set to [value] (added at the end if new); a description is kept. */
    fun withVariable(file: SharedRulesFile, name: String, value: String, description: String? = null): SharedRulesFile =
        upsert(file, name) { old -> SharedVariable(name, value, null, description ?: old?.description) }

    /** [file] with [name] declared local (no value), replacing a shared value if it had one. */
    fun withDeclaration(file: SharedRulesFile, name: String, description: String?): SharedRulesFile =
        upsert(file, name) { old -> declarationOf(name, description ?: old?.description) }

    /** [file] with [name]'s description changed, if the file has that variable. */
    fun withDescription(file: SharedRulesFile, name: String, description: String?): SharedRulesFile {
        val vars = file.variables.orEmpty().map { if (it.name == name) it.copy(description = description?.takeIf { d -> d.isNotBlank() }) else it }
        return file.copy(variables = vars.toMutableList())
    }

    private fun upsert(file: SharedRulesFile, name: String, make: (SharedVariable?) -> SharedVariable): SharedRulesFile {
        val vars = file.variables.orEmpty().filter { it.name != null }.toMutableList()
        val i = vars.indexOfFirst { it.name == name }
        if (i >= 0) vars[i] = make(vars[i]) else vars += make(null)
        return file.copy(variables = vars)
    }

    /** [file] without variable [name]. */
    fun withoutVariable(file: SharedRulesFile, name: String): SharedRulesFile =
        file.copy(variables = file.variables.orEmpty().filter { it.name != name }.toMutableList())
}
