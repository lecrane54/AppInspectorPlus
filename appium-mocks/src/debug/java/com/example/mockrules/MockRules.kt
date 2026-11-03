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
    ): String {
        init(context)
        // Load files and saved state first, so this change applies on top of earlier test steps.
        engine.ensureLoaded()

        var on = if (clear) emptySet() else engine.enabled
        if (only != null) on = only.toSet()
        on = on + enable - disable.toSet()
        engine.enabled = on

        var vars = if (clear) emptyMap() else engine.variableOverrides
        vars = vars + setVariables - unsetVariables.toSet()
        engine.variableOverrides = vars

        if (clear) engine.alwaysCallServer = false
        alwaysCallServer?.let { engine.alwaysCallServer = it }

        prefs(context).edit()
            .putStringSet(KEY_ENABLED, on)
            .putStringSet(KEY_VARS, vars.map { (k, v) -> "$k=$v" }.toSet())
            .putBoolean(KEY_CALL_SERVER, engine.alwaysCallServer)
            .apply()

        if (reload) {
            // Re-read the files; the saved state just written is read back with them.
            engine.loader = { loadAll() }
            engine.ensureLoaded()
        }
        return summary().also { Log.i(TAG, it) }
    }

    fun summary(): String {
        val active = engine.activeRules()
        val unknown = engine.enabled.filter { sel ->
            sel != "*" && engine.allRules.none { it.filePath == sel || it.team == sel || it.id == sel }
        }
        return buildString {
            append("enabled=").append(engine.enabled.sorted())
            append(" activeRules=").append(active.size).append('/').append(engine.allRules.size)
            if (engine.variableOverrides.isNotEmpty()) append(" vars=").append(engine.variableOverrides)
            if (engine.alwaysCallServer) append(" alwaysCallServer=true")
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

        // Saved state from earlier test steps.
        val p = prefs(context)
        engine.enabled = p.getStringSet(KEY_ENABLED, emptySet()).orEmpty().toSet()
        engine.variableOverrides = p.getStringSet(KEY_VARS, emptySet()).orEmpty()
            .filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        engine.alwaysCallServer = p.getBoolean(KEY_CALL_SERVER, false)
        Log.i(TAG, "Loaded ${engine.allRules.size} rule(s) from ${files.size} file(s). ${summary()}")
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
