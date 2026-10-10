const QUERIES = {
  FEED: {
    GET: 'QUERY_GET_FEED',
    GET_PROMOTED: 'QUERY_GET_FEED_PROMOTED',
  },
  DRAFTS: {
    GET: 'QUERY_GET_DRAFTS',
  },
  SCHEDULES: {
    GET: 'QUERY_GET_SCHEDULES',
  },
  NOTIFICATIONS: {
    GET: 'notifications', // Changed to match SDK query key pattern
  },
  ANNOUNCEMENTS: {
    GET: 'QERUY_GET_ANNOUNCEMENTS',
  },
  SNIPPETS: {
    GET: 'QUERY_GET_SNIPPETS',
  },
  MEDIA: {
    GET: 'QUERY_GET_UPLOADS',
    GET_VIDEOS: 'QUERY_GET_VIDEO_UPLOADS',
  },
  WALLET: {
    // Portfolio, recurring transfers, and pending requests now use SDK query keys directly.
    // Only engine activities remain mobile-specific.
    GET_ACTIVITIES: 'QUERY_GET_ACTIVITIES',
  },
  POST: {
    GET: 'QUERY_GET_POST',
    GET_POLL: 'QUERY_GET_POLL',
    GET_DISCUSSION: 'QUERY_GET_DISCUSSION',
    GET_BOT_AUTHERS: 'BOT_AUTHERS',
    GET_TIPS: 'QUERY_GET_POST_TIPS',
    SIGN_POLL_VOTE: 'SIGN_POLL_VOTE',
    GET_REBLOGS: 'GET_REBLOGS',
    REBLOG_POST: 'REBLOG_POST',
    CROSS_POST: 'CROSS_POST',
  },
  PLAUSIBLE: {
    GET_POST_STATS: 'QUERY_PLAUSIBLE_GET_POST_STATS',
    GET_POST_STATS_BY_COUNTRY: 'QUERY_PLAUSIBLE_GET_POST_STATS_BY_COUNTRY',
    GET_POST_STATS_BY_DEVICE: 'QUERY_PLAUSIBLE_GET_POST_STATS_BY_DEVICE',
  },
  LEADERBOARD: {
    GET: 'QUERY_GET_LEADERBOARD',
  },
  SETTINGS: {
    GET_SERVERS: 'QUERY_GET_SERVERS_LIST',
  },
  REDEEM: {
    GET_BOOST_PLUS_PRICES: 'REDEEM_GET_BOOST_PLUS_PRICES',
  },
  GAMES: {
    // suffix for a spin status request that must not share a cache entry
    STATUS_UNCACHED: 'QUERY_GAME_STATUS_UNCACHED',
  },
  PROPOSALS: {
    GET_VOTES: 'QUERY_PROPOSAL_GET_VOTES',
    GET_ACTIVE_PROPOSAL: 'QUERY_PROPOSAL_GET_ACTIVE_PROPOSAL',
  },
};

// A spin status request that must not share a cache entry: the SDK status key plus a
// number of its own per request.
export const gameStatusUncachedKey = (statusKey: readonly unknown[], requestSeq: number) => [
  ...statusKey,
  QUERIES.GAMES.STATUS_UNCACHED,
  requestSeq,
];

export default QUERIES;
