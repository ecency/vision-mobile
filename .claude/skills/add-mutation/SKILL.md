---
name: add-mutation
description: Use when adding or changing a write in the native Ecency app: a Hive broadcast (vote, comment, custom_json, transfer, delegation, account_update2, community or engine operation) built in the shared Kotlin core and sent through SigningModel, or an Ecency private API write (bookmarks, drafts, favourites, points), including operation builders, authority, retry safety, golden vectors and tests.
argument-hint: [operation-name]
---

# Add Mutation

Every write goes through the shared core; the UIs call a model action and draw its state. CLAUDE.md "Broadcasts"
and README "Data layer", "Node failover", "Authentication core" and "Wallet" hold the rules. Read them before
touching signing.

## Step 0: RN source and failing tests

1. Find the RN mutation and the `@ecency/sdk` builder it used: `git show rn-3.x-final:src/providers/sdk/mutations/...`
   and the screen that calls it. Native builds the same operation JSON.
2. Write the tests first and see them fail on an assertion (not on a missing class):
   - the operation JSON: a builder test next to the existing ones (`commonTest/.../api/community/CommunityOpsTest.kt`,
     `commonTest/.../api/wallet/...`);
   - a new operation TYPE (new serialization in `hive/`): golden vectors from the SDK. Run `npm install --prefix tools`
     once, then the generator for the area (`tools/golden/generate.mjs`, `generate-wallet.mjs`, ...; each header
     says its output), e.g. `node tools/golden/generate.mjs shared/src/commonTest/kotlin/com/ecency/core/hive/GoldenVectors.kt`.
     Vectors use throwaway keys only.
   - the model action: `TestApp(this) { req -> TestApp.chain(req) ?: ... }` answers the chain state a broadcast
     needs; `app.signIn()` stores a posting-key account; assert `app.broadcasts()` (the operations sent) and the
     state after success, failure and retry (`SocialModelsTest`, `WalletActionsTest`, `SigningModelTest`).

## Step 1: build the operations (`shared/.../core/api/broadcast/`)

- Add a builder to the area's object (`PostActionOps`, `CommunityOps`, `WalletOps`, `NotificationOps`,
  `Comments`) returning condenser JSON (`JsonArray`) as the SDK writes it. Operation types live in `hive/`
  (`Operation.kt`, `WalletOperations.kt`, `GovernanceOperations.kt`).
- Amounts are chain text, POSIX by construction (`Asset`, `Amounts`, `Num`): never format them with
  `LocaleFormat`. Typed input goes through `Amounts.typedText` first. `ChainPathsStayPosixTest` scans these paths.
- Profile metadata writes go through `ProfileWrites` (fresh read, `ProfileMetadataGuard`, merge, `account_update2`
  with the posting key); never write `posting_json_metadata` from another path.

## Step 2: send it from a model

- Social and content writes: `env.signing.broadcast(user, ops)` (authority from `OperationAuthority.required`),
  as `PostActionsModel.broadcast`. Check the account first (`requireCurrent`) and drop the result if the session
  moved on while signing.
- Anything that is not repeat safe (`RepeatSafeOps`: transfers, delegations, power ups, claims, active-auth
  custom_json) needs an action id: create it ONCE when the user confirms (`env.idempotencyKey()`) and pass it to
  `env.signing.broadcast(user, ops, authority, actionId)`. A key account's broadcast without one is refused. Wallet
  sends use `env.walletSends.send(user, ops, authority, actionId) { final -> ... }` (`AssetDetailsModel.send`),
  which follows an undecided send until the chain decides.
- Retries resend the SAME action (same id, same operations, same reply draft). Never rebuild operations or a
  reply draft to retry: a new permlink or a new transaction can post or pay twice.
- Errors: catch `CancellationException` and rethrow; map the rest with `AppError.fromBroadcast(e)`.
  `ErrorKind.CANCELLED` (the user closed a signing prompt) shows nothing; out of RC goes to
  `env.offerRcIfShort(e)` (RN's offer), not an alert. `BroadcastException.Network(undecided = true)` means "check
  your wallet", never an automatic new transaction.
- After success: invalidate and emit what changed (`env.api.posts.invalidate(key)`,
  `env.emit(AppEvent.PostChanged(key))`), toast through `env.ui.toast(...)`, and record points activity where RN's
  mutation did (`env.pointActivities.recordBroadcast(user, activity, signed)`).
- HiveSigner and HiveAuth accounts sign elsewhere (`SigningRouter`); the active key upgrade prompt is shown by
  `SigningModel` and drawn by the platform's auth overlay (iOS `Screens/AuthOverlay.swift`, Android `ui/AuthUi.kt`).
  Nothing to wire per operation, but test a posting-only account hitting an active operation if yours needs active.

## Ecency private API writes

- A method on the area class in `api/ecency/` (`EcencyAccountApi.addBookmark`, `EditorApi.addDraft`) over
  `EcencyPrivateApi.post(path, username, fields)`, which sends the account token as `code` and times out at
  `TIMEOUT_MILLIS`. A write that timed out may have reached the server: treat it as a lost answer, not a failure
  to redo blindly.
- Tests answer it with `app.authBackend.privateApi = { request -> HttpResult(200, "...") }` (or `rest`) and
  assert `app.authBackend.sent`.
- Paid calls (AI, points spends) follow README "Paid AI features: the money rules" (`app/PaidCalls.kt`).

## Checklist

- [ ] Builder JSON equals the SDK's; golden vectors for any new operation type
- [ ] Tests were red first: operations sent, success state, failure, retry resends the same transaction
- [ ] Action id for anything not in `RepeatSafeOps`, made once per user confirmation
- [ ] Account checked before and after signing; cancellation rethrown; `AppError.fromBroadcast`; RC offer
- [ ] Chain text POSIX; no display formatting in operation builders
- [ ] Caches invalidated and events emitted so every screen shows the change
- [ ] Touched test classes green; full suites and arm64 links once at the end
