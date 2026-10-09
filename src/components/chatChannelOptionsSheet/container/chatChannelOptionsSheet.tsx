import React, { useCallback, useMemo } from 'react';
import { Text, TouchableHighlight, View } from 'react-native';
import { useIntl } from 'react-intl';
import EStyleSheet from 'react-native-extended-stylesheet';
import ActionSheet, { SheetManager } from 'react-native-actions-sheet';
import { FlatList } from 'react-native-gesture-handler';

import styles from '../styles/chatChannelOptionsSheet.styles';
import { SheetNames } from '../../../navigation/sheets';

interface ChatChannelOptionsSheetProps {
  payload?: {
    title?: string;
    hasUnread?: boolean;
    isFavorite?: boolean;
    isMuted?: boolean;
    isDM?: boolean;
    onMarkRead?: () => void;
    onToggleFavorite?: () => void;
    onToggleMute?: () => void;
    onLeave?: () => void;
    /** Groups: list who is in it. */
    onShowMembers?: () => void;
    /** Groups the viewer started: name it. */
    onRename?: () => void;
  };
}

type OptionItem = {
  key: string;
  label: string;
  destructive?: boolean;
  onPress?: () => void;
};

const ChatChannelOptionsSheet = ({ payload }: ChatChannelOptionsSheetProps) => {
  const intl = useIntl();

  const _handleAction = useCallback((action?: () => void) => {
    SheetManager.hide(SheetNames.CHAT_CHANNEL_OPTIONS);
    if (action) {
      action();
    }
  }, []);

  const _renderOptionItem = useCallback(
    ({ item }: any) => (
      <TouchableHighlight
        underlayColor={EStyleSheet.value('$primaryLightBackground')}
        onPress={() => _handleAction(item.onPress)}
      >
        <Text style={[styles.optionItem, item.destructive && styles.optionItemDestructive]}>
          {item.label}
        </Text>
      </TouchableHighlight>
    ),
    [_handleAction],
  );

  const options = useMemo(() => {
    const items: OptionItem[] = [];

    if (payload?.hasUnread) {
      items.push({
        key: 'mark-read',
        label: intl.formatMessage({ id: 'chats.mark_as_read', defaultMessage: 'Mark as read' }),
        onPress: payload?.onMarkRead,
      });
    }

    if (payload?.onShowMembers) {
      items.push({
        key: 'members',
        label: intl.formatMessage({ id: 'chats.group_members', defaultMessage: 'Group members' }),
        onPress: payload.onShowMembers,
      });
    }

    if (payload?.onRename) {
      items.push({
        key: 'rename',
        label: intl.formatMessage({ id: 'chats.rename_group', defaultMessage: 'Rename group' }),
        onPress: payload.onRename,
      });
    }

    if (payload?.onToggleFavorite) {
      items.push({
        key: 'favorite',
        label: payload?.isFavorite
          ? intl.formatMessage({ id: 'chats.unfavorite', defaultMessage: 'Remove favorite' })
          : intl.formatMessage({ id: 'chats.favorite', defaultMessage: 'Favorite' }),
        onPress: payload.onToggleFavorite,
      });
    }

    if (payload?.onToggleMute) {
      items.push({
        key: 'mute',
        label: payload?.isMuted
          ? intl.formatMessage({ id: 'chats.unmute', defaultMessage: 'Unmute' })
          : intl.formatMessage({ id: 'chats.mute', defaultMessage: 'Mute' }),
        onPress: payload.onToggleMute,
      });
    }

    if (payload?.onLeave) {
      items.push({
        key: 'leave',
        label: payload?.isDM
          ? intl.formatMessage({
              id: 'chats.leave_conversation',
              defaultMessage: 'Leave conversation',
            })
          : intl.formatMessage({ id: 'chats.leave', defaultMessage: 'Leave channel' }),
        destructive: true,
        onPress: payload.onLeave,
      });
    }

    return items;
  }, [intl, payload]);

  return (
    <ActionSheet
      gestureEnabled={true}
      {...({ hideUnderlay: true } as any)}
      containerStyle={styles.sheetContent}
      indicatorStyle={styles.indicator}
    >
      <View style={styles.container}>
        {payload?.title ? <Text style={styles.title}>{payload.title}</Text> : null}
        <FlatList
          data={options}
          renderItem={_renderOptionItem}
          keyExtractor={(item) => item.key}
          contentContainerStyle={styles.optionsList}
        />
      </View>
    </ActionSheet>
  );
};

export default ChatChannelOptionsSheet;
