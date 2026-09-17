import { QueryCache, QueryClient, dehydrate, hydrate } from '@tanstack/react-query';
import { getNotificationsUnreadCountQueryOptions } from '@ecency/sdk';
import { fetchUnreadActivityCount } from './unreadActivityCount';

// The real SDK query options: the bug came from their old `initialData: 0`, so a mock
// would hide exactly what these tests are about. Only the query client is swapped.
let mockQueryClient: QueryClient;
jest.mock('@ecency/sdk', () => ({
  ...jest.requireActual('@ecency/sdk'),
  getQueryClient: () => mockQueryClient,
}));

// Same query defaults as the app client (providers/queries/index.ts).
const makeClient = () =>
  new QueryClient({ defaultOptions: { queries: { staleTime: 60 * 1000, retry: false } } });

const fetchMock = jest.fn();

const answer = (count: number) =>
  fetchMock.mockImplementation(async () => ({
    ok: true,
    status: 200,
    json: async () => ({ count }),
  }));

beforeEach(() => {
  fetchMock.mockReset();
  (global as any).fetch = fetchMock;
  mockQueryClient = makeClient();
});

afterEach(() => {
  mockQueryClient.clear();
});

describe('fetchUnreadActivityCount', () => {
  it('asks the server on a cold cache instead of returning the placeholder 0', async () => {
    answer(7);

    await expect(fetchUnreadActivityCount('alice', 'code')).resolves.toBe(7);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('reuses a count fetched under a minute ago', async () => {
    answer(7);
    await fetchUnreadActivityCount('alice', 'code');
    answer(9);

    await expect(fetchUnreadActivityCount('alice', 'code')).resolves.toBe(7);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('asks again when forced', async () => {
    answer(7);
    await fetchUnreadActivityCount('alice', 'code');
    answer(9);

    await expect(fetchUnreadActivityCount('alice', 'code', { force: true })).resolves.toBe(9);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('shares one request between overlapping forced reads', async () => {
    answer(4);

    const counts = await Promise.all([
      fetchUnreadActivityCount('alice', 'code', { force: true }),
      fetchUnreadActivityCount('alice', 'code', { force: true }),
    ]);

    expect(counts).toEqual([4, 4]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('skips the request without an access code and does not cache a 0', async () => {
    answer(5);

    await expect(fetchUnreadActivityCount('alice', '')).resolves.toBeUndefined();
    await expect(fetchUnreadActivityCount('alice', undefined)).resolves.toBeUndefined();
    await expect(fetchUnreadActivityCount(undefined, 'code')).resolves.toBeUndefined();
    expect(fetchMock).not.toHaveBeenCalled();

    // Once the code is available, the real count comes back at once.
    await expect(fetchUnreadActivityCount('alice', 'code')).resolves.toBe(5);
  });

  it('propagates a failed request so callers keep their last count', async () => {
    fetchMock.mockRejectedValue(new TypeError('Network request failed'));

    await expect(fetchUnreadActivityCount('alice', 'code')).rejects.toThrow(
      'Network request failed',
    );
  });
});

describe('SDK unread count options', () => {
  it('let a count restored from the persisted cache stand', async () => {
    const options = getNotificationsUnreadCountQueryOptions('alice', 'code');
    const previousSession = makeClient();
    previousSession.setQueryData(options.queryKey, 3);
    const persisted = dehydrate(previousSession);
    previousSession.clear();

    // A query for the key exists before the persisted cache is restored. `build`
    // does not accept the SDK's tagged query key type, hence the cast. The SDK seeds
    // nothing any more, so there is no fresh-looking 0 to win over the restored count.
    mockQueryClient
      .getQueryCache()
      .build(mockQueryClient, options as Parameters<QueryCache['build']>[1]);
    expect(mockQueryClient.getQueryData(options.queryKey)).toBeUndefined();

    hydrate(mockQueryClient, persisted);

    expect(mockQueryClient.getQueryData(options.queryKey)).toBe(3);
  });
});
