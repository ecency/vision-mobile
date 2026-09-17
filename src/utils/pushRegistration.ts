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

// The deregistration in progress, if any. Releases run one after another, and a
// registration waits for them (see waitForPushRelease).
let pendingRelease: Promise<void> = Promise.resolve();

export const PUSH_RELEASE_WAIT_MS = 30 * 1000;

const releasePushRegistrations = async (accounts: PushAccount[], deleteToken: boolean) => {
  let token: string;
  try {
    token = await getMessaging().getToken();
  } catch (err) {
    // No token on this device (no Play Services, simulator, offline): nothing to disable.
    console.warn('Push token unavailable, skipping push deregistration', err);
    return;
  }

  await Promise.all(
    accounts
      .filter((account) => account.username && account.accessToken)
      .map((account) =>
        saveNotificationSetting(
          account.accessToken,
          account.username,
          getPushSystem(),
          0,
          [],
          token,
        ).catch((err) => {
          console.warn('Failed to disable push notifications for', account.username, err);
        }),
      ),
  );

  if (deleteToken) {
    try {
      await getMessaging().deleteToken();
    } catch (err) {
      console.warn('Failed to delete push token', err);
    }
  }
};

/**
 * Turns push off for accounts leaving this device.
 *
 * Each account's row is disabled with the current FCM token. When no account is left
 * on the device, the token itself is deleted afterwards: the backend then gets
 * "unregistered" for it and stops sending, even for a row this call could not
 * disable (for example an account whose access token no longer decrypts). The
 * delete must come after the requests, because deleting first makes the next
 * `getToken()` mint a new token and the requests would disable the wrong rows.
 *
 * Callers do not need to wait: the logout itself should not hang on the network.
 * Registrations wait instead, through waitForPushRelease.
 */
export const disablePushRegistrations = (
  accounts: PushAccount[],
  { deleteToken }: { deleteToken: boolean },
): Promise<void> => {
  const release = pendingRelease
    .catch(() => undefined)
    .then(() => releasePushRegistrations(accounts, deleteToken));
  pendingRelease = release;
  return release;
};

/**
 * Resolves once no deregistration is in progress, or after `timeoutMs`.
 *
 * A login right after the last account logged out would otherwise read the token
 * that the pending release is about to delete, and register a dead token. Waiting
 * lets the release finish, so `getToken()` returns the new token. The timeout keeps a
 * stuck release (a native call that never settles) from blocking registration.
 */
export const waitForPushRelease = async (timeoutMs = PUSH_RELEASE_WAIT_MS) => {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<void>((resolve) => {
    timer = setTimeout(resolve, timeoutMs);
  });
  try {
    await Promise.race([pendingRelease.catch(() => undefined), timeout]);
  } finally {
    clearTimeout(timer);
  }
};
