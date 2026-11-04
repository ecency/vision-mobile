---
name: add-sheet
description: Use when adding, presenting or debugging a bottom sheet or modal in the native Ecency app: a SwiftUI sheet through themedSheet, the root AppSheet and nextSheet chaining, or a Compose ModalBottomSheet from ui/Windows.kt, including sheets driven by shared state, dismissal versus confirm, sheets that must not close while sending, and sheet background, language and accessibility checks.
argument-hint: [sheet-name]
---

# Add Sheet

A sheet is UI on each platform over shared state. Logic (what the choices are, what confirming does, the copy)
belongs in the shared core so both apps behave the same; the sheets only draw it and forward taps. Examples with
shared state: `VoteSheetState` (`shared/.../app/VoteSheet.kt`), `LinkSheet` (`app/LinkSheet.kt`), the chat and
profile sheet models (`app/ChatSheets.kt`, `app/ProfileSheetModels.kt`).

## Step 0: RN behaviour and tests first

- Read the RN sheet: `git show rn-3.x-final:src/navigation/sheets.tsx` lists every RN sheet and its component.
- Tests before the code, failing on their assertion: the shared state or model in `shared/src/commonTest`, then a
  Robolectric flow on Android (tap the trigger, assert the sheet's `testTag`, confirm and dismiss) and an iOS UI
  test launched with `-fakeData YES` (assert the sheet's accessibility identifiers).
- Dismissal is not a confirm: test both. Swipe down, back and a scrim tap must leave state as Cancel does.

## Step 1: shared part

- The choices, labels (copy getters reading `L`), validation and the confirm action live in the core: a small
  state class (`VoteSheetState.open(...)`, `withDirection(...)`) or a model with `confirm(...)`.
- A sheet opened from a screen's action is often a model state field (`state.prompt`, `state.alert`) that the
  platforms show while non-null and clear through a model call (`dismissAlert()`).

## Step 2: iOS (SwiftUI)

- Present with `.themedSheet(isPresented:)` / `.themedSheet(item:)` or `.themedFullScreenCover(...)`
  (`Screens/Components.swift`). Raw `.sheet(` and `.fullScreenCover(` fail `ThemedBackgroundLintTests`: they paint the
  system sheet colour.
- App-wide sheets are a case of `AppSheet` (`App/AppContainer.swift`, with a stable `id`) drawn in the
  `themedSheet(item: $app.sheet)` switch of `Screens/RootView.swift`; open with `app.sheet = .x(...)`. To open
  another root sheet after this one closes, set `app.nextSheet`, which `onDismiss` shows next. Screen-local sheets
  sit on the screen with their own `@State`.
- The root passes `.environmentObject(app)`, `app.session` and `app.votes` into the sheet content; a sheet that
  needs others gets them passed the same way.
- Sizing: `.presentationDetents([.medium, .large])` when the content grows with text size; rows that do not fit
  fall back through `ViewThatFits`. Use `.interactiveDismissDisabled(state.isSending)` while a send runs
  (`WavesViews.swift`, `EditorExtrasViews.swift`).
- Signing prompts and the PIN lock sit in windows above every sheet (`AuthOverlay`, `LockOverlay`): do not try
  to present them from a sheet.
- Copy from shared copy objects or Swift `L`; identifiers via `.accessibilityIdentifier("x-sheet-...")`.

## Step 3: Android (Compose)

- Use `ModalBottomSheet` from `ui/Windows.kt` (same name as Material's; screens in `com.ecency.app.ui` get it by the
  plain name). It wraps the content in `FollowAppLanguage`, so an open sheet switches with the app language.
- Pass `containerColor = colors.background` (`LocalEcencyColors.current`); anything else fails
  `ThemedBackgroundLintTest` unless allowlisted there with a reason (media viewers).
- Hoist the trigger as nullable state and clear it on dismiss, as the vote sheet in `ui/EcencyApp.kt`:
  `voteTarget?.let { card -> VoteSheet(card, onVote = { ...; voteTarget = null }, onDismiss = { voteTarget = null }) }`.
- Content: `Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
  .testTag("xSheet"))` so it wraps its content and scrolls at the largest font scale; `imePadding()` with a field.
- A sheet that must stay open while sending: `rememberModalBottomSheetState(confirmValueChange = { it !=
  SheetValue.Hidden || !sending })` and an `onDismissRequest` that ignores the call while sending (`ReplySheet` in
  `ui/Sheets.kt`).
- Clickables name a `role`; icon-only buttons carry a label (`A11yLintTest`).

## Checklist

- [ ] Shared state or model holds choices, copy and the confirm action; tested
- [ ] Dismiss (swipe, back, scrim) leaves state as Cancel; tested on both platforms
- [ ] iOS `.themedSheet` (no raw `.sheet(`), root sheets through `AppSheet`; Android `ModalBottomSheet` from
      `ui/Windows.kt` with `colors.background`
- [ ] No dismissal while a send runs, when the result belongs on screen
- [ ] Fits and scrolls at the largest text size; identifiers and test tags for tests
- [ ] No literal UI text (l10n lints), copy keys in `src/config/locales/en-US.json` only
