import React, { PureComponent } from 'react';
import { Alert } from 'react-native';
import { connect } from 'react-redux';
import { injectIntl } from 'react-intl';
import Config from 'react-native-config';

// Services and Actions
import { useNavigation } from '@react-navigation/native';
import { gestureHandlerRootHOC } from 'react-native-gesture-handler';
import { SheetManager } from 'react-native-actions-sheet';
import { getAccountsQueryOptions } from '@ecency/sdk';
import { captureException } from '../../../utils/sentryUtils';
import { getQueryClient } from '../../../providers/queries';
import { login, loginWithSC2 } from '../../../providers/hive/auth';

import {
  failedAccount,
  addOtherAccount,
  updateCurrentAccount,
  setPrevLoggedInUsers,
} from '../../../redux/actions/accountAction';
import { login as loginAction, setPinCode } from '../../../redux/actions/applicationActions';
import { setInitPosts, setFeedPosts } from '../../../redux/actions/postsAction';
import { setExistUser } from '../../../storage/storage';
import { decodeBase64, encryptKey } from '../../../utils/crypto';

// Middleware

// Constants
import ROUTES from '../../../constants/routeNames';

// Utilities

// Component
import LoginScreen from '../screen/loginScreen';
import persistAccountGenerator from '../../../utils/persistAccountGenerator';
import { fetchSubscribedCommunities } from '../../../redux/actions/communitiesAction';
import { UserAvatar } from '../../../components';
import { useUserActivityMutation } from '../../../providers/queries/pointQueries';
import { PointActivityIds } from '../../../providers/ecency/ecency.types';
import { SheetNames } from '../../../navigation/sheets';
import {
  selectIsPinCodeOpen,
  selectIsConnected,
  selectPrevLoggedInUsers,
} from '../../../redux/selectors';

/*
 *            Props Name        Description                                     Value
 *@props -->  props name here   description here                                Value Type Here
 *
 */

class LoginContainer extends PureComponent<any, any> {
  constructor(props: any) {
    super(props);

    this.state = {
      isLoading: false,
    };
  }

  // Component Life Cycle Functions
  componentDidMount() {
    // TOOD: migrate getParam to routes.state.param after nt/navigaiton merge

    const { route } = this.props;
    const username = route.params?.username ?? '';
    const code: string = route.params?.code ?? '';

    if (username && code) {
      this._confirmCodeLogin(username, code);
    }
  }

  // Component Functions
  _confirmCodeLogin = (username: any, code: any) => {
    const { intl } = this.props;

    try {
      // check accessCode formatting and compare expiry
      const dataStr = decodeBase64(code);
      if (!dataStr) {
        throw new Error('login.deep_login_malformed_url');
      }

      const data = JSON.parse(dataStr.slice(0, dataStr.lastIndexOf('}') + 1));

      const curTimestamp = new Date().getTime();
      const expiryTimestamp = data && data.timestamp && data.timestamp * 1000 + 604800000; // add 7 day (604800000ms) expiry

      if (!expiryTimestamp || expiryTimestamp < curTimestamp) {
        throw new Error('login.deep_login_url_expired');
      }

      // Everything is set, show login confirmation
      SheetManager.show(SheetNames.ACTION_MODAL, {
        payload: {
          title: intl.formatMessage({ id: 'login.deep_login_alert_title' }, { username }),
          body: intl.formatMessage({ id: 'login.deep_login_alert_body' }),
          buttons: [
            {
              text: intl.formatMessage({ id: 'alert.cancel' }),
              onPress: () => console.log('Cancel'),
              style: 'cancel',
            },
            {
              text: intl.formatMessage({ id: 'alert.confirm' }),
              onPress: () => this._loginWithCode(code),
            },
          ],
          headerContent: <UserAvatar username={username} size="xl" />,
        },
      });
    } catch (err) {
      console.warn('Failed to login using code', err);
      Alert.alert(
        intl.formatMessage({ id: 'alert.fail' }),
        intl.formatMessage({ id: (err as any).message }),
      );
    }
  };

  _loginWithCode = (code: any) => {
    const { dispatch, isPinCodeOpen, navigation, intl } = this.props;
    this.setState({ isLoading: true });
    loginWithSC2(code)
      .then((result) => {
        if (result) {
          const persistAccountData = persistAccountGenerator(result);

          dispatch(updateCurrentAccount({ ...result }));
          dispatch(fetchSubscribedCommunities(result.username));
          dispatch(addOtherAccount({ ...persistAccountData }));
          dispatch(loginAction(true));

          if (isPinCodeOpen) {
            navigation.navigate({
              name: ROUTES.SCREENS.PINCODE,
              params: {
                navigateTo: ROUTES.DRAWER.MAIN,
              },
            });
          } else {
            navigation.navigate({ name: ROUTES.DRAWER.MAIN });
          }
        } else {
          // TODO: Error alert (Toast Message)
        }
        this.setState({ isLoading: false });
      })
      .catch((error) => {
        this.setState({ isLoading: false });
        Alert.alert(
          intl.formatMessage({ id: 'alert.fail' }),
          intl.formatMessage({ id: error.message }),
        );
      });
  };

  // update previously loggedin users list,
  _updatePrevLoggedInUsersList = (username: string) => {
    const { dispatch, prevLoggedInUsers } = this.props as any;
    if (prevLoggedInUsers && prevLoggedInUsers.length > 0) {
      const userIndex = prevLoggedInUsers.findIndex((el: any) => el?.username === username);
      if (userIndex > -1) {
        const updatedPrevLoggedInUsers = prevLoggedInUsers;
        updatedPrevLoggedInUsers[userIndex] = { username, isLoggedOut: false };
        dispatch(setPrevLoggedInUsers(updatedPrevLoggedInUsers));
      } else {
        const u = { username, isLoggedOut: false };
        dispatch(setPrevLoggedInUsers([...prevLoggedInUsers, u]));
      }
    } else {
      const u = { username, isLoggedOut: false };
      dispatch(setPrevLoggedInUsers([u]));
    }
  };

  _handleOnPressLogin = (username: any, password: any) => {
    const { dispatch, intl, isPinCodeOpen, navigation, userActivityMutation } = this.props as any;

    this.setState({ isLoading: true });

    login(username, password)
      .then((result) => {
        if (result) {
          const persistAccountData = persistAccountGenerator(result);

          dispatch(updateCurrentAccount({ ...result }));
          dispatch(fetchSubscribedCommunities(username));
          dispatch(addOtherAccount({ ...persistAccountData }));
          dispatch(loginAction(true));
          dispatch(setInitPosts([]));
          dispatch(setFeedPosts([]));
          this._updatePrevLoggedInUsersList(username);
          // track user activity for login
          userActivityMutation.mutate({ pointsTy: PointActivityIds.LOGIN });
          setExistUser(true);
          const encryptedPin = encryptKey(Config.DEFAULT_PIN!, Config.PIN_KEY!);
          dispatch(setPinCode(encryptedPin));

          if (isPinCodeOpen) {
            navigation.navigate({
              name: ROUTES.SCREENS.PINCODE,
              params: {
                navigateTo: ROUTES.DRAWER.MAIN,
              },
            });
          } else {
            navigation.navigate({
              name: ROUTES.DRAWER.MAIN,
            });
          }
        }
      })
      .catch((err) => {
        // if error description exist, pass it to alert else pass error message key
        const errorDescription = err?.response?.data?.error_description
          ? err?.response?.data?.error_description
          : intl.formatMessage({
              id: err.message,
            });
        Alert.alert(
          intl.formatMessage({
            id: 'login.login_failed',
          }),
          ` ${errorDescription}\n${intl.formatMessage({ id: 'login.login_failed_body' })}`, // append login body failure key
        );
        dispatch(failedAccount(err.message));
        this.setState({ isLoading: false });

        captureException(err, (scope) => {
          scope.setTag('context', 'key-login-failure');
          scope.setUser({ username });
        });
      });
  };

  _getAccountsWithUsername = async (username: any) => {
    const { isConnected } = this.props;

    if (!isConnected) {
      return null;
    }

    try {
      const queryClient = getQueryClient();
      const accounts = await queryClient.fetchQuery(getAccountsQueryOptions([username]));

      return accounts;
    } catch (error) {
      return null;
    }
  };

  _handleSignUp = () => {
    const { navigation } = this.props;

    navigation.replace(ROUTES.SCREENS.REGISTER);
  };

  render() {
    const { navigation, route } = this.props as any;
    const { isLoading } = this.state as any;

    const initialUsername = route.params?.username ?? '';

    return (
      <LoginScreen
        initialUsername={initialUsername}
        navigation={navigation}
        handleOnPressLogin={this._handleOnPressLogin}
        getAccountsWithUsername={this._getAccountsWithUsername}
        handleSignUp={this._handleSignUp}
        isLoading={isLoading}
      />
    );
  }
}

const mapStateToProps = (state: any) => ({
  account: state.accounts,
  isConnected: selectIsConnected(state),
  isPinCodeOpen: selectIsPinCodeOpen(state),
  prevLoggedInUsers: selectPrevLoggedInUsers(state),
});

const mapHooksToProps = () => ({
  navigation: useNavigation(),
  userActivityMutation: useUserActivityMutation(),
});

export default gestureHandlerRootHOC(
  connect(mapStateToProps)(
    injectIntl((props) => <LoginContainer {...props} {...mapHooksToProps()} />),
  ),
);
