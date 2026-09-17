import { PrivateKey } from '@ecency/sdk';
import Config from 'react-native-config';
import get from 'lodash/get';

import * as Sentry from '@sentry/react-native';
import {
  getAccountFullQueryOptions,
  getAccountRcQueryOptions,
  getMutedUsersQueryOptions,
  hsTokenRenew,
} from '@ecency/sdk';
import { getDigitPinCode } from './hive';
import { getQueryClient } from '../queries';
import { fetchUnreadActivityCount } from '../queries/unreadActivityCount';
import { getPointsSummary } from '../ecency/ePoint';
import {
  setUserData,
  setAuthStatus,
  updateUserData,
  updateCurrentUsername,
  getUserData,
  getUserDataWithUsername,
  setSCAccount,
  getSCAccount,
  setPinCode,
} from '../../storage/storage';
import { encryptKey, decryptKey } from '../../utils/crypto';
import hsApi from './hivesignerAPI';
import { delay } from '../../utils/editor';

// Constants
import AUTH_TYPE from '../../constants/authType';
import { makeHsCode } from '../../utils/hive-signer-helper';
import { getAvatar, getName } from '../../utils/user';

const fetchAccount = async (username: string) => {
  const queryClient = getQueryClient();
  const account = await queryClient.fetchQuery(getAccountFullQueryOptions(username));
  let rcAccounts: any[] = [];
  try {
    rcAccounts = await queryClient.fetchQuery(getAccountRcQueryOptions(username));
  } catch {
    rcAccounts = [];
  }

  if (!account) {
    return null;
  }

  // Parse profile from posting_json_metadata if not already provided by SDK
  let profile: Record<string, any> = account.profile || {};

  if (!profile || Object.keys(profile).length === 0) {
    try {
      const raw = account.posting_json_metadata;
      const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
      if (parsed && typeof parsed === 'object') {
        profile = parsed.profile || {};
      }
    } catch (error) {
      profile = {};
    }
  }

  const resolvedName = account.name || (account as any).username || username;

  return {
    ...account,
    name: resolvedName,
    profile,
    avatar: getAvatar(profile),
    display_name: getName(profile),
    username: resolvedName,
    rc_manabar: rcAccounts?.[0]?.rc_manabar,
    max_rc: rcAccounts?.[0]?.max_rc,
    vp_manabar: account.voting_manabar,
  };
};

const getSCAccessToken = async (code: string, retriesCount = 3, delayMs = 200): Promise<any> => {
  try {
    return await hsTokenRenew(code);
  } catch (error) {
    if (retriesCount > 0) {
      await delay(delayMs);
      return getSCAccessToken(code, retriesCount - 1, delayMs * 2);
    }
    console.warn('failed to refresh token');
    Sentry.captureException(error);
    throw error;
  }
};

export const login = async (username: string, password: string) => {
  let _keyMatched = false;
  let avatar = '';
  let authType = '';
  // Get user account data from HIVE Blockchain
  const account: any = await fetchAccount(username);

  if (!account) {
    return Promise.reject(new Error('auth.invalid_username'));
  }

  // Public keys of user
  const publicKeys = {
    activeKey: get(account, 'active.key_auths', []).map((x: any) => x[0]),
    memoKey: [get(account, 'memo_key', '')],
    ownerKey: get(account, 'owner.key_auths', []).map((x: any) => x[0]),
    postingKey: get(account, 'posting.key_auths', []).map((x: any) => x[0]),
  };

  // // Set private keys of user
  const privateKeys = getPrivateKeys(username, password);

  // Check all keys
  Object.keys(publicKeys).forEach((pubKey) => {
    const _genPublicKey = (privateKeys as any)[pubKey].createPublic().toString();
    if ((publicKeys as any)[pubKey].some((key: string) => key === _genPublicKey)) {
      _keyMatched = true;
      if (privateKeys.isMasterKey) {
        authType = AUTH_TYPE.MASTER_KEY;
      } else {
        authType = pubKey;
      }
    }
  });

  if (!_keyMatched) {
    return Promise.reject(new Error('auth.invalid_credentials'));
  }

  // generate access token
  const signerPrivateKey = privateKeys.ownerKey || privateKeys.activeKey || privateKeys.postingKey;
  const code = makeHsCode(account.name, signerPrivateKey);
  const scTokens = await getSCAccessToken(code);

  // fetch optional account data;
  try {
    const queryClient = getQueryClient();
    const accessToken = scTokens?.access_token || '';
    account.unread_activity_count = await fetchUnreadActivityCount(account.username, accessToken);
    account.pointsSummary = await getPointsSummary(account.username);

    // Fetch muted users using SDK query
    account.mutes = await queryClient.fetchQuery(getMutedUsersQueryOptions(account.username));
  } catch (err) {
    console.warn('Optional user data fetch failed, account can still function without them', err);
  }

  const { profile } = account;
  avatar = profile?.profile_image || account.avatar || '';

  const userData = {
    username,
    avatar,
    authType,
    masterKey: '',
    postingKey: '',
    activeKey: '',
    memoKey: '',
    accessToken: '',
  };

  const resData = {
    pinCode: Config.DEFAULT_PIN,
    password,
    accessToken: get(scTokens, 'access_token', ''),
  };
  const updatedUserData = getUpdatedUserData(userData, resData);

  account.local = updatedUserData;
  account.local.avatar = avatar;

  const authData = {
    isLoggedIn: true,
    currentUsername: username,
  };
  await setAuthStatus(authData);
  await setSCAccount(scTokens);

  // Save user data to Realm DB
  await setUserData(account.local);
  await updateCurrentUsername(account.name);
  return {
    ...account,
    password,
  };
};

export const loginWithSC2 = async (code: string) => {
  try {
    const scTokens = await getSCAccessToken(code);
    hsApi.setAccessToken(get(scTokens, 'access_token', ''));
    const scAccount = await hsApi.me();

    // NOTE: hsApi account data is missing fields like account.username,
    // so we fetch full account data via SDK.
    const account: any = await fetchAccount(scAccount.account.name);
    if (!account) {
      throw new Error('auth.invalid_username');
    }
    let avatar = '';

    try {
      const queryClient = getQueryClient();
      const accessToken = scTokens ? scTokens.access_token : '';
      account.unread_activity_count = await fetchUnreadActivityCount(account.username, accessToken);
      account.pointsSummary = await getPointsSummary(account.username);

      // Fetch muted users using SDK query
      account.mutes = await queryClient.fetchQuery(getMutedUsersQueryOptions(account.username));
    } catch (err) {
      console.warn('Optional user data fetch failed, account can still function without them', err);
    }

    const { profile } = account;
    avatar = profile?.profile_image || account.avatar || '';

    const userData = {
      username: account.name,
      avatar,
      authType: AUTH_TYPE.STEEM_CONNECT,
      masterKey: '',
      postingKey: '',
      activeKey: '',
      memoKey: '',
      accessToken: '',
    };

    const resData = {
      pinCode: Config.DEFAULT_PIN,
      accessToken: get(scTokens, 'access_token', ''),
    };
    const updatedUserData = getUpdatedUserData(userData, resData);

    account.local = updatedUserData;
    account.local.avatar = avatar;

    await setUserData(account.local);

    await updateCurrentUsername(account.name);
    const authData = {
      isLoggedIn: true,
      currentUsername: account.name,
    };
    await setAuthStatus(authData);
    await setSCAccount(scTokens);

    return {
      ...account,
      accessToken: get(scTokens, 'access_token', ''),
    };
  } catch (err) {
    Sentry.captureException(err);
    throw err;
  }
};

/**
 * Login using tokens transferred from the web app via QR code.
 * Stores the session as a HiveSigner (steemConnect) auth type.
 */
export const loginWithAuthTransfer = async (
  username: string,
  accessToken: string,
  refreshToken: string,
  expiresIn: number,
) => {
  try {
    const account: any = await fetchAccount(username);
    if (!account) {
      throw new Error('auth.invalid_username');
    }

    let avatar = '';
    try {
      const queryClient = getQueryClient();
      account.unread_activity_count = await fetchUnreadActivityCount(username, accessToken);
      account.pointsSummary = await getPointsSummary(username);
      account.mutes = await queryClient.fetchQuery(getMutedUsersQueryOptions(username));
    } catch (err) {
      console.warn('Optional user data fetch failed during auth transfer', err);
    }

    const { profile } = account;
    avatar = profile?.profile_image || account.avatar || '';

    const userData = {
      username: account.name,
      avatar,
      authType: AUTH_TYPE.STEEM_CONNECT,
      masterKey: '',
      postingKey: '',
      activeKey: '',
      memoKey: '',
      accessToken: '',
    };

    const resData = {
      pinCode: Config.DEFAULT_PIN,
      accessToken,
    };
    const updatedUserData = getUpdatedUserData(userData, resData);

    account.local = updatedUserData;
    account.local.avatar = avatar;

    await setUserData(account.local);
    await updateCurrentUsername(account.name);

    const authData = {
      isLoggedIn: true,
      currentUsername: account.name,
    };
    await setAuthStatus(authData);

    // Store SC tokens for refresh
    await setSCAccount({
      username: account.name,
      access_token: accessToken,
      refresh_token: refreshToken,
      expires_in: expiresIn,
    });

    return {
      ...account,
      accessToken,
    };
  } catch (err) {
    Sentry.captureException(err);
    throw err;
  }
};

export const loginWithHiveAuth = async (
  hsCode: any,
  hiveAuthKey: any,
  hiveAuthExpiry: any,
  hiveAuthToken?: any,
) => {
  try {
    const scTokens = await getSCAccessToken(hsCode);

    hsApi.setAccessToken(get(scTokens, 'access_token', ''));
    const scAccount = await hsApi.me();

    // NOTE: hsApi account data is missing fields like account.username,
    // so we fetch full account data via SDK.
    const account: any = await fetchAccount(scAccount.account.name);
    if (!account) {
      throw new Error('auth.invalid_username');
    }
    let avatar = '';

    try {
      const queryClient = getQueryClient();
      const accessToken = scTokens ? scTokens.access_token : '';
      account.unread_activity_count = await fetchUnreadActivityCount(account.username, accessToken);
      account.pointsSummary = await getPointsSummary(account.username);

      // Fetch muted users using SDK query
      account.mutes = await queryClient.fetchQuery(getMutedUsersQueryOptions(account.username));
    } catch (err) {
      console.warn('Optional user data fetch failed, account can still function without them', err);
    }

    const { profile } = account;
    avatar = profile?.profile_image || account.avatar || '';

    const userData = {
      username: account.name,
      avatar,
      authType: AUTH_TYPE.HIVE_AUTH,
      masterKey: '',
      postingKey: '',
      activeKey: '',
      memoKey: '',
      accessToken: '',
      hiveAuthKey: '',
      hiveAuthExpiry: 0,
      hiveAuthToken: '',
    };

    const resData = {
      pinCode: Config.DEFAULT_PIN,
      accessToken: get(scTokens, 'access_token', ''),
      hiveAuthKey,
      hiveAuthExpiry,
      hiveAuthToken: hiveAuthToken || '',
    };
    const updatedUserData = getUpdatedUserData(userData, resData);

    account.local = updatedUserData;
    account.local.avatar = avatar;

    await setUserData(account.local);

    await updateCurrentUsername(account.name);
    const authData = {
      isLoggedIn: true,
      currentUsername: account.name,
    };
    await setAuthStatus(authData);
    await setSCAccount(scTokens);

    return {
      ...account,
      accessToken: get(scTokens, 'access_token', ''),
    };
  } catch (err) {
    Sentry.captureException(err);
    throw err;
  }
};

export const updatePinCode = (data: any) =>
  new Promise((resolve, reject) => {
    let currentUser: any = null;
    try {
      setPinCode(get(data, 'pinCode'));
      getUserData()
        .then(async (users) => {
          const _onDecryptError = () => {
            throw new Error('Decryption failed');
          };
          if (users && users.length > 0) {
            users.forEach((userData: any) => {
              if (
                get(userData, 'authType', '') === AUTH_TYPE.MASTER_KEY ||
                get(userData, 'authType', '') === AUTH_TYPE.ACTIVE_KEY ||
                get(userData, 'authType', '') === AUTH_TYPE.MEMO_KEY ||
                get(userData, 'authType', '') === AUTH_TYPE.POSTING_KEY
              ) {
                const publicKey =
                  get(userData, 'masterKey') ||
                  get(userData, 'activeKey') ||
                  get(userData, 'memoKey') ||
                  get(userData, 'postingKey');

                const password = decryptKey(
                  publicKey,
                  get(data, 'oldPinCode', ''),
                  _onDecryptError,
                );
                if (password === undefined) {
                  return;
                }

                data.password = password;
              } else if (get(userData, 'authType', '') === AUTH_TYPE.STEEM_CONNECT) {
                const accessToken = decryptKey(
                  get(userData, 'accessToken'),
                  get(data, 'oldPinCode', ''),
                  _onDecryptError,
                );
                if (accessToken === undefined) {
                  return;
                }
                data.accessToken = accessToken;
              } else if (get(userData, 'authType', '') === AUTH_TYPE.HIVE_AUTH) {
                const accessToken = decryptKey(
                  get(userData, 'accessToken'),
                  get(data, 'oldPinCode', ''),
                  _onDecryptError,
                );
                const hiveAuthKey = decryptKey(
                  get(userData, 'hiveAuthKey'),
                  get(data, 'oldPinCode', ''),
                  _onDecryptError,
                );
                const hiveAuthToken = get(userData, 'hiveAuthToken')
                  ? decryptKey(
                      get(userData, 'hiveAuthToken'),
                      get(data, 'oldPinCode', ''),
                      _onDecryptError,
                    )
                  : '';

                if (accessToken === undefined || hiveAuthKey === undefined) {
                  return;
                }
                data.accessToken = accessToken;
                data.hiveAuthKey = hiveAuthKey;
                data.hiveAuthToken = hiveAuthToken || '';
              }
              const updatedUserData = getUpdatedUserData(userData, data);
              updateUserData(updatedUserData);
              if (userData.username === data.username) {
                currentUser = updatedUserData;
              }
            });
          }
          // Resolve even when there are no users so an awaiting caller cannot hang.
          resolve(currentUser);
        })
        .catch((err) => {
          reject(err);
        });
    } catch (error) {
      reject((error as any).message);
    }
  });

export const refreshSCToken = async (userData: any, pinCode: string | undefined) => {
  const scAccount = await getSCAccount(userData.username);

  if (!scAccount || !scAccount.refreshToken) {
    console.warn('No SC account or refresh token found, keeping existing access token');
    return get(userData, 'accessToken', '');
  }

  const now = new Date().getTime();
  const expireDate = new Date(scAccount.expireDate).getTime();

  try {
    const newSCAccountData = await getSCAccessToken(scAccount.refreshToken);

    await setSCAccount(newSCAccountData);
    const accessToken = newSCAccountData.access_token;
    const encryptedAccessToken = encryptKey(accessToken, pinCode!);
    await updateUserData({
      ...userData,
      accessToken: encryptedAccessToken,
    });
    return encryptedAccessToken;
  } catch (error) {
    if (now > expireDate) {
      throw error;
    } else {
      console.warn('token failed to refresh but current token is still valid');
      return get(userData, 'accessToken', '');
    }
  }
};

/**
 * Persists updated HiveAuth session data (token and expiry) to realm storage.
 * Called after re-authentication so subsequent broadcasts can reuse the session.
 */
export const updateHiveAuthSession = async (
  username: string,
  pinCode: string,
  token: string,
  expiry: number,
) => {
  const users = await getUserDataWithUsername(username);
  if (!users || !users[0]) {
    console.warn('[HiveAuth] updateHiveAuthSession: no user data found for', username);
    return;
  }
  const userData = users[0];
  await updateUserData({
    ...userData,
    hiveAuthToken: token ? encryptKey(token, pinCode) : userData.hiveAuthToken,
    hiveAuthExpiry: expiry ?? userData.hiveAuthExpiry,
  });
};

export const switchAccount = (username: string) =>
  new Promise((resolve, reject) => {
    fetchAccount(username)
      .then((account) => {
        if (!account) {
          reject(new Error('auth.invalid_username'));
          return;
        }
        updateCurrentUsername(username)
          .then(() => {
            resolve(account);
          })
          .catch(() => {
            reject(new Error('auth.unknow_error'));
          });
      })
      .catch(() => {
        reject(new Error('auth.unknow_error'));
      });
  });

export const getPrivateKeys = (username: string, password: string) => {
  try {
    return {
      activeKey: PrivateKey.from(password),
      memoKey: PrivateKey.from(password),
      ownerKey: PrivateKey.from(password),
      postingKey: PrivateKey.from(password),
      isMasterKey: false,
    };
  } catch (e) {
    return {
      activeKey: PrivateKey.fromLogin(username, password, 'active'),
      memoKey: PrivateKey.fromLogin(username, password, 'memo'),
      ownerKey: PrivateKey.fromLogin(username, password, 'owner'),
      postingKey: PrivateKey.fromLogin(username, password, 'posting'),
      isMasterKey: true,
    };
  }
};

const getUpdatedUserData = (userData: any, data: any) => {
  const privateKeys = getPrivateKeys(get(userData, 'username', ''), get(data, 'password'));

  return {
    username: get(userData, 'username', ''),
    authType: get(userData, 'authType', ''),
    accessToken: encryptKey(data.accessToken, get(data, 'pinCode')),
    hiveAuthExpiry: get(data, 'hiveAuthExpiry', 0),

    masterKey:
      get(userData, 'authType', '') === AUTH_TYPE.MASTER_KEY
        ? encryptKey(data.password, get(data, 'pinCode'))
        : get(userData, 'masterKey', ''),
    postingKey:
      get(userData, 'authType', '') === AUTH_TYPE.MASTER_KEY ||
      get(userData, 'authType', '') === AUTH_TYPE.POSTING_KEY
        ? encryptKey(get(privateKeys, 'postingKey', '').toString(), get(data, 'pinCode'))
        : get(userData, 'postingKey', ''),
    activeKey:
      get(userData, 'authType', '') === AUTH_TYPE.MASTER_KEY ||
      get(userData, 'authType', '') === AUTH_TYPE.ACTIVE_KEY
        ? encryptKey(get(privateKeys, 'activeKey', '').toString(), get(data, 'pinCode'))
        : get(userData, 'activeKey', ''),
    memoKey:
      get(userData, 'authType', '') === AUTH_TYPE.MASTER_KEY ||
      get(userData, 'authType', '') === AUTH_TYPE.MEMO_KEY
        ? encryptKey(get(privateKeys, 'memoKey', '').toString(), get(data, 'pinCode'))
        : get(userData, 'memoKey', ''),
    ownerKey:
      get(userData, 'authType', '') === AUTH_TYPE.MASTER_KEY ||
      get(userData, 'authType', '') === AUTH_TYPE.OWNER_KEY
        ? encryptKey(get(privateKeys, 'ownerKey', '').toString(), get(data, 'pinCode'))
        : get(userData, 'ownerKey', ''),
    hiveAuthKey:
      get(userData, 'authType', '') === AUTH_TYPE.HIVE_AUTH
        ? encryptKey(data.hiveAuthKey, get(data, 'pinCode'))
        : get(userData, 'hiveAuthKey', ''),
    hiveAuthToken:
      get(userData, 'authType', '') === AUTH_TYPE.HIVE_AUTH && data.hiveAuthToken
        ? encryptKey(data.hiveAuthToken, get(data, 'pinCode'))
        : get(userData, 'hiveAuthToken', ''),
  };
};

export const getUpdatedUserKeys = async (currentAccountData: any, data: any) => {
  let loginFlag = false;
  // Get user account data from HIVE Blockchain
  // const account = await getUser(username);
  // Public keys of user
  const publicKeys = {
    activeKey: get(currentAccountData, 'active.key_auths', []).map((x: any) => x[0]),
    memoKey: [get(currentAccountData, 'memo_key', '')],
    ownerKey: get(currentAccountData, 'owner.key_auths', []).map((x: any) => x[0]),
    postingKey: get(currentAccountData, 'posting.key_auths', []).map((x: any) => x[0]),
  };

  // // Set private keys of user
  const privateKeys = getPrivateKeys(data.username, data.password);

  // Check all keys and set authType
  let authType = '';
  Object.keys(publicKeys).forEach((pubKey) => {
    const _genPublicKey = (privateKeys as any)[pubKey].createPublic().toString();
    if ((publicKeys as any)[pubKey].some((key: string) => key === _genPublicKey)) {
      loginFlag = true;
      if (privateKeys.isMasterKey) {
        authType = AUTH_TYPE.MASTER_KEY;
      } else {
        authType = pubKey;
      }
    }
  });

  if (loginFlag) {
    const _prevAuthType = currentAccountData.authType;

    const _localData = {
      ...currentAccountData.local,
      authType,
    };
    const _userData = getUpdatedUserData(_localData, data);

    // sustain appropriate authType
    if (_prevAuthType === AUTH_TYPE.STEEM_CONNECT || _prevAuthType === AUTH_TYPE.HIVE_AUTH) {
      _userData.authType = _prevAuthType;
    }

    await setUserData(_userData);
    currentAccountData.local = _userData;

    return currentAccountData;
  }
  return Promise.reject(new Error('auth.invalid_credentials'));
};

/**
 * This migration snippet is used to update access token for users logged in using masterKey
 * accessToken is required for all ecency api calls even for non hivesigner users.
 */
export const migrateToMasterKeyWithAccessToken = async (
  account: any,
  userData: any,
  pinHash: string,
) => {
  // get username, user local data from account;
  const username = account.name;

  // decrypt password from local data
  const pinCode = getDigitPinCode(pinHash)!;
  const password = decryptKey(
    userData.masterKey || userData.activeKey || userData.postingKey || userData.memoKey,
    pinCode,
  )!;

  // Set private keys of user
  const privateKeys = getPrivateKeys(username, password);

  const signerPrivateKey =
    privateKeys.ownerKey || privateKeys.activeKey || privateKeys.postingKey || privateKeys.memoKey;
  const code = makeHsCode(account.name, signerPrivateKey);
  const scTokens = await getSCAccessToken(code);

  await setSCAccount(scTokens);
  const accessToken = scTokens.access_token;

  // update data
  const localData = {
    ...userData,
    accessToken: encryptKey(accessToken, pinCode),
  };
  // update realm
  await updateUserData(localData);

  // return account with update local data
  account.local = localData;
  return account;
};
