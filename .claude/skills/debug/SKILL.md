---
name: debug
description: Diagnose a bug in the native Ecency app (iOS SwiftUI, Android Compose, shared Kotlin core) when a broadcast or login fails, a screen shows "Coming soon" or "Something went wrong", a link or notification does not open, text is untranslated, stale or in the wrong direction, colours are wrong in dark mode, data is stale, or a Gradle, Kotlin/Native, XcodeGen or xcodebuild build breaks; covers the simulator and emulator scripts, debug launch arguments, logs and the l10n generator.
argument-hint: [issue-description]
---

# Debug Guide

Triage procedure. Commands and layout are in CLAUDE.md; each area's rules in README.md. Reproduce on the real code
path before fixing, and turn the reproduction into the failing test the fix makes green.

## 1. Reproduce

- Shared logic first: most bugs are in the core, reproducible in a host test in seconds.
  `./gradlew :shared:testAndroidHostTest --tests 'com.ecency.core.app.<Area>Test'` with `TestApp` and the real
  input (the RPC answer, the link, the account state). Then check the iOS side of the core:
  `./gradlew :shared:iosSimulatorArm64Test` (or `:shared:iosX64Test` on an Intel Mac).
- RN behaviour, when the question is "what should it do": `git show rn-3.x-final:<path>`.
- On a simulator: `scripts/ios-sim.sh` builds and launches on "Ecency Spike"; relaunch with arguments:

  ```sh
  UDID=$(xcrun simctl list devices | grep "Ecency Spike" | grep -oE '[0-9A-F-]{36}' | head -1)
  xcrun simctl launch "$UDID" app.esteem.mobile.ios -fakeData YES -seedAccounts YES -startTab WALLET
  ```

  Debug only arguments (`LaunchOptions` in `App/AppContainer.swift`, more under `UserDefaults` reads):
  `-fakeData YES` (offline synthetic data, what UI tests use), `-seedAccounts YES` (two signed-in fake accounts,
  with fakeData), `-openScreen settings|refer|bookmarks|editor|chat:<id>|route:<ROUTE_NAME>:<arg>|...`,
  `-openPost author/permlink`, `-openLink <url>`, `-startTab FEED|WAVES|CHATS|WALLET|NOTIFICATION`,
  `-openDrawer YES`, `-lockDemo YES` (PIN 1234), `-demoPush tap|banner`, `-pseudoLocale ar-XB|en-XA`,
  `-AppleLanguages "(ar)"`, `-forceDarkTheme YES`, `-rendererDemo YES`.
  A normal launch (no `-fakeData`) over an installed RN build runs the real RN import.
- On Android: `scripts/android-emu.sh`, then

  ```sh
  adb shell am start -n app.esteem.mobile.android/com.ecency.app.MainActivity \
    --ez demoAuth true --ez seedAccounts true --es openScreen settings
  ```

  `demoAuth` is Android's offline mode (demo Hive transport, token endpoint, HiveAuth relay); also
  `--es openTab NOTIFICATION`, `--ez demo true` (renderer demo), `--es video <embed url>`, `--es pseudoLocale ar-XB`
  (see `MainActivity.kt`). Links: `adb shell am start -a android.intent.action.VIEW -d '<url>'`; iOS
  `xcrun simctl openurl "$UDID" '<url>'`.

## 2. Logs and measurements

- Android: `adb logcat -s Ecency EcencyPerf` (`AppLog` warnings, perf marks).
- iOS: `xcrun simctl spawn "$UDID" log stream --predicate 'process == "EcencyApp"'`; perf marks only with
  `--predicate 'subsystem == "app.esteem.mobile.ios" AND category == "perf"'`.
- Perf: measure release builds; debuggable builds render post bodies about 20 times slower.
- iOS test results: `xcrun xcresulttool get test-results summary --path "$(ls -td build/ios-dd/Logs/Test/*.xcresult | head -1)"`
  after `scripts/ios-sim.sh --test`.
- From Swift, a failed core call's Kotlin exception is `(error as NSError).userInfo["KotlinException"]`.

## 3. Symptoms and where to look

| Symptom | Look at |
|---|---|
| Screen shows "Coming soon" | `RouteName.built` is false, or `AppScreen.of(route, arg)` falls to `Pending` (missing or empty arg) |
| iOS "Something went wrong" on a push | `DestinationView` has no case for the screen (the crash reporter gets "No view for ..."); on Android a throw inside `Guarded`'s prepare |
| Signed-out user lands on login | `RouteName.requiresLogin` / `pushGated`; account change drops gated screens (`afterAccountChange`) |
| Link opens the browser or nothing | `ContentLinks.entry` / `contentEntry` returned null (host not first party or not in render-helper's list, path not a post, profile or feed); `AuthLinkModel` for `ecency://` and `hive://`; cold start links wait for accounts and the welcome screen |
| Broadcast fails | `AppError.fromBroadcast` kind; `BroadcastException` subclass (`MissingAuthority`: posting key on an active op, `InsufficientResourceCredits`, `Network` with `undecided`); `SigningRouter` for HiveSigner and HiveAuth accounts; `SigningModelTest` |
| A retry posted or paid twice | a new draft or a new action id on retry; see the `add-mutation` skill |
| Old account's data on screen | missing account check before applying an answer, or no reset on `AppEvent.SessionChanged` |
| Data stale after a write | missing `env.api.posts.invalidate(...)` / `env.emit(AppEvent.PostChanged(...))`; post reads are cached 60 s |
| Infinite scroll stops or loops | `pages` not counted for a duplicate-only page; end rule on repeated cursors |
| Text in English in another language | key new and not yet back from Crowdin (expected), or a translation rejected: `L10nCatalogTest` prints `l10n rejected:` lines |
| Text does not switch with the language | copy cached in a stored `val`, `by lazy` or Swift `static let`; `CachedCopyTest`, `SwiftCopyLintTest` |
| Latin text reversed in Arabic (Android) | a `TextStyle(...)` or `BasicTextField` without `textDirection`; reproduce with `pseudoLocale ar-XB` |
| Wrong colour in dark mode | raw `.sheet(` / system colours (iOS), `MaterialTheme.colorScheme` or a non-theme `containerColor` (Android); `-forceDarkTheme YES`; the themed background tests name the file and line |
| iOS layout wrong on iPhone Duo | `UIScreen.main` or idiom use; pane rules (`isInPane`, `isPaneTop`, `screenBarItem`); run `ECENCY_SIM_DEVICE="iPhone Duo" scripts/ios-sim.sh` |

## 4. Build failures

- **`IrClassSymbolImpl is already bound` on arm64 Apple targets only**: a 5-flow `combine` in shared code
  (`NativeCompilerGuardTest` should have caught it). Reproduce with
  `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 :shared:linkReleaseFrameworkIosArm64`.
- **Xcode "Build Kotlin shared core" phase fails**: run `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64`
  in a terminal to see the Kotlin error. Every Swift file failing at once after a shared change: a copy member
  named like an Objective-C macro (`YES`, `NO`; `ObjcNamesTest`).
- **Unknown `L...` accessor or a changed signature**: the generator writes `shared/build/generated/l10n`
  (`./gradlew :shared:generateL10n`); Swift reads the copy at `iosApp/Generated/L10n.generated.swift`, refreshed by
  the Xcode build phase. A placeholder added or renamed in en-US changes the call signature on purpose; a broken
  en-US message, `selectordinal`, number styles or dates fail the build.
- **New Swift file not compiled**: `cd iosApp && xcodegen generate` (the project is generated from `project.yml`).
- **Simulator Keychain error `-34018`**: build with ad hoc signing (`CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual
  DEVELOPMENT_TEAM=`), as `scripts/ios-sim.sh` does.
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE` on Android**: an RN build with the same id signed with another key is
  installed; uninstalling it deletes its data (`scripts/android-emu.sh` says so).
- **Release build fails before packaging**: the PIN build secrets in `local.properties` are missing or do not match
  their SHA-256 (README "RN build secrets in native builds"); debug builds use dummies.
- **A lint test fails** (`UiLiteralLintTest`, `SwiftCopyLintTest`, `A11yLintTest`, `ThemedBackgroundLintTest`,
  `TextDirectionLintTest`): it prints file and line. Fix the code; allowlist only text that is not copy, with a reason.

## 5. Test-only traps

- The iOS unit test host builds no `AppEnvironment` and shows an empty root; behaviour that needs the running app
  belongs in a UI test with `-fakeData YES`.
- Robolectric never runs draw callbacks: draw-time perf marks are checked on a device.
- Android flows that time out under load: use `compose.await(...)` (`ComposeAwait.kt`), which pumps the main looper.
- A `-fakeData` screen in its error state: the new call has no answer in `DemoHiveTransport`,
  `App/FakeHiveTransport.swift` or `DemoAuthServices`.
