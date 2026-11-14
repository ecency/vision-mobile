# Golden fixture generators

Scripts, run by hand, that write the test fixtures and data tables the shared core's tests check against. No build or
test runs them (some read public Hive nodes for chain state, such as `golden/generate.mjs`): their output is committed (`shared/src/commonTest/...`, a few tables in `shared/src/commonMain/...`), and a
test reads only that output.

They run the npm packages the app's behaviour is defined by, so the fixtures stay a second opinion on the Kotlin code:

- `render/`: `@ecency/render-helper` (post bodies, Markdown, URLs, proxied images, speak and video embeds, the
  block inputs). Run these again after a render-helper release (docs/upstream-render-helper-issues.md).
- `golden/`: `@ecency/sdk`, `diff-match-patch`, `speakingurl` (operations and their serialization,
  signing, wallet and resource credit math, feeds, profiles, slugs, edit history diffs). `golden/generate-wax.mjs`
  serializes every transaction those vectors pin again with Hive's own `@hiveio/wax` (hived's protocol code as
  WebAssembly, run here in Node only, never in the apps), so the Kotlin core is checked against the chain's code too.
- `l10n/seed-native-plurals.mjs`: fills missing `native.plurals.*` translations from the RN keys' translations in
  `src/config/locales`.
- `esie/`: the Esie mascot's art, converted to Kotlin path data.

## Running one

Two installs, once each, with the versions the committed fixtures were made with:

```sh
npm ci --prefix tools             # golden/ and l10n/ (tools/package.json and its lockfile)
npm ci --prefix tools/render      # render/ (tools/render/package.json and its lockfile: @ecency/render-helper)

node tools/golden/generate.mjs shared/src/commonTest/kotlin/com/ecency/core/hive/GoldenVectors.kt
node tools/golden/generate-memo.mjs shared/src/commonTest/kotlin/com/ecency/core/hive/MemoGoldenVectors.kt
node tools/golden/generate-wax.mjs shared/src/commonTest/kotlin/com/ecency/core/hive   # after any SDK vector file changes
node tools/render/generate-dom-fixtures.mjs shared/src/commonTest/kotlin/com/ecency/core/render/dom
```

The scripts find their packages next to them (`tools/render/node_modules` for `render/`, `tools/node_modules` for
the rest) wherever they run from; `golden/generate-wallet.mjs`, `golden/generate-platform.mjs` and
`golden/generate-bridge-v2.mjs` (the bridge v2 normalizer, per-node fallback and vote lookups, from `@ecency/sdk`
2.8.0) take another install through `GOLDEN_MODULES` (see their headers).

Each script's header says what it writes and with which arguments. Diff the output against the committed file: a
change is either a fix in the package (update the Kotlin side to match, and say so in the PR) or a regression to file
upstream. `GoldenVectors.kt`'s `sdkSignature` lines change on every run: the SDK signs with extra entropy, so its
signature differs each time; the tests recover the key from it (the byte-exact check is the generator's own RFC 6979
signature).

## What is gone

The generators that ran the React Native app's own source (`src/utils/*.ts`, its screens and hooks) were removed with
the RN app (#3627). Their fixtures stay as they are; to regenerate one, check out the `rn-3.x-final` tag and run it
from the repository root, with the paths under `native/` (`native/tools/...`, `native/shared/...`).
