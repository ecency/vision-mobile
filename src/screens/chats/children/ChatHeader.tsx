import React from 'react';
import { View, Text, TouchableOpacity } from 'react-native';
import EStyleSheet from 'react-native-extended-stylesheet';
import { IconButton } from '../../../components/iconButton';
import Icon from '../../../components/icon';

interface ChatHeaderProps {
  title: string;
  memberCount: number;
  pinnedCount: number;
  onBack: () => void;
  onMembersPress: () => void;
  onPinnedPress: () => void;
  onOptionsPress?: () => void;
  isDM?: boolean;
}

export const ChatHeader: React.FC<ChatHeaderProps> = ({
  title,
  memberCount,
  pinnedCount,
  onBack,
  onMembersPress,
  onPinnedPress,
  onOptionsPress,
  isDM: _isDM,
}) => {
  return (
    <View style={styles.container}>
      <View style={styles.leftSection}>
        <IconButton
          iconStyle={styles.backIcon}
          iconType="MaterialIcons"
          name="arrow-back"
          onPress={onBack}
        />
        <Text style={styles.title} numberOfLines={1}>
          {title}
        </Text>
      </View>

      <View style={styles.rightSection}>
        {/* Total Members */}
        <TouchableOpacity style={styles.iconButton} onPress={onMembersPress}>
          <Icon
            name="account-group"
            iconType="MaterialCommunityIcons"
            size={20}
            color={EStyleSheet.value('$iconColor')}
          />
          {memberCount > 0 && <Text style={styles.iconCount}>{memberCount}</Text>}
        </TouchableOpacity>

        {/* Pinned Messages */}
        {pinnedCount > 0 && (
          <TouchableOpacity style={styles.iconButton} onPress={onPinnedPress}>
            <Icon
              name="pin"
              iconType="MaterialCommunityIcons"
              size={20}
              color={EStyleSheet.value('$iconColor')}
            />
            <Text style={styles.iconCount}>{pinnedCount}</Text>
          </TouchableOpacity>
        )}

        {/* Options Menu */}
        {onOptionsPress && (
          <TouchableOpacity style={styles.iconButton} onPress={onOptionsPress}>
            <Icon
              name="dots-horizontal"
              iconType="MaterialCommunityIcons"
              size={20}
              color={EStyleSheet.value('$iconColor')}
            />
          </TouchableOpacity>
        )}
      </View>
    </View>
  );
};

const styles = EStyleSheet.create({
  container: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: '$primaryBackgroundColor',
    borderBottomWidth: 1,
    borderBottomColor: '$borderColor',
  },
  leftSection: {
    flexDirection: 'row',
    alignItems: 'center',
    flex: 1,
    marginRight: 12,
  },
  backIcon: {
    fontSize: 24,
    color: '$iconColor',
  },
  title: {
    fontSize: 16,
    fontWeight: '600',
    color: '$primaryBlack',
    marginLeft: 16,
    flex: 1,
  },
  rightSection: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  iconButton: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 6,
    paddingVertical: 4,
    borderRadius: 12,
    backgroundColor: '$primaryLightBackground',
  },
  iconCount: {
    fontSize: 12,
    fontWeight: '600',
    color: '$primaryBlack',
    marginLeft: 4,
  },
});
