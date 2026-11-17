package com.example.mockrules

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Debug-build mocks driven by the shared network-inspector-rules files.
 *
 * Rules are read from the APK's assets (`assets/network-inspector-rules/<team>/<file>.json`, copied
 * from the repo at build time) and, if present, from
 * `/sdcard/Android/data/<package>/files/network-inspector-rules/`, where a test can push newer files
 * without rebuilding. A pushed file replaces the bundled file with the same path.
 *
 * What's enabled is saved, so it survives the app being restarted between test steps. Nothing is
 * enabled until a test asks for it (see [MockRulesReceiver]).
 */
object MockRules {
    const val TAG = "MockRules"
    private const val ROOT = "network-inspector-rules"
    private const val PREFS = "mock_rules"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_VARS = "vars"
    private const val KEY_CALL_SERVER = "always_call_server"
    private const val KEY_SCENARIO = "scenario"
    private const val KEY_RECORD = "record"
    private const val SCENARIOS = "scenarios.json"

    /** Under the app's external files dir: `<team>/<file>.json` recorded by record mode, to pull with adb. */
    const val RECORDINGS = "network-inspector-recordings"

    val engine = MockRulesEngine()

    @Volatile private var appContext: Context? = null

    /** Called by the debug installer. Cheap: the files are read on the first request, off the main thread. */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        engine.loader = { loadAll() }
    }

    /** Applies a change from a test and saves it. Returns a one-line summary for the test's log. */
    @Synchronized
    fun update(
        context: Context,
        enable: Collection<String> = emptyList(),
        disable: Collection<String> = emptyList(),
        only: Collection<String>? = null,
        clear: Boolean = false,
        setVariables: Map<String, String> = emptyMap(),
        unsetVariables: Collection<String> = emptyList(),
        alwaysCallServer: Boolean? = null,
        reload: Boolean = false,
        scenario: String? = null,
        /** Start record mode into this `team/file.json` (see [MockRulesRecorder]); null leaves it as it is. */
        record: String? = null,
        stopRecording: Boolean = false,
    ): String {
        init(context)
        // Load files and saved state first, so this change applies on top of earlier test steps.
        engine.ensureLoaded()

        var badRecordTarget: String? = null
        if (stopRecording && engine.recorder != null) {
            Log.i(TAG, "Recording stopped: ${engine.recorder?.target} (${engine.recorder?.count} rules)")
            engine.recorder = null
        }
        record?.let { raw ->
            val target = MockRulesRecorder.normalizeTarget(raw)
            if (target == null) badRecordTarget = raw else if (engine.recorder?.target != target) startRecording(context, target)
        }

        // A scenario works like `only` with its rules, plus its variable values; explicit extras in the
        // same broadcast still apply on top (so --es var can supply a value the scenario leaves out).
        val s = scenario?.let { engine.scenarios[it] }
        if (clear) engine.activeScenario = null
        if (scenario != null) engine.activeScenario = if (s != null) scenario else "$scenario (NOT FOUND)"

        var on = if (clear) emptySet() else engine.enabled
        if (s != null) on = s.enable.toSet()
        if (only != null) on = if (s != null) on + only else only.toSet()
        on = on + enable - disable.toSet()
        engine.enabled = on

        var vars = if (clear) emptyMap() else engine.variableOverrides
        if (s != null) vars = vars + s.variables
        vars = vars + setVariables - unsetVariables.toSet()
        engine.variableOverrides = vars

        if (clear) engine.alwaysCallServer = false
        alwaysCallServer?.let { engine.alwaysCallServer = it }

        prefs(context).edit()
            .putStringSet(KEY_ENABLED, on)
            .putStringSet(KEY_VARS, vars.map { (k, v) -> "$k=$v" }.toSet())
            .putBoolean(KEY_CALL_SERVER, engine.alwaysCallServer)
            .putString(KEY_SCENARIO, engine.activeScenario)
            .putString(KEY_RECORD, engine.recorder?.target)
            .apply()

        if (reload) {
            // Re-read the files; the saved state just written is read back with them.
            engine.loader = { loadAll() }
            engine.ensureLoaded()
        }
        val summary = summary() + (badRecordTarget?.let { " recording=INVALID TARGET \"$it\" (use team/file.json)" } ?: "")
        return summary.also { Log.i(TAG, it) }
    }

    /** `recorded/<date-time>.json`, for `--ez record true`. */
    fun defaultRecordingTarget(): String =
        "recorded/" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".json"

    /** The file a recording goes to, under the app's external files dir (pull it with adb). */
    fun recordingFile(context: Context, target: String): File? =
        context.getExternalFilesDir(null)?.let { File(File(it, RECORDINGS), target) }

    private fun startRecording(context: Context, target: String) {
        val file = recordingFile(context, target)
        if (file == null) {
            Log.w(TAG, "Can't record: no external files dir")
            return
        }
        val existing = file.takeIf { it.isFile }?.let { f -> runCatching { f.readText() }.getOrNull() }
        engine.recorder = MockRulesRecorder(
            target,
            variables = { engine.variables() },
            sink = { text ->
                file.parentFile?.mkdirs()
                file.writeText(text)
            },
            existingJson = existing,
        )
        Log.i(TAG, "Recording server responses to $file")
    }

    fun summary(): String {
        val active = engine.activeRules()
        val unknown = engine.enabled.filter { sel ->
            sel != "*" && engine.allRules.none { it.filePath == sel || it.team == sel || it.id == sel }
        }
        return buildString {
            engine.activeScenario?.let { append("scenario=").append(it).append(' ') }
            append("enabled=").append(engine.enabled.sorted())
            append(" activeRules=").append(active.size).append('/').append(engine.allRules.size)
            if (engine.variableOverrides.isNotEmpty()) append(" vars=").append(engine.variableOverrides)
            if (engine.alwaysCallServer) append(" alwaysCallServer=true")
            engine.recorder?.let { append(" recording=").append(it.target).append(" (").append(it.count).append(" rules)") }
            if (unknown.isNotEmpty()) append(" NOT FOUND=").append(unknown)
        }
    }

    private fun loadAll() {
        val context = appContext ?: return
        val files = linkedMapOf<String, String>()
        // 1) Bundled with the APK.
        val assets = context.assets
        for (team in assets.list(ROOT).orEmpty()) {
            for (name in assets.list("$ROOT/$team").orEmpty()) {
                if (!name.endsWith(".json", ignoreCase = true)) continue
                runCatching { assets.open("$ROOT/$team/$name").bufferedReader().use { it.readText() } }
                    .onSuccess { files["$team/$name"] = it }
            }
        }
        // 2) Pushed to the device by a test; these win.
        context.getExternalFilesDir(null)?.let { File(it, ROOT) }?.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }?.forEach { f ->
                runCatching { f.readText() }.onSuccess { files["${dir.name}/${f.name}"] = it }
            }
        }
        val errors = engine.load(files)
        errors.forEach { (path, error) -> Log.w(TAG, "Couldn't read $path: $error") }

        // scenarios.json at the root: bundled, or pushed (which wins).
        val pushedScenarios = context.getExternalFilesDir(null)?.let { File(File(it, ROOT), SCENARIOS) }?.takeIf { it.isFile }
        val scenariosJson = pushedScenarios?.let { runCatching { it.readText() }.getOrNull() }
            ?: runCatching { assets.open("$ROOT/$SCENARIOS").bufferedReader().use { it.readText() } }.getOrNull()
        engine.scenarios = scenariosJson?.let { json ->
            runCatching { MockRulesFormat.parseScenarios(json) }.onFailure { Log.w(TAG, "Couldn't read $SCENARIOS: ${it.message}") }.getOrNull()
        }.orEmpty()

        // Saved state from earlier test steps.
        val p = prefs(context)
        engine.enabled = p.getStringSet(KEY_ENABLED, emptySet()).orEmpty().toSet()
        engine.variableOverrides = p.getStringSet(KEY_VARS, emptySet()).orEmpty()
            .filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        engine.alwaysCallServer = p.getBoolean(KEY_CALL_SERVER, false)
        engine.activeScenario = p.getString(KEY_SCENARIO, null)
        // A recording in progress continues after the app restarts, adding to the same file.
        p.getString(KEY_RECORD, null)?.let { target -> if (engine.recorder?.target != target) startRecording(context, target) }
        Log.i(TAG, "Loaded ${engine.allRules.size} rule(s) from ${files.size} file(s) and ${engine.scenarios.size} scenario(s). ${summary()}")
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
