package io.github.networkrules

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.CollectingOutputReceiver
import com.android.ddmlib.IDevice
import com.android.tools.idea.adb.AdbService
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.JComponent

/**
 * The adb side of **Push Rules to Device…**: paths on the device, the broadcast, and reading its
 * answer. No IDE or adb types, so it can be unit tested. The device layout is the one the Appium
 * mocks read (see appium-mocks/README.md): files pushed under the app's external files dir win
 * over the ones bundled in the APK.
 */
object DevicePushLogic {
    const val DEFAULT_ACTION = "com.example.mockrules.CONTROL"

    /** Where record mode writes its files on the device (see appium-mocks). */
    const val RECORDINGS_DIR = "network-inspector-recordings"

    val VALID_PACKAGE = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$""")
    val VALID_ACTION = Regex("""^[A-Za-z][A-Za-z0-9_.]*$""")

    fun filesDir(appPackage: String) = "/sdcard/Android/data/$appPackage/files"

    /** The folder the interceptor reads pushed rules from. */
    fun remoteRoot(appPackage: String) = "${filesDir(appPackage)}/${SharedRulesService.ROOT_DIR}"

    fun remotePath(appPackage: String, relPath: String) = "${remoteRoot(appPackage)}/$relPath"

    fun recordingsRoot(appPackage: String) = "${filesDir(appPackage)}/$RECORDINGS_DIR"

    /**
     * Every rules file to push, as `team/file.json` (or `scenarios.json`) to its local file: the JSON
     * files in each team folder under [root], plus scenarios.json at the root. Hidden teams are
     * included, since a scenario may name their rules.
     */
    fun collectFiles(root: File): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { team ->
            team.listFiles()?.filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }?.sortedBy { it.name }
                ?.forEach { out += "${team.name}/${it.name}" to it }
        }
        File(root, ScenarioLogic.FILE).takeIf { it.isFile }?.let { out += ScenarioLogic.FILE to it }
        return out
    }

    /** `am broadcast -a … -p … -f 0x20 <extras>`: the command the mocks receiver listens for. */
    fun broadcast(action: String, appPackage: String, extras: List<String>): String =
        "am broadcast -a $action -p $appPackage -f 0x20" + extras.joinToString("") { " $it" }

    /** Reload the pushed files, then apply [scenario] and pass [varArgs] (`--es var "…"`) when given. */
    fun reloadExtras(scenario: String?, varArgs: String?): List<String> =
        listOfNotNull("--ez reload true", scenario?.let { "--es scenario \"$it\"" }, varArgs)

    /** The receiver's summary line from `am broadcast` output, or null when the app didn't answer. */
    fun summaryOf(output: String): String? =
        Regex("""data="(.*)"""").find(output)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /** The shell-quoted path, for `mkdir`/`rm`. */
    fun quote(path: String) = "'" + path.replace("'", "'\\''") + "'"
}

/** Pushes the shared rules folder to a connected device and tells the Appium mocks to reload. */
class PushRulesToDeviceAction : ProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = EndpointSettings.getInstance(project)
        val appPackage = settings.appPackage
        if (!DevicePushLogic.VALID_PACKAGE.matches(appPackage)) {
            val choice = Messages.showOkCancelDialog(
                project,
                "Set the package name of the debug app that has the Appium mocks first.",
                "Push Rules to Device", "Open Settings", "Cancel", Messages.getInformationIcon(),
            )
            if (choice == Messages.OK) ShowSettingsUtil.getInstance().showSettingsDialog(project, SharedRulesConfigurable::class.java)
            return
        }
        val service = SharedRulesService.getInstance(project)
        val root = service.rootDir()?.let { VfsUtilCore.virtualToIoFile(it) }
        val files = root?.let { DevicePushLogic.collectFiles(it) }.orEmpty()
        if (files.isEmpty()) {
            Messages.showInfoMessage(project, "There are no rules files under ${SharedRulesService.ROOT_DIR}/ yet.", "Nothing to Push")
            return
        }
        val scenario = service.activeScenario
        val (varArgs, _) = VariableLogic.adbVarArgs(service.variableRows())
        val varCount = service.variableRows().count { it.yourValue != null }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Pushing shared rules to device", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Connecting to adb…"
                val bridge = AdbService.getInstance().getDebugBridge(project).get(30, TimeUnit.SECONDS)
                waitForDevices(bridge, indicator)
                val devices = bridge.devices.filter { it.isOnline }
                if (devices.isEmpty()) {
                    notify("No device connected", "Connect a device or start an emulator, then try again.", NotificationType.WARNING)
                    return
                }
                val labels = devices.map { it to label(it) }
                var choice: PushChoice? = null
                ApplicationManager.getApplication().invokeAndWait {
                    val dialog = PushDialog(project, labels.map { it.second }, scenario, if (varArgs != null) varCount else 0)
                    if (dialog.showAndGet()) choice = dialog.choice()
                }
                val c = choice ?: return
                val device = devices[c.deviceIndex]
                push(device, labels[c.deviceIndex].second, indicator, c)
            }

            private fun push(device: IDevice, deviceLabel: String, indicator: ProgressIndicator, choice: PushChoice) {
                val remoteRoot = DevicePushLogic.remoteRoot(appPackage)
                indicator.text = "Replacing $remoteRoot…"
                val dirs = files.map { (rel, _) -> rel.substringBeforeLast('/', "") }.filter { it.isNotEmpty() }.toSortedSet()
                shell(device, "rm -rf ${DevicePushLogic.quote(remoteRoot)} && mkdir -p ${DevicePushLogic.quote(remoteRoot)}" +
                    dirs.joinToString("") { " " + DevicePushLogic.quote("$remoteRoot/$it") })
                files.forEachIndexed { i, (rel, file) ->
                    indicator.checkCanceled()
                    indicator.text = "Pushing $rel"
                    indicator.fraction = i.toDouble() / files.size
                    device.pushFile(file.absolutePath, DevicePushLogic.remotePath(appPackage, rel))
                }
                indicator.text = "Reloading the mocks…"
                val extras = DevicePushLogic.reloadExtras(scenario.takeIf { choice.applyScenario }, varArgs.takeIf { choice.passVariables })
                val output = shell(device, DevicePushLogic.broadcast(settings.broadcastAction, appPackage, extras))
                val summary = DevicePushLogic.summaryOf(output)
                val pushed = "Pushed ${files.size} file${if (files.size == 1) "" else "s"} to ${esc(deviceLabel)}."
                when {
                    summary == null -> notify(
                        "Rules pushed, but the app didn't answer",
                        "$pushed<br>Is the debug build with the Appium mocks installed as <code>${esc(appPackage)}</code>, " +
                            "and is its broadcast action <code>${esc(settings.broadcastAction)}</code>?<br><code>${esc(output.trim().take(300))}</code>",
                        NotificationType.WARNING,
                    )
                    "NOT FOUND" in summary || "INVALID" in summary -> notify("Rules pushed with warnings", "$pushed<br><code>${esc(summary)}</code>", NotificationType.WARNING)
                    else -> notify("Rules pushed to device", "$pushed<br><code>${esc(summary)}</code>", NotificationType.INFORMATION)
                }
            }

            override fun onThrowable(error: Throwable) {
                notify("Couldn't push rules to the device", esc(error.message ?: error.javaClass.simpleName), NotificationType.ERROR)
            }

            private fun notify(title: String, content: String, type: NotificationType) =
                service.notification(title, content, type).notify(project)
        })
    }

    private fun esc(s: String) = StringUtil.escapeXmlEntities(s)

    private fun waitForDevices(bridge: AndroidDebugBridge, indicator: ProgressIndicator) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!bridge.hasInitialDeviceList() && System.currentTimeMillis() < deadline) {
            indicator.checkCanceled()
            Thread.sleep(100)
        }
    }

    private fun label(device: IDevice): String {
        val name = device.avdName ?: runCatching { device.getProperty("ro.product.model") }.getOrNull()
        return if (name.isNullOrBlank()) device.serialNumber else "$name (${device.serialNumber})"
    }

    private fun shell(device: IDevice, command: String): String {
        val receiver = CollectingOutputReceiver()
        device.executeShellCommand(command, receiver, 30, TimeUnit.SECONDS)
        return receiver.output
    }
}

private class PushChoice(val deviceIndex: Int, val applyScenario: Boolean, val passVariables: Boolean)

/** Which device, and whether to apply the active scenario and pass this developer's variable values after the reload. */
private class PushDialog(project: Project, devices: List<String>, private val scenario: String?, private val variableCount: Int) : DialogWrapper(project) {
    private val deviceBox = ComboBox(devices.toTypedArray())
    private val scenarioBox = JBCheckBox("Apply scenario \"$scenario\" on the device", scenario != null)
    private val variablesBox = JBCheckBox("Pass your $variableCount variable value${if (variableCount == 1) "" else "s"} (--es var)", variableCount > 0)

    init {
        title = "Push Rules to Device"
        setOKButtonText("Push")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Device:") { cell(deviceBox) }
        if (scenario != null) row { cell(scenarioBox) }
        if (variableCount > 0) {
            row { cell(variablesBox) }.comment("Local variables and your personal overrides, as the Variables tab's Copy adb Arguments would pass them.")
        }
        row { comment("Replaces ${SharedRulesService.ROOT_DIR}/ under the app's external files folder with the repo's files, then sends the reload broadcast.") }
    }

    override fun getPreferredFocusedComponent(): JComponent = deviceBox

    fun choice() = PushChoice(deviceBox.selectedIndex, scenario != null && scenarioBox.isSelected, variableCount > 0 && variablesBox.isSelected)
}
