import { Component } from 'react';
import DeviceInfo from 'react-native-device-info';

import { Platform, Alert, Linking, AppState, NativeEventSubscription } from 'react-native';
import NetInfo from '@react-native-community/netinfo';
import Config from 'react-native-config';
import changeNavigationBarColor from 'react-native-navigation-bar-color';
import { connect } from 'react-redux';
import { injectIntl } from 'react-intl';
import { bindActionCreators } from 'redux';
import { getMessaging } from '@react-native-firebase/messaging';
import VersionNumber from 'react-native-version-number';
import ReceiveSharingIntent from 'react-native-receive-sharing-intent';

// Constants
import { SheetManager } from 'react-native-actions-sheet';
import * as Sentry from '@sentry/react-native';
import {
  getMutedUsersQueryOptions,
  getNotificationsUnreadCountQueryOptions,
  saveNotificationSetting,
} from '@ecency/sdk';

import AUTH_TYPE from '../../../constants/authType';
import ROUTES from '../../../constants/routeNames';
import {
  FCM_FOREGROUND_NOTIFICATION_TYPES,
  WS_NOTIFICATION_TYPES,
} from '../../../constants/notificationTypes';

// Services
import {
  getUserData,
  removeUserData,
  getUserDataWithUsername,
  removePinCode,
  setAuthStatus,
  removeSCAccount,
  setExistUser,
  getLastUpdateCheck,
  setLastUpdateCheck,
} from '../../../storage/storage';
import { getDigitPinCode, getUser } from '../../../providers/hive/hive';
import { getQueryClient } from '../../../providers/queries';
import { getPointsSummary } from '../../../providers/ecency/ePoint';
import {
  migrateToMasterKeyWithAccessToken,
  refreshSCToken,
  switchAccount,
} from '../../../providers/hive/auth';
import { fetchLatestAppVersion } from '../../../providers/github/github';
import RootNavigation from '../../../navigation/rootNavigation';
import {
  bootstrapMattermostSession,
  calculateGlobalUnreadTotal,
  clearMattermostBootstrapCache,
} from '../../../providers/chat/mattermost';
import { setChatApiToken, getChatApiTokenOwner } from '../../../config/chatApi';

// Actions
import {
  updateCurrentAccount,
  updateUnreadActivityCount,
  removeOtherAccount,
  fetchGlobalProperties,
  setPrevLoggedInUsers,
} from '../../../redux/actions/accountAction';
import {
  login,
  setConnectivityStatus,
  setPinCode as savePinCode,
  isRenderRequired,
  isPinCodeOpen,
  setEncryptedUnlockPin,
  setFCMAvailable,
} from '../../../redux/actions/applicationActions';
import {
  setAvatarCacheStamp,
  toastNotification,
  updateActiveBottomTab,
  logout,
  logoutDone,
  updateUnreadChatCount,
} from '../../../redux/actions/uiAction';
import { setFeedPosts, setInitPosts } from '../../../redux/actions/postsAction';
import { fetchCoinQuotes } from '../../../redux/actions/walletActions';

import { decryptKey, encryptKey } from '../../../utils/crypto';

import parseVersionNumber from '../../../utils/parseVersionNumber';
import { setMomentLocale } from '../../../utils/time';
import { purgeExpiredCache } from '../../../redux/actions/cacheActions';
import {
  PUSH_REGISTRATION_REFRESH_MS,
  decryptAccessToken,
  disablePushRegistrations,
  getPushSystem,
} from '../../../utils/pushRegistration';
import { captureException, captureMessage } from '../../../utils/sentryUtils';
import { fetchSubscribedCommunities } from '../../../redux/actions/communitiesAction';
import MigrationHelpers, {
  repairOtherAccountsData,
  repairUserAccountData,
} from '../../../utils/migrationHelpers';
import { autoDetectLocale } from '../../../utils/autoLocale';
import { SheetNames } from '../../../navigation/sheets';
import {
  selectCurrentAccount,
  selectIsLoggedIn,
  selectIsDarkTheme,
  selectLanguage,
  selectPin,
  selectIsPinCodeOpen,
  selectOtherAccounts,
  selectIsConnected,
  selectPrevLoggedInUsers,
  selectNotificationDetails,
  selectEncUnlockPin,
  selectIsNotificationOpen,
  selectApi,
  selectSettingsMigratedV2,
  selectIsGlobalRenderRequired,
  selectLastUpdateCheck,
  selectCurrentAccountUnreadActivityCount,
} from '../../../redux/selectors';

let firebaseOnMessageListener: any = null;
let firebaseTokenRefreshListener: (() => void) | null = null;
let appStateSub: NativeEventSubscription | null = null;

class ApplicationContainer extends Component<any, any> {
  netListener: any;

  _pinCodeTimer: any = null;

  _notificationWs: WebSocket | null = null;

  _notificationUsername: string | null = null;

  _wsReconnectTimer: any = null;

  _wsReconnectAttempts: number = 0;

  _fcmAvailable: boolean | null = null; // Cache FCM availability check

  _lastPushRegistration = 0;

  constructor(props: any) {
    super(props);
    this.state = {
      isRenderRequire: true,
      // isIos: Platform.OS !== 'android',
      appState: AppState.currentState,
      foregroundNotificationData: null,
    };
  }

  componentDidMount = async () => {
    const { dispatch } = this.props;
    this._setNetworkListener();

    appStateSub = AppState.addEventListener('change', this._handleAppStateChange);

    // Only create FCM listener if FCM is available
    const fcmAvailable = await this._checkFCMAvailability();
    if (fcmAvailable) {
      this._createPushListener();
      console.log('Using FCM for foreground notifications');
    } else {
      console.log('FCM not available - will use WebSocket fallback when user logs in');
    }
    this._createTokenRefreshListener();

    // set avatar cache stamp to invalidate previous session avatars
    dispatch(setAvatarCacheStamp(new Date().getTime()));

    setMomentLocale();
    this._fetchApp();

    ReceiveSharingIntent.getReceivedFiles(
      async (files: any) => {
        try {
          const target = await SheetManager.show(SheetNames.SHARE_INTENT, {
            payload: { files },
          });
          // Default to blog editor when user dismisses the sheet (swipe / backdrop),
          // so an accidental dismiss doesn't silently lose the shared content.
          const choice = target || 'blog';
          if (choice === 'blog') {
            RootNavigation.navigate({
              name: ROUTES.SCREENS.EDITOR,
              params: { hasSharedIntent: true, files },
            });
          } else if (choice === 'wave') {
            SheetManager.show(SheetNames.QUICK_POST, {
              payload: { mode: 'wave', files },
            });
          }
        } catch (e) {
          console.log('share intent sheet error :>> ', e);
        }
        ReceiveSharingIntent.clearReceivedFiles();
      },
      (error: any) => {
        console.log('error :>> ', error);
      },
    );
  };

  componentDidUpdate(prevProps: any) {
    const { isGlobalRenderRequired, dispatch, currentAccount } = this.props;

    // A login (by any method) or an account switch makes a different account current.
    // Register it right away instead of waiting for the next cold start.
    if (currentAccount?.name && currentAccount.name !== prevProps.currentAccount?.name) {
      this._registerAccountForNotifications(currentAccount);
    }

    if (isGlobalRenderRequired !== prevProps.isGlobalRenderRequired && isGlobalRenderRequired) {
      this.setState(
        {
          isRenderRequire: false,
        },
        () =>
          this.setState({
            isRenderRequire: true,
          }),
      );
      dispatch(isRenderRequired(false));
    }
  }

  componentWillUnmount() {
    const { isPinCodeOpen: _isPinCodeOpen } = this.props;

    // TOOD: listen for back press and cancel all pending api requests;

    if (appStateSub) {
      appStateSub.remove();
    }

    if (_isPinCodeOpen) {
      clearTimeout(this._pinCodeTimer);
    }

    if (firebaseOnMessageListener) {
      firebaseOnMessageListener();
    }

    if (firebaseTokenRefreshListener) {
      firebaseTokenRefreshListener();
      firebaseTokenRefreshListener = null;
    }

    this._disconnectNotificationServer();

    this.netListener();
  }

  _setNetworkListener = () => {
    this.netListener = NetInfo.addEventListener((state) => {
      const { isConnected, dispatch } = this.props;
      if (state.isConnected !== isConnected) {
        dispatch(setConnectivityStatus(state.isConnected));
        this._fetchApp();
      }
    });
  };

  _compareAndPromptForUpdate = async () => {
    try {
      const recheckInterval = 48 * 3600 * 1000; // 2 days
      const { intl } = this.props;

      const lastUpdateCheck = await getLastUpdateCheck();

      if (lastUpdateCheck) {
        const timeDiff = new Date().getTime() - lastUpdateCheck;
        if (timeDiff < recheckInterval) {
          return;
        }
      }

      const remoteVersion = await fetchLatestAppVersion();

      if (parseVersionNumber(remoteVersion) > parseVersionNumber(VersionNumber.appVersion)) {
        const action = await SheetManager.show(SheetNames.ACTION_MODAL, {
          payload: {
            title: intl.formatMessage(
              { id: 'alert.update_available_title' },
              { version: remoteVersion },
            ),
            body: intl.formatMessage({ id: 'alert.update_available_body' }),
            buttons: [
              {
                text: intl.formatMessage({ id: 'alert.remind_later' }),
                returnValue: 'later',
              },
              {
                text: intl.formatMessage({ id: 'alert.update' }),
                returnValue: 'update',
              },
            ],
            headerImage: require('../../../assets/phone-holding.png'),
          },
        });

        if (action === 'later') {
          setLastUpdateCheck(new Date().getTime());
        } else if (action === 'update') {
          DeviceInfo.getInstallerPackageName().then((installerPackageName) => {
            let _url = 'https://github.com/ecency/vision-mobile/releases';
            switch (installerPackageName) {
              case 'com.android.vending':
                _url = 'market://details?id=app.esteem.mobile.android';
                break;
              case 'AppStore':
                _url = 'itms-apps://itunes.apple.com/us/app/apple-store/id1451896376?mt=8';
                break;
            }
            Linking.openURL(_url);
          });
        }
      }
    } catch (error) {
      Sentry.captureException(error);
    }
  };

  _handleAlert = (text = null, title = null) => {
    const { intl } = this.props;

    Alert.alert(
      intl.formatMessage({
        id: title || 'alert.warning',
      }),
      intl.formatMessage({
        id: text || 'alert.unknow_error',
      }),
    );
  };

  _handleAppStateChange = (nextAppState: any) => {
    const { isPinCodeOpen: _isPinCodeOpen, currentAccount } = this.props;
    const { appState } = this.state;

    if (appState.match(/inactive|background/) && nextAppState === 'active') {
      this._refreshGlobalProps();
      this._refreshUnreadActivityCount();
      this._refreshUnreadChats();
      if (Date.now() - this._lastPushRegistration > PUSH_REGISTRATION_REFRESH_MS) {
        this._registerDeviceForNotifications();
      }
      // Refresh account data to get latest profile updates from blockchain
      if (currentAccount?.local) {
        this._fetchUserDataFromDsteem(currentAccount.local);
      }
      if (_isPinCodeOpen && this._pinCodeTimer) {
        clearTimeout(this._pinCodeTimer);
      }
    }

    if (appState.match(/active|forground/) && nextAppState === 'inactive') {
      this._startPinCodeTimer();
    }

    this.setState({
      appState: nextAppState,
    });
  };

  _fetchApp = async () => {
    const { dispatch, settingsMigratedV2, selectedLanguage } = this.props;

    await MigrationHelpers.migrateSettings(dispatch, settingsMigratedV2);

    // First-launch only: set the app language from the device locale when the
    // user hasn't picked one yet (still on en-US default). Never overrides a
    // manual choice and only ever sets a registered locale.
    await autoDetectLocale(dispatch, selectedLanguage);

    this._refreshGlobalProps();
    await this._getUserDataFromRealm();
    await this._refreshUnreadChats();
    this._compareAndPromptForUpdate();
    this._registerDeviceForNotifications();
    dispatch(purgeExpiredCache());
  };

  _startPinCodeTimer = () => {
    const { isPinCodeOpen: _isPinCodeOpen } = this.props;
    if (_isPinCodeOpen) {
      this._pinCodeTimer = setTimeout(() => {
        RootNavigation.navigate({
          name: ROUTES.SCREENS.PINCODE,
        });
      }, 1 * 60 * 1000);
    }
  };

  _showNotificationToast = (remoteMessage: any) => {
    const { dispatch } = this.props;

    if (remoteMessage && remoteMessage.notification) {
      const { title } = remoteMessage.notification;
      dispatch(toastNotification(title));
    }
  };

  _createPushListener = async () => {
    await getMessaging().requestPermission();

    firebaseOnMessageListener = getMessaging().onMessage((remoteMessage) => {
      console.log('Notification Received: foreground', remoteMessage);

      const messageType = remoteMessage?.data?.type;
      if ((FCM_FOREGROUND_NOTIFICATION_TYPES as readonly string[]).includes(messageType as any)) {
        // FCM and the enotify websocket can both deliver the same event, so a
        // local +1 double-counted (e.g. daily-spin POINT transfers showed 2).
        // Re-fetch the authoritative unread count instead.
        this._refreshUnreadActivityCount();
      }

      // Show foreground notification banner
      this.setState({
        foregroundNotificationData: remoteMessage,
      });
    });
  };

  // FCM rotates tokens (app data restore, reinstall over backup, periodic refresh).
  // The old token stops working, so register the new one for every account now.
  _createTokenRefreshListener = () => {
    if (firebaseTokenRefreshListener) {
      return;
    }
    try {
      firebaseTokenRefreshListener = getMessaging().onTokenRefresh(() => {
        this._registerDeviceForNotifications();
      });
    } catch (error) {
      console.warn('Failed to listen for push token refresh', error);
    }
  };

  _handleConntectionChange = (status: any) => {
    const { dispatch, isConnected } = this.props;

    if (isConnected !== status) {
      dispatch(setConnectivityStatus(status));
    }
  };

  _refreshGlobalProps = () => {
    const { actions } = this.props;
    actions.fetchGlobalProperties();
    actions.fetchCoinQuotes();
  };

  _refreshUnreadActivityCount = async () => {
    const { dispatch, isLoggedIn, currentAccount, pinCode } = this.props;
    if (isLoggedIn && currentAccount) {
      const username = currentAccount.name;
      const accessToken =
        (currentAccount?.local?.accessToken
          ? decryptKey(currentAccount.local.accessToken, getDigitPinCode(pinCode))
          : '') ?? '';
      try {
        const queryClient = getQueryClient();
        const unreadActivityCount = await queryClient.fetchQuery(
          getNotificationsUnreadCountQueryOptions(username, accessToken),
        );
        dispatch(updateUnreadActivityCount(unreadActivityCount));
      } catch (error) {
        // Keep the last-known count: this runs on every incoming notification,
        // and intermittent mobile connectivity must not wipe the badge to 0.
        console.warn('Failed to refresh unread activity count', error);
      }
    }
  };

  _refreshUnreadChats = async () => {
    const { dispatch, isLoggedIn, isConnected, currentAccount, pinCode } = this.props;
    const username = currentAccount?.name;

    if (!isLoggedIn || !username) {
      setChatApiToken(null);
      clearMattermostBootstrapCache();
      dispatch(updateUnreadChatCount(0));
      return;
    }

    if (isConnected === false) {
      return;
    }

    try {
      await bootstrapMattermostSession(currentAccount, pinCode);

      // Guard: after bootstrap completes, verify the token still belongs
      // to this user. If another account's bootstrap ran concurrently and
      // overwrote the token, skip the unread fetch to prevent contamination.
      if (getChatApiTokenOwner() !== username) {
        return;
      }

      const unreadTotal = await calculateGlobalUnreadTotal();
      dispatch(updateUnreadChatCount(unreadTotal));
    } catch (error) {
      dispatch(updateUnreadChatCount(0));
    }
  };

  _checkHiveAuthExpiry = async (authData: any) => {
    const { intl } = this.props;

    // Only check expiry for session-based authentication (HiveAuth/Keychain or HiveSigner)
    // Skip for private key accounts (masterKey, postingKey, etc.)
    const isSessionBasedAuth =
      authData?.authType === AUTH_TYPE.HIVE_AUTH || authData?.authType === AUTH_TYPE.STEEM_CONNECT;

    if (
      authData?.username &&
      isSessionBasedAuth &&
      authData.hiveAuthExpiry &&
      typeof authData.hiveAuthExpiry === 'number' &&
      authData.hiveAuthExpiry > 0
    ) {
      const curTime = new Date().getTime();
      if (curTime > authData.hiveAuthExpiry) {
        const action = await SheetManager.show(SheetNames.ACTION_MODAL, {
          payload: {
            title: intl.formatMessage({ id: 'alert.warning' }),
            body: intl.formatMessage({ id: 'alert.auth_expired' }),
            buttons: [
              {
                text: intl.formatMessage({ id: 'alert.cancel' }),
                style: 'destructive',
                returnValue: 'cancel',
              },
              {
                text: intl.formatMessage({ id: 'alert.verify' }),
                returnValue: 'verify',
              },
            ],
          },
        });

        if (action === 'verify') {
          RootNavigation.navigate({
            name: ROUTES.SCREENS.LOGIN,
            params: { username: authData.username },
          });
        }
      }
    }
  };

  _getUserDataFromRealm = async () => {
    const {
      dispatch,
      isPinCodeOpen: _isPinCodeOpen,
      isConnected,
      otherAccounts,
      currentAccount,
    } = this.props;
    let realmData: any[] = [];

    if (currentAccount?.name) {
      dispatch(login(true));

      const username = currentAccount.name;

      const userData = await getUserData();

      if (userData && userData.length > 0) {
        realmData = userData;
        userData.forEach((accountData: any, index: any) => {
          if (
            !accountData ||
            (!accountData.accessToken &&
              !accountData.masterKey &&
              !accountData.postingKey &&
              !accountData.activeKey &&
              !accountData.memoKey)
          ) {
            realmData.splice(index, 1);
            removeSCAccount(accountData.username);
            removeUserData(accountData.username);
          }
        });
      }

      let [authData]: any = realmData.filter((data: any) => data.username === username);

      // reapir otherAccouts data is needed
      // this repair must be done because code above makes sure every entry is realmData is a valid one
      repairOtherAccountsData(otherAccounts, realmData, dispatch);

      if (!authData) {
        // means current logged in user keys data not present, re-verify required
        authData = await this._repairUserAccountData(username);

        // disrupt routine if repair helper fails
        if (!authData) {
          return null;
        }
      }

      // check session expiry for session-based auth (HiveAuth or HiveSigner)
      if (
        authData.authType === AUTH_TYPE.HIVE_AUTH ||
        authData.authType === AUTH_TYPE.STEEM_CONNECT
      ) {
        this._checkHiveAuthExpiry(authData);
      }

      // If in dev mode pin code does not show
      if (_isPinCodeOpen) {
        RootNavigation.navigate({ name: ROUTES.SCREENS.PINCODE });
      } else if (!_isPinCodeOpen) {
        const encryptedPin = encryptKey(Config.DEFAULT_PIN!, Config.PIN_KEY!);
        dispatch(savePinCode(encryptedPin));
      }

      if (isConnected) {
        this._fetchUserDataFromDsteem(authData);
      }

      return authData;
    }
  };

  _refreshAccessToken = async (currentAccount: any) => {
    const { pinCode, isPinCodeOpen, encUnlockPin, dispatch, intl } = this.props;

    if (isPinCodeOpen && !encUnlockPin) {
      return currentAccount;
    }

    try {
      const userData = currentAccount.local;
      const encryptedAccessToken = await refreshSCToken(userData, getDigitPinCode(pinCode));

      return {
        ...currentAccount,
        local: {
          ...userData,
          accessToken: encryptedAccessToken,
        },
      };
    } catch (error) {
      console.warn('Failed to refresh access token', error);
      Alert.alert(
        intl.formatMessage({
          id: 'alert.fail',
        }),
        (error as any).message,
        [
          {
            text: intl.formatMessage({ id: 'side_menu.logout' }),
            onPress: () => dispatch(logout()),
          },
          {
            text: intl.formatMessage({ id: 'alert.cancel' }),
            style: 'destructive',
          },
        ],
      );
      return currentAccount;
    }
  };

  _fetchUserDataFromDsteem = async (realmObject: any) => {
    const { dispatch, intl, pinCode, isPinCodeOpen, encUnlockPin } = this.props;

    try {
      let accountData = await getUser(realmObject.username);
      accountData.local = realmObject;

      // cannot migrate or refresh token since pin would null while pin code modal is open
      if (!isPinCodeOpen || encUnlockPin) {
        // migration script for previously mast key based logged in user not having access token
        if (
          realmObject.authType !== AUTH_TYPE.STEEM_CONNECT &&
          realmObject.authType !== AUTH_TYPE.HIVE_AUTH &&
          realmObject.accessToken === ''
        ) {
          accountData = await migrateToMasterKeyWithAccessToken(accountData, realmObject, pinCode);
        }

        // refresh access token
        accountData = await this._refreshAccessToken(accountData);
      }

      try {
        const queryClient = getQueryClient();
        const accessToken =
          (accountData?.local?.accessToken
            ? decryptKey(accountData.local.accessToken, getDigitPinCode(pinCode))
            : '') ?? '';
        accountData.unread_activity_count = await queryClient.fetchQuery(
          getNotificationsUnreadCountQueryOptions(realmObject.username, accessToken),
        );

        // Fetch muted users using SDK query
        accountData.mutes = await queryClient.fetchQuery(
          getMutedUsersQueryOptions(realmObject.username),
        );

        accountData.pointsSummary = await getPointsSummary(realmObject.username);
      } catch (err) {
        console.warn(
          'Optional user data fetch failed, account can still function without them',
          err,
        );
      }
      dispatch(updateCurrentAccount(accountData));
      dispatch(fetchSubscribedCommunities(realmObject.username));

      // Connect to notification server based on FCM availability
      // If FCM is available, it will handle notifications
      // If not, use WebSocket as fallback
      const fcmAvailable = await this._checkFCMAvailability();
      if (!fcmAvailable) {
        console.log('Connecting to WebSocket notification server (FCM not available)');
        this._connectNotificationServer(accountData.name);
      } else {
        console.log('Using FCM for notifications (WebSocket not needed)');
        // Ensure any previous websocket is disconnected
        this._disconnectNotificationServer();
      }

      // TODO: better update device push token here after access token refresh
    } catch (err) {
      // A cancelled in-flight startup query — e.g. the account-full fetch in
      // getUser() superseded/aborted during bootstrap — rejects with
      // CancelledError/AbortError. That is benign (the data refetches), so don't
      // surface the blocking "change server and restart" alert for it. Reading
      // `err.message` defensively also avoids a secondary throw when it's unset.
      const _name = (err && (err as any).name) || '';
      const _msg = (err && (err as any).message) || String(err) || '';
      if (
        _name === 'CancelledError' ||
        _name === 'AbortError' ||
        _msg.includes('CancelledError') ||
        _msg.includes('aborted')
      ) {
        console.warn('Startup user-data fetch cancelled (benign), skipping alert', err);
        return;
      }
      Alert.alert(
        `${intl.formatMessage({
          id: 'alert.fetch_error',
        })} \n${_msg.substr(0, 20)}`,
      );
    }
  };

  // Register the push token with each signed-in account's notification settings.
  _registerDeviceForNotifications = () => {
    const { currentAccount, otherAccounts } = this.props;

    this._lastPushRegistration = Date.now();

    otherAccounts.forEach((account: any) => {
      // since there can be more than one accounts, process access tokens separate
      if (account?.local?.accessToken) {
        this._registerAccountForNotifications(account);
        return;
      }

      // No stored access token on this other-account entry. This is common and benign
      // (HiveSigner accounts, or entries keyed only by username), so do NOT report it to
      // Sentry - it previously fired an error on every launch (ECENCY-MOBILE-1QY).
      const acctName = account?.name || account?.username;
      if (acctName && currentAccount?.name === acctName) {
        // fallback to current account access token to register at least the logged-in account
        this._registerAccountForNotifications(currentAccount);
      }
    });
  };

  _registerAccountForNotifications = (account: any) => {
    const { notificationDetails, isNotificationsEnabled } = this.props;
    const encAccessToken = account?.local?.accessToken;

    if (!encAccessToken) {
      return;
    }

    // otherAccounts entries are keyed by username; name can be undefined on some
    // (e.g. HiveSigner) entries, so fall back to username.
    this._enableNotification(
      account.name || account.username,
      isNotificationsEnabled,
      notificationDetails,
      encAccessToken,
    );
  };

  /**
   * Check if FCM (Firebase Cloud Messaging) is available on this device
   * Returns cached result if already checked
   *
   * FCM requires:
   * - iOS: APNS (Apple Push Notification Service) - not available on simulators
   * - Android: Google Play Services - not available on custom ROMs, Huawei devices, emulators without Google APIs
   */
  _checkFCMAvailability = async (): Promise<boolean> => {
    const { dispatch } = this.props;

    // Return cached result if already checked
    if (this._fcmAvailable !== null) {
      return this._fcmAvailable;
    }

    try {
      // iOS Simulator check - APNS not available
      const isEmulator = await DeviceInfo.isEmulator();
      if (Platform.OS === 'ios' && isEmulator) {
        console.log('FCM not available: iOS Simulator (no APNS)');
        this._fcmAvailable = false;
        dispatch(setFCMAvailable(false));
        return false;
      }

      // Check permission without prompting
      const authStatus = await getMessaging().hasPermission();
      const permissionGranted = authStatus === 1 || authStatus === 2; // authorized or provisional

      if (!permissionGranted) {
        console.log('FCM not available: User denied notification permission');
        this._fcmAvailable = false;
        dispatch(setFCMAvailable(false));
        return false;
      }

      // Try to get FCM token - this will fail if FCM isn't available
      const token = await getMessaging().getToken();

      if (token) {
        console.log('FCM is available - token obtained');
        this._fcmAvailable = true;
        dispatch(setFCMAvailable(true));
        return true;
      } else {
        console.log('FCM not available: No token returned');
        this._fcmAvailable = false;
        dispatch(setFCMAvailable(false));
        return false;
      }
    } catch (error) {
      const errorMessage = (error as any).message || '';

      // Expected errors when FCM is not available
      if (
        errorMessage.includes('MISSING_INSTANCEID_SERVICE') ||
        errorMessage.includes('SERVICE_NOT_AVAILABLE') ||
        errorMessage.includes('AUTHENTICATION_FAILED') ||
        errorMessage.includes('APNS')
      ) {
        console.log('FCM not available:', errorMessage);
        this._fcmAvailable = false;
        dispatch(setFCMAvailable(false));
        return false;
      }

      // Unexpected error - assume FCM not available to be safe
      console.warn('FCM availability check failed:', error);
      this._fcmAvailable = false;
      dispatch(setFCMAvailable(false));
      return false;
    }
  };

  _disconnectNotificationServer = () => {
    if (this._notificationWs) {
      console.log('Disconnecting notification websocket');
      this._notificationWs.close();
      this._notificationWs = null;
    }
    if (this._wsReconnectTimer) {
      clearTimeout(this._wsReconnectTimer);
      this._wsReconnectTimer = null;
    }
    this._wsReconnectAttempts = 0;
  };

  _connectNotificationServer = (username: any) => {
    // Clean up existing connection first
    this._disconnectNotificationServer();

    if (!username || !Config.ACTIVITY_WEBSOCKET_URL) {
      console.log('Skipping websocket - missing username or URL');
      return;
    }

    try {
      const safeUsername = encodeURIComponent(username);
      this._notificationUsername = username;
      console.log('Connecting notification websocket for user:', username);
      const ws = new WebSocket(`${Config.ACTIVITY_WEBSOCKET_URL}?user=${safeUsername}`);
      this._notificationWs = ws;

      ws.onopen = () => {
        console.log('Notification websocket connected');
        this._wsReconnectAttempts = 0;
      };

      ws.onmessage = (event) => {
        const { activeBottomTab, dispatch } = this.props;

        console.log('Websocket notification received:', event.data);

        // Try to parse notification data from enotify-py websocket
        // Format: { event: "notify", type: "mention"|"reply", source: "username", target: "username", extra: {...}, timestamp: "..." }
        let wsData = null;
        try {
          if (event.data) {
            wsData = JSON.parse(event.data);
          }
        } catch (err) {
          console.log('Websocket message is not JSON, treating as simple ping');
        }

        // Convert enotify-py websocket format to FCM format for ForegroundNotification component
        if (
          wsData &&
          wsData.event === 'notify' &&
          (WS_NOTIFICATION_TYPES as readonly string[]).includes(wsData.type)
        ) {
          // Re-fetch the authoritative unread count rather than a local +1:
          // FCM may deliver the same event, and a local increment in both
          // paths double-counted (daily-spin POINT transfers showed 2 not 1).
          this._refreshUnreadActivityCount();
          const { type, source, target, extra } = wsData;

          // Title/body per type. A ternary chain here previously ended in a
          // delegation fallback, so any unhandled type announced itself as a
          // delegation — every type in the allowlist above needs a case.
          let notifTitle = '';
          let notifBody = '';
          switch (type) {
            case 'mention':
              notifTitle = `@${source} mentioned @${target}`;
              break;
            case 'reply':
              notifTitle = `@${source} replied to @${target}`;
              notifBody = extra?.body ? extra.body.substring(0, 100) : '';
              break;
            case 'transfer':
              notifTitle = `@${source} transferred to @${target}`;
              notifBody = extra?.amount || '';
              break;
            case 'delegations':
              notifTitle = `@${source} delegated to @${target}`;
              notifBody = extra?.amount || '';
              break;
            case 'scheduled_published':
              notifTitle = 'Scheduled post published';
              notifBody = extra?.title || '';
              break;
            case 'payouts':
              notifTitle = 'Rewards received';
              notifBody = extra?.amount || '';
              break;
            case 'account_update':
              notifTitle = 'Account updated';
              notifBody = 'Your account details were updated';
              break;
            case 'weekly_earnings':
              notifTitle = 'Weekly earnings';
              notifBody = extra?.total_usd ? `$${extra.total_usd}` : '';
              break;
            case 'follow':
              notifTitle = `@${source} followed @${target}`;
              break;
            case 'unfollow':
              notifTitle = `@${source} unfollowed @${target}`;
              break;
            case 'ignore':
              notifTitle = `@${source} ignored @${target}`;
              break;
            case 'blacklist':
              notifTitle = `@${source} blacklisted @${target}`;
              break;
            case 'tags': {
              // A post lists every followed tag it matched and shows the first; a
              // bundle names its one tag and carries a count.
              const wsTag = extra?.tags?.[0] || extra?.tag || '';
              notifTitle = extra?.count
                ? `${extra.count} new posts in #${wsTag}`
                : `@${source} posted in #${wsTag}`;
              notifBody = extra?.title || '';
              break;
            }
            default:
              notifTitle = `@${source}`;
              break;
          }

          // Build FCM-compatible notification object
          const fcmFormat = {
            data: {
              id: `ws-${Date.now()}`,
              source,
              target,
              type,
              // For mentions/replies: extra.permlink
              // For transfers/delegations: no permlink needed
              permlink1: (extra?.permlink || '').substring(0, 250),
              permlink2: (extra?.permlink || '').substring(250, 500),
              permlink3: (extra?.permlink || '').substring(500, 750),
              // For transfers/delegations: extra.amount
              amount: extra?.amount || '',
              // For a followed-tag bundle: the tag feed to open
              tag: extra?.tags?.[0] || extra?.tag || '',
            },
            notification: {
              title: notifTitle,
              body: notifBody,
            },
          };

          console.log('Converted websocket to FCM format:', fcmFormat);
          this.setState({
            foregroundNotificationData: fcmFormat,
          });
        }

        // Workaround: Force refresh notification tab if active
        if (activeBottomTab === ROUTES.TABBAR.NOTIFICATION) {
          dispatch(updateActiveBottomTab(''));
          dispatch(updateActiveBottomTab(ROUTES.TABBAR.NOTIFICATION));
        }
      };

      ws.onerror = (error) => {
        console.warn('Notification websocket error:', error);
      };

      ws.onclose = (event) => {
        console.log('Notification websocket closed:', event.code, event.reason);
        this._notificationWs = null;

        // Only reconnect if not a normal closure and component is still mounted
        if (event.code !== 1000 && this._wsReconnectAttempts < 5) {
          this._wsReconnectAttempts++;
          const delay = Math.min(1000 * 2 ** this._wsReconnectAttempts, 30000);
          const latestUsername =
            this.props?.currentAccount?.name || this._notificationUsername || username;
          console.log(
            `Reconnecting websocket in ${delay}ms (attempt ${this._wsReconnectAttempts})`,
          );

          this._wsReconnectTimer = setTimeout(() => {
            if (latestUsername) {
              this._connectNotificationServer(latestUsername);
            }
          }, delay);
        }
      };
    } catch (error) {
      console.error('Failed to create notification websocket:', error);
    }
  };

  _repairUserAccountData = async (username: any) => {
    const { dispatch, intl, otherAccounts, currentAccount, pinCode } = this.props;

    // use current account variant if it exist of target account;
    const _accounts = currentAccount.name === username ? [currentAccount] : otherAccounts;
    return repairUserAccountData(username, dispatch, intl, _accounts, pinCode);
  };

  // update previously loggedin users list,
  _updatePrevLoggedInUsersList = (username: string) => {
    const { dispatch, prevLoggedInUsers } = this.props as any;
    if (prevLoggedInUsers && prevLoggedInUsers.length > 0) {
      const userIndex = prevLoggedInUsers.findIndex((el: any) => el?.username === username);

      if (userIndex > -1) {
        const updatedPrevLoggedInUsers = [...prevLoggedInUsers];
        updatedPrevLoggedInUsers[userIndex] = { username, isLoggedOut: true };
        dispatch(setPrevLoggedInUsers(updatedPrevLoggedInUsers));
      } else {
        const u = { username, isLoggedOut: true };
        dispatch(setPrevLoggedInUsers([...prevLoggedInUsers, u]));
      }
    } else {
      const u = { username, isLoggedOut: true };
      dispatch(setPrevLoggedInUsers([u]));
    }
  };

  _logout = async (username: any) => {
    const { currentAccount, otherAccounts, dispatch, intl, pinCode } = this.props;

    // Read the token before the account data is removed below.
    const loggedOutAccount =
      currentAccount?.name === username
        ? currentAccount
        : otherAccounts.find((user: any) => (user.username || user.name) === username);
    const accessToken = decryptAccessToken(loggedOutAccount?.local?.accessToken, pinCode);

    try {
      const response = await removeUserData(username);

      if (response instanceof Error) {
        throw response;
      }
      this._updatePrevLoggedInUsersList(username);

      // switch account if other account exist
      const _otherAccounts = otherAccounts.filter((user: any) => user.username !== username);

      // Stop pushes for the logged-out account. With no account left, also delete the
      // device token so nothing registered under it is delivered any more.
      disablePushRegistrations([{ username, accessToken }], {
        deleteToken: _otherAccounts.length === 0,
      });

      if (_otherAccounts.length > 0) {
        const targetAccount = _otherAccounts[0];
        await this._switchAccount(targetAccount);
      }

      // logut from app if no more other accounts
      else {
        dispatch(updateCurrentAccount({}));
        dispatch(login(false));
        removePinCode();
        setAuthStatus({
          isLoggedIn: false,
        });
        setExistUser(false);
        dispatch(isPinCodeOpen(false));
        dispatch(setEncryptedUnlockPin(encryptKey(Config.DEFAULT_PIN!, Config.PIN_KEY!)) as any);
      }

      removeSCAccount(username);
      dispatch(setFeedPosts([]));
      dispatch(setInitPosts([]));
      dispatch(removeOtherAccount(username));

      // Drop the shared Mattermost PAT + bootstrap cache so a request
      // already on the wire can't resurrect the session for the logged-out
      // user. The generation counter inside bootstrapMattermostSession
      // makes any in-flight resolve a no-op. If another account is still
      // logged in, its next chat access will re-bootstrap cleanly.
      setChatApiToken(null);
      clearMattermostBootstrapCache();

      dispatch(logoutDone());
    } catch (err) {
      dispatch(logoutDone());
      Alert.alert(intl.formatMessage({ id: 'alert.fail' }), (err as any).message);
      this._repairUserAccountData(username);
    }
  };

  _enableNotification = async (
    username: string,
    isEnable: boolean,
    settings: any,
    encAccesstoken: string,
  ) => {
    const { pinCode } = this.props;
    const accessToken = decryptAccessToken(encAccesstoken, pinCode);

    if (!accessToken) {
      // The request would be rejected without it. A stored token that does not decrypt
      // means the PIN state and the stored keys disagree, which is worth knowing about.
      captureMessage('Push registration skipped: stored access token did not decrypt', (scope) => {
        scope.setTag('context', 'push-registration');
        scope.setFingerprint(['push-registration-decrypt']);
      });
      return;
    }

    // compile notify_types
    let notify_types: any[] = [];
    const notifyTypesConst = {
      voteNotification: 1,
      mentionNotification: 2,
      followNotification: 3,
      commentNotification: 4,
      reblogNotification: 5,
      transfersNotification: 6,
      favoriteNotification: 13,
      bookmarkNotification: 15,
      tagsNotification: 23,
      delegationsNotification: 10,
      payoutsNotification: 19,
      accountUpdateNotification: 20,
      weeklyEarningsNotification: 21,
      scheduledPublishedNotification: 22,
    };

    if (settings) {
      Object.keys(settings).forEach((item) => {
        if ((notifyTypesConst as any)[item] && settings[item]) {
          notify_types.push((notifyTypesConst as any)[item]);
        }
      });
    } else {
      // Derived, not hardcoded. The literal here used to be [1,2,3,4,5,6,13,15,22],
      // which omitted 10, 19, 20 and 21, so a fresh login with no settings yet got no
      // delegations, payouts, account_update or weekly_earnings pushes until it saved
      // settings once. A hand-maintained copy of this map is guaranteed to drift.
      notify_types = Object.values(notifyTypesConst);
    }

    try {
      // Check if we're on iOS simulator where APNS isn't available
      const isSimulator = await DeviceInfo.isEmulator();
      if (Platform.OS === 'ios' && isSimulator) {
        console.log('Skipping FCM token on iOS simulator - APNS not available');
        return;
      }

      // Request permission first to ensure APNS is set up on iOS
      const authStatus = await getMessaging().requestPermission();
      const enabled =
        authStatus === 1 || // authorized
        authStatus === 2; // provisional

      if (!enabled) {
        console.log('Notification permission not granted');
        return;
      }

      const token = await getMessaging().getToken();
      console.log('FCM Token obtained:', !!token);
      try {
        await saveNotificationSetting(
          accessToken,
          username,
          getPushSystem(),
          Number(isEnable),
          notify_types,
          token,
        );
      } catch (error) {
        const status = (error as any)?.status;
        if (typeof status !== 'number') {
          // Offline or timed out: the next start, reconnect or daily refresh retries.
          console.warn('Push registration request failed', error);
          return;
        }
        captureException(error, (scope) => {
          scope.setTag('context', 'push-registration');
          scope.setTag('status', String(status));
          scope.setFingerprint(['push-registration-http', String(status)]);
        });
      }
    } catch (error) {
      // Handle platform-specific FCM errors gracefully
      const errorMessage = (error as any).message || '';
      const isUnknownError = (error as any).code === 'messaging/unknown';

      if (Platform.OS === 'ios' && (isUnknownError || errorMessage.includes('APNS'))) {
        // iOS: APNS not available (likely simulator or development environment)
        console.log('APNS not available on iOS - likely simulator or missing configuration');
      } else if (
        Platform.OS === 'android' &&
        (errorMessage.includes('MISSING_INSTANCEID_SERVICE') ||
          errorMessage.includes('SERVICE_NOT_AVAILABLE') ||
          errorMessage.includes('AUTHENTICATION_FAILED') ||
          errorMessage.includes('FIS_AUTH_ERROR'))
      ) {
        // Android: Google Play Services issues (common on emulators, custom ROMs, outdated devices)
        // FIS_AUTH_ERROR is Firebase Installations failing to authenticate the device
        // (ECENCY-MOBILE-28Y); the app cannot fix it either.
        console.log(
          'Google Play Services not available or misconfigured - FCM disabled for this device',
        );
        // Don't send to Sentry as this is expected on some Android configurations
      } else {
        // Unexpected error - log and report to Sentry
        console.warn('Failed to enable notification:', error);
        Sentry.captureException(error);
      }
    }
  };

  _switchAccount = async (targetAccount: any) => {
    const { dispatch, isConnected, pinCode, intl } = this.props;

    if (!isConnected) return;

    try {
      const accountData: any = await switchAccount(targetAccount.username);
      let realmData = await getUserDataWithUsername(targetAccount.username);

      let _currentAccount: any = accountData;
      _currentAccount.name = accountData.name;
      [_currentAccount.local] = realmData;

      if (!realmData[0]) {
        realmData = await this._repairUserAccountData(targetAccount.username);

        // disrupt routine if repair helper fails
        if (!realmData[0]) {
          return;
        }
      }

      // migreate account to use access token for master key auth type
      if (
        realmData[0].authType !== AUTH_TYPE.STEEM_CONNECT &&
        realmData[0].authType !== AUTH_TYPE.HIVE_AUTH &&
        realmData[0].accessToken === ''
      ) {
        _currentAccount = await migrateToMasterKeyWithAccessToken(
          _currentAccount,
          realmData[0],
          pinCode,
        );
      }

      // check session expiry for session-based auth (HiveAuth or HiveSigner)
      if (
        realmData[0].authType === AUTH_TYPE.HIVE_AUTH ||
        realmData[0].authType === AUTH_TYPE.STEEM_CONNECT
      ) {
        this._checkHiveAuthExpiry(realmData[0]);
      }

      // update refresh token
      _currentAccount = await this._refreshAccessToken(_currentAccount);

      try {
        const queryClient = getQueryClient();
        const accessToken =
          (_currentAccount?.local?.accessToken
            ? decryptKey(_currentAccount.local.accessToken, getDigitPinCode(pinCode))
            : '') ?? '';
        _currentAccount.unread_activity_count = await queryClient.fetchQuery(
          getNotificationsUnreadCountQueryOptions(_currentAccount.name, accessToken),
        );
        _currentAccount.pointsSummary = await getPointsSummary(_currentAccount.name);

        // Fetch muted users using SDK query
        _currentAccount.mutes = await queryClient.fetchQuery(
          getMutedUsersQueryOptions(_currentAccount.name),
        );
      } catch (err) {
        console.warn(
          'Optional user data fetch failed, account can still function without them',
          err,
        );
      }

      dispatch(updateCurrentAccount(_currentAccount));
      dispatch(fetchSubscribedCommunities(_currentAccount.name));

      // Ensure notification channel is connected for the new account
      try {
        const fcmAvailable = await this._checkFCMAvailability();
        if (!fcmAvailable) {
          console.log('Connecting to WebSocket notification server (FCM not available)');
          this._connectNotificationServer(_currentAccount.name);
        } else {
          console.log('Using FCM for notifications (WebSocket not needed)');
          this._disconnectNotificationServer();
        }
      } catch (wsErr) {
        console.warn('Failed to update notification channel after account switch', wsErr);
      }
    } catch (err) {
      dispatch(
        toastNotification(
          `${intl.formatMessage(
            { id: 'alert.logging_out' },
            { username: targetAccount.username },
          )}\n${(err as any).message}`,
        ),
      );
      this._logout(targetAccount.username);
    }
  };

  UNSAFE_componentWillReceiveProps(nextProps: any) {
    const {
      isDarkTheme: _isDarkTheme,
      currentAccount: { name },
      selectedLanguage,
      isLogingOut,
      isConnected,
      api,
    } = this.props;

    if (
      _isDarkTheme !== nextProps.isDarkTheme ||
      selectedLanguage !== nextProps.selectedLanguage ||
      (api !== nextProps.api && nextProps.api)
    ) {
      this.setState(
        {
          isRenderRequire: false,
        },
        () =>
          this.setState({
            isRenderRequire: true,
          }),
      );
      if (nextProps.isDarkTheme) {
        changeNavigationBarColor('#1e2835');
      } else {
        changeNavigationBarColor('#FFFFFF', true);
      }
    }

    if (isLogingOut !== nextProps.isLogingOut && nextProps.isLogingOut) {
      this._logout(name);
    }

    if (isConnected !== null && isConnected !== nextProps.isConnected && nextProps.isConnected) {
      this._fetchApp();
    }
  }

  render() {
    const {
      selectedLanguage,
      isConnected,
      toastNotification,
      isDarkTheme: _isDarkTheme,
      children,
      rcOffer,
    } = this.props;
    const { isRenderRequire, foregroundNotificationData } = this.state;

    return (
      children &&
      children({
        isConnected,
        isDarkTheme: _isDarkTheme,
        isRenderRequire,
        locale: selectedLanguage,
        rcOffer,
        toastNotification,
        foregroundNotificationData,
      })
    );
  }
}

export default connect(
  (state) => ({
    // Application
    isDarkTheme: selectIsDarkTheme(state),
    selectedLanguage: selectLanguage(state),
    isPinCodeOpen: selectIsPinCodeOpen(state),
    encUnlockPin: selectEncUnlockPin(state),

    isLoggedIn: selectIsLoggedIn(state),
    isConnected: selectIsConnected(state),
    api: selectApi(state),
    isGlobalRenderRequired: selectIsGlobalRenderRequired(state),
    lastUpdateCheck: selectLastUpdateCheck(state),
    settingsMigratedV2: selectSettingsMigratedV2(state),
    isNotificationsEnabled: selectIsNotificationOpen(state),
    notificationDetails: selectNotificationDetails(state),

    // Account
    unreadActivityCount: selectCurrentAccountUnreadActivityCount(state),
    currentAccount: selectCurrentAccount(state),
    otherAccounts: selectOtherAccounts(state),
    prevLoggedInUsers: selectPrevLoggedInUsers(state),
    pinCode: selectPin(state),

    // UI
    toastNotification: (state as any).ui.toastNotification,
    activeBottomTab: (state as any).ui.activeBottomTab,
    isLogingOut: (state as any).ui.isLogingOut,
    rcOffer: (state as any).ui.rcOffer,
  }),
  (dispatch) => ({
    dispatch,
    actions: {
      ...bindActionCreators(
        {
          fetchGlobalProperties,
          fetchCoinQuotes,
        },
        dispatch,
      ),
    },
  }),
)(injectIntl(ApplicationContainer));
