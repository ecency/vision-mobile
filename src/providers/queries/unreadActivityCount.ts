import { getNotificationsUnreadCountQueryOptions, getQueryClient } from '@ecency/sdk';

/**
 * Fetches the unread notification count shown on the notifications tab badge.
 *
 * Resolves to undefined without a username or access code, instead of the SDK query's
 * "Missing access token" error, so callers keep their current count. A read within the
 * default staleTime reuses the cached count. `force` asks the server again, for callers
 * that react to a new notification; overlapping forced reads share one request.
 *
 * Needs @ecency/sdk 2.4.11 or later: earlier versions seeded the query with
 * `initialData: 0`, which counted as a fresh count and was returned without a request.
 */
/** The most recent unread count fetched for `username`, if there is one. */
export const getCachedUnreadActivityCount = (username: string | undefined): number | undefined =>
  username
    ? getQueryClient().getQueryData(
        getNotificationsUnreadCountQueryOptions(username, undefined).queryKey,
      )
    : undefined;

export const fetchUnreadActivityCount = async (
  username: string | undefined,
  code: string | undefined,
  { force = false }: { force?: boolean } = {},
): Promise<number | undefined> => {
  if (!username || !code) {
    return undefined;
  }

  return getQueryClient().fetchQuery({
    ...getNotificationsUnreadCountQueryOptions(username, code),
    ...(force ? { staleTime: 0 } : {}),
  });
};
