# Appium mocks from the shared rules

Lets Appium tests on real devices use the same `network-inspector-rules/<team>/*.json` files as
Android Studio's Network Inspector, without Studio. A debug-only OkHttp interceptor applies the rules
inside the app, and a test turns scenarios on and off with an adb broadcast.

* **Off unless a test turns it on.** With nothing enabled, every request goes to the server as usual.
* **Debug builds only.** Release builds get a no-op and no receiver, so nothing ships.
* **Answers on its own.** A matching rule that replaces the body returns the mock **without calling
  the server**, so tests don't depend on the server or on what the test account looks like. Rules
  that only change the status code or headers, or modify part of the body, still call the server
  and rewrite its response, the way Studio does.
* **Matches like Studio.** Protocol and method must match. Host, port, path and query use Studio's
  wildcards (`*` = anything, `?` = one character, empty = anything), and `${VARIABLES}` work.

This folder only builds here to compile and test the code. Nothing in this repo depends on it.

## 1. Copy the files into the app module

```
app/src/debug/AndroidManifest.xml                     ← merge into yours if you already have one
app/src/debug/java/com/example/mockrules/*.kt         ← 7 files
app/src/release/java/com/example/mockrules/MockRulesInstaller.kt
```

* Rename the package `com.example.mockrules` to one of yours. Change it in the `.kt` files, in the
  receiver's `android:name`, and in the action string (`MockRulesReceiver.ACTION` and the manifest).
* If Appium tests use another build type (e.g. `qa`), put the `src/debug` files there instead. Every
  build type without the mocks needs the no-op `MockRulesInstaller.kt`.
* It needs only OkHttp 4.x or 5.x (which Retrofit already brings) and `org.json`, which is built into Android.

## 2. Add the interceptor

Where you build your `OkHttpClient`, in shared code:

```kotlin
import com.example.mockrules.addMockRules

val client = OkHttpClient.Builder()
    .addMockRules(context)      // first, so mocked responses skip auth/retry interceptors
    .addInterceptor(authInterceptor)
    .build()
```

## 3. Bundle the rules files into debug builds

In `app/build.gradle.kts`, copy the repo's `network-inspector-rules/` into the debug APK's assets.
Adjust `rulesDir` if the folder isn't at the root of the app repo:

```kotlin
abstract class CopyMockRules : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rulesDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile.resolve("network-inspector-rules")
        out.deleteRecursively()
        rulesDir.get().asFile.copyRecursively(out, overwrite = true)
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        val task = tasks.register<CopyMockRules>("copy${variant.name.replaceFirstChar { it.uppercase() }}MockRules") {
            rulesDir.set(rootProject.layout.projectDirectory.dir("network-inspector-rules"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, CopyMockRules::outputDir)
    }
}
```

> This snippet uses AGP's variant API (AGP 7.4+). It's the one part not compiled in this repo, since
> the repo has no Android Gradle plugin. Check that `network-inspector-rules/` shows up under
> `assets/` in the debug APK (Build → Analyze APK).

## 4. Control it from Appium

Every command is one adb broadcast to your app:

```
am broadcast -a com.example.mockrules.CONTROL -p <your.package> -f 0x20 <extras>
```

* `-p` targets your app. Android doesn't deliver broadcasts to a manifest receiver without it.
* `-f 0x20` also reaches the app after a force-stop or a fresh install.
* Only the shell user can send it (the receiver requires `android.permission.DUMP`), so other apps
  on the device can't turn mocks on.

| Extra | Does |
|---|---|
| `--es scenario "checkout-declined"` | Applies a scenario from `scenarios.json`: exactly its rules, plus its variable values. Other extras in the same broadcast apply on top, e.g. `--es var` for a local value |
| `--es only "checkout/payment-declined.json"` | Enables exactly these and turns everything else off |
| `--es enable "login,[checkout] Payment declined"` | Enables more. Accepts `team/file.json`, `team`, `[team] rule name`, or `*` |
| `--es disable "login"` | Turns those off |
| `--ez clear true` | Turns everything off and drops variable values |
| `--es var "API_HOST=api.staging.example.com"` | Sets variables (comma-separated: `"A=1,B=2"`). `--es unvar API_HOST` removes one |
| `--ez callServer true` | Always calls the server and rewrites its response (Studio's behavior) |
| `--ez reload true` | Re-reads the rules files, e.g. after pushing new ones |
| `--es record "checkout/signup-flow.json"` | [Record mode](#record-mode): every server response becomes a rule in that file. `--ez record true` records to `recorded/<date-time>.json`; `--ez record false` stops |

The state is saved in the app's preferences, so it survives the app restarting between steps.
Reinstalling the app or `clear` resets it. `am broadcast` prints a summary such as
`data="enabled=[checkout/payment-declined.json] activeRules=1/12"`. Any name that doesn't match a
file, team or rule shows up as `NOT FOUND=[…]`, which catches typos.

**Java (Appium java-client), via `mobile: shell`.** The Appium server must allow it:
`appium --allow-insecure=uiautomator2:adb_shell` (Appium 2), or `--allow-insecure=adb_shell` (Appium 1).

```java
void mockRules(AndroidDriver driver, String... extras) {
    List<String> args = new ArrayList<>(List.of(
        "broadcast", "-a", "com.example.mockrules.CONTROL", "-p", APP_PACKAGE, "-f", "0x20"));
    args.addAll(List.of(extras));
    Object out = driver.executeScript("mobile: shell", Map.of("command", "am", "args", args));
    System.out.println(out); // the summary
}

@Test void paymentDeclinedShowsError() {
    mockRules(driver, "--es", "only", "checkout/payment-declined.json");
    // … drive the UI …
}

@AfterEach void resetMocks() {
    mockRules(driver, "--ez", "clear", "true");
}
```

Newer UiAutomator2 drivers also have a `mobile: broadcast` command, which doesn't need the
insecure flag. Check your driver version's docs for its argument names.

**Updating rules without rebuilding.** Push a file into the app's external files folder, then reload:

```java
driver.pushFile("/sdcard/Android/data/" + APP_PACKAGE + "/files/network-inspector-rules/checkout/payment-declined.json",
    Files.readAllBytes(Path.of("network-inspector-rules/checkout/payment-declined.json")));
mockRules(driver, "--ez", "reload", "true");
```

A pushed file replaces the bundled file with the same path.

## Dynamic values in responses

A fixture can answer with what the app sent, or with a fresh value per response, so a mocked
"create order" returns the ID the app posted and a list screen gets a new timestamp each time:

```json
{ "type": "replace", "body": {
    "id": "${mock.uuid}",
    "orderNumber": "ORD-${mock.random:100000-999999}",
    "createdAt": "${mock.now}",
    "customerId": "${request.path[2]}",
    "items": "${request.body.items}",
    "total": "${request.body.total}",
    "trace": "${request.header.X-Trace-Id}" } }
```

| Placeholder | Value |
|---|---|
| `${mock.uuid}` | A random UUID |
| `${mock.now}` | The time, ISO-8601 in UTC (`2026-11-17T14:03:11Z`). `${mock.now.millis}` / `${mock.now.seconds}` since the epoch; `${mock.now:yyyy-MM-dd}` with a `SimpleDateFormat` pattern |
| `${mock.random}` | A random 6-digit number; `${mock.random:1-6}` within a range (inclusive) |
| `${request.method}` `${request.url}` `${request.host}` `${request.path}` `${request.query}` | Parts of the request |
| `${request.path[1]}` | One path segment: for `/users/42`, `[0]` is `users` and `[1]` is `42` |
| `${request.query.page}` | A query parameter |
| `${request.header.X-Trace-Id}` | A request header |
| `${request.body}` | The request body |
| `${request.body.items[0].sku}` | A value inside a JSON request body (`.name` and `[index]` as deep as needed) |

* **Values keep their type.** In a JSON body, a string that is exactly one placeholder is replaced
  whole: `"total": "${request.body.total}"` gives `"total": 19.99`, `"items": "${request.body.items}"`
  echoes the whole array, `"ts": "${mock.now.millis}"` is a number. Inside longer text
  (`"Order ${request.body.id}"`) everything is inserted as text, with quotes escaped.
* The names have a dot, so they never clash with `${VARIABLES}`, and Studio's variable checks ignore
  them. Studio itself sends them as plain text: they're resolved only here.
* A placeholder that can't be resolved (a missing query parameter, a body that isn't JSON) is left
  as it is, so it's visible in the response.
* They work in bodies (replace and modify), added or replaced headers, and the status code.

## Record mode

One run through a flow, by hand or by a test, gives fixtures to review and commit:

```
am broadcast … --es record "checkout/signup-flow.json"     # start (or continue) recording
… use the app, or run the test …
am broadcast … --ez record false                            # stop
adb pull /sdcard/Android/data/<your.package>/files/network-inspector-recordings/checkout/signup-flow.json \
         network-inspector-rules/checkout/signup-flow.json
```

* Every response **from the server** becomes a rule: method, host (as `${API_HOST}` when a variable
  has that value), path with IDs (numbers, UUIDs, long hex) as `*`, the status code, and the body as
  real JSON when it is JSON. Responses the mocks answered themselves aren't recorded.
* A second response for the same method and path is named `GET /v1/jobs/* (2)`, `(3)`…, so a polling
  flow keeps every state. Delete the ones you don't want.
* **Headers aren't recorded** (that's where tokens live). Review the bodies before committing: the
  plugin's email/token check runs on Export, not on a file you pull by hand. Importing the file with
  **Import Rules from File…** in Studio also works.
* Binary and very large bodies (over 1 MB) are skipped. Recording survives the app restarting, and
  keeps adding to the same file until stopped. Reinstalling the app removes the recordings.
* The broadcast summary shows `recording=checkout/signup-flow.json (3 rules)` while it's on.

## Debugging

* Every mocked response has an `X-Mock-Rules` header listing the rules applied.
* `adb logcat -s MockRules` shows what was loaded, what's enabled, and files that couldn't be read.

## Local variables

Variables that are only **declared** in a team file (`"local": true`, e.g. an email address or a
token) have no value in the files, so the interceptor can't know it. Pass it with the broadcast:
`--es var "EMAIL=qa@example.com"`. Until it's set, rules that use it send `${EMAIL}` as text.

In Android Studio, **Variables → Copy adb Arguments for Appium** copies your own values in this form.
In CI, build the argument from secrets (e.g. `"EMAIL=$QA_EMAIL"`) rather than committing it in test code.

## Scenarios

`network-inspector-rules/scenarios.json` (made with the **Scenario ▾** dropdown in Studio's Rules tab)
is bundled with the other files, and can be pushed next to them like the rules files. A test applies one
by name: `--es scenario "checkout-declined"`. The broadcast's summary starts with `scenario=…`, or
`scenario=<name> (NOT FOUND)` if there's no such scenario.
