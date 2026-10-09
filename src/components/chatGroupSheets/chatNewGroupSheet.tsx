import React, { useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  ScrollView,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { useIntl } from 'react-intl';
import ActionSheet, { SheetManager, SheetProps } from 'react-native-actions-sheet';
import EStyleSheet from 'react-native-extended-stylesheet';
import { MainButton } from '../mainButton';
import { UserAvatar } from '../userAvatar';
import {
  createMattermostGroup,
  getHiveUsernameFromMattermostUser,
  searchMattermostUsers,
} from '../../providers/chat/mattermost';
import { GROUP_MAX_OTHERS, GROUP_MIN_OTHERS } from '../../screens/chats/utils/groupUtils';
import styles from './chatGroupSheets.styles';

const SHEET_ID = 'chat_new_group';

/**
 * Result of the sheet. An object in both cases: react-native-actions-sheet
 * replaces a falsy result with the payload, which would read as success.
 */
export interface ChatNewGroupResult {
  channelId?: string;
  cancelled?: boolean;
}

const usernameOf = (user: any): string =>
  (getHiveUsernameFromMattermostUser(user) || user?.username || '').toLowerCase();

/**
 * Picks 2 to 7 people and opens a group conversation with them. The server
 * checks that everyone accepts messages from everyone else and applies the
 * same limits as direct messages; its message is shown when it refuses.
 */
const ChatNewGroupSheet: React.FC<SheetProps<'chat_new_group'>> = ({ sheetId, payload }) => {
  const intl = useIntl();
  const [term, setTerm] = useState('');
  const [results, setResults] = useState<any[]>([]);
  const [isSearching, setIsSearching] = useState(false);
  const [searchFailed, setSearchFailed] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  const [isCreating, setIsCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const closedRef = useRef(false);

  const self = (payload?.currentUsername || '').toLowerCase();
  const atLimit = selected.length >= GROUP_MAX_OTHERS;

  useEffect(() => {
    const query = term.trim();
    if (query.length < 2 || atLimit) {
      setResults([]);
      setIsSearching(false);
      return;
    }
    let cancelled = false;
    setIsSearching(true);
    setSearchFailed(false);
    const timer = setTimeout(() => {
      searchMattermostUsers(query)
        .then((users) => {
          if (!cancelled) {
            setResults(Array.isArray(users) ? users : []);
          }
        })
        .catch(() => {
          if (!cancelled) {
            setResults([]);
            setSearchFailed(true);
          }
        })
        .finally(() => {
          if (!cancelled) {
            setIsSearching(false);
          }
        });
    }, 350);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [term, atLimit]);

  const _close = (result: ChatNewGroupResult) => {
    if (closedRef.current) {
      return;
    }
    closedRef.current = true;
    SheetManager.hide(sheetId || SHEET_ID, { payload: result });
  };

  const _add = (username: string) => {
    if (atLimit || !username || selected.includes(username)) {
      return;
    }
    setSelected((prev) => [...prev, username]);
    setTerm('');
    setError(null);
  };

  const _remove = (username: string) => {
    setSelected((prev) => prev.filter((name) => name !== username));
    setError(null);
  };

  const _create = async () => {
    if (selected.length < GROUP_MIN_OTHERS || isCreating) {
      return;
    }
    setIsCreating(true);
    setError(null);
    try {
      const { channelId } = await createMattermostGroup(selected);
      _close({ channelId });
    } catch (err: any) {
      setError(
        err?.message ||
          intl.formatMessage({
            id: 'chats.new_group_failed',
            defaultMessage: 'Could not start the group. Please try again.',
          }),
      );
    } finally {
      setIsCreating(false);
    }
  };

  const visibleResults = results
    .map((user) => ({ user, username: usernameOf(user) }))
    .filter(({ username }) => username && username !== self && !selected.includes(username));

  return (
    <ActionSheet
      id={sheetId || SHEET_ID}
      gestureEnabled
      closeOnTouchBackdrop
      containerStyle={styles.sheetContainer}
    >
      <View style={styles.container}>
        <Text style={styles.title}>
          {intl.formatMessage({ id: 'chats.new_group', defaultMessage: 'New group' })}
        </Text>
        <Text style={styles.description}>
          {intl.formatMessage(
            {
              id: 'chats.new_group_hint',
              defaultMessage: 'Add {min} to {max} people to start a group conversation.',
            },
            { min: GROUP_MIN_OTHERS, max: GROUP_MAX_OTHERS },
          )}
        </Text>

        {selected.length > 0 && (
          <View style={styles.chips}>
            {selected.map((username) => (
              <TouchableOpacity
                key={username}
                style={styles.chip}
                onPress={() => _remove(username)}
                accessibilityLabel={intl.formatMessage(
                  { id: 'chats.new_group_remove', defaultMessage: 'Remove @{username}' },
                  { username },
                )}
              >
                <UserAvatar username={username} style={styles.chipAvatar} disableSize noAction />
                <Text style={styles.chipText}>{`@${username}`}</Text>
                <Text style={styles.chipRemove}>×</Text>
              </TouchableOpacity>
            ))}
          </View>
        )}

        <TextInput
          style={styles.input}
          placeholder={
            atLimit
              ? intl.formatMessage(
                  {
                    id: 'chats.new_group_full',
                    defaultMessage: 'A group can have up to {max} other people.',
                  },
                  { max: GROUP_MAX_OTHERS },
                )
              : intl.formatMessage({
                  id: 'chats.new_group_search',
                  defaultMessage: 'Search people by username',
                })
          }
          placeholderTextColor={EStyleSheet.value('$primaryDarkGray')}
          autoCapitalize="none"
          autoCorrect={false}
          editable={!atLimit}
          value={term}
          onChangeText={setTerm}
        />

        {term.trim().length >= 2 && !atLimit && (
          <ScrollView style={styles.results} keyboardShouldPersistTaps="handled">
            {isSearching && !visibleResults.length ? (
              <ActivityIndicator style={styles.resultsSpinner} />
            ) : visibleResults.length ? (
              visibleResults.map(({ user, username }) => (
                <TouchableOpacity
                  key={user?.id || username}
                  style={styles.personRow}
                  onPress={() => _add(username)}
                >
                  <UserAvatar
                    username={username}
                    style={styles.personAvatar}
                    disableSize
                    noAction
                  />
                  <Text style={styles.personName} numberOfLines={1}>{`@${username}`}</Text>
                </TouchableOpacity>
              ))
            ) : (
              <Text style={styles.emptyText}>
                {searchFailed
                  ? intl.formatMessage({
                      id: 'chats.new_group_search_failed',
                      defaultMessage: 'Search did not work. Check your connection and try again.',
                    })
                  : intl.formatMessage({
                      id: 'chats.new_group_no_results',
                      defaultMessage: 'No one found on chat with that name.',
                    })}
              </Text>
            )}
          </ScrollView>
        )}

        {!!error && (
          <Text style={styles.errorText} accessibilityRole="alert">
            {error}
          </Text>
        )}

        <MainButton
          onPress={_create}
          isDisable={selected.length < GROUP_MIN_OTHERS || isCreating}
          isLoading={isCreating}
          text={intl.formatMessage({ id: 'chats.new_group_create', defaultMessage: 'Start group' })}
          style={styles.confirmButton}
        />
        <MainButton
          onPress={() => _close({ cancelled: true })}
          text={intl.formatMessage({ id: 'chats.cancel', defaultMessage: 'Cancel' })}
          style={styles.cancelButton}
          textStyle={styles.cancelButtonText}
        />
      </View>
    </ActionSheet>
  );
};

export default ChatNewGroupSheet;
