import React, { Component } from 'react';
import { Alert, Platform } from 'react-native';
import { connect } from 'react-redux';
import { injectIntl } from 'react-intl';
import Config from 'react-native-config';
import get from 'lodash/get';
import * as LocalAuthentication from 'expo-local-authentication';

// Actions & Services
import { useNavigation } from '@react-navigation/native';
import RootNavigation from '../../../navigation/rootNavigation';
import { updatePinCode } from '../../../providers/hive/auth';
import {
  isPinCodeOpen,
  isRenderRequired,
  login,
  setEncryptedUnlockPin,
} from '../../../redux/actions/applicationActions';
import {
  setExistUser,
  removeAllUserData,
  removePinCode,
  setAuthStatus,
} from '../../../storage/storage';
import { updateCurrentAccount, removeOtherAccount } from '../../../redux/actions/accountAction';
import {
  selectCurrentAccount,
  selectPin,
  selectOtherAccounts,
  selectEncUnlockPin,
  selectIsBiometricEnabled,
} from '../../../redux/selectors';

// Utils
import { encryptKey, decryptKey } from '../../../utils/crypto';
import MigrationHelpers from '../../../utils/migrationHelpers';
import { disablePushRegistrations, getPushAccounts } from '../../../utils/pushRegistration';

// Component
import PinCodeView from '../children/pinCodeView';
import { logout, logoutDone } from '../../../redux/actions/uiAction';

class PinCodeContainer extends Component<any, any> {
  screenRef: any = null;

  constructor(props: any) {
    super(props);

    this.state = {
      informationText: '',
      newPinCode: null,
      isOldPinVerified: get(props.pinCodeParams, 'isOldPinVerified', false),
      oldPinCode: get(props.pinCodeParams, 'oldPinCode', null),
      failedAttempts: 0,
    };
  }

  // sets initial pin code screen label based on oldPinVerified param/state
  componentDidMount() {
    const { intl } = this.props;
    const { isOldPinVerified } = this.state;

    if (!isOldPinVerified) {
      this.setState({
        informationText: intl.formatMessage({
          id: 'pincode.enter_text',
        }),
      });
    } else {
      this.setState({
        informationText: intl.formatMessage({
          id: 'pincode.set_new',
        }),
      });
    }

    this._processBiometric();
  }

  _processBiometric = async () => {
    try {
      const {
        intl,
        pinCodeParams: { isReset },
        applicationPinCode,
        encUnlockPin,
        isBiometricEnabled,
      } = this.props;

      const _hasHardware = await LocalAuthentication.hasHardwareAsync();
      const _isEnrolled = await LocalAuthentication.isEnrolledAsync();

      if (isReset || !isBiometricEnabled || !_hasHardware || !_isEnrolled) {
        return;
      }

      const authResp = await LocalAuthentication.authenticateAsync({
        promptMessage: intl.formatMessage({ id: 'pincode.biometric_desc' }),
      });

      if (!authResp.success) {
        throw new Error('Authentication Failed');
      }

      console.log('successfully passed biometric auth');

      // code gets here means biometeric succeeded
      if (this.screenRef) {
        const encPin = encUnlockPin || applicationPinCode;
        const verifiedPin = decryptKey(encPin, Config.PIN_KEY, this._onDecryptFail);
        this.screenRef.setPinThroughBiometric(verifiedPin);
      }
    } catch (err) {
      console.warn('Failed to process biometric', err);
    }

    if (Platform.OS === 'android') {
      LocalAuthentication.cancelAuthenticate();
    }
  };

  // this function updates realm with appropriate master key required for encyrption
  // this function is important: must run while chaning pin
  // and even logging in with existing pin code

  _updatePinCodeRealm = async (pinData: any) => {
    try {
      const { currentAccount, dispatch } = this.props;
      const response = await updatePinCode(pinData);
      if (!response) {
        return false;
      }
      const _currentAccount = currentAccount;
      _currentAccount.local = response;
      dispatch(updateCurrentAccount({ ..._currentAccount }));
      return true;
    } catch (err) {
      this._onDecryptFail();
      return false;
    }
  };

  // routine for checking and setting new pin code, same routine is used for
  // setting pin for the first time
  _resetPinCode = (pin: any) =>
    new Promise<void>((resolve, reject) => {
      const {
        pinCodeParams: { navigateTo, navigateParams, navigateKey, callback },
        encUnlockPin,
        intl,
        navigation,
      } = this.props;
      const { isOldPinVerified, oldPinCode, newPinCode } = this.state;

      // if old pin already verified, check new pin setup conditions.
      if (isOldPinVerified) {
        // if newPin already exist and pin is a valid pin, compare and set new pin
        if (pin !== undefined && pin === newPinCode) {
          this._savePinCode(pin);
          if (callback) {
            callback(pin, oldPinCode);
          }

          if (navigateTo) {
            RootNavigation.navigate({
              name: navigateTo,
              params: navigateParams,
              key: navigateKey || '',
            });
          } else {
            navigation.goBack();
          }
          resolve();
        }

        // if newPin code exists and above case failed, that means pins did not match
        // warn user about it and do nothing
        else if (newPinCode) {
          Alert.alert(
            intl.formatMessage({
              id: 'alert.warning',
            }),
            intl.formatMessage({
              id: 'pincode.pin_not_matched',
            }),
          );
          resolve();
        }

        // if newPinCode do yet exist, save it in state and prompt user to reneter pin.
        else {
          this.setState({
            informationText: intl.formatMessage({
              id: 'pincode.write_again',
            }),
            newPinCode: pin,
          });
          resolve();
        }
      }

      // if old pin code is not yet verified attempt to verify code
      else {
        const unlockPin = decryptKey(encUnlockPin, Config.PIN_KEY);

        // check if pins match
        if (unlockPin !== pin) {
          const err = new Error('alert.invalid_pincode');
          reject(err);
          return;
        }

        this.setState({ isOldPinVerified: true });
        this.setState({
          informationText: intl.formatMessage({
            id: 'pincode.set_new',
          }),
          newPinCode: null,
          oldPinCode: pin,
        });
        resolve();
      }
    });

  _onRefreshTokenFailed = (error: any) => {
    setTimeout(() => {
      const { dispatch, intl } = this.props;
      const _logout = () => dispatch(logout());
      Alert.alert(
        intl.formatMessage({
          id: 'alert.fail',
        }),
        error.message,
        [
          { text: intl.formatMessage({ id: 'side_menu.logout' }), onPress: _logout },
          { text: intl.formatMessage({ id: 'alert.cancel' }), style: 'destructive' },
        ],
      );
    }, 300);
  };

  // verifies is the pin entered is right or wrong, also migrates to newer locking method
  _verifyPinCode = async (pin: any) => {
    const {
      intl,
      currentAccount,
      dispatch,
      encUnlockPin,
      applicationPinCode,
      pinCodeParams: { navigateTo, navigateParams, navigateKey, callback },
      navigation,
    } = this.props;
    const { oldPinCode } = this.state;

    const unlockPin = encUnlockPin
      ? decryptKey(encUnlockPin, Config.PIN_KEY)
      : decryptKey(applicationPinCode, Config.PIN_KEY);

    // check if pins match
    if (unlockPin !== pin) {
      throw new Error(
        intl.formatMessage({
          id: 'alert.invalid_pincode',
        }),
      );
    }

    // migrate data to default pin if encUnlockPin is not set.
    if (!encUnlockPin) {
      const migrated = await MigrationHelpers.migrateUserEncryption(
        dispatch,
        currentAccount,
        applicationPinCode,
        this._onRefreshTokenFailed,
      );
      // Migration failed — keys are still on the old PIN, so any signing the
      // caller would trigger next will fail. _onRefreshTokenFailed has already
      // surfaced the error/re-login; do not advance into the requested operation.
      if (!migrated) {
        return false;
      }
    }

    // on successful code verification run requested operation passed as props
    if (callback) {
      callback(pin, oldPinCode);
    }

    if (navigateTo) {
      RootNavigation.navigate({
        name: navigateTo,
        params: navigateParams,
        key: navigateKey || '',
      });
    } else {
      navigation.goBack();
    }

    return true;
  };

  // encryptes and saved unlockPin
  _savePinCode = (pin: any) => {
    const { dispatch } = this.props;
    const encryptedPin = encryptKey(pin, Config.PIN_KEY!);
    dispatch(setEncryptedUnlockPin(encryptedPin));
  };

  _forgotPinCode = async () => {
    const { otherAccounts, currentAccount, applicationPinCode, dispatch } = this.props;

    // Every account leaves the device: stop their pushes and drop the device token.
    // Tokens are read now, before the data below is wiped.
    disablePushRegistrations(getPushAccounts(currentAccount, otherAccounts, applicationPinCode), {
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
        dispatch(logoutDone());
        dispatch(isPinCodeOpen(false));
        dispatch(isRenderRequired(true));
      })
      .catch((err) => {
        console.warn('Failed to remove user data', err);
      });
  };

  _handleFailedAttempt = (error: any) => {
    console.warn('Failed to set pin: ', error);
    const { intl } = this.props;
    const { failedAttempts } = this.state;
    // increment failed attempt
    const totalAttempts = failedAttempts + 1;

    if (totalAttempts < 3) {
      // shwo failure alert box
      Alert.alert(
        intl.formatMessage({
          id: 'alert.warning',
        }),
        intl.formatMessage({
          id: error.message,
        }),
      );

      let infoMessage = intl.formatMessage({
        id: 'pincode.enter_text',
      });
      infoMessage += `, ${totalAttempts} ${intl.formatMessage({ id: 'pincode.attempts_postfix' })}`;
      if (totalAttempts > 1) {
        infoMessage += `\n${intl.formatMessage({ id: 'pincode.message_reset_warning' })}`;
      }
      this.setState({
        failedAttempts: totalAttempts,
        informationText: infoMessage,
      });

      return false;
    } else {
      // wipe user data
      this._forgotPinCode();
      return true;
    }
  };

  _onDecryptFail = () => {
    const { intl } = this.props;
    Alert.alert(
      intl.formatMessage({
        id: 'alert.warning',
      }),
      intl.formatMessage({
        id: 'alert.decrypt_fail_alert',
      }),
      [
        { text: intl.formatMessage({ id: 'alert.clear' }), onPress: () => this._forgotPinCode() },
        { text: intl.formatMessage({ id: 'alert.cancel' }), style: 'destructive' },
      ],
    );
  };

  _setPinCode = async (pin: any, isReset: any) => {
    try {
      // check if reset routine is triggered by user, reroute code to reset hanlder
      if (isReset) {
        await this._resetPinCode(pin);
      } else {
        await this._verifyPinCode(pin);
      }

      return true;
    } catch (error) {
      return this._handleFailedAttempt(error);
    }
  };

  _handleForgotButton = () => {
    const { intl } = this.props;

    Alert.alert(
      intl.formatMessage({
        id: 'alert.warning',
      }),
      intl.formatMessage({
        id: 'alert.clear_user_alert',
      }),
      [
        { text: intl.formatMessage({ id: 'alert.clear' }), onPress: () => this._forgotPinCode() },
        { text: intl.formatMessage({ id: 'alert.cancel' }), style: 'destructive' },
      ],
    );
  };

  render() {
    const {
      currentAccount,
      intl,
      pinCodeParams: { isReset },
    } = this.props;
    const { informationText, isOldPinVerified } = this.state;

    return (
      <PinCodeView
        ref={(ref) => {
          this.screenRef = ref;
        }}
        informationText={informationText}
        setPinCode={(pin: any) => this._setPinCode(pin, isReset)}
        showForgotButton={!isOldPinVerified}
        username={currentAccount.name}
        intl={intl}
        handleForgotButton={() => this._handleForgotButton()}
        isReset={isReset}
        {...this.props}
      />
    );
  }
}

const mapStateToProps = (state: any) => ({
  currentAccount: selectCurrentAccount(state),
  applicationPinCode: selectPin(state),
  encUnlockPin: selectEncUnlockPin(state),
  otherAccounts: selectOtherAccounts(state),
  isBiometricEnabled: selectIsBiometricEnabled(state),
});

const mapHooksToProps = (props: any) => {
  const navigation = useNavigation();
  return <PinCodeContainer {...props} navigation={navigation} />;
};

export default connect(mapStateToProps)(injectIntl(mapHooksToProps));
