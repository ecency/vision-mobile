// Real crypto, so the PIN fallbacks are exercised against actual ciphertext.
import { getMessaging } from '@react-native-firebase/messaging';
import { saveNotificationSetting } from '@ecency/sdk';
import { encryptKey } from './crypto';
import {
  decryptAccessToken,
  disablePushRegistrations,
  getPushAccounts,
  beginPushRegistration,
  getPushSystem,
  getSignedInAccounts,
  PushAccount,
  waitForPushRelease,
} from './pushRegistration';

jest.unmock('crypto-js');

jest.mock('react-native-config', () => ({ DEFAULT_PIN: 'default-pin', PIN_KEY: 'pin-key' }));

jest.mock('@ecency/sdk', () => ({ saveNotificationSetting: jest.fn() }));

const mockMessaging = {
  getToken: jest.fn(),
  deleteToken: jest.fn(),
};
jest.mock('@react-native-firebase/messaging', () => ({
  getMessaging: jest.fn(() => mockMessaging),
}));

const saveMock = saveNotificationSetting as jest.Mock;

const DEFAULT_PIN = 'default-pin';
const USER_PIN = '112233';
const appPin = (pin: string) => encryptKey(pin, 'pin-key');

beforeEach(() => {
  jest.clearAllMocks();
  (getMessaging as jest.Mock).mockImplementation(() => mockMessaging);
  mockMessaging.getToken.mockResolvedValue('fcm-token');
  mockMessaging.deleteToken.mockResolvedValue(undefined);
  saveMock.mockResolvedValue({});
});

describe('decryptAccessToken', () => {
  it('returns undefined when there is no stored token', () => {
    expect(decryptAccessToken(undefined, appPin(DEFAULT_PIN))).toBeUndefined();
    expect(decryptAccessToken('', appPin(DEFAULT_PIN))).toBeUndefined();
  });

  it('decrypts a token stored under DEFAULT_PIN', () => {
    const stored = encryptKey('code-1', DEFAULT_PIN);
    expect(decryptAccessToken(stored, appPin(DEFAULT_PIN))).toBe('code-1');
  });

  it("decrypts a token still stored under the user's own PIN", () => {
    // An account not unlocked since the DEFAULT_PIN migration. Decrypting with
    // DEFAULT_PIN alone, as registration used to, fails here.
    const stored = encryptKey('code-2', USER_PIN);
    expect(decryptAccessToken(stored, appPin(USER_PIN))).toBe('code-2');
  });

  it('falls back to DEFAULT_PIN when the app PIN does not match the token', () => {
    // A fresh login stores the token under DEFAULT_PIN before the app PIN is rewritten.
    const stored = encryptKey('code-3', DEFAULT_PIN);
    expect(decryptAccessToken(stored, appPin(USER_PIN))).toBe('code-3');
  });

  it('falls back to DEFAULT_PIN when no app PIN is stored', () => {
    const stored = encryptKey('code-4', DEFAULT_PIN);
    expect(decryptAccessToken(stored, undefined)).toBe('code-4');
  });

  it('returns undefined when neither PIN decrypts the token', () => {
    const stored = encryptKey('code-5', 'some-other-pin');
    expect(decryptAccessToken(stored, appPin(USER_PIN))).toBeUndefined();
    expect(decryptAccessToken(stored, appPin(DEFAULT_PIN))).toBeUndefined();
  });
});

describe('getPushAccounts', () => {
  const account = (name: string, code?: string, keyedBy: 'name' | 'username' = 'username') => ({
    [keyedBy]: name,
    local: code ? { accessToken: encryptKey(code, DEFAULT_PIN) } : {},
  });

  it('lists each account once with its decrypted token', () => {
    const current = { name: 'alice', local: { accessToken: encryptKey('alice-new', DEFAULT_PIN) } };
    const others = [account('alice', 'alice-old'), account('bob', 'bob-code')];

    expect(getPushAccounts(current, others, appPin(DEFAULT_PIN))).toEqual([
      { username: 'alice', accessToken: 'alice-new' },
      { username: 'bob', accessToken: 'bob-code' },
    ]);
  });

  it('adds the current account when it is missing from otherAccounts', () => {
    const current = { name: 'carol', local: { accessToken: encryptKey('carol', DEFAULT_PIN) } };

    expect(getPushAccounts(current, [account('bob', 'bob-code')], appPin(DEFAULT_PIN))).toEqual([
      { username: 'bob', accessToken: 'bob-code' },
      { username: 'carol', accessToken: 'carol' },
    ]);
  });

  it("keeps the other-account token when the current account's entry has none", () => {
    const current = { name: 'alice', local: {} };

    expect(getPushAccounts(current, [account('alice', 'alice-old')], appPin(DEFAULT_PIN))).toEqual([
      { username: 'alice', accessToken: 'alice-old' },
    ]);
  });

  it('accepts entries keyed by name and skips entries with no name', () => {
    const others = [account('dave', 'dave-code', 'name'), { local: {} }];

    expect(getPushAccounts({}, others, appPin(DEFAULT_PIN))).toEqual([
      { username: 'dave', accessToken: 'dave-code' },
    ]);
  });
});

describe('getSignedInAccounts', () => {
  it('includes the current account when otherAccounts does not list it', () => {
    const current = { name: 'alice', local: { accessToken: 'enc-alice' } };
    const bob = { username: 'bob', local: { accessToken: 'enc-bob' } };

    expect(getSignedInAccounts(current, [bob])).toEqual([
      { username: 'bob', account: bob },
      { username: 'alice', account: current },
    ]);
  });

  it('lists an account once when it is both current and in otherAccounts', () => {
    const current = { name: 'alice', local: { accessToken: 'enc-new' } };
    const stored = { username: 'alice', local: { accessToken: 'enc-old' } };

    expect(getSignedInAccounts(current, [stored])).toEqual([
      { username: 'alice', account: current },
    ]);
  });
});

const deferred = () => {
  let resolve: () => void = () => undefined;
  let reject: (err: Error) => void = () => undefined;
  const promise = new Promise<void>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};
const settle = () => new Promise((resolve) => setImmediate(resolve));
const always = () => true;

// Every test leaves the release chain and the in-flight set empty: each release it starts
// is awaited, and each registration it begins is finished.

describe('disablePushRegistrations', () => {
  it('disables every account with the current device token and keeps the token', async () => {
    await disablePushRegistrations(
      [
        { username: 'alice', accessToken: 'alice-code' },
        { username: 'carol', accessToken: 'carol-code' },
      ],
      { deleteToken: false },
    );

    expect(saveMock).toHaveBeenCalledTimes(2);
    expect(saveMock).toHaveBeenCalledWith(
      'alice-code',
      'alice',
      getPushSystem(),
      0,
      [],
      'fcm-token',
    );
    expect(saveMock).toHaveBeenCalledWith(
      'carol-code',
      'carol',
      getPushSystem(),
      0,
      [],
      'fcm-token',
    );
    expect(mockMessaging.deleteToken).not.toHaveBeenCalled();
  });

  it('deletes the device token only after every request has settled', async () => {
    const events: string[] = [];
    const alice = deferred();
    saveMock.mockImplementation((_code: string, username: string) => {
      if (username === 'alice') {
        return alice.promise.then(() => events.push('alice settled'));
      }
      events.push('bob settled');
      return Promise.reject(new Error('Request failed with status 500'));
    });
    mockMessaging.deleteToken.mockImplementation(async () => {
      events.push('token deleted');
    });

    const done = disablePushRegistrations(
      [
        { username: 'alice', accessToken: 'alice-code' },
        { username: 'bob', accessToken: 'bob-code' },
      ],
      { deleteToken: true },
    );
    await settle();
    expect(mockMessaging.deleteToken).not.toHaveBeenCalled();

    alice.resolve();
    await done;
    expect(events).toEqual(['bob settled', 'alice settled', 'token deleted']);
  });

  it.each([
    ['a request fails', [{ username: 'alice', accessToken: 'a' }], true],
    ['an account has no access token', [{ username: 'alice', accessToken: undefined }], false],
  ])(
    'with accounts left, replaces the token when %s, so the row cannot stay on',
    async (_case, accounts, failRequest) => {
      if (failRequest) {
        saveMock.mockRejectedValueOnce(new Error('Request failed with status 401'));
      }
      const onTokenReplaced = jest.fn();

      await disablePushRegistrations(accounts, { deleteToken: false, onTokenReplaced });

      expect(mockMessaging.deleteToken).toHaveBeenCalledTimes(1);
      expect(onTokenReplaced).toHaveBeenCalledTimes(1);
    },
  );

  it('asks for registration after deleting the token with no account left', async () => {
    const onTokenReplaced = jest.fn();
    await disablePushRegistrations([{ username: 'alice', accessToken: 'a' }], {
      deleteToken: true,
      onTokenReplaced,
    });
    expect(onTokenReplaced).toHaveBeenCalledTimes(1);
  });

  it('does not ask for registration when the token could not be deleted', async () => {
    mockMessaging.deleteToken.mockRejectedValue(new Error('offline'));
    const onTokenReplaced = jest.fn();

    await expect(
      disablePushRegistrations([], { deleteToken: true, onTokenReplaced }),
    ).resolves.toBeUndefined();
    expect(mockMessaging.deleteToken).toHaveBeenCalledTimes(1);
    expect(onTokenReplaced).not.toHaveBeenCalled();
  });

  it('does nothing when the device has no token', async () => {
    mockMessaging.getToken.mockRejectedValue(new Error('SERVICE_NOT_AVAILABLE'));

    await expect(
      disablePushRegistrations([{ username: 'alice', accessToken: 'alice-code' }], {
        deleteToken: true,
      }),
    ).resolves.toBeUndefined();
    expect(saveMock).not.toHaveBeenCalled();
    expect(mockMessaging.deleteToken).not.toHaveBeenCalled();
  });

  it('survives a request that throws synchronously', async () => {
    saveMock.mockImplementationOnce(() => {
      throw new Error('synchronous failure');
    });

    await disablePushRegistrations(
      [
        { username: 'alice', accessToken: 'a' },
        { username: 'bob', accessToken: 'b' },
      ],
      { deleteToken: false },
    );
    // Both requests were attempted, and the failed one replaced the token.
    expect(saveMock).toHaveBeenCalledTimes(2);
    expect(mockMessaging.deleteToken).toHaveBeenCalledTimes(1);
  });

  it('keeps the chain usable after a release fails unexpectedly', async () => {
    // A malformed call throws inside the release itself.
    await expect(
      disablePushRegistrations(null as unknown as PushAccount[], { deleteToken: true }),
    ).resolves.toBeUndefined();

    await expect(waitForPushRelease(20)).resolves.toBe(true);
    await disablePushRegistrations([{ username: 'alice', accessToken: 'a' }], {
      deleteToken: true,
    });
    expect(mockMessaging.deleteToken).toHaveBeenCalledTimes(1);
  });

  it('waits for a registration already in flight before disabling', async () => {
    const registration = await beginPushRegistration({ stillWanted: always });
    const release = disablePushRegistrations([{ username: 'alice', accessToken: 'a' }], {
      deleteToken: false,
    });
    await settle();
    // The registration's request is still out: the disable must not overtake it.
    expect(saveMock).not.toHaveBeenCalled();

    registration!.finish();
    await release;
    expect(saveMock).toHaveBeenCalledTimes(1);
  });

  it('stops waiting for a registration that never finishes', async () => {
    const registration = await beginPushRegistration({ stillWanted: always });
    await disablePushRegistrations([{ username: 'alice', accessToken: 'a' }], {
      deleteToken: false,
      registrationWaitMs: 20,
    });
    expect(saveMock).toHaveBeenCalledTimes(1);
    registration!.finish();
  });
});

describe('waitForPushRelease', () => {
  it('resolves at once when nothing is being released', async () => {
    await expect(waitForPushRelease(10_000)).resolves.toBe(true);
  });

  it('runs releases one after another', async () => {
    const first = deferred();
    saveMock.mockImplementationOnce(() => first.promise);
    const events: string[] = [];
    mockMessaging.getToken.mockImplementation(async () => {
      events.push('token read');
      return 'fcm-token';
    });
    mockMessaging.deleteToken.mockImplementation(async () => {
      events.push('token deleted');
    });

    const releaseA = disablePushRegistrations([{ username: 'alice', accessToken: 'a' }], {
      deleteToken: true,
    });
    const releaseB = disablePushRegistrations([{ username: 'bob', accessToken: 'b' }], {
      deleteToken: false,
    });
    await settle();
    expect(events).toEqual(['token read']);

    first.resolve();
    await Promise.all([releaseA, releaseB]);
    expect(events).toEqual(['token read', 'token deleted', 'token read']);
  });

  it('waits for releases queued while it waits', async () => {
    const first = deferred();
    const second = deferred();
    saveMock
      .mockImplementationOnce(() => first.promise)
      .mockImplementationOnce(() => second.promise);

    const releaseA = disablePushRegistrations([{ username: 'alice', accessToken: 'a' }], {
      deleteToken: false,
    });
    let waited: boolean | undefined;
    const waiting = waitForPushRelease(10_000).then((result) => {
      waited = result;
    });
    const releaseB = disablePushRegistrations([{ username: 'bob', accessToken: 'b' }], {
      deleteToken: false,
    });

    first.resolve();
    await releaseA;
    await settle();
    expect(waited).toBeUndefined();

    second.resolve();
    await Promise.all([releaseB, waiting]);
    expect(waited).toBe(true);
  });

  it('gives up after the timeout when a release never settles', async () => {
    const stuck = deferred();
    saveMock.mockImplementation(() => stuck.promise);

    const release = disablePushRegistrations([{ username: 'alice', accessToken: 'code' }], {
      deleteToken: true,
    });
    await expect(waitForPushRelease(20)).resolves.toBe(false);
    expect(mockMessaging.deleteToken).not.toHaveBeenCalled();

    stuck.resolve();
    await release;
  });
});

describe('beginPushRegistration', () => {
  it('reads the token once releases are done and the account is still wanted', async () => {
    const registration = await beginPushRegistration({ stillWanted: always });
    expect(registration?.token).toBe('fcm-token');
    registration!.finish();
  });

  it('gives up when the account left while a queued release ran', async () => {
    // Logout A (slow), login B waits, logout B queues a second release before the first ends.
    const first = deferred();
    saveMock.mockImplementationOnce(() => first.promise);
    let signedIn = true;
    const events: string[] = [];
    mockMessaging.getToken.mockImplementation(async () => {
      events.push('token read');
      return 'fcm-token';
    });
    mockMessaging.deleteToken.mockImplementation(async () => {
      events.push('token deleted');
    });

    const releaseA = disablePushRegistrations([{ username: 'alice', accessToken: 'a' }], {
      deleteToken: true,
    });
    const bob = beginPushRegistration({ stillWanted: () => signedIn });
    signedIn = false;
    const releaseB = disablePushRegistrations([{ username: 'bob', accessToken: 'b' }], {
      deleteToken: true,
    });

    first.resolve();
    await Promise.all([releaseA, releaseB]);
    await expect(bob).resolves.toBeNull();
    // Only the two releases read the token, and both deleted it.
    expect(events).toEqual(['token read', 'token deleted', 'token read', 'token deleted']);
  });

  it('finishes its in-flight mark when the token cannot be read', async () => {
    mockMessaging.getToken.mockRejectedValueOnce(new Error('SERVICE_NOT_AVAILABLE'));
    await expect(beginPushRegistration({ stillWanted: always })).rejects.toThrow(
      'SERVICE_NOT_AVAILABLE',
    );

    // A release does not wait for the failed registration.
    await disablePushRegistrations([{ username: 'alice', accessToken: 'a' }], {
      deleteToken: false,
      registrationWaitMs: 10_000,
    });
    expect(saveMock).toHaveBeenCalledTimes(1);
  });

  it('asks to register again once every release has settled after a timed-out wait', async () => {
    const stuck = deferred();
    const queued = deferred();
    saveMock
      .mockImplementationOnce(() => stuck.promise)
      .mockImplementationOnce(() => queued.promise);
    const registerAgain = jest.fn();

    const releaseA = disablePushRegistrations([{ username: 'alice', accessToken: 'a' }], {
      deleteToken: true,
    });
    const registration = await beginPushRegistration({
      stillWanted: always,
      onReleaseSettled: registerAgain,
      timeoutMs: 20,
    });
    // The wait gave up: the registration proceeds, its retry waits for the releases.
    expect(registration?.token).toBe('fcm-token');
    registration!.finish();
    const releaseB = disablePushRegistrations([{ username: 'bob', accessToken: 'b' }], {
      deleteToken: false,
    });

    stuck.resolve();
    await releaseA;
    await settle();
    expect(registerAgain).not.toHaveBeenCalled();

    queued.resolve();
    await releaseB;
    await settle();
    expect(registerAgain).toHaveBeenCalledTimes(1);
  });

  it('does not ask to register again when the wait did not time out', async () => {
    const quick = deferred();
    saveMock.mockImplementationOnce(() => quick.promise);
    const registerAgain = jest.fn();

    const release = disablePushRegistrations([{ username: 'alice', accessToken: 'a' }], {
      deleteToken: false,
    });
    const pending = beginPushRegistration({
      stillWanted: always,
      onReleaseSettled: registerAgain,
      timeoutMs: 10_000,
    });
    quick.resolve();
    await release;
    const registration = await pending;
    registration!.finish();
    await settle();

    expect(registerAgain).not.toHaveBeenCalled();
  });
});
