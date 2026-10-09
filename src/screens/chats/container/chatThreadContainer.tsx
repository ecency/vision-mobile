import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  BackHandler,
  DeviceEventEmitter,
  Dimensions,
  Keyboard,
  Platform,
  View,
} from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { useNavigation, useFocusEffect } from '@react-navigation/native';
import LinkifyIt from 'linkify-it';
import { useIntl } from 'react-intl';
import ImagePicker from 'react-native-image-crop-picker';
import axios from 'axios';
import { useDispatch } from 'react-redux';
import { SheetManager } from 'react-native-actions-sheet';
import unionBy from 'lodash/unionBy';
import { catchPostImage, postBodySummary } from '@ecency/render-helper';

import { getCommunityQueryOptions, getPostQueryOptions } from '@ecency/sdk';
import { useQueryClient } from '@tanstack/react-query';
import { addHiveScheme } from '../utils/chatLinkify';
import ROUTES from '../../../constants/routeNames';
import { useAppSelector, useLinkProcessor } from '../../../hooks';
import { selectCurrentAccount, selectPin } from '../../../redux/selectors';
import { useMattermostWebSocket } from '../../../hooks/useMattermostWebSocket';
import { toastNotification, updateUnreadChatCount } from '../../../redux/actions/uiAction';
import {
  bootstrapMattermostSession,
  fetchMattermostChannelPosts,
  fetchMattermostChannelMembers,
  fetchMattermostUsersByIds,
  sendMattermostMessage,
  getHiveUsernameFromMattermostUser,
  ensureMattermostUsersHaveHiveNames,
  deleteMattermostMessage,
  updateMattermostMessage,
  markMattermostChannelViewed,
  fetchMattermostPost,
  addMattermostReaction,
  removeMattermostReaction,
  joinMattermostChannel,
  fetchMattermostPinnedPosts,
  pinMattermostPost,
  unpinMattermostPost,
  calculateGlobalUnreadTotal,
  fetchMattermostChannels,
  normalizeMattermostChannels,
} from '../../../providers/chat/mattermost';
import { uploadImage } from '../../../providers/ecency/ecency';
import { signImage } from '../../../providers/hive/hive';
import { isSignImageUnavailable } from '../../../constants/imageUpload';
import { chatThreadStyles as styles } from '../styles/chatThread.styles';
import { emojifyMessage } from '../../../utils/emoji';
import { extractImageUrls, extractUrls } from '../../../utils/editor';
import postUrlParser from '../../../utils/postUrlParser';
import { fetchLinkMetadata } from '../../../utils/linkMetadata';
import { isMediaPickerCancellation, reportMediaPickerError } from '../../../utils/mediaPickerError';
import { isCommunityModerator } from '../../../utils/communityModeration';
import { LinkPreview, HiveLinkPreview } from '../../../components';
import { SheetNames } from '../../../navigation/sheets';

// Import utils
import {
  formatPostBody,
  parseMessageContent,
  getAddedUserInfo,
  ChatPost,
} from '../utils/messageFormatters';
import {
  normalizePosts,
  normalizePost,
  normalizeUserLookup,
  normalizeUsersFromMap,
  sortPosts,
} from '../utils/postNormalizers';
import {
  getMentionUsername,
  collectMissingUserIds,
  safeExtractCommunityIdentifier,
} from '../utils/userLookupHelpers';

// Import child components
import { ThreadMessageList } from '../children/ThreadMessageList';
import { ThreadComposer } from '../children/ThreadComposer';
import { ReplyPreview } from '../children/ReplyPreview';
import { EditingBanner } from '../children/EditingBanner';
import { MentionSuggestions } from '../children/MentionSuggestions';
import { ThreadMessageItem } from '../children/ThreadMessageItem';
import { GroupedSystemMessages } from '../children/GroupedSystemMessages';
import { SystemMessageItem } from '../children/SystemMessageItem';
import { MessageReactions } from '../children/MessageReactions';
import { GroupRenameNotice } from '../children/GroupRenameNotice';
import {
  GROUP_RENAMED_EVENT,
  getGroupMembersTitle,
  getRenamedGroupName,
} from '../utils/groupUtils';
import { ChatHeader } from '../children/ChatHeader';
import { PinnedMessagesModal } from '../children/PinnedMessagesModal';
import { OnlineUsersModal } from '../children/OnlineUsersModal';
import { TypingIndicator } from '../children/TypingIndicator';
import { DmWarningBanner } from '../children/DmWarningBanner';
import { ChatBanBanner } from '../children/ChatBanBanner';
import { ChatBanInfo, getChatBanInfo } from '../utils/chatBanNotice';

interface ChatReaction {
  emoji_name: string;
  user_id: string;
  create_at?: number;
}

interface ChatParentPreview {
  parent_id?: string;
  parent_message?: string;
  parent_username?: string;
}

interface GroupedSystemMessage {
  type: 'grouped_system_add';
  id: string;
  posts: ChatPost[];
  create_at: number;
}

export interface ChatThreadContainerProps {
  channelId: string;
  channelName?: string;
  channelDescription?: string;
  initialBootstrap?: any;
  initialUserLookup?: Record<string, any>;
  initialLastViewedAt?: number;
  communityIdentifier?: string;
  channelType?: string;
  /** Group channels: whether the viewer started it and may rename it. */
  groupOwner?: boolean;
  /** Group channels: the name its owner gave it, if any. */
  groupName?: string;
  /** Group channels: the other members. */
  groupUsers?: any[];
}

export const ChatThreadContainer: React.FC<ChatThreadContainerProps> = ({
  channelId,
  channelName,
  channelDescription,
  initialBootstrap,
  initialUserLookup,
  initialLastViewedAt,
  communityIdentifier: paramCommunityIdentifier,
  channelType,
  groupOwner,
  groupName,
  groupUsers,
}) => {
  const intl = useIntl();
  const insets = useSafeAreaInsets();
  const dispatch = useDispatch();
  const navigation = useNavigation();
  const { handleLink } = useLinkProcessor();
  const queryClient = useQueryClient();

  const currentAccount = useAppSelector(selectCurrentAccount);
  const pinCode = useAppSelector(selectPin);

  // State management
  const [bootstrapResult, setBootstrapResult] = useState<any>(initialBootstrap);
  const [posts, setPosts] = useState<ChatPost[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false);
  const [isSending, setIsSending] = useState<boolean>(false);
  const [isUploadingImage, setIsUploadingImage] = useState<boolean>(false);
  const [message, setMessage] = useState<string>('');
  const [mentionQuery, setMentionQuery] = useState<string | null>(null);
  const [mentionStartIndex, setMentionStartIndex] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [banInfo, setBanInfo] = useState<ChatBanInfo | null>(null);
  const [hasBootstrapped, setHasBootstrapped] = useState<boolean>(!!initialBootstrap);
  const [canModerate, setCanModerate] = useState<boolean>(false);
  const [lastViewedAt, setLastViewedAt] = useState<number | null>(initialLastViewedAt ?? null);
  const [unreadAnchor, setUnreadAnchor] = useState<number | null>(initialLastViewedAt ?? null);
  const [firstUnreadIndex, setFirstUnreadIndex] = useState<number | null>(null);
  const [hasScrolledToUnread, setHasScrolledToUnread] = useState<boolean>(false);
  const [editingPostId, setEditingPostId] = useState<string | null>(null);
  const [headerUser, setHeaderUser] = useState<any>(null);
  const [rootMessages, setRootMessages] = useState<Record<string, ChatPost>>({});
  const [rootPost, setRootPost] = useState<ChatPost | null>(null);
  const [isKeyboardVisible, setKeyboardVisible] = useState(false);
  const [keyboardHeight, setKeyboardHeight] = useState(0);
  const [lastPostId, setLastPostId] = useState<string | null>(null);
  const [memberCount, setMemberCount] = useState<number | null>(null);
  const [hasMorePosts, setHasMorePosts] = useState<boolean>(true);
  const [pinnedMessagesModalVisible, setPinnedMessagesModalVisible] = useState<boolean>(false);
  const [onlineUsersModalVisible, setOnlineUsersModalVisible] = useState<boolean>(false);
  const [showDmWarning, setShowDmWarning] = useState<boolean>(false);
  const [linkMeta, setLinkMeta] = useState<{
    url: string;
    author?: string;
    permlink?: string;
    linkMeta: { title: string; summary: string; image: string };
  } | null>(null);
  const [isFetchingLinkMeta, setIsFetchingLinkMeta] = useState<boolean>(false);
  const [pinnedCount, setPinnedCount] = useState<number>(0);
  const [channelMembers, setChannelMembers] = useState<any[]>([]);
  const channelMemberIds = useMemo(
    () =>
      channelMembers
        .map((member: any) => member?.user_id || member?.id)
        .filter((id: any): id is string => typeof id === 'string' && !!id),
    [channelMembers],
  );
  const [onlineUserIds, setOnlineUserIds] = useState<string[]>([]);
  const [wsEnabled, setWsEnabled] = useState<boolean>(true);

  // Refs
  const unreadScrollTimeoutRef = useRef<any>(null);
  const userLookupRef = useRef<Record<string, any>>({});
  const listRef = useRef<any>(null);
  const inputRef = useRef<any>(null);
  const lastMarkedViewedAtRef = useRef<any>(null);
  const lastSentPendingIdRef = useRef<any>(null);
  const lastSentMessageRef = useRef<any>(null);
  const lastSentRootIdRef = useRef<any>(null);
  const lastSentAtRef = useRef<number>(0);
  const confirmedPendingPostIdsRef = useRef<Set<string>>(new Set());
  const sendTimeoutRef = useRef<any>(null);
  const dismissedDmWarningsRef = useRef<Set<string>>(new Set());
  const repliedChannelsRef = useRef<Set<string>>(new Set());
  const messageRef = useRef<string>('');

  useEffect(() => {
    return () => {
      if (sendTimeoutRef.current) {
        clearTimeout(sendTimeoutRef.current);
        sendTimeoutRef.current = null;
      }
      lastSentPendingIdRef.current = null;
    };
  }, []);

  useEffect(() => {
    messageRef.current = message;
  }, [message]);

  // User lookup state
  const [userLookup, setUserLookup] = useState<Record<string, any>>(() => {
    const normalized = normalizeUserLookup(initialUserLookup);
    userLookupRef.current = normalized;
    return normalized;
  });

  const mentionableUsers = useMemo(() => normalizeUsersFromMap(userLookup), [userLookup]);

  const linkifyInstance = useMemo(() => {
    const linkify = new LinkifyIt();
    linkify.set({ fuzzyLink: false });
    addHiveScheme(linkify);
    return linkify;
  }, []);

  // Derive pin/unpin permission: DMs allow both members, channels require moderator status
  const isDM = channelType === 'D';
  const isGroup = channelType === 'G';
  // Set when a rename arrives live, so a channel fetch already in flight does
  // not put the old name back.
  const groupRenamedLiveRef = useRef(false);
  // Seeded from the channel list, then refreshed so a rename made elsewhere,
  // or ownership, is never stale.
  const [groupInfo, setGroupInfo] = useState<{ name: string; owner: boolean; users: any[] }>(
    () => ({ name: groupName || '', owner: !!groupOwner, users: groupUsers || [] }),
  );

  useEffect(() => {
    if (!isGroup || !channelId) {
      return undefined;
    }
    let cancelled = false;
    fetchMattermostChannels()
      .then((response) => {
        const channel = normalizeMattermostChannels(response).find(
          (item: any) => item?.id === channelId,
        );
        if (!cancelled && channel) {
          setGroupInfo((prev) => ({
            name: groupRenamedLiveRef.current
              ? prev.name
              : typeof channel.group_name === 'string'
              ? channel.group_name
              : '',
            owner: !!channel.group_owner,
            users: Array.isArray(channel.groupUsers) ? channel.groupUsers : [],
          }));
        }
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [isGroup, channelId]);
  const canPinUnpin = isDM || canModerate;

  // Bootstrap user ID extraction
  const bootstrapUserId =
    bootstrapResult?.user?.id || bootstrapResult?.user?.userId || bootstrapResult?.userId;

  useEffect(() => {
    if (!isDM || !channelId) {
      setShowDmWarning(false);
      return;
    }

    if (dismissedDmWarningsRef.current.has(channelId)) {
      setShowDmWarning(false);
      return;
    }

    if (!bootstrapUserId) {
      setShowDmWarning(false);
      return;
    }

    const hasReplied =
      repliedChannelsRef.current.has(channelId) ||
      posts.some((post) => (post as any).user_id === bootstrapUserId);
    setShowDmWarning(!hasReplied && posts.length > 0);
  }, [isDM, channelId, posts, bootstrapUserId]);

  // Mention state updater - declared early so WebSocket callbacks can use it
  const _updateMentionState = useCallback((text: string) => {
    const match = text.match(/(^|[\s\n])@([a-zA-Z0-9_.-]*)$/);
    if (match) {
      const startIndex = (match.index || 0) + match[1].length;
      setMentionStartIndex(startIndex);
      setMentionQuery(match[2]);
    } else {
      setMentionStartIndex(null);
      setMentionQuery(null);
    }
  }, []);

  // WebSocket connection for real-time updates
  const {
    isConnected: isWsConnected,
    typingUsers,
    sendTyping,
  } = useMattermostWebSocket({
    enabled: wsEnabled && !!bootstrapResult?.token && !!bootstrapUserId,
    token: bootstrapResult?.token || null,
    userId: bootstrapUserId || null,
    channelId,
    onNewMessage: useCallback(
      (post: any) => {
        const normalized = normalizePost(post);
        if (!normalized) {
          return;
        }

        // A group was renamed: its header is its name.
        if (channelType === 'G' && post?.type === 'system_header_change') {
          const renamed = getRenamedGroupName(post);
          groupRenamedLiveRef.current = true;
          setGroupInfo((prev) => ({ ...prev, name: renamed }));
          DeviceEventEmitter.emit(GROUP_RENAMED_EVENT, { channelId, name: renamed });
        }

        // Clear input when we get confirmation of our own message via WebSocket
        const pendingPostId = post.pending_post_id as string | undefined;
        const pendingMatch =
          pendingPostId &&
          (post as any).user_id === bootstrapUserId &&
          pendingPostId === lastSentPendingIdRef.current;
        const fallbackMatch =
          !pendingPostId &&
          (post as any).user_id === bootstrapUserId &&
          lastSentMessageRef.current !== null &&
          emojifyMessage(post.message) === lastSentMessageRef.current &&
          (post.root_id || '') === (lastSentRootIdRef.current || '') &&
          Math.abs((post.create_at || 0) - lastSentAtRef.current) < 30000;

        if (pendingMatch || fallbackMatch) {
          // A websocket echo of our own just-sent message is independent proof the create
          // landed, and it can arrive when the HTTP response never does (timeout, dropped
          // connection). Without this the banner would sit there until its original expiry
          // even though the ban has clearly been lifted. Both match arms are create-only:
          // pending_post_id is set only when creating, and the fallback keys on
          // lastSentMessageRef, which the create branch is what populates.
          setBanInfo(null);

          const confirmedId = lastSentPendingIdRef.current;
          if (confirmedId) {
            confirmedPendingPostIdsRef.current.add(confirmedId);
          }
          if (channelId) {
            repliedChannelsRef.current.add(channelId);
          }
          setPosts((prevPosts) => {
            const exists = prevPosts.find((p) => p.id === normalized.id);
            if (exists) {
              return prevPosts;
            }
            return [normalized, ...prevPosts];
          });
          const shouldClearComposer =
            messageRef.current.trim() === '' ||
            emojifyMessage(messageRef.current.trim()) === lastSentMessageRef.current;
          if (shouldClearComposer) {
            setMessage('');
            messageRef.current = '';
            inputRef.current?.setNativeProps({ text: '' });
            _updateMentionState('');
            setRootPost(null);
            setLinkMeta(null);
          }
          setIsSending(false);
          lastSentPendingIdRef.current = null;
          lastSentMessageRef.current = null;
          lastSentRootIdRef.current = null;
          lastSentAtRef.current = 0;
          if (sendTimeoutRef.current) {
            clearTimeout(sendTimeoutRef.current);
            sendTimeoutRef.current = null;
          }

          // Refocus input after sending
          setTimeout(() => inputRef.current?.focus(), 50);
          return;
        }

        // Skip only messages that match known pending client ids from this device
        if ((post as any).user_id === bootstrapUserId) {
          const pendingId = post.pending_post_id as string | undefined;
          if (
            pendingId &&
            (pendingId === lastSentPendingIdRef.current ||
              confirmedPendingPostIdsRef.current.has(pendingId))
          ) {
            return;
          }
        }

        setPosts((prevPosts) => {
          const exists = prevPosts.find((p) => p.id === normalized.id);
          if (exists) {
            return prevPosts;
          }
          return [normalized, ...prevPosts];
        });

        // Fetch user if not in lookup
        if ((post as any).user_id && !userLookupRef.current[(post as any).user_id]) {
          fetchMattermostUsersByIds([(post as any).user_id])
            .then((users) => {
              const userMap = ensureMattermostUsersHaveHiveNames(
                normalizeUsersFromMap({ [(post as any).user_id]: users[0] }),
              );
              setUserLookup((prev) => ({ ...prev, ...userMap }));
            })
            .catch(console.error);
        }
      },
      [bootstrapUserId, _updateMentionState, channelId, channelType],
    ),
    onMessageEdited: useCallback(
      (post: any) => {
        const normalized = normalizePost(post);
        if (!normalized) {
          return;
        }

        // Skip edits from current user - they're already updated optimistically
        if ((post as any).user_id === bootstrapUserId) {
          return;
        }

        setPosts((prevPosts) =>
          prevPosts.map((p) => (p.id === normalized.id ? { ...p, ...normalized } : p)),
        );
      },
      [bootstrapUserId],
    ),
    onMessageDeleted: useCallback((postId: string) => {
      setPosts((prevPosts) => prevPosts.filter((p) => p.id !== postId));
    }, []),
    onReactionAdded: useCallback(
      (reaction: any) => {
        // Skip reactions from current user - they're already added optimistically
        if (reaction.user_id === bootstrapUserId) {
          return;
        }

        setPosts((prevPosts) =>
          prevPosts.map((p) => {
            if (p.id === reaction.post_id) {
              const reactions = p.metadata?.reactions || p.props?.reactions || [];
              const exists = reactions.some(
                (r: any) => r.user_id === reaction.user_id && r.emoji_name === reaction.emoji_name,
              );
              if (!exists) {
                const updatedReactions = [...reactions, reaction];
                return {
                  ...p,
                  metadata: { ...p.metadata, reactions: updatedReactions },
                  props: { ...p.props, reactions: updatedReactions },
                };
              }
            }
            return p;
          }),
        );
      },
      [bootstrapUserId],
    ),
    onReactionRemoved: useCallback(
      (reaction: any) => {
        // Skip reactions from current user - they're already removed optimistically
        if (reaction.user_id === bootstrapUserId) {
          return;
        }

        setPosts((prevPosts) =>
          prevPosts.map((p) => {
            if (p.id === reaction.post_id) {
              const reactions = (p.metadata?.reactions || p.props?.reactions || []).filter(
                (r: any) =>
                  !(r.user_id === reaction.user_id && r.emoji_name === reaction.emoji_name),
              );
              return {
                ...p,
                metadata: { ...p.metadata, reactions },
                props: { ...p.props, reactions },
              };
            }
            return p;
          }),
        );
      },
      [bootstrapUserId],
    ),
    onUserTyping: useCallback((userId: string) => {
      // Hook manages typing state automatically
      console.log('[Chat] User typing:', userId);
    }, []),
    onError: useCallback((error: Error) => {
      console.error('[Chat] WebSocket error:', error);
      // Note: HTTP 426 "Upgrade Required" typically means the WebSocket endpoint
      // is not available or the server doesn't support WebSocket upgrades
      // This is expected if the backend doesn't have WebSocket support yet
    }, []),
  });

  // Callbacks
  const _handleMessageChange = useCallback(
    (text: string) => {
      setMessage(text);
      messageRef.current = text;
      _updateMentionState(text);

      // Send typing indicator via WebSocket
      if (isWsConnected && text.length > 0) {
        console.log('[Chat] Sending typing indicator');
        sendTyping();
      } else if (!isWsConnected && text.length > 0) {
        console.log('[Chat] Cannot send typing - WebSocket not connected');
      }
    },
    [_updateMentionState, isWsConnected, sendTyping],
  );

  const mentionSuggestions = useMemo(() => {
    if (mentionQuery === null) {
      return [];
    }

    const query = mentionQuery.toLowerCase();
    const filtered = mentionableUsers.filter((user) => {
      const username = getMentionUsername(user);
      if (!username) {
        return false;
      }

      return query ? username.toLowerCase().includes(query) : true;
    });

    return filtered
      .sort((a, b) => getMentionUsername(a).localeCompare(getMentionUsername(b)))
      .slice(0, 8);
  }, [mentionQuery, mentionableUsers]);

  const _mergeUserLookup = useCallback(
    (mergeFn: (prev: Record<string, any>) => Record<string, any>) => {
      setUserLookup((prev) => {
        const next = mergeFn(prev);
        userLookupRef.current = next;
        return next;
      });
    },
    [],
  );

  // Who to list as members of a group or direct message. Until the member
  // list loads, or if it fails, a group falls back to the members the chat
  // list already knew, never to everyone this screen has looked up.
  const conversationMemberIds = useMemo(() => {
    if (channelMemberIds.length) {
      return channelMemberIds;
    }
    if (!isGroup) {
      return [];
    }
    const known = groupInfo.users.map((user: any) => user?.id).filter(Boolean);
    return bootstrapUserId ? [bootstrapUserId, ...known] : known;
  }, [channelMemberIds, isGroup, groupInfo.users, bootstrapUserId]);

  // Those members need records for the list to show them.
  useEffect(() => {
    if (isGroup && groupInfo.users.length) {
      _mergeUserLookup((prev) => ({
        ...ensureMattermostUsersHaveHiveNames(groupInfo.users),
        ...prev,
      }));
    }
  }, [isGroup, groupInfo.users, _mergeUserLookup]);

  const derivedCommunityIdentifier = useMemo(
    () =>
      // A group's title is its name or its members, never a community.
      channelType === 'G'
        ? undefined
        : paramCommunityIdentifier ||
          safeExtractCommunityIdentifier({
            name: channelName,
            display_name: channelName,
            header: channelDescription,
          }),
    [channelDescription, channelName, channelType, paramCommunityIdentifier],
  );

  const _ensureBootstrap = useCallback(async () => {
    if (hasBootstrapped) {
      return;
    }

    const session = await bootstrapMattermostSession(currentAccount, pinCode);
    setBootstrapResult(session);
    setHasBootstrapped(true);
  }, [currentAccount, hasBootstrapped, pinCode]);

  // Bootstrap effect - add current user to lookup
  useEffect(() => {
    const bootstrapUser = bootstrapResult?.user;
    const userId = bootstrapUser?.id || bootstrapUser?.userId;

    if (userId) {
      _mergeUserLookup((prev) => ({
        ...prev,
        [userId]: {
          ...bootstrapUser,
          hiveUsername: getHiveUsernameFromMattermostUser(bootstrapUser),
        },
      }));
    }
  }, [bootstrapResult, _mergeUserLookup]);

  // Load channel members effect
  useEffect(() => {
    const loadChannelMembers = async () => {
      try {
        await _ensureBootstrap();
        const members = await fetchMattermostChannelMembers(channelId);
        const memberIds = (members || [])
          .map((member: any) => member?.user_id || member?.id)
          .filter(Boolean);

        // Store members for online users modal
        setChannelMembers(members || []);

        if (!lastViewedAt && bootstrapUserId) {
          const selfMember = members?.find(
            (member: any) => member?.user_id === bootstrapUserId || member?.id === bootstrapUserId,
          );

          if (selfMember?.last_viewed_at) {
            setLastViewedAt(selfMember.last_viewed_at);
            setUnreadAnchor((prev) => (prev === null ? selfMember.last_viewed_at : prev));
          }
        }

        if (memberIds.length) {
          const users = await fetchMattermostUsersByIds(memberIds);
          const usersWithHiveNames = ensureMattermostUsersHaveHiveNames(users);
          _mergeUserLookup((prev) => ({ ...prev, ...usersWithHiveNames }));

          // Determine the other user for header (for DM channels)
          if (bootstrapUserId && memberIds.length === 2) {
            const otherUserId = memberIds.find((id: string) => id !== bootstrapUserId);
            if (otherUserId && usersWithHiveNames[otherUserId]) {
              const otherUser = usersWithHiveNames[otherUserId];
              const hiveUsername =
                otherUser.hiveUsername || getHiveUsernameFromMattermostUser(otherUser);
              setHeaderUser({
                name: hiveUsername || otherUser.username,
                display_name: otherUser.nickname || otherUser.name || otherUser.username,
              });
            }
          } else if (memberIds.length > 2 || !bootstrapUserId) {
            // Channel (not DM) - use channel name for header
            if (channelName) {
              setHeaderUser({
                name: channelName.toLowerCase().replace(/\s+/g, ''),
                display_name: channelName,
              });
            }
          }
        } else if (channelName) {
          // No members found, but we have channel name - use it for header
          setHeaderUser({
            name: channelName.toLowerCase().replace(/\s+/g, ''),
            display_name: channelName,
          });
        }
      } catch (err) {
        // ignore member lookup failures so messages still load
        if (channelName && !headerUser) {
          setHeaderUser({
            name: channelName.toLowerCase().replace(/\s+/g, ''),
            display_name: channelName,
          });
        }
      }
    };

    loadChannelMembers();
  }, [
    channelId,
    _ensureBootstrap,
    _mergeUserLookup,
    bootstrapResult,
    lastViewedAt,
    channelName,
    headerUser,
  ]);

  // Load pinned messages count effect
  useEffect(() => {
    const loadPinnedCount = async () => {
      try {
        await _ensureBootstrap();
        const data = await fetchMattermostPinnedPosts(channelId);

        let count = 0;
        if (Array.isArray(data)) {
          count = data.length;
        } else if (data?.order) {
          count = data.order.length;
        } else if (data?.posts) {
          count = Object.keys(data.posts).length;
        }

        setPinnedCount(count);
      } catch (err) {
        // Ignore errors, just keep count at 0
        setPinnedCount(0);
      }
    };

    loadPinnedCount();
  }, [channelId, _ensureBootstrap]);

  // Resolve moderation status effect
  useEffect(() => {
    // The lookup is async, so a pending request from a previous account or
    // community can resolve after the deps change and grant moderation in a
    // context it was never resolved for (logging out clears the flag, then a
    // stale resolve sets it back to true). Ignore any result whose effect run
    // has already been cleaned up.
    let cancelled = false;

    const resolveModeration = async () => {
      if (!derivedCommunityIdentifier || !currentAccount?.name) {
        setCanModerate(false);
        return;
      }

      try {
        const community = await queryClient.fetchQuery(
          getCommunityQueryOptions(derivedCommunityIdentifier, currentAccount.name),
        );
        if (cancelled) {
          return;
        }
        setCanModerate(isCommunityModerator(community?.team, currentAccount.name));
      } catch (err) {
        if (!cancelled) {
          setCanModerate(false);
        }
      }
    };

    resolveModeration();

    return () => {
      cancelled = true;
    };
  }, [currentAccount?.name, derivedCommunityIdentifier, queryClient]);

  // Detect and fetch link metadata when message changes
  useEffect(() => {
    const detectAndFetchLinkMeta = async () => {
      if (!message.trim()) {
        setLinkMeta(null);
        return;
      }

      const urls = extractUrls(message);
      const imageUrls = extractImageUrls({ body: message });
      const nonImageUrls = urls.filter((url) => !imageUrls.includes(url));

      if (nonImageUrls.length === 0) {
        setLinkMeta(null);
        return;
      }

      // Get the first non-image URL
      const firstUrl = nonImageUrls[0];
      try {
        setIsFetchingLinkMeta(true);

        // First, try to parse as Hive post
        const parsed = postUrlParser(firstUrl);
        if (parsed?.author && parsed?.permlink) {
          // It's a Hive post, fetch from Hive
          const post = await queryClient.fetchQuery(
            getPostQueryOptions(parsed.author, parsed.permlink, currentAccount?.name),
          );

          if (post && post.title) {
            // SDK Entry doesn't compute image/summary — derive them from json_metadata/body
            let jsonMeta: any = post.json_metadata;
            if (typeof jsonMeta === 'string') {
              try {
                jsonMeta = JSON.parse(jsonMeta);
              } catch {
                jsonMeta = {};
              }
            }
            // JSON.parse can yield non-objects (null, primitives) — guard against
            // `jsonMeta?.description` blowing up downstream.
            if (!jsonMeta || typeof jsonMeta !== 'object') {
              jsonMeta = {};
            }
            const postForExtract = { ...post, json_metadata: jsonMeta };
            // Platform.OS is wider than 'ios' | 'android' (includes web/windows/macos);
            // narrow safely so postBodySummary always gets a value it accepts.
            const summaryPlatform: 'ios' | 'android' = Platform.OS === 'ios' ? 'ios' : 'android';
            const image =
              (post as any).image || catchPostImage(postForExtract, 600, 500, 'match') || '';
            const summary =
              (post as any).summary ||
              jsonMeta?.description ||
              postBodySummary(postForExtract, 150, summaryPlatform) ||
              '';

            setLinkMeta({
              url: firstUrl,
              author: parsed.author,
              permlink: parsed.permlink,
              linkMeta: {
                title: post.title || '',
                summary,
                image,
              },
            });
            setIsFetchingLinkMeta(false);
            return;
          }
        }

        // Not a Hive post or Hive post fetch failed, try generic link metadata
        const metadata = await fetchLinkMetadata(firstUrl);

        if (metadata && (metadata.title || metadata.summary || metadata.image)) {
          setLinkMeta({
            url: firstUrl,
            linkMeta: {
              title: metadata.title || '',
              summary: metadata.summary || '',
              image: metadata.image || '',
            },
          });
        } else {
          setLinkMeta(null);
        }
      } catch (err) {
        console.log('Error fetching link metadata:', err);
        setLinkMeta(null);
      } finally {
        setIsFetchingLinkMeta(false);
      }
    };

    // Debounce the link detection
    const timeoutId = setTimeout(() => {
      detectAndFetchLinkMeta();
    }, 500);

    return () => clearTimeout(timeoutId);
  }, [message, currentAccount?.name]);

  const _resolveUserProfiles = useCallback(
    async (userIds: string[]) => {
      if (!userIds.length) {
        return;
      }

      try {
        const users = await fetchMattermostUsersByIds(userIds);
        const usersWithHiveNames = ensureMattermostUsersHaveHiveNames(users);
        _mergeUserLookup((prev) => ({ ...prev, ...usersWithHiveNames }));
      } catch (err) {
        // silently ignore user lookup errors so messages still load
      }
    },
    [_mergeUserLookup],
  );

  const _fetchRootMessages = useCallback(
    async (rootIds: string[]) => {
      if (!rootIds.length) {
        return;
      }

      try {
        await _ensureBootstrap();
        const fetchPromises = rootIds.map((rootId) =>
          fetchMattermostPost(channelId, rootId).catch(() => null),
        );
        const results = await Promise.all(fetchPromises);

        const rootMessagesMap: Record<string, ChatPost> = {};
        results.forEach((post) => {
          if (post) {
            const normalized = normalizePost(post);
            if (normalized?.id) {
              rootMessagesMap[normalized.id] = normalized;
            }
          }
        });

        if (Object.keys(rootMessagesMap).length > 0) {
          setRootMessages((prev) => ({ ...prev, ...rootMessagesMap }));
          // Resolve user profiles for root messages
          const rootUserIds = Object.values(rootMessagesMap)
            .map((post) => (post as any).user_id || (post as any).user?.id)
            .filter(Boolean);
          if (rootUserIds.length > 0) {
            _resolveUserProfiles(rootUserIds);
          }
        }
      } catch (err) {
        // silently ignore root message fetch errors
      }
    },
    [channelId, _ensureBootstrap, _resolveUserProfiles],
  );

  const _loadPosts = useCallback(
    async (refresh = false) => {
      if (isRefreshing || isLoading) {
        return;
      }

      if (!refresh && !hasMorePosts) {
        return;
      }

      setIsLoading(!refresh);
      setIsRefreshing(refresh);
      setError(null);

      try {
        await _ensureBootstrap();
        let data;
        const _beforeId = refresh ? null : lastPostId;

        try {
          data = await fetchMattermostChannelPosts(channelId, _beforeId || undefined);
        } catch (err: any) {
          if (axios.isAxiosError(err) && [401, 403, 404].includes(err.response?.status || 0)) {
            const joined = await joinMattermostChannel(channelId);
            const resolvedId = joined?.id || joined?.channel_id || joined?.name || channelId;
            data = await fetchMattermostChannelPosts(resolvedId, _beforeId || undefined);
          } else {
            throw err;
          }
        }

        const membershipRecord =
          data?.member ||
          data?.membership ||
          (Array.isArray(data?.members)
            ? data.members.find(
                (member: any) =>
                  member?.user_id === bootstrapUserId || member?.id === bootstrapUserId,
              )
            : null);

        if (membershipRecord?.last_viewed_at || membershipRecord?.last_view_at) {
          setLastViewedAt(membershipRecord.last_viewed_at || membershipRecord.last_view_at || null);
        }

        // Update member count from response
        if (!!data?.memberCount && memberCount !== data.memberCount) {
          setMemberCount(data.memberCount);
        }

        // Update online user IDs from response
        if (data?.onlineUserIds && Array.isArray(data.onlineUserIds)) {
          setOnlineUserIds(data.onlineUserIds);
        }

        const userMap = ensureMattermostUsersHaveHiveNames(normalizeUsersFromMap(data?.users));
        if (Object.keys(userMap).length) {
          _mergeUserLookup((prev) => ({ ...prev, ...userMap }));
        }

        const normalizedPosts = sortPosts(normalizePosts(data));

        // Check if we got new posts (for pagination detection)
        const hasNewPosts = normalizedPosts.length > 0;
        setHasMorePosts(hasNewPosts);

        setPosts((prev) => {
          if (refresh) {
            return normalizedPosts;
          }
          return unionBy(prev, normalizedPosts, 'id');
        });

        const lastPost = normalizedPosts[normalizedPosts.length - 1];
        if (lastPost?.id) {
          setLastPostId(lastPost.id);
        } else if (!hasNewPosts) {
          // No more posts available
          setHasMorePosts(false);
        }

        _resolveUserProfiles(collectMissingUserIds(normalizedPosts, userLookupRef.current));

        // Collect root_ids and fetch root messages
        const rootIds = new Set<string>();
        normalizedPosts.forEach((post: ChatPost) => {
          if (post.root_id && !normalizedPosts.find((p: ChatPost) => p.id === post.root_id)) {
            rootIds.add(post.root_id);
          }
        });

        if (rootIds.size > 0) {
          _fetchRootMessages(Array.from(rootIds));
        }
      } catch (err: any) {
        setError(err?.message || 'Unable to load messages.');
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    },
    [
      _ensureBootstrap,
      _resolveUserProfiles,
      _mergeUserLookup,
      channelId,
      bootstrapResult,
      lastPostId,
      isRefreshing,
      isLoading,
      memberCount,
      _fetchRootMessages,
    ],
  );

  // Initial load effect - reset state when channel changes
  useEffect(() => {
    // Clear all channel-specific state to prevent cross-channel bleed
    setPosts([]);
    setLastPostId(null);
    setRootMessages({});
    setRootPost(null);
    setFirstUnreadIndex(null);
    setLastViewedAt(initialLastViewedAt ?? null);
    setUnreadAnchor(initialLastViewedAt ?? null);
    setError(null);
    setHasMorePosts(true);
    setMemberCount(null);

    // Load posts for new channel
    _loadPosts(false);
  }, [channelId, initialLastViewedAt]);

  // Reset scroll state when channel changes
  useEffect(() => {
    setHasScrolledToUnread(false);
  }, [channelId, unreadAnchor]);

  // Calculate first unread index
  useEffect(() => {
    if (!posts.length) {
      setFirstUnreadIndex(null);
      return;
    }

    const lastViewed = unreadAnchor || 0;
    let nextIndex = -1;
    for (let i = 0; i < posts.length; i++) {
      const postTimestamp = posts[i].create_at || posts[i].update_at || 0;
      if (postTimestamp > lastViewed) {
        nextIndex = i;
      } else {
        break;
      }
    }

    if (nextIndex >= 0) {
      setFirstUnreadIndex(nextIndex);
      return;
    }

    if (unreadAnchor !== null) {
      setFirstUnreadIndex(null);
      return;
    }

    setFirstUnreadIndex(null);
  }, [posts, unreadAnchor]);

  const _clearUnreadScrollTimeout = useCallback(() => {
    if (unreadScrollTimeoutRef.current) {
      clearTimeout(unreadScrollTimeoutRef.current);
      unreadScrollTimeoutRef.current = null;
    }
  }, []);

  const _scrollToUnread = useCallback(() => {
    if (firstUnreadIndex === null || hasScrolledToUnread || !posts.length) {
      return;
    }

    const targetIndex = Math.min(Math.max(firstUnreadIndex, 0), posts.length - 1);

    try {
      listRef.current?.scrollToIndex({
        index: targetIndex,
        animated: true,
        viewPosition: 0.5,
      });
      setHasScrolledToUnread(true);
      _clearUnreadScrollTimeout();
    } catch (err) {
      _clearUnreadScrollTimeout();
      unreadScrollTimeoutRef.current = setTimeout(() => {
        _scrollToUnread();
      }, 250);
    }
  }, [_clearUnreadScrollTimeout, firstUnreadIndex, hasScrolledToUnread, posts.length]);

  // Auto-scroll to unread effect
  useEffect(() => {
    _scrollToUnread();

    return () => {
      _clearUnreadScrollTimeout();
    };
  }, [_scrollToUnread, _clearUnreadScrollTimeout]);

  const latestPostTimestamp = useMemo(
    () =>
      posts.reduce((max, post) => {
        const timestamp = post.create_at || post.update_at || 0;
        return timestamp > max ? timestamp : max;
      }, 0),
    [posts],
  );

  const _refreshGlobalUnreadChatCount = useCallback(async () => {
    try {
      const unreadTotal = await calculateGlobalUnreadTotal();
      dispatch(updateUnreadChatCount(unreadTotal));
    } catch {
      // keep existing badge value when refresh fails
    }
  }, [dispatch]);

  const _markChannelViewed = useCallback(
    async (latestTimestamp: number) => {
      if (!latestTimestamp) {
        return;
      }

      if (lastMarkedViewedAtRef.current && latestTimestamp <= lastMarkedViewedAtRef.current) {
        return;
      }

      try {
        await _ensureBootstrap();
        await markMattermostChannelViewed(channelId);
        lastMarkedViewedAtRef.current = latestTimestamp;
        setLastViewedAt((prev) => {
          const previous = prev || 0;
          return latestTimestamp > previous ? latestTimestamp : prev;
        });
      } catch (err) {
        // ignore view update failures so the thread still renders
      }
    },
    [_ensureBootstrap, channelId],
  );

  // Mark channel viewed effect
  useEffect(() => {
    const shouldMarkViewed =
      (firstUnreadIndex === null || hasScrolledToUnread) && latestPostTimestamp > 0;

    if (!shouldMarkViewed) {
      return;
    }

    _markChannelViewed(latestPostTimestamp);
  }, [firstUnreadIndex, hasScrolledToUnread, latestPostTimestamp, _markChannelViewed]);

  // Keyboard visibility effect
  useEffect(() => {
    const keyboardDidShowListener = Keyboard.addListener('keyboardDidShow', (e) => {
      setKeyboardVisible(true);
      setKeyboardHeight(
        e.endCoordinates.height + Platform.select({ android: insets.bottom, default: 0 }),
      );
    });
    const keyboardDidHideListener = Keyboard.addListener('keyboardDidHide', (_) => {
      setKeyboardVisible(false);
      setKeyboardHeight(0);
    });

    return () => {
      keyboardDidHideListener.remove();
      keyboardDidShowListener.remove();
    };
  }, [insets.bottom, posts.length]);

  // Screen focus/blur handler - disconnect websocket when leaving chat
  useFocusEffect(
    useCallback(() => {
      // Screen is focused - enable websocket
      console.log('[Chat] Screen focused, enabling WebSocket');
      console.log('[Chat] Bootstrap token:', !!bootstrapResult?.token);
      console.log('[Chat] Bootstrap user ID:', bootstrapUserId);
      setWsEnabled(true);

      return () => {
        // Screen is blurred - disable websocket
        console.log('[Chat] Screen blurred, disabling WebSocket');
        setWsEnabled(false);

        // Refresh global unread count on leaving thread if user viewed messages
        if (lastMarkedViewedAtRef.current) {
          _refreshGlobalUnreadChatCount();
        }
      };
    }, [bootstrapResult, bootstrapUserId, _refreshGlobalUnreadChatCount]),
  );

  // Edit and reply actions
  const _resetEditing = useCallback(() => {
    setEditingPostId(null);
    setMessage('');
    messageRef.current = '';
    inputRef.current?.setNativeProps({ text: '' });
    setMentionQuery(null);
    setMentionStartIndex(null);
    setRootPost(null);
    setLinkMeta(null);
  }, []);

  const _cancelReply = useCallback(() => {
    setRootPost(null);
  }, []);

  const _handleSelectMention = useCallback(
    (user: any) => {
      const username = getMentionUsername(user);
      if (!username) {
        return;
      }

      const prev = messageRef.current;
      const start = mentionStartIndex ?? prev.length;
      const queryLength = mentionQuery?.length ?? 0;
      const afterStart = (mentionStartIndex ?? prev.length) + 1 + queryLength;
      const before = mentionStartIndex !== null ? prev.slice(0, start) : prev;
      const after = mentionStartIndex !== null ? prev.slice(afterStart) : '';
      const mentionText = `@${username}`;
      const needsSpaceAfter =
        after.length === 0 ? ' ' : after.startsWith(' ') || after.startsWith('\n') ? '' : ' ';
      const nextMessage = `${before}${mentionText}${needsSpaceAfter}${after}`;

      setMessage(nextMessage);
      messageRef.current = nextMessage;
      inputRef.current?.setNativeProps({ text: nextMessage });
      _updateMentionState(nextMessage);

      setTimeout(() => inputRef.current?.focus(), 50);
    },
    [mentionQuery, mentionStartIndex, _updateMentionState],
  );

  const _handleStartEdit = useCallback(
    (post: ChatPost) => {
      setRootPost(null);
      const timestamp = post.create_at || post.update_at;
      const body = formatPostBody(post, userLookup, timestamp);
      setMessage(body);
      messageRef.current = body;
      inputRef.current?.setNativeProps({ text: body });
      _updateMentionState(body);
      setEditingPostId(post.id || null);
      setTimeout(() => inputRef.current?.focus(), 100);
    },
    [userLookup, _updateMentionState],
  );

  const _handleSend = async () => {
    const trimmedMessage = message.trim();
    if (!trimmedMessage) {
      return;
    }

    const emojifiedMessage = emojifyMessage(trimmedMessage);

    setError(null);
    setIsSending(true);
    try {
      await _ensureBootstrap();
      if (editingPostId) {
        const response = await updateMattermostMessage(channelId, editingPostId, emojifiedMessage);
        const updatedPost = normalizePost(response) || {
          id: editingPostId,
          message: emojifiedMessage,
        };
        setPosts((prev) =>
          sortPosts(
            prev.map((item) =>
              item.id === editingPostId
                ? { ...item, ...updatedPost, message: emojifiedMessage }
                : item,
            ),
          ),
        );
        setEditingPostId(null);
      } else {
        const rootId = rootPost?.root_id || rootPost?.id || '';

        // Get username from userLookup
        const rootAuthorId = rootPost?.user_id;
        const rootMappedUser = rootAuthorId && userLookupRef.current[rootAuthorId];
        const hiveUsername = getHiveUsernameFromMattermostUser(rootMappedUser);
        const rootUsername =
          (typeof rootMappedUser?.hiveUsername === 'string' ? rootMappedUser.hiveUsername : null) ||
          (typeof hiveUsername === 'string' ? hiveUsername : null) ||
          (typeof rootMappedUser?.nickname === 'string' ? rootMappedUser.nickname : null) ||
          (typeof rootMappedUser?.username === 'string' ? rootMappedUser.username : null) ||
          (typeof rootMappedUser?.name === 'string' ? rootMappedUser.name : null) ||
          '';

        const props = {
          ...(rootId && {
            parent_id: rootPost?.id || '',
            parent_username: rootUsername,
            parent_message: typeof rootPost?.message === 'string' ? rootPost.message : '',
          }),
          ...(linkMeta && {
            link_url: linkMeta.url,
            link_title: linkMeta.linkMeta.title,
            link_summary: linkMeta.linkMeta.summary,
            link_image: linkMeta.linkMeta.image,
          }),
        };

        // Generate unique pending_post_id for idempotency across load-balanced instances
        const pendingPostId = `${bootstrapUserId || 'user'}_${Date.now()}_${Math.random()
          .toString(36)
          .substring(2, 11)}`;

        // Store pending ID for WebSocket confirmation
        lastSentPendingIdRef.current = pendingPostId;
        lastSentMessageRef.current = emojifiedMessage;
        lastSentRootIdRef.current = rootId || '';
        lastSentAtRef.current = Date.now();
        if (sendTimeoutRef.current) {
          clearTimeout(sendTimeoutRef.current);
        }
        sendTimeoutRef.current = setTimeout(() => {
          if (lastSentPendingIdRef.current === pendingPostId) {
            setIsSending(false);
          }
        }, 30000);

        const response = await sendMattermostMessage(
          channelId,
          emojifiedMessage,
          rootId,
          props,
          pendingPostId,
        );

        // Cleared HERE, in the create branch only. The ban gates creating posts and nothing
        // else — editing an existing message is not checked server-side — so a successful edit
        // proves nothing about the restriction and must not dismiss the notice. Only a create
        // that lands shows the ban is actually gone (an early moderator unban).
        setBanInfo(null);

        const newPost = normalizePost(response);
        if (newPost) {
          if (channelId) {
            repliedChannelsRef.current.add(channelId);
          }
          const wasConfirmed = confirmedPendingPostIdsRef.current.has(pendingPostId);
          if (!wasConfirmed) {
            setPosts((prev) => sortPosts([...prev, newPost]));
            _resolveUserProfiles(collectMissingUserIds([newPost], userLookupRef.current));

            // Scroll to bottom when new message is sent
            setTimeout(() => {
              listRef.current?.scrollToIndex({
                index: 0,
                animated: true,
                viewPosition: 0,
              });
            }, 100);
          } else {
            confirmedPendingPostIdsRef.current.delete(pendingPostId);
          }
        }
      }

      // Always clear input after successful send via HTTP response.
      // WebSocket may also clear it via onNewMessage, but HTTP is the reliable path.
      setMessage('');
      messageRef.current = '';
      inputRef.current?.setNativeProps({ text: '' });
      _updateMentionState('');
      setRootPost(null);
      setLinkMeta(null);
      lastSentPendingIdRef.current = null;
      lastSentMessageRef.current = null;
      lastSentRootIdRef.current = null;
      lastSentAtRef.current = 0;
      if (sendTimeoutRef.current) {
        clearTimeout(sendTimeoutRef.current);
        sendTimeoutRef.current = null;
      }
    } catch (err: any) {
      // Clear pending ID on error so we don't match it later
      const pendingId = lastSentPendingIdRef.current;
      if (pendingId && confirmedPendingPostIdsRef.current.has(pendingId)) {
        confirmedPendingPostIdsRef.current.delete(pendingId);
      } else {
        // Check if this is a ban error
        const ban = getChatBanInfo(err);
        if (ban) {
          // Standing state, not a toast: a ban persists, so the explanation has to persist with
          // it. `error` is no good here either — it only renders in the empty-thread view.
          setBanInfo(ban);
        } else if (err?.isBanError) {
          // Ban detected but no usable expiry (an older server, or a malformed payload). Fall
          // back to the previous one-shot message rather than showing a countdown we don't have.
          dispatch(
            toastNotification(
              intl.formatMessage({
                id: 'chats.banned_from_chat',
                defaultMessage: 'Unusual activity detected. Please try again after some time.',
              }),
            ),
          );
        } else {
          setError(err?.message || 'Unable to send your message.');
        }
      }

      lastSentPendingIdRef.current = null;
      lastSentMessageRef.current = null;
      lastSentRootIdRef.current = null;
      lastSentAtRef.current = 0;
      if (sendTimeoutRef.current) {
        clearTimeout(sendTimeoutRef.current);
        sendTimeoutRef.current = null;
      }
    } finally {
      setIsSending(false);
    }
  };

  const _handleRemovePost = useCallback(
    async (post: ChatPost) => {
      if (!post?.id) {
        return;
      }

      try {
        await _ensureBootstrap();
        await deleteMattermostMessage(channelId, post.id);
        setPosts((prev) => prev.filter((item) => item.id !== post.id));
      } catch (err: any) {
        setError(err?.message || 'Unable to remove message.');
      }
    },
    [_ensureBootstrap, channelId],
  );

  const _handleAttachImage = useCallback(async () => {
    if (!currentAccount?.name) {
      return;
    }

    try {
      setIsUploadingImage(true);
      const selection = await ImagePicker.openPicker({ mediaType: 'photo' });
      const media = Array.isArray(selection) ? selection[0] : selection;

      if (!media) {
        setIsUploadingImage(false);
        return;
      }

      const sign = await signImage(media, currentAccount, pinCode);
      const uploadResult: any = await uploadImage(media, currentAccount.name, sign);
      const uploadedUrl = uploadResult?.url || uploadResult?.image || uploadResult?.[0]?.url;

      if (uploadedUrl) {
        const prev = messageRef.current;
        const next = prev ? `${prev.trim()} ${uploadedUrl}` : uploadedUrl;
        setMessage(next);
        messageRef.current = next;
        inputRef.current?.setNativeProps({ text: next });
        _updateMentionState(next);
      } else {
        setError('Unable to attach image.');
      }
    } catch (err: any) {
      if (isMediaPickerCancellation(err)) {
        return;
      }
      // Not a picker failure — the account cannot produce an upload signature,
      // so report it as itself and tell the user how to recover. This goes to an
      // alert rather than `error`, which only renders in the empty-thread view.
      if (isSignImageUnavailable(err)) {
        Alert.alert(
          intl.formatMessage({ id: 'alert.fail' }),
          intl.formatMessage({ id: 'alert.decrypt_fail_alert' }),
        );
        return;
      }
      reportMediaPickerError(err, {
        feature: 'chat-thread',
        action: 'openPicker',
        mediaType: 'photo',
      });
      setError(err?.message || 'Unable to attach image.');
    } finally {
      setIsUploadingImage(false);
    }
  }, [currentAccount, pinCode, intl, _updateMentionState]);

  // Render helpers
  const _showUserProfile = useCallback((username?: string | null) => {
    const cleanedUsername = (username || '').replace(/^@/, '');
    if (!cleanedUsername) {
      return;
    }

    SheetManager.show(SheetNames.QUICK_PROFILE, {
      payload: {
        username: cleanedUsername,
      },
    });
  }, []);

  const _handleReplyToPost = useCallback((post: ChatPost) => {
    setEditingPostId(null);
    setRootPost(post);
    setTimeout(() => inputRef.current?.focus(), 100);
  }, []);

  const _handleAddReaction = useCallback(
    async (post: ChatPost, emojiName: string) => {
      const currentUserId = bootstrapUserId;
      if (!post?.id || !currentUserId) {
        return;
      }

      try {
        await _ensureBootstrap();

        const currentReactions = post.metadata?.reactions || post.props?.reactions || [];
        const hasUserReaction = currentReactions.some(
          (r: ChatReaction) => r.emoji_name === emojiName && r.user_id === currentUserId,
        );

        if (hasUserReaction) {
          await removeMattermostReaction(channelId, post.id, emojiName);
        } else {
          await addMattermostReaction(channelId, post.id, emojiName);
        }

        // Manually update the reactions array
        setPosts((prev) =>
          prev.map((item) => {
            if (item.id !== post.id) {
              return item;
            }

            const existingReactions = item.metadata?.reactions || item.props?.reactions || [];
            let updatedReactions: ChatReaction[];

            if (hasUserReaction) {
              updatedReactions = existingReactions.filter(
                (r: ChatReaction) => !(r.emoji_name === emojiName && r.user_id === currentUserId),
              );
            } else {
              updatedReactions = [
                ...existingReactions,
                {
                  emoji_name: emojiName,
                  user_id: currentUserId,
                  create_at: Date.now(),
                },
              ];
            }

            return {
              ...item,
              metadata: {
                ...item.metadata,
                reactions: updatedReactions,
              },
              props: {
                ...item.props,
                reactions: updatedReactions,
              },
            };
          }),
        );
      } catch (error: any) {
        console.error('Failed to toggle reaction:', error);
        dispatch(
          toastNotification(
            intl.formatMessage({
              id: 'chats.reaction_error',
              defaultMessage: 'Failed to update reaction',
            }),
          ),
        );
      } finally {
        SheetManager.hide(SheetNames.CHAT_OPTIONS);
      }
    },
    [_ensureBootstrap, channelId, bootstrapUserId, dispatch, intl],
  );

  const _confirmDelete = useCallback(
    (postToDelete: ChatPost) => {
      Alert.alert(
        intl.formatMessage({ id: 'chats.remove_message', defaultMessage: 'Remove message?' }),
        intl.formatMessage({
          id: 'chats.remove_message_body',
          defaultMessage: 'This will remove the message for everyone in the channel.',
        }),
        [
          {
            text: intl.formatMessage({ id: 'alert.cancel', defaultMessage: 'Cancel' }),
            style: 'cancel',
          },
          {
            text: intl.formatMessage({ id: 'chats.remove', defaultMessage: 'Remove' }),
            style: 'destructive',
            onPress: () => _handleRemovePost(postToDelete),
          },
        ],
      );
    },
    [intl, _handleRemovePost],
  );

  const _handlePinPost = useCallback(
    async (post: ChatPost) => {
      if (!post?.id) {
        return;
      }

      try {
        await _ensureBootstrap();
        await pinMattermostPost(channelId, post.id);

        // Update the post locally
        setPosts((prev) =>
          prev.map((item) => (item.id === post.id ? { ...item, is_pinned: true } : item)),
        );

        // Update pinned count
        setPinnedCount((prev) => prev + 1);

        dispatch(
          toastNotification(
            intl.formatMessage({
              id: 'alert.success',
              defaultMessage: 'Success!',
            }),
          ),
        );
      } catch (error: any) {
        console.error('Failed to pin message:', error);
        dispatch(
          toastNotification(
            intl.formatMessage({
              id: 'alert.error',
              defaultMessage: 'Error',
            }),
          ),
        );
      } finally {
        SheetManager.hide(SheetNames.CHAT_OPTIONS);
      }
    },
    [_ensureBootstrap, dispatch, intl, channelId],
  );

  const _handleUnpinPost = useCallback(
    async (post: ChatPost) => {
      if (!post?.id) {
        return;
      }

      try {
        await _ensureBootstrap();
        await unpinMattermostPost(channelId, post.id);

        // Update the post locally
        setPosts((prev) =>
          prev.map((item) => (item.id === post.id ? { ...item, is_pinned: false } : item)),
        );

        // Update pinned count
        setPinnedCount((prev) => Math.max(0, prev - 1));

        dispatch(
          toastNotification(
            intl.formatMessage({
              id: 'alert.success',
              defaultMessage: 'Success!',
            }),
          ),
        );
      } catch (error: any) {
        console.error('Failed to unpin message:', error);
        dispatch(
          toastNotification(
            intl.formatMessage({
              id: 'alert.error',
              defaultMessage: 'Error',
            }),
          ),
        );
      } finally {
        SheetManager.hide(SheetNames.CHAT_OPTIONS);
      }
    },
    [_ensureBootstrap, dispatch, intl, channelId],
  );

  const _handleUnpinPostFromModal = useCallback(
    async (post: ChatPost) => {
      if (!post?.id) {
        return;
      }

      try {
        await _ensureBootstrap();
        await unpinMattermostPost(channelId, post.id);

        // Update the post locally
        setPosts((prev) =>
          prev.map((item) => (item.id === post.id ? { ...item, is_pinned: false } : item)),
        );

        // Update pinned count
        setPinnedCount((prev) => Math.max(0, prev - 1));

        dispatch(
          toastNotification(
            intl.formatMessage({
              id: 'alert.success',
              defaultMessage: 'Success!',
            }),
          ),
        );
      } catch (error: any) {
        console.error('Failed to unpin message:', error);
        dispatch(
          toastNotification(
            intl.formatMessage({
              id: 'alert.error',
              defaultMessage: 'Error',
            }),
          ),
        );
      }
    },
    [_ensureBootstrap, dispatch, intl, channelId],
  );

  const _handleTranslateMessage = useCallback((post: ChatPost) => {
    const message = post.message || post.props?.message || '';
    if (message) {
      SheetManager.show(SheetNames.POST_TRANSLATION, {
        payload: {
          content: {
            body: message,
          },
        },
      });
    }
  }, []);

  const _showChatOptionsSheet = useCallback(
    (post: ChatPost, isOwn: boolean) => {
      SheetManager.show(SheetNames.CHAT_OPTIONS, {
        payload: {
          post,
          channelId,
          currentUserId: bootstrapUserId,
          isOwnMessage: isOwn,
          canModerate,
          onReply: () => {
            _handleReplyToPost(post);
          },
          onReaction: (emojiName: string) => {
            _handleAddReaction(post, emojiName);
          },
          onTranslate: () => {
            _handleTranslateMessage(post);
          },
          onEdit: isOwn
            ? () => {
                _handleStartEdit(post);
              }
            : undefined,
          onRemove:
            isOwn || canModerate
              ? () => {
                  _confirmDelete(post);
                }
              : undefined,
          onPin:
            canPinUnpin && !(post as any).is_pinned
              ? () => {
                  _handlePinPost(post);
                }
              : undefined,
          onUnpin:
            canPinUnpin && (post as any).is_pinned
              ? () => {
                  _handleUnpinPost(post);
                }
              : undefined,
        },
      });
    },
    [
      channelId,
      bootstrapUserId,
      canModerate,
      canPinUnpin,
      _handleReplyToPost,
      _handleAddReaction,
      _handleTranslateMessage,
      _handleStartEdit,
      _confirmDelete,
      _handlePinPost,
      _handleUnpinPost,
    ],
  );

  // Header title
  const headerTitle = useMemo(() => {
    if (isGroup) {
      return (
        groupInfo.name ||
        getGroupMembersTitle(
          groupInfo.users,
          channelName || intl.formatMessage({ id: 'chats.group', defaultMessage: 'Group' }),
        )
      );
    }
    let title = '';
    if (headerUser) {
      title = headerUser.display_name || headerUser.name || channelName || channelId;
    } else {
      title = channelName || channelId;
    }
    return title;
  }, [headerUser, channelName, channelId, isGroup, groupInfo, intl]);

  const _showGroupOptions = useCallback(() => {
    SheetManager.show(SheetNames.CHAT_CHANNEL_OPTIONS, {
      payload: {
        title: headerTitle,
        onShowMembers: () => setOnlineUsersModalVisible(true),
        onRename: groupInfo.owner
          ? async () => {
              const result = await SheetManager.show(SheetNames.CHAT_RENAME_GROUP, {
                payload: { channelId, currentName: groupInfo.name },
              });
              if (typeof result?.name === 'string') {
                groupRenamedLiveRef.current = true;
                setGroupInfo((prev) => ({ ...prev, name: result.name || '' }));
                DeviceEventEmitter.emit(GROUP_RENAMED_EVENT, { channelId, name: result.name });
              }
            }
          : undefined,
      },
    });
  }, [channelId, groupInfo, headerTitle]);

  // Header handlers
  const handleBack = useCallback(() => {
    // Check if any modal is open and close it first
    if (onlineUsersModalVisible) {
      setOnlineUsersModalVisible(false);
      return true;
    }
    if (pinnedMessagesModalVisible) {
      setPinnedMessagesModalVisible(false);
      return true;
    }
    // Navigate back if no modal is open
    if (navigation.canGoBack()) {
      navigation.goBack();
      return true;
    }
    return false;
  }, [onlineUsersModalVisible, pinnedMessagesModalVisible, navigation]);

  // Hardware back button handler
  useEffect(() => {
    const backHandler = BackHandler.addEventListener('hardwareBackPress', handleBack);
    return () => backHandler.remove();
  }, [handleBack]);

  const handleScrollToMessage = useCallback(
    (postId: string) => {
      const index = posts.findIndex((post) => post.id === postId);
      if (index >= 0 && listRef.current) {
        try {
          listRef.current.scrollToIndex({
            index,
            animated: true,
            viewPosition: 0.5,
          });
        } catch (err) {
          // If scroll fails, try again after a delay
          setTimeout(() => {
            try {
              listRef.current?.scrollToIndex({
                index,
                animated: true,
                viewPosition: 0.5,
              });
            } catch (e) {
              console.log('Failed to scroll to message:', e);
            }
          }, 250);
        }
      }
    },
    [posts],
  );

  // Process and group posts
  const processedPosts = useMemo(() => {
    const result: Array<ChatPost | GroupedSystemMessage> = [];
    let currentGroup: ChatPost[] = [];

    for (let i = 0; i < posts.length; i++) {
      const post = posts[i];
      const isSystemAdd =
        post?.type === 'system_add_to_channel' ||
        post?.type === 'system_add_to_team' ||
        post?.type === 'system_join_team' ||
        post?.type === 'system_join_channel';

      if (isSystemAdd) {
        currentGroup.push(post);
      } else {
        // If we have a group, add it before processing the current post
        if (currentGroup.length > 1) {
          result.push({
            type: 'grouped_system_add',
            id: `group_${currentGroup[0].id}`,
            posts: currentGroup.sort((a, b) => (a.create_at || 0) - (b.create_at || 0)),
            create_at: currentGroup[0].create_at || currentGroup[0].update_at || 0,
          });
        } else if (currentGroup.length === 1) {
          // Single system add message, add it as-is
          result.push(currentGroup[0]);
        }
        currentGroup = [];
        result.push(post);
      }
    }

    // Handle remaining group at the end
    if (currentGroup.length > 1) {
      result.push({
        type: 'grouped_system_add',
        id: `group_${currentGroup[0].id}`,
        posts: currentGroup,
        create_at: currentGroup[0].create_at || currentGroup[0].update_at || 0,
      });
    } else if (currentGroup.length === 1) {
      result.push(currentGroup[0]);
    }

    return result;
  }, [posts]);

  // Auto-fetch more posts if needed
  useEffect(() => {
    if (
      processedPosts.length < 15 &&
      !isLoading &&
      !isRefreshing &&
      hasMorePosts &&
      posts.length > 0
    ) {
      _loadPosts(false);
    }
  }, [processedPosts.length, isLoading, isRefreshing, hasMorePosts, posts.length, _loadPosts]);

  // List content style
  const listContentStyle = useMemo(
    () => [styles.listContent, { paddingBottom: styles.listContent.paddingBottom + insets.bottom }],
    [insets.bottom],
  );

  // Helper function for rendering reply preview
  const _renderReplyPreview = useCallback(
    (rootId: string, parentPreview: ChatParentPreview | null, isOwnMessage: boolean) => {
      if (!parentPreview) {
        const rootMessage = rootMessages[rootId];
        if (!rootMessage) {
          return null;
        }
        // Get username from userLookup
        const rootAuthorId = rootMessage.user_id;
        const rootMappedUser = rootAuthorId && userLookup[rootAuthorId];
        const hiveUsername = getHiveUsernameFromMattermostUser(rootMappedUser);

        parentPreview = {
          parent_id: rootMessage.id,
          parent_message: rootMessage.message || '',
          parent_username: hiveUsername,
        };
      }

      return (
        <ReplyPreview
          rootId={rootId}
          parentPreview={parentPreview}
          isOwnMessage={isOwnMessage}
          showCloseButton={false}
          rootMessages={rootMessages}
          userLookup={userLookup}
        />
      );
    },
    [rootMessages, userLookup],
  );

  // Helper function for rendering reactions
  const _renderReactions = useCallback(
    (reactions: ChatReaction[] | undefined, isOwnMessage: boolean, post: ChatPost) => {
      return (
        <MessageReactions
          reactions={reactions}
          isOwnMessage={isOwnMessage}
          bootstrapUserId={bootstrapUserId}
          onReactionPress={(emojiName: string) => {
            _handleAddReaction(post, emojiName);
          }}
          onReactionLongPress={(emojiName: string) => {
            SheetManager.show(SheetNames.CHAT_REACTORS, {
              payload: {
                reactions: reactions || [],
                userLookup,
                currentUserId: bootstrapUserId,
                initialEmoji: emojiName,
              },
            });
          }}
        />
      );
    },
    [bootstrapUserId, _handleAddReaction, userLookup],
  );

  const _renderMessageLinkPreview = useCallback(
    (messageLinkMeta: any) => {
      if (!messageLinkMeta?.url) {
        return null;
      }

      const screenWidth = Dimensions.get('window').width;
      const contentWidth = screenWidth * 0.8 - 48; // 80% of screen minus padding

      return (
        <LinkPreview
          title={messageLinkMeta.title}
          summary={messageLinkMeta.summary}
          imageUrl={messageLinkMeta.image}
          contentWidth={contentWidth}
          url={messageLinkMeta.url}
          onPress={() => {
            handleLink(messageLinkMeta.url);
          }}
        />
      );
    },
    [handleLink],
  );

  // Render item callback
  const _renderItem = useCallback(
    // eslint-disable-next-line react/no-unused-prop-types
    ({ item, index }: { item: any; index: number }) => {
      // Handle grouped system messages
      if ('type' in item && item.type === 'grouped_system_add') {
        const groupedItem = item as GroupedSystemMessage;

        return (
          <GroupedSystemMessages
            groupedItem={groupedItem}
            index={index}
            firstUnreadIndex={firstUnreadIndex}
            getAddedUserInfo={(post) => getAddedUserInfo(post, userLookup)}
            onShowUserProfile={_showUserProfile}
          />
        );
      }

      // Regular post rendering
      const postItem = item as ChatPost;
      const isSystemAddMessage =
        postItem?.type === 'system_add_to_channel' ||
        postItem?.type === 'system_add_to_team' ||
        postItem?.type === 'system_join_team' ||
        postItem?.type === 'system_join_channel';
      const authorId = (postItem as any).user_id || (postItem as any).user?.id;
      const isOwnMessage = authorId && bootstrapUserId === authorId;

      if (isGroup && postItem?.type === 'system_header_change') {
        const actor =
          getHiveUsernameFromMattermostUser(userLookup[authorId]) ||
          intl.formatMessage({ id: 'chats.reactor_unknown', defaultMessage: 'Someone' });
        const newName = getRenamedGroupName(postItem);
        return (
          <GroupRenameNotice
            showUnreadMarker={firstUnreadIndex !== null && index === firstUnreadIndex}
            text={
              newName
                ? intl.formatMessage(
                    {
                      id: 'chats.group_renamed',
                      defaultMessage: '{user} renamed the group to "{name}"',
                    },
                    { user: actor, name: newName },
                  )
                : intl.formatMessage(
                    {
                      id: 'chats.group_name_cleared',
                      defaultMessage: '{user} removed the group name',
                    },
                    { user: actor },
                  )
            }
          />
        );
      }

      if (isSystemAddMessage) {
        return (
          <SystemMessageItem
            post={postItem}
            index={index}
            firstUnreadIndex={firstUnreadIndex}
            formatPostBody={(post, timestamp) => formatPostBody(post, userLookup, timestamp)}
            parseMessageContent={parseMessageContent}
            getAddedUserInfo={(post) => getAddedUserInfo(post, userLookup)}
            onShowUserProfile={_showUserProfile}
          />
        );
      }

      return (
        <ThreadMessageItem
          post={postItem}
          index={index}
          isOwnMessage={isOwnMessage}
          bootstrapUserId={bootstrapUserId}
          userLookup={userLookup}
          rootMessages={rootMessages}
          firstUnreadIndex={firstUnreadIndex}
          canModerate={canModerate}
          onShowActions={_showChatOptionsSheet}
          onShowUserProfile={_showUserProfile}
          formatPostBody={(post, timestamp) => formatPostBody(post, userLookup, timestamp)}
          parseMessageContent={parseMessageContent}
          renderReplyPreview={_renderReplyPreview}
          renderReactions={_renderReactions}
          renderLinkPreview={_renderMessageLinkPreview}
          linkifyInstance={linkifyInstance}
          handleLink={handleLink}
        />
      );
    },
    [
      firstUnreadIndex,
      userLookup,
      bootstrapUserId,
      rootMessages,
      canModerate,
      _showUserProfile,
      _showChatOptionsSheet,
      _renderReplyPreview,
      _renderReactions,
      _renderMessageLinkPreview,
      linkifyInstance,
      handleLink,
      isGroup,
      intl,
    ],
  );

  // Render composer callback
  const _renderEditingBanner = useCallback(() => {
    return <EditingBanner editingPostId={editingPostId} onCancelEdit={_resetEditing} />;
  }, [editingPostId, _resetEditing]);

  const _renderComposerReplyPreview = useCallback(() => {
    if (!rootPost?.id) {
      return null;
    }

    const rootAuthorId = rootPost.user_id;
    const rootMappedUser = rootAuthorId && userLookup[rootAuthorId];
    const hiveUsername = getHiveUsernameFromMattermostUser(rootMappedUser);
    const parentPreview = {
      parent_id: rootPost.id,
      parent_message: rootPost.message || '',
      parent_username: hiveUsername,
    } as ChatParentPreview;

    return (
      <View style={styles.composerReplyPreview}>
        <ReplyPreview
          rootId={rootPost.id}
          parentPreview={parentPreview}
          isOwnMessage={false}
          showCloseButton={true}
          onClose={_cancelReply}
          rootMessages={rootMessages}
          userLookup={userLookup}
        />
      </View>
    );
  }, [rootPost, userLookup, rootMessages, _cancelReply]);

  const _renderLinkPreview = useCallback(() => {
    // Don't render if not loading and no linkMeta
    if (!isFetchingLinkMeta && !linkMeta) {
      return null;
    }

    // Calculate content width based on screen width minus all composer elements
    // composer paddingHorizontal: 24, attachButton: 44, inputContainer marginRight: 8,
    // sendButton: 44, composerLinkPreview marginHorizontal: 16
    const screenWidth = Dimensions.get('window').width;
    const contentWidth = screenWidth - 144;

    // Use HiveLinkPreview for Hive posts, LinkPreview for other links
    if (linkMeta?.author && linkMeta?.permlink) {
      return (
        <View style={styles.composerLinkPreview}>
          <HiveLinkPreview
            author={linkMeta.author}
            permlink={linkMeta.permlink}
            linkMeta={linkMeta.linkMeta}
            contentWidth={contentWidth}
            url={linkMeta.url}
            onPress={() => {
              handleLink(linkMeta.url);
            }}
            isLoading={isFetchingLinkMeta}
          />
        </View>
      );
    }

    return (
      <View style={styles.composerLinkPreview}>
        <LinkPreview
          title={linkMeta?.linkMeta?.title}
          summary={linkMeta?.linkMeta?.summary}
          imageUrl={linkMeta?.linkMeta?.image}
          contentWidth={contentWidth}
          url={linkMeta?.url}
          onPress={() => {
            if (linkMeta?.url) {
              handleLink(linkMeta.url);
            }
          }}
          isLoading={isFetchingLinkMeta}
        />
      </View>
    );
  }, [linkMeta, isFetchingLinkMeta, handleLink]);

  const _renderMentionSuggestions = useCallback(() => {
    return (
      <MentionSuggestions
        mentionQuery={mentionQuery}
        suggestions={mentionSuggestions}
        onSelectMention={_handleSelectMention}
        getMentionUsername={getMentionUsername}
      />
    );
  }, [mentionQuery, mentionSuggestions, _handleSelectMention]);

  const _handleDismissDmWarning = useCallback(() => {
    if (channelId) {
      dismissedDmWarningsRef.current.add(channelId);
    }
    setShowDmWarning(false);
  }, [channelId]);

  return (
    <SafeAreaView style={styles.container} edges={[]}>
      <ChatHeader
        title={headerTitle}
        memberCount={memberCount || 0}
        pinnedCount={pinnedCount}
        onBack={handleBack}
        onMembersPress={() => setOnlineUsersModalVisible(true)}
        onPinnedPress={() => setPinnedMessagesModalVisible(true)}
        onOptionsPress={isGroup ? _showGroupOptions : undefined}
        isDM={isDM}
      />

      <View style={{ flex: 1 }}>
        {showDmWarning && (
          <DmWarningBanner
            onDismiss={_handleDismissDmWarning}
            onSettingsPress={() => navigation.navigate(ROUTES.SCREENS.SETTINGS)}
          />
        )}
        <ThreadMessageList
          listRef={listRef}
          processedPosts={processedPosts}
          renderItem={_renderItem}
          isRefreshing={isRefreshing}
          onRefresh={() => _loadPosts(true)}
          isLoading={isLoading}
          hasMorePosts={hasMorePosts}
          onLoadMore={() => _loadPosts()}
          error={error}
          listContentStyle={listContentStyle}
          onScrollToIndexFailed={({ index }) =>
            setTimeout(
              () => listRef.current?.scrollToIndex({ index, animated: true, viewPosition: 0.5 }),
              400,
            )
          }
          onContentSizeChange={_scrollToUnread}
        />

        <TypingIndicator
          typingUsers={typingUsers}
          userLookup={userLookup}
          currentUserId={bootstrapUserId}
          getHiveUsername={getHiveUsernameFromMattermostUser as any}
        />

        {banInfo && <ChatBanBanner info={banInfo} onExpire={() => setBanInfo(null)} />}

        <ThreadComposer
          message={message}
          onMessageChange={_handleMessageChange}
          onSend={_handleSend}
          onAttachImage={_handleAttachImage}
          isSending={isSending}
          isUploadingImage={isUploadingImage}
          editingPostId={editingPostId}
          keyboardHeight={keyboardHeight}
          isKeyboardVisible={isKeyboardVisible}
          renderEditingBanner={_renderEditingBanner}
          renderComposerReplyPreview={_renderComposerReplyPreview}
          renderLinkPreview={_renderLinkPreview}
          renderMentionSuggestions={_renderMentionSuggestions}
          inputRef={inputRef}
          insets={insets}
        />
      </View>

      <PinnedMessagesModal
        visible={pinnedMessagesModalVisible}
        channelId={channelId}
        userLookup={userLookup}
        onClose={() => setPinnedMessagesModalVisible(false)}
        onMessagePress={handleScrollToMessage}
        onUnpin={_handleUnpinPostFromModal}
        canUnpin={(_post: ChatPost) => canPinUnpin}
      />

      <OnlineUsersModal
        visible={onlineUsersModalVisible}
        channelMembers={channelMembers}
        userLookup={userLookup}
        onlineUserIds={onlineUserIds}
        memberCount={memberCount || undefined}
        memberIds={isGroup || isDM ? conversationMemberIds : undefined}
        onClose={() => setOnlineUsersModalVisible(false)}
        onUserPress={_showUserProfile}
      />
    </SafeAreaView>
  );
};

export default ChatThreadContainer;
