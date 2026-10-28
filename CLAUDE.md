# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Ecency Mobile is a React Native client for the Hive blockchain available for iOS and Android. The app provides social features including posts, comments, wallet operations, and community interactions.

## Development Commands

### Setup
```bash
yarn                      # Install dependencies (auto-runs patch-gradle.sh and pod install)
yarn start               # Start Metro bundler
yarn ios                 # Run on iOS simulator/device
yarn android             # Run on Android emulator/device
```

### Linting & Testing
```bash
yarn lint                # Run ESLint on src/
yarn lint:fix            # Auto-fix linting issues
yarn format              # Format with Prettier and lint:fix
yarn test                # Run Jest tests in watch mode
yarn test:ci             # Run Jest tests for CI (no watch, forceExit)
```

### Build Scripts
```bash
yarn bundle:ios          # Bundle JS for iOS
yarn bundle:android      # Bundle JS for Android
yarn patch:gradle        # Re-apply React Native 0.79.5 Gradle patch if needed
```

### Other Utilities
```bash
yarn clear               # Clear all caches and reinstall dependencies
bash patch-gradle.sh     # Apply Gradle patch manually (required for RN 0.79.5)
```

## Architecture

### State Management

**Redux + Redux Persist**: Global state management with persistence via AsyncStorage
- Store: `src/redux/store/store.ts`
- Reducers: `src/redux/reducers/` (account, application, posts, cache, wallet, etc.)
- Redux Persist v6 with migrations in `src/utils/migrationHelpers.ts`

**TanStack Query (React Query)**: Server state management with persistence
- Query setup: `src/providers/queries/index.ts`
- Query keys: `src/providers/queries/queryKeys.ts`
- Custom hooks organized in `src/providers/queries/` (postQueries, walletQueries, etc.)
- Selective persistence strategy for feeds, waves, and notifications

### Navigation

Uses React Navigation v6 with nested navigators:
- **Stack Navigator**: `src/navigation/stackNavigator.tsx` - Main navigation container
- **Drawer Navigator**: `src/navigation/drawerNavigator.tsx` - Side menu
- **Bottom Tabs**: `src/navigation/botomTabNavigator.tsx` - Main tabs (Feed, Waves, Profile, etc.)
- Routes defined in `src/constants/routeNames.ts`

### Data Layer

**Hive Blockchain Integration**:
- Primary Hive client: `src/providers/hive/dhive.ts` (uses @hiveio/dhive and @esteemapp/dhive)
- Client auto-configures with fallback servers from `src/constants/options/api.ts`
- Post parsing utilities: `src/utils/postParser.ts`
  - `parsePost(post, currentUserName, isPromoted)` — all 3 args required
- HiveSigner support: `src/providers/hive/hivesignerAPI.ts`
- HiveAuth wrapper: Uses hive-auth-wrapper for keychain integration

**Ecency Backend APIs**:
- API client: `src/providers/ecency/ecency.ts`
- Provides notifications, drafts, market data, point activities, etc.
- Uses converters in `src/providers/ecency/converters/`

**Local Storage**:
- AsyncStorage wrapper: `src/storage/storage.ts` (historically `src/realm/realm.ts` when it used Realm DB)
- Stores user credentials, drafts, settings, and cached data
- User data, auth tokens, and drafts are schema-organized

### SDK Migration

**Status**: Fully migrated to `@ecency/sdk` (v2.x) for all queries and mutations

The app uses the centralized `@ecency/sdk` package for all blockchain and API queries, providing:
- **Consistent patterns**: All queries use TanStack Query with SDK query options
- **Type safety**: Full TypeScript support with SDK types
- **Performance**: Automatic caching, deduplication, and background refetching
- **Maintainability**: Single source of truth shared with Ecency Vision web app

**SDK Usage Patterns:**

For React components, use SDK query options directly:
```typescript
import { useQuery } from '@tanstack/react-query';
import { getPostQueryOptions, getAccountFullQueryOptions } from '@ecency/sdk';

// In component:
const { data: post, isLoading } = useQuery(getPostQueryOptions(author, permlink, observer));
const { data: account } = useQuery(getAccountFullQueryOptions(username));
```

For non-React contexts (Redux actions, utilities), use query client:
```typescript
import { getQueryClient, getAccountsQueryOptions } from '@ecency/sdk';

const queryClient = getQueryClient();
const accounts = await queryClient.fetchQuery(getAccountsQueryOptions([username]));
```

For mutations, use mobile mutation wrappers:
```typescript
import { useTransferMutation } from '../providers/sdk/mutations';

function MyScreen() {
  const transfer = useTransferMutation();
  const handleTransfer = async () => {
    await transfer.mutateAsync({ to, amount, memo });
  };
}
```

**Mobile Platform Adapter:**

The mobile app uses `src/providers/sdk/mobilePlatformAdapter.ts` to bridge the SDK with mobile-specific auth:
- Decrypts posting/active keys from encrypted AsyncStorage using PIN
- Opens HiveSigner WebView for OAuth-based signing
- Triggers HiveAuthBroadcastSheet for keychain app signing
- Shows AuthUpgradeSheet when active key is needed but only posting key is available
- Provides temporary active key storage (60s expiry) after auth upgrade

All mutation wrappers use `useMutationAuth()` from `src/providers/sdk/mutations/common.ts` which provides `{ username, authContext }` with the adapter configured.

**SDK & Query Organization:**
- SDK mutation wrappers: `src/providers/sdk/mutations/` (useTransferMutation, useFollowMutation, etc.)
- SDK auth context: `src/providers/sdk/useAuthContext.ts`
- Platform adapter: `src/providers/sdk/mobilePlatformAdapter.ts`
- Query hooks: `src/providers/queries/` (bookmarkQueries, draftQueries, editorQueries, etc.)
- Query keys: `src/providers/queries/queryKeys.ts`
- SDK config: `src/providers/queries/sdk-config.ts`
- Legacy helpers: `src/providers/hive/dhive.ts` (getDigitPinCode, signImage, key decryption helpers)

**Important Notes:**
- All new queries should use SDK query options
- All new mutations should use SDK mutation hooks with a mobile wrapper in `src/providers/sdk/mutations/`
- Legacy `dhive.ts` file now only contains helper functions (key decryption, PIN handling, HiveAuth fallback)
- Query cache persists to AsyncStorage for offline support

### Deep Links & URI Handling

The app handles custom URL schemes via `src/hooks/useLinkProcessor.tsx`:
- `ecency://login` - Share posting key with third-party apps
- `ecency://auth` - Passwordless authentication with access tokens
- `ecency://signup` - In-app registration with referral support
- `ecency://transfer` - Prepare Hive transfers or Ecency Points
- `hive://...` and `ecency://sign/...` - Standard Hive URI transactions

All flows include callback support and request_id correlation.

### Component Structure

**Screens**: `src/screens/` - Full-page components (e.g., Post, Editor, Profile, Chats)
**Components**: `src/components/` - Reusable UI components
**Containers**: `src/containers/` - Connected components with business logic (e.g., profileContainer, redeemContainer)

### Sheets (Bottom Sheets)

Uses `react-native-actions-sheet` for globally-registered bottom sheets:
- Registry: `src/navigation/sheets.tsx` — all sheets registered with `SheetNames` enum
- Show from anywhere: `SheetManager.show(SheetNames.MY_SHEET, { payload: {...} })`
- Returns value: `const result = await SheetManager.show(...)` — resolves when sheet closes
- Sheets are registered globally but MOUNT on show and UNMOUNT on hide (`SheetProvider`
  renders `!visible ? null : <Sheet/>`), so mount-time state resets are enough and any
  unmount cleanup runs on every close, not rarely
- Sheets render as siblings of `<Application/>` (`src/index.tsx`), so they sit OUTSIDE the
  app's `ErrorBoundary`: a throw from a sheet's render or effect cleanup is uncaught and
  fatal. Be careful with native/Expo shared objects in cleanups. Expo hooks such as
  `useAudioRecorder` release their native object before any cleanup you declare after them

### Styling

- Uses `react-native-extended-stylesheet` (imported as EStyleSheet)
- Global styles: `src/globalStyles.ts`
- Themes: `src/themes/`

### Cryptography & Security

- Key management: `src/utils/crypto.ts` (uses expo-crypto and crypto-js)
- PIN authentication via expo-local-authentication
- Keys encrypted and stored in AsyncStorage per user

## Platform-Specific Notes

### Android
- Requires `google-services.json` in `android/app/` (from Firebase console)
- Package name: `app.esteem.mobile.android`
- Native code: `android/app/src/main/java/app/esteem/mobile/android/`

### iOS
- Uses CocoaPods - run `cd ios && pod install` after dependency changes
- Native modules in `ios/` including Swift files

### React Native 0.79.5 Gradle Patch
The `patch-gradle.sh` script is **required** for builds with this RN version. It modifies `ReactRootProjectPlugin.kt` to fix preBuild task dependencies. The script runs automatically during `yarn install` (postinstall hook).

## Key Patterns

### Post Data Flow
1. Fetch posts via SDK query options (`getPostQueryOptions`, `getDiscussionsQueryOptions`, etc.)
2. TanStack Query automatically caches and manages the data
3. Cache votes and metadata in Redux cache reducer (for optimistic updates)
4. Inject cached data via `useInjectVotesCache()` and `usePostsCachePrimer()` hooks
5. Render body using `@ecency/render-helper` renderPostBody()

### Authentication Flow
Users can authenticate via:
- **Master/Posting Key**: Encrypted and stored locally (AUTH_TYPE 1/2)
- **HiveSigner**: OAuth-like flow with access tokens (AUTH_TYPE 5)
- **HiveAuth**: QR code scanning with keychain apps (AUTH_TYPE 7)

Auth state stored in Redux account reducer and persisted to AsyncStorage.

**Auth Upgrade**: When an active-key operation is needed but user only has posting key, the SDK triggers `AuthUpgradeSheet` via the mobile platform adapter. The user enters their active key, which is stored temporarily for 60 seconds.

**Broadcast Routing**: The `mobilePlatformAdapter` handles all auth routing automatically:
- Key users: direct signing with decrypted keys
- HiveSigner users: WebView hot signing via hive-uri encoded operations
- HiveAuth users: triggers HiveAuthBroadcastSheet for keychain app signing

### Offline Support
- Redux Persist maintains user state across sessions
- TanStack Query persistence provides cached feed/post data
- AsyncStorage stores drafts and user preferences locally

### Debugging
- **Reactotron**: Desktop app for logging and network inspection (config: `reactotron-config.ts`)
  - Android: Run `adb reverse tcp:9090 tcp:9090` then restart app
  - iOS: Connects automatically
- **Sentry**: Error tracking configured for production builds

## Dependencies Management

### Patches
The project uses `patch-package` to maintain custom fixes:
- Patches stored in `patches/` directory
- Applied automatically during `yarn install` via postinstall hook
- Common patches: react-native-navigation-bar-color, react-native-modal-dropdown, etc.

### Node Polyfills
Uses `rn-nodeify` to polyfill Node.js modules (crypto, stream, buffer, etc.) for React Native. Configured in package.json `react-native` and `browser` fields.

## Testing

### Unit Tests
- **Test first:** issues carry a required **Acceptance tests** field (`.github/ISSUE_TEMPLATE/spec.yml`). Write
  those tests before the code, see them fail for the right reason, then make them green. The PR body lists each test path, the command and the assertion that failed before the change, then the green run after it,
  or repeats the issue's `No test-first: <reason>`
- Test framework: Jest 29 with React Native preset
- Config: `jest.config.ts`, Setup: `jest.setup.ts`
- Tests co-located with source: `src/utils/*.test.ts`, `src/redux/reducers/*.test.ts`
- Run all: `yarn test` (watch mode) or `yarn test:ci` (CI, no watch)
- Run single: `node node_modules/jest/bin/jest.js <test-file-path>`

**Test suites (284 tests):**
- Utilities: `conversions`, `number`, `vote`, `wallet`, `postParser`, `deepLinkParser`, `transactionOpsBuilder`, `crypto`, `migrationHelpers`
- Redux reducers: `cacheReducer`, `accountReducer`

**Mocking patterns:**
- Native modules mocked globally in `jest.setup.ts` (react-native, expo-*, CryptoJS, etc.)
- Heavy dependency chains (store → navigation → components) require strategic `jest.mock()` to break circular imports
- Use `jest.unmock('crypto-js')` in crypto.test.ts for real encryption roundtrip tests

### E2E Tests (Maestro)
- Config: `.maestro/config.yaml`
- Flows: `.maestro/01-app-launch.yaml` through `04-feed-navigation.yaml`
- Run locally: `maestro test .maestro/` (requires Android emulator or device)
- CI: `.github/workflows/e2e-android.yml` — triggered by `e2e-test` label or manual dispatch
- Uses `reactivecircus/android-emulator-runner@v2` with API 34

### CI Workflows
- `.github/workflows/test.yml` — lint + typecheck + unit tests (runs on all PRs)
- Build workflows (`.github/workflows/build-android.yml`, `build-ios.yml`) gate on `lint-and-test` job

## Code Style (ESLint)

- Unused function params must be `_`-prefixed (e.g., `(_opts)` not `(opts)`)
- Use `Number.isNaN()` not global `isNaN` (`no-restricted-globals`)
- `require()` in TypeScript needs `// eslint-disable-next-line @typescript-eslint/no-var-requires`
- Max line length: 100 chars (warning, not error)

## TypeScript

- Config: `tsconfig.json` extends `@react-native/typescript-config` (`moduleResolution: bundler`)
- Base URL: `src/` for absolute imports
- `yarn typecheck` — runs `tsc --noEmit` against the committed `tsc-baseline.json`,
  which is now EMPTY: the repo typechecks clean, so any error fails CI
- Never reintroduce baseline entries — fix the types instead
- Many contracts are deliberately `any` from the 2026-08 burn-down (raw Hive posts,
  redux payloads, class-component props); tightening them is welcome, loosening is not

## Pre-commit Hooks

Uses Husky to run:
1. `./sync-env-example.sh` - Syncs environment variables
2. `lint-staged` - Lints and auto-fixes staged files

## Important Notes

- Firebase setup required for Android (FCM notifications)
- Minimum Node.js version: 18
- Uses Expo modules for camera, crypto, local auth, images
- Chat feature uses WebSocket connection (ACTIVITY_WEBSOCKET_URL env var)
- Market data from CoinGecko API (`src/providers/coingecko/`)
- Points system via Ecency backend API
- In-app purchases handled by `react-native-iap`
