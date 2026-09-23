interface PostUrlParseResult {
  feedType?: string;
  tag?: string;
  category?: string;
  author?: string;
  permlink?: string | null;
}

// Waves permalinks (https://ecency.com/waves/{author}/{permlink}) carry no @
// before the author segment, so none of the @-based post regexes below match
// them; parse them explicitly so wave links open natively as a thread.
// The web composer at ecency.com/waves accepts a `text` query and opens with it
// filled in; the games app and other Ecency surfaces build share links that
// way. Matches the waves tab URL itself (any query), so callers get `text`
// empty for a plain link. The bound only guards against absurd links: the
// composer shows its own character counter, so a long prefill is visible
// rather than silently cut.
export const WAVE_COMPOSE_TEXT_MAX = 2000;

export const parseWavesComposeUrl = (url: string): { text: string } | null => {
  if (!url) {
    return null;
  }

  let parsed: URL;
  try {
    parsed = new URL(url.replace(/^(ecency|esteem):\/\//i, 'https://ecency.com/'));
  } catch (e) {
    return null;
  }

  if (
    !/^(?:www\.)?(?:ecency\.com|esteem\.app|estm\.to)$/i.test(parsed.hostname) ||
    !/^\/waves\/?$/i.test(parsed.pathname)
  ) {
    return null;
  }

  // A waves link with no usable text is still the waves tab, never the browser.
  const text = (parsed.searchParams.get('text') || '').trim();
  return { text: text.slice(0, WAVE_COMPOSE_TEXT_MAX) };
};

export const parseWavesUrl = (url: string): PostUrlParseResult | null => {
  if (!url) {
    return null;
  }

  const normalized = url.replace(/^(ecency|esteem):\/\//i, 'https://ecency.com/');
  const match = normalized.match(
    /^https?:\/\/(?:www\.)?(?:ecency\.com|esteem\.app|estm\.to)\/waves\/@?([\w.-]+)\/([\w-]+)/i,
  );
  if (match) {
    return {
      // account names are lowercase-only on Hive; keep the permlink as written
      author: match[1].toLowerCase(),
      permlink: match[2],
    };
  }

  return null;
};

const parseCatAuthorPermlink = (u: string): PostUrlParseResult | null => {
  const postRegex = /^https?:\/\/(.*)\/(.*)\/(@[\w.\d-]+)\/(.*?)(?:\?|$)/i;
  const postMatch = u.match(postRegex);

  if (postMatch && postMatch.length === 5) {
    return {
      author: postMatch[3].replace('@', ''),
      permlink: postMatch[4],
    };
  }
  const authorRegex = /^https?:\/\/(.*)\/(.*)\/(@[\w.\d-]+)/i;
  const authorMatch = u.match(authorRegex);
  if (authorMatch && authorMatch.length === 4) {
    return {
      author: authorMatch[3].replace('@', ''),
      permlink: null,
    };
  }
  const r = /^https?:\/\/(.*)\/(@[\w.\d-]+)\/(.*?)(?:\?|$)/i;
  const match = u.match(r);

  if (match && match.length === 4) {
    return {
      author: match[2].replace('@', ''),
      permlink: match[3],
    };
  }
  return null;
};

const parseAuthorPermlink = (u: string): PostUrlParseResult | null => {
  const r = /^https?:\/\/(.*)\/(@[\w.\d-]+)\/(.*?)(?:\?|$)/i;
  const match = u.match(r);

  if (match && match.length === 4) {
    return {
      author: match[2].replace('@', ''),
      permlink: match[3],
    };
  }
  const authorRegex = /^https?:\/\/(.*)\/(@[\w.\d-]+)/i;
  const authorMatch = u.match(authorRegex);
  if (authorMatch && authorMatch.length === 3) {
    return {
      author: authorMatch[2].replace('@', ''),
      permlink: null,
    };
  }

  return null;
};

const postUrlParser = (url: string): PostUrlParseResult | null => {
  url = url && url.toLowerCase();
  if (url.startsWith('ecency://') || url.startsWith('esteem://')) {
    url = url
      .replace('ecency://', 'https://ecency.com/')
      .replace('esteem://', 'https://ecency.com/');
  }

  const wavesMatch = parseWavesUrl(url);
  if (wavesMatch) {
    return wavesMatch;
  }

  // eslint-disable-next-line no-useless-escape
  const feedMatch = url.match(/^https:\/\/([\w-\.]*)\/([\w-]*)\/?([\w-]*)\/?$/);

  if (feedMatch) {
    if (feedMatch[3]) {
      return {
        feedType: feedMatch[2],
        tag: feedMatch[3],
      };
    }
    return {
      feedType: feedMatch[2],
    };
  }

  // For non urls like @good-karma/esteem-london-presentation-e3105ba6637ed
  let match = url.match(/^[/]?(@[\w.\d-]+)\/(.*?)(?:\?|$)/);
  if (match && match.length === 3) {
    return {
      author: match[1].replace('@', ''),
      permlink: match[2],
    };
  }

  // For non urls with category like esteem/@good-karma/esteem-london-presentation-e3105ba6637ed
  match = url.match(/([\w.\d-]+)\/(@[\w.\d-]+)\/(.*?)(?:\?|$)/);
  if (match && match.length === 4) {
    // If the permlink contains a comment part, extract it
    if (match[3].indexOf('#@') > -1) {
      const commentPart = match[3].split('@')[1];
      const splits = commentPart.split('/');
      return {
        category: match[1],
        author: splits[0],
        permlink: splits[1],
      };
    }

    // strip hash from permlink if any
    const permlink = match[3].indexOf('#') > -1 ? match[3].split('#')[0] : match[3];

    return {
      category: match[1],
      author: match[2].replace('@', ''),
      permlink,
    };
  }

  const profile = url.match(/^https?:\/\/(.*)\/(@[\w.\d-]+)$/);
  if (profile) {
    if (profile && profile.length === 3) {
      return {
        author: profile[2].replace('@', ''),
        permlink: null,
      };
    }
  }

  if (
    [
      'https://estm.to',
      'https://ecency.com',
      'https://esteem.app',
      'https://hive.blog',
      'https://peakd.com',
      'https://leofinance.io',
    ].some((x) => url.startsWith(x))
  ) {
    return parseCatAuthorPermlink(url);
  }

  if (
    ['https://ecency.com', 'https://hive.blog', 'https://peakd.com', 'https://leofinance.io'].some(
      (x) => url.startsWith(x),
    )
  ) {
    return parseAuthorPermlink(url);
  }
  return null;
};

export default postUrlParser;
