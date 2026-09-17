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
 * Every account signed in on this device, with its decrypted access token. The
 * current account is normally also in `otherAccounts`; its own entry is used when it
 * carries a token (its `local` data is the freshest), and added if it was missing.
 */
export const getPushAccounts = (
  currentAccount: any,
  otherAccounts: any[] = [],
  encAppPin: string | null | undefined,
): PushAccount[] => {
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

  return Array.from(accounts, ([username, account]) => ({
    username,
    accessToken: decryptAccessToken(account?.local?.accessToken, encAppPin),
  }));
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
 */
export const disablePushRegistrations = async (
  accounts: PushAccount[],
  { deleteToken }: { deleteToken: boolean },
) => {
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
