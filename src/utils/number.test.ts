import {
  countDecimals,
  stripDecimalPlaces,
  getDecimalPlaces,
  formatNumberInputStr,
  getAbbreviatedNumber,
  formatAmount,
  getAssetPrecision,
  toFixedNoExp,
  formatTokenQuantity,
  capDecimals,
} from './number';

describe('countDecimals', () => {
  it('returns 0 for integers', () => {
    expect(countDecimals(5)).toBe(0);
    expect(countDecimals(100)).toBe(0);
  });

  it('returns 0 for 0', () => {
    expect(countDecimals(0)).toBe(0);
  });

  it('counts decimal places', () => {
    expect(countDecimals(1.5)).toBe(1);
    expect(countDecimals(1.23)).toBe(2);
    expect(countDecimals(0.123)).toBe(3);
    expect(countDecimals(1.123456)).toBe(6);
  });

  it('returns 0 for falsy values', () => {
    expect(countDecimals(null as any)).toBe(0);
    expect(countDecimals(undefined as any)).toBe(0);
  });
});

describe('stripDecimalPlaces', () => {
  it('truncates to specified precision', () => {
    expect(stripDecimalPlaces(1.123456, 3)).toBe(1.123);
    expect(stripDecimalPlaces(1.999, 2)).toBe(1.99);
  });

  it('defaults to precision 3', () => {
    expect(stripDecimalPlaces(1.123456)).toBe(1.123);
  });

  it('returns 0 for non-numeric input', () => {
    expect(stripDecimalPlaces(0)).toBe(0);
    expect(stripDecimalPlaces(NaN)).toBe(0);
  });

  it('floors rather than rounds', () => {
    expect(stripDecimalPlaces(1.9999, 2)).toBe(1.99);
    expect(stripDecimalPlaces(0.999, 1)).toBe(0.9);
  });

  it('handles values with fewer decimals than precision', () => {
    expect(stripDecimalPlaces(1.5, 3)).toBe(1.5);
    expect(stripDecimalPlaces(2, 3)).toBe(2);
  });
});

describe('getDecimalPlaces', () => {
  it('returns decimal count for floats', () => {
    expect(getDecimalPlaces(1.23)).toBe(2);
    expect(getDecimalPlaces(1.123456)).toBe(6);
  });

  it('returns 0 for integers', () => {
    expect(getDecimalPlaces(5)).toBe(0);
    expect(getDecimalPlaces(100)).toBe(0);
  });
});

describe('formatNumberInputStr', () => {
  it('returns "0" for empty string', () => {
    expect(formatNumberInputStr('')).toBe('0');
  });

  it('replaces comma with decimal point', () => {
    expect(formatNumberInputStr('1,5')).toBe('1.5');
  });

  it('strips leading zeros for numbers >= 1', () => {
    expect(formatNumberInputStr('01')).toBe('1');
    expect(formatNumberInputStr('007')).toBe('7');
  });

  it('handles leading decimal point', () => {
    expect(formatNumberInputStr('.5')).toBe('0.5');
  });

  it('limits decimal precision', () => {
    expect(formatNumberInputStr('1.123456789', 3)).toBe('1.123');
  });

  it('preserves valid input as-is', () => {
    expect(formatNumberInputStr('123')).toBe('123');
    expect(formatNumberInputStr('1.5')).toBe('1.5');
  });

  it('preserves trailing dot for continued input', () => {
    // When user types "1." the parseFloat gives 1 but text has dot
    // The function returns text as-is if it passes parseFloat
    expect(formatNumberInputStr('1.')).toBe('1.');
  });

  it('returns non-numeric text as-is', () => {
    expect(formatNumberInputStr('abc')).toBe('abc');
  });

  it('uses default precision of 10', () => {
    expect(formatNumberInputStr('0.1234567890')).toBe('0.1234567890');
    // stripDecimalPlaces(0.12345678901, 10) truncates trailing digit
    expect(formatNumberInputStr('0.12345678901')).toBe('0.123456789');
  });
});

describe('getAbbreviatedNumber', () => {
  it('returns small numbers as-is', () => {
    expect(getAbbreviatedNumber(50)).toBe('50');
    expect(getAbbreviatedNumber(999)).toBe('999');
  });

  it('formats thousands with K', () => {
    expect(getAbbreviatedNumber(1000)).toBe('1K');
    expect(getAbbreviatedNumber(1500)).toBe('1.5K');
    expect(getAbbreviatedNumber(99999)).toBe('100K');
  });

  it('formats 100K+ as M', () => {
    expect(getAbbreviatedNumber(100000)).toBe('0.1M');
    expect(getAbbreviatedNumber(500000)).toBe('0.5M');
  });

  it('formats millions with M', () => {
    expect(getAbbreviatedNumber(1_000_000)).toBe('1M');
    expect(getAbbreviatedNumber(2_500_000)).toBe('2.5M');
  });

  it('strips trailing .0', () => {
    expect(getAbbreviatedNumber(1000)).toBe('1K');
    expect(getAbbreviatedNumber(1_000_000)).toBe('1M');
  });

  it('accepts string input', () => {
    expect(getAbbreviatedNumber('1500')).toBe('1.5K');
  });

  it('returns NaN strings unchanged', () => {
    expect(getAbbreviatedNumber('not-a-number')).toBe('not-a-number');
  });

  it('handles zero', () => {
    expect(getAbbreviatedNumber(0)).toBe('0');
  });
});

describe('formatAmount', () => {
  it('formats with default options', () => {
    const result = formatAmount(1234.5);
    expect(result).toContain('1');
    expect(result).toContain('234');
    expect(result).toContain('50');
  });

  it('returns fallback for null/undefined/NaN', () => {
    expect(formatAmount(null as any)).toBe('0');
    expect(formatAmount(undefined as any)).toBe('0');
    expect(formatAmount(NaN)).toBe('0');
  });

  it('uses custom fallback', () => {
    expect(formatAmount(null as any, { fallback: 'N/A' })).toBe('N/A');
  });

  it('prepends currency symbol by default', () => {
    const result = formatAmount(100, { currencySymbol: '$' });
    expect(result.startsWith('$')).toBe(true);
  });

  it('appends currency symbol when suffix position', () => {
    const result = formatAmount(100, { currencySymbol: '€', symbolPosition: 'suffix' });
    expect(result.endsWith('€')).toBe(true);
  });

  it('respects fraction digit options', () => {
    const result = formatAmount(1.123456, {
      minimumFractionDigits: 0,
      maximumFractionDigits: 2,
    });
    expect(result).toBe('1.12');
  });
});

describe('getAssetPrecision', () => {
  it('returns 3 for HIVE/HBD/POINTS', () => {
    expect(getAssetPrecision('HIVE')).toBe(3);
    expect(getAssetPrecision('HBD')).toBe(3);
    expect(getAssetPrecision('POINT')).toBe(3);
    expect(getAssetPrecision('POINTS')).toBe(3);
  });

  it('returns 6 for VESTS', () => {
    expect(getAssetPrecision('VESTS')).toBe(6);
  });

  it('is case-insensitive and trims', () => {
    expect(getAssetPrecision(' hive ')).toBe(3);
    expect(getAssetPrecision('vests')).toBe(6);
  });

  it('defaults to 3 for unknown/missing symbols', () => {
    expect(getAssetPrecision('SOMETOKEN')).toBe(3);
    expect(getAssetPrecision(undefined)).toBe(3);
    expect(getAssetPrecision('')).toBe(3);
  });
});

describe('toFixedNoExp', () => {
  it('pads under-precise amounts to the asset precision', () => {
    expect(toFixedNoExp('10', 3)).toBe('10.000');
    expect(toFixedNoExp(1.5, 3)).toBe('1.500');
  });

  it('truncates over-precise amounts toward zero (never rounds up past balance)', () => {
    expect(toFixedNoExp('10.12345', 3)).toBe('10.123');
    expect(toFixedNoExp('1.23456789', 6)).toBe('1.234567');
    expect(toFixedNoExp('10.9995', 3)).toBe('10.999');
    expect(toFixedNoExp('0.9999', 3)).toBe('0.999');
    // A run of 9s must not carry/round up (regression guard)
    expect(toFixedNoExp('1.999999999', 3)).toBe('1.999');
    expect(toFixedNoExp('0.9999999', 6)).toBe('0.999999');
  });

  it('keeps float-error values exact (0.3 stays 0.300, not 0.299)', () => {
    expect(toFixedNoExp(0.3, 3)).toBe('0.300');
    expect(toFixedNoExp(0.1, 3)).toBe('0.100');
    expect(toFixedNoExp(0.1 + 0.2, 3)).toBe('0.300');
  });

  it('never emits scientific notation for tiny values', () => {
    expect(toFixedNoExp(0.0000001, 3)).toBe('0.000');
    expect(toFixedNoExp(1e-7, 8)).toBe('0.00000010');
    expect(toFixedNoExp(1e-7, 8)).not.toContain('e');
  });

  it('returns a zero-precision string for non-numeric input', () => {
    expect(toFixedNoExp('abc', 3)).toBe('0.000');
    expect(toFixedNoExp(NaN, 3)).toBe('0.000');
  });
});

describe('toFixedNoExp with plain decimal strings', () => {
  it('slices the text instead of rounding through a float', () => {
    expect(toFixedNoExp('12345678912.12345678', 8)).toBe('12345678912.12345678');
    expect(toFixedNoExp('1.9999999999', 3)).toBe('1.999');
    expect(toFixedNoExp('-0.0001', 3)).toBe('0.000');
  });
});

describe('formatTokenQuantity', () => {
  it('strips trailing zeros and never pads', () => {
    expect(formatTokenQuantity('10')).toBe('10');
    expect(formatTokenQuantity('10.500')).toBe('10.5');
    expect(formatTokenQuantity(1.23456789)).toBe('1.23456789');
  });

  it('avoids scientific notation for tiny token amounts', () => {
    expect(formatTokenQuantity(0.0000001)).toBe('0.0000001');
    expect(formatTokenQuantity(0.0000001)).not.toContain('e');
  });

  it('handles zero and invalid input', () => {
    expect(formatTokenQuantity(0)).toBe('0');
    expect(formatTokenQuantity('abc')).toBe('0');
  });

  it('keeps every digit of a plain decimal string beyond double precision', () => {
    expect(formatTokenQuantity('12345678912.12345678')).toBe('12345678912.12345678');
    expect(formatTokenQuantity('12345678912.12345678', 3)).toBe('12345678912.123');
    expect(formatTokenQuantity('99999999999.99999999', 8)).toBe('99999999999.99999999');
  });

  it('normalizes plain decimal strings', () => {
    expect(formatTokenQuantity('0.00000000')).toBe('0');
    expect(formatTokenQuantity('147.40287800')).toBe('147.402878');
    expect(formatTokenQuantity('130.')).toBe('130');
    expect(formatTokenQuantity('007.5')).toBe('7.5');
    expect(formatTokenQuantity(' 2.5 ')).toBe('2.5');
  });
});

describe('capDecimals', () => {
  it('truncates extra fraction digits without rounding', () => {
    expect(capDecimals('1.23456', 3)).toBe('1.234');
    expect(capDecimals('130.1055333', 8)).toBe('130.1055333');
    expect(capDecimals('0.99999', 2)).toBe('0.99');
  });

  it('leaves integers, partial input and empty strings alone', () => {
    expect(capDecimals('130', 3)).toBe('130');
    expect(capDecimals('130.', 0)).toBe('130.');
    expect(capDecimals('.', 3)).toBe('.');
    expect(capDecimals('', 3)).toBe('');
  });

  it('drops all fraction digits for precision 0', () => {
    expect(capDecimals('130.5', 0)).toBe('130.');
  });
});
