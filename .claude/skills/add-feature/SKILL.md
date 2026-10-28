---
name: add-feature
description: Use when adding a new screen, route, or user-facing feature to the Ecency mobile React Native app, covering navigator registration, route params, i18n strings and styling.
argument-hint: [feature-name]
---

# Add Feature

Ordered procedure for adding a screen. CLAUDE.md covers Redux, TanStack Query, `@ecency/sdk`,
sheets and lint rules in more depth. Easiest to miss: the safe-area root (Step 1) and the params
contract (Step 4). Each one breaks something.

New screens are functional. A few older screens under `src/screens/` are still classes, plus
`application/children/errorBoundary.tsx`, which React requires to be a class. An overlay with no
route of its own, shown with `SheetManager.show`, is a bottom sheet instead:
`src/navigation/sheets.tsx`, see CLAUDE.md.

```text
src/screens/<feature>/
  index.ts                     # local barrel, re-exported from src/screens/index.ts
  screen/<feature>Screen.tsx
  screen/<feature>Styles.ts    # or <feature>.styles.ts
  children/  hooks/            # optional
```

## Step 0: failing test first

Start from the issue's **Acceptance tests** list. Put the screen's logic (formatting, validation, what a button does with the data) in a hook or util and write its co-located `*.test.ts` before the code; a component-level `*.test.tsx` works too (`src/components/listen/listenBar.test.tsx` is one). Run it with `node node_modules/jest/bin/jest.js <path>` and confirm it fails **for the right reason**: an assertion about the missing behaviour, not an import error, a missing mock or a typo. When the subject does not exist yet, add a stub export (a component returning `null`, a function returning `undefined`) so the run reaches the assertion. Navigation and full flows that Jest cannot reach go in a Maestro flow under `.maestro/`. If the issue says `No test-first: <reason>` (pure layout or styling, a dependency upgrade, native build config), skip this and repeat that line in the PR.

## Step 1: screen rooted in SafeAreaView

Use `SafeAreaView` from `react-native-safe-area-context` as the root; that is where screens
generally import it from. `src/screens/dappBrowser/screen/dappBrowser.tsx` still takes it from
`react-native`, which is the pattern this rule exists to replace. Do not pass `edges`: it
**replaces** the defaults rather than extending them, so `edges={['bottom']}` drops the top inset
and the header runs under the status bar. That shipped on the Email digests screen; the fix was
removing `edges={['bottom']}`.

Skeleton for `screen/<feature>Screen.tsx` (a template, not a quote of any one file):

```tsx
import React from 'react';
import { useIntl } from 'react-intl';
import { SafeAreaView } from 'react-native-safe-area-context';

import { BasicHeader } from '../../../components';
import { useAppSelector } from '../../../hooks';
import { selectCurrentAccount } from '../../../redux/selectors';
import styles from './myFeatureStyles';

const MyFeatureScreen = () => {
  const intl = useIntl();
  const currentAccount = useAppSelector(selectCurrentAccount);

  return (
    <SafeAreaView style={styles.container}>
      <BasicHeader title={intl.formatMessage({ id: 'myfeature.title' })} />
      {/* content */}
    </SafeAreaView>
  );
};

export default MyFeatureScreen;
```

`useAppSelector(selectCurrentAccount)` reads the account; `useAuth()` from `src/hooks` when you
only need `{ username, code }`. Reusable UI is exported from the `src/components/index.tsx` barrel
(`BasicHeader`, `MainButton`, `TextInput`, `UserAvatar`, `Icon`).

The styles file default-exports
`EStyleSheet.create({ container: { flex: 1, backgroundColor: '$primaryBackgroundColor' } })`.
Variables are defined in `src/themes/lightTheme.ts` and `darkTheme.ts`: `$primaryBlack` text,
`$primaryDarkGray` secondary text, `$primaryBlue` accent, `$primaryLightBackground` cards,
`$iconColor`, `$primaryRed` destructive. Hex literals do not follow the theme.

## Step 2: both barrels

`src/screens/<feature>/index.ts` does `import MyFeature from './screen/myFeatureScreen';` then
`export { MyFeature }; export default MyFeature;`. Add the import plus the name to the export block
in `src/screens/index.ts`. `stackNavigator.tsx` imports its screens from that barrel.
`src/screens/waves` shows the cost of skipping this: it never reached the barrel, so
`botomTabNavigator.tsx` reaches it by path.

## Step 3: route name

In `src/constants/routeNames.ts`. Entries are template literals over the shared suffix consts. The
object ends `as const`, which is what makes the route names a literal union:

```ts
    MY_FEATURE: `MyFeature${SCREEN_SUFFIX}`,
```

## Step 4: params contract (skip it and typecheck fails)

`src/navigation/types.ts` derives `RouteName` from ROUTES, then asserts every route has an entry:

```ts
export type _MissingRouteContracts = AssertNever<Exclude<RouteName, keyof AppParamList>>;
```

A ROUTES entry with no `AppParamList` entry breaks that assertion: the route name does not satisfy
the constraint `never`. `yarn typecheck` runs against an empty baseline, so this fails CI. Add:

```ts
  [ROUTES.SCREENS.MY_FEATURE]: { username?: string } | undefined;
```

Append `| undefined` only if the screen renders with no params. Leaving it off makes params required
at the call sites, which is what you want for a screen that cannot render empty (`WEB_BROWSER`,
`VOTERS`, `ASSET_DETAILS`, `CHAT_THREAD`, `PROFILE_EDIT`).

## Step 5: register in the navigator

`src/navigation/stackNavigator.tsx` defines `MainStackNavigator` and the root `StackNavigator`.
`MainStackNavigator` registers the drawer as its first screen (`ROUTES.DRAWER.MAIN`), so an
ordinary screen added to it is a sibling of the drawer that pushes over it. The root
`StackNavigator` holds `MainStackNavigator` itself plus pre-auth and full-screen routes (Login,
Register, Welcome, PinCode, WebBrowser). Most new screens go in the main one:

```tsx
<MainStack.Screen name={ROUTES.SCREENS.MY_FEATURE} component={MyFeature} />
```

Put it in the `<MainStack.Group screenOptions={{ animation: 'slide_from_bottom' }}>` block to slide
up, add `options={{ presentation: 'modal' }}` for a true modal. The `as any` casts on existing rows
are legacy prop debt; a new screen needs none.

## Step 6: navigating

`types.ts` declares `ReactNavigation.RootParamList extends AppParamList`, so the untyped hook is
already checked against Step 4. Omitting params for a route that requires them is a compile error,
not a blank screen.

```tsx
const navigation = useNavigation();
navigation.navigate(ROUTES.SCREENS.MY_FEATURE, { username });
```

Reading the params back is not settled house style. Most screens destructure a `route` prop, usually
typed `any`; `useRoute` is rare. Prefer keying off Step 4 instead:

```tsx
import { RouteProp, useRoute } from '@react-navigation/native';
import { AppParamList } from '../../../navigation/types';

const route = useRoute<RouteProp<AppParamList, typeof ROUTES.SCREENS.MY_FEATURE>>();
```

Outside a component (deep links, redux actions) use the equally typed object form,
`RootNavigation.navigate({ name, params })` from `src/navigation/rootNavigation.tsx`.

## Step 7: strings

`src/config/locales/en-US.json` is the catalog you edit; Crowdin owns the translations. It is
**nested**: top-level keys are objects, not dotted ids. `src/utils/flattenMessages.ts` joins the
levels with dots at load, so a nested block is read with a dotted id.

```json
{ "myfeature": { "title": "My Feature", "empty": "Nothing here yet" } }
```

```tsx
intl.formatMessage({ id: 'myfeature.title' });
```

## Step 8: data

Server data uses `@ecency/sdk` query options with TanStack Query; mutations use a wrapper in
`src/providers/sdk/mutations/`. See the SDK Migration section of CLAUDE.md. Keep Redux for auth,
settings, UI state and the optimistic cache reducer. Older reducers (`postsReducer`,
`walletReducer`) still hold server data; do not extend them for a new screen.

## Checklist

- [ ] Test from Step 0 was red before the code and is green now (or the issue says `No test-first`)
- [ ] Root is `SafeAreaView` from `react-native-safe-area-context`, no `edges` override
- [ ] `src/screens/<feature>/index.ts` re-exported from `src/screens/index.ts`
- [ ] Route in `routeNames.ts` **and** a params entry in `AppParamList` (`src/navigation/types.ts`)
- [ ] Registered in `src/navigation/stackNavigator.tsx`
- [ ] Nested strings in `en-US.json` only
- [ ] Theme variables, no hex literals
- [ ] `yarn lint` and `yarn typecheck` clean
