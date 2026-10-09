import { registerSheet, SheetDefinition, type Sheets } from 'react-native-actions-sheet';
import type { DigestType, Operation } from '@ecency/sdk';
import {
  ActionModal,
  PostTranslationModal,
  QuickProfileModal,
  QuickPostModal,
  CrossPostModal,
  AccountsBottomSheet,
  QRModal,
  ChatOptionsSheet,
  ChatChannelOptionsSheet,
  PostingAuthoritySheet,
  HiveAuthBroadcastSheet,
  EmojiPickerSheet,
  AuthUpgradeSheet,
  AiAssistModal,
  DictationModal,
  ComposeTranslateModal,
  TransferFavoritesSheet,
  ModNotesSheet,
  NewsletterDigestSheet,
  CommunityManageSheet,
  CommunityRoleEditSheet,
  SearchFiltersSheet,
} from '../components';
import type { ModNotesResult } from '../components/modNotesSheet/modNotesSheet';
import type { NewsletterDigestResult } from '../components/newsletterDigestSheet/newsletterDigestSheet';
import type { CommunityManageAction } from '../components/communityManageSheet/communityManageSheet';
import type { CommunityRoleEditResult } from '../components/communityRoleEditSheet/communityRoleEditSheet';
import type { SearchFilters } from '../components/searchFiltersSheet';
import { ShareIntentSheet } from '../components/shareIntentSheet';
import {
  ChatNewGroupSheet,
  ChatReactorsSheet,
  ChatRenameGroupSheet,
} from '../components/chatGroupSheets';
import SignConfirmSheet from '../screens/dappBrowser/components/signConfirmSheet';
import ReceiveQrSheet from '../components/receiveQrSheet/receiveQrSheet';
import WalletHistoryFiltersSheet from '../components/walletHistoryFiltersSheet/walletHistoryFiltersSheet';
import BalanceAnalyticsSheet from '../components/balanceAnalyticsSheet/balanceAnalyticsSheet';
import { TippingDialog } from '../components/tipping';
import { TTSSettingsSheet } from '../components/textToSpeech/ttsSettingsSheet';
import { ActionModalPayload } from '../components/actionModal/container/actionModalContainer';
import type { ChatNewGroupResult, ChatRenameGroupResult } from '../components/chatGroupSheets';

export enum SheetNames {
  POST_TRANSLATION = 'post_translation',
  QUICK_PROFILE = 'quick_profile',
  ACTION_MODAL = 'action_modal',
  QUICK_POST = 'quick_post',
  CROSS_POST = 'cross_post',
  ACCOUNTS_SHEET = 'accounts_sheet',
  QR_SCAN = 'qr_sheet',
  CHAT_OPTIONS = 'chat_options',
  CHAT_CHANNEL_OPTIONS = 'chat_channel_options',
  CHAT_REACTORS = 'chat_reactors',
  CHAT_NEW_GROUP = 'chat_new_group',
  CHAT_RENAME_GROUP = 'chat_rename_group',
  TIPPING_DIALOG = 'tipping_dialog',
  TTS_SETTINGS = 'tts_settings',
  POSTING_AUTHORITY_PROMPT = 'posting_authority_prompt',
  HIVE_AUTH_BROADCAST = 'hive_auth_broadcast',
  EMOJI_PICKER = 'emoji_picker',
  AUTH_UPGRADE = 'auth_upgrade',
  AI_ASSIST = 'ai_assist',
  DICTATION = 'dictation',
  COMPOSE_TRANSLATE = 'compose_translate',
  SHARE_INTENT = 'share_intent',
  SIGN_CONFIRM = 'sign_confirm',
  RECEIVE_QR = 'receive_qr',
  BALANCE_ANALYTICS = 'balance_analytics',
  TRANSFER_FAVORITES = 'transfer_favorites',
  MOD_NOTES = 'mod_notes',
  COMMUNITY_MANAGE = 'community_manage',
  COMMUNITY_ROLE_EDIT = 'community_role_edit',
  SEARCH_FILTERS = 'search_filters',
  WALLET_HISTORY_FILTERS = 'wallet_history_filters',
  NEWSLETTER_DIGEST = 'newsletter_digest',
}

registerSheet(SheetNames.POST_TRANSLATION, PostTranslationModal);
registerSheet(SheetNames.QUICK_PROFILE, QuickProfileModal);
registerSheet(SheetNames.ACTION_MODAL, ActionModal);
registerSheet(SheetNames.QUICK_POST, QuickPostModal);
registerSheet(SheetNames.CROSS_POST, CrossPostModal);
registerSheet(SheetNames.ACCOUNTS_SHEET, AccountsBottomSheet);
registerSheet(SheetNames.QR_SCAN, QRModal);
registerSheet(SheetNames.CHAT_OPTIONS, ChatOptionsSheet);
registerSheet(SheetNames.CHAT_CHANNEL_OPTIONS, ChatChannelOptionsSheet);
registerSheet(SheetNames.CHAT_REACTORS, ChatReactorsSheet);
registerSheet(SheetNames.CHAT_NEW_GROUP, ChatNewGroupSheet);
registerSheet(SheetNames.CHAT_RENAME_GROUP, ChatRenameGroupSheet);
registerSheet(SheetNames.TIPPING_DIALOG, TippingDialog);
registerSheet(SheetNames.TTS_SETTINGS, TTSSettingsSheet);
registerSheet(SheetNames.POSTING_AUTHORITY_PROMPT, PostingAuthoritySheet);
registerSheet(SheetNames.HIVE_AUTH_BROADCAST, HiveAuthBroadcastSheet);
registerSheet(SheetNames.EMOJI_PICKER, EmojiPickerSheet);
registerSheet(SheetNames.AUTH_UPGRADE, AuthUpgradeSheet);
registerSheet(SheetNames.AI_ASSIST, AiAssistModal);
registerSheet(SheetNames.DICTATION, DictationModal);
registerSheet(SheetNames.COMPOSE_TRANSLATE, ComposeTranslateModal);
registerSheet(SheetNames.SHARE_INTENT, ShareIntentSheet);
registerSheet(SheetNames.SIGN_CONFIRM, SignConfirmSheet);
registerSheet(SheetNames.RECEIVE_QR, ReceiveQrSheet);
registerSheet(SheetNames.BALANCE_ANALYTICS, BalanceAnalyticsSheet);
registerSheet(SheetNames.TRANSFER_FAVORITES, TransferFavoritesSheet);
registerSheet(SheetNames.MOD_NOTES, ModNotesSheet);
registerSheet(SheetNames.COMMUNITY_MANAGE, CommunityManageSheet);
registerSheet(SheetNames.COMMUNITY_ROLE_EDIT, CommunityRoleEditSheet);
registerSheet(SheetNames.SEARCH_FILTERS, SearchFiltersSheet);
registerSheet(SheetNames.WALLET_HISTORY_FILTERS, WalletHistoryFiltersSheet);
registerSheet(SheetNames.NEWSLETTER_DIGEST, NewsletterDigestSheet);

// We extend some of the types here to give us great intellisense
// across the app for all registered sheets.
// Keys must be string literals, not [SheetNames.X] computed keys: string enum
// member types are nominal, so with enum keys `keyof Sheets` only accepts the
// enum members and every SheetProps<'name'> literal fails TS2344. Literal keys
// accept both styles (enum members are assignable to their literal values).
declare module 'react-native-actions-sheet' {
  interface Sheets {
    post_translation: SheetDefinition<{
      payload: {
        content: any;
        // Pre-select the target language (from the inline banner / a chip).
        initialTargetCode?: string;
        initialSource?: string;
      };
    }>;
    quick_profile: SheetDefinition<{
      payload: {
        username: string;
      };
    }>;
    quick_post: SheetDefinition<{
      payload: {
        mode: 'comment' | 'wave';
        parentPost?: any;
        files?: any[];
      };
    }>;
    cross_post: SheetDefinition<{
      payload: {
        postContent: any;
      };
    }>;
    action_modal: SheetDefinition<{
      payload: ActionModalPayload;
      returnValue: string | undefined;
    }>;
    accounts_sheet: SheetDefinition;
    qr_sheet: SheetDefinition<{
      payload?: {
        onScan?: (value: string) => void;
      };
      returnValue: string | undefined;
    }>;
    chat_options: SheetDefinition<{
      payload: {
        post: any;
        channelId: string;
        onReply?: () => void;
        onReaction?: (emojiName: string) => void;
        onEdit?: () => void;
        onRemove?: () => void;
        onTranslate?: () => void;
        onPin?: () => void;
        onUnpin?: () => void;
        currentUserId?: string;
        isOwnMessage?: boolean;
        canModerate?: boolean;
      };
    }>;
    chat_channel_options: SheetDefinition<{
      payload: {
        title?: string;
        hasUnread?: boolean;
        isFavorite?: boolean;
        isMuted?: boolean;
        isDM?: boolean;
        onMarkRead?: () => void;
        onToggleFavorite?: () => void;
        onToggleMute?: () => void;
        onLeave?: () => void;
        onShowMembers?: () => void;
        onRename?: () => void;
      };
    }>;
    chat_reactors: SheetDefinition<{
      payload: {
        reactions: { emoji_name: string; user_id: string }[];
        userLookup: Record<string, any>;
        currentUserId?: string;
        initialEmoji?: string;
      };
    }>;
    chat_new_group: SheetDefinition<{
      payload: {
        currentUsername?: string;
      };
      returnValue: ChatNewGroupResult;
    }>;
    chat_rename_group: SheetDefinition<{
      payload: {
        channelId: string;
        currentName?: string;
      };
      returnValue: ChatRenameGroupResult;
    }>;
    tipping_dialog: SheetDefinition<{
      payload: {
        post: any;
        onSuccess?: (data: any) => void;
      };
    }>;
    tts_settings: SheetDefinition<{
      payload?: {
        onSettingsChanged?: () => void;
      };
    }>;
    posting_authority_prompt: SheetDefinition<{
      payload?: {
        onGranted?: () => void | Promise<void>;
        onSkipped?: () => void | Promise<void>;
        onError?: (error: Error) => void;
      };
    }>;
    hive_auth_broadcast: SheetDefinition<{
      payload: {
        operations: Operation[];
      };
      returnValue: { success: true; result: any } | { success: false; error: Error };
    }>;
    emoji_picker: SheetDefinition<{
      payload: {
        onEmojiSelected: (emojiName: string) => void;
      };
    }>;
    auth_upgrade: SheetDefinition<{
      payload: {
        requiredAuthority: 'posting' | 'active';
        operation: string;
        username: string;
      };
      returnValue: 'key' | 'hivesigner' | 'hiveauth' | false;
    }>;
    share_intent: SheetDefinition<{
      payload: {
        files: any[];
      };
      returnValue: 'blog' | 'wave' | undefined;
    }>;
    ai_assist: SheetDefinition<{
      payload: {
        text: string;
        onApply?: (output: string, action: string) => void;
        supportedActions?: string[];
        // Optional "leave the sheet and generate an image" entry. Only callers that can host
        // the generator screen pass it, and the sheet renders the card only when they do, so
        // surfaces without a place to put the result are unaffected.
        onGenerateImage?: () => void | Promise<void>;
      };
    }>;
    dictation: SheetDefinition<{
      payload: {
        onInsert: (text: string) => void;
      };
    }>;
    compose_translate: SheetDefinition<{
      payload: {
        body: string;
        title?: string;
        onApply: (appendix: string, titleMarker?: string) => void;
      };
    }>;
    sign_confirm: SheetDefinition<{
      payload: {
        type: string;
        domain: string;
        username: string;
        method?: string;
        [key: string]: any;
      };
      returnValue: boolean;
    }>;
    receive_qr: SheetDefinition<{
      payload: {
        username: string;
      };
    }>;
    balance_analytics: SheetDefinition<{
      payload: {
        coinType: string;
        username: string;
      };
    }>;
    transfer_favorites: SheetDefinition<{
      payload?: {
        limit?: number;
      };
      returnValue: string | undefined;
    }>;
    mod_notes: SheetDefinition<{
      payload?: {
        title?: string;
        description?: string;
        placeholder?: string;
        maxLength?: number;
        confirmLabel?: string;
      };
      // `{ notes }` on confirm, `{ cancelled: true }` on explicit cancel.
      // Dismissing by backdrop, swipe or back button resolves the payload
      // object instead, because the library publishes
      // `data || payloadRef.current` on close. Gate on a string `notes`, never
      // on truthiness.
      returnValue: ModNotesResult | undefined;
    }>;
    community_manage: SheetDefinition<{
      // `{ action }` on selection. Dismissing by backdrop, swipe or back
      // resolves the payload object instead, because the library publishes
      // `data || payloadRef.current` on close, so match on a known action
      // rather than on truthiness.
      returnValue: { action?: CommunityManageAction } | undefined;
    }>;
    community_role_edit: SheetDefinition<{
      payload: {
        // Omitted when editableAccount is set: the moderator types the name.
        account?: string;
        currentRole?: string;
        assignableRoles: string[];
        editableAccount?: boolean;
      };
      // `{ role }` on selection, `{ cancelled: true }` on cancel. A backdrop,
      // swipe or back dismissal resolves the payload object instead, because
      // the library publishes `data || payloadRef.current` on close. Gate on a
      // string `role`, never on truthiness.
      returnValue: CommunityRoleEditResult | undefined;
    }>;
    search_filters: SheetDefinition<{
      payload: {
        // Current filters, so reopening shows what is applied rather than a
        // blank form.
        filters?: SearchFilters;
        // The free text from the search bar. The length cap the API enforces
        // covers the whole q string, so it has to be measured together with
        // the filter tokens rather than after them.
        searchValue?: string;
      };
      // `{ filters }` on apply, `{ cancelled: true }` on cancel. A backdrop,
      // swipe or back dismissal resolves the payload object instead, because
      // the library publishes `data || payloadRef.current` on close, so gate on
      // `filters` being an object rather than on truthiness.
      returnValue: { filters?: SearchFilters; cancelled?: boolean } | undefined;
    }>;
    wallet_history_filters: SheetDefinition<{
      payload: {
        // Which token's history is being filtered. The options are derived from it, so
        // only operations that tab can actually render are offered.
        symbol: string;
        // What is applied now, so reopening shows the current selection. Empty or absent
        // means the tab's full set.
        selected?: string[];
      };
      // `{ operations }` on apply, `{ cancelled: true }` on cancel. Same dismissal caveat
      // as search_filters: a backdrop, swipe or back resolves the payload object, so gate
      // on `operations` being an array rather than on truthiness.
      returnValue: { operations?: string[]; cancelled?: boolean } | undefined;
    }>;
    newsletter_digest: SheetDefinition<{
      payload: {
        // Which list: 'own' (target = own username), 'creator' (target = author),
        // 'community' (target = hive-xxxxx), 'site' (target = 'ecency').
        type: DigestType;
        target: string;
        // Display name for community lists (the community title).
        targetLabel?: string;
        // First-publish flavor: prompt copy instead of the generic title/body.
        firstPublish?: boolean;
      };
      // `{ done: true }` after a completed action, `{ cancelled: true }` on cancel.
      // A backdrop/swipe/back dismissal resolves the payload object; gate on the
      // field, never on truthiness.
      returnValue: NewsletterDigestResult | undefined;
    }>;
  }
}

// Compile-time completeness check: a SheetNames member missing from the Sheets
// augmentation above makes MissingSheetDefinitions non-never, so this
// assignment fails and the error names the missing sheet.
type MissingSheetDefinitions = Exclude<`${SheetNames}`, keyof Sheets>;
export const everySheetHasDefinition: [MissingSheetDefinitions] extends [never]
  ? true
  : MissingSheetDefinitions = true;

export {};
