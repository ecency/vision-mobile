import React from 'react';
import { View, Text, TouchableOpacity } from 'react-native';
import moment from 'moment';
import EStyleSheet from 'react-native-extended-stylesheet';
import { Icon, UserAvatar } from '../../../components';
import { getHiveUsernameFromMattermostUser } from '../../../providers/chat/mattermost';
import { chatsStyles as styles } from '../styles/chats.styles';
import { getGroupTitle, isGroupChannel } from '../utils/groupUtils';

interface ChannelListItemProps {
  channel: any;
  currentUserId: string;
  userLookup: Record<string, any>;
  getUnreadMeta: (channel: any) => {
    unreadMentions: number;
    unreadMessages: number;
    unreadCount: number;
    totalUnread: number;
  };
  safeExtractCommunityIdentifier: (channel: any) => string | undefined;
  onPress: (channel: any) => void;
  onShowOptions: (channel: any) => void;
}

export const ChannelListItem: React.FC<ChannelListItemProps> = React.memo(
  ({
    channel,
    currentUserId: _currentUserId,
    userLookup,
    getUnreadMeta,
    safeExtractCommunityIdentifier,
    onPress,
    onShowOptions,
  }) => {
    const isDM = channel?.type === 'D';
    const isFavorite = !!channel?.is_favorite;
    const isMuted = !!channel?.is_muted;

    const unreadMeta = getUnreadMeta(channel);
    const hasUnread = unreadMeta.totalUnread > 0;

    let channelName = channel?.display_name || channel?.name || 'Unknown';
    let channelAvatar = null;

    if (isDM) {
      const teammateId = channel?.teammate_id;
      const directUser = channel?.directUser || (teammateId && userLookup[teammateId]);

      if (directUser) {
        const hiveUsername = getHiveUsernameFromMattermostUser(directUser);
        channelName = hiveUsername || directUser.nickname || directUser.username || channelName;
        channelAvatar = (
          <UserAvatar username={channelName} style={styles.channelAvatar} disableSize />
        );
      }
    }

    if (isGroupChannel(channel)) {
      channelName = getGroupTitle(channel, channelName);
      // Two of the other members, overlapped.
      const names = (channel?.groupUsers || [])
        .map((user: any) => getHiveUsernameFromMattermostUser(user))
        .filter(Boolean)
        .slice(0, 2);
      if (names.length === 2) {
        channelAvatar = (
          <View style={styles.groupAvatar}>
            <UserAvatar username={names[0]} style={styles.groupAvatarBack} disableSize />
            <UserAvatar username={names[1]} style={styles.groupAvatarFront} disableSize />
          </View>
        );
      }
    }

    if (!channelAvatar) {
      const communityId = safeExtractCommunityIdentifier(channel);
      if (communityId) {
        channelAvatar = (
          <UserAvatar username={communityId} style={styles.channelAvatar} disableSize />
        );
      } else {
        const initial = channelName.charAt(0).toUpperCase();
        channelAvatar = (
          <View style={styles.channelAvatarFallback}>
            <Text style={styles.channelAvatarText}>{initial}</Text>
          </View>
        );
      }
    }

    const lastActivityAt = channel?.last_post_at || channel?.update_at || channel?.create_at;
    const relativeTime = lastActivityAt ? moment(lastActivityAt).fromNow() : '';

    return (
      <TouchableOpacity onPress={() => onPress(channel)} style={styles.channelRow}>
        {channelAvatar}
        <View style={styles.channelRowContent}>
          <View style={styles.channelTitleRow}>
            <Text style={styles.channelName} numberOfLines={1}>
              {channelName}
            </Text>
            {isFavorite && (
              <Icon
                name="star"
                iconType="MaterialCommunityIcons"
                size={14}
                color="#FFB300"
                style={styles.channelMetaIcon}
              />
            )}
            {isMuted && (
              <Icon
                name="bell-off"
                iconType="MaterialCommunityIcons"
                size={14}
                color={EStyleSheet.value('$iconColor')}
                style={styles.channelMetaIcon}
              />
            )}
          </View>
          {!!relativeTime && (
            <Text style={styles.channelMeta} numberOfLines={1}>
              {relativeTime}
            </Text>
          )}
        </View>

        {hasUnread && (
          <View style={styles.unreadBadge}>
            <Text style={styles.unreadBadgeText}>{unreadMeta.totalUnread}</Text>
          </View>
        )}

        <TouchableOpacity onPress={() => onShowOptions(channel)} style={styles.channelOptions}>
          <Icon
            name="dots-horizontal"
            iconType="MaterialCommunityIcons"
            size={20}
            color={EStyleSheet.value('$iconColor')}
          />
        </TouchableOpacity>
      </TouchableOpacity>
    );
  },
);

ChannelListItem.displayName = 'ChannelListItem';
