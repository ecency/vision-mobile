import React from 'react';

import { gestureHandlerRootHOC } from 'react-native-gesture-handler';
import { TransferContainer } from '../../containers';

import TransferView from './screen/transferScreen';
import AddressView from './screen/addressScreen';
import PowerDownView from './screen/powerDownScreen';
import DelegateView from './screen/delegateScreen';
import TransferTypes from '../../constants/transferTypes';
import { useBadActors } from '../../hooks';

const Transfer = ({ navigation, route }: any) => {
  const { data: badActors } = useBadActors();

  return (
    <TransferContainer navigation={navigation} route={route}>
      {({
        accounts,
        balance,
        fundType,
        transferType,
        fetchBalance,
        selectedAccount,
        getAccountsWithUsername,
        transferToAccount,
        handleOnModalClose,
        accountType,
        currentAccountName,
        hivePerMVests,
        actionModalVisible,
        setWithdrawVestingRoute,
        dispatch,
        referredUsername,
        initialAmount,
        initialMemo,
        recurrentTransfers,
        fetchRecurrentTransfers,
        tokenLayer,
        tokenPrecision,
        tokenPrecisionFailed,
        retryTokenPrecision,
        tokenAddress,
        setFundType,
      }: any) => {
        switch (transferType) {
          case TransferTypes.DELEGATE_VESTING_SHARES:
            return (
              <DelegateView
                {...({} as any)}
                accounts={accounts as any}
                currentAccountName={currentAccountName}
                selectedAccount={selectedAccount}
                getAccountsWithUsername={getAccountsWithUsername}
                balance={balance}
                fetchBalance={fetchBalance}
                transferToAccount={transferToAccount}
                accountType={accountType}
                handleOnModalClose={handleOnModalClose}
                hivePerMVests={hivePerMVests}
                actionModalVisible={actionModalVisible}
                dispatch={dispatch}
                referredUsername={referredUsername}
                badActors={badActors}
              />
            );
          case TransferTypes.WITHDRAW_VESTING:
          case TransferTypes.SET_WITHDRAW_VESTING_ROUTE:
            return (
              <PowerDownView
                accounts={accounts as any}
                balance={balance}
                fundType={fundType}
                transferType={transferType}
                fetchBalance={fetchBalance}
                getAccountsWithUsername={getAccountsWithUsername}
                transferToAccount={transferToAccount}
                handleOnModalClose={handleOnModalClose}
                currentAccountName={currentAccountName}
                selectedAccount={selectedAccount}
                hivePerMVests={hivePerMVests}
                setWithdrawVestingRoute={setWithdrawVestingRoute}
              />
            );
          case TransferTypes.RECEIVE:
            return (
              <AddressView
                fundType={fundType}
                transferType={transferType}
                handleOnModalClose={handleOnModalClose}
                accountType={accountType}
                currentAccountName={currentAccountName}
                selectedAccount={selectedAccount}
                tokenAddress={tokenAddress}
              />
            );

          default:
            return (
              <TransferView
                accounts={accounts as any}
                balance={balance}
                fundType={fundType}
                transferType={transferType}
                fetchBalance={fetchBalance}
                getAccountsWithUsername={getAccountsWithUsername}
                transferToAccount={transferToAccount}
                handleOnModalClose={handleOnModalClose}
                accountType={accountType}
                currentAccountName={currentAccountName}
                selectedAccount={selectedAccount}
                referredUsername={referredUsername || ''}
                initialAmount={initialAmount || ''}
                initialMemo={initialMemo || ''}
                recurrentTransfers={recurrentTransfers || []}
                fetchRecurrentTransfers={fetchRecurrentTransfers}
                tokenLayer={tokenLayer}
                tokenPrecision={tokenPrecision}
                tokenPrecisionFailed={tokenPrecisionFailed}
                retryTokenPrecision={retryTokenPrecision}
                badActors={badActors}
                setFundType={setFundType}
              />
            );
        }
      }}
    </TransferContainer>
  );
};

export default gestureHandlerRootHOC(Transfer);
