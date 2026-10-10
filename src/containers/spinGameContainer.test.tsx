import React from 'react';
import TestRenderer, { act } from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

// What the server would answer next for the spin status, and every claim sent.
const mockStatus = jest.fn();
const mockClaim = jest.fn();
// The access token as useAuth reports it. Changing it re-creates the status check,
// exactly as a token refresh does in the app.
const mockAuth = { username: 'alice', code: 'token-1' };

jest.mock('@ecency/sdk', () => ({
  getGameStatusCheckQueryOptions: (username: string) => ({
    queryKey: ['games', 'status-check', 'spin', username],
    queryFn: () => mockStatus(),
  }),
  useGameClaim: (_username: string, _code: string, _type: string, key: string) => ({
    mutateAsync: () => mockClaim(key),
  }),
}));

jest.mock('../hooks', () => ({
  useAuth: () => ({ ...mockAuth }),
}));

jest.mock('react-intl', () => ({
  useIntl: () => ({ formatMessage: ({ id }: { id: string }) => id }),
}));

jest.mock('../utils/sentryUtils', () => ({
  captureException: jest.fn(),
}));

// eslint-disable-next-line import/first
import SpinGameContainer from './spinGameContainer';

const FREE_KEY = 'sxa12-free';
const AVAILABLE = { status: 12, remaining: 1, key: FREE_KEY };
const USED = { status: 18, next_date: '2030-01-01T00:00:00Z', wait_secs: 100 };

// A server that hands out one free spin: available until it is claimed.
function serveOneFreeSpin() {
  let used = false;
  mockStatus.mockImplementation(async () => (used ? USED : AVAILABLE));
  return () => {
    used = true;
  };
}

const flush = async () => {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
};

// Same defaults as the app's client: a status read within the minute is "fresh".
const makeClient = () =>
  new QueryClient({ defaultOptions: { queries: { staleTime: 60 * 1000, retry: false } } });

function mount(client: QueryClient) {
  const api: { current: any } = { current: null };
  let renderer: TestRenderer.ReactTestRenderer;
  const tree = () => (
    <QueryClientProvider client={client}>
      <SpinGameContainer>
        {(props: any) => {
          api.current = props;
          return null;
        }}
      </SpinGameContainer>
    </QueryClientProvider>
  );
  act(() => {
    renderer = TestRenderer.create(tree());
  });
  return {
    api,
    rerender: () => act(() => renderer.update(tree())),
    unmount: () => act(() => renderer.unmount()),
  };
}

const press = async (api: { current: any }) => {
  await act(async () => {
    await api.current.startGame('spin');
  });
  await flush();
};

describe('SpinGameContainer', () => {
  beforeEach(() => {
    mockStatus.mockReset();
    mockClaim.mockReset();
    mockClaim.mockResolvedValue({ score: 10 });
    mockAuth.code = 'token-1';
  });

  it('does not offer or claim a used spin again when the screen is reopened', async () => {
    const markUsed = serveOneFreeSpin();
    mockClaim.mockImplementation(async () => {
      markUsed();
      return { score: 10 };
    });
    const client = makeClient();

    const first = mount(client);
    await flush();
    await press(first.api);
    expect(mockClaim).toHaveBeenCalledTimes(1);
    expect(first.api.current.gameRight).toBe(0);
    first.unmount();

    const second = mount(client);
    await flush();
    expect(second.api.current.gameRight).toBe(0);
    await press(second.api);
    expect(mockClaim).toHaveBeenCalledTimes(1);
  });

  it('does not send the claim again when the access token is refreshed', async () => {
    const markUsed = serveOneFreeSpin();
    const screen = mount(makeClient());
    await flush();

    // hold the claim open so the refresh lands while the claim key is still set
    let finishClaim: (value: { score: number }) => void = () => undefined;
    mockClaim.mockImplementation(() => {
      markUsed();
      return new Promise<{ score: number }>((resolve) => {
        finishClaim = resolve;
      });
    });
    await press(screen.api);
    expect(mockClaim).toHaveBeenCalledTimes(1);

    mockAuth.code = 'token-2';
    screen.rerender();
    await flush();
    expect(mockClaim).toHaveBeenCalledTimes(1);

    await act(async () => {
      finishClaim({ score: 10 });
    });
    await flush();
    expect(mockClaim).toHaveBeenCalledTimes(1);
    expect(screen.api.current.score).toBe(10);
  });

  it('sends one claim when spin is pressed again while a claim is in flight', async () => {
    // purchased spins: every status answer carries a different key, so nothing but
    // the in-flight guard stops the second press from claiming
    let n = 0;
    mockStatus.mockImplementation(async () => {
      n += 1;
      return { status: 3, remaining: 5, key: `paid-${n}` };
    });
    const screen = mount(makeClient());
    await flush();

    let finishClaim: (value: { score: number }) => void = () => undefined;
    mockClaim.mockImplementation(
      () =>
        new Promise<{ score: number }>((resolve) => {
          finishClaim = resolve;
        }),
    );
    await press(screen.api);
    expect(mockClaim).toHaveBeenCalledTimes(1);

    await press(screen.api);
    expect(mockClaim).toHaveBeenCalledTimes(1);

    await act(async () => {
      finishClaim({ score: 10 });
    });
    await flush();
    expect(mockClaim).toHaveBeenCalledTimes(1);

    // and the next press, with nothing in flight, claims the next purchased spin
    mockClaim.mockResolvedValue({ score: 10 });
    await press(screen.api);
    expect(mockClaim).toHaveBeenCalledTimes(2);
  });

  it('ignores a status answer requested before the claim that arrives after it', async () => {
    const markUsed = serveOneFreeSpin();
    mockClaim.mockImplementation(async () => {
      markUsed();
      return { score: 10 };
    });
    const screen = mount(makeClient());
    await flush();

    // a token refresh starts a status check that the server answers late, with the
    // state from before the claim
    let answerLate: (value: typeof AVAILABLE) => void = () => undefined;
    mockStatus.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          answerLate = resolve;
        }),
    );
    mockAuth.code = 'token-2';
    screen.rerender();

    await press(screen.api);
    expect(mockClaim).toHaveBeenCalledTimes(1);
    expect(screen.api.current.gameRight).toBe(0);

    await act(async () => {
      answerLate(AVAILABLE);
    });
    await flush();
    expect(screen.api.current.gameRight).toBe(0);
  });

  it('claims the free spin again on the same screen once it is available again', async () => {
    mockStatus.mockResolvedValue(AVAILABLE);
    const screen = mount(makeClient());
    await flush();

    await press(screen.api);
    expect(mockClaim).toHaveBeenCalledTimes(1);

    // the next day: same screen, same free key
    await press(screen.api);
    expect(mockClaim).toHaveBeenCalledTimes(2);
    expect(mockClaim).toHaveBeenLastCalledWith(FREE_KEY);
  });

  it('lets the user try again after a failed claim', async () => {
    mockStatus.mockResolvedValue(AVAILABLE);
    mockClaim.mockRejectedValueOnce(new Error('failed with status 502'));
    const screen = mount(makeClient());
    await flush();

    await press(screen.api);
    await press(screen.api);
    expect(mockClaim).toHaveBeenCalledTimes(2);
  });
});
