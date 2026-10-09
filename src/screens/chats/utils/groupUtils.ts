import { getHiveUsernameFromMattermostUser } from '../../../providers/chat/mattermost';

/** Mattermost allows 3 to 8 members in a group, the creator included. */
export const GROUP_MIN_OTHERS = 2;
export const GROUP_MAX_OTHERS = 7;

/** Characters (code points) a group name may have, as the server counts them. */
export const GROUP_NAME_MAX_LENGTH = 64;

/** Emitted with { channelId, name } when the viewer renames a group. */
export const GROUP_RENAMED_EVENT = 'chat_group_renamed';

export const isGroupChannel = (channel: any): boolean => channel?.type === 'G';

/** A direct message or a group: private conversations, listed together. */
export const isConversationChannel = (channel: any): boolean =>
  channel?.type === 'D' || channel?.type === 'G';

/** The length of a group name as the server counts it, so an emoji counts as one. */
export const groupNameLength = (name: string): number => Array.from(name.trim()).length;

const userName = (user: any): string =>
  getHiveUsernameFromMattermostUser(user) || user?.username || '';

/**
 * "alice, bob, carol +2". Mattermost's own display name for a group lists every
 * member's username, the viewer included, cut at 64 characters.
 */
export const getGroupMembersTitle = (users: any[] | undefined, fallback: string, maxNames = 3) => {
  const names = (users || []).map(userName).filter(Boolean);
  if (!names.length) {
    return fallback;
  }
  if (names.length <= maxNames) {
    return names.join(', ');
  }
  return `${names.slice(0, maxNames).join(', ')} +${names.length - maxNames}`;
};

/** The name to show for a group: the name its owner gave it, else its members. */
export const getGroupTitle = (channel: any, fallback?: string): string =>
  (typeof channel?.group_name === 'string' && channel.group_name.trim()) ||
  getGroupMembersTitle(
    channel?.groupUsers,
    fallback || channel?.display_name || channel?.name || '',
  );

export interface ReactionLike {
  emoji_name: string;
  user_id: string;
}

export interface GroupedReaction {
  emojiName: string;
  userIds: string[];
  reacted: boolean;
}

/** One entry per emoji, in the order each emoji was first used. */
export const groupReactions = (
  reactions: ReactionLike[] | undefined,
  currentUserId?: string,
): GroupedReaction[] => {
  const byEmoji = new Map<string, GroupedReaction>();
  (reactions || []).forEach((reaction) => {
    if (!reaction?.emoji_name) {
      return;
    }
    const entry = byEmoji.get(reaction.emoji_name) || {
      emojiName: reaction.emoji_name,
      userIds: [],
      reacted: false,
    };
    if (reaction.user_id && !entry.userIds.includes(reaction.user_id)) {
      entry.userIds.push(reaction.user_id);
    }
    if (currentUserId && reaction.user_id === currentUserId) {
      entry.reacted = true;
    }
    byEmoji.set(reaction.emoji_name, entry);
  });
  return Array.from(byEmoji.values());
};

/** Reactor ids that are not in the users lookup yet, for one batched request. */
export const findMissingUserIds = (userIds: string[], userLookup: Record<string, any>): string[] =>
  Array.from(new Set(userIds.filter((id) => id && !userLookup[id]))).sort();

/** The Hive username for a user id, or undefined while it is unknown. */
export const getUsernameForId = (
  userId: string,
  userLookup: Record<string, any>,
): string | undefined => {
  const user = userLookup[userId];
  return user ? userName(user) || undefined : undefined;
};

/**
 * The new name from a group rename notice. Mattermost announces a header change
 * with a `system_header_change` post; in a group the header is its name.
 */
export const getRenamedGroupName = (post: any): string =>
  typeof post?.props?.new_header === 'string' ? post.props.new_header.trim() : '';
