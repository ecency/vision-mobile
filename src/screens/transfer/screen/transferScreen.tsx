import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { Alert, FlatList, Text, TouchableOpacity, View } from 'react-native';
import { WebView } from 'react-native-webview';
import { useIntl } from 'react-intl';
import { get, debounce } from 'lodash';
import { useDispatch } from 'react-redux';
import { useNavigation } from '@react-navigation/native';

import { KeyboardAwareScrollView } from 'react-native-keyboard-aware-scroll-view';
import { SafeAreaView } from 'react-native-safe-area-context';
import * as hiveuri from 'hive-uri';
import { SheetManager } from 'react-native-actions-sheet';
import EStyleSheet from 'react-native-extended-stylesheet';
import { hsOptions } from '../../../constants/hsOptions';
import AUTH_TYPE from '../../../constants/authType';

import { MainButton, Modal, TextInput, UserAvatar, Icon } from '../../../components';
import { RECURRENCE_TYPES } from '../../../components/transferAmountInputSection/transferAmountInputSection';

import styles from './transferStyles';
import { extractUsernameFromScannedValue } from './transferRecipientParser';
import TransferTypes from '../../../constants/transferTypes';
import { getEngineActionJSON } from '../../../providers/hive-engine/hiveEngineActions';
import parseToken from '../../../utils/parseToken';
import { buildTransferOpsArray } from '../../../utils/transactionOpsBuilder';
import {
  getAssetPrecision,
  toFixedNoExp,
  formatTokenQuantity,
  capDecimals,
} from '../../../utils/number';
import { SheetNames } from '../../../navigation/sheets';
import TokenLayers from '../../../constants/tokenLayers';
import { EngineActions } from '../../../providers/hive-engine/hiveEngine.types';
import { toastNotification } from '../../../redux/actions/uiAction';
import { dateToFormatted } from '../../../utils/time';
import { isExchangeAccount } from '../../../constants/exchangeAccounts';

// Hive's recurrent_transfer operation requires at least 2 executions.
const MIN_RECURRENT_EXECUTIONS = 2;

// The preset cadence labels reuse the shared leaderboard.* strings, which are ALL CAPS
// (e.g. "DAILY"). Title-case them so the compact header pill reads "Daily" alongside the
// title-cased "One Time"/"Custom" entries instead of a jarring "DAILY".
const toTitleCase = (value: string) =>
  value.replace(/\S+/g, (word) => word.charAt(0).toUpperCase() + word.slice(1).toLowerCase());

interface TransferViewProps {
  currentAccountName: string;
  transferType: string;
  getAccountsWithUsername: (username: string) => any;
  balance: number | string;
  transferToAccount: (
    from: string,
    destination: string,
    amount: string | number,
    memo?: string,
    recurrence?: string | number | null,
    executions?: string | number | null,
    transferType?: string,
  ) => void;
  accountType: string;
  accounts: any[];
  handleOnModalClose: () => void;
  fundType: string;
  selectedAccount: any;
  fetchBalance: (username: string) => void;
  referredUsername?: string;
  initialAmount?: string | number;
  initialMemo?: string;
  fetchRecurrentTransfers?: (username: string) => void;
  recurrentTransfers?: any;
  tokenLayer?: string;
  tokenPrecision?: number;
  badActors?: Set<string>;
  setFundType?: (fundType: string) => void;
}

const TransferView = ({
  currentAccountName,
  transferType,
  getAccountsWithUsername,
  balance,
  transferToAccount,
  accountType,
  accounts: _accounts,
  handleOnModalClose,
  fundType,
  selectedAccount,
  fetchBalance: _fetchBalance,
  referredUsername,
  initialAmount,
  initialMemo,
  fetchRecurrentTransfers,
  recurrentTransfers = [],
  tokenLayer,
  tokenPrecision,
  badActors,
  setFundType,
}: TransferViewProps) => {
  const intl = useIntl();
  const dispatch = useDispatch();
  const navigation = useNavigation();

  const [from] = useState(currentAccountName);
  const [destination, setDestination] = useState(
    transferType === TransferTypes.TRANSFER_TO_SAVINGS ||
      transferType === TransferTypes.TRANSFER_TO_VESTING ||
      transferType === TransferTypes.TRANSFER_FROM_SAVINGS ||
      transferType === TransferTypes.WITHDRAW_VESTING ||
      transferType === TransferTypes.CONVERT ||
      transferType === TransferTypes.UNSTAKE ||
      transferType === TransferTypes.STAKE
      ? currentAccountName
      : referredUsername || '',
  );

  const [amount, setAmount] = useState(initialAmount != null ? `${initialAmount}` : '');
  const [memo, setMemo] = useState(
    transferType === 'purchase_estm' ? 'estm-purchase' : initialMemo ?? '',
  );
  const [recurrence, setRecurrence] = useState('');
  const [executions, setExecutions] = useState('');
  const [startDate, setStartDate] = useState('');
  const [isScheduledTransfer, setIsScheduledTransfer] = useState(
    transferType === TransferTypes.RECURRENT_TRANSFER,
  );
  const [showScheduleMenu, setShowScheduleMenu] = useState(false);

  const [isUsernameValid, setIsUsernameValid] = useState(false);
  const [usersResult, setUsersResult] = useState<string[]>([]);
  const [hsTransfer, setHsTransfer] = useState(false);
  const [isTransfering, setIsTransfering] = useState(false);

  const destinationRef = useRef<string[]>([]);
  const hasInitializedRef = useRef(false);
  // Tracks the recipient whose existing on-chain schedule we last autofilled, so a
  // different recipient without a schedule can be reset without wiping manual input.
  const lastHydratedRecipientRef = useRef<any>(null);

  const oneTimeTransferType =
    transferType === TransferTypes.RECURRENT_TRANSFER ? TransferTypes.TRANSFER : transferType;
  const isRecurrentTransfer = isScheduledTransfer;
  const effectiveTransferType = isRecurrentTransfer
    ? TransferTypes.RECURRENT_TRANSFER
    : oneTimeTransferType;
  const isEngineToken = tokenLayer === TokenLayers.ENGINE;

  const destinationLocked = useMemo(() => {
    switch (oneTimeTransferType) {
      case TransferTypes.CONVERT:
      case TransferTypes.UNSTAKE:
        return true;
      default:
        return false;
    }
  }, [oneTimeTransferType]);

  const allowMultipleDest =
    !isRecurrentTransfer &&
    ((tokenLayer === TokenLayers.HIVE && effectiveTransferType === TransferTypes.TRANSFER) ||
      (tokenLayer === TokenLayers.POINTS &&
        effectiveTransferType === TransferTypes.ECENCY_POINT_TRANSFER) ||
      (tokenLayer === TokenLayers.ENGINE && effectiveTransferType === TransferTypes.TRANSFER));

  const showMemo =
    effectiveTransferType === TransferTypes.ECENCY_POINT_TRANSFER ||
    effectiveTransferType === TransferTypes.TRANSFER ||
    effectiveTransferType === TransferTypes.RECURRENT_TRANSFER ||
    effectiveTransferType === TransferTypes.TRANSFER_TO_SAVINGS;

  const displayFundType = fundType === 'POINT' ? 'Points' : fundType;
  const isNativeTokenLayer = !tokenLayer || tokenLayer === TokenLayers.HIVE;
  // Balance is '' (or nullish) while the container is fetching/refetching it; treat
  // that as a loading state rather than a usable 0 so the amount field stays usable
  // and no false low-liquidity alert fires before the real balance arrives.
  const isBalanceLoading = balance === '' || balance === undefined || balance === null;

  // Token switching is only available for basic transfers. `oneTimeTransferType`
  // already collapses RECURRENT_TRANSFER -> TRANSFER, so no separate clause is needed.
  const canSwitchToken =
    !!setFundType && oneTimeTransferType === TransferTypes.TRANSFER && isNativeTokenLayer;
  const canScheduleTransfer =
    isNativeTokenLayer &&
    oneTimeTransferType === TransferTypes.TRANSFER &&
    (fundType === 'HIVE' || fundType === 'HBD');

  // Index of the current recurrence among the preset cadences (-1 when the schedule was
  // created off-app with a non-preset interval, e.g. 48h). `recurrence` is string state,
  // so coerce with +recurrence before matching the numeric RECURRENCE_TYPES.hours.
  const recurrenceIndex = RECURRENCE_TYPES.findIndex((r) => r.hours === +recurrence);
  const isOffGridRecurrence = isRecurrentTransfer && !!recurrence && recurrenceIndex === -1;

  const scheduleOptions = useMemo(() => {
    const options = [
      intl.formatMessage({ id: 'transfer.one_time', defaultMessage: 'One Time' }),
      ...RECURRENCE_TYPES.map((item) => toTitleCase(intl.formatMessage({ id: item.intlId }))),
    ];
    if (isOffGridRecurrence) {
      // Surface the real (non-preset) cadence instead of mislabeling it as the first
      // preset; selecting it is a no-op so the on-chain interval is preserved.
      options.push(
        intl.formatMessage(
          { id: 'transfer.custom_schedule', defaultMessage: 'Custom ({hours}h)' },
          { hours: recurrence },
        ),
      );
    }
    return options;
  }, [intl, isOffGridRecurrence, recurrence]);

  // 0 = One Time; 1..N = preset cadences; last = the synthetic Custom option (when shown).
  const scheduleSelectedIndex = !isRecurrentTransfer
    ? 0
    : recurrenceIndex > -1
    ? recurrenceIndex + 1
    : isOffGridRecurrence
    ? scheduleOptions.length - 1
    : 1;

  const [showTokenPicker, setShowTokenPicker] = useState(false);
  const transferableTokens = ['HIVE', 'HBD'];

  const _onFundBadgePress = () => {
    if (!canSwitchToken) return;
    setShowTokenPicker(true);
  };

  const _onTokenSelect = (token: string) => {
    setShowTokenPicker(false);
    if (token !== fundType) {
      setFundType!(token);
      setAmount('');
    }
  };

  // --- Validation ---
  const _debouncedValidateUsername = useCallback(
    debounce(async (usernames: string[]) => {
      if (usernames.length === 0) {
        setIsUsernameValid(false);
        setUsersResult([]);
        return;
      }
      const maxUsernames = allowMultipleDest ? 50 : 5;
      if (usernames.length > maxUsernames) {
        dispatch(toastNotification(intl.formatMessage({ id: 'transfer.too_many_usernames' })));
        setIsUsernameValid(false);
        setUsersResult([]);
        return;
      }

      // For single username, fetch suggestions for dropdown
      if (usernames.length === 1) {
        try {
          const users = await getAccountsWithUsername(usernames[0].trim());
          setUsersResult([...users]);
          const _isValid = users.includes(usernames[0]);
          if (_isValid) {
            _findRecurrentTransferOfUser(usernames[0]);
          }
          if (usernames.toString() !== destinationRef.current.toString()) {
            return;
          }
          setIsUsernameValid(_isValid);
        } catch {
          setUsersResult([]);
          setIsUsernameValid(false);
        }
        return;
      }

      // Multiple usernames — validate each
      const validationResults = await Promise.all(
        usernames.map(async (username) => {
          try {
            const users = await getAccountsWithUsername(username.trim());
            const _isValid = users.includes(username);
            if (_isValid) {
              _findRecurrentTransferOfUser(username);
            }
            return _isValid;
          } catch (error) {
            return false;
          }
        }),
      );

      setUsersResult([]);

      if (usernames.toString() !== destinationRef.current.toString()) {
        return;
      }

      setIsUsernameValid(validationResults.every((result) => result));
    }, 300),
    [recurrentTransfers, allowMultipleDest],
  );

  // --- Validate prefilled destination on mount ---
  useEffect(() => {
    if (hasInitializedRef.current) return;
    hasInitializedRef.current = true;
    if (destination) {
      const usernames = destination
        .trim()
        .toLowerCase()
        .split(/[\s,]+/)
        .filter(Boolean);
      destinationRef.current = allowMultipleDest ? usernames : [usernames[0]].filter(Boolean);
      _debouncedValidateUsername(destinationRef.current);
    }
  }, [destination, allowMultipleDest, _debouncedValidateUsername]);

  // --- Handlers ---
  const _handleDestinationChange = useCallback(
    (val: string) => {
      const trimmedLowercase = val.trim().toLowerCase();
      const usernames = trimmedLowercase ? trimmedLowercase.split(/[\s,]+/).filter(Boolean) : [];
      setIsUsernameValid(false);
      _debouncedValidateUsername(
        allowMultipleDest ? usernames : trimmedLowercase ? [trimmedLowercase] : [],
      );
      destinationRef.current = allowMultipleDest
        ? usernames
        : trimmedLowercase
        ? [trimmedLowercase]
        : [];
      setDestination(trimmedLowercase);
    },
    [_debouncedValidateUsername, allowMultipleDest],
  );

  const _handleUserSelect = useCallback(
    (username: string) => {
      if (username === from) {
        Alert.alert(
          intl.formatMessage({ id: 'transfer.username_alert' }),
          intl.formatMessage({ id: 'transfer.username_alert_detail' }),
        );
        return;
      }
      setDestination(username);
      destinationRef.current = [username];
      setUsersResult([]);
      setIsUsernameValid(true);
    },
    [from, intl],
  );

  const _handleScannedRecipient = useCallback(
    (value: string) => {
      const username = extractUsernameFromScannedValue(value);
      if (!username) {
        dispatch(
          toastNotification(
            intl.formatMessage({
              id: 'transfer.invalid_recipient_qr',
              defaultMessage: 'Could not find a username in this QR code.',
            }),
          ),
        );
        return;
      }
      _handleDestinationChange(username);
    },
    [dispatch, intl, _handleDestinationChange],
  );

  const _openQrScanner = useCallback(() => {
    SheetManager.show(SheetNames.QR_SCAN, {
      payload: {
        onScan: _handleScannedRecipient,
      },
    });
  }, [_handleScannedRecipient]);

  const _openFavorites = useCallback(async () => {
    const username = await SheetManager.show(SheetNames.TRANSFER_FAVORITES, {
      payload: { limit: 50 },
    });
    if (username) {
      _handleDestinationChange(username);
    }
  }, [_handleDestinationChange]);

  const _renderSuggestionItem = useCallback(
    ({ item: username }: any) => (
      <TouchableOpacity onPress={() => _handleUserSelect(username)} style={styles.usersDropItemRow}>
        <UserAvatar username={username} noAction />
        <Text style={styles.usersDropItemRowText}>{username}</Text>
      </TouchableOpacity>
    ),
    [_handleUserSelect],
  );

  const _handleAmountChange = (val: string | number) => {
    let newValue = String(val);
    if (newValue.includes(',')) {
      newValue = newValue.replace(',', '.');
    }
    // Cap decimals to the asset's precision so an over-precise amount can never be
    // entered (HIVE/HBD/POINTS = 3, VESTS = 6; engine tokens allow up to 8).
    const maxDecimals = isEngineToken ? tokenPrecision ?? 8 : getAssetPrecision(fundType);
    newValue = capDecimals(newValue, maxDecimals);
    const parsed = parseFloat(newValue);
    if (newValue === '' || newValue === '.' || Number.isNaN(parsed)) {
      setAmount(newValue);
    } else if (isBalanceLoading || parsed <= parseFloat(String(balance))) {
      setAmount(newValue);
    }
  };

  // Engine precision loads after mount, so an amount typed before it arrived was only
  // capped at the 8-decimal fallback. Re-cap it once the real precision is known so the
  // field shows the same quantity that will be broadcast.
  useEffect(() => {
    if (isEngineToken && tokenPrecision !== undefined) {
      setAmount((prev) => capDecimals(prev, tokenPrecision));
    }
  }, [isEngineToken, tokenPrecision]);

  // Keep `recurrence` normalized to a canonical preset value. The schedule pill in the
  // header reads scheduleOptions[scheduleSelectedIndex] directly, so it stays in sync
  // without any imperative dropdown handle.
  useEffect(() => {
    if (recurrenceIndex > -1) {
      setRecurrence(`${RECURRENCE_TYPES[recurrenceIndex].hours}`);
    }
  }, [recurrence]);

  useEffect(() => {
    if (!isRecurrentTransfer) return;
    if (!recurrence) {
      setRecurrence(`${RECURRENCE_TYPES[0].hours}`);
    }
    // Seed executions only when entering scheduled mode / changing cadence — deliberately
    // NOT keyed on `executions`, so clearing the field to edit it doesn't snap it back.
    if (!executions) {
      setExecutions(`${MIN_RECURRENT_EXECUTIONS}`);
    }
  }, [isRecurrentTransfer, recurrence]);

  const _handleScheduleSelect = useCallback(
    (index: number) => {
      if (index === 0) {
        setIsScheduledTransfer(false);
        setRecurrence('');
        setExecutions('');
        setStartDate('');
        return;
      }

      const selectedRecurrence = RECURRENCE_TYPES[index - 1];
      if (!selectedRecurrence) return;

      setIsScheduledTransfer(true);
      setRecurrence(`${selectedRecurrence.hours}`);
      if (!executions) {
        setExecutions(`${MIN_RECURRENT_EXECUTIONS}`);
      }
    },
    [executions],
  );

  // --- Transfer Actions ---
  const _handleTransferAction = debounce(
    () => {
      if (isTransfering) return;
      setIsTransfering(true);

      if (accountType === AUTH_TYPE.STEEM_CONNECT) {
        setHsTransfer(true);
      } else if (accountType === AUTH_TYPE.HIVE_AUTH) {
        const opArray = buildTransferOpsArray(effectiveTransferType, {
          from,
          to: destination,
          amount,
          fundType,
          memo,
          tokenLayer,
          precision: tokenPrecision,
          recurrence: isRecurrentTransfer ? +recurrence : (null as any),
          executions: isRecurrentTransfer ? +executions : (null as any),
        });
        SheetManager.show(SheetNames.HIVE_AUTH_BROADCAST, {
          payload: { operations: opArray } as any,
        })
          .then((response) => {
            if (response?.success) {
              handleOnModalClose();
            } else {
              if (response?.error) {
                console.error('[Transfer] HiveAuth broadcast failed:', response.error);
              }
              setIsTransfering(false);
            }
          })
          .catch((error) => {
            console.error('[Transfer] HiveAuth broadcast failed:', error);
            setIsTransfering(false);
          });
      } else {
        transferToAccount(
          from,
          destination,
          amount,
          memo,
          isRecurrentTransfer ? recurrence : null,
          isRecurrentTransfer ? executions : null,
          effectiveTransferType,
        );
      }
    },
    300,
    { trailing: true },
  );

  const _handleDeleteRecurrentTransfer = debounce(
    () => {
      setIsTransfering(true);
      if (accountType === AUTH_TYPE.STEEM_CONNECT) {
        setHsTransfer(true);
      } else {
        transferToAccount(
          from,
          destination,
          '0',
          memo,
          RECURRENCE_TYPES[0].hours,
          MIN_RECURRENT_EXECUTIONS,
          TransferTypes.RECURRENT_TRANSFER,
        );
      }
    },
    300,
    { trailing: true },
  );

  // --- HiveSigner Path ---
  let path;
  if (hsTransfer) {
    // Normalize the amount to the asset's on-chain precision before encoding the
    // hive-uri; this HiveSigner path previously sent the raw, unclamped user input.
    const hsNativeAmount = `${toFixedNoExp(amount, getAssetPrecision(fundType))} ${fundType}`;
    const hsEngineAmount = `${formatTokenQuantity(amount, tokenPrecision)} ${fundType}`;
    const destinations = destination
      .trim()
      .split(/[\s,]+/)
      .filter(Boolean);

    if (isEngineToken) {
      if (effectiveTransferType === TransferTypes.TRANSFER && destinations.length > 1) {
        path = hiveuri
          .encodeOps(
            destinations.map((receiver) => [
              'custom_json',
              {
                required_auths: [selectedAccount.name],
                required_posting_auths: [],
                id: 'ssc-mainnet-hive',
                json: JSON.stringify(
                  getEngineActionJSON(
                    EngineActions.TRANSFER,
                    receiver,
                    hsEngineAmount,
                    fundType,
                    memo,
                  ),
                ),
              },
            ]),
          )
          .replace('hive://', '');
        path += '?authority=active';
      } else {
        const json = getEngineActionJSON(
          effectiveTransferType as EngineActions,
          destination,
          hsEngineAmount,
          fundType,
          memo,
        );
        path = `sign/custom-json?authority=active&required_auths=%5B%22${get(
          selectedAccount,
          'name',
        )}%22%5D&required_posting_auths=%5B%5D&id=ssc-mainnet-hive&json=${encodeURIComponent(
          JSON.stringify(json),
        )}`;
      }
    } else if (effectiveTransferType === TransferTypes.RECURRENT_TRANSFER) {
      path = `sign/recurrent_transfer?from=${from}&to=${destination}&amount=${encodeURIComponent(
        hsNativeAmount,
      )}&memo=${encodeURIComponent(memo)}&recurrence=${recurrence}&executions=${executions}`;
    } else if (effectiveTransferType === TransferTypes.TRANSFER_TO_SAVINGS) {
      path = `sign/transfer_to_savings?from=${from}&to=${destination}&amount=${encodeURIComponent(
        hsNativeAmount,
      )}&memo=${encodeURIComponent(memo)}`;
    } else if (effectiveTransferType === TransferTypes.DELEGATE_VESTING_SHARES) {
      path = `sign/delegate_vesting_shares?delegator=${from}&delegatee=${destination}&vesting_shares=${encodeURIComponent(
        hsNativeAmount,
      )}`;
    } else if (effectiveTransferType === TransferTypes.TRANSFER_TO_VESTING) {
      path = `sign/transfer_to_vesting?from=${from}&to=${destination}&amount=${encodeURIComponent(
        hsNativeAmount,
      )}`;
    } else if (effectiveTransferType === TransferTypes.TRANSFER_FROM_SAVINGS) {
      path = `sign/transfer_from_savings?from=${from}&to=${destination}&amount=${encodeURIComponent(
        hsNativeAmount,
      )}&memo=${encodeURIComponent(memo ?? '')}&request_id=${new Date().getTime() >>> 0}`;
    } else if (effectiveTransferType === TransferTypes.CONVERT) {
      path = `sign/convert?owner=${from}&amount=${encodeURIComponent(hsNativeAmount)}&requestid=${
        new Date().getTime() >>> 0
      }`;
    } else if (effectiveTransferType === TransferTypes.WITHDRAW_VESTING) {
      path = `sign/withdraw_vesting?account=${from}&vesting_shares=${encodeURIComponent(
        hsNativeAmount,
      )}`;
    } else if (effectiveTransferType === TransferTypes.ECENCY_POINT_TRANSFER) {
      path = hiveuri
        .encodeOps(
          destinations.map((receiver) => [
            'custom_json',
            {
              required_auths: [selectedAccount.name],
              required_posting_auths: [],
              id: 'ecency_point_transfer',
              json: JSON.stringify({
                sender: selectedAccount.name,
                receiver,
                amount: hsNativeAmount,
                memo,
              }),
            },
          ]),
        )
        .replace('hive://', '');
      path += '?authority=active';
    } else {
      path = hiveuri
        .encodeOps(
          destinations.map((receiver) => [
            'transfer',
            { from, to: receiver, amount: hsNativeAmount, memo },
          ]),
        )
        .replace('hive://', '');
    }
  }

  // --- Confirmation ---
  const _showConfirmSheet = async () => {
    const action = await SheetManager.show(SheetNames.ACTION_MODAL, {
      payload: {
        title: intl.formatMessage({ id: 'transfer.information' }),
        buttons: [
          {
            text: intl.formatMessage({ id: 'alert.cancel' }),
            returnValue: 'cancel',
          },
          {
            text: intl.formatMessage({ id: 'alert.confirm' }),
            returnValue: 'confirm',
          },
        ],
      },
    });
    return action === 'confirm';
  };

  const _onNextPress = async () => {
    // Defense-in-depth: nextBtnDisabled already blocks this, but never broadcast a
    // memo-less HIVE/HBD send to an exchange (the deposit is lost) regardless of which
    // signing path _handleTransferAction takes.
    if (exchangeMemoRequired) {
      Alert.alert(
        intl.formatMessage(
          {
            id: 'transfer.exchange_memo_required',
            defaultMessage:
              'Sending to {exchange} requires a memo. Without the exchange-provided memo your funds may be lost.',
          },
          { exchange: exchangeDestination },
        ),
      );
      return false;
    }
    const parsedDestinations = destination
      .trim()
      .split(/[\s,]+/)
      .filter(Boolean);
    const recipientCount = allowMultipleDest ? parsedDestinations.length : 1;
    if (allowMultipleDest && parsedDestinations.length === 0) {
      return false;
    }
    if (!isBalanceLoading && (balance as any) < (amount as any) * recipientCount) {
      Alert.alert(intl.formatMessage({ id: 'wallet.low_liquidity' }));
      return false;
    }
    if (await _showConfirmSheet()) {
      _handleTransferAction();
    }
  };

  const _onDeletePress = async () => {
    if (await _showConfirmSheet()) {
      _handleDeleteRecurrentTransfer();
    }
  };

  const destinationUsernames = useMemo(
    () =>
      destination
        .trim()
        .toLowerCase()
        .split(/[\s,]+/)
        .filter(Boolean),
    [destination],
  );

  const badActorUsername = useMemo(
    () => (badActors ? destinationUsernames.find((u) => badActors.has(u)) ?? null : null),
    [destinationUsernames, badActors],
  );

  // First known exchange deposit account among the recipients, if any. Exchanges credit
  // only plain `transfer` operations identified by a memo, which drives the two
  // recipient-specific notices below.
  const exchangeDestination = useMemo(
    () => destinationUsernames.find((u) => isExchangeAccount(u)) ?? null,
    [destinationUsernames],
  );

  // Sending HIVE/HBD to an exchange without a memo means the deposit can't be attributed
  // and is typically lost — block submit until a memo is added (parity with ecency web).
  const isNativeFund = fundType === 'HIVE' || fundType === 'HBD';
  const exchangeMemoRequired = !!exchangeDestination && isNativeFund && !memo?.trim();

  // Exchanges settle recurrent_transfer through fill_recurrent_transfer virtual ops, which
  // their deposit systems don't watch — advise (without blocking) switching to one-time.
  // Scheduling is only reachable for native HIVE/HBD, but gate on isNativeFund anyway so
  // the notice can never surface for a non-native token.
  const exchangeRecurrentWarning = isRecurrentTransfer && isNativeFund && !!exchangeDestination;

  const nextBtnDisabled = !(
    (isEngineToken ? (amount as any) > 0 : (amount as any) >= 0.001) &&
    isUsernameValid &&
    // Don't allow submit until the real balance has loaded (it is '' while fetching).
    !isBalanceLoading &&
    // For Engine tokens, wait for precision before broadcasting a fractional amount
    // so it can't go out with the 8-decimal fallback (which an over-precise sidechain
    // quantity is rejected for). A whole-number amount is precision-safe at any
    // precision, so allow it through even if the token-metadata lookup degrades —
    // that keeps the common case working instead of dead-buttoning NEXT. `amount` is
    // string state, so coerce before the integer test (Number.isInteger never coerces).
    (!isEngineToken || tokenPrecision !== undefined || Number.isInteger(Number(amount))) &&
    // A HIVE/HBD send to an exchange must carry a memo or the deposit is lost.
    !exchangeMemoRequired &&
    (!isRecurrentTransfer ||
      (!!recurrence &&
        Number.isInteger(Number(executions)) &&
        Number(executions) >= MIN_RECURRENT_EXECUTIONS))
  );

  useEffect(() => {
    if (isRecurrentTransfer) {
      fetchRecurrentTransfers?.(currentAccountName);
    }
  }, [currentAccountName, fetchRecurrentTransfers, isRecurrentTransfer]);

  const _findRecurrentTransferOfUser = useCallback(
    (userToFind: any) => {
      if (!isRecurrentTransfer) {
        return false;
      }

      const existingRecurrentTransfer = recurrentTransfers.find((rt: any) => rt.to === userToFind);

      if (!existingRecurrentTransfer) {
        // This recipient has no on-chain schedule. If the fields were autofilled
        // from a *different* recipient's existing schedule, reset them to a fresh
        // schedule so that recipient's amount/memo/cadence don't bleed onto this
        // one. Manually entered values (no prior autofill) are left untouched.
        if (lastHydratedRecipientRef.current && lastHydratedRecipientRef.current !== userToFind) {
          setMemo('');
          setAmount('');
          setRecurrence(`${RECURRENCE_TYPES[0].hours}`);
          setExecutions(`${MIN_RECURRENT_EXECUTIONS}`);
        }
        lastHydratedRecipientRef.current = null;
        setStartDate('');
        return false;
      }

      lastHydratedRecipientRef.current = userToFind;
      setIsScheduledTransfer(true);
      setMemo(existingRecurrentTransfer.memo);
      setAmount(parseToken(existingRecurrentTransfer.amount).toString());
      setRecurrence(existingRecurrentTransfer.recurrence.toString());
      setExecutions(`${existingRecurrentTransfer.remaining_executions}`);
      setStartDate(existingRecurrentTransfer.trigger_date);

      return existingRecurrentTransfer;
    },
    [isRecurrentTransfer, recurrentTransfers],
  );

  // When Schedule is enabled after a recipient is already validated, hydrate any
  // existing on-chain recurrent transfer for that recipient. The username-validation
  // autofill only runs while already in scheduled mode, so a user who picks the
  // recipient first and toggles Schedule afterwards would otherwise see the existing
  // schedule's executions/start date stay blank.
  useEffect(() => {
    if (isRecurrentTransfer && isUsernameValid && destination && !allowMultipleDest) {
      _findRecurrentTransferOfUser(destination);
    }
  }, [
    isRecurrentTransfer,
    recurrentTransfers,
    isUsernameValid,
    destination,
    allowMultipleDest,
    _findRecurrentTransferOfUser,
  ]);

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <TouchableOpacity
          style={styles.headerSide}
          onPress={() => navigation.goBack()}
          hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
          accessibilityRole="button"
          accessibilityLabel={intl.formatMessage({ id: 'transfer.close', defaultMessage: 'Close' })}
        >
          <Icon iconType="MaterialIcons" name="close" size={26} style={styles.headerCloseIcon} />
        </TouchableOpacity>

        <View style={styles.headerCenter}>
          <Text style={styles.headerTitle} numberOfLines={1}>
            {intl.formatMessage({ id: `wallet.${oneTimeTransferType}` })}
          </Text>
          {canScheduleTransfer && (
            <TouchableOpacity
              style={styles.scheduleTrigger}
              onPress={() => setShowScheduleMenu((prev) => !prev)}
              activeOpacity={0.7}
              accessibilityRole="button"
              accessibilityState={{ expanded: showScheduleMenu }}
              accessibilityLabel={`${intl.formatMessage({ id: 'transfer.schedule' })}, ${
                scheduleOptions[scheduleSelectedIndex]
              }`}
            >
              <Text style={styles.scheduleTriggerText} numberOfLines={1}>
                {scheduleOptions[scheduleSelectedIndex]}
              </Text>
              <Icon
                iconType="MaterialCommunityIcons"
                name={showScheduleMenu ? 'chevron-up' : 'chevron-down'}
                size={18}
                style={styles.scheduleChevron}
              />
            </TouchableOpacity>
          )}
        </View>

        <View style={styles.headerSide} />
      </View>

      <KeyboardAwareScrollView
        keyboardShouldPersistTaps="always"
        enableOnAndroid={true}
        extraScrollHeight={80}
        contentContainerStyle={styles.scrollContent}
      >
        {/* --- Recipient Section --- */}
        {!destinationLocked && (
          <View style={styles.recipientSection}>
            <View style={styles.recipientInputRow}>
              <UserAvatar username={from} size="large" noAction />
              <Icon style={styles.arrowIcon} name="arrow-forward" iconType="MaterialIcons" />
              {destinationRef.current.length > 0 && (
                <View style={styles.recipientAvatars}>
                  {destinationRef.current.map((username, index) => (
                    <UserAvatar
                      key={username}
                      username={username}
                      size="large"
                      style={index > 0 ? { marginLeft: -12 } : undefined}
                      noAction
                    />
                  ))}
                </View>
              )}
              <View style={styles.recipientInputWrapper}>
                <View style={styles.recipientInputControl}>
                  <TextInput
                    style={[styles.inputField, styles.recipientTextInput] as any}
                    onChangeText={_handleDestinationChange}
                    value={destination}
                    placeholder={intl.formatMessage({ id: 'transfer.to_placeholder' })}
                    placeholderTextColor="#c1c5c7"
                    autoCapitalize="none"
                    autoCorrect={false}
                  />
                  <View style={styles.recipientActionButtons}>
                    <TouchableOpacity
                      style={styles.recipientActionButton}
                      onPress={_openFavorites}
                      activeOpacity={0.7}
                      accessibilityRole="button"
                      accessibilityLabel={intl.formatMessage({
                        id: 'transfer.pick_from_favorites',
                        defaultMessage: 'Choose recipient from favorites',
                      })}
                    >
                      <Icon
                        iconType="MaterialCommunityIcons"
                        name="account-circle-outline"
                        size={22}
                        color={EStyleSheet.value('$iconColor')}
                      />
                    </TouchableOpacity>
                    <TouchableOpacity
                      style={styles.recipientActionButton}
                      onPress={_openQrScanner}
                      activeOpacity={0.7}
                      accessibilityRole="button"
                      accessibilityLabel={intl.formatMessage({
                        id: 'transfer.scan_qr_recipient',
                        defaultMessage: 'Scan a QR code for the recipient',
                      })}
                    >
                      <Icon
                        iconType="MaterialCommunityIcons"
                        name="qrcode-scan"
                        size={20}
                        color={EStyleSheet.value('$iconColor')}
                      />
                    </TouchableOpacity>
                  </View>
                </View>

                {destination !== '' && usersResult.length > 0 && !isUsernameValid && (
                  <View style={styles.suggestionsContainer}>
                    <FlatList
                      data={usersResult}
                      keyboardShouldPersistTaps="always"
                      renderItem={_renderSuggestionItem}
                      keyExtractor={(item) => `suggest-${item}`}
                      style={styles.suggestionsList}
                    />
                  </View>
                )}
              </View>
            </View>

            {badActorUsername && (
              <Text style={styles.badActorWarning}>
                {intl.formatMessage({ id: 'transfer.to_bad_actor' })}
              </Text>
            )}

            {exchangeRecurrentWarning && (
              <Text style={styles.exchangeWarning}>
                {intl.formatMessage(
                  {
                    id: 'transfer.exchange_recurrent_warning',
                    defaultMessage:
                      '{exchange} is an exchange and may not support recurring transfers — funds sent on a schedule can be lost. Use a one-time transfer instead.',
                  },
                  { exchange: exchangeDestination },
                )}
              </Text>
            )}

            {exchangeMemoRequired && (
              <Text style={styles.exchangeBlockingWarning}>
                {intl.formatMessage(
                  {
                    id: 'transfer.exchange_memo_required',
                    defaultMessage:
                      'Sending to {exchange} requires a memo. Without the exchange-provided memo your funds may be lost.',
                  },
                  { exchange: exchangeDestination },
                )}
              </Text>
            )}
          </View>
        )}

        {/* --- Amount Section --- */}
        <View style={styles.fieldGroup}>
          <Text style={styles.fieldLabel}>{intl.formatMessage({ id: 'transfer.amount' })}</Text>
          <View style={styles.amountRow}>
            <TextInput
              style={[styles.inputField, styles.amountInputLarge] as any}
              onChangeText={_handleAmountChange}
              value={amount}
              placeholder="0.000"
              placeholderTextColor="#c1c5c7"
              keyboardType="decimal-pad"
              autoCapitalize="none"
            />
            <TouchableOpacity
              style={styles.fundBadge}
              onPress={_onFundBadgePress}
              disabled={!canSwitchToken}
              activeOpacity={canSwitchToken ? 0.7 : 1}
            >
              <Text style={styles.fundBadgeText}>{displayFundType}</Text>
              {canSwitchToken && (
                <Icon
                  iconType="MaterialCommunityIcons"
                  name="chevron-down"
                  size={16}
                  color="#fff"
                  style={{ marginLeft: 4 }}
                />
              )}
            </TouchableOpacity>
          </View>
          <TouchableOpacity style={styles.balanceRow} onPress={() => _handleAmountChange(balance)}>
            <Text style={styles.balanceText}>
              {intl.formatMessage({ id: 'transfer.amount_desc' })} {balance} {displayFundType}
            </Text>
            <Text style={styles.maxButton}>MAX</Text>
          </TouchableOpacity>
        </View>

        {/* --- Recurrent Transfer Fields --- */}
        {isRecurrentTransfer && (
          <View style={styles.fieldGroup}>
            {startDate && startDate !== '' && (
              <TouchableOpacity onPress={_onDeletePress}>
                <Text style={styles.deleteRecurrentText}>
                  {intl.formatMessage({ id: 'transfer.delete_recurrent_transfer' }) +
                    dateToFormatted(startDate, 'LL')}
                </Text>
              </TouchableOpacity>
            )}
            <Text style={styles.fieldLabel}>
              {intl.formatMessage({ id: 'transfer.executions' })}
            </Text>
            <TextInput
              style={styles.inputField}
              onChangeText={setExecutions}
              value={executions}
              placeholder={intl.formatMessage({ id: 'transfer.executions_placeholder' })}
              placeholderTextColor="#c1c5c7"
              keyboardType="numeric"
              autoCapitalize="none"
            />
          </View>
        )}

        {/* --- Memo Section --- */}
        {showMemo && (
          <View style={styles.fieldGroup}>
            <Text style={styles.fieldLabel}>{intl.formatMessage({ id: 'transfer.memo' })}</Text>
            <TextInput
              style={[styles.inputField, styles.memoInput] as any}
              onChangeText={setMemo}
              value={memo}
              placeholder={intl.formatMessage({ id: 'transfer.memo_placeholder' })}
              placeholderTextColor="#c1c5c7"
              autoCapitalize="none"
              multiline
              numberOfLines={3}
            />
            <Text style={styles.memoHint}>{intl.formatMessage({ id: 'transfer.memo_desc' })}</Text>
          </View>
        )}

        {/* --- Convert Description --- */}
        {effectiveTransferType === TransferTypes.CONVERT && (
          <View style={styles.fieldGroup}>
            <Text style={styles.convertDesc}>
              {intl.formatMessage({ id: 'transfer.convert_desc' })}
            </Text>
          </View>
        )}

        {/* --- Submit Button --- */}
        <View style={styles.submitContainer}>
          <MainButton
            style={styles.submitButton}
            isDisable={nextBtnDisabled}
            onPress={_onNextPress}
            isLoading={isTransfering}
          >
            <Text style={styles.submitButtonText}>
              {intl.formatMessage({ id: 'transfer.next' })}
            </Text>
          </MainButton>
        </View>
      </KeyboardAwareScrollView>

      {!!path && (
        <Modal
          isOpen={hsTransfer}
          isFullScreen
          isCloseButton
          handleOnModalClose={handleOnModalClose}
          title={intl.formatMessage({ id: 'transfer.steemconnect_title' })}
        >
          <WebView source={{ uri: `${hsOptions.base_url}${path}` }} />
        </Modal>
      )}

      {/* Token Picker Overlay */}
      {showTokenPicker && (
        <TouchableOpacity
          style={styles.tokenPickerOverlay}
          activeOpacity={1}
          onPress={() => setShowTokenPicker(false)}
        >
          <View style={styles.tokenPickerContainer}>
            <Text style={styles.tokenPickerTitle}>
              {intl.formatMessage({ id: 'transfer.select_token', defaultMessage: 'Select Token' })}
            </Text>
            {transferableTokens.map((token) => (
              <TouchableOpacity
                key={token}
                style={[styles.tokenPickerItem, token === fundType && styles.tokenPickerItemActive]}
                onPress={() => _onTokenSelect(token)}
              >
                <Text
                  style={[
                    styles.tokenPickerItemText,
                    token === fundType && styles.tokenPickerItemTextActive,
                  ]}
                >
                  {token}
                </Text>
              </TouchableOpacity>
            ))}
          </View>
        </TouchableOpacity>
      )}

      {/* Schedule Dropdown Menu (drops from the header title area) */}
      {showScheduleMenu && (
        <TouchableOpacity
          style={styles.scheduleMenuOverlay}
          activeOpacity={1}
          onPress={() => setShowScheduleMenu(false)}
        >
          <View style={styles.scheduleMenuCard}>
            {scheduleOptions.map((option, index) => {
              const isActive = index === scheduleSelectedIndex;
              return (
                <TouchableOpacity
                  key={option}
                  style={[styles.scheduleMenuItem, isActive && styles.scheduleMenuItemActive]}
                  onPress={() => {
                    setShowScheduleMenu(false);
                    _handleScheduleSelect(index);
                  }}
                  accessibilityRole="button"
                  accessibilityState={{ selected: isActive }}
                >
                  <Text
                    style={[
                      styles.scheduleMenuItemText,
                      isActive && styles.scheduleMenuItemTextActive,
                    ]}
                  >
                    {option}
                  </Text>
                  {isActive && (
                    <Icon
                      iconType="MaterialCommunityIcons"
                      name="check"
                      size={18}
                      style={styles.scheduleMenuCheck}
                    />
                  )}
                </TouchableOpacity>
              );
            })}
          </View>
        </TouchableOpacity>
      )}
    </SafeAreaView>
  );
};

export default TransferView;
