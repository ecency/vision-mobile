import React from 'react';
import { View, Text } from 'react-native';
import { UnreadMarker } from './UnreadMarker';
import { chatThreadStyles as styles } from '../styles/chatThread.styles';

interface GroupRenameNoticeProps {
  text: string;
  showUnreadMarker: boolean;
}

/**
 * "alice renamed the group to ...". Plain text on purpose: a group name is
 * typed by a member, so it is never run through markdown, links or mentions.
 */
export const GroupRenameNotice: React.FC<GroupRenameNoticeProps> = React.memo(
  ({ text, showUnreadMarker }) => (
    <View>
      <UnreadMarker show={showUnreadMarker} />
      <View style={styles.systemMessageContainer}>
        <View style={styles.systemMessagePill}>
          <Text style={styles.systemBody}>{text}</Text>
        </View>
      </View>
    </View>
  ),
);

GroupRenameNotice.displayName = 'GroupRenameNotice';
