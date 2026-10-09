import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  DeviceEventEmitter,
  FlatList,
  RefreshControl,
  Text,
  View,
} from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { useIntl } from 'react-intl';
import { SheetManager } from 'react-native-actions-sheet';

import ROUTES from '../../../constants/routeNames';
import { useAppDispatch, useAppSelector } from '../../../hooks';
import { updateUnreadChatCount } from '../../../redux/actions/uiAction';
import {
  selectCurrentAccount,
  selectPin,
  selectIsLoggedIn,
  selectIsConnected,
} from '../../../redux/selectors';
import { SheetNames } from '../../../navigation/sheets';
import {
  bootstrapMattermostSession,
  fetchMattermostChannels,
  fetchMattermostUsersByIds,
  getHiveUsernameFromMattermostUser,
  ensureMattermostUsersHaveHiveNames,
  searchMattermostChannels,
  searchMattermostUsers,
  joinMattermostChannel,
  startMattermostDirectMessage,
  toggleMattermostChannelFavorite,
  toggleMattermostChannelMute,
  leaveMattermostChannel,
  markMattermostChannelViewed,
  normalizeMattermostChannels,
  calculateGlobalUnreadTotal,
  getChannelUnreadMeta,
} from '../../../providers/chat/mattermost';
import { Header } from '../../../components';
import { chatsStyles as styles } from '../styles/chats.styles';
import { safeExtractCommunityIdentifier } from '../utils/userLookupHelpers';
import {
  GROUP_RENAMED_EVENT,
  getGroupTitle,
  isConversationChannel,
  isGroupChannel,
} from '../utils/groupUtils';
import { StatusPill } from '../children/StatusPill';
import { SearchBar } from '../children/SearchBar';
import { ChannelListItem } from '../children/ChannelListItem';
import { SearchResults } from '../children/SearchResults';

const ChatsContainer = () => {
  const intl = useIntl();
  const navigation = useNavigation();
  const dispatch = useAppDispatch();

  const currentAccount = useAppSelector(selectCurrentAccount);
  const pinCode = useAppSelector(selectPin);
  const isLoggedIn = useAppSelector(selectIsLoggedIn);
  const isConnected = useAppSelector(selectIsConnected);

  const [bootstrapResult, setBootstrapResult] = useState<any>(null);
  const [channels, setChannels] = useState<any[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false);
  const [isSearching, setIsSearching] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [userLookup, setUserLookup] = useState<Record<string, any>>({});
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [searchError, setSearchError] = useState<string | null>(null);
  const [searchResults, setSearchResults] = useState<{ channels: any[]; users: any[] }>(() => ({
    channels: [],
    users: [],
  }));
  const [sortByName, setSortByName] = useState<boolean>(false);

  const userLookupRef = useRef<Record<string, any>>({});
  const searchTimeoutRef = useRef<any>(null);

  const currentUserId = bootstrapResult?.user?.id;

  // ============================================================================
  // Utility Functions
  // ============================================================================

  const _normalizeChannels = normalizeMattermostChannels;

  const _collectDmUserIds = (channelList: any[], knownUsers: Record<string, any>): string[] => {
    const ids = new Set<string>();

    channelList
      .filter((channel) => channel?.type === 'D')
      .forEach((channel) => {
        const teammateId = channel?.teammate_id;
        if (teammateId && teammateId !== currentUserId && !knownUsers[teammateId]) {
          ids.add(teammateId);
        }

        const dmIds = (channel?.name || '').split('__');
        dmIds.forEach((id: string) => {
          if (id && id !== currentUserId && !knownUsers[id]) {
            ids.add(id);
          }
        });
      });

    return Array.from(ids);
  };

  const _getChannelId = useCallback((channel: any): string | undefined => {
    if (!channel) {
      return undefined;
    }

    return (
      channel.id ||
      channel.channel_id ||
      channel.channelId ||
      channel.channel?.id ||
      (typeof channel === 'string' ? channel : undefined)
    );
  }, []);

  const _updateChannelState = useCallback((channelId: string, updates: Record<string, any>) => {
    setChannels((prev) =>
      prev.map((channel) =>
        channel.id === channelId || channel.channel_id === channelId || channel.name === channelId
          ? { ...channel, ...updates }
          : channel,
      ),
    );
  }, []);

  // ============================================================================
  // Unread Meta Calculation
  // ============================================================================

  // Row badges, sorting and the channel-options sheet all read this. It has to
  // be the SAME rule the global badge uses (calculateGlobalUnreadTotal), or a
  // row shows a count the badge does not include — or worse, the badge counts a
  // channel whose row shows nothing to clear.
  const _getUnreadMeta = useCallback((channel: any) => getChannelUnreadMeta(channel), []);

  const _refreshGlobalUnreadChatCount = useCallback(async () => {
    try {
      const unreadTotal = await calculateGlobalUnreadTotal();
      dispatch(updateUnreadChatCount(unreadTotal));
    } catch {
      // keep existing badge value when refresh fails
    }
  }, [dispatch]);

  // ============================================================================
  // Bootstrap and Session Management
  // ============================================================================

  const _ensureBootstrap = useCallback(
    async (refresh = false) => {
      if (bootstrapResult && !refresh) {
        return bootstrapResult;
      }

      const session = await bootstrapMattermostSession(currentAccount, pinCode);
      setBootstrapResult(session);
      return session;
    },
    [bootstrapResult, currentAccount, pinCode],
  );

  const _seedDirectUsers = useCallback((channelList: any[]) => {
    const next: Record<string, any> = {};

    channelList
      .filter((channel) => channel?.type === 'D' && channel?.directUser?.id)
      .forEach((channel) => {
        const { directUser } = channel;
        next[directUser.id] = {
          ...directUser,
          hiveUsername: getHiveUsernameFromMattermostUser(directUser),
        };
      });

    if (Object.keys(next).length) {
      setUserLookup((prev) => {
        const merged = { ...prev, ...next };
        userLookupRef.current = merged;
        return merged;
      });
    }
  }, []);

  const _resolveUserProfiles = useCallback(async (userIds: string[]) => {
    try {
      const users = await fetchMattermostUsersByIds(userIds);
      const usersWithHiveNames = ensureMattermostUsersHaveHiveNames(users);
      setUserLookup((prev) => {
        const merged = { ...prev, ...usersWithHiveNames };
        userLookupRef.current = merged;
        return merged;
      });
    } catch (err) {
      // silently ignore user lookup errors so channel loading still works
    }
  }, []);

  // ============================================================================
  // Channel Loading
  // ============================================================================

  const _loadChannels = useCallback(
    async (refresh = false) => {
      if (!isLoggedIn) {
        setError(
          intl.formatMessage({
            id: 'chats.login_required',
            defaultMessage: 'Sign in to start chatting with your communities.',
          }),
        );
        return;
      }

      if (isConnected === false) {
        setError(
          intl.formatMessage({
            id: 'alert.no_internet',
            defaultMessage: 'No Internet Connection',
          }),
        );
        return;
      }

      setError(null);
      setIsLoading(!refresh);
      setIsRefreshing(refresh);

      try {
        const session = await _ensureBootstrap(refresh);
        setBootstrapResult(session);

        const channelResponse = await fetchMattermostChannels();
        const normalizedChannels = _normalizeChannels(channelResponse);
        setChannels(normalizedChannels);

        _seedDirectUsers(normalizedChannels);

        const dmUserIds = _collectDmUserIds(normalizedChannels, userLookupRef.current);
        if (dmUserIds.length) {
          _resolveUserProfiles(dmUserIds);
        }
      } catch (err: any) {
        setError(err?.message || 'Failed to load chats');
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    },
    [_ensureBootstrap, intl, isConnected, isLoggedIn, _resolveUserProfiles],
  );

  // ============================================================================
  // Channel Operations (Favorite, Mute, Leave, Mark Read)
  // ============================================================================

  const _handleToggleFavorite = useCallback(
    async (channel: any) => {
      try {
        await _ensureBootstrap();
        const shouldFavorite = !channel.is_favorite;
        const updated = await toggleMattermostChannelFavorite(
          channel.id || channel.channel_id,
          shouldFavorite,
        );
        _updateChannelState(channel.id || channel.channel_id, {
          ...updated,
          is_favorite: shouldFavorite,
        });
      } catch (err: any) {
        setError(err?.message || 'Unable to update favorites');
      }
    },
    [_ensureBootstrap, _updateChannelState],
  );

  const _handleToggleMute = useCallback(
    async (channel: any) => {
      try {
        await _ensureBootstrap();
        const shouldMute = !channel.is_muted;
        const updated = await toggleMattermostChannelMute(
          channel.id || channel.channel_id,
          shouldMute,
        );
        _updateChannelState(channel.id || channel.channel_id, {
          ...updated,
          is_muted: shouldMute,
        });
      } catch (err: any) {
        setError(err?.message || 'Unable to update mute');
      }
    },
    [_ensureBootstrap, _updateChannelState],
  );

  // ============================================================================
  // Channel Resolution
  // ============================================================================

  const _resolveChannelIdentity = useCallback(
    async (channel: any) => {
      const currentId = _getChannelId(channel);
      const name = channel?.name || channel?.display_name || currentId;

      if (currentId && name && currentId !== name) {
        return { channelId: currentId, resolvedChannel: channel };
      }

      if (!name) {
        return null;
      }

      const searched = await searchMattermostChannels(name);
      const normalized = _normalizeChannels(searched);
      const match = normalized.find(
        (item) =>
          _getChannelId(item) === currentId ||
          item.name === name ||
          item.display_name === channel?.display_name,
      );

      if (match) {
        return { channelId: _getChannelId(match) || match.name || name, resolvedChannel: match };
      }

      if (channel?.type === 'D') {
        const channelResponse = await fetchMattermostChannels();
        const fallbackChannels = _normalizeChannels(channelResponse);
        const dmMatch = fallbackChannels.find(
          (item) =>
            _getChannelId(item) === currentId ||
            item.name === name ||
            item.display_name === channel?.display_name,
        );

        if (dmMatch) {
          return {
            channelId: _getChannelId(dmMatch) || dmMatch.name || name,
            resolvedChannel: dmMatch,
          };
        }
      }

      return null;
    },
    [_getChannelId, _normalizeChannels],
  );

  const _handleLeaveChannel = useCallback(
    async (channel: any) => {
      try {
        await _ensureBootstrap();
        const resolved = await _resolveChannelIdentity(channel);
        const channelId = resolved?.channelId || _getChannelId(channel) || channel?.name;
        const channelName = channel?.name;

        if (!channelId) {
          throw new Error(
            intl.formatMessage({
              id: 'chats.unable_to_leave_channel',
              defaultMessage: 'Unable to leave channel. Please try again.',
            }),
          );
        }

        await leaveMattermostChannel(channelId);
        setChannels((prev) =>
          prev.filter((item) => {
            const itemId = _getChannelId(item);
            if (itemId && itemId === channelId) {
              return false;
            }
            if (channelName && (item.name === channelName || item.display_name === channelName)) {
              return false;
            }
            return true;
          }),
        );
        await _loadChannels(true);
      } catch (err: any) {
        setError(
          err?.message ||
            intl.formatMessage({
              id: 'chats.unable_to_leave_channel',
              defaultMessage: 'Unable to leave channel. Please try again.',
            }),
        );
      }
    },
    [_ensureBootstrap, _getChannelId, _loadChannels, _resolveChannelIdentity, intl],
  );

  const _handleMarkChannelRead = useCallback(
    async (channel: any) => {
      try {
        await _ensureBootstrap();
        const channelId = _getChannelId(channel);

        if (!channelId) {
          throw new Error(
            intl.formatMessage({
              id: 'chats.unable_to_mark_read',
              defaultMessage: 'Unable to mark channel as read. Please try again.',
            }),
          );
        }

        await markMattermostChannelViewed(channelId);

        const viewedAt = Math.max(Number(channel?.last_post_at) || 0, Date.now());

        _updateChannelState(channelId, {
          unread_messages: 0,
          unread_msg_count: 0,
          unread_count: 0,
          unread_mentions: 0,
          mention_count: 0,
          mentions_count: 0,
          mention_count_root: 0,
          urgent_mention_count: 0,
          channel_wide_mention_count: 0,
          last_viewed_at: viewedAt,
          last_view_at: viewedAt,
          lastViewedAt: viewedAt,
          lastViewed: viewedAt,
        });

        await _refreshGlobalUnreadChatCount();
      } catch (err: any) {
        setError(err?.message || 'Unable to mark channel as read');
      }
    },
    [_ensureBootstrap, _getChannelId, _updateChannelState, _refreshGlobalUnreadChatCount, intl],
  );

  const _handleRenameGroup = useCallback(
    async (channel: any) => {
      const channelId = _getChannelId(channel);
      if (!channelId) {
        return;
      }
      const result = await SheetManager.show(SheetNames.CHAT_RENAME_GROUP, {
        payload: { channelId, currentName: channel?.group_name || '' },
      });
      if (typeof result?.name === 'string') {
        _updateChannelState(channelId, { group_name: result.name || undefined });
      }
    },
    [_getChannelId, _updateChannelState],
  );

  const _confirmChannelOptions = useCallback(
    (channel: any) => {
      const name = isGroupChannel(channel)
        ? getGroupTitle(channel)
        : channel.display_name || channel.name;
      const { totalUnread } = _getUnreadMeta(channel);
      const hasUnread = (totalUnread || 0) > 0;
      // Groups are left like direct messages: closed for this viewer.
      const isDM = isConversationChannel(channel);

      SheetManager.show(SheetNames.CHAT_CHANNEL_OPTIONS, {
        payload: {
          title: name,
          hasUnread,
          isFavorite: channel.is_favorite,
          isMuted: channel.is_muted,
          isDM,
          onMarkRead: hasUnread ? () => _handleMarkChannelRead(channel) : undefined,
          onToggleFavorite: () => _handleToggleFavorite(channel),
          onToggleMute: () => _handleToggleMute(channel),
          onLeave: () => _handleLeaveChannel(channel),
          onRename:
            isGroupChannel(channel) && channel?.group_owner
              ? () => _handleRenameGroup(channel)
              : undefined,
        },
      });
    },
    [
      _getUnreadMeta,
      _handleLeaveChannel,
      _handleRenameGroup,
      _handleMarkChannelRead,
      _handleToggleFavorite,
      _handleToggleMute,
      intl,
    ],
  );

  const _resolveDirectChannel = useCallback(
    async (dmChannel: any, user: any) => {
      const channelId = dmChannel?.id || dmChannel?.channel_id || dmChannel?.name;
      if (channelId) {
        setChannels((prev) => {
          const existing = prev.find(
            (item) =>
              item.id === channelId || item.channel_id === channelId || item.name === channelId,
          );

          if (existing) {
            return prev.map((item) =>
              item.id === channelId || item.channel_id === channelId || item.name === channelId
                ? { ...item, ...dmChannel }
                : item,
            );
          }

          return [dmChannel, ...prev];
        });

        return { channel: dmChannel, channelId };
      }

      const userId = user?.id || user?.user_id;
      if (!userId) {
        throw new Error(
          intl.formatMessage({
            id: 'chats.unable_to_start_dm',
            defaultMessage: 'Unable to start chat. Please try again.',
          }),
        );
      }

      const channelResponse = await fetchMattermostChannels();
      const normalizedChannels = _normalizeChannels(channelResponse);
      const existingDm = normalizedChannels.find(
        (channel) =>
          channel?.type === 'D' &&
          (channel?.teammate_id === userId || (channel?.name || '').split('__').includes(userId)),
      );

      if (existingDm) {
        const resolvedId = existingDm.id || existingDm.channel_id || existingDm.name;

        setChannels((prev) => {
          const already = prev.find(
            (item) =>
              item.id === resolvedId || item.channel_id === resolvedId || item.name === resolvedId,
          );

          if (already) {
            return prev.map((item) =>
              item.id === resolvedId || item.channel_id === resolvedId || item.name === resolvedId
                ? { ...item, ...existingDm }
                : item,
            );
          }

          return [existingDm, ...prev];
        });

        return { channel: existingDm, channelId: resolvedId };
      }

      throw new Error(
        intl.formatMessage({
          id: 'chats.unable_to_start_dm',
          defaultMessage: 'Unable to start chat. Please try again.',
        }),
      );
    },
    [_normalizeChannels, intl],
  );

  // ============================================================================
  // Join Channel and Start DM
  // ============================================================================

  const _handleJoinChannel = useCallback(
    async (channel: any) => {
      try {
        await _ensureBootstrap();
        const resolved = await _resolveChannelIdentity(channel);
        const channelId = resolved?.channelId || _getChannelId(channel) || channel?.name;

        if (channelId === channel?.name && (!resolved || resolved.channelId === channel?.name)) {
          throw new Error(
            intl.formatMessage({
              id: 'chats.unable_to_join_channel',
              defaultMessage: 'Unable to join channel. Please try again.',
            }),
          );
        }

        if (!channelId) {
          throw new Error(
            intl.formatMessage({
              id: 'chats.unable_to_join_channel',
              defaultMessage: 'Unable to join channel. Please try again.',
            }),
          );
        }

        const joined = await joinMattermostChannel(channelId);
        const joinedId = _getChannelId(joined) || channelId;

        // Mark channel as viewed immediately after joining so historical
        // messages don't inflate the unread count for newly joined users
        let viewedSuccessfully = false;
        try {
          await markMattermostChannelViewed(joinedId);
          viewedSuccessfully = true;
        } catch {
          // non-critical — channel will show unreads until manually opened
        }

        const mergedChannel = {
          ...resolved?.resolvedChannel,
          ...channel,
          ...joined,
          ...(viewedSuccessfully && {
            unread_messages: 0,
            unread_mentions: 0,
            mention_count: 0,
          }),
        };
        const communityIdentifier = safeExtractCommunityIdentifier(mergedChannel);

        setChannels((prev) => {
          const exists = prev.find(
            (item) =>
              item.id === joinedId || item.channel_id === joinedId || item.name === joinedId,
          );
          if (exists) {
            return prev.map((item) =>
              item.id === joinedId || item.channel_id === joinedId || item.name === joinedId
                ? { ...item, ...mergedChannel }
                : item,
            );
          }
          return [mergedChannel, ...prev];
        });

        // Refresh global badge to reflect the mark-as-viewed
        _refreshGlobalUnreadChatCount();

        navigation.navigate(ROUTES.SCREENS.CHAT_THREAD, {
          channelId: joinedId,
          channelName: mergedChannel.display_name || mergedChannel.name || channel.name,
          channelDescription: mergedChannel.header || mergedChannel.purpose,
          communityIdentifier,
          bootstrapResult,
          userLookup,
          lastViewedAt: mergedChannel.last_viewed_at || mergedChannel.last_view_at,
          channelType: mergedChannel.type || channel.type,
        });
      } catch (err: any) {
        setSearchError(err?.message || 'Unable to join channel');
      }
    },
    [
      _ensureBootstrap,
      _getChannelId,
      _resolveChannelIdentity,
      bootstrapResult,
      intl,
      navigation,
      userLookup,
    ],
  );

  const _handleStartDm = useCallback(
    async (user: any) => {
      try {
        await _ensureBootstrap();
        const dmChannel = await startMattermostDirectMessage(user);
        const { channel: resolvedChannel, channelId } = await _resolveDirectChannel(
          dmChannel,
          user,
        );
        const communityIdentifier = safeExtractCommunityIdentifier(resolvedChannel);

        navigation.navigate(ROUTES.SCREENS.CHAT_THREAD, {
          channelId,
          channelName: getHiveUsernameFromMattermostUser(user) || user.username || user.nickname,
          channelDescription: resolvedChannel.header || resolvedChannel.purpose,
          communityIdentifier,
          bootstrapResult,
          userLookup,
          lastViewedAt: resolvedChannel.last_viewed_at || resolvedChannel.last_view_at,
          channelType: 'D',
        });
      } catch (err: any) {
        setSearchError(err?.message || 'Unable to start chat');
      }
    },
    [_ensureBootstrap, _resolveDirectChannel, bootstrapResult, navigation, userLookup],
  );

  const _openChannel = useCallback(
    (item: any) => {
      const channelId = item.id || item.channel_id || item.name;
      const description = item.header || item.purpose || '';
      const isGroup = isGroupChannel(item);
      // A group's header is its name, which must not read as a community.
      const communityIdentifier = isGroup ? undefined : safeExtractCommunityIdentifier(item);

      navigation.navigate(ROUTES.SCREENS.CHAT_THREAD, {
        channelId,
        channelName: isGroup ? getGroupTitle(item) : item.display_name || item.name || channelId,
        // A group's header is its name, not a description.
        channelDescription: isGroup ? '' : description,
        communityIdentifier,
        bootstrapResult,
        userLookup,
        lastViewedAt:
          item?.last_viewed_at ||
          item?.last_view_at ||
          item?.lastViewedAt ||
          item?.lastViewed ||
          null,
        channelType: item.type,
        groupOwner: isGroup ? !!item.group_owner : undefined,
        groupName: isGroup ? item.group_name || '' : undefined,
        groupUsers: isGroup ? item.groupUsers : undefined,
      });
    },
    [bootstrapResult, navigation, userLookup],
  );

  const _handleNewGroup = useCallback(async () => {
    const result = await SheetManager.show(SheetNames.CHAT_NEW_GROUP, {
      payload: { currentUsername: currentAccount?.name },
    });
    const channelId = result?.channelId;
    if (!channelId) {
      return;
    }
    try {
      const channelList = _normalizeChannels(await fetchMattermostChannels());
      setChannels(channelList);
      const created = channelList.find((item) => _getChannelId(item) === channelId);
      _openChannel(created || { id: channelId, type: 'G' });
    } catch (err) {
      _openChannel({ id: channelId, type: 'G' });
    }
  }, [_getChannelId, _normalizeChannels, _openChannel, currentAccount?.name]);

  // ============================================================================
  // Search
  // ============================================================================

  const _search = useCallback(
    async (query: string) => {
      const trimmed = query.trim();
      if (trimmed.length < 2) {
        setSearchResults({ channels: [], users: [] });
        setSearchError(null);
        setIsSearching(false);
        return;
      }

      try {
        setIsSearching(true);
        setSearchError(null);
        await _ensureBootstrap();

        const lowerQuery = trimmed.toLowerCase();

        // Search existing channels locally
        const matchingExistingChannels = channels.filter((channel) => {
          const displayName = (channel?.display_name || channel?.name || '').toLowerCase();
          // A group also matches its given name and its members' names.
          const groupTitle = isGroupChannel(channel)
            ? `${channel?.group_name || ''} ${getGroupTitle(channel)}`.toLowerCase()
            : '';
          return displayName.includes(lowerQuery) || groupTitle.includes(lowerQuery);
        });

        // Search API for public channels and users
        const [channelData, userData] = await Promise.all([
          searchMattermostChannels(trimmed),
          searchMattermostUsers(trimmed),
        ]);

        const apiChannels = _normalizeChannels(channelData);
        const normalizedUsers = Array.isArray(userData) ? userData : userData?.users || [];

        // Filter users to prefer those with Hive usernames, but show all if needed
        const usersWithHive: any[] = [];
        const usersWithoutHive: any[] = [];

        normalizedUsers.forEach((user: any) => {
          const hiveUsername = getHiveUsernameFromMattermostUser(user);
          if (hiveUsername) {
            usersWithHive.push(user);
          } else {
            usersWithoutHive.push(user);
          }
        });

        // Prioritize users with Hive usernames
        const sortedUsers = [...usersWithHive, ...usersWithoutHive];

        // Combine and deduplicate channels (existing channels first, then API results)
        const channelMap = new Map();
        matchingExistingChannels.forEach((ch) => {
          const id = ch?.id || ch?.channel_id || ch?.name;
          if (id) {
            channelMap.set(id, ch);
          }
        });
        apiChannels.forEach((ch) => {
          const id = ch?.id || ch?.channel_id || ch?.name;
          if (id && !channelMap.has(id)) {
            channelMap.set(id, ch);
          }
        });

        const combinedChannels = Array.from(channelMap.values());

        setSearchResults({
          channels: combinedChannels,
          users: sortedUsers,
        });
      } catch (err: any) {
        setSearchError(err?.message || 'Unable to search');
      } finally {
        setIsSearching(false);
      }
    },
    [_ensureBootstrap, channels],
  );

  // ============================================================================
  // Sorting and Rendering
  // ============================================================================

  const sortedChannels = useMemo(() => {
    const favorites: any[] = [];
    const unread: any[] = [];
    const regular: any[] = [];
    const muted: any[] = [];

    channels.forEach((channel) => {
      const unreadMeta = _getUnreadMeta(channel);
      const enriched = {
        ...channel,
        unreadMeta,
        lastInteraction: channel.last_viewed_at ?? channel.last_post_at ?? 0,
      };

      if (channel?.is_muted) {
        muted.push(enriched);
      } else if (channel?.is_favorite) {
        favorites.push(enriched);
      } else if (unreadMeta.unreadCount > 0) {
        unread.push(enriched);
      } else {
        regular.push(enriched);
      }
    });

    const getDisplayName = (channel: any): string => {
      let displayName = channel.display_name || channel.name || '';
      if (channel?.type === 'D') {
        displayName = channel.directUser?.username || '';
      } else if (isGroupChannel(channel)) {
        displayName = getGroupTitle(channel);
      }
      return displayName;
    };

    const sortByNameOnly = (list: any[]) =>
      list.sort((a, b) => {
        const nameA = getDisplayName(a).toLowerCase();
        const nameB = getDisplayName(b).toLowerCase();
        return nameA.localeCompare(nameB);
      });

    const sortByRecencyThenUnreadThenName = (list: any[]) =>
      list.sort((a, b) => {
        if (a.lastInteraction !== b.lastInteraction) {
          return b.lastInteraction - a.lastInteraction;
        }

        const unreadDiff = (b.unreadMeta?.totalUnread || 0) - (a.unreadMeta?.totalUnread || 0);
        if (unreadDiff !== 0) {
          return unreadDiff;
        }

        const nameA = (a.display_name || a.name || '').toLowerCase();
        const nameB = (b.display_name || b.name || '').toLowerCase();
        return nameA.localeCompare(nameB);
      });

    const sortByRecencyThenName = (list: any[]) =>
      list.sort((a, b) => {
        if (a.lastInteraction !== b.lastInteraction) {
          return b.lastInteraction - a.lastInteraction;
        }

        const nameA = (a.display_name || a.name || '').toLowerCase();
        const nameB = (b.display_name || b.name || '').toLowerCase();
        return nameA.localeCompare(nameB);
      });

    if (sortByName) {
      const allChannels = [...favorites, ...unread, ...regular, ...muted];
      return sortByNameOnly(allChannels);
    }

    return [
      ...sortByRecencyThenUnreadThenName(favorites),
      ...sortByRecencyThenUnreadThenName(unread),
      ...sortByRecencyThenName(regular),
      ...sortByRecencyThenUnreadThenName(muted),
    ];
  }, [channels, _getUnreadMeta, sortByName, currentUserId, userLookup]);

  const _listEmpty = useMemo(() => {
    if (isLoading) {
      return null;
    }
    if (error) {
      return <Text style={styles.errorText}>{error}</Text>;
    }
    if (!isLoggedIn) {
      return (
        <Text style={styles.emptyState}>
          {intl.formatMessage({
            id: 'chats.login_required',
            defaultMessage: 'Sign in to start chatting with your communities.',
          })}
        </Text>
      );
    }

    return (
      <Text style={styles.emptyState}>
        {intl.formatMessage({
          id: 'chats.empty',
          defaultMessage: 'No chats yet. Join a community to see its channels here.',
        })}
      </Text>
    );
  }, [error, intl, isLoading, isLoggedIn]);

  const _renderChannel = useCallback(
    // eslint-disable-next-line react/no-unused-prop-types
    ({ item }: { item: any }) => (
      <ChannelListItem
        channel={item}
        currentUserId={currentUserId}
        userLookup={userLookup}
        getUnreadMeta={_getUnreadMeta}
        safeExtractCommunityIdentifier={safeExtractCommunityIdentifier}
        onPress={() => _openChannel(item)}
        onShowOptions={_confirmChannelOptions}
      />
    ),
    [currentUserId, userLookup, _getUnreadMeta, _openChannel, _confirmChannelOptions],
  );

  // ============================================================================
  // Effects
  // ============================================================================

  // A group renamed from its conversation screen shows its new name here too.
  useEffect(() => {
    const subscription = DeviceEventEmitter.addListener(
      GROUP_RENAMED_EVENT,
      ({ channelId, name }: { channelId: string; name: string }) => {
        if (channelId) {
          _updateChannelState(channelId, { group_name: name || undefined });
        }
      },
    );
    return () => subscription.remove();
  }, [_updateChannelState]);

  useEffect(() => {
    if (currentAccount) {
      _loadChannels(true);
    }
  }, [currentAccount]);

  useEffect(() => {
    const bootstrapUser = bootstrapResult?.user;
    if (bootstrapUser?.id) {
      setUserLookup((prev) => ({
        ...prev,
        [bootstrapUser.id]: {
          ...bootstrapUser,
          hiveUsername: getHiveUsernameFromMattermostUser(bootstrapUser),
        },
      }));
      userLookupRef.current = {
        ...userLookupRef.current,
        [bootstrapUser.id]: {
          ...bootstrapUser,
          hiveUsername: getHiveUsernameFromMattermostUser(bootstrapUser),
        },
      };
    }
  }, [bootstrapResult]);

  useEffect(() => {
    if (searchTimeoutRef.current) {
      clearTimeout(searchTimeoutRef.current);
    }

    const trimmedQuery = searchQuery.trim();

    if (trimmedQuery.length < 2) {
      setSearchResults({ channels: [], users: [] });
      setSearchError(null);
      setIsSearching(false);
      return;
    }

    searchTimeoutRef.current = setTimeout(() => {
      _search(trimmedQuery);
    }, 350);

    return () => {
      if (searchTimeoutRef.current) {
        clearTimeout(searchTimeoutRef.current);
      }
    };
  }, [_search, searchQuery]);

  // ============================================================================
  // Render
  // ============================================================================

  return (
    <View style={styles.container}>
      <Header hideSearch={true} />

      <View style={styles.header}>
        {error && !isLoading && <Text style={styles.errorText}>{error}</Text>}
      </View>

      <View style={styles.content}>
        <SearchBar
          searchQuery={searchQuery}
          onSearchChange={(text) => setSearchQuery(text.toLowerCase())}
          sortByName={sortByName}
          onToggleSort={() => setSortByName(!sortByName)}
          onNewGroup={isLoggedIn && bootstrapResult ? _handleNewGroup : undefined}
          searchError={searchError}
        />

        {(isLoading || isSearching) && <ActivityIndicator style={{ marginTop: 16 }} />}

        <FlatList
          data={sortedChannels}
          keyExtractor={(item, index) =>
            (item.id || item.channel_id || item.name || index).toString()
          }
          renderItem={_renderChannel}
          refreshControl={
            <RefreshControl refreshing={isRefreshing} onRefresh={() => _loadChannels(true)} />
          }
          ListHeaderComponent={
            <SearchResults
              searchResults={searchResults}
              safeExtractCommunityIdentifier={safeExtractCommunityIdentifier}
              onJoinChannel={_handleJoinChannel}
              onStartDm={_handleStartDm}
            />
          }
          ListEmptyComponent={_listEmpty}
          contentContainerStyle={
            !channels.length
              ? { flex: 1, justifyContent: 'center', paddingBottom: 56 }
              : { paddingBottom: 56, paddingHorizontal: 16 }
          }
        />
      </View>

      <StatusPill isConnected={!!bootstrapResult} />
    </View>
  );
};

export default ChatsContainer;
