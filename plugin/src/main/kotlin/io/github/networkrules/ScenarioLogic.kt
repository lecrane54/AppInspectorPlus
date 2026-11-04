package io.github.networkrules

/**
 * Scenarios: named sets of shared rules (and optional variable values) that are turned on together,
 * stored in `network-inspector-rules/scenarios.json`. The same scenario works in Studio and, with
 * `--es scenario "<name>"`, in the Appium mocks. Kept free of Studio types so it can be unit tested.
 *
 * ```json
 * { "version": 1, "scenarios": [
 *   { "name": "checkout-declined", "description": "Card declined, session still valid",
 *     "enable": ["checkout/payment-declined.json", "[login] Session OK"],
 *     "variables": { "API_HOST": "staging.example.com" } } ] }
 * ```
 *
 * `enable` uses the Appium selectors: `team/file.json`, `team`, `[team] rule name`, or `*`.
 * Applying a scenario turns on exactly those shared rules and turns the other shared rules off;
 * rules you made yourself aren't touched.
 */
data class ScenariosFile(
    var version: Int? = 1,
    var scenarios: MutableList<SharedScenario>? = mutableListOf(),
)

data class SharedScenario(
    var name: String? = null,
    var description: String? = null,
    var enable: MutableList<String>? = mutableListOf(),
    /** Values used while the scenario is on. Committed, so never personal data. */
    var variables: MutableMap<String, String>? = null,
)

object ScenarioLogic {
    const val FILE = "scenarios.json"
    val VALID_NAME = Regex("""^[A-Za-z0-9][A-Za-z0-9 _.-]*$""")

    /** A shared rule as selectors see it: `[team] name`, its team and its `team/file.json`. */
    data class RuleRef(val studioName: String, val team: String, val file: String)

    fun matches(selector: String, ref: RuleRef): Boolean =
        selector == "*" || selector == ref.file || selector == ref.team || selector == ref.studioName

    /** The Studio names [selectors] turn on, and the selectors that match nothing. */
    fun resolve(selectors: List<String>, refs: List<RuleRef>): Pair<Set<String>, List<String>> {
        val on = linkedSetOf<String>()
        val notFound = mutableListOf<String>()
        for (sel in selectors.map { it.trim() }.filter { it.isNotEmpty() }) {
            val hits = refs.filter { matches(sel, it) }
            if (hits.isEmpty()) notFound += sel else hits.mapTo(on) { it.studioName }
        }
        return on to notFound
    }

    /**
     * The shortest selectors for [enabled]: a whole team when all its rules are on, else a whole
     * file when all its rules are on, else the single rules.
     */
    fun compact(enabled: Set<String>, refs: List<RuleRef>): List<String> {
        val out = mutableListOf<String>()
        for ((team, teamRefs) in refs.groupBy { it.team }.toSortedMap()) {
            val on = teamRefs.filter { it.studioName in enabled }
            when {
                on.isEmpty() -> continue
                on.size == teamRefs.size -> out += team
                else -> for ((file, fileRefs) in teamRefs.groupBy { it.file }.toSortedMap()) {
                    val fileOn = fileRefs.filter { it.studioName in enabled }
                    when {
                        fileOn.isEmpty() -> {}
                        fileOn.size == fileRefs.size -> out += file
                        else -> fileOn.mapTo(out) { it.studioName }
                    }
                }
            }
        }
        return out
    }

    fun find(file: ScenariosFile?, name: String): SharedScenario? = file?.scenarios.orEmpty().firstOrNull { it.name == name }

    /** [file] with [scenario] added, or replacing the one with the same name (keeping its place). */
    fun upsert(file: ScenariosFile?, scenario: SharedScenario): ScenariosFile {
        val list = file?.scenarios.orEmpty().filter { it.name != null }.toMutableList()
        val i = list.indexOfFirst { it.name == scenario.name }
        if (i >= 0) list[i] = scenario else list += scenario
        return ScenariosFile(1, list)
    }

    fun remove(file: ScenariosFile?, name: String): ScenariosFile =
        ScenariosFile(1, file?.scenarios.orEmpty().filter { it.name != name }.toMutableList())

    /** A command a test (or a person) can run to apply [name] in the Appium mocks. */
    fun adbCommand(name: String): String =
        "adb shell am broadcast -a com.example.mockrules.CONTROL -p <your.package> -f 0x20 --es scenario \"$name\""
}
