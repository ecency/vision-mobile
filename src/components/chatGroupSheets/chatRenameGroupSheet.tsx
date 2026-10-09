import React, { useRef, useState } from 'react';
import { Text, TextInput, View } from 'react-native';
import { useIntl } from 'react-intl';
import ActionSheet, { SheetManager, SheetProps } from 'react-native-actions-sheet';
import EStyleSheet from 'react-native-extended-stylesheet';
import { MainButton } from '../mainButton';
import { renameMattermostGroup } from '../../providers/chat/mattermost';
import { GROUP_NAME_MAX_LENGTH, groupNameLength } from '../../screens/chats/utils/groupUtils';
import styles from './chatGroupSheets.styles';

const SHEET_ID = 'chat_rename_group';

/** An object in both cases, see ChatNewGroupResult. */
export interface ChatRenameGroupResult {
  name?: string;
  cancelled?: boolean;
}

/**
 * Names a group the viewer started, or clears its name. Only the owner is
 * offered this; the server checks it again.
 */
const ChatRenameGroupSheet: React.FC<SheetProps<'chat_rename_group'>> = ({ sheetId, payload }) => {
  const intl = useIntl();
  const [value, setValue] = useState(payload?.currentName || '');
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const closedRef = useRef(false);

  const trimmed = value.trim();
  const unchanged = trimmed === (payload?.currentName || '').trim();
  // Counted as the server counts, so an emoji is one character.
  const tooLong = groupNameLength(trimmed) > GROUP_NAME_MAX_LENGTH;

  const _close = (result: ChatRenameGroupResult) => {
    if (closedRef.current) {
      return;
    }
    closedRef.current = true;
    SheetManager.hide(sheetId || SHEET_ID, { payload: result });
  };

  const _save = async () => {
    if (!payload?.channelId || unchanged || tooLong || isSaving) {
      return;
    }
    setIsSaving(true);
    setError(null);
    try {
      const { name } = await renameMattermostGroup(payload.channelId, trimmed);
      _close({ name: typeof name === 'string' ? name : trimmed });
    } catch (err: any) {
      const code = err?.response?.data?.code;
      setError(
        code === 'not_owner'
          ? intl.formatMessage({
              id: 'chats.rename_group_not_owner',
              defaultMessage: 'Only the person who started this group can rename it.',
            })
          : intl.formatMessage({
              id: 'chats.rename_group_failed',
              defaultMessage: 'Could not rename the group. Please try again.',
            }),
      );
    } finally {
      setIsSaving(false);
    }
  };

  return (
    <ActionSheet
      id={sheetId || SHEET_ID}
      gestureEnabled
      closeOnTouchBackdrop
      containerStyle={styles.sheetContainer}
    >
      <View style={styles.container}>
        <Text style={styles.title}>
          {intl.formatMessage({ id: 'chats.rename_group', defaultMessage: 'Rename group' })}
        </Text>
        <Text style={styles.description}>
          {intl.formatMessage({
            id: 'chats.rename_group_hint',
            defaultMessage:
              'Give this group a name everyone in it will see. ' +
              "Leave it empty to show the members' names instead.",
          })}
        </Text>

        <TextInput
          style={styles.input}
          placeholder={intl.formatMessage({ id: 'chats.group_name', defaultMessage: 'Group name' })}
          placeholderTextColor={EStyleSheet.value('$primaryDarkGray')}
          autoFocus
          value={value}
          onChangeText={setValue}
          returnKeyType="done"
          onSubmitEditing={_save}
        />

        {(tooLong || !!error) && (
          <Text style={styles.errorText} accessibilityRole="alert">
            {tooLong
              ? intl.formatMessage(
                  {
                    id: 'chats.rename_group_too_long',
                    defaultMessage: 'A group name can be up to {max} characters.',
                  },
                  { max: GROUP_NAME_MAX_LENGTH },
                )
              : error}
          </Text>
        )}

        <MainButton
          onPress={_save}
          isDisable={unchanged || tooLong || isSaving}
          isLoading={isSaving}
          text={intl.formatMessage({ id: 'chats.save', defaultMessage: 'Save' })}
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

export default ChatRenameGroupSheet;
