import { Appearance } from 'react-native';
import Config from 'react-native-config';

// Constants
import { SheetManager } from 'react-native-actions-sheet';
import { isArray } from 'lodash';
import { getMutedUsersQueryOptions } from '@ecency/sdk';
import THEME_OPTIONS from '../constants/options/theme';
import { getPointsSummary } from '../providers/ecency/ePoint';
import {
  login,
  loginWithSC2,
  migrateToMasterKeyWithAccessToken,
  refreshSCToken,
  updatePinCode,
} from '../providers/hive/auth';
import { getDigitPinCode } from '../providers/hive/hive';
import { fetchUnreadActivityCount, getQueryClient } from '../providers/queries';
import AUTH_TYPE from '../constants/authType';

// Services
import { getSCAccount, getSettings, removeUserData } from '../storage/storage';
import { updateCurrentAccount, updateOtherAccount } from '../redux/actions/accountAction';

import {
  changeNotificationSettings,
  changeAllNotificationSettings,
  setApi,
  setCurrency,
  setLanguage,
  setNsfw,
  isDefaultFooter,
  isPinCodeOpen,
  setColorTheme,
  setSettingsMigrated,
  setPinCode,
  setEncryptedUnlockPin,
  setPostUpvotePercent,
  setCommentUpvotePercent,
  setIsDarkTheme,
} from '../redux/actions/applicationActions';
import { fetchSubscribedCommunities } from '../redux/actions/communitiesAction';
import { setRcOffer, toastNotification } from '../redux/actions/uiAction';
import { decryptKey, encryptKey } from './crypto';
import { delay } from './editor';
import RootNavigation from '../navigation/rootNavigation';
import ROUTES from '../constants/routeNames';
import {
  DEFAULT_FEED_FILTERS,
  DEFAULT_PROFILE_FILTERS,
  DEFAULT_OWN_PROFILE_FILTERS,
} from '../constants/options/filters';
import { SheetNames } from '../navigation/sheets';
import { ProfileToken, TokenType } from '../redux/reducers/walletReducer';
import DEFAULT_ASSETS from '../constants/defaultAssets';

// migrates settings from realm to redux once and do no user realm for settings again;
export const migrateSettings = async (dispatch: any, settingsMigratedV2: boolean) => {
  if (settingsMigratedV2) {
    return;
  }

  // reset certain properties
  dispatch(toastNotification(''));
  dispatch(setRcOffer(false));

  const settings = await getSettings();

  if (settings) {
    const isDarkMode = Appearance.getColorScheme() === 'dark';
    dispatch(setIsDarkTheme(settings.isDarkTheme !== null ? settings.isDarkTheme : isDarkMode));
    dispatch(setColorTheme(THEME_OPTIONS.findIndex((item) => item.value === settings.isDarkTheme)));
    await dispatch(isPinCodeOpen(!!settings.isPinCodeOpen));
    if (settings.language !== '') dispatch(setLanguage(settings.language));
    if (settings.server !== '') dispatch(setApi(settings.server));
    if (settings.upvotePercent !== '') {
      const percent = Number(settings.upvotePercent);
      dispatch(setPostUpvotePercent(percent));
      dispatch(setCommentUpvotePercent(percent));
    }
    if (settings.isDefaultFooter !== '') dispatch(isDefaultFooter(settings.isDefaultFooter)); // TODO: remove as not being used

    if (settings.nsfw !== '') dispatch(setNsfw(settings.nsfw));

    dispatch(setCurrency(settings.currency !== '' ? settings.currency : 'usd'));

    if (settings.notification !== '') {
      dispatch(
        changeNotificationSettings({
          type: 'notification',
          action: settings.notification,
        }),
      );

      dispatch(changeAllNotificationSettings(settings));
    }

    await dispatch(setSettingsMigrated(true));
  }
};

// migrates local user data to use default pin encruption instead of user pin encryption
export const migrateUserEncryption = async (
  dispatch: any,
  currentAccount: any,
  encUserPin: string,
  onFailure: (error: unknown) => void,
) => {
  const oldPinCode = decryptKey(encUserPin, Config.PIN_KEY!);

  if (oldPinCode === undefined || oldPinCode === Config.DEFAULT_PIN) {
    return true;
  }

  let migratedLocal: any;
  try {
    const pinData = {
      pinCode: Config.DEFAULT_PIN,
      username: currentAccount.name,
      oldPinCode,
    };

    migratedLocal = await updatePinCode(pinData);

    // updatePinCode resolves with no record when storage has no matching user.
    // Bail BEFORE mutating PIN/account state so a retry starts from a clean state —
    // otherwise local is nulled and the app PIN is already switched to DEFAULT_PIN,
    // and the user can no longer pass PIN verification on the next unlock.
    if (!migratedLocal) {
      onFailure(new Error('PIN migration produced no account data'));
      return false;
    }

    const _currentAccount = currentAccount;
    _currentAccount.local = migratedLocal;

    dispatch(
      updateCurrentAccount({
        ..._currentAccount,
      }),
    );

    const encryptedPin = encryptKey(Config.DEFAULT_PIN!, Config.PIN_KEY!);
    dispatch(setPinCode(encryptedPin));
  } catch (err) {
    // Re-encryption to DEFAULT_PIN failed. Do NOT mark the migration complete
    // (setEncryptedUnlockPin) below — otherwise the keys stay encrypted with the
    // old PIN while the app expects DEFAULT_PIN, silently locking the user out of
    // signing. Surface the failure (offers re-login) and abort so it can retry on
    // the next unlock.
    console.warn('pin update failure: ', err);
    onFailure(err);
    return false;
  }

  dispatch(setEncryptedUnlockPin(encUserPin));

  // Reuse the freshly re-encrypted record returned by updatePinCode instead of
  // re-reading realm: the AsyncStorage write inside updatePinCode is not awaited,
  // so a disk re-read here can race it and return stale, old-PIN-encrypted keys.
  let _currentAccount = currentAccount;
  _currentAccount.username = _currentAccount.name;
  _currentAccount.local = migratedLocal;

  try {
    const pinHash = encryptKey(Config.DEFAULT_PIN!, Config.PIN_KEY!);
    // migration script for previously mast key based logged in user not having access token
    if (migratedLocal.authType !== AUTH_TYPE.STEEM_CONNECT && migratedLocal.accessToken === '') {
      _currentAccount = await migrateToMasterKeyWithAccessToken(
        _currentAccount,
        migratedLocal,
        pinHash,
      );
    }

    // refresh access token
    const encryptedAccessToken = await refreshSCToken(_currentAccount.local, Config.DEFAULT_PIN);
    _currentAccount.local.accessToken = encryptedAccessToken;
  } catch (error) {
    onFailure(error);
  }

  // get unread notifications
  try {
    const queryClient = getQueryClient();
    const accessToken =
      (_currentAccount?.local?.accessToken
        ? decryptKey(_currentAccount.local.accessToken, Config.DEFAULT_PIN!)
        : '') ?? '';
    // Without a usable access code the helper returns nothing; keep the current count.
    const unreadActivityCount = await fetchUnreadActivityCount(_currentAccount.name, accessToken);
    if (typeof unreadActivityCount === 'number') {
      _currentAccount.unread_activity_count = unreadActivityCount;
    }
    _currentAccount.pointsSummary = await getPointsSummary(_currentAccount.name);

    // Fetch muted users using SDK query
    _currentAccount.mutes = await queryClient.fetchQuery(
      getMutedUsersQueryOptions(_currentAccount.name),
    );
  } catch (err) {
    console.warn('Optional user data fetch failed, account can still function without them', err);
  }

  dispatch(updateCurrentAccount({ ..._currentAccount }));
  dispatch(fetchSubscribedCommunities(_currentAccount.name));
  return true;
};

export const repairUserAccountData = async (
  username: string,
  dispatch: any,
  intl: any,
  accounts: any[],
  pinHash: string,
) => {
  let authData: any[] = [];
  try {
    // clean realm data just in case, to avoid already logged error
    await removeUserData(username);

    // extract key information from otherAccounts if data is available, use key to re-verify account;
    let _userAccount = accounts.find((account) => account.name === username);
    const _authType = _userAccount?.local?.authType;
    if (!_authType) {
      throw new Error('could not recover account data from redux copy');
    }

    // TODO: check if this need to accomodate HIVE_AUTH;
    if (_authType === AUTH_TYPE.STEEM_CONNECT) {
      const _scAccount = await getSCAccount(username);
      if (!_scAccount?.refreshToken) {
        throw new Error('refresh node not present');
      }
      _userAccount = await loginWithSC2(_scAccount.refreshToken);
      console.log('successfully repair hive signer based account data', username);
    }

    // TOOD: handle hive auth account repair
    else if (_authType === AUTH_TYPE.HIVE_AUTH) {
      // get SC data
      // make sure tokens are not already expired
      // repair data
      // if already expired, prompt for relogin
    } else {
      const _encryptedKey = _userAccount.local[_authType];
      const _key = decryptKey(_encryptedKey, getDigitPinCode(pinHash)!);
      if (!_key) {
        throw new Error('Pin decryption failed');
      }
      _userAccount = await login(username, _key);
      console.log('successfully repair key based account data', username);
    }

    dispatch(updateCurrentAccount({ ..._userAccount }));

    // compile authData for return;
    authData = [_userAccount.local];
  } catch (err) {
    // keys data corrupted, ask user to verify login
    await delay(500);

    SheetManager.show(SheetNames.ACTION_MODAL, {
      payload: {
        title: intl.formatMessage({ id: 'alert.warning' }),
        body: intl.formatMessage({ id: 'alert.auth_expired' }),
        buttons: [
          {
            text: intl.formatMessage({ id: 'alert.cancel' }),
            style: 'destructive',
            onPress: () => {
              console.log('cancel pressed');
            },
          },
          {
            text: intl.formatMessage({ id: 'alert.verify' }),
            onPress: () => {
              RootNavigation.navigate({
                name: ROUTES.SCREENS.LOGIN,
                params: { username },
              });
            },
          },
        ],
      },
    });
  }

  return authData;
};

export const repairOtherAccountsData = (accounts: any[], realmAuthData: any, dispatch: any) => {
  accounts.forEach((account) => {
    // otherAccounts entries are keyed by username; account.name can be undefined on some
    // (e.g. HiveSigner) entries, so match realm data on either field.
    const accRealmData = realmAuthData.find(
      (data: any) => data.username === (account.username || account.name),
    );
    if (!account.local?.accessToken && accRealmData) {
      account.local = accRealmData;
      // Backfill name so consumers that read account.name (e.g. notification registration)
      // have a value for entries that were keyed only by username.
      account.name = account.name || accRealmData.username;
      dispatch(updateOtherAccount({ ...account }));
    }
  });
};

export const migrateSelectedTokens = (tokens: any) => {
  if (!isArray(tokens)) {
    // means tokens is using old object formation, covert to array
    const _mapSymbolsToProfileToken = (symbols: string[], type: TokenType) =>
      isArray(symbols)
        ? symbols.map((symbol) => ({
            symbol,
            type,
            meta: { show: true },
          }))
        : [];

    // Legacy spk arrays are intentionally ignored (SPK support removed)
    return [..._mapSymbolsToProfileToken(tokens.engine, TokenType.ENGINE)];
  }

  // check for missing meta entries
  else if (tokens.some((item: ProfileToken) => !item.meta)) {
    // unify tokens to have meta and discard duplicate entries

    const map = new Map();

    tokens.forEach((token: ProfileToken) => {
      const key = `${token.symbol}-${token.type}`;
      const existing = map.get(key);

      // If no existing entry, or existing entry has no meta but current has meta, update
      if (!existing || (!existing.meta && token.meta)) {
        map.set(key, token);
      }
    });

    // Add meta:{show:true} to entries missing meta
    const _tokens = Array.from(map.values()).map((token) => {
      if (!token.meta) {
        return { ...token, meta: { show: true } };
      }
      return token;
    });

    return _tokens;
  }

  return null;
};

const reduxMigrations = {
  0: (state: any) => {
    const { upvotePercent } = state.application;
    state.application.postUpvotePercent = upvotePercent;
    state.application.commentUpvotePercent = upvotePercent;
    state.application.upvotePercent = undefined;
    return state;
  },
  1: (state: any) => {
    state.application.notificationDetails.favoriteNotification = true;
    return state;
  },
  2: (state: any) => {
    state.application.notificationDetails.bookmarkNotification = true;
    return state;
  },
  3: (state: any) => {
    const { drafts } = state.cache;
    const _draftsCollection: Record<string, any> = {};
    if (drafts instanceof Array) {
      drafts.forEach(([key, data]) => {
        if (key && data.body && data.author && data.updated) {
          _draftsCollection[key] = data;
        }
      });
    }
    state.cache.draftsCollection = _draftsCollection;
    delete state.cache.drafts;
    return state;
  },
  4: (state: any) => {
    const { comments } = state.cache;
    const _collection: Record<string, any> = {};
    if (comments instanceof Array) {
      comments.forEach(([key, data]) => {
        if (key && data.body && data.parent_author && data.parent_permlink) {
          _collection[key] = data;
        }
      });
    }
    state.cache.commentsCollection = _collection;
    delete state.cache.comments;
    return state;
  },
  5: (state: any) => {
    state.cache.votesCollection = {};
    return state;
  },
  6: (state: any) => {
    state.application.waveUpvotePercent = state.application.commentUpvotePercent;
    return state;
  },
  7: (state: any) => {
    state.cache.announcementsMeta = {};
    return state;
  },
  8: (state: any) => {
    state.cache.pollVotesCollection = {};
    return state;
  },
  9: (state: any) => {
    state.editor.pollDraftsMap = {};
    return state;
  },
  10: (state: any) => {
    state.customTabs.mainTabs = DEFAULT_FEED_FILTERS;
    return state;
  },
  11: (state: any) => {
    state.cache.proposalsVoteMeta = {};
    return state;
  },
  12: (state: any) => {
    state.application.pin = encryptKey(Config.DEFAULT_PIN!, Config.PIN_KEY!);
    return state;
  },
  13: (state: any) => {
    state.wallet.selectedAssets = state.wallet.selectedCoins || DEFAULT_ASSETS;
    // Fix first asset symbol if it's not POINTS (migration from old data)
    if (state.wallet.selectedAssets[0] && state.wallet.selectedAssets[0].symbol !== 'POINTS') {
      state.wallet.selectedAssets[0].symbol = 'POINTS';
    } // ensuring correct symbol for ecency points

    delete state.wallet.selectedCoins;
    delete state.wallet.coinsData;

    state.cache.claimsCollection = {};

    return state;
  },
  14: (state: any) => {
    // Migrate reply and wave drafts from draftsCollection to replyCache
    state.cache.replyCache = {};

    if (state.cache.draftsCollection) {
      const _replyCache: Record<string, any> = {};
      const _draftsToKeep: Record<string, any> = {};

      Object.keys(state.cache.draftsCollection).forEach((key) => {
        const draft = state.cache.draftsCollection[key];

        // Identify waves: username/ecency.waves
        // Identify replies: username/parentAuthor/parentPermlink (has 2+ slashes)
        const isWaveOrReply =
          key.includes('/ecency.waves') ||
          (key.split('/').length >= 3 && !key.startsWith('DEFAULT_USER_DRAFT_ID_'));

        if (isWaveOrReply) {
          // Move to replyCache
          _replyCache[key] = draft;
        } else {
          // Keep in draftsCollection
          _draftsToKeep[key] = draft;
        }
      });

      state.cache.replyCache = _replyCache;
      state.cache.draftsCollection = _draftsToKeep;
    }

    return state;
  },
  15: (state: any) => {
    // Add 'waves' tab to profile and own-profile custom tabs for existing users
    const _insertWaves = (tabs: any, defaults: any) => {
      if (!tabs || tabs.indexOf('waves') !== -1) {
        return tabs;
      }
      const postsIdx = tabs.indexOf('posts');
      const insertAt = postsIdx !== -1 ? postsIdx + 1 : defaults.indexOf('waves');
      const updated = [...tabs];
      updated.splice(insertAt, 0, 'waves');
      return updated;
    };

    state.customTabs.profileTabs = _insertWaves(
      state.customTabs.profileTabs,
      DEFAULT_PROFILE_FILTERS,
    );
    state.customTabs.ownProfileTabs = _insertWaves(
      state.customTabs.ownProfileTabs,
      DEFAULT_OWN_PROFILE_FILTERS,
    );
    return state;
  },
  16: (state: any) => {
    // Backfill account.globalProps fields added later (votePowerReserveRate,
    // authorRewardCurve, contentConstant, currentHardforkVersion, lastHardfork).
    // Without these, vote estimation can throw on first launch after upgrade
    // until fetchGlobalProperties() refreshes from the chain.
    if (state.account?.globalProps) {
      const gp = state.account.globalProps;
      state.account.globalProps = {
        votePowerReserveRate: 10,
        authorRewardCurve: 'linear',
        contentConstant: 2000000000000,
        currentHardforkVersion: '1.28.0',
        lastHardfork: 28,
        ...gp,
      };
    }
    return state;
  },
  17: (state: any) => {
    // Backfill appRating for users upgrading from a build before the in-app
    // review prompt. autoMergeLevel1 replaces the whole persisted `application`
    // slice on rehydration, so a missing appRating key is NOT defaulted from
    // initialState — and recordAppSession dereferences it on launch, crashing
    // every existing install. (A primitive like imageServer survived this only
    // because its reads are falsy-guarded; an object that's dereferenced is not.)
    if (state.application && !state.application.appRating) {
      state.application.appRating = {
        firstUseTime: null,
        sessionCount: 0,
        hasRequestedReview: false,
      };
    }
    return state;
  },
  18: (state: any) => {
    // Backfill editor.caretMap for users upgrading from a build before per-draft
    // caret persistence existed. autoMergeLevel1 keeps the persisted `editor`
    // slice as-is on rehydration, so the new key is not defaulted from
    // initialState. The reducer and read paths are optional-chaining-guarded, so
    // this only normalizes the shape for consistency with pollDraftsMap (9).
    if (state.editor) {
      state.editor.caretMap = {};
    }
    return state;
  },
  19: (state: any) => {
    // SPK Network support removed: purge persisted SPK/LARYNX/LP entries from
    // wallet.selectedAssets so stale selections don't linger in the wallet list.
    // The symbol check catches legacy entries persisted before the isSpk flag;
    // engine tokens are exempt so a Hive Engine token sharing one of these
    // symbols is never purged.
    if (state.wallet) {
      state.wallet.selectedAssets = (state.wallet.selectedAssets || []).filter(
        (asset: any) =>
          !asset?.isSpk && (asset?.isEngine || !['SPK', 'LARYNX', 'LP'].includes(asset?.symbol)),
      );
    }
    return state;
  },
  20: (state: any) => {
    // Default the scheduled-post-published notification setting ON for existing
    // installs; autoMergeLevel1 keeps the persisted notificationDetails as-is on
    // rehydration, so the new key is never defaulted from initialState (see 17).
    // Also backfill bookmarkNotification: migration 2 covered upgrades, but fresh
    // installs since then started from an initialState that lacked the key.
    if (state.application?.notificationDetails) {
      const details = state.application.notificationDetails;
      details.bookmarkNotification = details.bookmarkNotification ?? true;
      details.scheduledPublishedNotification = details.scheduledPublishedNotification ?? true;
    }
    return state;
  },
  21: (state: any) => {
    // Default the notification types that previously had no push toggle ON for
    // existing installs, matching every other type (and 20 above): autoMergeLevel1
    // keeps persisted notificationDetails as-is, so a new key is never picked up
    // from initialState without a migration.
    if (state.application?.notificationDetails) {
      const details = state.application.notificationDetails;
      details.delegationsNotification = details.delegationsNotification ?? true;
      details.payoutsNotification = details.payoutsNotification ?? true;
      details.accountUpdateNotification = details.accountUpdateNotification ?? true;
      details.weeklyEarningsNotification = details.weeklyEarningsNotification ?? true;
    }
    return state;
  },
  22: (state: any) => {
    // Default the followed-hashtag notification ON for existing installs, like every
    // other type (20 and 21 above): autoMergeLevel1 keeps persisted notificationDetails
    // as-is, so a new key is never picked up from initialState without a migration.
    if (state.application?.notificationDetails) {
      const details = state.application.notificationDetails;
      details.tagsNotification = details.tagsNotification ?? true;
    }
    return state;
  },
};

// Wrap every migration so a throw degrades to "skip this migration" and keep the
// rest of the persisted state, instead of rejecting rehydration — which makes
// redux-persist drop ALL persisted state (silent logout + loss of local drafts).
const safeReduxMigrations = Object.keys(reduxMigrations).reduce((acc, version) => {
  const migrate = (reduxMigrations as any)[version];
  acc[version] = (state: any) => {
    // Migrations mutate `state` in place, so snapshot the (JSON-serializable,
    // persisted) state first and roll back to it on a throw — this is a true
    // "skip", not a partially-applied mutation. The persisted state to migrate is
    // small relative to rehydration cost, and only the versions actually behind run.
    let snapshot;
    try {
      snapshot = state ? JSON.parse(JSON.stringify(state)) : state;
    } catch (_cloneErr) {
      snapshot = state;
    }
    try {
      return migrate(state);
    } catch (err) {
      console.warn(`redux-persist migration ${version} failed, rolling back:`, err);
      return snapshot;
    }
  };
  return acc;
}, {} as Record<string, (state: any) => any>);

export default {
  migrateSettings,
  migrateUserEncryption,
  migrateSelectedTokens,
  reduxMigrations: safeReduxMigrations,
};
