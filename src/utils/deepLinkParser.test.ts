// Mock SDK query client
import { deepLinkParser } from './deepLinkParser';
import ROUTES from '../constants/routeNames';

jest.mock('@ecency/sdk', () => ({
  getQueryClient: jest.fn(() => ({
    fetchQuery: jest.fn((_opts) => Promise.resolve({ name: 'mockuser', reputation: 50 })),
  })),
  getAccountFullQueryOptions: jest.fn((author) => ({ queryKey: ['account', author] })),
}));

describe('deepLinkParser', () => {
  // Non-null variant for tests that assert on the parsed route.
  const parse = async (url: string) => {
    const parsed = await deepLinkParser(url);
    expect(parsed).toBeDefined();
    return parsed!;
  };
  it('returns undefined for null/empty url', async () => {
    expect(await deepLinkParser(null)).toBeUndefined();
    expect(await deepLinkParser('')).toBeUndefined();
  });

  it('returns undefined for ShareMedia urls', async () => {
    expect(await deepLinkParser('ShareMedia://test')).toBeUndefined();
  });

  describe('post URLs', () => {
    it('parses author/permlink post url', async () => {
      const result = await parse('https://ecency.com/hive/@alice/my-first-post');
      expect(result.name).toBe(ROUTES.SCREENS.POST);
      expect(result.params).toEqual({ author: 'alice', permlink: 'my-first-post' });
      expect(result.key).toBe('alice/my-first-post');
    });

    it('parses ecency:// deep link for posts', async () => {
      const result = await parse('ecency://@alice/my-post');
      expect(result.name).toBe(ROUTES.SCREENS.POST);
      expect(result.params.author).toBe('alice');
      expect(result.params.permlink).toBe('my-post');
    });
  });

  describe('waves URLs', () => {
    it('routes waves permalink (no @author) to post screen', async () => {
      const result = await parse('https://ecency.com/waves/jza/wave-202677t12348900z');
      expect(result.name).toBe(ROUTES.SCREENS.POST);
      expect(result.params).toEqual({ author: 'jza', permlink: 'wave-202677t12348900z' });
      expect(result.key).toBe('jza/wave-202677t12348900z');
    });

    it('routes waves permalink with @author to post screen', async () => {
      const result = await parse('https://ecency.com/waves/@jza/wave-202677t12348900z');
      expect(result.name).toBe(ROUTES.SCREENS.POST);
      expect(result.params).toEqual({ author: 'jza', permlink: 'wave-202677t12348900z' });
    });

    it('parses ecency:// deep link for waves', async () => {
      const result = await parse('ecency://waves/jza/wave-202677t12348900z');
      expect(result.name).toBe(ROUTES.SCREENS.POST);
      expect(result.params.author).toBe('jza');
      expect(result.params.permlink).toBe('wave-202677t12348900z');
    });

    it('routes bare waves url to waves tab without a key', async () => {
      // A supplied key makes the tab router look the route up by key; tab
      // routes have generated keys, so a fixed one drops the navigation.
      const result = await parse('https://ecency.com/waves');
      expect(result.name).toBe(ROUTES.TABBAR.WAVES);
      expect(result.params).toEqual({});
      expect(result.key).toBeUndefined();
    });

    it('routes waves compose link to the waves tab with the text', async () => {
      const encoded = [
        'Just%20hit%20stage%203%20%F0%9F%90%9D%20',
        'https%3A%2F%2Fecency.com%2Fhoneyback-share%2Fabc123defg',
      ].join('');
      const result = await parse(`https://ecency.com/waves?text=${encoded}`);
      expect(result.name).toBe(ROUTES.TABBAR.WAVES);
      expect(result.key).toBeUndefined();
      expect(result.params).toEqual({
        text: 'Just hit stage 3 🐝 https://ecency.com/honeyback-share/abc123defg',
      });
    });

    it('routes ecency:// waves compose link the same way', async () => {
      const result = await parse('ecency://waves?text=hello+waves');
      expect(result.name).toBe(ROUTES.TABBAR.WAVES);
      expect(result.params).toEqual({ text: 'hello waves' });
    });

    it('treats a blank text query as the bare waves tab', async () => {
      const result = await parse('https://ecency.com/waves?text=%20%20');
      expect(result.name).toBe(ROUTES.TABBAR.WAVES);
      expect(result.key).toBeUndefined();
      expect(result.params).toEqual({});
    });

    it('caps an oversized compose text without halving an emoji', async () => {
      const result = await parse(`https://ecency.com/waves?text=${'a'.repeat(2500)}`);
      expect(result.params.text).toHaveLength(2000);

      const bee = encodeURIComponent('🐝');
      const edge = await parse(`https://ecency.com/waves?text=${'a'.repeat(1999)}${bee}${bee}`);
      expect(edge.params.text).toBe(`${'a'.repeat(1999)}🐝`);
    });

    it('opens other schemes and ports on ecency.com in the browser, not the composer', async () => {
      // fall-through leaves name unset, which the link processor sends to the browser
      const ftp = await deepLinkParser('ftp://ecency.com/waves?text=hi');
      expect(ftp?.name).toBeUndefined();
      const port = await deepLinkParser('https://ecency.com:8443/waves?text=hi');
      expect(port?.name).toBeUndefined();
    });

    it('does not read text from a waves permalink query', async () => {
      const result = await parse('https://ecency.com/waves/@jza/wave-202677t12348900z?text=x');
      expect(result.name).toBe(ROUTES.SCREENS.POST);
    });

    it('routes waves permalink named like a profile filter to post screen', async () => {
      const result = await parse('https://ecency.com/waves/alice/wallet');
      expect(result.name).toBe(ROUTES.SCREENS.POST);
      expect(result.params).toEqual({ author: 'alice', permlink: 'wallet' });
    });
  });

  describe('profile URLs', () => {
    it('routes to profile when author only (no permlink)', async () => {
      const result = await parse('https://ecency.com/@alice');
      expect(result.name).toBe(ROUTES.SCREENS.PROFILE);
      expect(result.params.username).toBe('mockuser');
    });

    it('routes to profile with wallet filter', async () => {
      const result = await parse('https://ecency.com/hive/@alice/wallet');
      expect(result.name).toBe(ROUTES.SCREENS.PROFILE);
      expect(result.params.deepLinkFilter).toBe('wallet');
    });

    it('maps points filter to wallet', async () => {
      const result = await parse('https://ecency.com/hive/@alice/points');
      expect(result.name).toBe(ROUTES.SCREENS.PROFILE);
      expect(result.params.deepLinkFilter).toBe('wallet');
    });

    it('routes to profile with comments filter', async () => {
      const result = await parse('https://ecency.com/hive/@alice/comments');
      expect(result.name).toBe(ROUTES.SCREENS.PROFILE);
      expect(result.params.deepLinkFilter).toBe('comments');
    });

    it('routes to profile with replies filter', async () => {
      const result = await parse('https://ecency.com/hive/@alice/replies');
      expect(result.name).toBe(ROUTES.SCREENS.PROFILE);
      expect(result.params.deepLinkFilter).toBe('replies');
    });

    it('routes to profile with posts filter', async () => {
      const result = await parse('https://ecency.com/hive/@alice/posts');
      expect(result.name).toBe(ROUTES.SCREENS.PROFILE);
      expect(result.params.deepLinkFilter).toBe('posts');
    });

    it('routes communities permlink to web browser', async () => {
      const result = await parse('https://ecency.com/hive/@alice/communities');
      expect(result.name).toBe(ROUTES.SCREENS.WEB_BROWSER);
    });
  });

  describe('feed URLs', () => {
    it('routes trending feed', async () => {
      const result = await parse('https://ecency.com/trending/hive');
      expect(result.name).toBe(ROUTES.SCREENS.TAG_RESULT);
      expect(result.params.filter).toBe('trending');
      expect(result.params.tag).toBe('hive');
    });

    it('routes hot feed', async () => {
      const result = await parse('https://ecency.com/hot/photography');
      expect(result.name).toBe(ROUTES.SCREENS.TAG_RESULT);
      expect(result.params.filter).toBe('hot');
    });

    it('routes created feed', async () => {
      const result = await parse('https://ecency.com/created/art');
      expect(result.name).toBe(ROUTES.SCREENS.TAG_RESULT);
      expect(result.params.filter).toBe('created');
    });

    it('routes community tag to COMMUNITY screen', async () => {
      const result = await parse('https://ecency.com/trending/hive-174301');
      expect(result.name).toBe(ROUTES.SCREENS.COMMUNITY);
      expect(result.params.tag).toBe('hive-174301');
    });

    it('routes feed without tag', async () => {
      const result = await parse('https://ecency.com/trending');
      expect(result.name).toBe(ROUTES.SCREENS.TAG_RESULT);
      expect(result.params.filter).toBe('trending');
    });
  });

  describe('native web-standard routes', () => {
    it('routes /communities to the communities directory', async () => {
      const result = await parse('https://ecency.com/communities');
      expect(result.name).toBe(ROUTES.SCREENS.COMMUNITIES);
    });

    it('routes /search to the search screen', async () => {
      const result = await parse('https://ecency.com/search');
      expect(result.name).toBe(ROUTES.SCREENS.SEARCH_RESULT);
    });

    it('routes /bookmarks to the bookmarks screen', async () => {
      const result = await parse('https://ecency.com/bookmarks');
      expect(result.name).toBe(ROUTES.SCREENS.BOOKMARKS);
    });

    it('routes /wallet to the wallet tab', async () => {
      const result = await parse('https://ecency.com/wallet');
      expect(result.name).toBe(ROUTES.TABBAR.WALLET);
    });

    it('routes /@user/followers to the follows screen (followers)', async () => {
      const result = await parse('https://ecency.com/@alice/followers');
      expect(result.name).toBe(ROUTES.SCREENS.FOLLOWS);
      expect(result.params.username).toBe('alice');
      expect(result.params.isFollowingPress).toBe(false);
    });

    it('routes /@user/following to the follows screen (following)', async () => {
      const result = await parse('https://ecency.com/@alice/following');
      expect(result.name).toBe(ROUTES.SCREENS.FOLLOWS);
      expect(result.params.username).toBe('alice');
      expect(result.params.isFollowingPress).toBe(true);
    });

    it('does not native-route web paths on non-Ecency hosts', async () => {
      const result = await parse('https://example.com/wallet');
      expect(result.name).toBeUndefined();
    });
  });

  describe('auth URLs', () => {
    it('parses signup URL', async () => {
      const result = await parse('https://ecency.com/signup?referral=alice');
      expect(result.name).toBe(ROUTES.SCREENS.REGISTER);
      expect(result.params.referredUser).toBe('alice');
    });

    it('parses auth URL', async () => {
      const result = await parse('https://ecency.com/auth?username=alice&code=abc123');
      expect(result.name).toBe(ROUTES.SCREENS.LOGIN);
      expect(result.params.username).toBe('alice');
      expect(result.params.code).toBe('abc123');
    });
  });

  describe('purchase URLs', () => {
    it('parses boost purchase', async () => {
      const result = await parse('https://ecency.com/purchase?type=boost&username=alice');
      expect(result.name).toBe(ROUTES.SCREENS.ACCOUNT_BOOST);
      expect(result.params.username).toBe('alice');
    });

    it('parses points purchase', async () => {
      const result = await parse(
        'https://ecency.com/purchase?type=points&username=alice&product_id=999points',
      );
      expect(result.name).toBe(ROUTES.SCREENS.BOOST);
      expect(result.params.username).toBe('alice');
      expect(result.params.productId).toBe('999points');
    });

    it('parses account purchase', async () => {
      const result = await parse(
        'https://ecency.com/purchase?type=account&username=newuser&email=a@b.com&referral=alice',
      );
      expect(result.name).toBe(ROUTES.SCREENS.REGISTER);
      expect(result.params.purchaseOnly).toBe(true);
      expect(result.params.email).toBe('a@b.com');
    });
  });
});
