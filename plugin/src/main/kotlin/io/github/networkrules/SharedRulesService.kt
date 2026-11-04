package io.github.networkrules

import com.android.tools.idea.appinspection.inspectors.network.model.rules.RuleData
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.CommonActionsPanel
import com.intellij.ui.table.TableView
import java.awt.BorderLayout
import java.awt.Container
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/** One rules file inside a team folder, e.g. `login/session-expired.json`. */
class RulesFileRef(val team: String, val file: VirtualFile) {
    val relPath: String get() = "$team/${file.name}"
}

class LoadedRulesFile(val ref: RulesFileRef, val content: SharedRulesFile?, val error: String?, val hash: String)

/**
 * Syncs Android Studio's Network Inspector Rules tab with team folders committed to the repo:
 *
 * ```
 * network-inspector-rules/
 *   login/      session-expired.json  mfa.json
 *   checkout/   payment-declined.json
 * ```
 *
 * Each subfolder is a team. In Studio, a shared rule is named `[team] <name>`. That name is the sync
 * key and keeps two teams' rules apart even when they use the same name. A refresh replaces every
 * Studio rule that came from the folders (now or at the last refresh) with the file's version; rules
 * you created yourself without a `[team]` prefix are never touched.
 */
@Service(Service.Level.PROJECT)
@State(name = "NetworkRulesSync", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class SharedRulesService(private val project: Project) :
    PersistentStateComponent<SharedRulesService.Data>, Disposable, CollapsedTeams {

    class Data {
        /** Studio names imported at the last refresh, so a rule deleted from a file is removed here too. */
        var importedNames: MutableSet<String> = linkedSetOf()

        /** Studio name → file it came from (team/file.json); used by Export. */
        var origins: MutableMap<String, String> = linkedMapOf()

        /** Teams this developer has hidden. New team folders are shown by default. */
        var hiddenTeams: MutableSet<String> = linkedSetOf()

        /** team/file.json → hash of the content last loaded or written, so Export can spot files changed since. */
        var fileHashes: MutableMap<String, String> = linkedMapOf()

        /**
         * This developer's own variable values: overrides of shared variables, and local variables
         * that exist only on this machine. Never written to the shared files.
         */
        var localVariables: MutableMap<String, String> = linkedMapOf()

        /** The scenario this developer turned on, if any (scenarios.json). */
        var activeScenario: String? = null

        /** Groups this developer collapsed in the Rules tab ("" is the local rules group). */
        var collapsedTeams: MutableSet<String> = linkedSetOf()
    }

    private var data = Data()
    override fun getState() = data
    override fun loadState(state: Data) {
        data = state
    }

    @Volatile
    private var suppressChangeNotification = false

    override val collapsed: Set<String> get() = data.collapsedTeams
    override fun isCollapsed(key: String) = key in data.collapsedTeams
    override fun setCollapsed(key: String, collapsed: Boolean) {
        if (collapsed) data.collapsedTeams += key else data.collapsedTeams -= key
    }

    val rootPath: String? get() = project.basePath?.let { "$it/$ROOT_DIR" }
    fun rootDir(): VirtualFile? = rootPath?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }

    /** All team folder names, sorted. */
    fun allTeams(): List<String> =
        rootDir()?.children.orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.map { it.name }.sorted()

    fun visibleTeams(): List<String> = allTeams().filter { it !in data.hiddenTeams }
    val hiddenTeams: Set<String> get() = data.hiddenTeams

    fun setHiddenTeams(hidden: Set<String>) {
        data.hiddenTeams = hidden.toMutableSet()
        refresh()
    }

    fun loadFiles(teams: Collection<String> = visibleTeams()): List<LoadedRulesFile> {
        val root = rootDir() ?: return emptyList()
        return teams.mapNotNull { root.findChild(it) }.flatMap { dir ->
            dir.children.filter { !it.isDirectory && it.extension.equals("json", true) }.sortedBy { it.name }
                .map { f -> parsed(RulesFileRef(dir.name, f)) }
        }
    }

    private class CachedFile(val stamp: String, val loaded: LoadedRulesFile)

    /** Parsed team files by path, reused until the file (or its unsaved editor document) changes. */
    private val parseCache = java.util.concurrent.ConcurrentHashMap<String, CachedFile>()

    /**
     * Reads and parses a team file, or returns the cached result if it hasn't changed. Callers treat
     * the content as read-only: every change builds new objects (see VariableLogic, RulesLogic).
     */
    private fun parsed(ref: RulesFileRef): LoadedRulesFile {
        val f = ref.file
        val doc = FileDocumentManager.getInstance().getCachedDocument(f)
        val stamp = "${f.modificationStamp}:${doc?.modificationStamp ?: -1}"
        parseCache[f.path]?.takeIf { it.stamp == stamp }?.let { return LoadedRulesFile(ref, it.loaded.content, it.loaded.error, it.loaded.hash) }
        val loaded = run {
            val text = try {
                doc?.text ?: VfsUtilCore.loadText(f)
            } catch (e: Exception) {
                return@run LoadedRulesFile(ref, null, e.message ?: e.javaClass.simpleName, "")
            }
            try {
                LoadedRulesFile(ref, RuleMapper.gson.fromJson(text, SharedRulesFile::class.java) ?: SharedRulesFile(), null, RulesLogic.hash(text))
            } catch (e: Exception) {
                LOG.warn("Failed to parse ${f.path}", e)
                LoadedRulesFile(ref, null, e.message ?: e.javaClass.simpleName, RulesLogic.hash(text))
            }
        }
        parseCache[f.path] = CachedFile(stamp, loaded)
        return loaded
    }

    /** Every existing rules file, as team/file.json, for Export's destination picker. */
    fun allFilePaths(): List<String> = loadFiles(allTeams()).map { it.ref.relPath }

    // ---- lifecycle ---------------------------------------------------------------------------

    /** Called once on project open (EDT). */
    fun start() {
        // Import into Studio's saved list now, so the Rules tab already shows the rules when it opens.
        if (rootDir() != null) refresh(notifyResult = false)
        watchFolder()
        startToolbarInjection()
    }

    private fun watchFolder() {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val root = rootPath ?: return
                if (suppressChangeNotification || events.none { it.path == root || it.path.startsWith("$root/") }) return
                ApplicationManager.getApplication().invokeLater({ onSharedFilesChanged() }, project.disposed)
            }
        })
    }

    private fun onSharedFilesChanged() {
        if (NetworkInspectorBridge.findLiveTable(project) == null) {
            // No inspector session, so update the saved list silently; the next session picks it up.
            refresh(notifyResult = false)
            return
        }
        notification("Shared network rules changed", "$ROOT_DIR/ was updated (e.g. by git pull).", NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Refresh now") { refresh() })
            .notify(project)
    }

    // ---- refresh (files → Studio) ---------------------------------------------------------------

    fun refresh(notifyResult: Boolean = true) {
        if (rootDir() == null) {
            if (notifyResult) {
                notification(
                    "No shared rules folder",
                    "Use Create Team… (or Export) to create $ROOT_DIR/&lt;team&gt;/ at the project root.",
                    NotificationType.WARNING,
                ).notify(project)
            }
            // Still run: if every team folder was deleted, previously imported rules get removed.
        }
        val files = loadFiles()
        files.filter { it.error != null }.takeIf { it.isNotEmpty() }?.let(::notifyUnreadable)

        // Variables first: rules are resolved against them when they're sent to the device. If a
        // value changed, every rule is re-sent below; otherwise only rules that changed are.
        val variablesChanged = applyVariablesToStudio(resend = false)

        // Desired Studio rules: "[team] name" → (rule, origin). A duplicate name in a team keeps the first.
        val desired = linkedMapOf<String, Pair<SharedRule, String>>()
        val duplicates = mutableListOf<String>()
        for (f in files) for (r in f.content?.rules.orEmpty()) {
            val name = r.name?.takeIf { it.isNotBlank() } ?: continue
            val studioName = studioName(f.ref.team, name)
            if (studioName in desired) duplicates += "$studioName (${f.ref.relPath})" else desired[studioName] = r to f.ref.relPath
        }

        val live = NetworkInspectorBridge.findLiveTable(project)
        val persisted = NetworkInspectorBridge.persistedRules(project)
        val current: List<RuleData> = live?.rules ?: persisted
        val managed = data.importedNames + desired.keys

        // Only what changed is touched: a rule whose file version changed is updated in place (Studio
        // re-sends just that rule), a rule gone from its file is removed, a new one is added. Unchanged
        // rules keep their place, selection and on/off state, and aren't re-sent to the device.
        // Brand-new shared rules start off, so pulling a teammate's rules never starts rewriting
        // your traffic unexpectedly.
        val existingNames = mutableSetOf<String>()
        var updated = 0
        RulesTableGroups.bulkUpdate = true
        try {
            for (rule in current.filter { it.name in managed }.toList()) {
                val wanted = desired[rule.name]
                if (wanted == null || !existingNames.add(rule.name)) {
                    // Gone from the files, or a duplicate of a name already kept.
                    if (live != null) live.remove(rule) else persisted.remove(rule)
                    continue
                }
                if (RuleMapper.copyInto(rule, wanted.first.copy(name = rule.name))) updated++
            }
            for ((studioName, entry) in desired) {
                if (studioName in existingNames) continue
                val rule = RuleMapper.toRuleData(entry.first.copy(name = studioName), RuleData.newId(), false)
                if (live != null) live.add(rule) else persisted.add(rule)
            }
            if (variablesChanged) live?.resendAll()
        } finally {
            RulesTableGroups.bulkUpdate = false
        }
        LOG.debug("Refresh: ${desired.size} shared rule(s), $updated updated, ${desired.size - existingNames.size} added")
        data.importedNames = desired.keys.toMutableSet()
        data.origins = desired.mapValuesTo(linkedMapOf()) { it.value.second }
        data.fileHashes = files.associateTo(linkedMapOf()) { it.ref.relPath to it.hash }

        checkMissingVariables()
        if (duplicates.isNotEmpty()) {
            notification("Duplicate rule names skipped", duplicates.joinToString("<br>") { esc(it) }, NotificationType.WARNING).notify(project)
        }
        if (notifyResult) {
            val where = if (live != null) "and sent to the device" else "(applied when Network Inspector next connects)"
            val newOnes = desired.keys.count { it !in existingNames }
            val teams = visibleTeams().joinToString().ifEmpty { "none" }
            notification(
                "Shared network rules refreshed",
                "${desired.size} rule(s) from teams: ${esc(teams)}, loaded $where." +
                    if (newOnes > 0) "<br>$newOnes new rule(s) start disabled; tick them to enable." else "",
                NotificationType.INFORMATION,
            ).notify(project)
        }
    }

    /** One notification listing the broken files, with a link per file that opens it at the error. */
    private fun notifyUnreadable(bad: List<LoadedRulesFile>) {
        val n = notification(
            "Some shared rule files couldn't be read",
            bad.joinToString("<br>") { "${esc(it.ref.relPath)}: ${esc(it.error.orEmpty())}" },
            NotificationType.ERROR,
        )
        for (f in bad.take(MAX_FILE_LINKS)) {
            n.addAction(NotificationAction.createSimple("Open ${f.ref.relPath}") {
                val (line, col) = RulesLogic.errorPosition(f.error) ?: (0 to 0)
                if (f.ref.file.isValid) OpenFileDescriptor(project, f.ref.file, line, col).navigate(true)
            })
        }
        n.notify(project)
    }

    // ---- export (Studio → files) ----------------------------------------------------------------

    /** Studio's current rules (live session if there is one, otherwise the saved list). */
    fun studioRules(): List<RuleData> =
        NetworkInspectorBridge.findLiveTable(project)?.rules ?: NetworkInspectorBridge.persistedRules(project)

    /** Where a Studio rule is saved on export, or null if it's local (needs a destination). */
    fun originOf(studioName: String): String? = data.origins[studioName]

    /**
     * Loaded files that changed on disk since the last refresh or write (e.g. a `git pull` during a
     * live session). Exporting over them would replace a teammate's edits and drop rules not loaded yet.
     */
    fun staleFiles(): List<String> =
        RulesLogic.staleFiles(data.fileHashes, loadFiles().filter { it.content != null }.associate { it.ref.relPath to it.hash })

    /**
     * Writes the selected rules to their team files.
     *  - Rules that came from a file go back to that file. Studio's version wins over the file's.
     *  - Selected local rules go to [newRulesTarget] (team/file.json), and are renamed in Studio to
     *    "[team] name" so later refreshes match them.
     *  - Shared rules left unchecked are removed from their file.
     */
    fun export(selected: Set<String>, newRulesTarget: String?) {
        val studio = studioRules().associateBy { it.name }
        val studioShared = studio.filterKeys { it in selected }.mapValues { RuleMapper.toShared(it.value) }
        val files = loadFiles().filter { it.content != null }.associateBy { it.ref.relPath }
        val existing = files.mapValuesTo(linkedMapOf()) { it.value.content!! }
        val output = linkedMapOf<String, MutableList<SharedRule>>()
        val renames = mutableMapOf<RuleData, String>()

        // 1) Existing files: keep file order, drop unchecked rules, take Studio's version when it exists.
        for ((path, f) in files) {
            output[path] = RulesLogic.exportedFileRules(f.ref.team, f.content!!.rules.orEmpty(), selected, studioShared)
        }
        // 2) Shared rules whose file disappeared, plus local rules: these go to newRulesTarget.
        val needsTarget = selected.filter { n -> studio.containsKey(n) && files[originOf(n)] == null }
        if (needsTarget.isNotEmpty()) {
            val target = newRulesTarget ?: error("No destination chosen for new rules")
            val team = target.substringBefore('/')
            // The target may be a file of a hidden team that wasn't loaded above; keep what it has.
            if (target !in output) {
                existingContent(target)?.let { existing[target] = it }
            }
            val list = output.getOrPut(target) { existing[target]?.rules.orEmpty().filter { it.name != null }.toMutableList() }
            for (n in needsTarget) {
                val base = baseName(n)
                RulesLogic.upsert(list, RuleMapper.toShared(studio.getValue(n)).copy(name = base))
                renames[studio.getValue(n)] = studioName(team, base)
            }
        }

        // 3) Write only the files whose contents changed. Each file carries the variables its rules use.
        val studioVars = NetworkInspectorBridge.variables(project).associate { it.name to it.value }
        val sharedDefaults = RulesLogic.effectiveVariables(loadFiles(allTeams()).mapNotNull { it.content }, emptyMap())
        val declared = declaredLocalVariables()
        var written = 0
        for ((path, rules) in output) {
            val vars = RulesLogic.exportVariables(
                existing[path]?.variables.orEmpty(), studioVars, RulesLogic.usedVariables(rules),
                data.localVariables.keys, sharedDefaults, declared,
            )
            val text = RuleMapper.gson.toJson(SharedRulesFile(version = 1, variables = vars, rules = rules)) + "\n"
            val old = rootDir()?.findFileByRelativePath(path)?.let { VfsUtilCore.loadText(it) }
            if (text != old) {
                writeFile(path, text)
                written++
            }
        }

        // 4) Rename newly shared rules in Studio. RuleData.setName notifies Studio's listener, which syncs the device.
        for ((rule, newName) in renames) rule.name = newName
        refresh(notifyResult = false)

        val localUsed = RulesLogic.usedVariables(output.values.flatten()) - sharedDefaults.keys
        val undeclared = localUsed - declared.keys
        notification(
            "Exported to $written file(s)",
            "Saved under $ROOT_DIR/. Commit the changes to share them with your team." +
                (if (localUsed.isEmpty()) "" else "<br>Local variables weren't exported, only referenced: ${esc(localUsed.sorted().joinToString())}.") +
                (if (undeclared.isEmpty()) "" else "<br>To have teammates asked for a value, use Variables → Share in Team File → " +
                    "<i>Declare only</i> for: ${esc(undeclared.sorted().joinToString())}."),
            NotificationType.INFORMATION,
        ).notify(project)
    }

    // ---- import (outside file → team folder) ----------------------------------------------------

    /** Parses a rules file picked from anywhere on disk. Throws with a readable message if it isn't one. */
    fun parseImport(file: VirtualFile): SharedRulesFile {
        val parsed = try {
            RuleMapper.gson.fromJson(VfsUtilCore.loadText(file), SharedRulesFile::class.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("not valid JSON (${e.message ?: e.javaClass.simpleName})")
        } ?: throw IllegalArgumentException("the file is empty")
        val rules = parsed.rules.orEmpty().filter { !it.name.isNullOrBlank() }
        require(rules.isNotEmpty()) { "no named rules found (expected a \"rules\" list like the files in $ROOT_DIR/)" }
        // Imported rules arrive under a team prefix, so strip one if the file was exported with Studio names.
        return SharedRulesFile(
            version = 1,
            variables = parsed.variables.orEmpty().filter { !it.name.isNullOrBlank() }.toMutableList(),
            rules = rules.map { it.copy(name = baseName(it.name!!)) }.toMutableList(),
        )
    }

    /** Rule names in [team]/[fileName] that an import of [incoming] would overwrite. */
    fun importCollisions(team: String, fileName: String, incoming: SharedRulesFile): List<String> =
        RulesLogic.importCollisions(existingContent("$team/$fileName"), incoming)

    /**
     * Copies imported rules into `team/fileName`. If that file already exists, the rules are merged:
     * an imported rule replaces the existing rule with the same name, others are appended.
     * The team is un-hidden so the rules show up, then the folders are refreshed into Studio.
     */
    fun importRules(imports: List<Pair<String, SharedRulesFile>>, team: String) {
        var ruleCount = 0
        for ((fileName, incoming) in imports) {
            val relPath = "$team/$fileName"
            val merged = RulesLogic.mergeImport(existingContent(relPath), incoming)
            writeFile(relPath, RuleMapper.gson.toJson(merged) + "\n", "Import Network Rules")
            ruleCount += incoming.rules.orEmpty().size
        }
        data.hiddenTeams.remove(team)
        refresh(notifyResult = false)
        notification(
            "Imported $ruleCount rule(s) into ${esc(team)}",
            "Saved under $ROOT_DIR/${esc(team)}/. Imported rules start disabled; tick them in the Rules tab to enable. " +
                "Commit the changes to share them with your team.",
            NotificationType.INFORMATION,
        ).notify(project)
    }

    // ---- new rules from code (Create Network Rule from Endpoint) ---------------------------------

    /**
     * Adds [rule] to Studio's Rules tab as a local rule (not shared until exported). A taken name gets
     * " (2)" etc. In a live session Studio sends it to the device straight away. Returns the name used.
     */
    fun addLocalRule(rule: SharedRule, active: Boolean): String {
        val name = EndpointLogic.uniqueName(rule.name ?: "New rule", studioRules().map { it.name }.toSet())
        val rd = RuleMapper.toRuleData(rule.copy(name = name), RuleData.newId(), active)
        val live = NetworkInspectorBridge.findLiveTable(project)
        if (live != null) live.add(rd) else NetworkInspectorBridge.persistedRules(project).add(rd)
        val where = when {
            live == null -> "It's applied when Network Inspector next connects."
            active -> "It's on and was sent to the device."
            else -> "It's off; tick it in the Rules tab to enable."
        }
        notification("Added rule \"${esc(name)}\"", "$where Use Export to share it with your team.", NotificationType.INFORMATION)
            .notify(project)
        return name
    }

    /** True if [target] (team/file.json) already has a rule named [name]. */
    fun fileHasRule(target: String, name: String): Boolean =
        existingContent(target)?.rules.orEmpty().any { it.name == name }

    /**
     * Saves [rule] into [target] (team/file.json), replacing a same-named rule, then refreshes it into
     * Studio. The file also gets the shared variables the rule uses, never a personal value.
     */
    fun addRuleToFile(target: String, rule: SharedRule, active: Boolean) {
        val team = target.substringBefore('/')
        writeRuleToFile(target, rule, "Add Network Rule")
        data.hiddenTeams.remove(team)
        refresh(notifyResult = false)

        val studioName = studioName(team, rule.name.orEmpty())
        if (active) studioRules().firstOrNull { it.name == studioName }?.isActive = true
        NetworkInspectorBridge.findLiveTable(project)?.view?.table?.repaint()
        notification(
            "Added rule \"${esc(studioName)}\"",
            "Saved to $ROOT_DIR/${esc(target)}" + (if (active) " and turned on" else "") + ". Commit the file to share it.",
            NotificationType.INFORMATION,
        ).notify(project)
    }

    /** Upserts [rule] (named without its team prefix) into [target], with the shared variables its rules use. */
    private fun writeRuleToFile(target: String, rule: SharedRule, commandName: String) {
        val existing = existingContent(target)
        val rules = existing?.rules.orEmpty().filter { it.name != null }.toMutableList()
        RulesLogic.upsert(rules, rule)
        val vars = RulesLogic.exportVariables(
            existing?.variables.orEmpty(),
            NetworkInspectorBridge.variables(project).associate { it.name to it.value },
            RulesLogic.usedVariables(rules),
            data.localVariables.keys,
            sharedVariables(),
            declaredLocalVariables(),
        )
        writeFile(target, RuleMapper.gson.toJson(SharedRulesFile(1, vars, rules)) + "\n", commandName)
    }

    /** The rules files in a team folder, e.g. `rules.json`, sorted. */
    fun teamFiles(team: String): List<String> =
        rootDir()?.findChild(team)?.children.orEmpty()
            .filter { !it.isDirectory && it.extension.equals("json", true) }.map { it.name }.sorted()

    /**
     * The Studio name [rule] would get in [target], or a reason it can't be saved there: another
     * file in that team, or another rule in the Rules tab, already uses that name.
     */
    fun checkSaveToTeam(rule: RuleData, target: String): String? {
        val base = baseName(rule.name).trim()
        if (base.isEmpty()) return "Give the rule a name first."
        val newName = studioName(target.substringBefore('/'), base)
        val otherFile = data.origins[newName]
        if (otherFile != null && otherFile != target) return "$otherFile already has a rule named \"$base\". Rename this rule, or save it to $otherFile."
        if (studioRules().any { it !== rule && it.name == newName && it.name !in data.importedNames }) {
            return "Another rule in your Rules tab is already named \"$newName\". Rename one of them first."
        }
        return null
    }

    /**
     * Saves a rule from the Rules tab into [target] (team/file.json), with Studio's current version:
     *  - a local rule becomes the team's shared rule `[team] name`, and the local copy is replaced by it;
     *  - a shared rule is written back to [target], and removed from its old file if it moved.
     * The rule keeps its on/off state and stays selected. Returns its Studio name.
     */
    fun saveRuleToTeam(rule: RuleData, target: String): String {
        checkSaveToTeam(rule, target)?.let { throw IllegalStateException(it) }
        val team = target.substringBefore('/')
        val base = baseName(rule.name).trim()
        val newName = studioName(team, base)
        val oldOrigin = originOf(rule.name)
        val wasActive = rule.isActive

        writeRuleToFile(target, RuleMapper.toShared(rule).copy(name = base), "Save Network Rule to Team")
        if (oldOrigin != null && oldOrigin != target) {
            existingContent(oldOrigin)?.let { old ->
                val oldBase = baseName(rule.name)
                val kept = old.copy(rules = old.rules.orEmpty().filter { it.name != oldBase }.toMutableList())
                writeFile(oldOrigin, RuleMapper.gson.toJson(kept) + "\n", "Save Network Rule to Team")
            }
        }
        if (oldOrigin == null) {
            // The local copy is replaced by the shared rule the refresh loads from the file.
            val live = NetworkInspectorBridge.findLiveTable(project)
            if (live != null) live.remove(rule) else NetworkInspectorBridge.persistedRules(project).remove(rule)
        }
        data.hiddenTeams.remove(team)
        setCollapsed(team, false)
        refresh(notifyResult = false)

        val saved = studioRules().firstOrNull { it.name == newName }
        if (saved != null && saved.isActive != wasActive) saved.isActive = wasActive
        NetworkInspectorBridge.findLiveTable(project)?.let { live ->
            val modelRow = live.rules.indexOfFirst { it.name == newName }
            val table = live.view.table
            val viewRow = if (modelRow >= 0) table.convertRowIndexToView(modelRow) else -1
            if (viewRow >= 0) {
                table.selectionModel.setSelectionInterval(viewRow, viewRow)
                table.scrollRectToVisible(table.getCellRect(viewRow, 0, true))
            }
            table.repaint()
        }
        notification(
            "Saved \"${esc(newName)}\"",
            "Saved to $ROOT_DIR/${esc(target)}. Commit the file to share it.",
            NotificationType.INFORMATION,
        ).notify(project)
        return newName
    }

    // ---- teams ----------------------------------------------------------------------------------

    /**
     * Creates a team folder with an empty starter rules.json (git doesn't track empty folders,
     * and the file gives Export a ready destination). Returns the relative path of the new file.
     */
    fun createTeam(team: String): String {
        check(team !in allTeams()) { "Team \"$team\" already exists." }
        val relPath = "$team/$STARTER_FILE"
        writeFile(relPath, RuleMapper.gson.toJson(SharedRulesFile()) + "\n", "Create Network Rules Team")
        refresh(notifyResult = false)
        return relPath
    }

    /**
     * Renames a team folder. Its Studio rules are renamed from `[old] x` to `[new] x` in place,
     * so each developer's on/off choices survive, and a hidden team stays hidden.
     */
    fun renameTeam(old: String, new: String) {
        check(new !in allTeams()) { "Team \"$new\" already exists." }
        val dir = rootDir()?.findChild(old) ?: throw IllegalStateException("Team \"$old\" no longer exists.")
        writeAction("Rename Network Rules Team") { dir.rename(this, new) }

        if (data.collapsedTeams.remove(old)) data.collapsedTeams += new
        for (rule in studioRules()) {
            if (rule.name in data.importedNames) rule.name = RulesLogic.renameStudioName(rule.name, old, new)
        }
        data.importedNames = data.importedNames.mapTo(linkedSetOf()) { RulesLogic.renameStudioName(it, old, new) }
        data.origins = data.origins.entries.associateTo(linkedMapOf()) { (k, v) ->
            RulesLogic.renameStudioName(k, old, new) to RulesLogic.renamePath(v, old, new)
        }
        data.fileHashes = data.fileHashes.mapKeysTo(linkedMapOf()) { RulesLogic.renamePath(it.key, old, new) }
        if (data.hiddenTeams.remove(old)) data.hiddenTeams += new
        NetworkInspectorBridge.findLiveTable(project)?.view?.table?.repaint()
        refresh(notifyResult = false)
        notification(
            "Renamed team ${esc(old)} to ${esc(new)}",
            "Commit the change so your teammates get the new folder name.",
            NotificationType.INFORMATION,
        ).notify(project)
    }

    /** Number of rules files and rules in a team, for the delete confirmation. */
    fun teamSummary(team: String): Pair<Int, Int> {
        val files = loadFiles(listOf(team))
        return files.size to files.sumOf { it.content?.rules.orEmpty().size }
    }

    /** Deletes a team folder; the refresh then removes its rules from Studio. */
    fun deleteTeam(team: String) {
        val dir = rootDir()?.findChild(team) ?: throw IllegalStateException("Team \"$team\" no longer exists.")
        writeAction("Delete Network Rules Team") { dir.delete(this) }
        data.hiddenTeams.remove(team)
        data.collapsedTeams.remove(team)
        refresh(notifyResult = false)
        notification(
            "Deleted team ${esc(team)}",
            "Commit the change to share it. Until then you can get it back with git or Local History.",
            NotificationType.INFORMATION,
        ).notify(project)
    }

    // ---- turning rules on and off by file -------------------------------------------------------

    /** Each loaded file and the Studio rules that came from it. */
    fun rulesByFile(): Map<String, List<RuleData>> {
        val studio = studioRules().associateBy { it.name }
        val out = linkedMapOf<String, MutableList<RuleData>>()
        for (f in loadFiles()) out[f.ref.relPath] = mutableListOf()
        for ((studioName, path) in data.origins) studio[studioName]?.let { out[path]?.add(it) }
        return out
    }

    /** Turns every rule of each file in [plan] on or off. RuleData.setActive syncs the device in a live session. */
    fun applyActivity(plan: Map<String, Boolean>) {
        val byFile = rulesByFile()
        var changed = 0
        for ((path, on) in plan) for (rule in byFile[path].orEmpty()) {
            if (rule.isActive != on) {
                rule.isActive = on
                changed++
            }
        }
        NetworkInspectorBridge.findLiveTable(project)?.view?.table?.repaint()
        if (changed > 0) {
            val where = if (NetworkInspectorBridge.findLiveTable(project) != null) " and sent to the device" else ""
            notification("Turned $changed rule(s) on or off$where", "This only changes your Rules tab, not the shared files.", NotificationType.INFORMATION)
                .notify(project)
        }
    }

    // ---- variables ------------------------------------------------------------------------------

    /** Every team file's content (all teams, hidden ones too), by team/file.json. */
    private fun allContents(): Map<String, SharedRulesFile> =
        loadFiles(allTeams()).filter { it.content != null }.associate { it.ref.relPath to it.content!! }

    /** Every shared variable (all teams) with its shared value. Declared-local variables have none. */
    fun sharedVariables(): Map<String, String> = sharedVariableDefinitions().mapValues { it.value.value }

    fun sharedVariableDefinitions(): Map<String, VariableLogic.SharedDefinition> = VariableLogic.sharedDefinitions(allContents())

    /** Variables team files declare as local, as the entries Export carries into other files. */
    fun declaredLocalVariables(): Map<String, SharedVariable> =
        VariableLogic.declarations(allContents()).mapValues { (name, d) -> VariableLogic.declarationOf(name, d.description) }

    val localVariables: Map<String, String> get() = data.localVariables

    /** Everything the Variables tab lists. */
    fun variableRows(): List<VariableLogic.Row> {
        val contents = allContents()
        val used = linkedMapOf<String, Int>()
        for (rule in studioRules()) {
            for (name in RulesLogic.usedVariables(listOf(RuleMapper.toShared(rule)))) used[name] = (used[name] ?: 0) + 1
        }
        val studio = NetworkInspectorBridge.variables(project).associate { it.name to it.value }
        return VariableLogic.rows(
            VariableLogic.sharedDefinitions(contents), data.localVariables, studio, used, VariableLogic.declarations(contents),
            scenarioVariables(),
        )
    }

    /**
     * Puts every shared and local variable into Studio's variable list. With [resend], the rules in
     * a live session are sent to the device again so they pick up the new values.
     */
    fun applyVariablesToStudio(resend: Boolean = true, removed: Collection<String> = emptyList()): Boolean {
        val vars = NetworkInspectorBridge.variables(project)
        val before = vars.associate { it.name to it.value }
        vars.removeAll { it.name in removed }
        for ((name, value) in VariableLogic.studioValues(sharedVariableDefinitions(), data.localVariables, scenarioVariables())) {
            val existing = vars.firstOrNull { it.name == name }
            if (existing != null) existing.value = value else vars += RuleMapper.toRuleVariable(SharedVariable(name, value))
        }
        val changed = vars.associate { it.name to it.value } != before
        // Rules are resolved against the variables when sent, so they only need re-sending if a value changed.
        if (resend && changed) NetworkInspectorBridge.findLiveTable(project)?.resendAll()
        VariablesPanel.reloadAll(project)
        return changed
    }

    /** Names in the last "variables not set" warning, so it isn't repeated for the same set. */
    private var lastMissingWarning: Set<String> = emptySet()

    /**
     * Warns when rules use (or team files declare) variables this developer has no value for: the
     * device would otherwise get the literal text `${NAME}`. Repeated only when the set changes.
     */
    fun checkMissingVariables() {
        val missing = VariableLogic.missing(variableRows()).filter { it.usedBy > 0 }
        val names = missing.map { it.name }.toSet()
        if (names == lastMissingWarning) return
        lastMissingWarning = names
        if (missing.isEmpty()) return
        val lines = missing.joinToString("<br>") { r ->
            "<b>${esc(r.name)}</b>" + (r.description?.let { " – ${esc(it)}" } ?: "") + " (${r.usedBy} rule${if (r.usedBy == 1) "" else "s"})"
        }
        notification(
            "Rules use variables you haven't set",
            "$lines<br>Until you set them, those rules send the text \${NAME} as is.",
            NotificationType.WARNING,
        ).addAction(NotificationAction.createSimpleExpiring("Set values…") { openVariables() })
            .notify(project)
    }

    fun openVariables() {
        if (!VariablesPanel.selectTab(project)) VariablesPanel.showDialog(project)
    }

    /**
     * Sets this developer's value. For a shared variable it's an override (empty goes back to the
     * shared value); otherwise it's a local variable. Never written to the shared files.
     */
    fun setVariableValue(name: String, value: String) {
        val isShared = name in sharedVariableDefinitions()
        if (isShared && value.isEmpty()) data.localVariables.remove(name) else data.localVariables[name] = value
        applyVariablesToStudio()
        checkMissingVariables()
    }

    /** Adds local values (e.g. from Replace with Variable) and applies them. */
    fun addLocalValues(values: Map<String, String>) {
        if (values.isEmpty()) return
        data.localVariables.putAll(values)
        applyVariablesToStudio()
    }

    /** Adds a local variable: only on this machine, never committed. With [declareIn], team files are told it exists. */
    fun addLocalVariable(name: String, value: String, declareIn: String? = null, description: String? = null) {
        check(VariableLogic.VALID_NAME.matches(name)) { "Use letters, digits and _ only." }
        check(name !in sharedVariableDefinitions()) { "$name is already a shared variable; set your own value for it instead." }
        data.localVariables[name] = value
        if (declareIn != null) {
            val content = existingContent(declareIn) ?: SharedRulesFile()
            writeFile(declareIn, RuleMapper.gson.toJson(VariableLogic.withDeclaration(content, name, description)) + "\n", "Declare Local Variable")
        }
        applyVariablesToStudio()
    }

    /** Adds (or updates) a shared variable in [target] (team/file.json). Its value is committed. */
    fun addSharedVariable(name: String, value: String, target: String, description: String? = null) {
        check(VariableLogic.VALID_NAME.matches(name)) { "Use letters, digits and _ only." }
        val content = existingContent(target) ?: SharedRulesFile()
        writeFile(target, RuleMapper.gson.toJson(VariableLogic.withVariable(content, name, value, description)) + "\n", "Add Shared Variable")
        // The shared value is now the value; a leftover local value of the same name would hide it.
        data.localVariables.remove(name)
        data.hiddenTeams.remove(target.substringBefore('/'))
        applyVariablesToStudio()
        notification(
            "Shared variable $name added",
            "Saved to $ROOT_DIR/${esc(target)}. Commit the file to share it.",
            NotificationType.INFORMATION,
        ).notify(project)
    }

    /**
     * Turns a shared variable into a local one. In every team file its value is replaced by a
     * declaration (no value), so it's no longer committed and teammates are told to set their own.
     * This developer keeps the current value on their machine.
     */
    fun makeVariableLocal(name: String) {
        val def = sharedVariableDefinitions()[name] ?: return
        data.localVariables[name] = data.localVariables[name]?.takeIf { it.isNotEmpty() } ?: def.value
        for (path in def.files) {
            val content = existingContent(path) ?: continue
            writeFile(path, RuleMapper.gson.toJson(VariableLogic.withDeclaration(content, name, def.description)) + "\n", "Make Variable Local")
        }
        applyVariablesToStudio()
        notification(
            "$name is now local",
            "Its value was removed from ${def.files.joinToString { esc(it) }}, which now only declare it. Commit the change; " +
                "teammates set their own value in the Variables tab. If the old value was sensitive, it's still in git history.",
            NotificationType.INFORMATION,
        ).notify(project)
    }

    /**
     * Shares a local variable in [target]: with its value, or with [declareOnly] just a declaration
     * (teammates are told to set their own value, nothing is committed).
     */
    fun shareVariable(name: String, target: String, declareOnly: Boolean, description: String?) {
        val value = data.localVariables[name]
            ?: NetworkInspectorBridge.variables(project).firstOrNull { it.name == name }?.value
            ?: ""
        if (declareOnly) {
            data.localVariables[name] = value
            val content = existingContent(target) ?: SharedRulesFile()
            writeFile(target, RuleMapper.gson.toJson(VariableLogic.withDeclaration(content, name, description)) + "\n", "Declare Local Variable")
            data.hiddenTeams.remove(target.substringBefore('/'))
            applyVariablesToStudio()
            notification(
                "$name declared in ${esc(target)}",
                "Teammates will be asked for their own value. Your value stays on this machine. Commit the file to share it.",
                NotificationType.INFORMATION,
            ).notify(project)
        } else {
            addSharedVariable(name, value, target, description)
        }
    }

    /** Changes the description in every team file that defines or declares [name]. */
    fun setVariableDescription(name: String, description: String) {
        for ((path, content) in allContents()) {
            if (content.variables.orEmpty().none { it.name == name }) continue
            writeFile(path, RuleMapper.gson.toJson(VariableLogic.withDescription(content, name, description)) + "\n", "Describe Variable")
        }
        VariablesPanel.reloadAll(project)
    }

    /** Removes a variable: from the team files that define or declare it, and from this machine. */
    fun removeVariable(name: String) {
        for ((path, content) in allContents()) {
            if (content.variables.orEmpty().none { it.name == name }) continue
            writeFile(path, RuleMapper.gson.toJson(VariableLogic.withoutVariable(content, name)) + "\n", "Remove Variable")
        }
        data.localVariables.remove(name)
        applyVariablesToStudio(removed = listOf(name))
    }

    /**
     * Replaces literal values (an email, a token) in the named Studio rules with `${NAME}`, and saves
     * those values as local variables, so the rules can be shared without the values.
     */
    fun replaceInStudioRules(studioNames: Collection<String>, replacements: Map<String, String>) {
        if (replacements.isEmpty()) return
        // Variables first, so the rules are re-sent with the values already in place.
        data.localVariables.putAll(replacements.entries.associate { (literal, name) -> name to literal })
        for (rule in studioRules().filter { it.name in studioNames }) {
            RuleMapper.copyInto(rule, SensitiveData.replace(RuleMapper.toShared(rule), replacements))
        }
        applyVariablesToStudio()
        NetworkInspectorBridge.findLiveTable(project)?.view?.table?.repaint()
    }

    // ---- scenarios ------------------------------------------------------------------------------

    val activeScenario: String? get() = data.activeScenario

    fun scenariosFile(): VirtualFile? = rootDir()?.findChild(ScenarioLogic.FILE)

    /** scenarios.json, or null when there isn't one. Throws IllegalStateException when it can't be read. */
    fun loadScenarios(): ScenariosFile? {
        val file = scenariosFile() ?: return null
        return try {
            val text = FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: VfsUtilCore.loadText(file)
            RuleMapper.gson.fromJson(text, ScenariosFile::class.java) ?: ScenariosFile()
        } catch (e: Exception) {
            throw IllegalStateException("$ROOT_DIR/${ScenarioLogic.FILE} couldn't be read (${e.message ?: e.javaClass.simpleName}).")
        }
    }

    fun scenarios(): List<SharedScenario> = runCatching { loadScenarios() }.getOrNull()?.scenarios.orEmpty().filter { !it.name.isNullOrBlank() }

    /** Values the active scenario sets; they apply between the shared value and this developer's own. */
    fun scenarioVariables(): Map<String, String> =
        data.activeScenario?.let { name -> scenarios().firstOrNull { it.name == name }?.variables }.orEmpty()

    /** Studio's shared rules, as scenario selectors see them. */
    fun sharedRuleRefs(): List<ScenarioLogic.RuleRef> = studioRules().mapNotNull { rule ->
        val origin = originOf(rule.name) ?: return@mapNotNull null
        ScenarioLogic.RuleRef(rule.name, origin.substringBefore('/'), origin)
    }

    /**
     * Turns on exactly the shared rules [name] selects and turns the other shared rules off (your own
     * rules aren't touched), and applies its variable values. In a live session Studio sends the changes.
     */
    fun applyScenario(name: String) {
        val scenario = loadScenarios()?.let { ScenarioLogic.find(it, name) } ?: throw IllegalStateException("Scenario \"$name\" no longer exists.")
        val (on, notFound) = ScenarioLogic.resolve(scenario.enable.orEmpty(), sharedRuleRefs())
        val previousVars = scenarioVariables().keys
        for (rule in studioRules()) {
            if (originOf(rule.name) == null) continue
            val wanted = rule.name in on
            if (rule.isActive != wanted) rule.isActive = wanted
        }
        data.activeScenario = name
        applyVariablesToStudio(removed = previousVars - scenarioVariables().keys - sharedVariables().keys - data.localVariables.keys)
        NetworkInspectorBridge.findLiveTable(project)?.view?.table?.repaint()
        notification(
            "Scenario \"${esc(name)}\" is on",
            "${on.size} shared rule(s) on, the other shared rules off." +
                (if (notFound.isEmpty()) "" else "<br>Not found (renamed, deleted, or in a hidden team?): ${esc(notFound.joinToString())}"),
            if (notFound.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING,
        ).notify(project)
    }

    /** Turns the active scenario's rules off and drops its variable values. */
    fun turnOffScenario() {
        val name = data.activeScenario ?: return
        val scenario = scenarios().firstOrNull { it.name == name }
        val vars = scenarioVariables().keys
        if (scenario != null) {
            val (on, _) = ScenarioLogic.resolve(scenario.enable.orEmpty(), sharedRuleRefs())
            for (rule in studioRules()) if (rule.name in on && rule.isActive) rule.isActive = false
        }
        data.activeScenario = null
        applyVariablesToStudio(removed = vars - sharedVariables().keys - data.localVariables.keys)
        NetworkInspectorBridge.findLiveTable(project)?.view?.table?.repaint()
    }

    /** The selectors for the shared rules that are on right now. */
    fun enabledSelectors(): List<String> {
        val refs = sharedRuleRefs()
        val on = studioRules().filter { it.isActive }.map { it.name }.toSet()
        return ScenarioLogic.compact(on, refs)
    }

    /** Saves (or replaces) a scenario in scenarios.json and marks it as the active one. */
    fun saveScenario(scenario: SharedScenario) {
        val name = scenario.name ?: error("A scenario needs a name")
        check(ScenarioLogic.VALID_NAME.matches(name)) { "Use letters, digits, spaces, _ - . in scenario names." }
        val updated = ScenarioLogic.upsert(loadScenarios(), scenario)
        writeFile(ScenarioLogic.FILE, RuleMapper.gson.toJson(updated) + "\n", "Save Network Rules Scenario")
        data.activeScenario = name
        applyVariablesToStudio()
        notification(
            "Saved scenario \"${esc(name)}\"",
            "Saved to $ROOT_DIR/${ScenarioLogic.FILE}. Commit it to share it; in Appium tests use <code>--es scenario \"${esc(name)}\"</code>.",
            NotificationType.INFORMATION,
        ).notify(project)
    }

    fun deleteScenario(name: String) {
        if (data.activeScenario == name) turnOffScenario()
        writeFile(ScenarioLogic.FILE, RuleMapper.gson.toJson(ScenarioLogic.remove(loadScenarios(), name)) + "\n", "Delete Network Rules Scenario")
    }

    // ---- file helpers ---------------------------------------------------------------------------

    /** The current contents of a team file, or null if it doesn't exist. Throws if it exists but can't be read, so it's never overwritten. */
    private fun existingContent(relPath: String): SharedRulesFile? {
        val file = rootDir()?.findFileByRelativePath(relPath) ?: return null
        val loaded = parsed(RulesFileRef(relPath.substringBefore('/'), file))
        loaded.error?.let { throw IllegalStateException("$ROOT_DIR/$relPath exists but couldn't be read ($it). Fix or remove it first.") }
        return loaded.content
    }


    private fun writeFile(relPath: String, text: String, commandName: String = "Export Network Rules") {
        val root = rootPath ?: error("Project has no base directory")
        writeAction(commandName) {
            // team/file.json, or a file at the root such as scenarios.json.
            val dir = VfsUtil.createDirectoryIfMissing(if ('/' in relPath) "$root/${relPath.substringBefore('/')}" else root)!!
            val name = relPath.substringAfterLast('/')
            val file = dir.findChild(name) ?: dir.createChildData(this, name)
            val fdm = FileDocumentManager.getInstance()
            val doc = fdm.getDocument(file)
            if (doc != null) {
                doc.setText(text)
                fdm.saveDocument(doc)
            } else {
                VfsUtil.saveText(file, text)
            }
        }
        if ('/' in relPath) data.fileHashes[relPath] = RulesLogic.hash(text)
    }

    /** An undoable write to the shared folder that doesn't trigger our own "files changed" notification. */
    private fun writeAction(commandName: String, block: () -> Unit) {
        suppressChangeNotification = true
        try {
            WriteCommandAction.runWriteCommandAction(project, commandName, null, block)
        } finally {
            ApplicationManager.getApplication().invokeLater { suppressChangeNotification = false }
        }
    }

    // ---- toolbar buttons inside Studio's Rules tab ----------------------------------------------

    /**
     * Studio's Rules tab is built with ToolbarDecorator, so its +/− buttons sit in a CommonActionsPanel.
     * We put our own small toolbar to the right of it. The Rules panel is created per session and
     * only exists while it's on screen, so we check every couple of seconds while App Inspection is visible.
     * If Studio's layout ever changes, this quietly does nothing; the Tools menu actions still work.
     */
    private fun startToolbarInjection() {
        val timer = Timer(2000) { injectToolbarIfNeeded() }
        timer.start()
        Disposer.register(this) { timer.stop() }
    }

    private fun injectToolbarIfNeeded() {
        if (project.isDisposed) return
        val tw = ToolWindowManager.getInstance(project).getToolWindow(NetworkInspectorBridge.APP_INSPECTION_TOOL_WINDOW) ?: return
        if (!tw.isVisible) return
        for (table in NetworkInspectorBridge.rulesTablesOnScreen(project)) {
            runCatching { inject(table) }.onFailure { LOG.debug("Toolbar injection skipped", it) }
            runCatching { RulesTableGroups.ensure(table, this) }.onFailure { LOG.debug("Team groups skipped", it) }
            runCatching { VariablesPanel.ensureTab(project, table) }.onFailure { LOG.debug("Variables tab skipped", it) }
        }
        runCatching { TrafficRules.ensureConnectionsMenu(project) }.onFailure { LOG.debug("Connections menu skipped", it) }
        for (view in NetworkInspectorBridge.ruleDetailViewsOnScreen(project)) {
            runCatching { RuleTeamBar.ensure(project, view) }.onFailure { LOG.debug("Team bar skipped", it) }
        }
    }

    private fun inject(table: TableView<*>) {
        val decorator = generateSequence(table.parent as Container?) { it.parent }
            .firstOrNull { c -> c.components.any { it is CommonActionsPanel } } as? JPanel ?: return
        if (decorator.getClientProperty(INJECTED_KEY) == true) return
        val layout = decorator.layout as? BorderLayout ?: return
        val actionsPanel = decorator.components.first { it is CommonActionsPanel }
        val constraint = layout.getConstraints(actionsPanel) ?: return

        val group = ActionManager.getInstance().getAction("NetworkRulesSync.Toolbar") as ActionGroup
        val toolbar = ActionManager.getInstance().createActionToolbar("NetworkRulesSyncToolbar", group, true)
        toolbar.targetComponent = table
        toolbar.component.isOpaque = false
        decorator.remove(actionsPanel)
        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(actionsPanel, BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.EAST)
        }
        decorator.add(row, constraint)
        decorator.putClientProperty(INJECTED_KEY, true)
        SwingUtilities.invokeLater { decorator.revalidate(); decorator.repaint() }
    }

    private fun notification(title: String, content: String, type: NotificationType) =
        NotificationGroupManager.getInstance().getNotificationGroup("Network Rules Sync").createNotification(title, content, type)

    private fun esc(s: String) = StringUtil.escapeXmlEntities(s)

    override fun dispose() = Unit

    companion object {
        const val ROOT_DIR = "network-inspector-rules"
        const val STARTER_FILE = "rules.json"
        val VALID_TEAM get() = RulesLogic.VALID_TEAM
        val VALID_TARGET get() = RulesLogic.VALID_TARGET
        private const val INJECTED_KEY = "io.github.networkrules.injected"
        private const val MAX_FILE_LINKS = 3
        private val LOG = logger<SharedRulesService>()

        fun getInstance(project: Project): SharedRulesService = project.service()
        fun studioName(team: String, name: String) = RulesLogic.studioName(team, name)
        fun baseName(studioName: String) = RulesLogic.baseName(studioName)
    }
}
