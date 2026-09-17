import React, { Component } from 'react';
import { Platform, Alert, Appearance } from 'react-native';
import { connect } from 'react-redux';
import { useQueryClient } from '@tanstack/react-query';

import Config from 'react-native-config';
import { injectIntl } from 'react-intl';
import { getMessaging } from '@react-native-firebase/messaging';
import { useNavigation } from '@react-navigation/native';
import { gestureHandlerRootHOC } from 'react-native-gesture-handler';
import DeviceInfo from 'react-native-device-info';
import { SheetManager } from 'react-native-actions-sheet';
import {
  saveNotificationSetting,
  ConfigManager,
  getSupportSettingsQueryOptions,
  updateSupportSettingsRequest,
  applySupportSettingsUpdate,
} from '@ecency/sdk';
import { languageRestart } from '../../../utils/I18nUtils';
import THEME_OPTIONS from '../../../constants/options/theme';

// Realm
import {
  setCurrency as setCurrency2DB,
  setServer,
  setNotificationSettings,
  setLanguage as setLanguage2DB,
  setNsfw as setNsfw2DB,
  removePinCode,
  setAuthStatus,
  setExistUser,
  removeAllUserData,
} from '../../../storage/storage';

// Services and Actions
import {
  setLanguage,
  changeNotificationSettings,
  setCurrency,
  setApi,
  isDefaultFooter,
  setNsfw,
  isPinCodeOpen,
  login,
  setColorTheme,
  setIsBiometricEnabled,
  setEncryptedUnlockPin,
  setHidePostsThumbnails,
  setIsDarkTheme,
  setImageServer,
} from '../../../redux/actions/applicationActions';
import { logout, logoutDone, toastNotification } from '../../../redux/actions/uiAction';
import { deleteAccount } from '../../../providers/ecency/ecency';
import {
  DEFAULT_SUPPORT_PERCENT,
  SUPPORT_BENEFICIARY_PERCENTS,
  SUPPORT_CURATION_PERCENTS,
  isValidSupportSettings,
} from '../../../providers/ecency/supportBeneficiary';
import {
  clearMattermostBootstrapCache,
  getMattermostDmPrivacy,
  updateMattermostDmPrivacy,
  type MattermostDmPrivacy,
} from '../../../providers/chat/mattermost';
import { setChatApiToken } from '../../../config/chatApi';
import { checkClient, getDigitPinCode } from '../../../providers/hive/hive';
import { removeOtherAccount, updateCurrentAccount } from '../../../redux/actions/accountAction';
import { useGetServersQuery } from '../../../providers/queries';
import { useAuth } from '../../../hooks';
import {
  selectCurrentAccount,
  selectIsLoggedIn,
  selectIsDarkTheme,
  selectLanguage,
  selectCurrency,
  selectIsPinCodeOpen,
  selectOtherAccounts,
  selectHidePostsThumbnails,
  selectNotificationDetails,
  selectPin,
  selectEncUnlockPin,
  selectIsNotificationOpen,
  selectIsFCMAvailable,
  selectApi,
  selectIsBiometricEnabled,
  selectColorTheme,
  selectNsfw,
  selectIsDefaultFooter,
  selectImageServer,
} from '../../../redux/selectors';
// Middleware

// Constants
import { VALUE as CURRENCY_VALUE } from '../../../constants/options/currency';
import { VALUE as LANGUAGE_VALUE } from '../../../constants/options/language';
import { IMAGE_SERVERS } from '../../../constants/options/imageServer';
import settingsTypes from '../../../constants/settingsTypes';

// Utilities
import { sendEmail } from '../../../utils/sendEmail';
import { encryptKey, decryptKey } from '../../../utils/crypto';
import { openStoreListing } from '../../../utils/storeReview';
import { disablePushRegistrations, getPushAccounts } from '../../../utils/pushRegistration';

// Component
import SettingsScreen from '../screen/settingsScreen';
import ROUTES from '../../../constants/routeNames';
import { SheetNames } from '../../../navigation/sheets';

/*
 *            Props Name        Description                                     Value
 *@props -->  props name here   description here                                Value Type Here
 *
 */

class SettingsContainer extends Component<any, any> {
  // Monotonic sequence for support settings fetches/saves. Only the latest
  // operation may apply its response (or rollback) to state and cache, so an
  // older request that settles late can never overwrite newer state. The
  // counter is also bumped on account switch, invalidating in-flight work
  // that belongs to the previous account.
  _supportOpSeq = 0;

  // saves are queued FIFO so full-payload updates can never reach the server
  // out of order; a save superseded by a newer one is skipped before sending
  _supportSaveSeq = 0;

  _supportSaveChain: Promise<void> = Promise.resolve();

  _focusUnsubscribe: (() => void) | null = null;

  // Last server-acknowledged support settings (and the seq that produced
  // them). Failed saves roll back HERE, never to the point-in-time snapshot
  // taken when the save started: with two overlapping saves that both fail,
  // that snapshot is the older save's unconfirmed optimistic value, which the
  // server never accepted. Stale-but-successful saves still record their
  // response (the server did apply it), guarded by seq so an older response
  // cannot overwrite a newer acknowledgment or leak across account switches.
  _confirmedSupportSettings: any = null;

  _confirmedSupportSeq = 0;

  constructor(props: any) {
    super(props);
    this.state = {
      isNotificationMenuOpen: props.isNotificationSettingsOpen,
      isLoading: false,
      dmPrivacy: 'all',
      // null = not loaded (or load failed); the support controls stay hidden
      // until a load succeeds so a toggle can never do a read-modify-write
      // against unknown values and wipe the other saved field
      supportSettings: null,
    };
  }

  async componentDidMount() {
    const { isLoggedIn } = this.props as any;
    if (!isLoggedIn) return;

    // fire-and-forget: never rejects (guarded internally) and must not
    // serialize the DM privacy fetch behind a slow support settings request
    this._fetchSupportSettings();

    // the editor chip can change the stored settings while this screen stays
    // mounted in the stack; refetch on focus so a later partial save cannot
    // merge against stale values. Chained behind queued saves so the fetch
    // cannot observe pre-save server state.
    const { navigation } = this.props as any;
    this._focusUnsubscribe =
      navigation?.addListener?.('focus', () => {
        if (!(this.props as any).isLoggedIn) return;
        this._supportSaveChain.then(() => this._fetchSupportSettings());
      }) ?? null;

    try {
      const dmPrivacy = await getMattermostDmPrivacy();
      this.setState({ dmPrivacy });
    } catch {
      // best-effort: keep default
    }
  }

  componentDidUpdate(prevProps: any) {
    const { username, isLoggedIn } = this.props as any;
    // account switched while the screen stayed mounted: drop the previous
    // account's values (hides the controls), invalidate in-flight support
    // fetches/saves, and refetch for the newly selected account so a partial
    // save can never write the previous account's percents to this one
    if (prevProps.username !== username) {
      this._supportOpSeq += 1;
      // raise the acknowledgment floor so a late response from the previous
      // account's in-flight save can never become this account's rollback
      // target, and drop the previous account's confirmed values
      this._confirmedSupportSeq = this._supportOpSeq;
      this._confirmedSupportSettings = null;
      this.setState({ supportSettings: null });
      if (isLoggedIn && username) {
        this._fetchSupportSettings();
      }
    }
  }

  componentWillUnmount() {
    this._focusUnsubscribe?.();
  }

  // reads through the shared SDK query cache so the editor chip and this
  // screen stay coherent; malformed 200 responses keep the controls hidden
  _fetchSupportSettings = async () => {
    const { queryClient, username, code } = this.props as any;

    this._supportOpSeq += 1;
    const seq = this._supportOpSeq;

    try {
      const supportSettings = await queryClient.fetchQuery(
        getSupportSettingsQueryOptions(username, code),
      );
      if (seq !== this._supportOpSeq) return;
      const nextSettings = isValidSupportSettings(supportSettings)
        ? {
            beneficiary_percent: supportSettings.beneficiary_percent || 0,
            curation_percent: supportSettings.curation_percent || 0,
          }
        : null;
      if (nextSettings && seq > this._confirmedSupportSeq) {
        this._confirmedSupportSettings = nextSettings;
        this._confirmedSupportSeq = seq;
      }
      this.setState({ supportSettings: nextSettings });
    } catch {
      if (seq !== this._supportOpSeq) return;
      // keep controls hidden; re-entering the screen retries
      this.setState({ supportSettings: null });
    }
  };

  // Component Functions
  _handleDropdownSelected = async (action: any, actionType: any) => {
    const { dispatch, selectedLanguage, intl } = this.props as any;
    switch (actionType) {
      case 'currency':
        this._currencyChange(action);
        break;

      case 'language':
        await dispatch(setLanguage(LANGUAGE_VALUE[action]));
        await setLanguage2DB(LANGUAGE_VALUE[action]);
        await languageRestart(selectedLanguage, LANGUAGE_VALUE[action], intl); // restart the app and flip change layout according to lang direction
        break;

      case 'api':
        this._changeApi(action);
        break;

      case 'nsfw':
        dispatch(setNsfw(action));
        setNsfw2DB(action);
        break;

      case 'theme':
        const setting = THEME_OPTIONS[action].value;
        const systemTheme = Appearance.getColorScheme();

        dispatch(setIsDarkTheme(setting === null ? systemTheme === 'dark' : setting));
        dispatch(setColorTheme(action));

        break;
      case settingsTypes.IMAGE_SERVER: {
        const server = IMAGE_SERVERS[action];
        if (server) {
          dispatch(setImageServer(server));
          dispatch(toastNotification(intl.formatMessage({ id: 'alert.successful' })));
        }
        break;
      }

      case settingsTypes.DM_PRIVACY: {
        const options: MattermostDmPrivacy[] = ['all', 'followers', 'none'];
        const nextValue = options[action] || 'all';
        try {
          const updated = await updateMattermostDmPrivacy(nextValue);
          this.setState({ dmPrivacy: updated });
          dispatch(toastNotification(intl.formatMessage({ id: 'settings.dm-privacy-updated' })));
        } catch {
          dispatch(toastNotification(intl.formatMessage({ id: 'settings.dm-privacy-failed' })));
        }
        break;
      }

      case settingsTypes.SUPPORT_BENEFICIARY_PERCENT: {
        const percent = SUPPORT_BENEFICIARY_PERCENTS[action];
        if (percent) {
          this._updateSupportSettings({ beneficiary_percent: percent });
        }
        break;
      }

      case settingsTypes.SUPPORT_CURATION_PERCENT: {
        const percent = SUPPORT_CURATION_PERCENTS[action];
        if (percent) {
          this._updateSupportSettings({ curation_percent: percent });
        }
        break;
      }

      default:
        break;
    }
  };

  _updateSupportSettings = async (partial: any) => {
    const { dispatch, intl, username, code, queryClient } = this.props as any;
    const { supportSettings } = this.state as any;

    // the update carries BOTH fields; never write from unknown state
    // (controls are hidden until loaded, this is a safety net)
    if (!supportSettings || !code) {
      return;
    }

    this._supportOpSeq += 1;
    const seq = this._supportOpSeq;
    this._supportSaveSeq += 1;
    const saveSeq = this._supportSaveSeq;

    const prevSettings = supportSettings;
    const nextSettings = { ...supportSettings, ...partial };

    this.setState({ supportSettings: nextSettings });

    const run = async () => {
      // a newer save supersedes this one; every save carries the full
      // payload, so skipping stale sends collapses redundant writes and
      // guarantees the server never applies them out of order
      if (saveSeq !== this._supportSaveSeq) return;
      try {
        const response = await updateSupportSettingsRequest(code, {
          beneficiary_percent: nextSettings.beneficiary_percent,
          curation_percent: nextSettings.curation_percent,
        });
        // even a stale save was accepted by the server: record it as the
        // rollback target for later failed saves (seq-guarded)
        if (isValidSupportSettings(response) && seq > this._confirmedSupportSeq) {
          this._confirmedSupportSettings = {
            beneficiary_percent: response.beneficiary_percent,
            curation_percent: response.curation_percent,
          };
          this._confirmedSupportSeq = seq;
        }
        // an older save must not overwrite state/cache a newer save produced
        if (seq !== this._supportOpSeq) return;
        if (isValidSupportSettings(response)) {
          this.setState({
            supportSettings: {
              beneficiary_percent: response.beneficiary_percent,
              curation_percent: response.curation_percent,
            },
          });
          applySupportSettingsUpdate(queryClient, username, response);
        } else {
          queryClient?.invalidateQueries({
            queryKey: getSupportSettingsQueryOptions(username, code).queryKey,
          });
        }
        dispatch(toastNotification(intl.formatMessage({ id: 'alert.successful' })));
      } catch {
        // a failed older save must not roll back a newer save's state
        if (seq !== this._supportOpSeq) return;
        // roll back to what the server last acknowledged; the snapshot taken
        // at save start may be an earlier save's unconfirmed optimistic value
        this.setState({ supportSettings: this._confirmedSupportSettings || prevSettings });
        dispatch(toastNotification(intl.formatMessage({ id: 'alert.fail' })));
      }
    };

    // strict FIFO: the request only goes out after every earlier one settled
    // (run never rejects, its try/catch covers the whole body)
    this._supportSaveChain = this._supportSaveChain.then(run);
    return this._supportSaveChain;
  };

  _changeApi = async (action: any) => {
    const { dispatch, selectedApi, intl, getServersQuery } = this.props as any;
    const serverList = getServersQuery.data;
    const server = serverList[action];
    let serverResp;
    let isError = false;
    let alertMessage;
    dispatch(setApi(''));

    this.setState({
      isLoading: true,
    });

    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 5000);
      const resp = await fetch(server, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          jsonrpc: '2.0',
          method: 'condenser_api.get_dynamic_global_properties',
          params: [],
          id: 1,
        }),
        signal: controller.signal,
      });
      clearTimeout(timeoutId);
      const json = await resp.json();
      if (!json.result || json.error) {
        throw new Error(json.error?.message || 'RPC error');
      }
      serverResp = json.result;
    } catch (e) {
      isError = true;
      alertMessage = 'alert.connection_fail';
    } finally {
      if (!isError) {
        alertMessage = 'alert.connection_success';
      }
    }

    if (!isError) {
      const localTime = new Date(new Date().toISOString().split('.')[0]);
      const serverTime = new Date(serverResp.time);
      const isAlive = (localTime as any) - (serverTime as any) < 15000;

      if (!isAlive) {
        alertMessage = 'settings.server_fail';

        isError = true;

        return;
      }
    }

    if (isError) {
      dispatch(setApi(selectedApi));
    } else {
      await setServer(server);
      dispatch(setApi(server));
      checkClient();
      // Sync SDK's internal dhive client with the selected server
      ConfigManager.setHiveNodes([server, ...serverList.filter((s: any) => s !== server)]);
    }

    this.setState({
      isLoading: false,
    });
    dispatch(
      toastNotification(
        intl.formatMessage({
          id: alertMessage,
        }),
      ),
    );
  };

  _currencyChange = (action: any) => {
    const { dispatch } = this.props;

    dispatch(setCurrency(CURRENCY_VALUE[action]));
    setCurrency2DB(CURRENCY_VALUE[action]);
  };

  _handleToggleChanged = (action: any, actionType: any) => {
    const { dispatch, isHideImages, navigation } = this.props;

    switch (actionType) {
      case 'notification':
      case 'notification.follow':
      case 'notification.vote':
      case 'notification.comment':
      case 'notification.mention':
      case 'notification.favorite':
      case 'notification.bookmark':
      case 'notification.tags':
      case 'notification.reblog':
      case 'notification.transfers':
      case 'notification.scheduledPublished':
      case 'notification.delegations':
      case 'notification.payouts':
      case 'notification.accountUpdate':
      case 'notification.weeklyEarnings':
        this._handleNotification(action, actionType);
        break;

      case 'default_footer':
        dispatch(isDefaultFooter(action));
        // setDefaultFooter(action);
        break;

      case 'pincode':
        if (action) {
          navigation.navigate(ROUTES.SCREENS.PINCODE, {
            callback: () => this._enableDefaultUnlockPin(action),
            isReset: true,
            isOldPinVerified: true,
            oldPinCode: Config.DEFAULT_PIN,
          });
        } else {
          navigation.navigate(ROUTES.SCREENS.PINCODE, {
            callback: () => this._enableDefaultUnlockPin(action),
          });
        }
        break;

      case 'biometric':
        navigation.navigate(ROUTES.SCREENS.PINCODE, {
          callback: () => dispatch(setIsBiometricEnabled(action)),
        });

        break;
      case settingsTypes.SHOW_HIDE_IMGS:
        dispatch(setHidePostsThumbnails(!isHideImages));
        break;
      case settingsTypes.SUPPORT_BENEFICIARY:
        this._updateSupportSettings({
          beneficiary_percent: action ? DEFAULT_SUPPORT_PERCENT : 0,
        });
        break;
      case settingsTypes.SUPPORT_CURATION:
        this._updateSupportSettings({
          curation_percent: action ? DEFAULT_SUPPORT_PERCENT : 0,
        });
        break;
      default:
        break;
    }
  };

  _handleNotification = async (action: any, actionType: any) => {
    const { dispatch, notificationDetails } = this.props;
    const notifyTypesConst = {
      vote: 1,
      mention: 2,
      follow: 3,
      comment: 4,
      reblog: 5,
      transfers: 6,
      favorite: 13,
      bookmark: 15,
      tags: 23,
      delegations: 10,
      payouts: 19,
      accountUpdate: 20,
      weeklyEarnings: 21,
      scheduledPublished: 22,
    };
    const notifyTypes: any[] = [];

    dispatch(
      changeNotificationSettings({
        action,
        type: actionType,
      }),
    );
    // TODO: remove setting notification settings
    setNotificationSettings({
      action,
      type: actionType,
    });

    Object.keys(notificationDetails).forEach((item) => {
      const notificationType = item.replace('Notification', '');

      if (notificationType === actionType.replace('notification.', '')) {
        if (action) {
          notifyTypes.push((notifyTypesConst as any)[notificationType]);
        }
      } else if (notificationDetails[item]) {
        notifyTypes.push((notifyTypesConst as any)[notificationType]);
      }
    });
    notifyTypes.sort();

    if (actionType === 'notification') {
      this._setPushToken(action ? notifyTypes : [], action);
    } else {
      this._setPushToken(notifyTypes, action);
    }
  };

  _handleButtonPress = (actionType: any) => {
    const { navigation, isPinCodeOpen, intl } = this.props as any;
    switch (actionType) {
      case 'reset_pin':
        navigation.navigate(ROUTES.SCREENS.PINCODE, {
          isReset: true,
        });
        break;

      case 'feedback':
        this._handleSendFeedback();
        break;

      case settingsTypes.RATE_APP:
        openStoreListing();
        break;

      case settingsTypes.EMAIL_DIGESTS:
        navigation.navigate(ROUTES.SCREENS.EMAIL_DIGESTS);
        break;

      case settingsTypes.BACKUP_PRIVATE_KEYS:
        if (isPinCodeOpen) {
          navigation.navigate(ROUTES.SCREENS.PINCODE, {
            navigateTo: ROUTES.SCREENS.BACKUP_KEYS,
          });
        } else {
          SheetManager.show(SheetNames.ACTION_MODAL, {
            payload: {
              title: intl.formatMessage({ id: 'alert.warning' }),
              body: intl.formatMessage({ id: 'settings.keys_warning' }),
              buttons: [
                {
                  text: intl.formatMessage({ id: 'alert.cancel' }),
                  onPress: () => {
                    console.log('cancel pressed');
                  },
                  type: 'destructive' as any,
                },
                {
                  text: intl.formatMessage({ id: 'settings.set_pin' }),
                  onPress: () => {
                    navigation.navigate(ROUTES.SCREENS.PINCODE, {
                      callback: () => {
                        this._enableDefaultUnlockPin(true);
                      },
                      navigateTo: ROUTES.SCREENS.BACKUP_KEYS,
                      isReset: true,
                      isOldPinVerified: true,
                      oldPinCode: Config.DEFAULT_PIN,
                    });
                  },
                },
              ],
            },
          });
        }
        break;

      case settingsTypes.DELETE_ACCOUNT:
        this._handleDeleteAccount();
        break;

      default:
        break;
    }
  };

  _handleOnChange = (action: any, type: any, actionType = null) => {
    switch (type) {
      case 'dropdown':
        this._handleDropdownSelected(action, actionType);
        break;

      case 'toggle':
        this._handleToggleChanged(action, actionType);
        break;

      default:
        break;
    }
  };

  _setPushToken = async (notifyTypes: any, enabled = true) => {
    const { isLoggedIn, otherAccounts = [], pinCode } = this.props;

    if (isLoggedIn) {
      await Promise.all(
        otherAccounts.map(async (item: any) => {
          try {
            const token = await getMessaging().getToken();

            const data = {
              username: item.username,
              token,
              system: `fcm-${Platform.OS}`,
              allows_notify: enabled ? 1 : 0,
              notify_types: notifyTypes,
            };

            if (item?.local?.accessToken && !pinCode) {
              console.warn('PIN required to decrypt access token for', data.username);
              return;
            }

            const accessToken =
              item?.local?.accessToken && pinCode
                ? decryptKey(item.local.accessToken, getDigitPinCode(pinCode))
                : undefined;

            if (!accessToken) {
              console.warn('Failed to decrypt access token for', data.username);
              return;
            }

            await saveNotificationSetting(
              accessToken,
              data.username,
              data.system,
              data.allows_notify,
              data.notify_types,
              data.token,
            );
          } catch (err) {
            console.warn('Failed to save notification setting for', item.username, err);
          }
        }),
      );
    }
  };

  _handleSendFeedback = async () => {
    const { dispatch, intl, currentAccount } = this.props;
    let message;

    const deviceName = await DeviceInfo.getDeviceName();
    const platform = `${deviceName} - ${Platform.OS === 'ios' ? 'iOS' : 'Android'} ${
      Platform.Version
    }`;
    const appVersion = `${DeviceInfo.getVersion()} (${DeviceInfo.getBuildNumber()})`;
    const username = currentAccount?.name || 'Unknown User';

    const _emailBody = intl.formatMessage(
      { id: 'settings.feedback_body' },
      { username, appVersion, platform },
    );

    await sendEmail('bug@ecency.com', 'Feedback/Bug report', _emailBody)
      .then(() => {
        message = 'settings.feedback_success';
      })
      .catch(() => {
        message = 'settings.feedback_fail';
      });

    if (message) {
      dispatch(
        toastNotification(
          intl.formatMessage({
            id: message,
          }),
        ),
      );
    }
  };

  _handleDeleteAccount = () => {
    const { dispatch, intl, currentAccount } = this.props;

    const _onConfirm = () => {
      deleteAccount(currentAccount.name, '')
        .then(() => {
          dispatch(
            toastNotification(
              intl.formatMessage({
                id: 'delete.request_sent',
              }),
            ),
          );
          dispatch(logout());
        })
        .catch(() => {
          dispatch(
            toastNotification(
              intl.formatMessage({
                id: 'delete.request_sent',
              }),
            ),
          );
          dispatch(logout());
        });
    };

    SheetManager.show(SheetNames.ACTION_MODAL, {
      payload: {
        title: intl.formatMessage({ id: 'delete.confirm_delete_title' }),
        body: intl.formatMessage({ id: 'delete.confirm_delete_body' }),
        buttons: [
          {
            text: intl.formatMessage({ id: 'alert.cancel' }),
            onPress: () => {
              console.log('cancel pressed');
            },
          },
          {
            text: intl.formatMessage({ id: 'alert.delete' }),
            onPress: _onConfirm,
          },
        ],
      },
    });
  };

  _clearUserData = async () => {
    const { otherAccounts, currentAccount, pinCode, dispatch } = this.props;

    // Every account leaves the device: stop their pushes and drop the device token.
    // Tokens are read now, before the data below is wiped.
    disablePushRegistrations(getPushAccounts(currentAccount, otherAccounts, pinCode), {
      deleteToken: true,
    });

    await removeAllUserData()
      .then(async () => {
        dispatch(updateCurrentAccount({}));
        dispatch(login(false));
        removePinCode();
        setAuthStatus({ isLoggedIn: false });
        setExistUser(false);
        if (otherAccounts.length > 0) {
          otherAccounts.map((item: any) => dispatch(removeOtherAccount(item.username)));
        }
        // Drop the shared Mattermost PAT and any cached/in-flight bootstrap
        // so a request already on the wire can't resurrect the session for
        // the user we just wiped.
        setChatApiToken(null);
        clearMattermostBootstrapCache();
        dispatch(logoutDone());
        dispatch(isPinCodeOpen(false));
      })
      .catch((err) => {
        console.warn('Failed to remove user data', err);
      });
  };

  _onDecryptFail = () => {
    const { intl } = this.props;
    setTimeout(() => {
      Alert.alert(
        intl.formatMessage({
          id: 'alert.warning',
        }),
        intl.formatMessage({
          id: 'alert.decrypt_fail_alert',
        }),
        [
          { text: intl.formatMessage({ id: 'alert.clear' }), onPress: () => this._clearUserData() },
          { text: intl.formatMessage({ id: 'alert.cancel' }), style: 'destructive' },
        ],
      );
    }, 500);
  };

  _enableDefaultUnlockPin = (isEnabled: any) => {
    const { dispatch, encUnlockPin } = this.props;

    dispatch(isPinCodeOpen(isEnabled));

    if (!isEnabled) {
      const oldPinCode = decryptKey(encUnlockPin, Config.PIN_KEY, this._onDecryptFail);

      if (oldPinCode === undefined) {
        return;
      }

      const encryptedPin = encryptKey(Config.DEFAULT_PIN!, Config.PIN_KEY!);
      dispatch(setEncryptedUnlockPin(encryptedPin));
    }
  };

  render() {
    const { isNotificationMenuOpen, isLoading, dmPrivacy, supportSettings } = this.state as any;
    const { colorTheme, getServersQuery } = this.props as any;
    const serverList = getServersQuery.data;

    return (
      <SettingsScreen
        serverList={serverList}
        handleOnChange={this._handleOnChange}
        isNotificationMenuOpen={isNotificationMenuOpen}
        handleOnButtonPress={this._handleButtonPress}
        isLoading={isLoading}
        colorThemeIndex={colorTheme}
        dmPrivacy={dmPrivacy}
        supportSettings={supportSettings}
        {...this.props}
      />
    );
  }
}

const mapStateToProps = (state: any) => {
  const notificationDetails = selectNotificationDetails(state);
  return {
    isDarkTheme: selectIsDarkTheme(state),
    colorTheme: selectColorTheme(state),
    isPinCodeOpen: selectIsPinCodeOpen(state),
    encUnlockPin: selectEncUnlockPin(state),
    isBiometricEnabled: selectIsBiometricEnabled(state),
    isDefaultFooter: selectIsDefaultFooter(state),
    isLoggedIn: selectIsLoggedIn(state),
    isNotificationSettingsOpen: selectIsNotificationOpen(state),
    isFCMAvailable: selectIsFCMAvailable(state),
    nsfw: selectNsfw(state),
    notificationDetails,
    commentNotification: notificationDetails.commentNotification,
    followNotification: notificationDetails.followNotification,
    mentionNotification: notificationDetails.mentionNotification,
    favoriteNotification: notificationDetails.favoriteNotification,
    bookmarkNotification: notificationDetails.bookmarkNotification,
    tagsNotification: notificationDetails.tagsNotification,
    reblogNotification: notificationDetails.reblogNotification,
    transfersNotification: notificationDetails.transfersNotification,
    voteNotification: notificationDetails.voteNotification,
    scheduledPublishedNotification: notificationDetails.scheduledPublishedNotification,
    delegationsNotification: notificationDetails.delegationsNotification,
    payoutsNotification: notificationDetails.payoutsNotification,
    accountUpdateNotification: notificationDetails.accountUpdateNotification,
    weeklyEarningsNotification: notificationDetails.weeklyEarningsNotification,
    selectedApi: selectApi(state),
    selectedCurrency: selectCurrency(state),
    selectedLanguage: selectLanguage(state),
    username: selectCurrentAccount(state)?.name,
    currentAccount: selectCurrentAccount(state),
    pinCode: selectPin(state),
    otherAccounts: selectOtherAccounts(state),
    isHideImages: selectHidePostsThumbnails(state),
    selectedImageServer: selectImageServer(state),
  };
};

const mapHooksToProps = (props: any) => {
  const navigation = useNavigation();
  const getServersQuery = useGetServersQuery();
  const queryClient = useQueryClient();
  // decrypted access token for the SDK support settings query/update
  const { code } = useAuth();
  return (
    <SettingsContainer
      {...props}
      navigation={navigation}
      getServersQuery={getServersQuery}
      queryClient={queryClient}
      code={code}
    />
  );
};
export default gestureHandlerRootHOC(connect(mapStateToProps)(injectIntl(mapHooksToProps)));
