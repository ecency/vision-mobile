// eslint-disable-next-line @typescript-eslint/no-var-requires
const path = require('path');
// eslint-disable-next-line @typescript-eslint/no-var-requires
const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');
// eslint-disable-next-line @typescript-eslint/no-var-requires
const { wrapWithReanimatedMetroConfig } = require('react-native-reanimated/metro-config');

// eslint-disable-next-line @typescript-eslint/no-var-requires
const { withSentryConfig } = require('@sentry/react-native/metro');

/**
 * Metro configuration
 * https://reactnative.dev/docs/metro
 *
 * @type {import('@react-native/metro-config').MetroConfig}
 */
const defaultConfig = getDefaultConfig(__dirname);
const config = {
  resolver: {
    // native/ holds the Kotlin and Swift spike apps; Metro must not crawl their build output.
    blockList: [].concat(
      defaultConfig.resolver.blockList,
      new RegExp(`^${path.resolve(__dirname, 'native').replace(/[/\\.]/g, '\\$&')}\\/.*`),
    ),
  },
};

// eslint-disable-next-line max-len
module.exports = wrapWithReanimatedMetroConfig(
  withSentryConfig(mergeConfig(defaultConfig, config)),
);
