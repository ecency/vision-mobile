---
name: add-query
description: Use when reading server data in the native Ecency app: adding a Hive RPC or Ecency API read to the shared Kotlin core (an API client under shared/src/commonMain/kotlin/com/ecency/core/api), exposing it through a StateModel with loading, paging, refresh and account binding, or fixing a stale, looping or cross-account read, with FakeTransport and TestApp tests.
argument-hint: [query-name]
---

# Add Query

Reads live in the shared core; the UIs only draw a model's state. CLAUDE.md has the layout; README "Data layer" and
"Node failover" have the RPC rules. Writes are the `add-mutation` skill.

## Step 0: find what RN asked for, write the failing test

1. Find the RN call: `git show rn-3.x-final:<path>` of the screen or hook, then the `@ecency/sdk` query options it
   used (`getXQueryOptions`). Native sends the same method and parameters. Check whether the core already has it:
   grep `shared/src/commonMain/kotlin/com/ecency/core/api` for the RPC method name or the private API path.
2. Write the tests first and see them fail on an assertion:
   - the request shape, in `shared/src/commonTest/kotlin/com/ecency/core/api/...`: `FakeTransport.byMethod(mapOf(
     "bridge.x" to "<json>"))` with `clientWith(t)`, then assert `t.sent.single().params` (see `CommunityApiTest`).
     Fixtures are synthetic but shaped like real answers.
   - the model, in `commonTest/.../app/<Feature>ModelTest.kt` with `TestApp(this) { req -> ... }` (answer Hive by
     `req.method`, return `FakeTransport.result(req, json)`) and `app.authBackend.rest = { r -> ... }` for Ecency
     REST (`ReferModelTest`). Drive time with `testScheduler.advanceUntilIdle()`.

## Step 1: the API call

- Hive RPC: a method on the area's client (`api/community/CommunityApi.kt`, `api/HiveApi.kt`, `api/wallet/...`)
  through `client.call(method, params, validate = ...)` on the shared `HiveRpcClient`. Pass `validate` when a
  well-formed answer can still be impossible (a list expected): the node cools down and the next is asked.
- Ecency private API (per account, token sent as `code`): a method on the area's class in `api/ecency/` over
  `EcencyPrivateApi.post(path, username, fields)` (`auth/EcencyTokens.kt`). Public Ecency REST: `EcencyRestApi`.
- Public entry points are `suspend`, annotated `@Throws(EcencyException::class, CancellationException::class)`
  (Swift needs it) and wrapped in `guarded { }` so only `EcencyException` or cancellation escapes.
- Parse with the tolerant readers in `api/model/JsonReading.kt` (`obj()`, `arr()`, `str()`) and
  `api/json/SafeJson`: chain JSON is loosely typed and `json_metadata` is untrusted. A malformed row is skipped
  (`mapNotNull(X::fromJson)`), never a crash.
- Wire a new client once: on `EcencyApi` (Hive) or as a `by lazy` property of `AppEnvironment`
  (`communityApi`, `editorApi`). Short-lived caches use `api/repository/MemoryCache`.

## Step 2: the model

- `StateModel<XState>` (`app/StateModel.kt`) with an immutable state: `isLoading`, `isRefreshing`,
  `isLoadingMore`, `loaded`, `error: AppError?`, and for lists `pages` (count every page, even one of only
  duplicates, so the UI's "near the end" check does not stall).
- Paging ends on a cursor the server repeats, a missing cursor, or a page that adds nothing new (`ReferModel`,
  `FeedPager.isEndReached`): a misbehaving server must not page forever. Drop duplicates by key.
- Refresh keeps the old list until the new one is complete; `refreshAndWait()` style suspend variants exist for
  pull to refresh on iOS.
- Bind to the account: `env.session.awaitLoaded()`, then collect `env.events` for `AppEvent.SessionChanged`,
  cancel the running job, reset the state and reload; check the account again before applying an answer.
- Catch `CancellationException` and rethrow; everything else becomes `AppError.from(e)` in state. Never compare
  catalog text to decide anything.
- Factory on `AppEnvironment`, closed by the screen (`Observed(..., ownsModel: true)` on iOS, the stack entry on
  Android). App-wide models (`env.session`, `env.votes`) are never closed by a screen.

## Step 3: offline answers

`-fakeData` UI tests and screenshots answer Hive from `DemoHiveTransport` (`shared/.../app/DemoHiveTransport.kt`)
and iOS `App/FakeHiveTransport.swift`, Ecency REST from `DemoAuthServices`. Add the new call there, or the screen
shows its error in those runs.

## Traps

- A read that must not reach the node in a test: assert on `transport.sent` / `authBackend.sent`, not on state only.
- Public reads send no account token (assert `body == null` for GETs, as `ReferModelTest` does).
- iOS rows identify by key (`ForEach(..., id: \.key)`), never by hashing the Kotlin data class.
- Live node smoke test, read only, Android host: `ECENCY_LIVE_TEST=1 ./gradlew :shared:testAndroidHostTest --tests '*LiveSmokeTest*'`.

## Checklist

- [ ] Request shape test and model test were red first, green now (PR body: paths, command, failing assertion, green run)
- [ ] Same method and parameters as the SDK call RN used
- [ ] `@Throws` and `guarded`, tolerant parsing, `validate` where an answer can be impossible
- [ ] Paging ends on repeats and empty pages; `pages` counted
- [ ] Account change resets and reloads; late answers for the previous account dropped
- [ ] Demo answers added for fake data runs
- [ ] `:shared:testAndroidHostTest` for the touched classes, then the full suites and arm64 links once
