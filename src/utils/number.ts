export const countDecimals = (value: number) => {
  if (!value) {
    return 0;
  }

  if (Math.floor(value) === value) {
    return 0;
  }

  return value.toString().split('.')[1].length || 0;
};

export const stripDecimalPlaces = (value: number, precision = 3) => {
  if (!Number(value)) {
    return 0;
  }

  const power = 10 ** precision;

  return Math.floor(value * power) / power;
};

export const getDecimalPlaces = (value: number) => {
  const regex = /(?<=\.)\d+/;
  const match = value.toString().match(regex);
  return match ? match[0].length : 0;
};

// Required on-chain decimal precision per native Hive asset. HIVE/HBD/POINTS are
// exactly 3 decimals; VESTS is 6. Used to normalize broadcast amounts so the
// chain does not reject them for a precision mismatch.
const NATIVE_ASSET_PRECISION: Record<string, number> = {
  HIVE: 3,
  HBD: 3,
  HP: 3,
  POINT: 3,
  POINTS: 3,
  TESTS: 3,
  TBD: 3,
  VESTS: 6,
};

export const getAssetPrecision = (symbol?: string): number => {
  if (!symbol) {
    return 3;
  }
  return NATIVE_ASSET_PRECISION[symbol.trim().toUpperCase()] ?? 3;
};

// Truncate `num` toward zero to exactly `precision` decimals, returned as a plain
// decimal string. It slices the decimal-string representation rather than using
// `toFixed`, which rounds and can carry on a run of 9s (e.g. 1.999999999 -> 2.000),
// breaking the "never exceed the user's balance" guarantee. `Number.toString()` gives
// the shortest round-trip form (so 0.3 stays "0.3", not "0.2999…"); scientific-
// notation values (tiny magnitudes) are expanded via toFixed, where their
// sub-precision digits truncate to zero anyway.
const truncateToPrecision = (num: number, precision: number): string => {
  let s = Math.abs(num).toString();
  if (s.indexOf('e') !== -1 || s.indexOf('E') !== -1) {
    s = Math.abs(num).toFixed(precision);
  }
  const dot = s.indexOf('.');
  let cut: string;
  if (dot === -1) {
    cut = precision > 0 ? `${s}.${'0'.repeat(precision)}` : s;
  } else {
    const frac = `${s.slice(dot + 1)}${'0'.repeat(precision)}`.slice(0, precision);
    cut = precision > 0 ? `${s.slice(0, dot)}.${frac}` : s.slice(0, dot);
  }
  const sign = num < 0 && Number(cut) !== 0 ? '-' : '';
  return sign + cut;
};

// Format an amount to exactly `precision` decimals, truncating excess precision
// toward zero (so the broadcast never inflates past the user's balance) and padding
// when under-precise.
export const toFixedNoExp = (value: number | string, precision: number): string => {
  const num = typeof value === 'number' ? value : parseFloat(String(value));
  if (!Number.isFinite(num)) {
    return (0).toFixed(precision);
  }
  return truncateToPrecision(num, precision);
};

// Format a Hive-Engine token quantity: truncated to the token precision (default 8 —
// the engine maximum), never scientific notation, with trailing zeros and any
// dangling decimal point stripped so the quantity string is clean.
export const formatTokenQuantity = (value: number | string, precision = 8): string => {
  const num = typeof value === 'number' ? value : parseFloat(String(value));
  if (!Number.isFinite(num)) {
    return '0';
  }
  const truncated = truncateToPrecision(num, Math.max(0, Math.min(precision, 8)));
  return truncated.indexOf('.') === -1 ? truncated : truncated.replace(/\.?0+$/, '');
};

// Truncate a typed amount string to at most `maxDecimals` fraction digits, leaving the
// rest of the input (including a trailing '.') untouched.
export const capDecimals = (value: string, maxDecimals: number): string => {
  const dotIndex = value.indexOf('.');
  if (dotIndex === -1 || value.length - dotIndex - 1 <= maxDecimals) {
    return value;
  }
  return value.slice(0, dotIndex + 1 + maxDecimals);
};

export const formatNumberInputStr = (text: string, precision = 10) => {
  if (text.includes(',')) {
    text = text.replace(',', '.');
  }

  const _num = parseFloat(text);

  if (_num) {
    let _retVal = text;
    if ((text.startsWith('0') && _num >= 1) || text.startsWith('.')) {
      _retVal = `${_num}`;
    }

    if (getDecimalPlaces(_num) > precision) {
      _retVal = `${stripDecimalPlaces(_num, precision)}`;
    }
    return _retVal;
  } else if (text === '') {
    return '0';
  } else {
    return text;
  }
};

export const getAbbreviatedNumber = (input: string | number) => {
  const num = parseFloat(input.toString()); // Convert the string to a number

  // Check if the input is not a valid number
  if (Number.isNaN(num)) {
    return input; // Return the original string if it's not a number
  }

  if (num >= 1_000_000) {
    return `${(num / 1_000_000).toFixed(1).replace(/\.0$/, '')}M`; // Format in millions
  } else if (num >= 100_000) {
    return `${(num / 1_000_000).toFixed(1).replace(/\.0$/, '')}M`; // Convert 100K to 0.1M
  } else if (num >= 1_000) {
    return `${(num / 1_000).toFixed(1).replace(/\.0$/, '')}K`; // Format in thousands
  } else {
    return num.toString(); // Return smaller numbers as-is
  }
};

interface FormatAmountOptions {
  locale?: string;
  currencyCode?: string;
  currencySymbol?: string;
  minimumFractionDigits?: number;
  maximumFractionDigits?: number;
  symbolPosition?: 'prefix' | 'suffix';
  fallback?: string;
}

export const formatAmount = (value: number, options: FormatAmountOptions = {}) => {
  const {
    locale,
    currencyCode,
    currencySymbol,
    minimumFractionDigits = 2,
    maximumFractionDigits = 2,
    symbolPosition = 'prefix',
    fallback = '0',
  } = options;

  if (value === null || value === undefined || Number.isNaN(value)) {
    return fallback;
  }

  const formatter = new Intl.NumberFormat(locale, {
    currency: currencyCode,
    minimumFractionDigits,
    maximumFractionDigits,
    useGrouping: true,
  });

  let formattedValue = formatter.format(value);

  if (currencySymbol) {
    formattedValue =
      symbolPosition === 'suffix'
        ? `${formattedValue}${currencySymbol}`
        : `${currencySymbol}${formattedValue}`;
  }

  return formattedValue;
};
