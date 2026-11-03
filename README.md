# Network Inspector Shared Rules

An Android Studio plugin that lets your team share **App Inspection → Network Inspector → Rules**
through git. There's no extra tab and no app-side code: you keep using Studio's own Rules tab,
and the rewritten responses show up in Network Inspector as usual.

## Folder layout: one folder per team

```
your-app-repo/
└── network-inspector-rules/
    ├── login/
    │   ├── session-expired.json
    │   └── mfa.json
    └── checkout/
        └── payment-declined.json
```

* Each **subfolder is a team**, and each team can have as many `.json` files as it likes.
* In Studio's Rules tab, shared rules show up as **`[team] name`**, e.g. `[login] Session expired`
  or `[checkout] Payment declined`. Two teams can use the same rule name without clashing.
* Each developer can hide teams they don't need with **Teams…**. Rules from hidden teams are
  removed from their Rules tab. New team folders appear automatically.
* In the Rules tab, rules are **grouped by team** under a header showing the team name, its rule
  count and how many are on. Click a header to collapse or expand that team. Your own rules
  (no `[team]` prefix) are grouped last, under *Local rules*. Collapsing only hides rows: collapsed
  rules still apply, and the order Studio applies rules in doesn't change. Which teams are
  collapsed is remembered per developer. Studio's ↑/↓ buttons only move a rule within its own
  team, and are off while any team is collapsed.
* To create a new team, use **Create Team…**. **Rename Team…** and **Delete Team…** are under the
  **⋯** button (and the Tools menu).

## What you get

Buttons added to the right of the **+ / −** buttons in the Rules tab, with less common actions under **⋯**. The same actions are
under **Tools → Network Inspector Shared Rules**.

| Button | What it does |
|---|---|
| ⟳ **Refresh Shared Rules** | Loads every visible team's files into the Rules tab. If an app is connected, the rules go to the device **immediately**, with no restart. |
| ⇪ **Export Shared Rules…** | Shows a checklist of your Rules-tab rules. Shared rules go back to their own file. Local rules you check go to the file you pick under "Save new rules to" (`team/file.json`). |
| ⇩ **Import Rules from File…** | Picks one or more rules files from anywhere on disk (same JSON format) and copies them into a team folder you choose, then refreshes. If a file with that name already exists in the team, the rules are merged and you're asked before same-named rules are replaced. A `[team]` prefix left in imported rule names is removed. |
| ☑ **Turn On/Off by File…** | Turns every rule in a file on or off at once, so a file works as a scenario (e.g. `checkout/payment-declined.json`). Optionally turns off every other shared rule, to test one scenario at a time. Only changes your Rules tab. |
| 📁 **Create Team…** | Creates a new team folder with an empty starter `rules.json`, so the team is committed to git and shows up as an Export destination. |
| ⧩ **Choose Teams…** | Chooses which team folders *you* load. This choice is local and not committed. |
| 📂 **Show Shared Rules Folder** | Shows `network-inspector-rules/` in the Project view. |
| ⋯ **Rename Team…** | Renames a team folder. Its rules become `[new name] …` in Studio and keep your on/off choices. |
| ⋯ **Delete Team…** | Deletes a team folder and its rules, after a confirmation. Git or Local History can undo it until you commit. |
| ⋯ **Variables…** | Opens the **Variables** tab (see below), or the same table in a dialog when no app is connected. |

It also refreshes on its own:
* **On project open** it imports the folders, so the rules are already in the Rules tab when you
  open Network Inspector.
* **When a file changes** (`git pull`, a hand edit) and no inspector session is running, it
  updates silently. During a live session you get a notification with a **Refresh now** link.

## Variables: shared and local

Rules can use variables as `${NAME}` (in the host, path, headers or body). A **Variables** tab next to
Network Inspector's **Rules** tab lists every variable:

| Where | Meaning |
|---|---|
| **Shared** · `team/file.json` | Defined in a team file, so its value is **committed**. Good for hosts and IDs everyone uses. |
| **Local** | Only on your machine (in `.idea/workspace.xml`), **never committed**. Use these for emails, test accounts and tokens. Variables made in Studio's own Rule Variables dialog are local too. |
| **Local** · declared in `team/file.json` | Local, and a team file *declares* it (name and description, no value), so teammates are asked for their own value. |
| **Missing** | A rule uses it (or a team file declares it) but you have no value. Type one in **Your value**; it stays local. |

* **Your value** can be edited for any variable and never leaves your machine. For a shared variable it
  replaces the shared value only in your Rules tab; leave it empty to use the shared value.
* **Description** tells teammates what to put in a variable (e.g. "Your QA account email"). It's stored
  in the team files, so it can be edited for shared and declared variables.
* **+** adds a variable: **Local** (the default), **Local, declared in a team file**, or **Shared** (its
  value is committed; you're warned if it looks like an email address or a token).
* **Share in Team File…** puts a local variable in a team file, either **Declare only** (the default:
  teammates set their own value, nothing is committed) or with its value.
* **Make Local** takes a shared variable's value out of the team files, leaving a declaration, and
  keeps the value on your machine. Commit the change; if the value was sensitive it's still in git history.
* **−** removes a variable, from the team files too after a confirmation.
* **Copy adb Arguments for Appium** copies your values as `--es var "EMAIL=…,TOKEN=…"` for the
  [Appium mocks](appium-mocks) broadcast (see below).
* Changes apply immediately: rules in a connected app are sent again with the new values.

The file format for a declared variable:

```json
"variables": [
  { "name": "API_HOST", "value": "api.example.com" },
  { "name": "EMAIL", "local": true, "description": "Your QA account email" }
]
```

**Keeping personal data out of git**

* **Export never writes the value of a variable that isn't already shared.** Rules that use a local
  variable are written with `${NAME}` as is. If a team file declares the variable, the declaration is
  carried into the files the rules go to; otherwise the Export notification suggests declaring it.
* **Emails and tokens typed into rules are caught** before Export, **Save to Team** or **Create Network
  Rule from Endpoint** write them to a team file: email addresses anywhere in a rule, JWTs and bearer
  tokens, and the values of headers like `Authorization`, `Cookie` or `X-Api-Key`. A dialog lists them
  and **Replace and Continue** swaps each for a local variable (e.g. `${EMAIL}`), named after an
  existing variable that already holds that value when there is one. **Keep Values** writes them as is.
* **Missing values are flagged.** After a refresh, and when an app connects, a notification lists the
  variables your rules use but you haven't set (with their descriptions). Until you set them, those
  rules send the text `${NAME}` as is. **Set values…** opens the Variables tab.

## Saving a rule to a team from the Rules tab

When you select a rule in the Rules tab (including one you just made with **+**), a bar above
Studio's rule details shows its **Team** and **File**:

* For a local rule the team is *None*. Pick a team (and file) and press **Save to Team**: the rule
  is written to `team/file.json`, renamed `[team] name`, and keeps its on/off state. Type the
  name without a `[team]` prefix; the team supplies it.
* For a shared rule it shows the file it came from. **Save Changes** writes your Rules-tab edits
  back to that file, without going through Export. Picking another team or file moves it there.
* If that team already has a rule with the same name in another file, you're asked to rename it
  first; if the chosen file already has one, you're asked before it's replaced.

## Create a rule from a Retrofit endpoint

Right-click a Retrofit interface method, or its `@GET` / `@POST` / … annotation, and choose
**Create Network Rule from Endpoint…**. It's also in **Generate** (⌘N / Alt+Insert). It works in
Kotlin and Java.

```kotlin
interface UserApi {
    @GET("users/{id}")
    suspend fun user(@Path("id") id: String): User   // → GET, path */users/*
}
```

The dialog is pre-filled, and everything in it can be edited:

* **Method** from the annotation (`@HTTP(method = …)` too). Path parameters such as `{id}` become
  `*`, because Studio matches host, port, path and query with wildcards (`*` = anything,
  `?` = one character, empty = anything, and the pattern must match the whole value).
* **Path** is `*/` plus the annotation path, e.g. `@GET("trips/{id}")` → `*/trips/*`. The leading
  `*` matches whatever base path the service uses in any environment (`/mobileapps/…/int/v1.0/…`),
  so nothing environment-specific is hardcoded. Edit it in the dialog if you need to tell apart two
  services with the same endpoint path, e.g. `*/drivewise/api/trips/*`.
* **Protocol, host and port** from the **Base URL** under
  *Settings → Tools → Network Inspector Shared Rules* (its path is ignored). Leave it empty to match any host.
  The host can be a variable, e.g. `https://${API_HOST}/`, so each developer can point it elsewhere
  in the **Variables** tab. The setting is stored in `.idea/networkInspectorRules.xml`;
  commit that file if you want the team to share it.
* **Status code** 200, and a **response body** built from the return type. `Call<T>`,
  `Response<T>`, `suspend`, RxJava, `Deferred`, `Flow` and `LiveData` are unwrapped first. Fields use
  the names from `@SerializedName` (Gson), `@Json` (Moshi), `@SerialName` (kotlinx.serialization)
  or `@JsonProperty` (Jackson). Strings are filled with their field name, numbers with 0, lists
  with one item, enums with their first value. `Unit`, `Void`, `Completable` and `ResponseBody`
  get no body.
* **Team** and **File**: pick a team to save the rule straight into that team's file (`rules.json`
  by default; pick another of the team's files or type a new name). It shows up in the Rules tab as
  `[team] name`. Leave the team at *None* to add it to your Rules tab only and export it later.

Rules only rewrite the **response**; the real request is still sent to the server. Types the
plugin can't see into (custom adapters, polymorphic types) get a best guess for you to edit.

## Using the same rules in Appium tests

Network Inspector rules only work while Android Studio is attached to the app. For Appium tests
on real devices, [`appium-mocks/`](appium-mocks) has a debug-only OkHttp interceptor to copy into
the app. It reads these same files, and a test turns scenarios on with an adb broadcast, e.g.
`--es only "checkout/payment-declined.json"`. See its README.

## How sharing behaves

* Rules are matched **by their Studio name** (`[team] name`). A refresh replaces every Studio rule
  that came from the folders with the file's version, and a rule deleted from a file is removed
  from Studio. Rules you created yourself (no `[team]` prefix) are never touched until you export them.
* **On/off is per developer.** Toggling a rule doesn't change any file. New shared rules start
  **disabled**, and your toggles are kept across refreshes.
* **Export:** a shared rule always goes back to the file it came from, and Studio's version wins.
  Unchecking a shared rule removes it from its file. Local rules start unchecked, so scratch rules
  aren't shared by accident. When you export one, it's renamed to `[team] name` in Studio.
  Each file also stores the **shared** variables (`${NAME}`) its rules use. Local variables are never
  written to a file (see below).
* If a shared file changed since your last refresh (for example a `git pull` during a live
  session), **Export** warns you and offers to refresh first, so a teammate's edits aren't overwritten.
* If a file can't be read, the error notification has a link that opens it at the problem line.
* Don't rename a shared rule inside Studio. Its name is the sync key, so rename it in the JSON instead.

## File format

Rules files get completion, hover docs and error highlighting in the editor from a bundled JSON
schema, so typos show up as you type rather than at Refresh time.

See [`examples/network-inspector-rules/`](examples/network-inspector-rules). Each file mirrors
the Rules tab one to one. Rule names in files have **no** `[team]` prefix; the folder supplies it:

```jsonc
{
  "version": 1,
  "variables": [{ "name": "API_HOST", "value": "api.example.com" }],
  "rules": [{
    "name": "Profile: server error",                       // unique within the team
    "criteria": { "protocol": "HTTPS", "host": "${API_HOST}", "port": "", "path": "/v1/profile",
                  "query": "", "method": "GET" },           // method: ANY, GET, POST, ...
    "statusCode": { "active": true, "findCode": "", "newCode": "500" },
    "headers": [
      { "type": "add", "name": "X-Foo", "value": "bar" },
      { "type": "replace", "findName": "Cache-Control", "findNameRegex": false,
        "findValue": ".*", "findValueRegex": true, "newName": "Cache-Control", "newValue": "no-store" }
    ],
    "body": [
      { "type": "replace", "body": { "error": "internal_error" } },   // JSON stays real JSON
      { "type": "modify", "targetText": "\"premium\":false", "regex": false, "newText": "\"premium\":true" }
    ]
  }]
}
```

Keep in mind that these are Studio's rules: the request still goes to the real server, and Studio
rewrites the response. Don't commit secrets (auth headers, tokens) in rules.

## How it works

Studio saves Rules-tab rules in `.idea/workspace.xml` (component `NetworkInspectorRules`), which
is per user and not committed. The plugin:

1. Reads and writes that saved list (`RulesPersistentStateComponent`).
2. When a Network Inspector session is live, it finds the session's `RulesTableView` and adds
   rules through Studio's own `addRule`, which sends them to the device. It removes rules with
   the same `InterceptRuleRemoved` command Studio uses.
3. It adds its toolbar next to the Rules tab's built-in toolbar.
4. It groups the Rules table by team with a `RowSorter` that only changes which rows are shown and
   in what order, never the rule list itself. The team headers are drawn on top of each group's
   first row ([`RulesTableGroups.kt`](plugin/src/main/kotlin/io/github/networkrules/RulesTableGroups.kt)).

All of this is Studio-internal API (`com.android.tools.idea.appinspection.inspectors.network.*`)
and is confined to [`NetworkInspectorBridge.kt`](plugin/src/main/kotlin/io/github/networkrules/NetworkInspectorBridge.kt)
plus the toolbar injection in `SharedRulesService.kt` and the grouping in `RulesTableGroups.kt`. It's verified to compile against
**Android Studio 2025.3 (build 253)**. A future Studio version may need small fixes there. If the
toolbar injection ever stops matching Studio's layout, the Tools menu actions still work.

## Releases

Teammates don't need to build anything. They download the zip from the repo's
[**Releases**](../../releases) page and install it (see below).

To publish a new version, push a tag. The [Build plugin](.github/workflows/release.yml) workflow
builds the zip, versions it from the tag, and attaches it to a GitHub Release:

```bash
git tag v0.3.0
git push origin v0.3.0
```

### Verifying a download

Each release includes `SHA256SUMS.txt`. Check the zip before installing it:

```bash
shasum -a 256 -c SHA256SUMS.txt
```

Once the repo is public, releases also carry a signed **build attestation**. It proves the zip was
built by this repo's workflow from this repo's code, and not uploaded by hand:

```bash
gh attestation verify network-rules-sync-<ver>.zip -R lecrane54/AppInspectorPlus
```

Workflow actions are pinned to commit SHAs. Dependabot opens PRs for updates, and the Gradle
download and wrapper jar are checked against Gradle's official checksums.

You can also run the workflow by hand from the **Actions** tab. That gives you the zip as a build
artifact without creating a release. CI downloads the Android Studio version named by
`androidStudioVersion` in `gradle.properties`, so bump it when you move to a new Studio.

## Build & install

```bash
./gradlew buildPlugin   # → plugin/build/distributions/network-rules-sync-<ver>.zip
./gradlew test          # unit tests for the sync logic
./gradlew runIde        # sandboxed Android Studio with the plugin, for trying it out
```

Install the zip through Settings → Plugins → ⚙ → **Install Plugin from Disk…**, then restart.
`androidStudioPath` in `gradle.properties` points to a local Studio install to compile against.
If that path doesn't exist, Gradle downloads `androidStudioVersion` instead.

To push updates to the team automatically, host the zip in a
[custom plugin repository](https://plugins.jetbrains.com/docs/intellij/custom-plugin-repository.html).

**Corporate TLS proxy:** if Gradle fails with `PKIX path building failed`, import your proxy's
CA into a truststore and pass `-Djavax.net.ssl.trustStore=…` in both `JAVA_OPTS` and
`org.gradle.jvmargs`.

## License

[MIT](LICENSE)
