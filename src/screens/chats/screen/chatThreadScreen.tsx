import React from 'react';
import { SafeAreaView } from 'react-native-safe-area-context';
import ChatThreadContainer from '../container/chatThreadContainer';
import { chatThreadStyles as styles } from '../styles/chatThread.styles';

interface ChatThreadParams {
  channelId: string;
  channelName?: string;
  channelDescription?: string;
  bootstrapResult?: any;
  userLookup?: Record<string, any>;
  lastViewedAt?: number;
  communityIdentifier?: string;
  channelType?: string;
  groupOwner?: boolean;
  groupName?: string;
  groupUsers?: any[];
}

const ChatThreadScreen = ({ route }: { route: { params: ChatThreadParams } }) => {
  const {
    channelId,
    channelName,
    channelDescription,
    communityIdentifier,
    bootstrapResult,
    userLookup,
    lastViewedAt,
    channelType,
    groupOwner,
    groupName,
    groupUsers,
  } = route.params;

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <ChatThreadContainer
        channelId={channelId}
        channelName={channelName}
        channelDescription={channelDescription}
        communityIdentifier={communityIdentifier}
        initialBootstrap={bootstrapResult}
        initialUserLookup={userLookup}
        initialLastViewedAt={lastViewedAt}
        channelType={channelType}
        groupOwner={groupOwner}
        groupName={groupName}
        groupUsers={groupUsers}
      />
    </SafeAreaView>
  );
};

export default ChatThreadScreen;
