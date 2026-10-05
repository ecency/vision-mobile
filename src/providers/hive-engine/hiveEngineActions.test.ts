import { getEngineActionJSON } from './hiveEngineActions';
import { EngineActions } from './hiveEngine.types';

describe('getEngineActionJSON quantity', () => {
  it('keeps every digit of a large amount instead of rounding it as a float', () => {
    const json = getEngineActionJSON(
      EngineActions.STAKE,
      'alice',
      '12345678912.12345678 POB',
      'POB',
      undefined,
      8,
    );
    expect(json.contractPayload.quantity).toBe('12345678912.12345678');
  });

  it('truncates to the token precision', () => {
    const json = getEngineActionJSON(EngineActions.TRANSFER, 'bob', '1.23456 LEO', 'LEO', 'm', 3);
    expect(json.contractPayload.quantity).toBe('1.234');
  });

  it('formats an invalid amount as 0', () => {
    const json = getEngineActionJSON(EngineActions.TRANSFER, 'bob', 'abc LEO', 'LEO', 'm', 3);
    expect(json.contractPayload.quantity).toBe('0');
  });
});
