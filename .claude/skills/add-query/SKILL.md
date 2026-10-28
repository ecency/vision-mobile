---
name: add-query
description: Use when reading server data in the mobile app - wiring an @ecency/sdk query option into a screen, adding or editing a hook under src/providers/queries/, paginating a feed or list with an infinite query, or fixing a query key, enabled guard, or cache persistence problem.
argument-hint: [query-name]
---

# Add Query

Read `CLAUDE.md` first (State Management, SDK Migration). Writes are a separate skill: `add-mutation`.

## First: a failing test for what the app adds

The fetch itself belongs to the SDK, so its spec is written first in vision-web (`add-query` skill there). On mobile, test what the app adds before writing it: a mapping or `select`, an `enabled` guard, a page param for an infinite query. `src/providers/queries/` already has co-located tests to copy. Run it with `node node_modules/jest/bin/jest.js <path>` and confirm it fails **for the right reason**: an assertion about the missing behaviour, not an import error, a missing mock or a typo. When the subject does not exist yet, add a stub export (a component returning `null`, a function returning `undefined`) so the run reaches the assertion. If the issue says `No test-first: <reason>` (pure layout or styling, a dependency upgrade, native build config), skip this and repeat that line in the PR.

## Rule: the SDK owns the fetch

`@ecency/sdk` exports a large family of `get*QueryOptions` helpers. Most files under
`src/providers/queries/` import from `@ecency/sdk`; a bare `queryFn:` is rare in that directory.
Search the SDK for your own domain first. Write a `queryFn` only when that search comes back empty:

```bash
D=node_modules/@ecency/sdk/dist/browser/index.d.ts
test -f "$D" || echo 'SDK not installed, run yarn'   # a missing file greps as empty, a false all-clear
grep -o "get[A-Za-z]*QueryOptions" "$D" | sort -u | grep -i draft   # swap in your domain
grep -n "declare function getPostQueryOptions" "$D"
```

Drop the trailing `| grep -i draft` to list them all. The last grep gives the real argument order.
Do not guess it. In the call sites here, `getPostQueryOptions` takes the observer third.

## 1. Straight from a component

```typescript
import { useQuery } from '@tanstack/react-query';
import { getPostQueryOptions, getAccountFullQueryOptions } from '@ecency/sdk';

const observer = currentAccount?.name;
const { data: post, isLoading } = useQuery(getPostQueryOptions(author, permlink, observer));
const { data: account } = useQuery(getAccountFullQueryOptions(author));
```

## 2. App hook that adds mobile-only options

The dominant shape: spread the SDK options, then override. Verbatim,
`src/providers/queries/leaderboardQueries/leaderboardQueries.ts`:

```typescript
import { useQuery } from '@tanstack/react-query';
import { getDiscoverLeaderboardQueryOptions } from '@ecency/sdk';

/** hook used to return leaderboard data using SDK */
export const useGetLeaderboardQuery = (duration: 'day' | 'week' | 'month') => {
  return useQuery({
    ...getDiscoverLeaderboardQueryOptions(duration),
    // Opted in explicitly: the client default is off, because waking every query in
    // the cache on every resume is far more than this needs. The board lives inside a
    // tab that stays mounted, so without this it shows whatever it fetched hours ago
    // until the user thinks to pull to refresh.
    refetchOnWindowFocus: true,
  });
};
```

Usual overrides: `enabled`, `select`, `staleTime`, `gcTime`, `initialData`. Keep the SDK's
`queryKey` plus `queryFn` so the cache entry stays shared with other surfaces.

## 3. Private-API queries need the auth pair

Ecency backend queries take `username` plus an access token. Use `useAuth()` rather than
re-deriving it. From `src/providers/queries/newsletterQueries.ts`:

```typescript
import { useAuth } from '../../hooks';

export const useDigestSubscriptionsQuery = () => {
  const { username, code } = useAuth();
  return useQuery(getDigestSubscriptionsQueryOptions(username, code));
};
```

## 4. Infinite queries

SDK `get*InfiniteQueryOptions` already carry `initialPageParam` plus `getNextPageParam`. The repo
rarely hand-rolls those two. Flatten in the hook, do not re-key. From
`src/providers/queries/draftQueries.ts` (comments stripped):

```typescript
const { username, code } = useAuth();
const enabled = !!username && !!code;

const infiniteQuery = useInfiniteQuery({
  ...getDraftsInfiniteQueryOptions(username ?? '', code ?? '', limit),
  enabled,
});

const data = useMemo(() => {
  if (!infiniteQuery.data?.pages) return [];
  return infiniteQuery.data.pages.flatMap((page) => page.data);
}, [infiniteQuery.data?.pages]);

return { ...infiniteQuery, data, pagesLoaded: infiniteQuery.data?.pages?.length ?? 0 };
```

## 5. Query keys

- **SDK keys**: `import { QueryKeys } from '@ecency/sdk'`. Namespaced factories, not strings:
  `QueryKeys.posts.draftsInfinite(username, limit)`, `QueryKeys.accounts.full(name)`,
  `QueryKeys.polls.details(author, permlink)`. Use these to invalidate or seed an SDK cache entry.
- **Mobile-only keys**: `src/providers/queries/queryKeys.ts` is a *default* export named `QUERIES`
  with a nested shape. Files import that default (`import QUERIES from '<relative>/queryKeys'`,
  so the specifier depends on the file) then `queryKey: [QUERIES.WALLET.GET_ACTIVITIES, username]`.
  There is no local `QueryKeys` export.

## 6. Hand-rolled query (last resort)

Only when the SDK has nothing. Verbatim, `src/providers/queries/settingsQueries.ts`:

```typescript
export const useGetServersQuery = () => {
  return useQuery<string[]>({
    queryKey: [QUERIES.SETTINGS.GET_SERVERS],
    queryFn: getNodes,
    placeholderData: [...SERVER_LIST],
    staleTime: 0,
  });
};
```

`getNodes` comes from `src/providers/ecency/ecency.ts`, a provider module, not a bare fetch.

## 7. Export

`src/providers/queries/index.ts` uses `export * from './<domain>Queries'`. It also re-exports
`getQueryClient` from the SDK; the rest of the file is local (`initQueryClient` plus the
persistence allowlist). A subdirectory carries its own `index.ts` that re-exports namespaces.

## Gotchas

1. **Persistence is an allowlist.** `_shouldDehydrateQuery` in `src/providers/queries/index.ts`
   switches on `queryKey[0]`, then narrows on `queryKey[1]`. A namespace with no case falls to the
   default and is dropped, so a new namespace is not persisted until you add its case. Within a
   case the subtype handling differs per namespace: some subtypes are dropped, some persist only
   while a single page is loaded, some persist wholesale. Read the switch before assuming a new key
   persists.
2. **Guard with `enabled`** when a param can be undefined. An `undefined` anywhere in a query key
   also blocks persistence.
3. Returning `undefined` from `getNextPageParam` stops pagination; that is what the repo's
   hand-rolled case returns.
4. **Optimistic vote data is no longer Redux.** Call `updateVoteInQueryCaches()` and read back via
   `applyRecentVoteOverrideToEntry()` from `src/providers/queries/postQueries/voteCacheUtils.ts`;
   seed a post before navigation with `usePostsCachePrimer()`. `useInjectVotesCache` is gone,
   though CLAUDE.md's Post Data Flow still lists it.
5. **Non-React code**: import `getQueryClient` from the app barrel `providers/queries` rather than
   the SDK: `await queryClient.fetchQuery(getAccountsQueryOptions([username]))`.
6. `src/providers/queries/sdk-config.ts` runs from `initQueryClient()` and configures
   `ConfigManager` (query client, private API host, image host, Hive nodes, DMCA lists). Adding a
   query rarely requires touching it.

Prettier width is 100 (`.prettierrc`). Finish with `yarn lint`, `yarn typecheck` plus
`yarn test:ci`; `.github/workflows/test.yml` runs all three on every PR. The baseline in
`tsc-baseline.json` is empty, so any type error fails. Two co-located tests in
`src/providers/queries/` read `index.ts` and assert the `export * from './<domain>Queries';` line
from section 7, so a missing barrel export fails jest.
