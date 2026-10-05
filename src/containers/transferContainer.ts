import React, { Component } from 'react';
import { Alert } from 'react-native';
import { connect } from 'react-redux';
import { injectIntl } from 'react-intl';
import get from 'lodash/get';

// Services and Actions
import * as Sentry from '@sentry/react-native';
import { useNavigation } from '@react-navigation/native';
import {
  lookupAccountsQueryOptions,
  getAccountsQueryOptions,
  getRecurrentTransfersQueryOptions,
} from '@ecency/sdk';
import { captureException } from '../utils/sentryUtils';
import { selectCurrentAccount, selectGlobalProps, selectOtherAccounts } from '../redux/selectors';
import { useTransferMutations } from '../hooks';
import { getQueryClient } from '../providers/queries';
import QUERIES from '../providers/queries/queryKeys';
import { toastNotification } from '../redux/actions/uiAction';
import { getUserDataWithUsername } from '../storage/storage';
import { getPointsSummary } from '../providers/ecency/ePoint';

// Utils
import { getAssetPrecision, toFixedNoExp, formatTokenQuantity } from '../utils/number';
import { fetchTokenBalances, fetchTokens } from '../providers/hive-engine/hiveEngine';
import TransferTypes from '../constants/transferTypes';
import TokenLayers from '../constants/tokenLayers';
import { normalizeTransferType, getNativeAccountBalance } from '../utils/transferBalance';

/*
 *            Props Name        Description                                     Value
 *@props -->  props name here   description here                                Value Type Here
 *
 */

class TransferContainer extends Component<any, any> {
  constructor(props: any) {
    super(props);
    const routeParams = props.route.params ?? {};
    const transferType = normalizeTransferType(routeParams.transferType ?? '');
    const fundType = routeParams.fundType ?? '';
    const initialBalance =
      routeParams.balance ??
      getNativeAccountBalance(props.currentAccount, transferType, fundType) ??
      '';

    this.state = {
      fundType,
      balance: initialBalance,
      tokenAddress: routeParams.tokenAddress ?? '',
      transferType,
      referredUsername: routeParams.referredUsername,
      selectedAccount: props.currentAccount,
      initialAmount: routeParams.initialAmount,
      initialMemo: routeParams.initialMemo,
      recurrentTransfers: [],
      tokenPrecision: undefined,
      tokenPrecisionFailed: false,
    };
  }

  // Component Life Cycle Functions
  componentDidMount() {
    const {
      currentAccount: { name },
    } = this.props;

    this.fetchBalance(name);

    this._fetchRecurrentTransfers(name);
  }

  // Component Functions

  _getUserPointsBalance = async (username: any) => {
    await getPointsSummary(username)
      .then((userPoints) => {
        // Ignore a late points response if the fund type changed while it was in
        // flight, so it can't clobber the newly-selected asset's balance.
        if (this.state.fundType !== 'POINT') {
          return;
        }
        const balance = Math.round(Number(get(userPoints, 'points', 0)) * 1000) / 1000;
        this.setState({ balance });
      })
      .catch((err) => {
        if (err) {
          captureException(err, (scope) => scope.setTag('context', 'transfer-points-balance'));
          Alert.alert(
            get(err, 'message') || this.props.intl.formatMessage({ id: 'alert.unknow_error' }),
          );
        }
      });
  };

  fetchBalance = async (username: any) => {
    const { fundType, transferType, tokenAddress, tokenPrecision } = this.state;

    const assetLayer = this.props.route.params?.assetLayer ?? this.props.route.params?.tokenLayer;
    const isEngine = assetLayer === TokenLayers.ENGINE;
    // Engine precision loads on its own, so a balance or account failure can't hide
    // it and a failed lookup can be retried without refetching the balance.
    if (isEngine && tokenPrecision === undefined) {
      this.fetchTokenPrecision();
    }

    // Fetch account using SDK
    const queryClient = getQueryClient();

    try {
      const accountQuery = getAccountsQueryOptions([username]);
      await queryClient.invalidateQueries({ queryKey: accountQuery.queryKey, exact: true });
      const accounts = await queryClient.fetchQuery(accountQuery);
      const account = accounts?.[0] ?? {};
      let balance: any;

      if (isEngine) {
        const tokenBalances = await fetchTokenBalances(username);

        tokenBalances.forEach((tokenBalance) => {
          if (tokenBalance.symbol === fundType) {
            switch (transferType) {
              case TransferTypes.UNDELEGATE:
                balance = tokenBalance.delegationsOut;
                break;
              case TransferTypes.UNSTAKE:
              case TransferTypes.DELEGATE:
                balance = tokenBalance.stake;
                break;
              default:
                const { balance: _balance } = tokenBalance;
                balance = _balance;
                break;
            }
          }
          if (!balance) {
            balance = '0';
          }
        });
      } else {
        balance = getNativeAccountBalance(account, transferType, fundType);
        if (transferType === TransferTypes.ECENCY_POINT_TRANSFER && fundType === 'POINT') {
          this._getUserPointsBalance(username);
        }
        if (transferType === 'address_view' && fundType === 'BTC') {
          // TODO implement transfer of custom tokens
          console.log(tokenAddress);
        }
      }

      const local = await getUserDataWithUsername(username);

      // Drop the result if the user switched fund type while this request was in
      // flight, so a stale fundType's balance can't clobber the newer selection.
      if (this.state.fundType !== fundType) {
        return;
      }

      // An empty/missing account yields `undefined` here (handled above), so a
      // freshly-fetched 0 is a real on-chain balance and must be honored.
      if (balance !== undefined && balance !== null && balance !== '') {
        const nextBalance = Number(balance);
        if (Number.isFinite(nextBalance)) {
          // Keep an Engine balance as its exact decimal text: a double holds only
          // ~16 significant digits, so MAX on a large 8-decimal balance would ask
          // for slightly more than the account holds and the sidechain rejects it.
          this.setState({ balance: isEngine ? formatTokenQuantity(balance) : nextBalance });
        }
      }

      this.setState({
        selectedAccount: { ...account, local: local[0] },
      });
    } catch (error) {
      console.warn('[TransferContainer] Failed to fetch transfer balance', error);
    }
  };

  // Engine precision lives on the TOKENS table, not the balances row (which has no
  // `precision` field). Precision can legitimately be 0 (integer tokens), so keep it
  // as-is. A failed or empty lookup sets tokenPrecisionFailed so the screen can say
  // why only whole amounts are allowed and offer a retry.
  _precisionRequestId = 0;

  fetchTokenPrecision = async () => {
    const { fundType } = this.state;
    this._precisionRequestId += 1;
    const requestId = this._precisionRequestId;
    this.setState({ tokenPrecisionFailed: false });
    let precision: number | undefined;
    try {
      const tokens = await fetchTokens([fundType]);
      precision = tokens.find((t) => t.symbol === fundType)?.precision;
    } catch (err) {
      console.warn('[TransferContainer] Failed to fetch token precision', err);
    }
    // Drop a superseded lookup, or one for a fund type the user switched away from.
    if (requestId !== this._precisionRequestId || this.state.fundType !== fundType) {
      return;
    }
    this.setState({ tokenPrecision: precision, tokenPrecisionFailed: precision === undefined });
  };

  _getAccountsWithUsername = async (username: any) => {
    const queryClient = getQueryClient();
    const validUsers = await queryClient.fetchQuery(lookupAccountsQueryOptions(username, 20));
    return validUsers;
  };

  _fetchRecurrentTransfers = async (username: any) => {
    const queryClient = getQueryClient();
    const recTransfers = await queryClient.fetchQuery(getRecurrentTransfersQueryOptions(username));

    this.setState({
      recurrentTransfers: recTransfers,
    });

    return recTransfers;
  };

  _setFundType = (newFundType: string) => {
    const { currentAccount } = this.props;
    this.setState({ fundType: newFundType, balance: '' }, () => {
      this.fetchBalance(currentAccount.name);
    });
    // Also update route params so _transferToAccount picks up the new fundType
    if (this.props.route?.params) {
      this.props.route.params.fundType = newFundType;
      this.props.route.params.balance = undefined;
    }
  };

  _delayedRefreshCoinsData = () => {
    const { currentAccount } = this.props;
    const queryClient = getQueryClient();

    if (!currentAccount?.name) return;

    // SDK mutations already invalidate portfolio on success via the adapter.
    // Wait 3 seconds for blockchain propagation, then invalidate secondary data
    // that the SDK doesn't cover (activities, pending requests, etc.)
    setTimeout(() => {
      // Re-invalidate portfolio as safety net for blockchain propagation
      queryClient.invalidateQueries({
        queryKey: ['wallet', 'portfolio', 'v2', currentAccount.name],
      });
      queryClient.invalidateQueries({
        queryKey: getAccountsQueryOptions([currentAccount.name]).queryKey,
      });

      // Invalidate secondary data (lazy refetch on next view)
      queryClient.invalidateQueries({
        queryKey: [QUERIES.WALLET.GET_ACTIVITIES, currentAccount.name],
      });
      queryClient.invalidateQueries({
        queryKey: ['accounts', 'transactions', currentAccount.name],
      });
      queryClient.invalidateQueries({
        queryKey: ['points', currentAccount.name],
      });
      queryClient.invalidateQueries({
        queryKey: ['wallet', 'savings-withdraw'],
      });
      queryClient.invalidateQueries({
        queryKey: ['wallet', 'conversion-requests'],
      });
      queryClient.invalidateQueries({
        queryKey: ['wallet', 'open-orders'],
      });
      queryClient.invalidateQueries({
        queryKey: ['wallet', 'recurrent-transfers'],
      });
    }, 3000); // 3 second delay for blockchain processing
  };

  _transferToAccount = async (
    from: any,
    destination: any,
    amount: any,
    memo: any,
    recurrence = null,
    executions = 0,
    overrideTransferType = null,
  ) => {
    const { navigation, dispatch, intl, route, mutations } = this.props;

    const transferType = normalizeTransferType(
      overrideTransferType ?? route.params?.transferType ?? '',
    );
    const fundType = route.params?.fundType ?? '';
    let tokenLayer = route.params?.assetLayer ?? route.params?.tokenLayer ?? '';

    if (!tokenLayer && (fundType === 'HIVE' || fundType === 'HBD')) {
      tokenLayer = TokenLayers.HIVE;
    }

    const data: any = { from, destination, amount, memo, fundType };

    if (recurrence !== undefined && recurrence !== null) {
      data.recurrence = +recurrence;
    }
    if (executions !== undefined && executions !== null) {
      data.executions = +executions;
    }

    // Normalize to the asset's on-chain precision before building the op: HIVE/HBD/
    // POINTS need exactly 3 decimals, VESTS 6; Hive-Engine tokens use their own
    // precision (no scientific notation). Over-precise amounts are otherwise rejected.
    const amountValue =
      tokenLayer === TokenLayers.ENGINE
        ? formatTokenQuantity(data.amount, this.state.tokenPrecision)
        : toFixedNoExp(data.amount, getAssetPrecision(fundType));

    data.amount = `${amountValue} ${fundType}`;

    const _onSuccess = () => {
      dispatch(toastNotification(intl.formatMessage({ id: 'alert.successful' })));
      this._delayedRefreshCoinsData();
      navigation.goBack();
    };

    const _onError = (error: any) => {
      navigation.goBack();
      Sentry.captureException(error);

      let alertId = 'alert.fail';
      const msg = error?.message?.toLowerCase?.() || '';
      if (msg.includes('key') || msg.includes('authority') || msg.includes('missing')) {
        alertId = 'alert.key_warning';
      }

      const operationLabel = intl.formatMessage({ id: `wallet.${transferType}` });
      const errorDetail = ((error as any)?.message ?? '').toString().split('\n')[0];

      if (alertId === 'alert.key_warning') {
        dispatch(
          toastNotification(
            intl.formatMessage(
              { id: 'alert.operation_failed_with_reason' },
              {
                operation: operationLabel,
                reason: intl.formatMessage({ id: 'alert.key_warning' }),
              },
            ),
          ),
        );
      } else {
        dispatch(
          toastNotification(
            errorDetail
              ? intl.formatMessage(
                  { id: 'alert.operation_failed_with_reason' },
                  { operation: operationLabel, reason: errorDetail },
                )
              : intl.formatMessage(
                  { id: 'alert.operation_failed_with_reason' },
                  { operation: operationLabel, reason: intl.formatMessage({ id: alertId }) },
                ),
          ),
        );
      }
    };

    try {
      // Handle ENGINE layer
      if (tokenLayer === TokenLayers.ENGINE) {
        const amountStr = data.amount.split(' ')[0];
        switch (transferType) {
          case TransferTypes.TRANSFER: {
            const destinations = data.destination
              .trim()
              .split(/[\s,]+/)
              .filter(Boolean);
            if (destinations.length === 0) {
              throw new Error('No valid transfer destinations provided');
            }
            if (destinations.length > 50) {
              throw new Error(`Too many recipients (${destinations.length}), max is 50`);
            }
            if (destinations.length === 1) {
              await mutations.transferEngine.mutateAsync({
                to: destinations[0],
                symbol: fundType,
                quantity: amountStr,
                memo: data.memo,
              });
            } else {
              await mutations.multiEngineTransfer.mutateAsync({
                destinations,
                symbol: fundType,
                quantity: amountStr,
                memo: data.memo,
              });
            }
            break;
          }
          case TransferTypes.STAKE:
            await mutations.stakeEngine.mutateAsync({
              to: data.destination || from,
              symbol: fundType,
              quantity: amountStr,
            });
            break;
          case TransferTypes.DELEGATE:
            await mutations.delegateEngine.mutateAsync({
              to: data.destination,
              symbol: fundType,
              quantity: amountStr,
            });
            break;
          case TransferTypes.UNSTAKE:
            await mutations.unstakeEngine.mutateAsync({
              to: data.destination || from,
              symbol: fundType,
              quantity: amountStr,
            });
            break;
          case TransferTypes.UNDELEGATE:
            await mutations.undelegateEngine.mutateAsync({
              from: data.destination,
              symbol: fundType,
              quantity: amountStr,
            });
            break;
          default:
            throw new Error(`Unknown transferType for ENGINE: ${transferType}`);
        }
        _onSuccess();
        return;
      }

      // Handle HIVE layer
      if (tokenLayer === TokenLayers.HIVE) {
        switch (transferType) {
          case TransferTypes.TRANSFER: {
            const destinations = data.destination
              .trim()
              .split(/[\s,]+/)
              .filter(Boolean);
            if (destinations.length === 0) {
              throw new Error('No valid transfer destinations provided');
            }
            if (destinations.length > 50) {
              throw new Error(`Too many recipients (${destinations.length}), max is 50`);
            }
            if (destinations.length === 1) {
              await mutations.transfer.mutateAsync({
                to: destinations[0],
                amount: data.amount,
                memo: data.memo,
              });
            } else {
              // Single broadcast with all transfer ops — avoids repeated
              // invalidation that caused false "Fail" toasts.
              await mutations.multiTransfer.mutateAsync({
                destinations,
                amount: data.amount,
                memo: data.memo,
              });
            }
            break;
          }
          case TransferTypes.RECURRENT_TRANSFER:
            await mutations.recurrentTransfer.mutateAsync({
              from: data.from,
              to: data.destination,
              amount: data.amount,
              memo: data.memo,
              recurrence: data.recurrence,
              executions: data.executions,
            });
            break;
          case TransferTypes.CONVERT:
            await mutations.convert.mutateAsync({
              amount: data.amount,
              requestId: new Date().getTime() >>> 0,
            });
            break;
          case TransferTypes.TRANSFER_TO_SAVINGS:
            await mutations.transferToSavings.mutateAsync({
              to: data.destination,
              amount: data.amount,
              memo: data.memo,
            });
            break;
          case TransferTypes.TRANSFER_FROM_SAVINGS:
            await mutations.transferFromSavings.mutateAsync({
              to: data.destination,
              amount: data.amount,
              memo: data.memo,
              requestId: new Date().getTime() >>> 0,
            });
            break;
          case TransferTypes.TRANSFER_TO_VESTING:
            await mutations.transferToVesting.mutateAsync({
              to: data.destination,
              amount: data.amount,
            });
            break;
          case TransferTypes.WITHDRAW_VESTING: {
            const vestsAmount = Number(amount);
            if (Number.isNaN(vestsAmount)) {
              throw new Error(`Invalid amount for WITHDRAW_VESTING: ${amount}`);
            }
            await mutations.withdrawVesting.mutateAsync({
              vestingShares: `${vestsAmount.toFixed(6)} VESTS`,
            });
            break;
          }
          case TransferTypes.DELEGATE_VESTING_SHARES: {
            const vestsAmount = Number(amount);
            if (Number.isNaN(vestsAmount)) {
              throw new Error(`Invalid amount for DELEGATE_VESTING_SHARES: ${amount}`);
            }
            await mutations.delegateVestingShares.mutateAsync({
              delegatee: data.destination,
              vestingShares: `${vestsAmount.toFixed(6)} VESTS`,
            });
            break;
          }
          default:
            throw new Error(`Unknown transferType for HIVE: ${transferType}`);
        }
        _onSuccess();
        return;
      }

      // Handle POINTS layer
      if (tokenLayer === TokenLayers.POINTS) {
        const destinations = data.destination
          .trim()
          .split(/[\s,]+/)
          .filter(Boolean);
        if (destinations.length === 0) {
          throw new Error('No valid transfer destinations provided');
        }
        if (destinations.length > 50) {
          throw new Error(`Too many recipients (${destinations.length}), max is 50`);
        }
        if (destinations.length === 1) {
          await mutations.transferPoint.mutateAsync({
            to: destinations[0],
            amount: data.amount,
            memo: data.memo,
          });
        } else {
          // Single broadcast with all point transfer ops — avoids repeated
          // invalidation that caused false "Fail" toasts.
          await mutations.multiPointTransfer.mutateAsync({
            destinations,
            amount: data.amount,
            memo: data.memo,
          });
        }
        _onSuccess();
        return;
      }

      throw new Error(`Unknown tokenLayer: ${tokenLayer}`);
    } catch (error) {
      _onError(error);
    }
  };

  _setWithdrawVestingRoute = async (_from: any, to: any, percentage: any, autoVest: any) => {
    const { mutations, dispatch, intl } = this.props;

    try {
      await mutations.setWithdrawVestingRoute.mutateAsync({
        toAccount: to,
        percent: percentage,
        autoVest,
      });
      dispatch(toastNotification(intl.formatMessage({ id: 'alert.successful' })));
      this._delayedRefreshCoinsData();
    } catch (error) {
      const errorDetail = ((error as any)?.message ?? '').toString().split('\n')[0];
      const operationLabel = intl.formatMessage({ id: 'transfer.withdraw_accounts' });
      dispatch(
        toastNotification(
          errorDetail
            ? intl.formatMessage(
                { id: 'alert.operation_failed_with_reason' },
                { operation: operationLabel, reason: errorDetail },
              )
            : intl.formatMessage(
                { id: 'alert.operation_failed_with_reason' },
                {
                  operation: operationLabel,
                  reason: intl.formatMessage({ id: 'alert.fail' }),
                },
              ),
        ),
      );
      throw error;
    }
  };

  _handleOnModalClose = () => {
    const { navigation } = this.props;
    this._delayedRefreshCoinsData();
    navigation.goBack();
  };

  render() {
    const {
      accounts,
      children,
      hivePerMVests,
      currentAccount,
      actionModalVisible,
      dispatch,
      route,
    } = this.props;
    const {
      balance,
      fundType,
      selectedAccount,
      tokenAddress,
      referredUsername,
      initialAmount,
      initialMemo,
      recurrentTransfers,
      tokenPrecisionFailed,
    } = this.state;

    const rawTransferType = route.params?.transferType ?? '';
    // Normalize legacy route aliases so the screen and submit path use operation names.
    const transferType = normalizeTransferType(rawTransferType);
    const tokenLayer =
      route.params?.assetLayer ??
      route.params?.tokenLayer ??
      (fundType === 'HIVE' || fundType === 'HBD' ? TokenLayers.HIVE : '');

    return (
      children &&
      children({
        dispatch,
        accounts,
        balance,
        tokenAddress,
        fundType,
        transferType,
        selectedAccount,
        hivePerMVests,
        actionModalVisible,
        referredUsername,
        fetchBalance: this.fetchBalance,
        getAccountsWithUsername: this._getAccountsWithUsername,
        transferToAccount: this._transferToAccount,
        handleOnModalClose: this._handleOnModalClose,
        accountType: get(selectedAccount || currentAccount, 'local.authType'),
        currentAccountName: get(currentAccount, 'name'),
        setWithdrawVestingRoute: this._setWithdrawVestingRoute,
        initialAmount,
        initialMemo,
        fetchRecurrentTransfers: this._fetchRecurrentTransfers,
        recurrentTransfers,
        tokenLayer,
        tokenPrecision: this.state.tokenPrecision,
        tokenPrecisionFailed,
        retryTokenPrecision: this.fetchTokenPrecision,
        setFundType: this._setFundType,
      })
    );
  }
}

const mapStateToProps = (state: any) => ({
  accounts: selectOtherAccounts(state),
  currentAccount: selectCurrentAccount(state),
  hivePerMVests: selectGlobalProps(state).hivePerMVests,
  actionModalVisible: state.ui.actionModalVisible,
});

const mapHooksToProps = (props: any) => {
  const navigation = useNavigation();
  const mutations = useTransferMutations();
  return React.createElement(TransferContainer, {
    ...props,
    navigation,
    mutations,
  });
};

export default connect(mapStateToProps)(injectIntl(mapHooksToProps));
