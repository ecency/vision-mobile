import React, { useEffect, useRef, useState } from 'react';
import { useDispatch } from 'react-redux';

import { useIntl } from 'react-intl';
import { Alert } from 'react-native';
import { SheetManager } from 'react-native-actions-sheet';
import { getMutedUsersQueryOptions } from '@ecency/sdk';
import RootNavigation, { NavigateOptions } from '../../../navigation/rootNavigation';
import { NavigateArgs, RouteName } from '../../../navigation/types';

import { setPrevLoggedInUsers, updateCurrentAccount } from '../../../redux/actions/accountAction';

import {
  migrateToMasterKeyWithAccessToken,
  refreshSCToken,
  switchAccount,
} from '../../../providers/hive/auth';
import { getUserDataWithUsername } from '../../../storage/storage';

import { logout } from '../../../redux/actions/uiAction';
import AccountsBottomSheet from '../view/accountsBottomSheetView';

// Constants
import AUTH_TYPE from '../../../constants/authType';
import { getDigitPinCode } from '../../../providers/hive/hive';
import { fetchUnreadActivityCount, getQueryClient } from '../../../providers/queries';

import { useAppSelector } from '../../../hooks';
import {
  selectCurrentAccount,
  selectPin,
  selectIsLoggedIn,
  selectOtherAccounts,
  selectPrevLoggedInUsers,
} from '../../../redux/selectors';
import { getPointsSummary } from '../../../providers/ecency/ePoint';
import { clearSubscribedCommunitiesCache } from '../../../redux/actions/cacheActions';
import { fetchSubscribedCommunities } from '../../../redux/actions/communitiesAction';
import { decryptKey } from '../../../utils/crypto';
import { repairUserAccountData } from '../../../utils/migrationHelpers';
import ROUTES from '../../../constants/routeNames';
import { SheetNames } from '../../../navigation/sheets';

const AccountsBottomSheetContainer = () => {
  const intl = useIntl();
  const dispatch = useDispatch();

  const accountsBottomSheetViewRef = useRef<any>(null);

  const currentAccount = useAppSelector(selectCurrentAccount);
  const accounts = useAppSelector(selectOtherAccounts);
  const pinHash = useAppSelector(selectPin);
  const prevLoggedInUsers = useAppSelector(selectPrevLoggedInUsers);
  const isLoggedIn = useAppSelector(selectIsLoggedIn);
  const [isSwitching, setIsSwitching] = useState(false);

  useEffect(() => {
    accountsBottomSheetViewRef.current?.showAccountsBottomSheet();
    _checkPrevLoggedInUsersList();
  }, []);

  // checks if prevLoggedInUsers do not contain any invalid value and filters the array from invalid data
  const _checkPrevLoggedInUsersList = () => {
    if (prevLoggedInUsers && prevLoggedInUsers.length > 0) {
      const filteredUsersList = prevLoggedInUsers.filter((el: any) => el.username);
      dispatch(setPrevLoggedInUsers(filteredUsersList));
    }
  };

  const _navigateToRoute = <K extends RouteName>(...[name, params]: NavigateArgs<K>) => {
    SheetManager.hide(SheetNames.ACCOUNTS_SHEET);
    accountsBottomSheetViewRef.current?.closeAccountsBottomSheet();
    if (name) {
      // Correlated by the generic at the call site; TS cannot re-derive that pairing here.
      RootNavigation.navigate({ name, params } as NavigateOptions);
    }
  };

  const _switchAccount = async (account: any = {}) => {
    if (currentAccount && account && account.username !== currentAccount.name) {
      _handleSwitch(account);
    }
  };

  const _logout = async () => {
    dispatch(logout());
  };

  const _checkHiveAuthExpiry = (authData: any) => {
    if (
      authData?.username &&
      authData.authType === AUTH_TYPE.HIVE_AUTH &&
      authData.hiveAuthExpiry &&
      typeof authData.hiveAuthExpiry === 'number' &&
      authData.hiveAuthExpiry > 0
    ) {
      const curTime = new Date().getTime();
      if (curTime > authData.hiveAuthExpiry) {
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
                    params: { username: authData.username },
                  });
                },
              },
            ],
          },
        });
      }
    }
  };

  const _handleSwitch = async (switchingAccount: any = {}) => {
    try {
      setIsSwitching(true);
      const accountData = accounts.filter(
        (account: any) => account.username === switchingAccount.username,
      )[0];

      // fetch upto data account data nd update current account;
      let _currentAccount: any = await switchAccount(accountData.username);
      let realmData = await getUserDataWithUsername(accountData.username);

      if (!realmData[0]) {
        realmData = await repairUserAccountData(
          _currentAccount.name,
          dispatch,
          intl,
          accounts,
          pinHash,
        );
        if (!realmData[0]) {
          return;
        }
      }

      [_currentAccount.local] = realmData;

      if (_currentAccount.local.authType === AUTH_TYPE.HIVE_AUTH) {
        _checkHiveAuthExpiry(_currentAccount.local);
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
          pinHash,
        );
      }

      // refresh access token
      const encryptedAccessToken = await refreshSCToken(
        _currentAccount.local,
        getDigitPinCode(pinHash),
      );

      _currentAccount.local.accessToken = encryptedAccessToken;

      // Optional data: a failed request must not fail the switch, as in the app container.
      try {
        const queryClient = getQueryClient();
        const accessToken = decryptKey(encryptedAccessToken, getDigitPinCode(pinHash)) ?? '';
        _currentAccount.unread_activity_count =
          (await fetchUnreadActivityCount(_currentAccount.name, accessToken)) ?? 0;
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
      dispatch(clearSubscribedCommunitiesCache());
      dispatch(fetchSubscribedCommunities(_currentAccount.name) as any);
    } catch (error) {
      Alert.alert(
        intl.formatMessage({
          id: 'alert.fail',
        }),
        (error as any).message,
        [
          { text: intl.formatMessage({ id: 'side_menu.logout' }), onPress: () => _logout() },
          { text: intl.formatMessage({ id: 'alert.cancel' }), style: 'destructive' },
        ],
      );
    }
    setIsSwitching(false);
    SheetManager.hide(SheetNames.ACCOUNTS_SHEET);
    accountsBottomSheetViewRef.current?.closeAccountsBottomSheet();
  };

  return (
    <AccountsBottomSheet
      ref={accountsBottomSheetViewRef}
      accounts={accounts}
      currentAccount={currentAccount}
      navigateToRoute={_navigateToRoute}
      switchAccount={_switchAccount}
      prevLoggedInUsers={prevLoggedInUsers}
      dispatch={dispatch}
      isLoggedIn={isLoggedIn}
      isSwitching={isSwitching}
    />
  );
};

export default AccountsBottomSheetContainer;
