import React, { useEffect, useMemo, useState } from 'react';
import { ScrollView, Text, TouchableOpacity, View } from 'react-native';
import { useIntl } from 'react-intl';
import ActionSheet, { SheetManager, SheetProps } from 'react-native-actions-sheet';
import { UserAvatar } from '../userAvatar';
import {
  ensureMattermostUsersHaveHiveNames,
  fetchMattermostUsersByIds,
} from '../../providers/chat/mattermost';
import { getEmojiDisplay } from '../../screens/chats/utils/messageFormatters';
import {
  findMissingUserIds,
  getUsernameForId,
  groupReactions,
} from '../../screens/chats/utils/groupUtils';
import styles from './chatGroupSheets.styles';

const SHEET_ID = 'chat_reactors';

/**
 * Who reacted to a message, one tab per emoji. Reactors the screen has not
 * loaded yet, such as someone whose reaction arrived live, are looked up once.
 */
const ChatReactorsSheet: React.FC<SheetProps<'chat_reactors'>> = ({ sheetId, payload }) => {
  const intl = useIntl();
  const groups = useMemo(
    () => groupReactions(payload?.reactions, payload?.currentUserId),
    [payload?.reactions, payload?.currentUserId],
  );
  const [selected, setSelected] = useState<string | undefined>(
    payload?.initialEmoji || groups[0]?.emojiName,
  );
  const [lookup, setLookup] = useState<Record<string, any>>(payload?.userLookup || {});

  useEffect(() => {
    const missing = findMissingUserIds(
      groups.flatMap((group) => group.userIds),
      payload?.userLookup || {},
    );
    if (!missing.length) {
      return;
    }
    let cancelled = false;
    fetchMattermostUsersByIds(missing)
      .then((users) => {
        if (!cancelled && Array.isArray(users)) {
          setLookup((prev) => ({ ...prev, ...ensureMattermostUsersHaveHiveNames(users) }));
        }
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [groups, payload?.userLookup]);

  const current = groups.find((group) => group.emojiName === selected) || groups[0];
  const ordered = useMemo(() => {
    const ids = current?.userIds || [];
    // The viewer first, as chat apps commonly do.
    return [...ids].sort((a, b) =>
      a === payload?.currentUserId ? -1 : b === payload?.currentUserId ? 1 : 0,
    );
  }, [current, payload?.currentUserId]);

  const _openProfile = (username: string) => {
    SheetManager.hide(sheetId || SHEET_ID);
    SheetManager.show('quick_profile', { payload: { username } });
  };

  return (
    <ActionSheet id={sheetId || SHEET_ID} gestureEnabled containerStyle={styles.sheetContainer}>
      <View style={styles.container}>
        <Text style={styles.title}>
          {intl.formatMessage({ id: 'chats.reactions_title', defaultMessage: 'Reactions' })}
        </Text>

        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.tabs}>
          {groups.map((group) => {
            const active = group.emojiName === current?.emojiName;
            return (
              <TouchableOpacity
                key={group.emojiName}
                style={[styles.tab, active && styles.tabActive]}
                onPress={() => setSelected(group.emojiName)}
                accessibilityRole="tab"
                accessibilityState={{ selected: active }}
              >
                <Text style={styles.tabEmoji}>{getEmojiDisplay(group.emojiName)}</Text>
                <Text style={[styles.tabCount, active && styles.tabCountActive]}>
                  {group.userIds.length}
                </Text>
              </TouchableOpacity>
            );
          })}
        </ScrollView>

        <ScrollView style={styles.list}>
          {ordered.map((userId) => {
            const isYou = userId === payload?.currentUserId;
            const username = getUsernameForId(userId, lookup);
            const label = isYou
              ? intl.formatMessage({ id: 'chats.reactor_you', defaultMessage: 'You' })
              : username
              ? `@${username}`
              : intl.formatMessage({ id: 'chats.reactor_unknown', defaultMessage: 'Someone' });
            return (
              <TouchableOpacity
                key={userId}
                style={styles.personRow}
                disabled={!username}
                onPress={() => username && _openProfile(username)}
              >
                {username ? (
                  <UserAvatar
                    username={username}
                    style={styles.personAvatar}
                    disableSize
                    noAction
                  />
                ) : (
                  <View style={[styles.personAvatar, styles.personAvatarEmpty]} />
                )}
                <Text style={styles.personName} numberOfLines={1}>
                  {label}
                </Text>
              </TouchableOpacity>
            );
          })}
        </ScrollView>
      </View>
    </ActionSheet>
  );
};

export default ChatReactorsSheet;
