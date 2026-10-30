# Store builds

Native 4.x ships as an update of the RN 3.x store app (README "In-place upgrade from RN 3.x"): the same store
records, ids, signing keys and profiles, built and uploaded by the same GitHub Actions the RN app used.

| | Android | iOS |
|---|---|---|
| App id | `app.esteem.mobile.android` | `app.esteem.mobile.ios` (+ share extension `.eshare`, notification service `.ImageNotifi`) |
| Display name | Ecency | Ecency |
| Store | Google Play, alpha track (as RN) | App Store Connect (TestFlight) |
| Workflow | `.github/workflows/build-android.yml` | `.github/workflows/build-ios.yml` |
| Uploaded to the store | signed AAB (R8) + `mapping.txt` | signed IPA |
| Kept as workflow artifact | `mapping.txt` only | dSYMs only |

## When they run

- Push to `development` that changes `native/`: build, sign, upload. A change to the workflow file alone builds
  nothing (dispatch it by hand to try it). Android goes to the
  alpha track with status completed, iOS to App Store Connect, exactly where the RN workflows sent their builds.
- Manual (Actions, the workflow, "Run workflow"): Android `track` (alpha, internal, none to only build),
  `release_status`, `notes`, `build_number`; iOS `upload`, `launch_check`, `run_shared_tests`, `notes`, `build_number`.
- Before any upload, both start the release build once ("Release launch check"): Android on every run, iOS on
  manual runs (a push skips it to save macOS minutes).
- Never on pull requests (CI minutes). `test.yml` runs the Gradle unit tests and Android lint on pull requests
  that touch `native/` (Linux, a few minutes; other pull requests pass without running them).
  `e2e-android.yml` runs the Android device tests on an emulator on the `e2e-test` label or by hand.

Removed with the RN app: yarn, `pod install`, `patch-gradle.sh`, the JS bundle and source map uploads to Sentry,
the Maestro flows in CI, the separate deploy jobs, the `deploy-pr-build` label (PR builds) and the APK, AAB and IPA
workflow artifacts (they embed `PIN_KEY` and `DEFAULT_PIN`, and anyone signed in to GitHub can download the
artifacts of a public repository; get the binaries from the Play Console or App Store Connect). The former
`native-build-android.yml`, `native-build-ios.yml` and `native-tests.yml` are merged into these workflows.

## Versions and build numbers

- Marketing version: `appVersion` in `gradle/libs.versions.toml` and `MARKETING_VERSION` in
  `iosApp/project.yml` (4.0.0). Tests fail when the apps and the core disagree.
- Android versionCode: RN's rule. Locally `major * 10000 + minor * 100 + patch` (40000; RN 3.5.11 is 30511). In
  CI the build number, which the workflow sets to the Unix time in seconds exactly as the RN workflow did (the RN
  store builds carry those, about 1.79 billion in 2026; Play's maximum is 2,100,000,000, reached in 2036). Gradle
  reads `ECENCY_BUILD_NUMBER` or `ecency.buildNumber` and refuses a value below the version's own code or above
  Play's maximum. `BuildConfig.BUILD_NUMBER` and `RN_VERSION_CODE` expose them and `VersionTest` checks the rule.
  A local 40000 build cannot replace an RN store build on a device (lower versionCode); pass a higher
  `ecency.buildNumber` for that.
- iOS CFBundleVersion: the build number, passed to xcodebuild as `CURRENT_PROJECT_VERSION` (CI: the Unix time,
  as RN; 1 locally). Build numbers are per marketing version, so 4.0.0 starts fresh anyway.
- The `build_number` input overrides it, for example to re-upload with a known number. Each store needs a
  higher number than any build it has seen for the app, RN builds included.

## GitHub secrets

All names except the two hashes and the optional Sentry DSN are the RN workflows' existing secrets, reused as
they were.

### Both workflows

| Secret | What it is |
|---|---|
| `PIN_KEY`, `DEFAULT_PIN` (existing) | RN build secrets, compiled in for the one-time import of 3.x accounts |
| `NATIVE_PIN_KEY_SHA256` (new) | SHA-256 of the `PIN_KEY` value without leading and trailing whitespace (the recipe below) |
| `NATIVE_DEFAULT_PIN_SHA256` (new) | the same for `DEFAULT_PIN` |
| `USER_AGENT` (existing) | how the app names itself to Ecency's APIs; `ECENCY_USER_AGENT`, when set, wins |
| `ACTIVITY_WEBSOCKET_URL` (existing, optional) | the notification websocket used where push is unavailable |
| `ECENCY_BACKEND_API` (existing, optional) | the chat backend (RN `chatApi`); empty means the Ecency host |
| `NATIVE_SENTRY_DSN` (new, optional) | Sentry DSN for crash reporting (README "Crash reporting"); unset, nothing is reported |
| `SENTRY_AUTH_TOKEN` (existing, optional) | Android R8 mapping and iOS dSYM upload to Sentry's `ecency-mobile` project, as RN; skipped when unset ("Crash symbols") |

| Variable (not a secret, optional) | What it is |
|---|---|
| `ECENCY_UPDATE_RELEASES_URL`, `ECENCY_UPDATE_TAG_PREFIX` | where the update prompt looks (README "App shell: update prompt"); the default, RN's repository with plain tags, is right for the in-place update |
| `ECENCY_PLAUSIBLE_HOST` | another Plausible host, or `off` (once analytics lands) |

Not used any more: `NEW_IMAGE_API`, `PLAUSIBLE_API_KEY` (the native app has no use for them yet),
`EXPORT_OPTIONS_PLIST` (the workflow fills `iosApp/ci/ExportOptions.plist` from the profiles),
`APP_STORE_CONNECT_API_KEY_*` and `NATIVE_PROVISIONING_PROFILE` (only the preview workflows used them).

The hashes catch a truncated or wrong secret: release builds fail unless each value matches its hash. Both
builds trim leading and trailing whitespace from the value before they use and hash it (Gradle's `secret()`,
`iosApp/scripts/build-secrets.sh`), so hash the trimmed value:

```sh
printf %s '<value>' | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//' | tr -d '\n' | shasum -a 256 | cut -c1-64
```

The values must equal what the RN app compiled in. RN's workflow pasted the secret into a double-quoted bash
`echo` into `.env` (so `$`, `"`, `\` and backticks were interpreted by bash) and react-native-config then dropped
one pair of surrounding quotes and trailing whitespace; native uses the secret as stored, trimmed. They are the
same when the secret contains none of `$ " ' \` or a backtick and has no outer quotes. Checked by the owner on
10-21 for both `PIN_KEY` and `DEFAULT_PIN` (no values recorded). If either secret ever changes to contain those
characters, store RN's effective value in the native secrets instead, or 3.x users cannot be imported: they then
get the one-time "could not bring over your accounts" notice and, after 3 launches, the RN secrets are deleted
(README "Importing accounts from the RN app"). They are
secrets too, because a 4 digit PIN is trivial to recover from its hash. Compute them on your own machine, not in
a shared shell history. The workflows write the values into `native/local.properties` with `printf`, never into
a command line or Xcode build settings, and delete it at the end of the job.

### Android

| Secret | What it is |
|---|---|
| `UPLOAD_KEYSTORE` (existing) | base64 of the RN upload keystore |
| `UPLOAD_KEYSTORE_PASSPHRASE` (existing) | keystore password, also the key password (as in the RN build) |
| `ALIAS` (existing) | key alias |
| `SERVICE_ACCOUNT_JSON` (existing) | Play Console service account JSON, plain text |
| `GOOGLE_JSON` (existing) | base64 of the RN app's `google-services.json` |

Play App Signing keeps signing what users install with the RN app's app signing key; the upload key only
authenticates the upload. Users therefore get the native build as a normal update.

### iOS

| Secret | What it is |
|---|---|
| `CERTIFICATES_P12`, `CERTIFICATE_PASSWORD` (existing) | the Apple Distribution certificate with its private key |
| `PROVISIONING_PROFILE` (existing) | App Store profile for `app.esteem.mobile.ios` (RN's "ecency-dist") |
| `EXTEN_PROVISIONING_PROFILE` (existing) | profile for `app.esteem.mobile.ios.eshare` (RN's "extension-dist"); required, the app embeds the share extension |
| `NOTIFI_PROVISIONING_PROFILE` (existing) | profile for `app.esteem.mobile.ios.ImageNotifi` (RN's "notifi-dist"); required, the app embeds the notification service |
| `WIDGET_PROVISIONING_PROFILE` (new) | App Store profile for `app.esteem.mobile.ios.widgets`, the widget extension (README "Widgets"); required, the app embeds it. See "Widget extension" below |
| `APPLE_ID`, `APPLE_APP_PASS` (existing) | the RN upload: `xcrun altool --upload-app -u -p` with an app-specific password |
| `GOOGLE_PLIST` (existing) | base64 of the RN app's `GoogleService-Info.plist`; the workflow sets `ECENCY_FIREBASE=true` |

The workflow checks that each profile is for its bundle id and team and not expired (a warning 7 days before),
installs them, and passes their names as `ECENCY_APP_PROFILE`, `ECENCY_SHARE_PROFILE`, `ECENCY_NSE_PROFILE` and
`ECENCY_WIDGET_PROFILE`;
each target's `PROVISIONING_PROFILE_SPECIFIER` in `iosApp/project.yml` names its own. The signing identity is the
certificate's SHA-1, the keychain password is random per run, and the keychain and profiles are removed at the end.

The RN App IDs carry more capabilities (associated domains, App Groups, push) than the native app uses today;
an app may use fewer entitlements than its profile allows, so the RN profiles sign it as they are.

### Widget extension (owner action)

The widgets are the one extension RN did not have, so their App ID and profile are new (everything else keeps RN's):

1. Apple Developer > Identifiers: register the App ID `app.esteem.mobile.ios.widgets` (explicit, same team as the
   app) with the App Groups capability, and assign it the existing group `group.com.ecency.eshare`. No other
   capability (no push, no associated domains, no Keychain sharing).
2. Profiles: create an App Store distribution profile for it with the same Apple Distribution certificate as
   `CERTIFICATES_P12`, download it and store it base64 encoded as the repository secret
   `WIDGET_PROVISIONING_PROFILE` (`base64 -i Ecency_Widgets.mobileprovision | pbcopy`).
3. Nothing changes in App Store Connect: the extension ships inside the app.

Until the secret exists the iOS release workflow stops at the profile step with "Secret for WIDGET_PROFILE is not
set". Local and simulator builds need nothing (ad hoc signing).

## Owner checklist

Not needed any more (they were for the separate preview app): a new Play app or App Store record, new App IDs or
profiles, a new Firebase app, new in-app purchase products (the RN products and their ids stay; ePoints already
verifies purchases for these package ids), and any `com.ecency.mobile` entry in ecency.com's `assetlinks.json`
or `apple-app-site-association`.

Needed:
1. Add `NATIVE_PIN_KEY_SHA256` and `NATIVE_DEFAULT_PIN_SHA256` if they are not there yet (repository or
   organization secrets), and optionally `NATIVE_SENTRY_DSN`.
2. Check that the RN secrets above still exist and are current where the workflows can read them, in particular
   `UPLOAD_KEYSTORE`/`ALIAS` (the RN upload key, so Play App Signing accepts the upload), `GOOGLE_JSON`,
   `GOOGLE_PLIST`, and the three RN iOS profiles (same team and Apple Distribution certificate as `CERTIFICATES_P12`),
   plus the new `WIDGET_PROVISIONING_PROFILE` ("Widget extension" above).
3. Make sure no RN build is uploaded after the first native one (RN's workflows are now the native ones; a local
   RN build with a later timestamp would be above native).
4. Before the first public release: Play Console app content forms that change with the update (data safety,
   foreground service declarations for Listen media playback (`FOREGROUND_SERVICE_MEDIA_PLAYBACK`) and background
   dictation (`FOREGROUND_SERVICE_MICROPHONE`), README "Background media") and App Store privacy labels.
5. iOS universal links use the RN App ID's associated domains (ecency.com, alpha.ecency.com); the profile in
   `PROVISIONING_PROFILE` must include that capability (RN's does).

## First deploy

Before merging the native PR into `development` (the merge push itself builds and uploads):
1. Add `NATIVE_PIN_KEY_SHA256` and `NATIVE_DEFAULT_PIN_SHA256` as **repository** secrets (Settings, Secrets and
   variables, Actions, "Repository secrets"), not environment secrets: the jobs declare no environment, so they
   would not see them.
2. Run both build workflows by hand on the PR branch with uploading off. The RN workflow files on `development`
   have no manual trigger, so the button may not show in the Actions tab; dispatch from the command line, which
   runs the branch's version of the file:
   ```sh
   gh workflow run build-android.yml -R ecency/ecency-mobile --ref <branch> -f track=none
   gh workflow run build-ios.yml -R ecency/ecency-mobile --ref <branch> -f upload=false
   gh run list -R ecency/ecency-mobile --workflow build-android.yml --branch <branch>   # and build-ios.yml
   gh run watch -R ecency/ecency-mobile <run id>
   ```
   If GitHub answers 422 about the workflow not having a `workflow_dispatch` trigger, merge only the two workflow
   files (`build-android.yml`, `build-ios.yml`) into `development` first. That builds nothing: their push trigger
   only reacts to changes under `native/`. Then dispatch again.
3. These runs rehearse everything except the store upload: unit tests, the secrets and their hashes, signing with
   the RN keys and profiles (`jarsigner -verify` on the AAB, the IPA export), and they upload only the R8 mapping
   and dSYM artifacts (plus dSYMs to Sentry when `SENTRY_AUTH_TOKEN` is set). Fix any missing secret (owner
   checklist above) and run them again.
4. Merge. The push to `development` then builds again and uploads to the Play alpha track and App Store Connect.

## Caches and artifacts

`PIN_KEY` and `DEFAULT_PIN` are compiled into every release binary by design, exactly as the RN app compiles them
from `.env`: the app needs them on the device to import 3.x accounts. Anyone holding the AAB or IPA can extract
them. They only have to stay out of this public repository, out of logs and out of anything downloadable from it.

- Artifacts: only `mapping.txt` (Android) and the dSYMs (iOS), which hold no secret values.
- Caches: `actions/cache` entries can be restored by workflows of other branches, including pull requests from
  forks. They hold only Gradle dependencies, the Gradle wrapper and `~/.konan`, and exclude
  `~/.gradle/caches/build-cache-1`. The release Gradle build runs with `--no-build-cache`, and every task of a
  non-debuggable Android variant is marked not cacheable in `androidApp/build.gradle.kts`. On iOS the values live
  only in `native/local.properties` and the generated `iosApp/Generated/BuildSecrets.generated.swift`; neither
  is cached and both are deleted at the end of the job.
- Unit tests run before the secrets are written, so test builds in CI only ever see the dummy values.
- Signing material (keystore, .p12, keychain, profiles) and the Firebase files are written to the runner's temp
  directory, keychain or untracked paths and deleted at the end of the job, even when it fails.

## Crash symbols

Release stack traces are unreadable without symbols; both workflows upload them to Sentry's `ecency-mobile`
project with the existing `SENTRY_AUTH_TOKEN` (scopes `project:write` and `org:read`, as RN's dSYM upload), never blocking the
release (retries, then a warning). Each upload is a job of its own on Linux after the build job (`sentry-mapping`,
`sentry-dsyms`): it downloads only the symbol artifact and sentry-cli 2.58.6 from its GitHub release (checked
by SHA-256), and sees neither the build secrets nor the signing or store credentials. A build whose job failed
uploads no symbols, so a crash the launch check caught reaches Sentry obfuscated (retrace it with the mapping
artifact). The symbol jobs are never cancelled by a newer push; when one failed (a warning on the run), re-run
it from the run page.

- Android: each build gets a fresh mapping id (`ECENCY_PROGUARD_UUID`, the "Mapping id" step) that Gradle compiles
  into `BuildConfig.PROGUARD_UUID` (a malformed one fails the build) and the app gives Sentry as `proguardUuid`;
  `sentry-cli upload-proguard --uuid` uploads `mapping.txt` under it. The mapping also goes to Play and stays a
  workflow artifact for 30 days.
- iOS: `sentry-cli debug-files upload` of the archive's dSYMs (every image in the archive), matched by
  their image ids. No source context (RN's `--include-sources` needs the sources on the uploading machine).
- Local builds have no mapping id; their events (if any) stay obfuscated.

## Local release builds

```sh
cd native
# Android: unsigned unless the ecency.upload* keys (or ECENCY_UPLOAD_* env) are set; all four or none.
./gradlew :androidApp:bundleRelease
# iOS: unsigned archive, needs the ecency.pinKey/defaultPin keys and their hashes in local.properties
cd iosApp && xcodegen generate
xcodebuild archive -project EcencySpike.xcodeproj -scheme EcencyApp -configuration Release \
  -destination generic/platform=iOS -archivePath /tmp/EcencyApp.xcarchive CODE_SIGNING_ALLOWED=NO -quiet
```

### Release launch check

Unit and device tests run debug builds, and R8 (Android) and the optimised Release configuration only run on
release builds, so a release-only crash passes every test. 4.0 had one: Glance pulled WorkManager 2.7.1, whose
reflective Room lookup R8 broke, and the minified app crashed at launch. `scripts/release-launch-check.sh` installs
a release build, starts it from cold and waits (exit 1 failed, 2 could not run). Android fails on a Java or native
crash, an ANR (a key event halfway makes a frozen main thread show as one), a dead process or the app no longer in
front; iOS on a crash report or a dead process.

- CI, Android: every run. bundletool turns the AAB that is about to ship into a universal APK signed with a
  throwaway key, an emulator (API 34, Google APIs) runs the check, and a failure stops the job before the Play
  upload. The mapping artifact is kept first, so the obfuscated stack trace in the log can be retraced.
- CI, iOS: manual runs (`launch_check`, default on). The Release configuration built for the simulator, checked on
  an iPhone simulator before the App Store Connect upload. A push skips it (macOS minutes).
- The checked build is the real one, network on: a CI launch reports to Sentry like a user's (one session per
  build, `sdk_gphone64_x86_64` or a simulator device; an inbound filter on those models keeps release health clean)
  and a crash in the check shows up as a production issue. The launch opens no post, so Plausible gets nothing.
- Locally, after a dependency or keep-rule change:

```sh
cd native
# Android: a release with throwaway PIN values (ECENCY_PIN_KEY, ECENCY_DEFAULT_PIN and their _SHA256), then
java -jar bundletool.jar build-apks --mode=universal \
  --bundle=androidApp/build/outputs/bundle/release/androidApp-release.aab --output=/tmp/app.apks \
  --ks=$HOME/.android/debug.keystore --ks-pass=pass:android --ks-key-alias=androiddebugkey --key-pass=pass:android
unzip -o /tmp/app.apks universal.apk -d /tmp
scripts/release-launch-check.sh android /tmp/universal.apk
# iOS: xcodebuild build -configuration Release -destination 'generic/platform=iOS Simulator' (signing as
# scripts/ios-sim.sh), then
scripts/release-launch-check.sh ios <DerivedData>/Build/Products/Release-iphonesimulator/EcencyApp.app
```

Signed with the debug key, the APK replaces a local debug build in place.
A build signed with another key than the installed app (debug, throwaway, or the RN store app) cannot replace
it; uninstalling deletes that app's data, so do it only on a device whose data you do not need.
