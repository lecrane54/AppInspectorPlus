package com.example.mockrules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Lets a test turn mocks on and off with a broadcast. Declared in the debug manifest with
 * `android:permission="android.permission.DUMP"`, so only adb's shell (Appium) can send it,
 * not other apps on the device.
 *
 * ```
 * adb shell am broadcast -a com.example.mockrules.CONTROL -p <your.package> -f 0x20 \
 *     --es only "checkout/payment-declined.json"
 * ```
 *
 * Extras (all optional; lists are comma-separated):
 *  - `enable` / `disable`: `team/file.json`, `team`, `[team] rule name`, or `*`
 *  - `only`: enable exactly these and disable everything else
 *  - `clear` (`--ez clear true`): turn everything off and drop variable values
 *  - `var` (`--es var "API_HOST=10.0.0.5"`): set a variable; `unvar`: remove one
 *  - `callServer` (`--ez callServer true`): rewrite the real response instead of answering locally
 *  - `reload` (`--ez reload true`): re-read the rules files, e.g. after pushing new ones
 *
 * The broadcast's result data is a summary, printed by `am broadcast`, so a test log shows what's on.
 */
class MockRulesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        fun list(key: String): List<String>? =
            intent.getStringExtra(key)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }

        val vars = list("var").orEmpty().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val summary = MockRules.update(
            context,
            enable = list("enable").orEmpty(),
            disable = list("disable").orEmpty(),
            only = list("only"),
            clear = intent.getBooleanExtra("clear", false),
            setVariables = vars,
            unsetVariables = list("unvar").orEmpty(),
            alwaysCallServer = if (intent.hasExtra("callServer")) intent.getBooleanExtra("callServer", false) else null,
            reload = intent.getBooleanExtra("reload", false),
        )
        if (isOrderedBroadcast) resultData = summary
    }

    companion object {
        const val ACTION = "com.example.mockrules.CONTROL"
    }
}
