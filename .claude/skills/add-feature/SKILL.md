---
name: add-feature
description: Use when adding a new screen, route or user-facing feature to the native Ecency app (Kotlin Multiplatform core, SwiftUI, Jetpack Compose), covering the RouteName and AppScreen case, saved navigation encode/decode, the shared StateModel, the iOS DestinationView and Android EcencyApp.kt screens, l10n keys and tests on both platforms.
argument-hint: [feature-name]
---

# Add Feature

Ordered procedure for a new screen. CLAUDE.md covers the architecture, commands and rules; README.md has the area
you are working in. A complete worked example to copy from is Refer & Earn: grep `Refer` through each file below.

## Step 0: RN behaviour and failing tests first

1. Read what RN did: `git show rn-3.x-final:src/constants/routeNames.ts` for the route name, then the RN screen
   (`git show rn-3.x-final:src/screens/<name>/...`). Native matches it unless README lists a deliberate difference.
2. Write the issue's **Acceptance tests** before the code and see them fail on their assertion:
   - shared model behaviour: `shared/src/commonTest/kotlin/com/ecency/core/app/<Feature>ModelTest.kt` with `TestApp`
     (see `ReferModelTest.kt`);
   - navigation: a case in `NavigationTest.kt` (route, gating, save and restore);
   - Android UI: a Robolectric flow in `androidApp/src/test/kotlin/com/ecency/app/` (see `GapsFlowsTest.kt`);
   - iOS: `iosApp/EcencyAppTests/<Area>Tests.swift` and a UI test launched with `-fakeData YES` (see
     `OnboardingUITests.swift`, which opens Refer with `-seedAccounts YES -openScreen refer`).

## Step 1: route and screen (`shared/.../core/app/Navigation.kt`)

- `RouteName`: the RN route already has an entry (every RN route is listed). Flip `built = true` and check
  `requiresLogin` (RN `showLoginAlert`). A new native-only route gets a new entry with an `L.native.navigation.*` title.
- `AppScreen`: add a `data object` or `data class` with `override val route get() = RouteName.X`. Arguments are
  saved as plain text: never put keys, memos, amounts or tokens in them; read those from a model at runtime.
  Swift sees no default arguments, so add an explicit secondary constructor when Swift builds it with fewer
  arguments (see `AppScreen.Profile(author)`).
- `AppScreen.of(route, arg)`: map the route to the new screen (links and `-openScreen route:...` use it).
- `NavState.encodeScreen` and `decodeScreen`: add a line each. Return null from `decodeScreen` for a screen that
  must not come back after process death (as `backupKeys` and `qr` do). Extend
  `everyWaveScreenSurvivesSaveAndRestoreExceptWhatMustNot` style coverage in `NavigationTest`.
- Two panes (README "iPhone Duo"): add the screen to `AppScreen.fitsAPane` only if it is reading or people that
  belongs beside a list; writing, settings, wallet actions and search cover the window.
- A link to it: extend `ContentLinks.entry` (ecency.com paths) or `AuthLinkModel`, with a `LinkRouterTest` case.

## Step 2: shared model (`shared/.../core/app/<Feature>Model.kt`)

- `class XModel internal constructor(private val env: AppEnvironment) : StateModel<XState>(XState(), env.dispatchers)`
  with an immutable `data class XState` (loading, refreshing, error as `AppError?`, `loaded`, `pages` for paging).
- Add a factory on `AppEnvironment` (`fun xModel(): XModel = XModel(this)`, as `referModel()`).
- Actions return at once and launch in `scope`; catch `CancellationException` and rethrow it, map other failures
  with `AppError.from(e)` (or `AppError.fromBroadcast(e)` for writes).
- Data belongs to the account that loaded it: wait for `env.session.awaitLoaded()`, collect `env.events` for
  `AppEvent.SessionChanged`, and drop answers that arrive for a previous account (`ReferModel.owns`).
- Copy for the screen goes in a copy object with getters (`object XCopy { val TITLE: String get() = L... }`).
- Data reads: the `add-query` skill. Writes: the `add-mutation` skill.
- If the Android UI receives the state class, list it in `androidApp/compose-stability.conf` when recomposition matters.

## Step 3: l10n keys (`src/config/locales/en-US.json` only)

- RN key with the same English and meaning: reuse it and record it in `shared/l10n/rn-reuse.json`.
- Otherwise a new key under `native.<area>` (`native.ios.<area>` / `native.android.<area>` for one platform), ICU
  plurals for counts. Never edit RN's English or the other 38 locale files.
- Use `L.native.area.key` (Kotlin) / `L.Native.Area.key` (Swift), or the shared copy object.

## Step 4: iOS screen

- SwiftUI view in `iosApp/EcencyApp/Screens/` (an existing area file, or a new file followed by
  `cd iosApp && xcodegen generate`). Hold the model as `@StateObject private var x: Observed<XState>` built from
  `Observed(env.xModel(), ownsModel: true)`, and call `start()` on appear.
- Add the case to `DestinationView` (`Screens/RootView.swift`). A missing case compiles and falls to the default
  `ShellErrorView` at runtime, so test that the screen opens.
- A screen that `fitsAPane` sets its title and bar items through `screenTitle(...)` and `screenBarItem(...)`
  (`Screens/PaneViews.swift`), so they show in the pane's header on iPhone Duo; a covering screen can use
  `.navigationTitle` (as `ReferScreen`). Lists use `.themedList()`; sheets `.themedSheet(...)`.
- Accessibility identifiers for UI tests (`.accessibilityIdentifier("x-...")`); counts through `Fmt.int`.
- Debug entry for UI tests and screenshots: a case in `AppContainer`'s `options.openScreen` switch, or use the
  generic `-openScreen route:<ROUTE_NAME>:<arg>`.

## Step 5: Android screen

- Composable in `androidApp/src/main/kotlin/com/ecency/app/ui/` (an area file such as `ProfileScreens.kt`).
- Add the branch to the `when (screen)` in `ui/EcencyApp.kt`: keep models in the stack entry
  (`entry.get("x") { env.xModel() }`, list state `entry.get("list") { LazyListState() }`) inside
  `Guarded({ ... }) { model -> ... }`; navigate with `navigator.push(...)` / `navigator.pop()`.
- Colors from `LocalEcencyColors.current`; `Scaffold(containerColor = colors.background)`; dialogs and sheets from
  `ui/Windows.kt`; clickables name a `role`; `testTag`s for tests.
- Debug entry: `--es openScreen ...` handling in `MainActivity.kt` if screenshots need it.

## Step 6: offline data for UI tests

UI tests and screenshots run without network: Hive calls answer from `DemoHiveTransport` (shared) and
`iosApp/EcencyApp/App/FakeHiveTransport.swift`, Ecency REST from `DemoAuthServices`. Add answers for every new call,
or the screen shows its error state in `-fakeData` runs.

## Checklist

- [ ] Acceptance tests were red before the code and are green now; the PR body lists each test path, the command,
      the failing assertion before and the green run after (or the issue's `No test-first: <reason>`)
- [ ] `RouteName` `built`/`requiresLogin`, `AppScreen` case, `AppScreen.of`, encode and decode, `NavigationTest`
- [ ] No secret or private value in screen arguments
- [ ] Model via `AppEnvironment` factory; account change handled; cancellation rethrown
- [ ] Copy in `en-US.json` only, `rn-reuse.json` updated for reused RN keys, no literal UI text
- [ ] iOS `DestinationView` case, `screenTitle`/`screenBarItem` for pane screens, themed surfaces, `xcodegen generate` run
- [ ] Android `ui/EcencyApp.kt` branch with `Guarded` and `entry.get`
- [ ] New route added to the themed background checks (`UniformBackgroundTest` routes, `ThemedBackgroundUITests`)
- [ ] Demo answers for new calls; README section updated
- [ ] Touched test classes green; full suites and arm64 links once at the end
