import { Platform } from 'react-native';
import Config from 'react-native-config';
import { getMessaging } from '@react-native-firebase/messaging';
import { saveNotificationSetting } from '@ecency/sdk';

import { decryptKey } from './crypto';

// Coming back to the foreground re-registers at most this often, so the backend keeps
// seeing a live install even when the app is resumed for days without a cold start.
export const PUSH_REGISTRATION_REFRESH_MS = 24 * 60 * 60 * 1000;

export const getPushSystem = () => `fcm-${Platform.OS}`;

/**
 * Decrypts a stored access token for a push registration request.
 *
 * Stored tokens are encrypted with the app PIN kept in redux (`application.pin`,
 * itself encrypted with PIN_KEY). That is DEFAULT_PIN once an account is migrated,
 * but an account that has not been unlocked since the migration still uses the
 * user's own PIN, so decrypting with DEFAULT_PIN alone fails for it. DEFAULT_PIN
 * stays as the fallback because a fresh login stores its token under DEFAULT_PIN
 * and can reach here before the redux PIN is written.
 */
export const decryptAccessToken = (
  encAccessToken: string | null | undefined,
  encAppPin: string | null | undefined,
): string | undefined => {
  if (!encAccessToken) {
    return undefined;
  }

  const appPin = encAppPin ? decryptKey(encAppPin, Config.PIN_KEY) : undefined;
  const accessToken = appPin ? decryptKey(encAccessToken, appPin) : undefined;
  if (accessToken || appPin === Config.DEFAULT_PIN) {
    return accessToken;
  }

  return decryptKey(encAccessToken, Config.DEFAULT_PIN);
};

export interface PushAccount {
  username: string;
  accessToken?: string;
}

/**
 * Every account signed in on this device, once each. The current account is normally
 * also in `otherAccounts`; its own entry is used when it carries a token (its `local`
 * data is the freshest), and added if it was missing.
 */
export const getSignedInAccounts = (currentAccount: any, otherAccounts: any[] = []) => {
  const accounts = new Map<string, any>();
  otherAccounts.forEach((account) => {
    const username = account?.username || account?.name;
    if (username) {
      accounts.set(username, account);
    }
  });
  if (currentAccount?.name && currentAccount.local?.accessToken) {
    accounts.set(currentAccount.name, currentAccount);
  }

  return Array.from(accounts, ([username, account]) => ({ username, account }));
};

/** Every account signed in on this device, with its decrypted access token. */
export const getPushAccounts = (
  currentAccount: any,
  otherAccounts: any[] = [],
  encAppPin: string | null | undefined,
): PushAccount[] =>
  getSignedInAccounts(currentAccount, otherAccounts).map(({ username, account }) => ({
    username,
    accessToken: decryptAccessToken(account?.local?.accessToken, encAppPin),
  }));

// Deregistrations (releases) run one after another on this chain, and registrations
// wait for it. It never rejects.
let pendingRelease: Promise<void> = Promise.resolve();

// Registrations past their wait whose request has not settled yet. A release waits for
// them before it sends its disable requests.
const registrationsInFlight = new Set<Promise<void>>();

export const PUSH_RELEASE_WAIT_MS = 30 * 1000;

// Cold start and reconnect both register every account; within this window the second
// pass is skipped.
export const PUSH_REGISTRATION_DEDUPE_MS = 60 * 1000;

/** Resolves to true when `promise` settles within `ms`, false otherwise. */
const settlesWithin = async (promise: Promise<unknown>, ms: number): Promise<boolean> => {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<boolean>((resolve) => {
    timer = setTimeout(() => resolve(false), Math.max(ms, 0));
  });
  try {
    return await Promise.race([
      promise.then(
        () => true,
        () => true,
      ),
      timeout,
    ]);
  } finally {
    clearTimeout(timer);
  }
};

interface ReleaseOptions {
  deleteToken: boolean;
  /** Called after the token was deleted, to register the accounts still signed in. */
  onTokenReplaced?: () => void;
  /** How long to wait for registrations already in flight. */
  registrationWaitMs?: number;
}

const releasePushRegistrations = async (
  accounts: PushAccount[],
  { deleteToken, onTokenReplaced, registrationWaitMs = PUSH_RELEASE_WAIT_MS }: ReleaseOptions,
) => {
  // A registration already on its way must land first, or it could switch a departing
  // account back on after its disable.
  await settlesWithin(Promise.all(registrationsInFlight), registrationWaitMs);

  let token: string;
  try {
    token = await getMessaging().getToken();
  } catch (err) {
    // No token on this device (no Play Services, simulator, offline): nothing to disable.
    console.warn('Push token unavailable, skipping push deregistration', err);
    return;
  }

  const disabled = await Promise.all(
    accounts.map((account) => {
      if (!account.username || !account.accessToken) {
        return Promise.resolve(false);
      }
      // Inside then(), so even a synchronous throw becomes this account's failure.
      return Promise.resolve()
        .then(() =>
          saveNotificationSetting(
            account.accessToken,
            account.username,
            getPushSystem(),
            0,
            [],
            token,
          ),
        )
        .then(
          () => true,
          (err) => {
            console.warn('Failed to disable push notifications for', account.username, err);
            return false;
          },
        );
    }),
  );

  // With no account left the token goes. With accounts left it stays, unless a row could
  // not be disabled: deleting the token is then the only way to stop that account's
  // pushes, and the accounts still signed in register again with the new token.
  if (!deleteToken && disabled.every(Boolean)) {
    return;
  }

  try {
    await getMessaging().deleteToken();
  } catch (err) {
    console.warn('Failed to delete push token', err);
    return;
  }

  if (onTokenReplaced) {
    try {
      onTokenReplaced();
    } catch (err) {
      console.warn('Failed to register after replacing the push token', err);
    }
  }
};

/**
 * Turns push off for accounts leaving this device.
 *
 * Each account's row is disabled with the current FCM token, after any registration
 * already in flight has landed. The token is then deleted when no account is left, or
 * when a row could not be disabled: the backend then gets "unregistered" for it and
 * stops sending. The delete must come after the requests, because deleting first makes
 * the next `getToken()` mint a new token and the requests would disable the wrong rows.
 *
 * Callers do not need to wait: the logout itself should not hang on the network.
 * Registrations wait instead, through beginPushRegistration.
 */
export const disablePushRegistrations = (
  accounts: PushAccount[],
  options: ReleaseOptions,
): Promise<void> => {
  const release = pendingRelease
    .then(() => releasePushRegistrations(accounts, options))
    // Never rejects, so the chain stays usable for later releases and registrations.
    .catch((err) => {
      console.warn('Push deregistration failed', err);
    });
  pendingRelease = release;
  return release;
};

/**
 * Resolves to true once no deregistration is in progress, or to false after `timeoutMs`.
 * Releases queued while it waits count too: it returns only when the chain has stopped
 * growing.
 */
export const waitForPushRelease = async (timeoutMs = PUSH_RELEASE_WAIT_MS): Promise<boolean> => {
  const release = pendingRelease;
  const startedAt = Date.now();
  if (!(await settlesWithin(release, timeoutMs))) {
    return false;
  }
  return release === pendingRelease
    ? true
    : waitForPushRelease(timeoutMs - (Date.now() - startedAt));
};

const whenReleasesSettle = async (): Promise<void> => {
  const release = pendingRelease;
  await release;
  if (release !== pendingRelease) {
    await whenReleasesSettle();
  }
};

export interface PushRegistration {
  token: string;
  /** Call once the registration request has settled, whatever its outcome. */
  finish: () => void;
}

/**
 * Starts a registration: waits for deregistrations, checks the account is still wanted,
 * then reads the device token.
 *
 * Resolves to null when `stillWanted()` is false after the wait (the account left while
 * it waited). Otherwise the caller sends its request and calls `finish()`; releases that
 * start meanwhile wait for it before disabling anything.
 *
 * When the wait times out, releases are still running. Their disable requests may reach
 * the backend after this registration, and a release may replace the token it read.
 * Neither can be recalled, so `onReleaseSettled` is called once every release has
 * settled, for the caller to register again.
 */
export const beginPushRegistration = async ({
  stillWanted,
  onReleaseSettled,
  timeoutMs = PUSH_RELEASE_WAIT_MS,
}: {
  stillWanted: () => boolean;
  onReleaseSettled?: () => void;
  timeoutMs?: number;
}): Promise<PushRegistration | null> => {
  const settled = await waitForPushRelease(timeoutMs);
  if (!stillWanted()) {
    return null;
  }

  let resolveInFlight: () => void = () => undefined;
  const inFlight = new Promise<void>((resolve) => {
    resolveInFlight = resolve;
  });
  registrationsInFlight.add(inFlight);
  const finish = () => {
    registrationsInFlight.delete(inFlight);
    resolveInFlight();
  };

  if (!settled && onReleaseSettled) {
    whenReleasesSettle().then(() => {
      try {
        onReleaseSettled();
      } catch (err) {
        console.warn('Failed to register again after push deregistration', err);
      }
    });
  }

  try {
    const token = await getMessaging().getToken();
    return { token, finish };
  } catch (err) {
    finish();
    throw err;
  }
};
