# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project overview

Ecency mobile 4.x: the Ecency app for the Hive blockchain on iOS (SwiftUI) and Android (Jetpack Compose), over one
Kotlin Multiplatform core. It ships to both stores as the update of the React Native 3.x app (same app ids
`app.esteem.mobile.ios` and `app.esteem.mobile.android`) and imports the RN app's accounts, keys and settings in place.

The RN app was removed in #3627; its last state is the git tag `rn-3.x-final`. "RN" in code comments and README means
that app. README.md is the authoritative description of the architecture, every feature area and its rules: read the
section for the area you touch before changing it, and keep it current when behaviour changes.

```
shared/              Kotlin Multiplatform core, package com.ecency.core (models, API clients, renderer, l10n)
androidApp/          Jetpack Compose app (com.ecency.app)
iosApp/              SwiftUI app; project.yml is the source, EcencySpike.xcodeproj is generated (not committed)
build-logic/         Included Gradle build: the l10n generator and the source lint tests
src/config/locales/  Translations; en-US.json is the one source. Crowdin syncs this exact path: never move it
scripts/             ios-sim.sh, android-emu.sh, duo-screenshots.sh, icons/, release checks
tools/               Golden fixture generators run by hand (tools/README.md); never part of a build
docs/                Release builds, post rendering notes, brand sources
```

## Commands

Requirements: JDK 17+, Android SDK 37 (`local.properties` with `sdk.dir=...`), Xcode 26+ (27.1+ for iPhone Duo),
XcodeGen (`brew install xcodegen`).

```sh
# What CI runs on a pull request (Linux)
./gradlew --no-daemon :build-logic:test :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:lintDebug

# One test class while working
./gradlew :shared:testAndroidHostTest --tests 'com.ecency.core.app.NavigationTest'
./gradlew :androidApp:testDebugUnitTest --tests 'com.ecency.app.ShellFlowsTest'
./gradlew :build-logic:test --tests '*UiLiteralLintTest*'

# Shared tests on the iOS simulator target (Apple silicon / Intel Mac)
./gradlew :shared:iosSimulatorArm64Test        # or :shared:iosX64Test

# After any change in shared/: the arm64 Apple links (Kotlin/Native can crash where JVM and iosX64 pass)
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 :shared:linkReleaseFrameworkIosArm64

# Everything on the Kotlin side, and the Android app
./gradlew check
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:connectedDebugAndroidTest   # device tests (Keystore), emulator attached

# iOS: regenerate the project after adding or removing Swift files, then test
cd iosApp && xcodegen generate
xcodebuild test -project EcencySpike.xcodeproj -scheme EcencyApp \
  -destination 'platform=iOS Simulator,name=Ecency Spike' \
  CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= \
  -only-testing:EcencyAppTests/ShellTests          # or EcencyAppUITests/ShellUITests

# Run on a simulator or emulator
scripts/ios-sim.sh                  # build, install, launch on the "Ecency Spike" simulator (created on first run)
scripts/ios-sim.sh --test           # all iOS unit + UI tests on that simulator
scripts/ios-sim.sh --shot out.png   # launch, then screenshot
ECENCY_SIM_NAME="Ecency Duo" ECENCY_SIM_DEVICE="iPhone Duo" scripts/ios-sim.sh
scripts/android-emu.sh              # build, install, launch on a device or the AVD in ECENCY_AVD
scripts/android-emu.sh --shot out.png
```

The Xcode build runs Gradle itself ("Build Kotlin shared core" phase: `:shared:embedAndSignAppleFrameworkForXcode
:shared:generateAppleL10n`), so a shared change needs no separate step before `xcodebuild`. The ad hoc signing flags
matter: the simulator Keychain refuses unsigned apps (`-34018`). Debug launch arguments for offline runs
(`-fakeData YES`, `-seedAccounts YES`, `-openScreen ...`): see the `debug` skill and README "iOS app (SwiftUI)".

## Architecture

### Shared core (`shared/src/commonMain/kotlin/com/ecency/core`)

- `api/`: Hive RPC reads (`HiveApi`, `rpc/HiveRpcClient` with node failover), Ecency REST and private API clients
  (`api/ecency/*`, `EcencyRestApi`), broadcast builders (`api/broadcast/*Ops.kt`). One `EcencyApi` per process.
  Calls mirror what `@ecency/sdk` does for the RN app, same methods and parameters.
- `hive/`: transaction serialization and signing, pinned to the SDK by golden vectors (`commonTest/.../hive`).
- `auth/`: logins, HiveSigner, HiveAuth, `SigningRouter`, `OperationAuthority`.
- `app/`: the presentation layer both UIs draw. `AppEnvironment` (one per process, `AppEnvironment.create(...)`)
  owns the API, vault, session, signing and every model factory (`env.feedModel(...)`, `env.postModel(...)`,
  `env.referModel()`...). Models extend `StateModel<S>` (`app/StateModel.kt`): immutable state in a `StateFlow`,
  changed only on `AppDispatchers.main`; actions return at once and failures land in state as `AppError`.
- `render/`: a pure Kotlin port of `@ecency/render-helper` (`RenderHelper`, `renderPostBodySafe`) and
  `render/blocks/BlockBuilder.kt`, which turns post HTML into the native block model. Renders run on
  `AppDispatchers.render`, never main. `PostBodyRenderer` is the seam the models use.
- `l10n/`: `L10n` (current language), `LocaleFormat` (numbers and dates), the generated `L` accessors.
- `secure/`: the key vault over `SecureStore` (Android Keystore, iOS Keychain).

Swift sees the core as the `EcencyCore` framework. Suspend entry points are `@Throws(EcencyException::class,
CancellationException::class)`; a thrown error carries the Kotlin exception in `userInfo["KotlinException"]`. Swift
sees no default arguments, so types Swift builds get explicit overloads (see `AppScreen.Profile(author)`).

### UI on each platform

- iOS: `iosApp/EcencyApp/App/AppContainer.swift` owns the environment, `nav`, the root `sheet` and alerts.
  `Observed<S>` (`App/Observed.swift`) mirrors a `StateModel` into SwiftUI and closes it with the view. Screens live
  in `EcencyApp/Screens`, post body drawing in `EcencyApp/Render`.
- Android: `androidApp/src/main/kotlin/com/ecency/app/ui`. `ui/EcencyApp.kt` maps each `AppScreen` to its
  composable; models are kept per stack entry with `entry.get("key") { env.xModel() }` and wrapped in `Guarded`
  (the error boundary). Theme tokens are `EcencyColors` in `ui/Theme.kt`; windows (dialogs, sheets, menus) come
  from `ui/Windows.kt` so they follow the app language.

### Navigation (`app/Navigation.kt`)

- `RouteName`: every RN route with its title, `built` and `requiresLogin`. Unbuilt routes open `AppScreen.Pending`.
- `AppScreen`: the typed screens (sealed). `AppScreen.of(route, arg)` maps a route name to one.
- `NavState`: a value (tab, `stack`, `pane`, `drawerOpen`). `push`, `pushGated` (login for gated routes),
  `pushIn` (two panes on a wide window, README "iPhone Duo"), `back`, `open(entry, loggedIn)`,
  `afterAccountChange`. `encode`/`decode` save it (Android `SavedStateHandle`, iOS scene storage): a screen's
  arguments are saved as plain text, so they never carry secrets, keys, memos or amounts.
- `NavEntry`: where a deep link or notification goes (an optional tab, then screens).
- Links: `LinkRouter.route(target)` turns body taps into a `Route`; `ContentLinks.entry(url)` /
  `contentEntry(url)` turn ecency.com (and other Hive frontend) URLs into a `NavEntry`; `AuthLinkModel` takes
  `ecency://`, `hive://` and the auth pages first. Every target is validated again with `SafeTarget`.
- iOS draws a screen in `DestinationView` (`Screens/RootView.swift`); Android in the `when (screen)` of
  `ui/EcencyApp.kt` with the `Navigator` of `ui/Navigation.kt`.

### Broadcasts

Every Hive write goes through `env.signing.broadcast(user, ops, authority, actionId)` (`app/SigningModel.kt`),
which routes key, HiveSigner and HiveAuth accounts and the active key upgrade. A retry of the same action
(`actionId` from `env.idempotencyKey()`) re-sends the same signed transaction; never rebuild operations or a reply
draft to retry. README "Data layer" and "Node failover" hold the rules.

### Localization

- Add copy to `src/config/locales/en-US.json` only (ICU MessageFormat, `{name}` placeholders, plurals as
  `{count, plural, one {# vote} other {# votes}}`). Never edit the other locale files: Crowdin writes them.
- Native only copy goes under `native.<area>` (`native.ios.<area>`, `native.android.<area>` for one platform).
- Reuse an RN key only when its English is word for word native's text with the same meaning, and record it in
  `shared/l10n/rn-reuse.json`. Never change RN's English under an existing key; add a new key instead.
- `:shared:generateL10n` (build-logic `L10nGenerator`) writes typed accessors into `shared/build/generated/l10n`:
  Kotlin `L.settings.language`, `L.post.a11yVotes(count = 3)`; Swift `L.Settings.language`. A changed
  placeholder changes the signature, so call sites fail to compile.
- Copy is read when shown: `val X: String get() = L...`, never a stored `val`, `by lazy` or `static let`
  (`CachedCopyTest`, `SwiftCopyLintTest`). Code never decides by comparing copy text.
- Screen text both apps share lives in copy objects (`UiCopy`, `ReadingUiCopy`, ... in `app/ScreenCopy.kt`).
- Literal lints fail on UI text not from the catalog: `UiLiteralLintTest` (Android) and `SwiftCopyLintTest` (iOS),
  with allowlists `androidApp/l10n-literals-allowed.txt` and `iosApp/l10n-literals-allowed.txt` for text that is
  not copy (brands, tickers, ids) only.
- Chain and protocol text stays POSIX and English (`ChainPathsStayPosixTest`); display numbers and dates go
  through `LocaleFormat` (iOS `Fmt.int`, `Fmt.percent`).

## Rules

- **RN parity.** Native matches the RN app's behaviour unless README lists a deliberate difference. Read the RN
  source with `git show rn-3.x-final:<path>` (e.g. `git show rn-3.x-final:src/constants/routeNames.ts`) and name
  the RN file in comments where behaviour follows it.
- **Native means native.** No runtime JavaScript. JS packages run only offline in `tools/` to generate golden
  fixtures (`npm ci --prefix tools`, and `npm ci --prefix tools/render` for the renderer ones; tools/README.md).
- **Kotlin/Native guard.** No `combine` of 5 or more flows in shared code (`NativeCompilerGuardTest`): it crashes
  the arm64 Apple compiler. Combine two at a time and run the link tasks above.
- **Objective-C names.** Copy members never use names like `YES`, `NO`, `init...`, `new...`, `copy...`
  (`ObjcNamesTest`): the framework header breaks.
- **Themed surfaces.** iOS uses `.themedSheet(...)` / `.themedFullScreenCover(...)` and `.themedList()`, never raw
  `.sheet(` or system background colours (`ThemedBackgroundLintTests`). Android uses `Scaffold(containerColor =
  colors.background)`, the shared `Spinner`, `PullToRefreshBox` (`ThemedBackgroundLintTest`).
- **Accessibility.** Android clickables name a `role` and icon-only buttons a label, text uses `blueText` / `redText`
  (`A11yLintTest`); every Compose `TextStyle(...)` names its `textDirection` (`TextDirectionLintTest`).
- **Window size, not screen size (iOS).** No `UIScreen.main`, device orientation or idiom (`ResizabilityTests`);
  read `\.viewportSize` and `\.displayScale`.
- **Never commit** signing keys, keystores, profiles, `google-services.json`, `GoogleService-Info.plist`, `.env`
  files, tokens, DSNs or real account keys. The repo is public: keep PRs and comments free of infrastructure details.
  Test data uses throwaway keys only.

## Testing

- **Test first.** Issues carry a required **Acceptance tests** field (`.github/ISSUE_TEMPLATE/spec.yml`). Write
  those tests before the code, run them and see them fail for the right reason (the assertion, not a compile error
  or a missing fixture), then make them green. The PR body lists each test path, the command, the assertion that
  failed before the change, then the green run after it, or repeats the issue's `No test-first: <reason>`.
- Every change lands with tests (README "Tests are required"):
  - shared logic: `shared/src/commonTest` (runs on the Android host and iOS). Models use `TestApp`
    (`commonTest/.../app/TestApp.kt`: fake transport, in-memory vault, test dispatchers, fixed clock); RPC is
    scripted with `FakeTransport` (`commonTest/.../api/FakeTransport.kt`), Ecency REST with
    `app.authBackend.rest` / `privateApi` (`AuthFakes.kt`). New operations get golden vectors from the SDK.
  - Android UI: Robolectric Compose tests in `androidApp/src/test` (`TestNode`, `compose.await(...)` from
    `ComposeAwait.kt`); Keystore only in `androidApp/src/androidTest`.
  - iOS: `iosApp/EcencyAppTests` (XCTest) and `iosApp/EcencyAppUITests` (XCUITest, launched with `-fakeData YES`).
- While working, run only the test classes the change touches; run the full suites once at the end (the PR
  command above, the iOS tests, and the arm64 link tasks after shared changes).
- For a new guard, check it bites: remove or weaken it, see a test fail, restore it (README "Mutation checks").
- File pre-existing failures you find on the base branch as issues; do not just note them.

## Pull requests and CI

- `test.yml` runs the PR command above on Linux (skipped for docs-only changes). No workflow builds an app on a
  pull request; `build-android.yml` and `build-ios.yml` build, sign and upload on pushes to `development` (iOS on
  GitHub's `xcode-27` image, Xcode 27.1). `e2e-android.yml` runs the device tests by hand or on its label.
- Never add labels to a PR: labels trigger workflows (`e2e-test`, `sync-translations`) and are set by hand.
- Base branch is `development`. Releases and secrets: `docs/release-builds.md`.
