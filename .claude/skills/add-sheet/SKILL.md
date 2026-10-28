---
name: add-sheet
description: Use when adding, registering, or debugging a bottom sheet (action sheet) in the mobile app, including when a sheet's cancel or dismissal reaches the caller as the wrong result.
argument-hint: [sheet-name]
---

# Add Sheet

Registry, show call and mount lifecycle: CLAUDE.md "Sheets (Bottom Sheets)". This file adds
the procedure plus the result convention new sheets here should follow.

## Resolve with an object, gate on a named field

`react-native-actions-sheet` 0.9.7, `node_modules/react-native-actions-sheet/dist/src/index.js:408`:

```js
actionSheetEventManager.publish("onclose_".concat(sheetId), data || payloadRef.current || data, currentContext);
```

`data` is what you passed to `SheetManager.hide(id, { payload: data })`. `payloadRef.current` is
NOT the show payload: it tracks `<ActionSheet>`'s own `payload` prop (`payload = _a.payload` at
index.js:53, `useRef(payload)` at 87, `payloadRef.current = payload` at 139). The
`SheetManager.show` payload goes somewhere else entirely, to the registered component as a prop
from the provider (`<Sheet sheetId={id} payload={payload}/>`, `dist/src/provider.js:160`).

Sheets in `src/` do not forward that prop down into `<ActionSheet>`: `grep -rn "payload=" src/`
returns zero hits. So `payloadRef.current` is `undefined` for the sheets here.
`data || payloadRef.current || data` collapses to `data`, so a falsy return does reach the caller
intact today. Same expression on `onBeforeClose` (line 385) and `onClose` (line 401).

Still resolve with an object and gate on a named field. Two reasons:

- A backdrop tap, swipe down or hardware back closes with `data === undefined`, so `show()`
  resolves `undefined`. Truthiness cannot separate that dismissal from a sheet that deliberately
  answered `false`, `0` or `''`. A named field can.
- The substitution is one prop away from going live. Adding `payload={payload}` to an
  `<ActionSheet>` would silently turn every falsy cancel in that sheet into the truthy show
  payload, with no type error and no crash.

Copy `modNotesSheet`, `communityRoleEditSheet`, `walletHistoryFiltersSheet` or
`newsletterDigestSheet`. All four resolve `{ cancelled: true }` on cancel.
`searchFiltersSheet` is apply-only, with no cancel control, so it is not a model here.

`src/components/authUpgradeSheet/authUpgradeSheet.tsx` is one counter-example: it cancels with
`_close(false)` while `src/providers/sdk/mobilePlatformAdapter.ts:317` gates on
`if (!result) return false;`. That reads correctly right now, since cancel and dismissal are both
falsy there and both mean the same thing, but it is one of the sheets that breaks first if anyone
gives it a `payload` prop.

Comments across `src/` (in `sheets.tsx`, in several sheet components, in several screens) justify
this convention by claiming a dismissal resolves the payload object. The convention is right; that
reason is not.

## Step 0: failing test first

Before the sheet, write a test for its result contract from the issue's **Acceptance tests**: which object it resolves with for each action and that dismissal reaches the caller as the cancel case, not as a falsy value read as a choice. Test the caller's gate on the named field too. Run it with `node node_modules/jest/bin/jest.js <path>` and confirm it fails **for the right reason**: an assertion about the missing behaviour, not an import error, a missing mock or a typo. When the subject does not exist yet, add a stub export (a component returning `null`, a function returning `undefined`) so the run reaches the assertion. If the issue says `No test-first: <reason>` (pure layout or styling, a dependency upgrade, native build config), skip this and repeat that line in the PR.

## Step 1: Component

`src/components/<sheetName>/<sheetName>.tsx` is the usual path for a component typed with
`SheetProps<'...'>`. Other sheets use the enum form `SheetProps<SheetNames.X>`, which is equally
accepted. Trimmed from `src/components/modNotesSheet/modNotesSheet.tsx`:

```typescript
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useIntl } from 'react-intl';
import ActionSheet, { SheetManager, SheetProps } from 'react-native-actions-sheet';
import EStyleSheet from 'react-native-extended-stylesheet';
import { MainButton } from '../mainButton';

// Matches the SheetNames value. Sheets keep this so `hide` has an id even when the
// sheet is rendered outside the registry.
const FALLBACK_SHEET_ID = 'my_sheet';

/** `{ value }` on confirm, `{ cancelled: true }` on cancel. A backdrop, swipe or back
 * dismissal resolves `undefined`, so callers gate on a string `value`, never on
 * truthiness. */
export interface MySheetResult {
  value?: string;
  cancelled?: boolean;
}

const MySheet: React.FC<SheetProps<'my_sheet'>> = ({ sheetId, payload }) => {
  const intl = useIntl();
  const [value, setValue] = useState('');
  const closedRef = useRef(false);

  const _reset = useCallback(() => {
    closedRef.current = false;
    setValue('');
  }, []);

  // onBeforeShow fires the same reset on every fresh presentation, which a registered
  // sheet also gets from its fresh mount. This effect covers the one case both miss, a
  // payload swap while the sheet is already open, because use-sheet-manager.js drops the
  // re-show with `if (visible) return;` before onBeforeShow can run. Do not delete it as
  // redundant.
  useEffect(() => {
    _reset();
  }, [payload, _reset]);

  // closedRef stops a double tap firing two hides, which would resolve twice.
  const _close = (result: MySheetResult) => {
    if (closedRef.current) return;
    closedRef.current = true;
    SheetManager.hide(sheetId || FALLBACK_SHEET_ID, { payload: result });
  };

  return (
    <ActionSheet id={sheetId || FALLBACK_SHEET_ID} gestureEnabled closeOnTouchBackdrop
      onBeforeShow={_reset} containerStyle={styles.sheetContainer}>
      <MainButton text={intl.formatMessage({ id: 'my_sheet.confirm' })}
        onPress={() => _close({ value: value.trim() })} />
      <MainButton text={intl.formatMessage({ id: 'my_sheet.cancel' })}
        onPress={() => _close({ cancelled: true })} />
    </ActionSheet>
  );
};

const styles = EStyleSheet.create({
  sheetContainer: { paddingHorizontal: 0, backgroundColor: '$primaryBackgroundColor' },
});

export default MySheet;
```

Colors come from EStyleSheet theme variables in `src/themes/` (`$primaryBackgroundColor`,
`$primaryBlack`, `$primaryDarkGray`, `$iconColor`) rather than a hex literal. For a color a prop
needs as a plain string rather than a style, resolve it with `EStyleSheet.value('$primaryDarkGray')`, as
`modNotesSheet` does for `placeholderTextColor`.

## Step 2: Folder index

`src/components/<sheetName>/index.ts`. Sheet folders re-export their sheet with the first line;
those that also publish a result type add the second:

```typescript
export { default as MySheet } from './<sheetName>';
export type { MySheetResult } from './<sheetName>';
```

A sheet that `sheets.tsx` imports by path can skip this file entirely. `walletHistoryFiltersSheet`
has no `index.ts`.

## Step 3: Components barrel

`src/components/index.tsx` is an import list plus one `export { ... }` block at line 164. Add
`import { MySheet } from './<sheetName>';` plus a `MySheet,` entry inside that block. There is no
`export ... from` line to add. A sheet nothing else imports can skip this step and be imported
in `sheets.tsx` by path, as some registrations are.

## Step 4: Register in `src/navigation/sheets.tsx`

Add the `SheetNames` member (`MY_SHEET = 'my_sheet',`), the
`registerSheet(SheetNames.MY_SHEET, MySheet);` call, then extend `Sheets`. **The key must be a
string literal, not `[SheetNames.MY_SHEET]`**: string enum member types are nominal, so with a
computed key `keyof Sheets` carries the enum member rather than the string literal and
`SheetProps<'my_sheet'>` stops resolving.

```typescript
declare module 'react-native-actions-sheet' {
  interface Sheets {
    my_sheet: SheetDefinition<{
      payload: { someParam: string };
      // `{ value }` on confirm, `{ cancelled: true }` on cancel, `undefined` on a
      // backdrop, swipe or back dismissal. Gate on `value`, not on truthiness.
      returnValue: MySheetResult | undefined;
    }>;
  }
}
```

Skipping this fails `yarn typecheck`: the `everySheetHasDefinition` assertion at the bottom of
the file names any `SheetNames` member missing from the augmentation.

## Step 5: Show it and read the result

```typescript
const result = await SheetManager.show(SheetNames.MY_SHEET, { payload: { someParam: 'x' } });

// Only a confirmation carries a string `value`. Cancel yields { cancelled: true }; a
// backdrop, swipe or back dismissal yields undefined. Both are rejected here.
const confirmed = typeof result?.value === 'string' ? result.value : '';
if (!confirmed) return;
```

Real callers: `src/components/postOptionsModal/container/postOptionsModal.tsx:742`
(`typeof result?.notes === 'string'`) and
`src/screens/assetDetails/screen/assetDetailsScreen.tsx:265` (`!Array.isArray(result?.operations)`).

## Step 6: i18n strings

Edit `src/config/locales/en-US.json` only; Crowdin owns the other locales. The file is nested
objects, not dotted keys, while `formatMessage` ids stay dotted:

```json
  "my_sheet": { "title": "Sheet Title", "confirm": "Confirm", "cancel": "Cancel" },
```

## Lifecycle

- **Sheets mount on a fresh show and unmount on hide**: the library renders the registered
  component only while that sheet is visible. `useState` initials are fresh on that open, so
  nothing stale needs clearing on mount; unmount cleanups run on close. The exception is a
  `show()` against a sheet that is already open: the stored payload is replaced with no remount,
  so reset on the `payload` prop as well, as Step 1 does. The CLAUDE.md bullet states the mount
  rule without that exception.
- **Sheets render outside the ErrorBoundary**: `SheetProvider` returns `<>{children}{sheets}</>`,
  so sheets are siblings of `<Application/>` while the boundary sits inside it
  (`src/screens/application/index.tsx:17`). A throw in a sheet render or effect cleanup is fatal.
  Be careful with native or Expo shared objects in cleanups.
- **The payload is captured at show time**: it is stored in state at that show, so a callback
  passed inside a payload keeps the closure it had then. Route anything that changes through a
  ref, as `src/components/quickPostModal/quickPostModalContent.tsx:565-575` does.
