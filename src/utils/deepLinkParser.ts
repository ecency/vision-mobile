import get from 'lodash/get';
import { getQueryClient, getAccountFullQueryOptions } from '@ecency/sdk';
import postUrlParser, { parseWavesComposeUrl, parseWavesUrl } from './postUrlParser';
import parseAuthUrl, { AUTH_MODES } from './parseAuthUrl';
import ROUTES from '../constants/routeNames';
import { RouteName } from '../navigation/types';
import parsePurchaseUrl from './parsePurchaseUrl';

// name can be undefined on fall-through: useLinkProcessor only navigates
// natively when name, params and key are all set. Typing it as RouteName rather than string
// means a route that does not exist fails here, at the branch that produced it.
export interface DeepLinkRoute {
  name?: RouteName;
  params?: any;
  key?: string;
}

export const deepLinkParser = async (
  url: string | null | undefined,
): Promise<DeepLinkRoute | undefined> => {
  if (!url || url.indexOf('ShareMedia://') >= 0) return;

  let routeName: RouteName | undefined;
  let params: any;
  let profile;
  let keey: string | undefined;

  // waves permalinks always open the thread view; route them before the
  // generic author/permlink flow so reserved permlink names like 'wallet'
  // or 'followers' cannot be misread as profile filters
  const wavesLink = parseWavesUrl(url);
  if (wavesLink) {
    return {
      name: ROUTES.SCREENS.POST,
      params: { author: wavesLink.author, permlink: wavesLink.permlink },
      key: `${wavesLink.author}/${wavesLink.permlink}`,
    };
  }

  // ecency.com/waves?text=... opens the wave composer with that text, the
  // same link the web composer accepts; the Waves screen opens the sheet.
  // Without text it is the waves tab, whatever else the query carries.
  const composeLink = parseWavesComposeUrl(url);
  if (composeLink) {
    return composeLink.text
      ? { name: ROUTES.TABBAR.WAVES, params: { text: composeLink.text }, key: 'waves/compose' }
      : { name: ROUTES.TABBAR.WAVES, params: {}, key: 'waves' };
  }

  // profess url for post/content
  const postUrl = postUrlParser(url);
  console.log('postUrl : ', postUrl);

  const { author, permlink, feedType, tag } = postUrl || {};

  if (author) {
    if (
      !permlink ||
      permlink === 'wallet' ||
      permlink === 'points' ||
      permlink === 'comments' ||
      permlink === 'replies' ||
      permlink === 'posts'
    ) {
      let deepLinkFilter;
      if (permlink) {
        deepLinkFilter = permlink === 'points' ? 'wallet' : permlink;
      }

      const queryClient = getQueryClient();
      profile = await queryClient.fetchQuery(getAccountFullQueryOptions(author));
      routeName = ROUTES.SCREENS.PROFILE;
      params = {
        username: get(profile, 'name'),
        reputation: get(profile, 'reputation'),
        deepLinkFilter, // TODO: process this in profile screen
      };
      keey = get(profile, 'name');
    } else if (permlink === 'communities') {
      routeName = ROUTES.SCREENS.WEB_BROWSER;
      params = {
        url,
      };
      keey = 'WebBrowser';
    } else if (permlink === 'followers' || permlink === 'following') {
      routeName = ROUTES.SCREENS.FOLLOWS;
      // No count is available at parse time; FollowsScreen omits the header count when it
      // is not a number, and the list still loads from username + mode.
      params = {
        username: author,
        isFollowingPress: permlink === 'following',
      };
      keey = `${author}/${permlink}`;
    } else if (permlink) {
      params = { author, permlink };
      routeName = ROUTES.SCREENS.POST;
      keey = `${author}/${permlink}`;
    }
  }

  if (feedType === 'hot' || feedType === 'trending' || feedType === 'created') {
    if (!tag) {
      routeName = ROUTES.SCREENS.TAG_RESULT;
    } else if (/hive-[1-3]\d{4,6}$/.test(tag)) {
      routeName = ROUTES.SCREENS.COMMUNITY;
    } else {
      routeName = ROUTES.SCREENS.TAG_RESULT;
    }
    params = {
      tag,
      filter: feedType,
    };
    keey = `${feedType}/${tag || ''}`;
  }

  // Standalone web-standard routes -> native screens. postUrlParser surfaces a bare path
  // like /communities, /search, /bookmarks or /wallet as a feedType with no tag. params
  // must be a truthy object: useLinkProcessor only navigates natively when name && params
  // && key are all set, otherwise it falls back to the in-app web browser.
  // Gate on Ecency hosts so external links (e.g. https://example.com/wallet) still open in
  // the in-app browser instead of hijacking to a native Ecency screen.
  const isEcencyUrl =
    /^(ecency|esteem):\/\//i.test(url) ||
    /^https?:\/\/(www\.)?(ecency\.com|esteem\.app|estm\.to)(\/|$)/i.test(url);
  if (!routeName && isEcencyUrl && feedType && !tag) {
    switch (feedType) {
      case 'communities':
        routeName = ROUTES.SCREENS.COMMUNITIES;
        params = {};
        keey = 'communities';
        break;
      case 'search':
        routeName = ROUTES.SCREENS.SEARCH_RESULT;
        params = {};
        keey = 'search';
        break;
      case 'bookmarks':
        routeName = ROUTES.SCREENS.BOOKMARKS;
        params = {};
        keey = 'bookmarks';
        break;
      case 'wallet':
        routeName = ROUTES.TABBAR.WALLET;
        params = {};
        keey = 'wallet';
        break;
      case 'waves':
        routeName = ROUTES.TABBAR.WAVES;
        params = {};
        keey = 'waves';
        break;
      default:
        break;
    }
  }

  // process url for authentication
  if (!routeName) {
    const data = parseAuthUrl(url);
    if (data) {
      const { mode, referredUser, username, code, email } = data;

      if (mode === AUTH_MODES.SIGNUP) {
        routeName = ROUTES.SCREENS.REGISTER;
        params = {
          referredUser,
          username,
          email,
        };
        keey = `${mode}/${referredUser || ''}`;
      }

      if (mode === AUTH_MODES.AUTH) {
        routeName = ROUTES.SCREENS.LOGIN;
        params = {
          username,
          code,
        };
        keey = `${mode}/${username || ''}`;
      }
    }
  }

  // process url for purchasing
  if (!routeName) {
    const { type, username, email, referredUser, productId } = parsePurchaseUrl(url) || {};

    if (type && type === 'boost') {
      routeName = ROUTES.SCREENS.ACCOUNT_BOOST;
      params = {
        username,
      };
      keey = `${type}/${username || ''}`;
    }
    if (type && type === 'points') {
      routeName = ROUTES.SCREENS.BOOST;
      params = {
        username,
        productId,
      };
      keey = `${type}/${username || ''}`;
    }
    if (type && type === 'account') {
      routeName = ROUTES.SCREENS.REGISTER;
      (params = {
        username,
        email,
        referredUser,
        purchaseOnly: true,
      }),
        (keey = `${type}/${username || ''}`);
    }
  }

  return {
    name: routeName,
    params,
    key: keey,
  };
};
