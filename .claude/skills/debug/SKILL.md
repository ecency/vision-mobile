---
name: debug
description: Diagnose a bug in the Ecency mobile app when a broadcast or login fails, a wallet or transfer amount is wrong, a screen or deep link does not open, a bottom sheet misbehaves, dark mode colors are wrong, an SDK query returns stale data, or a Metro or native build breaks
argument-hint: [issue-description]
---

# Debug Guide

Triage procedure. Layout, commands, architecture and test setup are in `CLAUDE.md`; this file
adds the per-area entry points plus the traps. Verify against the code before acting.

## 1. Auth / broadcast

`authType` is a **string**, not a number (`src/constants/authType.ts`):
`steemConnect`, `hiveAuth`, `masterKey`, `activeKey`, `memoKey`, `postingKey`, `ownerKey`.
`mapAuthTypeToLoginType` (`src/utils/authMapper.ts`) maps them to the SDK login type:

| `currentAccount.local.authType` | login type |
|---|---|
| `'steemConnect'` | `'hivesigner'` |
| `'hiveAuth'` | `'hiveauth'` |
| the key types above | `'key'` |
| anything else | `'key'` plus an `[AuthMapper] Unknown authType` warning |

(CLAUDE.md still describes `AUTH_TYPE` as numbers; the code uses the strings above.)

Routing is `src/providers/sdk/mobilePlatformAdapter.ts`. `getLoginType(username, authority)` can
override the map: a key user with no `postingKey` but an `accessToken` goes to HiveSigner. A
HiveSigner user asking for `active` returns `null`, so the SDK falls through to
`showAuthUpgradeUI`.

Authority per operation: `resolveOperationAuthority` / `resolveTxRequiredAuthority` in
`src/utils/hiveOperationAuthority.ts`. `vote`, `comment`, `comment_options`, `delete_comment`,
`claim_reward_balance` are posting outright. The payload dependent ops are checked before that
set: `custom_json` is posting unless it declares a non-empty `required_auths`; `account_update2`
is posting unless it sets a non-empty `json_metadata` or any of
`owner`/`active`/`posting`/`memo_key`. Everything else is active.

`resolveTxRequiredAuthority` then collapses a whole transaction to a single authority, returning
active when any one operation needs it, so the signer decrypts one key for the batch. That is
fine for a uniform transaction. It is wrong for a mixed one, meaning a batch holding both a
posting-only operation and an active operation. Hive's `verify_authority` checks the required
posting authorities, then calls `clear_approved()` before checking the required active ones, so
the two sets are satisfied independently and one active signature does not cover both. HF28
lifted the old ban on mixing them in a single transaction, so such a batch can now arrive from a
deep link where it previously could not. Mobile signs with one key, so treat a mixed batch as
unsupported by this client, the same as a mixed `custom_json`.

- **Active key gone right after upgrade**: `setTempActiveKey` expires it on a timer while
  `getActiveKey` calls `clearTempActiveKey()` on read, so it is single use.
- **HiveSigner WebView not opening**: `broadcastWithHiveSigner` calls
  `RootNavigation.navigate({ name: ROUTES.MODALS.HIVE_SIGNER, ... })`
  (`src/navigation/rootNavigation.tsx`).
- **HiveAuth not responding**: `broadcastWithHiveAuth` delegates to `handleHiveAuthFallback` in
  `src/providers/hive/hive.ts`. CLAUDE.md still points at `src/providers/hive/dhive.ts`; that file
  is gone and nothing imports `hive/dhive`. The fallback dedupes by
  `` `${name}:${operationName}` ``, so a concurrent call reuses the in-flight promise.
- **Auth upgrade sheet not showing**: `showAuthUpgradeUI` loads `SheetManager` plus `SheetNames`
  via `getSheetDeps()`, a cached lazy `require()` deliberately used instead of `import()`
  (which Metro wraps in an async shim), to dodge a circular import. Check that first.
- **"@ecency.app doesn't have permission to broadcast"**:
  `isMissingEcencyPostingAuthorityError` lowercases the error text then matches the substring
  `permission to broadcast`, or `unauthorized_client` together with an `ecency.app` mention; an
  `ecency.app` mention on its own matches neither branch. A bare `unauthorized_client` is an
  expired token or wrong scope. `shouldPromptPostingAuthority` gates the grant sheet.

## 2. Wallet / transfer

Screens `src/screens/transfer/screen/`; hooks `src/providers/queries/walletQueries/`, which
composes SDK options (`getPortfolioQueryOptions`, `getPointsQueryOptions`,
`get{Hive,Hbd,HivePower}AssetTransactionsQueryOptions`, `getOpenOrdersQueryOptions`,
`getRecurrentTransfersQueryOptions`, `getSavingsWithdrawFromQueryOptions`,
`getConversionRequestsQueryOptions`, `getCollateralizedConversionRequestsQueryOptions`).

- Delegations are `getVestingDelegationsQueryOptions(username, limit)` (`delegateScreen.tsx`,
  `src/screens/assetDetails/children/delegationsModal.tsx`). The SDK also exports
  `getHivePowerDelegatingsQueryOptions`, which mobile does not appear to use, so do not reach for
  it by name.
- **Shows 0 HP**: `vestsToHp(vests, hivePerMVests)` (`src/utils/conversions.ts`) returns `0` when
  either argument is falsy and runs `parseFloat(String(vests))`, so a raw number and
  `"1000000.000000 VESTS"` both work. Zero almost always means `hivePerMVests` was missing.
- **Stale delegations**: invalidate the exact
  `getVestingDelegationsQueryOptions(name, limit).queryKey`; a different `limit` is another key.
- **`[object Object]`**: RPC rejections are often not `Error` instances, so `String(error)`
  collapses them. See `src/components/upvotePopover/container/upvotePopover.tsx`.

## 3. Navigation

`src/navigation/`: `stackNavigator.tsx`, `drawerNavigator.tsx`, `botomTabNavigator.tsx` (spelling
is intentional), `appNavigator.tsx`, plus `rootNavigation.tsx` for non-React navigation.

- **Screen not found**: the route must be in `src/constants/routeNames.ts` *and* registered in one
  of the navigators. `stackNavigator.tsx` holds both `<MainStack.Screen>` and `<RootStack.Screen>`
  entries: the root stack mounts the main stack (`STACK.MAIN`, which renders
  `MainStackNavigator`) and registers routes beside it (`SCREENS.REGISTER`, `LOGIN`, `WELCOME`,
  `SCREENS.ACCOUNT_LIST`, `WEB_BROWSER`, `PINCODE`, `MODALS.POLL_WIZARD`, `MODALS.HIVE_SIGNER`),
  so grepping only for `MainStack` wrongly declares login, pincode, web browser and the HiveSigner
  modal unregistered. Remaining routes are the `<Tab.Screen>` entries in `botomTabNavigator.tsx`
  and `<Drawer.Screen name={ROUTES.SCREENS.FEED}>` in `drawerNavigator.tsx`.
- **Deep link dead**: `src/hooks/useLinkProcessor.tsx` returns `handleLink`, which dispatches to
  `_handleEcencyAuthTransferDeeplink`, `_handleEcencyLoginDeeplink`,
  `_handleEcencyTransferDeeplink`, `_handleHiveUri` (which defers to `_handleHiveUriTransaction`)
  or else `_handleDeepLink`. That last one runs `deepLinkParser` then navigates, falling back to
  `ROUTES.SCREENS.WEB_BROWSER` when nothing parses, so an unrecognised link looks like the in-app
  browser opening for no reason. Parsing is `src/utils/deepLinkParser.ts`, which has a co-located
  test to reproduce against.

## 4. Bottom sheets

Registry `src/navigation/sheets.tsx`: the `SheetNames` enum and the `registerSheet` calls line up
one-to-one.

- **Not opening**: the component must be imported into `sheets.tsx` and registered. It need not
  come from the `src/components/index.tsx` barrel; some registered sheets are imported by direct
  path instead, for example `SignConfirmSheet` from `src/screens/dappBrowser/components/`.
- **Stale data**: sheets unmount on hide (CLAUDE.md), so sheet state resets between shows. What a
  sheet renders is the payload captured when `SheetManager.show` ran, so re-show with fresh data.
- **Falsy result**: a sheet resolves with what it passes to
  `SheetManager.hide(sheetId, { payload: value })` (`src/components/authUpgradeSheet/`), so a
  falsy result does not mean confirmed. It also may not say why: `SignConfirmSheet` routes both
  its Cancel button and its `onClose`, which fires on a backdrop or gesture dismiss, through the
  same `_close(false)`, so `!ok` lumps an explicit reject in with a dismissal. Bail out on falsy;
  resolve a named field when the caller has to tell the two apart:
  `const ok = await SheetManager.show(SheetNames.SIGN_CONFIRM, { payload }); if (!ok) return;`
- A throw from a sheet render or cleanup is fatal: sheets sit outside the ErrorBoundary.

## 5. Theme

`react-native-extended-stylesheet` is built by
`EStyleSheet.build(isDarkTheme ? darkTheme : lightTheme)` inside a `useMemo` keyed on
`[isDarkTheme]` (`src/screens/application/hook/useInitApplication.tsx`), so it reruns when the
theme toggles. Stylesheet values therefore re-resolve; a value read outside a stylesheet can stay
stale. For those reads use `EStyleSheet.value('$theme') === 'darkTheme'`.

| Variable | Light | Dark |
|---|---|---|
| `$primaryBackgroundColor` | `#FFFFFF` | `#1e2835` |
| `$primaryLightBackground` | `#f6f6f6` | `#2e3d51` |
| `$primaryBlack` | `#3c4449` | `#fcfcfc` |
| `$primaryDarkText` | `#788187` | `#fcfcfc` |
| `$iconColor` | `#c1c5c7` | `#788187` |

`$primaryGray`, `$primaryLightGray`, `$primaryRed`, `$primaryGreen` are identical in both themes,
so switching to them fixes nothing. Bad dark mode colors usually mean a literal hex.

## 6. SDK queries

Config `src/providers/queries/sdk-config.ts` (`initSdkConfig`), client
`src/providers/queries/index.ts`.

- **No fetch**: check `enabled`; an undefined username usually disables the query.
- **Stale after a mutation**: the adapter's `invalidateQueries` takes a raw key or `{ queryKey }`
  and warns instead of throwing on failure, so a wrong key looks like success.
- **RPC errors**: the node pool reaches the SDK through `ConfigManager.setHiveNodes(...)`, which
  runs from more than one call site, so confirm which list won before blaming failover. Denied
  nodes are dropped by `withoutBlockedServers` / `isBlockedServer`
  (`src/constants/options/api.ts`), so check the pool too.

## 7. Build

```bash
bash patch-gradle.sh                       # gradle patch, also runs on install
cd android && ./gradlew clean && cd .. && yarn android
cd ios && pod install && cd .. && yarn ios
yarn start --reset-cache                   # Metro cache only
```

`yarn clear` deletes `node_modules` and reinstalls, so never run it in a shared or worktree
checkout. `yarn typecheck` runs `scripts/typecheck.js`, not bare `tsc`.

## Triage order

1. Reproduce, name the screen or component, find it under `src/screens/` or `src/components/`.
   Then, before any fix, write a co-located Jest test that fails the way the report does, through
   the real code path with the reporter's input (`src/utils/` already has suites to copy). Run it with `node node_modules/jest/bin/jest.js <path>` and confirm it fails **for the right reason**: an assertion about the missing behaviour, not an import error, a missing mock or a typo. When the subject does not exist yet, add a stub export (a component returning `null`, a function returning `undefined`) so the run reaches the assertion. If it passes on the unchanged code, that is not a fix: either
   the report does not reproduce on current code or the test misses the reporter's input. Stop,
   recheck the input, steps and assertion, then report that before writing any fix. Keep the test;
   the fix is done when it goes from red to green. A bug Jest cannot reach (native module, device only) gets a Maestro flow or written
   repro steps in the issue, plus the reason there is no test.
2. Reads: SDK query options to query hook to component.
3. Writes: `useMutationAuth()` to `mobilePlatformAdapter` to HiveSigner / HiveAuth / key.
4. User state: `useAppSelector(selectCurrentAccount)`.
