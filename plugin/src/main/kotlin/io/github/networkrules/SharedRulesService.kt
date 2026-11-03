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
    PersistentStateComponent<SharedRulesService.Data>, Disposable {

    class Data {
        /** Studio names imported at the last refresh, so a rule deleted from a file is removed here too. */
        var importedNames: MutableSet<String> = linkedSetOf()

        /** Studio name → file it came from (team/file.json); used by Export. */
        var origins: MutableMap<String, String> = linkedMapOf()

        /** Teams this developer has hidden. New team folders are shown by default. */
        var hiddenTeams: MutableSet<String> = linkedSetOf()

        /** team/file.json → hash of the content last loaded or written, so Export can spot files changed since. */
        var fileHashes: MutableMap<String, String> = linkedMapOf()

        /** This developer's own values for shared variables. Never written to the shared files. */
        var localVariables: MutableMap<String, String> = linkedMapOf()
    }

    private var data = Data()
    override fun getState() = data
    override fun loadState(state: Data) {
        data = state
    }

    @Volatile
    private var suppressChangeNotification = false

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
                .map { f ->
                    val ref = RulesFileRef(dir.name, f)
                    val text = try {
                        FileDocumentManager.getInstance().getCachedDocument(f)?.text ?: VfsUtilCore.loadText(f)
                    } catch (e: Exception) {
                        return@map LoadedRulesFile(ref, null, e.message ?: e.javaClass.simpleName, "")
                    }
                    try {
                        LoadedRulesFile(ref, RuleMapper.gson.fromJson(text, SharedRulesFile::class.java) ?: SharedRulesFile(), null, RulesLogic.hash(text))
                    } catch (e: Exception) {
                        LOG.warn("Failed to parse ${f.path}", e)
                        LoadedRulesFile(ref, null, e.message ?: e.javaClass.simpleName, RulesLogic.hash(text))
                    }
                }
        }
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

        // Variables first: rules are resolved against them when they're sent to the device.
        // A personal override replaces the shared value, only in this developer's Studio.
        val vars = NetworkInspectorBridge.variables(project)
        for ((name, value) in RulesLogic.effectiveVariables(files.mapNotNull { it.content }, data.localVariables)) {
            vars.removeAll { it.name == name }
            vars += RuleMapper.toRuleVariable(SharedVariable(name, value))
        }

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

        // Your on/off choice survives a refresh. Brand-new shared rules start off, so pulling a
        // teammate's rules never starts rewriting your traffic unexpectedly.
        val wasActive = mutableMapOf<String, Boolean>()
        for (rule in current.filter { it.name in managed }.toList()) {
            wasActive[rule.name] = rule.isActive
            if (live != null) live.remove(rule) else persisted.remove(rule)
        }
        for ((studioName, entry) in desired) {
            val shared = entry.first.copy(name = studioName)
            val rule = RuleMapper.toRuleData(shared, RuleData.newId(), wasActive[studioName] ?: false)
            if (live != null) live.add(rule) else persisted.add(rule)
        }
        live?.view?.table?.clearSelection()
        data.importedNames = desired.keys.toMutableSet()
        data.origins = desired.mapValuesTo(linkedMapOf()) { it.value.second }
        data.fileHashes = files.associateTo(linkedMapOf()) { it.ref.relPath to it.hash }

        if (duplicates.isNotEmpty()) {
            notification("Duplicate rule names skipped", duplicates.joinToString("<br>") { esc(it) }, NotificationType.WARNING).notify(project)
        }
        if (notifyResult) {
            val where = if (live != null) "and sent to the device" else "(applied when Network Inspector next connects)"
            val newOnes = desired.keys.count { it !in wasActive }
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
        var written = 0
        for ((path, rules) in output) {
            val vars = RulesLogic.exportVariables(
                existing[path]?.variables.orEmpty(), studioVars, RulesLogic.usedVariables(rules),
                data.localVariables.keys, sharedDefaults,
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

        notification(
            "Exported to $written file(s)",
            "Saved under $ROOT_DIR/. Commit the changes to share them with your team.",
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

    // ---- personal variable values ---------------------------------------------------------------

    /** Every shared variable (all teams) with its shared value. */
    fun sharedVariables(): Map<String, String> =
        RulesLogic.effectiveVariables(loadFiles(allTeams()).mapNotNull { it.content }, emptyMap())

    val localVariables: Map<String, String> get() = data.localVariables

    /** Replaces this developer's overrides (blank values are dropped) and applies them. */
    fun setLocalVariables(values: Map<String, String>) {
        data.localVariables = values.filterValues { it.isNotEmpty() }.toMutableMap()
        // A variable whose override was cleared goes back to its shared value on refresh.
        refresh(notifyResult = false)
        notification(
            "Variable values updated",
            "${data.localVariables.size} personal value(s) in use. They stay on your machine and are never exported.",
            NotificationType.INFORMATION,
        ).notify(project)
    }

    // ---- file helpers ---------------------------------------------------------------------------

    /** The current contents of a team file, or null if it doesn't exist. Throws if it exists but can't be read, so it's never overwritten. */
    private fun existingContent(relPath: String): SharedRulesFile? {
        val file = rootDir()?.findFileByRelativePath(relPath) ?: return null
        return try {
            val text = FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: VfsUtilCore.loadText(file)
            RuleMapper.gson.fromJson(text, SharedRulesFile::class.java) ?: SharedRulesFile()
        } catch (e: Exception) {
            throw IllegalStateException("$ROOT_DIR/$relPath exists but couldn't be read (${e.message ?: e.javaClass.simpleName}). Fix or remove it first.")
        }
    }

    private fun writeFile(relPath: String, text: String, commandName: String = "Export Network Rules") {
        val root = rootPath ?: error("Project has no base directory")
        writeAction(commandName) {
            val dir = VfsUtil.createDirectoryIfMissing("$root/${relPath.substringBefore('/')}")!!
            val name = relPath.substringAfter('/')
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
        data.fileHashes[relPath] = RulesLogic.hash(text)
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
