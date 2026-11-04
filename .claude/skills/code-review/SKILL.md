---
name: code-review
description: Review a change to the native Ecency app (diff, branch, PR or file under shared/, androidApp/, iosApp/, build-logic/ or src/config/locales) against this repo's own traps: StateModel threading and account binding, broadcast retry safety, saved navigation, l10n copy rules and literal lints, iPhone Duo panes (isInPane, isPaneTop, transformPreference), themed surfaces, Kotlin/Native and Objective-C guards, test-first evidence and RN parity.
argument-hint: [file-or-branch]
---

# Code Review

Architecture, commands and rules are in CLAUDE.md; each area's rules are in README.md. This file is the checklist
of traps this codebase has actually hit. Confirm every finding against the code on disk (open the file, quote the
line) before reporting it, and say which test would catch it.

## Process

- [ ] **Test first.** The PR body lists each acceptance test path, the command, the assertion that failed before
      the change and the green run after, or repeats the issue's `No test-first: <reason>`. Tests assert the
      behaviour (requests sent, state, what the user sees), not only that nothing crashed.
- [ ] **New guards bite.** For each new guard (an account check, a cancel, a dedupe), removing it must fail a test
      (README "Mutation checks"). Two guards for one risk: say which test fails only when both go.
- [ ] **RN parity.** Behaviour matches `git show rn-3.x-final:<path>` unless README lists the difference; a new
      deliberate difference is added to the area's "Deliberate differences" list.
- [ ] README section for the area updated when behaviour or a rule changed.
- [ ] No PR labels added; no infrastructure details, real keys or tokens in code, tests, comments or the PR.

## Shared core (`shared/src/commonMain`)

- [ ] **State on main, work off it.** `setState` only from the model's scope (main); post bodies, summaries and card
      images render on `AppDispatchers.render`; blocking platform calls on `io`.
- [ ] **Cancellation.** Every `catch (e: Exception)` in a coroutine has `catch (e: CancellationException) { throw e }`
      before it; `runCatching` around a suspend call rethrows cancellation (`onFailure { if (it is
      CancellationException) throw it }`).
- [ ] **Account binding.** Results that belong to an account are checked against the current one before they are
      applied (`ReferModel.owns`, `PostActionsModel.requireCurrent`); `AppEvent.SessionChanged` resets the state.
      Look for a late answer of the previous account reaching the screen.
- [ ] **Broadcast retry.** Not repeat safe (`RepeatSafeOps`) means an action id made once per user confirmation
      (`env.idempotencyKey()`) and passed to `env.signing.broadcast(...)` or `env.walletSends.send(...)`. A retry
      resends the same action; a rebuilt reply draft or a fresh id on retry can post or pay twice. An undecided
      `BroadcastException.Network` never signs a new transaction by itself.
- [ ] **Chain text POSIX.** Operation builders and amounts never read `LocaleFormat` (`ChainPathsStayPosixTest`);
      typed amounts go through `Amounts.typedText`.
- [ ] **Paging ends.** A repeated cursor, a missing cursor or a page of only duplicates ends the list; `pages`
      counts every page.
- [ ] **Untrusted JSON.** Chain and API data parse through the tolerant readers (`api/model/JsonReading.kt`,
      `SafeJson`); a malformed row is skipped, never thrown.
- [ ] **Links validated.** Targets go through `SafeTarget` (`LinkRouter.route`, `ContentLinks.entry`); only
      http(s) opens a browser.
- [ ] **Saved navigation.** A new `AppScreen` has `encodeScreen`/`decodeScreen` lines and `AppScreen.of`; its
      arguments carry no keys, memos, amounts or tokens; a screen that must not return after process death
      decodes to null.
- [ ] **Kotlin/Native.** No `combine` of 5+ flows (`NativeCompilerGuardTest`); the arm64 link tasks were run for
      shared changes. Public suspend entry points have `@Throws(EcencyException::class, CancellationException::class)`;
      types Swift constructs have overloads instead of relying on default arguments.
- [ ] **Objective-C names.** No copy member named `YES`, `NO`, `id`, `init...`, `new...`, `copy...`
      (`ObjcNamesTest`).

## Localization

- [ ] Keys added only to `src/config/locales/en-US.json`; no edits to the other locale files; RN's English under an
      existing key unchanged; native copy under `native.<area>`.
- [ ] A reused RN key is word for word native's English with the same meaning, recorded in `shared/l10n/rn-reuse.json`
      (`RnReuseTest`). RN keys with fake plurals (`{suffix}`) are not reused; counts are ICU plurals.
- [ ] Copy read when shown: getters, lambdas for enum labels; no stored `val x = L...`, `by lazy`, enum constructor
      argument or `MutableStateFlow` initial value reading `L` (`CachedCopyTest`); no Swift `static let` or file
      level `let` reading the catalog (`SwiftCopyLintTest`).
- [ ] Whole messages with placeholders, never concatenated fragments; no decision made by comparing copy text.
- [ ] New allowlist lines in `androidApp/l10n-literals-allowed.txt` / `iosApp/l10n-literals-allowed.txt` are for
      text that is not copy (brand, ticker, id, debug screen), each with a reason.
- [ ] Display numbers and dates through `LocaleFormat` (iOS `Fmt.int`, `Fmt.percent`; `Text("\(n)")` formats with
      the device locale); values beside labels isolated (`Bidi.inUi`, iOS `bidiValue`).

## iOS (`iosApp/EcencyApp`)

- [ ] **DestinationView.** A new `AppScreen` has its case in `Screens/RootView.swift`; a missing one compiles and
      shows `ShellErrorView` at runtime.
- [ ] **Panes (iPhone Duo).** Screens under the pane's top stay mounted, hidden. Anything live (a chat marking
      messages read, audio, timers, polling) is gated on `@Environment(\.isPaneTop)` as `ChatThreadView` does.
      A pane screen sets its title and bar items through `screenTitle` / `screenBarItem` (`Screens/PaneViews.swift`),
      not `.navigationTitle` or `.toolbar` alone. Bar items merge with `transformPreference`; a new preference
      that uses `.preference(key:)` for a dictionary of items replaces what sibling views set. Only the selected
      tab draws the pane (`PanedTab`). `TwoPaneUITests` needs the Duo simulator unfolded and sideways.
- [ ] **Window, not screen.** No `UIScreen.main`, device orientation or idiom (`ResizabilityTests`); sizes from
      `\.viewportSize`, pixels from `\.displayScale`.
- [ ] **Themed surfaces.** `.themedSheet` / `.themedFullScreenCover`, `.themedList()` on every `List` and `Form`, no
      system background colours, black only behind media with a `// media:` comment (`ThemedBackgroundLintTests`).
- [ ] **Model lifetime.** Screen models `Observed(model, ownsModel: true)` in a `@StateObject`; app-wide models
      (`env.session`, `env.votes`) never closed by a screen. Watchers closed with the view.
- [ ] **Lists.** `ForEach(..., id: \.key)` by post key, never hashing a Kotlin data class.
- [ ] New Swift files: `project.yml` sources cover them and `xcodegen generate` was run (the project is not committed).

## Android (`androidApp/src/main/kotlin/com/ecency/app`)

- [ ] **Models per stack entry.** `entry.get("key") { env.xModel() }` inside `Guarded`, not `remember`, so they
      survive under other screens and close when popped.
- [ ] **Windows.** Dialogs, sheets, menus and popups from `ui/Windows.kt` (they follow the app language);
      `ModalBottomSheet` and `Scaffold` with `colors.background`; the shared `Spinner` and `PullToRefreshBox`; no
      `MaterialTheme.colorScheme` outside `ui/Theme.kt` (`ThemedBackgroundLintTest`).
- [ ] **Accessibility.** Clickables name a `role`; icon-only buttons have a label; text uses `blueText` / `redText`,
      not the fill tokens (`A11yLintTest`).
- [ ] **Text direction.** Every `TextStyle(...)` names `textDirection` (`Content` for user text, `Ltr` for machine
      text) (`TextDirectionLintTest`); machine fields stay left to right.
- [ ] Shared state classes the UI receives are in `androidApp/compose-stability.conf` when recomposition matters.

## Tests

- [ ] Shared tests use `TestApp` / `FakeTransport` with synthetic fixtures and throwaway keys; broadcasts are
      asserted through `app.broadcasts()`.
- [ ] Android flows wait with `compose.await(...)` (`ComposeAwait.kt`), not wall clock sleeps.
- [ ] iOS UI tests launch with `-fakeData YES`; new calls have answers in `DemoHiveTransport`,
      `FakeHiveTransport.swift` or `DemoAuthServices`.
- [ ] The full suites ran once at the end (PR command, iOS tests, arm64 links) and CI finished green before
      calling it done.
