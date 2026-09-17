// Real crypto, so the PIN fallbacks are exercised against actual ciphertext.
import { getMessaging } from '@react-native-firebase/messaging';
import { saveNotificationSetting } from '@ecency/sdk';
import { encryptKey } from './crypto';
import {
  decryptAccessToken,
  disablePushRegistrations,
  getPushAccounts,
  getPushSystem,
  getRegistrationToken,
  getSignedInAccounts,
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

describe('disablePushRegistrations', () => {
  it('disables every account that has a token, with the current device token', async () => {
    await disablePushRegistrations(
      [
        { username: 'alice', accessToken: 'alice-code' },
        { username: 'bob', accessToken: undefined },
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
    let finishAlice: () => void = () => undefined;
    saveMock.mockImplementation(
      (_code: string, username: string) =>
        new Promise<void>((resolve, reject) => {
          if (username === 'alice') {
            finishAlice = () => {
              events.push('alice settled');
              resolve();
            };
          } else {
            events.push('bob settled');
            reject(new Error('Request failed with status 500'));
          }
        }),
    );
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
    await new Promise((resolve) => setImmediate(resolve));
    expect(mockMessaging.deleteToken).not.toHaveBeenCalled();

    finishAlice();
    await done;

    // A failed request does not block the others or the token delete.
    expect(events).toEqual(['bob settled', 'alice settled', 'token deleted']);
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

  it('does not throw when deleting the token fails', async () => {
    mockMessaging.deleteToken.mockRejectedValue(new Error('offline'));

    await expect(disablePushRegistrations([], { deleteToken: true })).resolves.toBeUndefined();
    expect(mockMessaging.deleteToken).toHaveBeenCalledTimes(1);
  });
});

describe('waitForPushRelease', () => {
  const deferred = () => {
    let resolve: () => void = () => undefined;
    const promise = new Promise<void>((r) => {
      resolve = r;
    });
    return { promise, resolve };
  };
  const settle = () => new Promise((resolve) => setImmediate(resolve));

  it('resolves at once when nothing is being released', async () => {
    await expect(waitForPushRelease(10_000)).resolves.toBeUndefined();
  });

  it('waits until a pending release has deleted the token', async () => {
    const request = deferred();
    saveMock.mockImplementation(() => request.promise);
    const events: string[] = [];
    mockMessaging.deleteToken.mockImplementation(async () => {
      events.push('token deleted');
    });

    const release = disablePushRegistrations([{ username: 'alice', accessToken: 'code' }], {
      deleteToken: true,
    });
    const register = async () => {
      await waitForPushRelease(10_000);
      events.push('registration may read');
    };
    const waiting = register();
    await settle();
    expect(events).toEqual([]);

    request.resolve();
    await Promise.all([release, waiting]);
    expect(events).toEqual(['token deleted', 'registration may read']);
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

  it('gives up waiting after the timeout when a release never settles', async () => {
    const stuck = deferred();
    saveMock.mockImplementation(() => stuck.promise);

    const release = disablePushRegistrations([{ username: 'alice', accessToken: 'code' }], {
      deleteToken: true,
    });
    await expect(waitForPushRelease(20)).resolves.toBeUndefined();
    expect(mockMessaging.deleteToken).not.toHaveBeenCalled();

    // Let the release finish so it does not hold up the next test.
    stuck.resolve();
    await release;
  });

  it('keeps the token when a registration read it while the release was stuck', async () => {
    const stuck = deferred();
    saveMock.mockImplementation(() => stuck.promise);

    const release = disablePushRegistrations([{ username: 'alice', accessToken: 'code' }], {
      deleteToken: true,
    });
    await expect(getRegistrationToken(20)).resolves.toBe('fcm-token');

    stuck.resolve();
    await release;
    expect(mockMessaging.deleteToken).not.toHaveBeenCalled();
  });

  it('still deletes the token for a release that starts after a registration', async () => {
    await getRegistrationToken(20);

    await disablePushRegistrations([{ username: 'alice', accessToken: 'code' }], {
      deleteToken: true,
    });
    expect(mockMessaging.deleteToken).toHaveBeenCalledTimes(1);
  });
});
