import { getNotificationsUnreadCountQueryOptions, getQueryClient } from '@ecency/sdk';

/**
 * The SDK's unread notification count query, with its placeholder marked as never
 * fetched.
 *
 * The SDK seeds the count with `initialData: 0`, and TanStack stamps initial data as
 * fetched "now". Under the app's 60s default staleTime that 0 counted as fresh, so
 * `fetchQuery` returned it without a request and the badge stayed empty after a cold
 * start. The same 0 also won over the count restored from the persisted cache.
 */
export const getUnreadActivityCountQueryOptions = (
  username: string | undefined,
  code: string | undefined,
) => ({
  ...getNotificationsUnreadCountQueryOptions(username, code),
  initialDataUpdatedAt: 0,
});

/**
 * Fetches the unread notification count shown on the notifications tab badge.
 *
 * Resolves to undefined without a username or access code: the SDK query answers 0
 * without a code and would cache that 0 as a real count. A read within the default
 * staleTime reuses the cached count. `force` asks the server again, for callers that
 * react to a new notification; overlapping forced reads share one request.
 */
export const fetchUnreadActivityCount = async (
  username: string | undefined,
  code: string | undefined,
  { force = false }: { force?: boolean } = {},
): Promise<number | undefined> => {
  if (!username || !code) {
    return undefined;
  }

  return getQueryClient().fetchQuery({
    ...getUnreadActivityCountQueryOptions(username, code),
    ...(force ? { staleTime: 0 } : {}),
  });
};
