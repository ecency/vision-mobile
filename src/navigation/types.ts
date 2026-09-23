import ROUTES from '../constants/routeNames';

type ValueOf<T> = T[keyof T];

/** Every navigable route name in the app, derived from the ROUTES constants. */
export type RouteName =
  | ValueOf<typeof ROUTES.SCREENS>
  | ValueOf<typeof ROUTES.MODALS>
  | ValueOf<typeof ROUTES.DRAWER>
  | ValueOf<typeof ROUTES.TABBAR>
  | ValueOf<typeof ROUTES.STACK>;

/**
 * True when a route is navigable without params.
 *
 * PINCODE is answered without probing its params: AppParamList[PINCODE] is PinCodeParams, which
 * embeds ForwardedNavigation, so evaluating `undefined extends AppParamList[PINCODE]` makes both
 * types circular. It is navigable without params, so the short-circuit is also the right answer.
 * Referencing AppParamList[K] in a property position stays lazy and is fine.
 */
export type ParamsOptional<K extends RouteName> = K extends typeof ROUTES.SCREENS.PINCODE
  ? true
  : undefined extends AppParamList[K]
  ? true
  : false;

/**
 * Argument list for a helper that takes a route and its params as two arguments.
 *
 * params is required exactly when the destination requires them, so a paramless call to
 * WEB_BROWSER and friends is a type error. A plain `params?: AppParamList[K]` would not do
 * this: the `?` makes the argument optional for every route regardless of its contract.
 *
 * The outer `K extends RouteName` is what makes this distribute. Without it, a caller whose
 * route is typed as the whole RouteName union collapses ParamsOptional<K> to boolean, which
 * fails `extends true` and yields [RouteName, AppParamList[RouteName]] where the params union
 * already includes undefined, so the required-params routes stop being checked. Distributing
 * produces one tuple per route instead, and a union-typed route matches none of them.
 */
export type NavigateArgs<K extends RouteName> = K extends RouteName
  ? ParamsOptional<K> extends true
    ? [route: K, params?: AppParamList[K]]
    : [route: K, params: AppParamList[K]]
  : never;

/**
 * A forwarded navigation target: navigateParams is typed by the destination
 * route, so routing through the PIN gate keeps the destination's contract.
 *
 * navigateParams is required exactly where the destination requires params
 * (WEB_BROWSER, VOTERS, CHAT_THREAD, ASSET_DETAILS, PROFILE_EDIT), so a
 * paramless forward to one of those is a type error rather than a runtime
 * screen with nothing to render.
 */
export type ForwardedNavigation = {
  [K in RouteName]: ParamsOptional<K> extends true
    ? { navigateTo: K; navigateParams?: AppParamList[K]; navigateKey?: string }
    : { navigateTo: K; navigateParams: AppParamList[K]; navigateKey?: string };
}[RouteName];

/** Params the PinCode screen forwards to its container as pinCodeParams. */
export type PinCodeParams = {
  hideCloseButton?: boolean;
  isReset?: boolean;
  isOldPinVerified?: boolean;
  oldPinCode?: string | null;
  callback?: (newPinCode: string, oldPinCode: string | null) => void;
} & (
  | ForwardedNavigation
  | { navigateTo?: undefined; navigateParams?: undefined; navigateKey?: undefined }
);

/**
 * Contracts derived from what each screen actually reads from its route
 * params (#3455). Object payloads that are still untyped app-wide (posts,
 * drafts, portfolio assets, chat bootstrap data) stay any here; the field
 * names are the contract. `| undefined` marks routes navigable without
 * params, per React Navigation's convention.
 */
export type AppParamList = {
  [ROUTES.SCREENS.BOOKMARKS]: { showFavorites?: boolean; showTags?: boolean } | undefined;
  [ROUTES.SCREENS.BOOST]: { username?: string; productId?: string } | undefined;
  [ROUTES.SCREENS.DRAFTS]: { showSchedules?: boolean } | undefined;
  [ROUTES.SCREENS.EDITOR]:
    | {
        hasSharedIntent?: boolean;
        draftId?: string;
        templateDraft?: any;
        community?: string[];
        post?: any;
        isReply?: boolean;
        replyMediaUrls?: string[];
        isEdit?: boolean;
        files?: any[];
        tags?: string[];
      }
    | undefined;
  [ROUTES.SCREENS.FOLLOWS]:
    | { username?: string; isFollowingPress?: boolean; count?: number }
    | undefined;
  [ROUTES.SCREENS.SPIN_GAME]: { productId?: string; username?: string } | undefined;
  [ROUTES.SCREENS.PERKS]: undefined;
  [ROUTES.SCREENS.FEED]: undefined;
  [ROUTES.SCREENS.LOGIN]: { username?: string; code?: string } | undefined;
  [ROUTES.SCREENS.PINCODE]: PinCodeParams | undefined;
  [ROUTES.SCREENS.POST]:
    | { content?: any; author?: string; permlink?: string; isNewPost?: boolean }
    | undefined;
  [ROUTES.SCREENS.PROFILE_EDIT]: { fetchUser: () => void };
  [ROUTES.SCREENS.PROFILE]:
    | {
        username?: string;
        reputation?: string | number;
        deepLinkFilter?: string;
        state?: number;
        fetchData?: () => void;
      }
    | undefined;
  [ROUTES.SCREENS.REBLOGS]: { author?: string; permlink?: string } | undefined;
  [ROUTES.SCREENS.REDEEM]:
    | { redeemType?: 'promote' | 'boost_plus' | 'rc_topup'; permlink?: string }
    | undefined;
  [ROUTES.SCREENS.REGISTER]:
    | { username?: string; email?: string; referredUser?: string; purchaseOnly?: boolean }
    | undefined;
  [ROUTES.SCREENS.SEARCH_RESULT]: undefined;
  [ROUTES.SCREENS.TAG_RESULT]: { tag?: string; filter?: string } | undefined;
  [ROUTES.SCREENS.SETTINGS]: undefined;
  [ROUTES.SCREENS.TRANSFER]:
    | {
        transferType?: string;
        fundType?: string;
        balance?: string | number;
        tokenAddress?: string;
        referredUsername?: string;
        initialAmount?: string | number;
        initialMemo?: string;
        assetLayer?: string;
        tokenLayer?: string;
      }
    | undefined;
  [ROUTES.SCREENS.VOTERS]: { content: any };
  [ROUTES.SCREENS.ACCOUNT_BOOST]: { username?: string } | undefined;
  [ROUTES.SCREENS.COMMUNITY]: { tag?: string; filter?: string } | undefined;
  [ROUTES.SCREENS.COMMUNITIES]: undefined;
  [ROUTES.SCREENS.COMMUNITY_MEMBERS]: { communityId?: string; communityTitle?: string } | undefined;
  [ROUTES.SCREENS.COMMUNITY_SETTINGS]: { communityId?: string } | undefined;
  [ROUTES.SCREENS.COMMUNITY_ACTIVITIES]:
    | { communityId?: string; communityTitle?: string }
    | undefined;
  [ROUTES.SCREENS.WEB_BROWSER]: { url: string };
  [ROUTES.SCREENS.REFER]: undefined;
  [ROUTES.SCREENS.ASSET_DETAILS]: { asset: any };
  [ROUTES.SCREENS.EDIT_HISTORY]: { author?: string; permlink?: string } | undefined;
  [ROUTES.SCREENS.WELCOME]: undefined;
  [ROUTES.SCREENS.CHAT_THREAD]: {
    channelId: string;
    channelName?: string;
    channelDescription?: string;
    communityIdentifier?: string;
    bootstrapResult?: any;
    userLookup?: any;
    lastViewedAt?: number;
    channelType?: string;
  };
  [ROUTES.SCREENS.ACCOUNT_LIST]: { users?: any[]; title?: string } | undefined;
  [ROUTES.SCREENS.BACKUP_KEYS]: undefined;
  [ROUTES.SCREENS.TRADE]: { transferType?: string; fundType?: string } | undefined;
  [ROUTES.SCREENS.AI_IMAGE_GENERATOR]:
    | { onInsert?: (url: string) => void; suggestedPrompt?: string }
    | undefined;
  [ROUTES.SCREENS.DAPP_BROWSER]: { url?: string } | undefined;
  [ROUTES.SCREENS.EMAIL_DIGESTS]: undefined;
  [ROUTES.MODALS.ASSETS_SELECT]: undefined;
  [ROUTES.MODALS.ACCOUNT_LIST]: { users?: any[]; title?: string } | undefined;
  [ROUTES.MODALS.POLL_WIZARD]: { draftId?: string } | undefined;
  [ROUTES.MODALS.BOT_COMMENTS]: { comments?: any[] } | undefined;
  [ROUTES.MODALS.HIVE_SIGNER]:
    | { hiveuri?: string; opsArray?: any[]; onClose?: () => void; onSuccess?: () => void }
    | undefined;
  [ROUTES.DRAWER.MAIN]: undefined;
  [ROUTES.TABBAR.FEED]: { iconName?: string } | undefined;
  [ROUTES.TABBAR.NOTIFICATION]: { iconName?: string } | undefined;
  [ROUTES.TABBAR.WALLET]: { iconName?: string } | undefined;
  [ROUTES.TABBAR.WAVES]: { iconName?: string; text?: string } | undefined;
  [ROUTES.TABBAR.CHATS]: { iconName?: string } | undefined;
  [ROUTES.STACK.MAIN]: undefined;
};

type AssertNever<T extends never> = T;
/** Compile error here means a ROUTES constant has no AppParamList entry. */
export type _MissingRouteContracts = AssertNever<Exclude<RouteName, keyof AppParamList>>;

declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace ReactNavigation {
    interface RootParamList extends AppParamList {}
  }
}
