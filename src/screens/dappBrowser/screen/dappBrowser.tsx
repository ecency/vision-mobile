import React, { useCallback, useMemo, useRef, useState } from 'react';
import {
  View,
  TextInput,
  TouchableOpacity,
  Text,
  SafeAreaView,
  StatusBar,
  Animated,
  Keyboard,
  ScrollView,
  Image,
} from 'react-native';
import { WebView, WebViewNavigation } from 'react-native-webview';
import { gestureHandlerRootHOC } from 'react-native-gesture-handler';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import EStyleSheet from 'react-native-extended-stylesheet';
import { useIntl } from 'react-intl';
import { Icon } from '../../../components/icon';
import { HIVE_EXTENSION_BRIDGE_JS } from '../bridges/hiveExtensionBridge';
import { PEAK_VAULT_BRIDGE_JS } from '../bridges/peakVaultBridge';
import { useHiveBridgeHandler } from '../hooks/useHiveBridgeHandler';
import styles from './dappBrowserStyles';

// ─── dApp icon with fallback ──────────────────────────────────
const DappIcon = ({ dapp }: { dapp: DappEntry }) => {
  const [iconFailed, setIconFailed] = useState(false);

  if (iconFailed) {
    return (
      <View style={[styles.dappIconImage, styles.dappIconFallback]}>
        <Text style={styles.dappIconFallbackText}>{dapp.initial}</Text>
      </View>
    );
  }

  return (
    <Image
      source={{ uri: dapp.icon }}
      style={styles.dappIconImage}
      resizeMode="contain"
      onError={() => setIconFailed(true)}
    />
  );
};

// ─── dApp directory ───────────────────────────────────────────
interface DappEntry {
  name: string;
  url: string;
  icon: string;
  color: string;
  initial: string;
  category: string;
}

const DAPP_DIRECTORY: DappEntry[] = [
  {
    name: 'Ecency',
    url: 'https://ecency.com',
    icon: 'https://www.google.com/s2/favicons?domain=ecency.com&sz=128',
    color: '#357CE6',
    initial: 'E',
    category: 'Social',
  },
  {
    name: 'Raidstead',
    url: 'https://ecency.com/raidstead',
    icon: 'https://ecency.com/assets/raidstead-icon.png',
    color: '#7A4BB4',
    initial: 'R',
    category: 'Gaming',
  },
  {
    name: 'PeakD',
    url: 'https://peakd.com',
    icon: 'https://www.google.com/s2/favicons?domain=peakd.com&sz=128',
    color: '#E31337',
    initial: 'P',
    category: 'Social',
  },
  {
    name: 'Hive.blog',
    url: 'https://hive.blog',
    icon: 'https://www.google.com/s2/favicons?domain=hive.blog&sz=128',
    color: '#E31337',
    initial: 'H',
    category: 'Social',
  },
  {
    name: 'Splinterlands',
    url: 'https://splinterlands.com',
    icon: 'https://www.google.com/s2/favicons?domain=splinterlands.com&sz=128',
    color: '#F5A623',
    initial: 'S',
    category: 'Gaming',
  },
  {
    name: 'Rising Star',
    url: 'https://www.risingstargame.com',
    icon: 'https://www.google.com/s2/favicons?domain=risingstargame.com&sz=128',
    color: '#E91E63',
    initial: 'R',
    category: 'Gaming',
  },
  {
    name: 'Tribaldex',
    url: 'https://tribaldex.com',
    icon: 'https://www.google.com/s2/favicons?domain=tribaldex.com&sz=128',
    color: '#00B4D8',
    initial: 'T',
    category: 'DeFi',
  },
  {
    name: 'LeoDex',
    url: 'https://leodex.io',
    icon: 'https://www.google.com/s2/favicons?domain=leodex.io&sz=128',
    color: '#F5A623',
    initial: 'L',
    category: 'DeFi',
  },
  {
    name: 'HiveEngine',
    url: 'https://hive-engine.com',
    icon: 'https://www.google.com/s2/favicons?domain=hive-engine.com&sz=128',
    color: '#E31337',
    initial: 'HE',
    category: 'DeFi',
  },
  {
    name: '3Speak',
    url: 'https://3speak.tv',
    icon: 'https://www.google.com/s2/favicons?domain=3speak.tv&sz=128',
    color: '#E74C3C',
    initial: '3S',
    category: 'Video',
  },
  {
    name: 'Liketu',
    url: 'https://liketu.com',
    icon: 'https://www.google.com/s2/favicons?domain=liketu.com&sz=128',
    color: '#FF69B4',
    initial: 'Li',
    category: 'Social',
  },
  {
    name: 'InLeo',
    url: 'https://inleo.io',
    icon: 'https://www.google.com/s2/favicons?domain=inleo.io&sz=128',
    color: '#F5A623',
    initial: 'IL',
    category: 'Social',
  },
  {
    name: 'Altera',
    url: 'https://altera.magi.eco',
    icon: 'https://www.google.com/s2/favicons?domain=altera.magi.eco&sz=128',
    color: '#6C5CE7',
    initial: 'A',
    category: 'DeFi',
  },
];

// ─── Tab type ─────────────────────────────────────────────────
interface Tab {
  id: number;
  url: string | null; // null = home screen
  title: string;
  canGoBack: boolean;
  canGoForward: boolean;
  hasError: boolean;
}

let nextTabId = 1;

const createTab = (url: string | null = null): Tab => ({
  id: nextTabId++,
  url,
  title: url ? 'Loading...' : 'Home',
  canGoBack: false,
  canGoForward: false,
  hasError: false,
});

// ─── Params ───────────────────────────────────────────────────
export interface DappBrowserParams {
  url?: string;
}

type DappBrowserRoute = RouteProp<{ DappBrowser: DappBrowserParams }, 'DappBrowser'>;

// ─── Component ────────────────────────────────────────────────
const DappBrowser = () => {
  const navigation = useNavigation();
  const intl = useIntl();
  const route = useRoute<DappBrowserRoute>();
  const paramUrl = route.params?.url || null;

  // Tabs
  const [tabs, setTabs] = useState<Tab[]>([createTab(paramUrl)]);
  const [activeTabId, setActiveTabId] = useState(tabs[0].id);

  // WebView refs keyed by tab id
  const webViewRefs = useRef<Record<number, WebView | null>>({});

  // URL bar
  const [urlBarText, setUrlBarText] = useState(paramUrl || '');
  const [isUrlFocused, setIsUrlFocused] = useState(false);

  // Progress
  const progressAnim = useRef(new Animated.Value(0)).current;

  const activeTab = useMemo(
    () => tabs.find((t) => t.id === activeTabId) || tabs[0],
    [tabs, activeTabId],
  );

  const activeWebView = webViewRefs.current[activeTabId];

  // Stable ref that always points to the active tab's WebView
  const activeWebViewRef = useRef<WebView | null>(null);
  activeWebViewRef.current = webViewRefs.current[activeTabId] || null;

  const { handleMessage } = useHiveBridgeHandler(
    activeWebViewRef as React.RefObject<WebView | null>,
  );

  // ─── Tab helpers ──────────────────────────────────────────
  const _updateTab = useCallback((tabId: number, updates: Partial<Tab>) => {
    setTabs((prev) => prev.map((t) => (t.id === tabId ? { ...t, ...updates } : t)));
  }, []);

  const _addTab = useCallback(() => {
    const tab = createTab(null);
    setTabs((prev) => [...prev, tab]);
    setActiveTabId(tab.id);
    setUrlBarText('');
  }, []);

  const _closeTab = useCallback(
    (tabId: number) => {
      setTabs((prev) => {
        const next = prev.filter((t) => t.id !== tabId);
        if (next.length === 0) {
          const tab = createTab(null);
          setActiveTabId(tab.id);
          setUrlBarText('');
          return [tab];
        }
        if (activeTabId === tabId) {
          const idx = prev.findIndex((t) => t.id === tabId);
          const newActive = next[Math.min(idx, next.length - 1)];
          setActiveTabId(newActive.id);
          setUrlBarText(newActive.url || '');
        }
        return next;
      });
      delete webViewRefs.current[tabId];
    },
    [activeTabId],
  );

  const _switchTab = useCallback(
    (tabId: number) => {
      setActiveTabId(tabId);
      const tab = tabs.find((t) => t.id === tabId);
      setUrlBarText(tab?.url || '');
    },
    [tabs],
  );

  // ─── Navigation helpers ───────────────────────────────────
  const _normalizeUrl = (input: string): string => {
    const trimmed = input.trim();
    if (/^https?:\/\//i.test(trimmed)) return trimmed;
    // Only treat as hostname if it looks like one (no spaces, valid chars)
    if (/^[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?\.[a-zA-Z]{2,}(\/.*)?$/.test(trimmed)) {
      return `https://${trimmed}`;
    }
    return `https://www.google.com/search?q=${encodeURIComponent(trimmed)}`;
  };

  const _navigateTo = useCallback(
    (url: string) => {
      const normalized = _normalizeUrl(url);
      _updateTab(activeTabId, {
        url: normalized,
        hasError: false,
      });
      setUrlBarText(normalized);
      Keyboard.dismiss();
    },
    [activeTabId, _updateTab],
  );

  const _onSubmitUrl = useCallback(() => {
    if (!urlBarText.trim()) return;
    _navigateTo(urlBarText);
  }, [urlBarText, _navigateTo]);

  const _onNavigationStateChange = useCallback(
    (navState: WebViewNavigation) => {
      _updateTab(activeTabId, {
        canGoBack: navState.canGoBack,
        canGoForward: navState.canGoForward,
        title: navState.title || activeTab.title,
      });
      if (navState.url) {
        _updateTab(activeTabId, { url: navState.url });
        if (!isUrlFocused) {
          setUrlBarText(navState.url);
        }
      }
    },
    [activeTabId, activeTab.title, isUrlFocused, _updateTab],
  );

  const _getDisplayUrl = (): string => {
    if (isUrlFocused) return urlBarText;
    if (!activeTab.url) return '';
    try {
      return new URL(activeTab.url).hostname;
    } catch {
      return activeTab.url;
    }
  };

  const _isSecure = useMemo(() => {
    if (!activeTab.url) return false;
    try {
      return new URL(activeTab.url).protocol === 'https:';
    } catch {
      return false;
    }
  }, [activeTab.url]);

  const _onLoadProgress = useCallback(
    ({ nativeEvent }: { nativeEvent: { progress: number } }) => {
      Animated.timing(progressAnim, {
        toValue: nativeEvent.progress,
        duration: 100,
        useNativeDriver: false,
      }).start();
      if (nativeEvent.progress === 1) {
        setTimeout(() => {
          Animated.timing(progressAnim, {
            toValue: 0,
            duration: 200,
            useNativeDriver: false,
          }).start();
        }, 300);
      }
    },
    [progressAnim],
  );

  const _onError = useCallback(() => {
    _updateTab(activeTabId, { hasError: true });
  }, [activeTabId, _updateTab]);

  const _onRetry = useCallback(() => {
    _updateTab(activeTabId, { hasError: false });
    activeWebView?.reload();
  }, [activeTabId, activeWebView, _updateTab]);

  // ─── Home screen ──────────────────────────────────────────
  const _renderHome = () => (
    <ScrollView
      style={styles.homeContainer}
      contentContainerStyle={styles.homeContent}
      keyboardShouldPersistTaps="handled"
    >
      <Text style={styles.homeTitle}>{intl.formatMessage({ id: 'dapp_browser.title' })}</Text>
      <Text style={styles.homeSubtitle}>{intl.formatMessage({ id: 'dapp_browser.subtitle' })}</Text>

      <View style={styles.homeSearchContainer}>
        <Icon
          iconType="MaterialCommunityIcons"
          name="magnify"
          size={20}
          color={EStyleSheet.value('$iconColor')}
        />
        <TextInput
          style={styles.homeSearchInput}
          placeholder={intl.formatMessage({ id: 'dapp_browser.search_placeholder' })}
          placeholderTextColor={EStyleSheet.value('$iconColor')}
          value={urlBarText}
          onChangeText={setUrlBarText}
          onSubmitEditing={_onSubmitUrl}
          autoCapitalize="none"
          autoCorrect={false}
          keyboardType="url"
          returnKeyType="go"
        />
      </View>

      <View style={styles.infoBanner}>
        <Icon
          iconType="MaterialCommunityIcons"
          name="information-outline"
          size={20}
          color={EStyleSheet.value('$primaryBlue')}
        />
        <Text style={styles.infoBannerText}>
          {intl.formatMessage({ id: 'dapp_browser.signing_info' })}
        </Text>
      </View>

      <Text style={styles.homeSectionTitle}>
        {intl.formatMessage({ id: 'dapp_browser.popular_dapps' })}
      </Text>
      <View style={styles.dappGrid}>
        {DAPP_DIRECTORY.map((dapp) => (
          <TouchableOpacity
            key={dapp.url}
            style={styles.dappItem}
            onPress={() => _navigateTo(dapp.url)}
            activeOpacity={0.7}
          >
            <View style={styles.dappIconContainer}>
              <DappIcon dapp={dapp} />
            </View>
            <Text style={styles.dappName} numberOfLines={1}>
              {dapp.name}
            </Text>
            <Text style={styles.dappCategory}>{dapp.category}</Text>
          </TouchableOpacity>
        ))}
      </View>
    </ScrollView>
  );

  // ─── Header ───────────────────────────────────────────────
  const _renderHeader = () => {
    const showNavButtons = !!activeTab.url;

    return (
      <View style={styles.header}>
        <TouchableOpacity
          style={styles.navButton}
          onPress={() => {
            if (activeTab.url) {
              _updateTab(activeTabId, {
                url: null,
                title: 'Home',
                canGoBack: false,
                canGoForward: false,
                hasError: false,
              });
              setUrlBarText('');
            } else {
              navigation.goBack();
            }
          }}
        >
          <Icon
            iconType="MaterialCommunityIcons"
            name={activeTab.url ? 'home' : 'close'}
            size={22}
            color={EStyleSheet.value('$primaryBlack')}
          />
        </TouchableOpacity>

        {showNavButtons && (
          <>
            <TouchableOpacity
              style={styles.navButton}
              onPress={() => activeWebView?.goBack()}
              disabled={!activeTab.canGoBack}
            >
              <Icon
                iconType="MaterialCommunityIcons"
                name="chevron-left"
                size={24}
                color={EStyleSheet.value(activeTab.canGoBack ? '$primaryBlack' : '$iconColor')}
              />
            </TouchableOpacity>

            <TouchableOpacity
              style={styles.navButton}
              onPress={() => activeWebView?.goForward()}
              disabled={!activeTab.canGoForward}
            >
              <Icon
                iconType="MaterialCommunityIcons"
                name="chevron-right"
                size={24}
                color={EStyleSheet.value(activeTab.canGoForward ? '$primaryBlack' : '$iconColor')}
              />
            </TouchableOpacity>
          </>
        )}

        {showNavButtons && (
          <View style={styles.urlBarContainer}>
            {!isUrlFocused && _isSecure && (
              <Icon
                iconType="MaterialCommunityIcons"
                name="lock"
                size={14}
                color={EStyleSheet.value('$primaryGreen')}
                style={styles.lockIcon}
              />
            )}
            <TextInput
              style={styles.urlInput}
              value={_getDisplayUrl()}
              onChangeText={setUrlBarText}
              onSubmitEditing={_onSubmitUrl}
              onFocus={() => {
                setIsUrlFocused(true);
                setUrlBarText(activeTab.url || '');
              }}
              onBlur={() => setIsUrlFocused(false)}
              autoCapitalize="none"
              autoCorrect={false}
              keyboardType="url"
              returnKeyType="go"
              selectTextOnFocus
              placeholder={intl.formatMessage({ id: 'dapp_browser.search_placeholder' })}
              placeholderTextColor={EStyleSheet.value('$iconColor')}
            />
            {isUrlFocused && urlBarText.length > 0 && (
              <TouchableOpacity style={styles.clearButton} onPress={() => setUrlBarText('')}>
                <Icon
                  iconType="MaterialCommunityIcons"
                  name="close-circle"
                  size={16}
                  color={EStyleSheet.value('$iconColor')}
                />
              </TouchableOpacity>
            )}
          </View>
        )}

        {showNavButtons && (
          <TouchableOpacity style={styles.navButton} onPress={() => activeWebView?.reload()}>
            <Icon
              iconType="MaterialCommunityIcons"
              name="refresh"
              size={22}
              color={EStyleSheet.value('$primaryBlack')}
            />
          </TouchableOpacity>
        )}
      </View>
    );
  };

  // ─── Progress bar ─────────────────────────────────────────
  const _renderProgressBar = () => {
    const width = progressAnim.interpolate({
      inputRange: [0, 1],
      outputRange: ['0%', '100%'],
    });
    return (
      <View style={styles.progressBarTrack}>
        <Animated.View style={[styles.progressBar, { width }]} />
      </View>
    );
  };

  // ─── Error ────────────────────────────────────────────────
  const _renderError = () => (
    <View style={styles.errorContainer}>
      <Icon
        iconType="MaterialCommunityIcons"
        name="web-off"
        size={64}
        color={EStyleSheet.value('$iconColor')}
      />
      <Text style={styles.errorText}>
        {intl.formatMessage({ id: 'dapp_browser.page_load_failed' })}
      </Text>
      <TouchableOpacity style={styles.retryButton} onPress={_onRetry}>
        <Text style={styles.retryText}>{intl.formatMessage({ id: 'dapp_browser.retry' })}</Text>
      </TouchableOpacity>
    </View>
  );

  // ─── Tab bar ──────────────────────────────────────────────
  const _renderTabBar = () => (
    <View style={styles.tabBar}>
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={{ alignItems: 'center' }}
      >
        {tabs.map((tab) => (
          <TouchableOpacity
            key={tab.id}
            style={[styles.tabItem, tab.id === activeTabId && styles.tabItemActive]}
            onPress={() => _switchTab(tab.id)}
            activeOpacity={0.7}
          >
            <Text style={styles.tabTitle} numberOfLines={1}>
              {tab.title}
            </Text>
            {tabs.length > 1 && (
              <TouchableOpacity
                style={styles.tabClose}
                onPress={() => _closeTab(tab.id)}
                hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
              >
                <Icon
                  iconType="MaterialCommunityIcons"
                  name="close"
                  size={14}
                  color={EStyleSheet.value('$iconColor')}
                />
              </TouchableOpacity>
            )}
          </TouchableOpacity>
        ))}
      </ScrollView>
      <TouchableOpacity style={styles.tabAddButton} onPress={_addTab}>
        <Icon
          iconType="MaterialCommunityIcons"
          name="plus"
          size={22}
          color={EStyleSheet.value('$primaryBlack')}
        />
      </TouchableOpacity>
    </View>
  );

  // ─── WebView per tab (hidden when not active) ─────────────
  const _renderWebViews = () =>
    tabs
      .filter((tab) => tab.url)
      .map((tab) => {
        const isActive = tab.id === activeTabId;
        const isHttps = /^https:/i.test(tab.url || '');
        return (
          <View
            key={tab.id}
            style={[styles.webView, !isActive && { position: 'absolute', opacity: 0, height: 0 }]}
            pointerEvents={isActive ? 'auto' : 'none'}
          >
            {tab.hasError && isActive ? (
              _renderError()
            ) : (
              <WebView
                ref={(ref) => {
                  webViewRefs.current[tab.id] = ref;
                }}
                source={{ uri: tab.url! }}
                style={styles.webView}
                injectedJavaScriptBeforeContentLoaded={
                  isHttps ? HIVE_EXTENSION_BRIDGE_JS + PEAK_VAULT_BRIDGE_JS : undefined
                }
                onMessage={isActive ? handleMessage : undefined}
                onNavigationStateChange={isActive ? _onNavigationStateChange : undefined}
                onLoadProgress={isActive ? _onLoadProgress : undefined}
                onError={isActive ? _onError : undefined}
                javaScriptEnabled
                domStorageEnabled
                allowsInlineMediaPlayback
                mediaPlaybackRequiresUserAction={false}
                originWhitelist={['https://*', 'http://*']}
                allowsBackForwardNavigationGestures
                startInLoadingState={false}
                pullToRefreshEnabled
              />
            )}
          </View>
        );
      });

  // ─── Render ───────────────────────────────────────────────
  const showHome = !activeTab.url;

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="default" />
      {_renderHeader()}
      {!showHome && _renderProgressBar()}
      {_renderWebViews()}
      {showHome && _renderHome()}
      {_renderTabBar()}
    </SafeAreaView>
  );
};

export default gestureHandlerRootHOC(DappBrowser);
