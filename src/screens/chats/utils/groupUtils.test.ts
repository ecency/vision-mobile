import {
  findMissingUserIds,
  getGroupMembersTitle,
  getGroupTitle,
  getRenamedGroupName,
  getUsernameForId,
  groupNameLength,
  groupReactions,
  isConversationChannel,
  isGroupChannel,
} from './groupUtils';

jest.mock('../../../config/chatApi', () => ({
  __esModule: true,
  default: { get: jest.fn(), post: jest.fn(), put: jest.fn(), delete: jest.fn() },
  setChatApiToken: jest.fn(),
}));

const user = (username: string) => ({ id: `id-${username}`, username });

describe('group channels', () => {
  it('tells groups and direct messages apart from channels', () => {
    expect(isGroupChannel({ type: 'G' })).toBe(true);
    expect(isGroupChannel({ type: 'D' })).toBe(false);
    expect(isConversationChannel({ type: 'D' })).toBe(true);
    expect(isConversationChannel({ type: 'G' })).toBe(true);
    expect(isConversationChannel({ type: 'O' })).toBe(false);
    expect(isConversationChannel(undefined)).toBe(false);
  });

  it('names a group by its given name, else by its other members', () => {
    const members = [user('alice'), user('bob'), user('carol'), user('dave'), user('erin')];
    expect(getGroupTitle({ group_name: ' Book club ', groupUsers: members })).toBe('Book club');
    expect(getGroupTitle({ group_name: '', groupUsers: members.slice(0, 2) })).toBe('alice, bob');
    expect(getGroupTitle({ groupUsers: members })).toBe('alice, bob, carol +2');
    expect(getGroupTitle({ display_name: 'a, b, me' })).toBe('a, b, me');
    expect(getGroupMembersTitle([], 'fallback')).toBe('fallback');
  });

  it('counts a name as the server does, so an emoji is one character', () => {
    expect(groupNameLength('  🙂🙂 ')).toBe(2);
    expect(groupNameLength('abc')).toBe(3);
  });

  it('reads the new name from a rename notice', () => {
    expect(getRenamedGroupName({ props: { new_header: ' Book club ' } })).toBe('Book club');
    expect(getRenamedGroupName({ props: { new_header: '' } })).toBe('');
    expect(getRenamedGroupName({ props: {} })).toBe('');
    expect(getRenamedGroupName(undefined)).toBe('');
  });
});

describe('reactions', () => {
  it('groups by emoji in first-use order, once per person, marking the viewer', () => {
    const grouped = groupReactions(
      [
        { emoji_name: 'heart', user_id: 'u1' },
        { emoji_name: '+1', user_id: 'me' },
        { emoji_name: 'heart', user_id: 'u2' },
        { emoji_name: 'heart', user_id: 'u1' },
      ],
      'me',
    );
    expect(grouped).toEqual([
      { emojiName: 'heart', userIds: ['u1', 'u2'], reacted: false },
      { emojiName: '+1', userIds: ['me'], reacted: true },
    ]);
    expect(groupReactions(undefined)).toEqual([]);
  });

  it('lists reactors the lookup does not know yet, once and sorted', () => {
    expect(findMissingUserIds(['z9', 'known', 'a1', 'z9', ''], { known: user('known') })).toEqual([
      'a1',
      'z9',
    ]);
  });

  it('names a known reactor and leaves an unknown one undefined', () => {
    const lookup = { 'id-alice': user('alice') };
    expect(getUsernameForId('id-alice', lookup)).toBe('alice');
    expect(getUsernameForId('ghost', lookup)).toBeUndefined();
  });
});
