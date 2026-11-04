# Golden fixture generators

Offline scripts that write the test fixtures and data tables the shared core's tests check against. No build or test
runs them: their output is committed (`shared/src/commonTest/...`, a few tables in `shared/src/commonMain/...`), and a
test reads only that output.

They run the npm packages the app's behaviour is defined by, so the fixtures stay a second opinion on the Kotlin code:

- `render/`: `@ecency/render-helper` (post bodies, Markdown, URLs, proxied images, speak and video embeds, the
  block inputs). Run these again after a render-helper release (docs/upstream-render-helper-issues.md).
- `golden/`: `@ecency/sdk`, `@hiveio/dhive`, `diff-match-patch`, `speakingurl` (operations and their serialization,
  signing, wallet and resource credit math, feeds, profiles, slugs, edit history diffs).
- `l10n/seed-native-plurals.mjs`: fills missing `native.plurals.*` translations from the RN keys' translations in
  `src/config/locales`.
- `esie/`: the Esie mascot's art, converted to Kotlin path data.

## Running one

```sh
npm install --prefix tools   # once: the versions in tools/package.json, which the committed fixtures were made with
node tools/golden/generate.mjs shared/src/commonTest/kotlin/com/ecency/core/hive/GoldenVectors.kt
```

The scripts find their packages in `tools/node_modules` wherever they run from; `golden/generate-wallet.mjs` and
`golden/generate-platform.mjs` take another install through `GOLDEN_MODULES` (see their headers).

Each script's header says what it writes and with which arguments. Diff the output against the committed file: a
change is either a fix in the package (update the Kotlin side to match, and say so in the PR) or a regression to file
upstream.

## What is gone

The generators that ran the React Native app's own source (`src/utils/*.ts`, its screens and hooks) were removed with
the RN app (#3627). Their fixtures stay as they are; to regenerate one, check out the `rn-3.x-final` tag, where they
still run from the repository root.
