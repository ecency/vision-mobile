---
name: code-review
description: Review a vision-mobile React Native change (diff, branch, PR or file) against this repo's own traps and conventions: action sheet return values, setNativeProps caret, safe-area edges, editor teardown order, notification routing copies, SDK mutation wrappers, EStyleSheet theming.
argument-hint: [file-or-branch]
---

# Code Review

Architecture, commands, TypeScript and ESLint rules live in CLAUDE.md. This file holds
the traps and repo conventions this review gates on. Confirm each finding against the
code on disk before reporting it.

## Action sheets

- [ ] **Resolve an object, gate on a named field.** A repo convention, not something the
  library enforces today. Two different payloads are involved, so keep them apart when
  reviewing:
  - the SHOW payload, `SheetManager.show(name, { payload })`, which the provider hands to
    the registered component as a prop. Every sheet uses this.
  - `<ActionSheet payload={...}>`, ActionSheet's OWN prop. Nothing in `src/` sets it, and
    forwarding the show payload into it is not the same thing.

  Only the second one feeds `payloadRef`, and the library resolves a close with
  `data || payloadRef.current || data`. Since no sheet sets that prop, `payloadRef.current`
  is `undefined` and a falsy result reaches the caller intact today. Keep the convention
  anyway: adding `payload` to one `<ActionSheet>` would silently turn every falsy cancel in
  that sheet into a confirm. A dismissal also resolves `undefined`, which truthiness cannot
  tell apart from a deliberate `false`. Sheets resolve `{ cancelled: true }`
  or `{ field: value }` instead. Sheets document their own contract, e.g.
  `src/components/searchFiltersSheet/searchFiltersSheet.tsx`. Callers test the field,
  abridged from `src/screens/searchResult/screen/searchResultScreen.tsx:65-77`:
  ```ts
  const result = await SheetManager.show(SheetNames.SEARCH_FILTERS, {
    payload: { filters, searchValue: clipSearchValue(searchInputValue) },
  });
  if (result && typeof result === 'object' && result.filters) { ... }
  ```
- [ ] **Sheets unmount on hide**, so mount-time resets are enough and cleanups run on
  close (CLAUDE.md, Sheets). Reject "state persists between invocations".
- [ ] **A throw in a sheet render or cleanup is fatal:** `SheetProvider` wraps
  `<Application/>` (`src/index.tsx:39-43`), outside `ErrorBoundary`
  (`src/screens/application/index.tsx:17`). Watch native objects in cleanups.
- [ ] **Payloads freeze at show time.** A handler in a payload keeps the closure it had
  when the sheet opened, so route it through a ref
  (`src/components/quickPostModal/quickPostModalContent.tsx:565-575`).
- [ ] **Missing `SheetDefinition`?** `everySheetHasDefinition`
  (`src/navigation/sheets.tsx:358`) fails typecheck and names it. Keys are plain string
  literals.

## Caret on programmatic writes

- [ ] **`setNativeProps({ text })` on its own moves the caret.** Android keeps the
  caret's DISTANCE FROM THE END, so it lands inside the text just written and the next
  keystroke splits it. Pass `selection` whenever the caret position after the write
  matters: inserts and appends into existing text, plus full replacements of a focused
  field. A reset to `text: ''` does not need it. Call sites that pass it:
  `src/components/quickPostModal/quickPostModalContent.tsx:559` and `:604`, plus
  `src/components/markdownEditor/view/markdownEditorView.tsx:371`.

## Editor teardown order

- [ ] **Pending work drains before the save, not in a child cleanup.**
  `componentWillUnmount` runs in the commit phase ahead of descendant effect cleanups,
  so the screen calls `flushPendingEditorWork()` then `_saveDraftToDB()`
  (`src/screens/editor/screen/editorScreen.tsx:116-127`). Register new deferred editor
  work via `registerPendingFlush`
  (`src/components/uploadsGalleryModal/mediaInsertQueue.ts:33`), not a local cleanup.

## Safe area

- [ ] **`edges` REPLACES the defaults, it does not add to them.** `edges={['bottom']}`
  removes the top inset. The top inset is generally the screen's job: a screen
  rendering `BasicHeader` wraps it in its own `SafeAreaView`
  (`src/components/basicHeader/view/basicHeaderStyles.ts:13`).

## Notification routing

Notification type strings are matched in more than one place, whose spellings do NOT
agree. A new type may need adding in several of them.

- [ ] Tap routing: the switch at
  `src/screens/application/hook/useInitApplication.tsx:222-302`; its `default` does
  nothing.
- [ ] Websocket to FCM bridge: the allowlist at
  `src/screens/application/container/applicationContainer.tsx:891-901` admits
  `mention`, `reply`, `transfer`, `delegations`, `scheduled_published`, `payouts`,
  `account_update` and `weekly_earnings`. Each one also needs a case in the title/body
  switch at `:914`, whose `default` announces a bare `@source`.
- [ ] Foreground banner: the allowlist at
  `src/components/foregroundNotification/foregroundNotification.tsx:51-58` admits
  `reply`, `mention`, `transfer`, `delegations` and `scheduled_published`; anything
  else shows nothing. Its own `_onPress` (`:127`) routes `transfer` and `delegations`
  to the wallet, everything else to a post.

Mind the singular/plural split: tap routing matches `favorite` and has no payout case
at all, the list rendering (`src/utils/notificationImage.ts:12`,
`src/components/notificationLine/view/notificationLineView.tsx:138`) matches `favorites`
and `payouts`, while the websocket allowlist has `payouts` but no `favorites`.

## SDK, queries, styling, i18n

- [ ] Broadcast mutation wrappers are thin: `useMutationAuth()` from
  `src/providers/sdk/mutations/common.ts`, then the SDK hook. No key decryption, no
  HiveSigner/HiveAuth branching in one; the adapter owns that. The documented
  exceptions are not broadcasts, so do not report them: `useGenerateImageMutation.ts`
  and the digest hooks in `useNewsletterDigestMutations.ts` bind the HiveSigner `code`
  from `useAuth()`, while `useClaimPointsMutation.ts` decrypts
  `currentAccount.local.accessToken` into a REST access token. A new wrapper that
  reaches for keys without a non-broadcast reason is still a finding.
- [ ] Optional query params need `enabled: !!param`.
- [ ] Mobile-only keys come from `QUERIES`, the DEFAULT export of
  `src/providers/queries/queryKeys.ts`; SDK-owned data uses `QueryKeys` from
  `@ecency/sdk`.
- [ ] DMCA lists are set once by `ConfigManager.setDmcaLists` in
  `src/providers/queries/sdk-config.ts:69`; a hand-rolled filter in a query is a
  finding.
- [ ] `vestsToHp(vests, hivePerMVests)` takes TWO args and returns `0` when either is
  falsy (`src/utils/conversions.ts`), so a missing rate renders a silent 0.
- [ ] Colors come from theme vars: `'$primaryBackgroundColor'` inside
  `EStyleSheet.create` or `EStyleSheet.value('$primaryBlue')` at runtime. A literal hex
  is a finding when it shadows a var, above all one that differs between
  `src/themes/lightTheme.ts` and `src/themes/darkTheme.ts`. Deliberately
  theme-independent chrome is not a finding, e.g. the black media backgrounds in
  `src/screens/waves/styles/wavesReels.styles.ts`, so flag literals on a surface that
  should follow the theme. `$white` is `#1e2835` in the dark theme, `$pureWhite` stays
  white in both.
- [ ] Text via `intl.formatMessage({ id: 'section.key' })`, key added to the NESTED
  `src/config/locales/en-US.json`; ids are dotted only because `flattenMessages`
  flattens the tree in `src/index.tsx`.
- [ ] Redux reads use `useAppSelector` (`src/hooks/index.ts:6`, a
  `TypedUseSelectorHook<RootState>` alias) plus a memoized selector from
  `src/redux/selectors`. The finding is an inline lambda picking state apart, not the
  hook name. Handlers are `_`-prefixed.

## Tests

- [ ] Each new or changed test fails without the change. Check it: revert only the source part
  of the diff (keep the test), run it and confirm it goes red for the behaviour it names, then
  restore. For a new guard, delete just the guard. A test that stays green only covers the path
  where the change does nothing: report it as `BUG`. Restore from a copy of the file, never
  `git checkout -- <file>`, which drops uncommitted work.
- [ ] A change with no test carries the issue's `No test-first: <reason>`.

## Report

Group as inline (must fix), outside-diff (should fix), nitpick. Per finding:
`**[BUG|SECURITY|PERF|STYLE|NITPICK]** file:line`, then what is wrong, why it matters
and the fix. Gate on `yarn lint`, `yarn typecheck` and `yarn test:ci`.
