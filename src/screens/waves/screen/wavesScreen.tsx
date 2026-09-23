import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  NativeScrollEvent,
  NativeSyntheticEvent,
  RefreshControl,
  View,
  FlatList,
  Platform,
} from 'react-native';
import { debounce } from 'lodash';
import { TabView } from 'react-native-tab-view';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { SheetManager } from 'react-native-actions-sheet';
import { useIntl } from 'react-intl';
import { getWavesFeedQueryOptions } from '@ecency/sdk';
import { useIsFocused, useNavigation, useRoute } from '@react-navigation/native';
import type { NavigationProp, RouteProp } from '@react-navigation/native';
import {
  Comments,
  EmptyScreen,
  Header,
  PostOptionsModal,
  FabButton,
} from '../../../components/index';
import WavesTabBar from '../../../components/wavesTabBar/wavesTabBar';
import { renderPillTabLabel } from '../../../components/tabbedPosts/view/renderPillTabLabel';
import styles from '../styles/wavesScreen.styles';
import { useCheckIn, wavesQueries } from '../../../providers/queries';
import { useAppSelector } from '../../../hooks';
import { WavesHeader } from '../children/wavesHeader';
import WavesOnboardingChecklist from '../children/wavesOnboardingChecklist';
import WavesReelsView from '../children/wavesReelsView';
import { PostTypes } from '../../../constants/postTypes';
import { SHORTS_SOURCE, waveSourceLabel } from '../../../constants/waves';
import { ScrollTopPopup } from '../../../components/atoms';
import { SheetNames } from '../../../navigation/sheets';
import {
  selectCurrentAccount,
  selectIsDarkTheme,
  selectIsLoggedIn,
} from '../../../redux/selectors';
import ROUTES from '../../../constants/routeNames';
import RootNavigation from '../../../navigation/rootNavigation';
import { AppParamList } from '../../../navigation/types';

const SCROLL_POPUP_THRESHOLD = 5000;

type DeleteWaveFn = (args: {
  _permlink: string;
  _parent_permlink: string;
  _parent_author?: string;
}) => Promise<void>;

const WavesFeed = ({
  queryOptions,
  queryKey,
  listRef,
  onTagPress,
  onOptionsPress,
  onScrollStateChange,
  feedKey,
  registerDeleter,
  isDarkTheme,
}: {
  /**
   * The combined-feed query options for this flavour, built via
   * `getWavesFeedQueryOptions({ observer, following, tag })`. A single
   * keyset-paginated query backs the list.
   */
  queryOptions: ReturnType<typeof getWavesFeedQueryOptions>;
  queryKey: string;
  listRef: React.RefObject<FlatList | null>;
  onTagPress: (tag: string) => void;
  onOptionsPress: (content: any) => void;
  onScrollStateChange: (state: { enabled: boolean; offset: number }) => void;
  /**
   * Stable identifier (e.g., "for-you" / "following" / "tag") under which
   * this feed publishes its `deleteWave` into the parent's deleter map. Each
   * feed owns its own slot, so simultaneously mounted feeds (TabView keeps
   * both tabs alive once visited) don't overwrite each other's deleter.
   */
  feedKey: string;
  /**
   * Stable register/unregister callback. Must be `useCallback`-stable in the
   * parent — otherwise this feed's registration effect would re-fire on
   * every parent render and clobber whichever feed happens to render last.
   */
  registerDeleter: (key: string, deleter: DeleteWaveFn | null) => void;
  isDarkTheme: boolean;
}) => {
  // Interleave promoted waves on the for-you / following feeds only, never on a
  // tag or source feed: a filtered feed must surface only its own waves, and a
  // promoted wave could belong to a different tag/container (matches the web
  // waves feed's `enabled: !tag`).
  const isFilteredFeed = feedKey.startsWith('tag') || feedKey.startsWith('container');
  const wavesQuery = wavesQueries.useWavesQuery(queryOptions, !isFilteredFeed);
  const blockPopupRef = useRef(false);
  const scrollOffsetRef = useRef(0);

  const scrollPopupDebounce = useMemo(
    () =>
      debounce(
        (value: boolean, offset: number) => {
          onScrollStateChange({ enabled: value, offset });
        },
        500,
        { leading: true },
      ),
    [onScrollStateChange],
  );

  useEffect(() => () => scrollPopupDebounce.cancel(), [scrollPopupDebounce]);

  const _fetchData = (fetchProps: any) => {
    if (fetchProps?.refresh) {
      wavesQuery.refresh();
    } else if (wavesQuery.hasNextPage && !wavesQuery.isFetchingNextPage) {
      wavesQuery.fetchNextPage();
    }
  };

  const _onScroll = (event: NativeSyntheticEvent<NativeScrollEvent>) => {
    const currentOffset = event.nativeEvent.contentOffset.y;
    const scrollUp = currentOffset < scrollOffsetRef.current;
    scrollOffsetRef.current = currentOffset;

    if (currentOffset <= SCROLL_POPUP_THRESHOLD) {
      scrollPopupDebounce(false, currentOffset);
      return;
    }

    if (scrollUp && !blockPopupRef.current) {
      scrollPopupDebounce(true, currentOffset);
    } else {
      scrollPopupDebounce(false, currentOffset);
    }
  };

  const _renderListFooter = () =>
    wavesQuery.isFetchingNextPage && !wavesQuery.isRefreshing ? (
      <ActivityIndicator style={styles.listSpacing} />
    ) : (
      <View style={styles.listSpacing} />
    );

  const _renderListEmpty = () =>
    wavesQuery.isRefreshing || wavesQuery.isLoading ? <View /> : <EmptyScreen />;

  useEffect(() => {
    // Each feed writes to its own slot in the parent's deleter map; on
    // unmount (or feedKey change) it removes its slot. The previous design
    // pushed into a single shared ref, which both feeds clobbered on every
    // parent render once both tabs were mounted.
    registerDeleter(feedKey, wavesQuery.deleteWave);
    return () => registerDeleter(feedKey, null);
  }, [feedKey, registerDeleter, wavesQuery.deleteWave]);

  return (
    <Comments
      key={queryKey}
      postType={PostTypes.WAVE}
      comments={wavesQuery.data}
      handleOnOptionsPress={onOptionsPress}
      handleCommentDelete={wavesQuery.deleteWave}
      onTagPress={onTagPress}
      flatListProps={{
        ref: listRef,
        onEndReached: _fetchData,
        onScroll: _onScroll,
        ListEmptyComponent: _renderListEmpty,
        ListFooterComponent: _renderListFooter,
        refreshControl: (
          <RefreshControl
            refreshing={wavesQuery.isRefreshing}
            onRefresh={() => _fetchData({ refresh: true })}
            progressBackgroundColor="#357CE6"
            tintColor={!isDarkTheme ? '#357ce6' : '#96c0ff'}
            titleColor="#fff"
            colors={['#fff']}
          />
        ),
      }}
    />
  );
};

const WavesScreen = () => {
  const postOptionsModalRef = useRef<any>(null);
  const forYouListRef = useRef<any>(null);
  const followingListRef = useRef<any>(null);
  const tagListRef = useRef<any>(null);
  // Lazily-created list refs for each pinned tag or source tab, keyed by route key.
  const dynamicTabListRefs = useRef<Record<string, React.RefObject<FlatList>>>({});

  const _getDynamicTabRef = (key: string) => {
    if (!dynamicTabListRefs.current[key]) {
      dynamicTabListRefs.current[key] = React.createRef<FlatList>() as any;
    }
    return dynamicTabListRefs.current[key];
  };

  const [feedType, setFeedType] = useState<string>('for-you');
  const [activeTag, setActiveTag] = useState<string | null>(null);
  const [enableScrollTop, setEnableScrollTop] = useState(false);
  const [lazyLoad, setLazyLoad] = useState(false);
  // Map of feedKey → that feed's `deleteWave`. A Map instead of a single
  // ref because both TabView tabs stay mounted once visited; each feed
  // owns its own slot so simultaneously mounted feeds don't clobber each
  // other. At delete time we look up by the *currently active* feedKey
  // (derived below) rather than by render order.
  const feedDeletersRef = useRef<Map<string, DeleteWaveFn>>(new Map());

  const _registerFeedDeleter = useCallback((key: string, deleter: DeleteWaveFn | null) => {
    if (deleter) {
      feedDeletersRef.current.set(key, deleter);
    } else {
      feedDeletersRef.current.delete(key);
    }
  }, []);

  // Whether the Waves screen itself is the focused route (not behind another
  // bottom tab / pushed screen). Combined with the active waves tab below to
  // decide if the Shorts reels should actually be playing.
  const isScreenFocused = useIsFocused();

  // A share link (ecency.com/waves?text=...) lands here with the text as a
  // route param. Open the wave composer with it the way the OS share sheet
  // does, then clear the param so a later focus does not open it again.
  const navigation = useNavigation<NavigationProp<AppParamList, typeof ROUTES.TABBAR.WAVES>>();
  const screenRoute = useRoute<RouteProp<AppParamList, typeof ROUTES.TABBAR.WAVES>>();
  const composeText = screenRoute.params?.text;
  useEffect(() => {
    if (!isScreenFocused || !composeText) {
      return;
    }
    navigation.setParams({ text: undefined });
    SheetManager.show(SheetNames.QUICK_POST, {
      payload: { mode: 'wave', files: [{ text: composeText }] },
    });
  }, [isScreenFocused, composeText, navigation]);

  // Browsing waves is reading too, so it records a check-in like opening a post
  // does; users who only ever scroll waves still complete the daily quest.
  const recordCheckIn = useCheckIn();
  useEffect(() => {
    if (isScreenFocused) {
      recordCheckIn();
    }
  }, [isScreenFocused, recordCheckIn]);

  const isLoggedIn = useAppSelector(selectIsLoggedIn);
  const currentAccount = useAppSelector(selectCurrentAccount);
  const isDarkTheme = useAppSelector(selectIsDarkTheme);
  const waveTags = useAppSelector((state) => state.customTabs.waveTags || []);
  const waveContainers = useAppSelector((state) => state.customTabs.waveContainers || []);
  const insets = useSafeAreaInsets();

  // The logged-in user is the observer on every feed: the backend drops authors
  // they currently mute, so each page stays full of waves they can see (a
  // client-side filter would shrink pages). Logged-out users get the public
  // combined feed (observer undefined).
  const observer = currentAccount?.name || undefined;

  // One combined, cross-container, keyset-paginated endpoint backs all three
  // feeds; `following` and `tag` are just filters on the same stream.
  const forYouQueryOptions = useMemo(() => getWavesFeedQueryOptions({ observer }), [observer]);
  const followingQueryOptions = useMemo(
    () => getWavesFeedQueryOptions({ following: currentAccount?.name, observer }),
    [currentAccount?.name, observer],
  );
  const tagQueryOptions = useMemo(
    () => getWavesFeedQueryOptions({ tag: activeTag ?? undefined, observer }),
    [activeTag, observer],
  );
  // Query options for each pinned tag tab, keyed by route key ("tag:<t>").
  const tagTabQueryOptions = useMemo(() => {
    const map: Record<string, ReturnType<typeof getWavesFeedQueryOptions>> = {};
    waveTags.forEach((tag: any) => {
      map[`tag:${tag}`] = getWavesFeedQueryOptions({ tag, observer });
    });
    return map;
  }, [waveTags, observer]);
  // Query options for each pinned source tab, keyed by route key
  // ("container:<host>"). Filters the same combined feed to one container.
  const containerTabQueryOptions = useMemo(() => {
    const map: Record<string, ReturnType<typeof getWavesFeedQueryOptions>> = {};
    waveContainers.forEach((host: any) => {
      // Shorts isn't a single container: it's the cross-container reels feed,
      // rendered by WavesReelsView with its own SDK query, so skip it here.
      if (host === SHORTS_SOURCE) {
        return;
      }
      map[`container:${host}`] = getWavesFeedQueryOptions({ containers: [host], observer });
    });
    return map;
  }, [waveContainers, observer]);

  const activeListRef = activeTag
    ? tagListRef
    : feedType === 'following'
    ? followingListRef
    : feedType.startsWith('tag:') || feedType.startsWith('container:')
    ? _getDynamicTabRef(feedType)
    : forYouListRef;

  const intl = useIntl();
  const wavesRoutes = useMemo(
    () => [
      { key: 'for-you', title: intl.formatMessage({ id: 'waves.for_you' }) },
      { key: 'following', title: intl.formatMessage({ id: 'waves.following' }) },
      ...waveContainers.map((host: any) => ({
        key: `container:${host}`,
        title: waveSourceLabel(host),
      })),
      ...waveTags.map((tag: any) => ({ key: `tag:${tag}`, title: `#${tag}` })),
    ],
    [intl, waveContainers, waveTags],
  );

  const wavesIndex = useMemo(() => {
    const index = wavesRoutes.findIndex((route) => route.key === feedType);
    return index < 0 ? 0 : index;
  }, [feedType, wavesRoutes]);

  // If the active tag or source tab was removed from the picker, fall back to
  // For you so the TabView never points at a route that no longer exists.
  useEffect(() => {
    if (feedType.startsWith('tag:') && !waveTags.includes(feedType.slice(4))) {
      setFeedType('for-you');
    } else if (
      feedType.startsWith('container:') &&
      !waveContainers.includes(feedType.slice('container:'.length))
    ) {
      setFeedType('for-you');
    }
  }, [feedType, waveTags, waveContainers]);

  const fabBottomOffset = Platform.OS === 'android' ? 66 + (insets.bottom || 0) : 16;

  const _lazyLoadContent = () => {
    if (!lazyLoad) {
      setTimeout(() => {
        setLazyLoad(true);
      }, 100);
    }
  };

  const _handleTabChange = (tab: string) => {
    if (tab === 'following' && !isLoggedIn) {
      RootNavigation.navigate({ name: ROUTES.SCREENS.LOGIN });
      return;
    }

    if (tab === feedType && !activeTag) {
      activeListRef.current?.scrollToOffset({ offset: 0, animated: false });
      setEnableScrollTop(false);
      return;
    }

    // Following, pinned tag and pinned source tabs are real feeds; clear any
    // transient tap-a-hashtag filter (it only overlays the For you tab).
    if (tab === 'following' || tab.startsWith('tag:') || tab.startsWith('container:')) {
      setActiveTag(null);
    }

    setEnableScrollTop(false);
    setFeedType(tab);
  };

  const _handleTagFilter = useCallback((tag: string) => {
    // Tapping a hashtag inside a wave always drops back to the For you tab and
    // shows the transient tag overlay there (kept separate from pinned tag tabs).
    setFeedType('for-you');
    setEnableScrollTop(false);
    setActiveTag(tag);
  }, []);

  const _handleClearTag = useCallback(() => {
    setEnableScrollTop(false);
    setActiveTag(null);
  }, []);

  const _handleOnOptionsPress = (content: any) => {
    if (postOptionsModalRef.current) {
      postOptionsModalRef.current.show(content);
    }
  };

  // Each WavesFeed registers/unregisters its slot in `feedDeletersRef`
  // via its mount/unmount effect, so no parent-level tab-change cleanup
  // is needed — the previous code zeroed a single shared ref defensively,
  // which is now obsolete with the per-feed Map.

  const _onCreatePress = () => {
    SheetManager.show(SheetNames.QUICK_POST, {
      payload: { mode: 'wave' },
    });
  };

  const _scrollTop = () => {
    activeListRef.current?.scrollToOffset({ offset: 0, animated: true });
    setEnableScrollTop(false);
  };

  const _handleScrollStateChange = ({ enabled, offset }: { enabled: boolean; offset: number }) => {
    if (offset <= SCROLL_POPUP_THRESHOLD) {
      setEnableScrollTop(false);
      return;
    }

    setEnableScrollTop(enabled);
  };

  const _renderFilterChip = activeTag ? (
    <WavesHeader activeTag={activeTag} onClearTag={_handleClearTag} />
  ) : null;

  const _renderWavesScene = ({ route }: { route: { key: string } }) => {
    // Shorts is a source like any other in the picker, but it's cross-container
    // and gets the full-screen vertical reels viewer instead of the waves list.
    if (route.key === `container:${SHORTS_SOURCE}`) {
      // TabView keeps visited scenes mounted, so gate playback on the Shorts tab
      // actually being the active, on-screen tab — otherwise the reel keeps
      // playing (and holding the player) after switching away.
      const shortsFocused = isScreenFocused && feedType === `container:${SHORTS_SOURCE}`;
      return (
        <View style={styles.tabScene}>
          <WavesReelsView
            observer={observer}
            isDarkTheme={isDarkTheme}
            listRef={_getDynamicTabRef(route.key)}
            focused={shortsFocused}
          />
        </View>
      );
    }

    if (route.key === 'following') {
      if (!currentAccount?.name) {
        return <View style={styles.tabScene} />;
      }

      return (
        <View style={styles.tabScene}>
          <WavesFeed
            queryOptions={followingQueryOptions}
            queryKey={`following:${currentAccount?.name}`}
            listRef={followingListRef}
            onTagPress={_handleTagFilter}
            onOptionsPress={_handleOnOptionsPress}
            onScrollStateChange={_handleScrollStateChange}
            feedKey="following"
            registerDeleter={_registerFeedDeleter}
            isDarkTheme={isDarkTheme}
          />
        </View>
      );
    }

    if (route.key.startsWith('tag:')) {
      const queryOptions = tagTabQueryOptions[route.key];
      if (!queryOptions) {
        return <View style={styles.tabScene} />;
      }
      return (
        <View style={styles.tabScene}>
          <WavesFeed
            queryOptions={queryOptions}
            queryKey={route.key}
            listRef={_getDynamicTabRef(route.key)}
            onTagPress={_handleTagFilter}
            onOptionsPress={_handleOnOptionsPress}
            onScrollStateChange={_handleScrollStateChange}
            feedKey={route.key}
            registerDeleter={_registerFeedDeleter}
            isDarkTheme={isDarkTheme}
          />
        </View>
      );
    }

    if (route.key.startsWith('container:')) {
      const queryOptions = containerTabQueryOptions[route.key];
      if (!queryOptions) {
        return <View style={styles.tabScene} />;
      }
      return (
        <View style={styles.tabScene}>
          <WavesFeed
            queryOptions={queryOptions}
            queryKey={route.key}
            listRef={_getDynamicTabRef(route.key)}
            onTagPress={_handleTagFilter}
            onOptionsPress={_handleOnOptionsPress}
            onScrollStateChange={_handleScrollStateChange}
            feedKey={route.key}
            registerDeleter={_registerFeedDeleter}
            isDarkTheme={isDarkTheme}
          />
        </View>
      );
    }

    if (activeTag) {
      return (
        <View style={styles.tabScene}>
          {_renderFilterChip}
          <WavesFeed
            queryOptions={tagQueryOptions}
            queryKey={`tag:${activeTag}`}
            listRef={tagListRef}
            onTagPress={_handleTagFilter}
            onOptionsPress={_handleOnOptionsPress}
            onScrollStateChange={_handleScrollStateChange}
            feedKey="tag"
            registerDeleter={_registerFeedDeleter}
            isDarkTheme={isDarkTheme}
          />
        </View>
      );
    }

    return (
      <View style={styles.tabScene}>
        <WavesFeed
          queryOptions={forYouQueryOptions}
          queryKey="for-you"
          listRef={forYouListRef}
          onTagPress={_handleTagFilter}
          onOptionsPress={_handleOnOptionsPress}
          onScrollStateChange={_handleScrollStateChange}
          feedKey="for-you"
          registerDeleter={_registerFeedDeleter}
          isDarkTheme={isDarkTheme}
        />
      </View>
    );
  };

  return (
    <View style={styles.container}>
      <Header />

      <View style={styles.contentContainer} onLayout={_lazyLoadContent}>
        {/* Getting-started nudges for new accounts; the card gates itself on
            account freshness, dismissal and completion, so it renders nothing
            for established users. */}
        {isLoggedIn && <WavesOnboardingChecklist />}
        {lazyLoad ? (
          <TabView
            navigationState={{ index: wavesIndex, routes: wavesRoutes }}
            style={styles.tabView}
            renderTabBar={(tabProps) => <WavesTabBar {...tabProps} onTabPress={_handleTabChange} />}
            renderScene={_renderWavesScene}
            onIndexChange={(index) => {
              const nextFeed = wavesRoutes[index]?.key;
              if (nextFeed && nextFeed !== feedType) {
                _handleTabChange(nextFeed);
              }
            }}
            animationEnabled={false}
            lazy={true}
            swipeEnabled={isLoggedIn}
            commonOptions={{
              label: renderPillTabLabel as any,
            }}
          />
        ) : null}

        <ScrollTopPopup enable={enableScrollTop} onPress={_scrollTop} />
      </View>
      <PostOptionsModal
        ref={postOptionsModalRef}
        isVisibleTranslateModal={true}
        isWave={true}
        onDelete={async (content) => {
          // Route the options-menu delete through the *currently active*
          // feed's `wavesQuery.deleteWave`, which both broadcasts the
          // delete and removes the wave from the waves infinite-query
          // cache for THAT feed. The modal's default path uses
          // `navigation.goBack()` and never updates the cache.
          //
          // Awaited so any future rejection propagates back to
          // PostOptionsModal's onDelete try/catch (today the SDK delete
          // hook swallows its own errors and shows its own toast, but the
          // contract should stay forward-compatible).
          const activeFeedKey = activeTag ? 'tag' : feedType;
          const deleteWave = feedDeletersRef.current.get(activeFeedKey);
          if (deleteWave) {
            await deleteWave({
              _permlink: content.permlink,
              _parent_permlink: content.parent_permlink,
              _parent_author: content.parent_author,
            });
            return;
          }

          // No deleter registered for the active feed (scene not yet
          // lazy-loaded or torn down between tap and confirm). Don't
          // silently swallow the confirmed delete — tell the user and log
          // so we can spot it.
          console.warn(
            'wavesScreen: no deleter registered for active feed; cannot delete',
            activeFeedKey,
            content.permlink,
          );
          Alert.alert(intl.formatMessage({ id: 'alert.fail' }));
        }}
      />
      {/* No compose-wave FAB on the Shorts reels tab: you don't post into
          shorts, and the FAB would sit on top of the reel's tip button. */}
      {feedType !== `container:${SHORTS_SOURCE}` && (
        <FabButton bottomOffset={fabBottomOffset} onPress={_onCreatePress} />
      )}
    </View>
  );
};

export default WavesScreen;
