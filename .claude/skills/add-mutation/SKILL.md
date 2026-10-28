---
name: add-mutation
description: Use when adding, wrapping, or calling an @ecency/sdk mutation hook in the mobile app (Hive broadcasts like transfer, follow, vote, delegate, community, engine token, or points)
argument-hint: [operation-name]
---

# Add Mutation

Wrap an `@ecency/sdk` mutation hook in `src/providers/sdk/mutations/`. CLAUDE.md
("SDK Migration") covers the adapter; this file is the procedure plus the Hive authority
rules a wrapper must respect.

## 0. Failing test first

A wrapper is thin by rule, so it rarely needs a test of its own. The behaviour lives elsewhere: the operation payload in `@ecency/sdk` (write that spec first in vision-web, `add-sdk-mutation` skill) and what the caller does with the result (toast, navigation, cache update) at the call site. For call-site logic, write the co-located test from the issue's **Acceptance tests** before wiring it. Run it with `node node_modules/jest/bin/jest.js <path>` and confirm it fails **for the right reason**: an assertion about the missing behaviour, not an import error, a missing mock or a typo. When the subject does not exist yet, add a stub export (a component returning `null`, a function returning `undefined`) so the run reaches the assertion. If the issue says `No test-first: <reason>` (pure layout or styling, a dependency upgrade, native build config), skip this and repeat that line in the PR.

## 1. Create the wrapper

`src/providers/sdk/mutations/use<Operation>Mutation.ts`. Most wrappers there are exactly
this shape, so copy it verbatim:

```typescript
import { useTransfer } from '@ecency/sdk';
import { useMutationAuth } from './common';

export function useTransferMutation() {
  const { username, authContext } = useMutationAuth();
  return useTransfer(username, authContext, 'async');
}
```

- `'async'` is the broadcast mode, passed as the last positional arg after `authContext`.
  Its index varies by hook, so read the signature rather than assuming a position. Pass it
  unless the hook has no such parameter. `useBroadcastMutation` takes it as
  `{ broadcastMode: 'async' }` inside the options object instead.
- `useMutationAuth()` from `./common.ts` (most wrappers import it) returns
  `{ username, authContext }`: `currentAccount?.name` off `selectCurrentAccount`, plus
  `useAuthContext()` (`src/providers/sdk/useAuthContext.ts`) building
  `{ adapter: createMobilePlatformAdapter({...}), enableFallback: true }`. There is no
  `mobilePlatformAdapter` object to import, use the factory.
- Most wrappers take no arguments. `useSetCommunityRoleMutation(community)` plus
  `useUpdateCommunityMutation(community)` take the community because the SDK bakes it into
  the mutation key. `useAccountRelationsUpdateMutation(target, onSuccess, onError)` takes
  the target plus both callbacks because the SDK bakes them into the mutation options.
- A few files are not broadcasts, so they skip `useMutationAuth`, despite
  CLAUDE.md still saying all mutation wrappers use it.
  `useGenerateImageMutation` plus the digest hooks in
  `useNewsletterDigestMutations.ts` (`useSubscribeDigestMutation`,
  `useLeaveDigestMutation`, `useUnsubscribeAllDigestsMutation`) bind the HiveSigner `code`
  from `useAuth()` (`src/hooks/useAuth.ts`): `useGenerateImage(username, code)`.
  `useClaimPointsMutation` instead derives the access token itself, decrypting
  `currentAccount.local.accessToken` with `getDigitPinCode(pin)` plus `decryptKey`.

## 2. Export from the barrel

One line in `src/providers/sdk/mutations/index.ts`, under the matching domain comment:

```typescript
export { useTransferMutation } from './useTransferMutation';
```

## 3. Call it (from the barrel)

```typescript
import { useFollowMutation } from '../providers/sdk/mutations';
const followMutation = useFollowMutation();
await followMutation.mutateAsync({ following: data.following });
```

## No SDK hook for the operation?

There is no `packages/sdk` here. `@ecency/sdk` is an npm dependency, so there
is no local build step. Use the generic `useBroadcastMutation`, as
`useIgnoreUserMutation.ts` does. The positional args:

```typescript
return useBroadcastMutation(
  ['hive', 'ignore-user'],                     // 1 mutation key
  username,                                    // 2 username
  ({ following }: { following: string }) => [  // 3 ops builder
    buildIgnoreOp(username!, following),
  ],
  undefined,                                   // 4 onSuccess or undefined
  authContext,                                 // 5 auth context
  'posting',                                   // 6 authority
  { broadcastMode: 'async' },                  // 7 options
);
```

Pass authority as a plain lowercase string. The parameter is typed `AuthorityLevel` from
`@ecency/sdk` (`'posting' | 'active' | 'owner' | 'memo'`); mobile wrappers use
`'posting'` or `'active'`. Do not import the same-named type from
`src/screens/dappBrowser/bridges/bridgeTypes.ts`, an unrelated dapp browser union.

- `'posting'`: vote, comment, reblog, follow, ignore, community roles
- `'active'`: transfer, delegate, power up/down, savings, limit orders, proposal vote,
  witness proxy

The SDK's exported `OPERATION_AUTHORITY_MAP` is the reference list. It maps both
`account_update` plus `account_update2` to `'active'` flatly, which is right for the common
cases but wrong at both ends, so do not copy it for either one.

Both account update operations have payload-dependent authority (`custom_json` is the third
payload-dependent case, below):

| Operation | Payload sets | Authority Hive requires |
|---|---|---|
| both | `owner` | `'owner'` |
| `account_update2` | only `posting_json_metadata` (profile edit, pinned post) | `'posting'` |
| both | anything else | `'active'` |

The owner row covers BOTH versions.

`src/utils/hiveOperationAuthority.ts` implements the posting row plus the active row for
`account_update2` only. It does NOT implement the owner row for either version: the function is
typed `(operation: Operation) => 'posting' | 'active'`, so its `owner` branch returns
`'active'`, `account_update` has no branch at all, plus `hiveOperationAuthority.test.ts` has
no owner case. Its doc comment calling v1 "correctly resolves to active" is wrong whenever the
payload sets `owner`. That resolver
serves the hive-uri path (`src/providers/hive/hive.ts` and
`src/hooks/useLinkProcessor.tsx`), where the operations arrive from an external link.
Treat an owner change as unsupported and reject it rather than routing
it to `'active'`.

`custom_json` is the third payload-dependent case. `required_auths` alone means active,
`required_posting_auths` alone means posting.

A payload populating BOTH is the case this app does not handle. `resolveTxRequiredAuthority`
collapses a whole transaction to a single `'posting' | 'active'`, which
`src/providers/hive/hive.ts` then turns into one decrypted key, posting or active. So treat a
mixed payload as unsupported by this client and say so, rather than calling it malformed or
picking one authority for it.

Neither implementation detects the case today. `hiveOperationAuthority.ts` never reads
`required_posting_auths` at all. The SDK's `getCustomJsonAuthority` returns `'active'` as soon
as `required_auths` is non-empty, without checking the other list. Both therefore sign with one
key.

`useBroadcastMutation` takes `authority` as its
sixth positional parameter, fixed when the hook is created, while `operations` is
`(payload: T) => Operation[]` and only runs at mutate time. The mutation reads the closed-over
value, so a wrapper cannot choose an authority from its payload. If both shapes are possible,
write two hooks with fixed authorities and pick at the call site:

```typescript
// posting: profile edit, pinned post, anything touching only posting_json_metadata
export const useUpdateProfileMetadataMutation = () => { /* ..., 'posting' */ };
// active: json_metadata or a key or authority change other than owner (an owner
// change is unsupported, reject it)
export const useUpdateAccountKeysMutation = () => { /* ..., 'active' */ };
```

`useBroadcastMutation`'s `authority` parameter defaults to `'posting'`, so pass the right
value explicitly.

Prefer an SDK `build<Operation>Op` helper (`buildTransferOp`, `buildVoteOp`) over a hand
written op tuple.

## Gotchas

1. Auth is not your job: the adapter routes PIN key decryption, HiveSigner `hive-uri`
   WebView signing, HiveAuth signing, plus the active key upgrade sheet.
2. No toasts or navigation in the wrapper. Do that at the call site or in hook callbacks.
3. Check the hook exists in the installed `@ecency/sdk` first, then run `yarn lint` plus
   `yarn typecheck`; the baseline is empty, so any error fails CI.
