import type { Operation } from '@ecency/sdk';
import { formatTokenQuantity } from '../../utils/number';
import { EngineActionJSON, EngineActions, EngineContracts } from './hiveEngine.types';

export const getEngineActionJSON = (
  action: EngineActions,
  to: string,
  amount: string,
  symbol: string,
  memo?: string,
  precision?: number,
): EngineActionJSON => {
  return {
    contractName: EngineContracts.TOKENS,
    contractAction: action,
    contractPayload: {
      symbol,
      to,
      // Truncate to the token's on-chain precision; an over-precise quantity is
      // silently rejected by the Engine sidechain. precision can be 0 (integer
      // tokens), so pass it through as-is rather than defaulting a falsy 0 away.
      // Format the amount's own text rather than a parsed float, so a large balance
      // keeps every digit instead of rounding above what the account holds.
      quantity: formatTokenQuantity(amount.trim().split(' ')[0], precision),
      memo: action === EngineActions.TRANSFER ? memo : undefined,
    },
  };
};

export const getEngineActionOpArray = (
  action: EngineActions,
  username: string,
  to: string,
  amount: string,
  symbol: string,
  memo?: string,
  precision?: number,
): Operation[] => {
  const json = getEngineActionJSON(action, to, amount, symbol, memo, precision);

  const op = {
    id: 'ssc-mainnet-hive',
    json: JSON.stringify(json),
    required_auths: [username],
    required_posting_auths: [],
  };
  return [['custom_json', op]];
};
