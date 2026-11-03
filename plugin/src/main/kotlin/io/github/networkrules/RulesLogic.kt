package io.github.networkrules

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.security.MessageDigest

/**
 * The sync rules that don't touch Android Studio or the IDE: naming, merging, export choices,
 * variable overrides, change detection. Kept free of Studio types so it can be unit tested.
 */
object RulesLogic {
    val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** A team folder name: letters, digits, spaces, '_', '-', '.', not starting with a space or dot. */
    val VALID_TEAM = Regex("""^[A-Za-z0-9_-][A-Za-z0-9 _.-]*$""")

    /** An Export destination: team/file.json. */
    val VALID_TARGET = Regex("""^[A-Za-z0-9 _.-]+/[A-Za-z0-9 _.-]+\.json$""")

    private val PREFIX = Regex("""^\[([^\]]+)] (.*)$""")
    private val VAR_REF = Regex("""\$\{([A-Za-z0-9_]+)}""")
    private val ERROR_POSITION = Regex("""line (\d+) column (\d+)""")

    // ---- names ---------------------------------------------------------------------------------

    fun studioName(team: String, name: String) = "[$team] $name"
    fun baseName(studioName: String) = PREFIX.matchEntire(studioName)?.groupValues?.get(2) ?: studioName
    fun teamOf(studioName: String): String? = PREFIX.matchEntire(studioName)?.groupValues?.get(1)

    /** Keeps a picked file's name, but only with characters a team file path allows. */
    fun sanitizeFileName(name: String): String {
        val base = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _.-]"), "-")
            .trim('-', '.', ' ').ifEmpty { "imported" }
        return "$base.json"
    }

    // ---- import --------------------------------------------------------------------------------

    /** Rule names in [existing] that importing [incoming] would replace. */
    fun importCollisions(existing: SharedRulesFile?, incoming: SharedRulesFile): List<String> {
        val names = existing?.rules.orEmpty().mapNotNull { it.name }.toSet()
        return incoming.rules.orEmpty().mapNotNull { it.name }.filter { it in names }
    }

    /** Imported rules replace same-named rules and are appended otherwise; imported variables win. */
    fun mergeImport(existing: SharedRulesFile?, incoming: SharedRulesFile): SharedRulesFile {
        val rules = existing?.rules.orEmpty().filter { it.name != null }.toMutableList()
        for (r in incoming.rules.orEmpty()) {
            val i = rules.indexOfFirst { it.name == r.name }
            if (i >= 0) rules[i] = r else rules += r
        }
        val vars = (existing?.variables.orEmpty() + incoming.variables.orEmpty())
            .filter { it.name != null }.associateBy { it.name!! }.values.toMutableList()
        return SharedRulesFile(1, vars, rules)
    }

    // ---- export --------------------------------------------------------------------------------

    /**
     * A file's rules after an export, in file order: unchecked rules are dropped and checked rules
     * take Studio's version ([studio], keyed by Studio name) when Studio has one.
     */
    fun exportedFileRules(
        team: String,
        fileRules: List<SharedRule>,
        selected: Set<String>,
        studio: Map<String, SharedRule>,
    ): MutableList<SharedRule> = fileRules.mapNotNull { r ->
        val base = r.name ?: return@mapNotNull null
        val sName = studioName(team, base)
        if (sName !in selected) return@mapNotNull null
        studio[sName]?.copy(name = base) ?: r
    }.toMutableList()

    /** Adds or replaces [rule] in [rules] by name. */
    fun upsert(rules: MutableList<SharedRule>, rule: SharedRule) {
        val i = rules.indexOfFirst { it.name == rule.name }
        if (i >= 0) rules[i] = rule else rules += rule
    }

    /** `${NAME}` references anywhere in [rules]. */
    fun usedVariables(rules: List<SharedRule>): Set<String> =
        rules.flatMap { r -> VAR_REF.findAll(gson.toJson(r)).map { it.groupValues[1] } }.toSet()

    /**
     * The variables written to an exported file: everything the file already had, plus Studio's
     * value of each variable the rules use. A variable with a personal override keeps the shared
     * value instead, so a developer's own value is never committed.
     */
    fun exportVariables(
        existing: List<SharedVariable>,
        studio: Map<String, String>,
        used: Set<String>,
        overridden: Set<String>,
        sharedDefaults: Map<String, String>,
    ): MutableList<SharedVariable> {
        val out = linkedMapOf<String, SharedVariable>()
        for (v in existing) v.name?.let { out[it] = v }
        for (name in used) {
            if (name in overridden) {
                if (name !in out) sharedDefaults[name]?.let { out[name] = SharedVariable(name, it) }
            } else {
                studio[name]?.let { out[name] = SharedVariable(name, it) }
            }
        }
        return out.values.toMutableList()
    }

    // ---- variables -----------------------------------------------------------------------------

    /** The shared value of each variable across files (first file wins), with personal [overrides] applied. */
    fun effectiveVariables(files: List<SharedRulesFile>, overrides: Map<String, String>): LinkedHashMap<String, String> {
        val out = linkedMapOf<String, String>()
        for (f in files) for (v in f.variables.orEmpty()) {
            val name = v.name ?: continue
            if (name !in out) out[name] = v.value.orEmpty()
        }
        for (name in out.keys.toList()) overrides[name]?.let { out[name] = it }
        return out
    }

    // ---- change detection ----------------------------------------------------------------------

    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Files whose content differs from what was last loaded or written (or that weren't loaded at all). */
    fun staleFiles(recorded: Map<String, String>, current: Map<String, String>): List<String> =
        current.filter { (path, h) -> recorded[path] != h }.keys.sorted()

    // ---- turning rules on and off by file ------------------------------------------------------

    class FileActivity(val path: String, val on: Int, val total: Int) {
        val allOn get() = total > 0 && on == total
    }

    /**
     * What to set each file's rules to, given the files the developer checked. A checked file is
     * turned fully on; a file that was fully on and is now unchecked is turned off; a partly-on file
     * left unchecked is untouched unless [exclusive] ("turn off everything else") is set.
     */
    fun activityPlan(files: List<FileActivity>, checked: Set<String>, exclusive: Boolean): Map<String, Boolean> {
        val plan = linkedMapOf<String, Boolean>()
        for (f in files) {
            when {
                f.path in checked -> if (!f.allOn) plan[f.path] = true
                f.allOn || (exclusive && f.on > 0) -> plan[f.path] = false
            }
        }
        return plan
    }

    // ---- renaming teams ------------------------------------------------------------------------

    /** `[old] name` → `[new] name`; anything else unchanged. */
    fun renameStudioName(studioName: String, old: String, new: String): String =
        if (teamOf(studioName) == old) studioName(new, baseName(studioName)) else studioName

    /** `old/file.json` → `new/file.json`; anything else unchanged. */
    fun renamePath(relPath: String, old: String, new: String): String =
        if (relPath.startsWith("$old/")) "$new/" + relPath.removePrefix("$old/") else relPath

    // ---- errors --------------------------------------------------------------------------------

    /** Zero-based (line, column) from a Gson parse error such as "... at line 5 column 12 path $.rules". */
    fun errorPosition(message: String?): Pair<Int, Int>? {
        val m = ERROR_POSITION.find(message ?: return null) ?: return null
        return (m.groupValues[1].toInt() - 1).coerceAtLeast(0) to (m.groupValues[2].toInt() - 1).coerceAtLeast(0)
    }
}
