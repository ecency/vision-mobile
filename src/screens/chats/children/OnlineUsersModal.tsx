import React, { useMemo, useCallback } from 'react';
import { View, Text, Modal, TouchableOpacity, FlatList, Pressable, Image } from 'react-native';
import EStyleSheet from 'react-native-extended-stylesheet';
import { useIntl } from 'react-intl';
import Icon from '../../../components/icon';
import { getHiveUsernameFromMattermostUser } from '../../../providers/chat/mattermost';
import { getResizedAvatar } from '../../../utils/image';

interface OnlineUsersModalProps {
  visible: boolean;
  channelMembers: any[];
  userLookup: Record<string, any>;
  onlineUserIds: string[];
  onClose: () => void;
  onUserPress: (username: string) => void;
  memberCount?: number;
  /**
   * When set, only these users are listed. Passed for groups and direct
   * messages, whose member list is complete; the lookup also holds post
   * authors, reactors and other conversations' partners.
   */
  memberIds?: string[];
}

interface UserStatus {
  userId: string;
  user: any;
  username: string;
  displayName: string;
  isOnline: boolean;
}

export const OnlineUsersModal: React.FC<OnlineUsersModalProps> = ({
  visible,
  channelMembers,
  userLookup,
  onlineUserIds,
  onClose,
  onUserPress,
  memberCount,
  memberIds,
}) => {
  const intl = useIntl();

  const userStatuses = useMemo<UserStatus[]>(() => {
    const statuses: UserStatus[] = [];
    const processedUserIds = new Set<string>();

    // Get all user IDs from userLookup (this should have all channel members)
    const allUserIds = memberIds?.length ? memberIds : Object.keys(userLookup);

    console.log('[OnlineUsersModal] Total users in lookup:', allUserIds.length);
    console.log('[OnlineUsersModal] Channel members count:', channelMembers.length);
    console.log('[OnlineUsersModal] Member count prop:', memberCount);
    console.log('[OnlineUsersModal] Online user IDs:', onlineUserIds.length);

    allUserIds.forEach((userId) => {
      if (processedUserIds.has(userId)) {
        return;
      }

      const user = userLookup[userId];
      if (!user) {
        return;
      }

      const hiveUsername = getHiveUsernameFromMattermostUser(user);
      const username = hiveUsername || user.username || user.nickname || user.name || 'Unknown';
      const displayName = user.nickname || user.name || username;

      // Check if user is online based on onlineUserIds
      const isOnline = onlineUserIds.includes(userId);

      statuses.push({
        userId,
        user,
        username,
        displayName,
        isOnline,
      });

      processedUserIds.add(userId);
    });

    console.log('[OnlineUsersModal] Total statuses created:', statuses.length);

    // Sort: online users first, then alphabetically within each group
    statuses.sort((a, b) => {
      if (a.isOnline && !b.isOnline) {
        return -1;
      }
      if (!a.isOnline && b.isOnline) {
        return 1;
      }
      return a.displayName.localeCompare(b.displayName);
    });

    return statuses;
  }, [userLookup, onlineUserIds, channelMembers.length, memberCount, memberIds]);

  const handleUserPress = useCallback(
    (username: string) => {
      onUserPress(username);
      onClose();
    },
    [onUserPress, onClose],
  );

  const renderUser = useCallback(
    // eslint-disable-next-line react/no-unused-prop-types
    ({ item }: { item: UserStatus }) => {
      const avatarUrl = item.username ? getResizedAvatar(item.username, 'small') : null;

      return (
        <TouchableOpacity
          style={styles.userItem}
          onPress={() => handleUserPress(item.username)}
          activeOpacity={0.7}
        >
          <View style={styles.userAvatarContainer}>
            {avatarUrl ? (
              <Image source={{ uri: avatarUrl }} style={styles.userAvatar} />
            ) : (
              <View style={[styles.userAvatar, styles.userAvatarPlaceholder]}>
                <Icon
                  name="account"
                  iconType="MaterialCommunityIcons"
                  size={20}
                  color={EStyleSheet.value('$iconColor')}
                />
              </View>
            )}
            {item.isOnline && <View style={styles.onlineDot} />}
          </View>
          <View style={styles.userInfo}>
            <Text style={styles.userDisplayName}>{item.displayName}</Text>
            {item.username !== item.displayName && (
              <Text style={styles.userUsername}>@{item.username}</Text>
            )}
          </View>
        </TouchableOpacity>
      );
    },
    [handleUserPress],
  );

  const onlineCount = useMemo(() => userStatuses.filter((u) => u.isOnline).length, [userStatuses]);

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <Pressable style={styles.modalOverlay} onPress={onClose}>
        <Pressable style={styles.modalContent} onPress={(e) => e.stopPropagation()}>
          <View style={styles.modalHeader}>
            <View style={styles.modalTitleRow}>
              <Icon
                name="account-group"
                iconType="MaterialCommunityIcons"
                size={20}
                color={EStyleSheet.value('$primaryBlue')}
              />
              <View>
                <Text style={styles.modalTitle}>
                  {intl.formatMessage({
                    id: 'chats.channel_members',
                    defaultMessage: 'Channel Members',
                  })}
                </Text>
                <Text style={styles.modalSubtitle}>
                  {intl.formatMessage(
                    {
                      id: 'chats.members_count_with_online',
                      defaultMessage: '{total} members • {online} online',
                    },
                    { total: memberCount || userStatuses.length, online: onlineCount },
                  )}
                </Text>
              </View>
            </View>
            <TouchableOpacity onPress={onClose}>
              <Icon
                name="close"
                iconType="MaterialIcons"
                size={24}
                color={EStyleSheet.value('$iconColor')}
              />
            </TouchableOpacity>
          </View>

          {userStatuses.length === 0 ? (
            <View style={styles.centerContainer}>
              <Text style={styles.emptyText}>
                {intl.formatMessage({
                  id: 'chats.no_members',
                  defaultMessage: 'No members found',
                })}
              </Text>
            </View>
          ) : (
            <FlatList
              data={userStatuses}
              renderItem={renderUser}
              keyExtractor={(item) => item.userId}
              contentContainerStyle={styles.listContent}
            />
          )}
        </Pressable>
      </Pressable>
    </Modal>
  );
};

const styles = EStyleSheet.create({
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.5)',
    justifyContent: 'flex-end',
  },
  modalContent: {
    backgroundColor: '$primaryBackgroundColor',
    borderTopLeftRadius: 16,
    borderTopRightRadius: 16,
    maxHeight: '70%',
    paddingBottom: 20,
  },
  modalHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 16,
    borderBottomWidth: 1,
    borderBottomColor: '$borderColor',
  },
  modalTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  modalTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: '$primaryBlack',
  },
  modalSubtitle: {
    fontSize: 13,
    color: '$primaryDarkGray',
    marginTop: 2,
  },
  listContent: {
    paddingHorizontal: 16,
    paddingVertical: 8,
  },
  userItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 12,
    paddingHorizontal: 8,
    borderRadius: 8,
    marginVertical: 2,
  },
  userAvatarContainer: {
    position: 'relative',
    marginRight: 12,
  },
  userAvatar: {
    width: 40,
    height: 40,
    borderRadius: 20,
  },
  userAvatarPlaceholder: {
    backgroundColor: '$primaryLightBackground',
    alignItems: 'center',
    justifyContent: 'center',
  },
  onlineDot: {
    position: 'absolute',
    bottom: 0,
    right: 0,
    width: 12,
    height: 12,
    borderRadius: 6,
    backgroundColor: '#4CAF50',
    borderWidth: 2,
    borderColor: '$primaryBackgroundColor',
  },
  userInfo: {
    flex: 1,
  },
  userDisplayName: {
    fontSize: 15,
    fontWeight: '500',
    color: '$primaryBlack',
  },
  userUsername: {
    fontSize: 13,
    color: '$primaryDarkGray',
    marginTop: 2,
  },
  centerContainer: {
    paddingVertical: 40,
    alignItems: 'center',
    justifyContent: 'center',
  },
  emptyText: {
    fontSize: 14,
    color: '$primaryDarkGray',
    textAlign: 'center',
  },
});
