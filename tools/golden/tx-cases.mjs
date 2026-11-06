// The transactions of GoldenVectors.kt, shared by generate.mjs (the SDK and hived) and generate-wax.mjs (Hive's wax),
// so both pin the same operations. Plain data: no package, no network.

export const HEADER = { ref_block_num: 12345, ref_block_prefix: 3367463123, expiration: '2026-10-06T12:00:00' }
const LONG_BODY = 'Native spike golden vector. '.repeat(12) // > 127 bytes, multi-byte varint length
const UNICODE_BODY = 'Hive 🐝 Привет مرحبا 你好 — ünïcödé'

export const cases = [
  ['vote_upvote', [['vote', { voter: 'spike-voter', author: 'ecency', permlink: 'native-golden-vote', weight: 10000 }]]],
  ['vote_downvote', [['vote', { voter: 'spike-voter', author: 'ecency', permlink: 'native-golden-vote', weight: -5000 }]]],
  ['comment_root_unicode', [['comment', {
    parent_author: '', parent_permlink: 'hive-125125', author: 'spike-author', permlink: 'native-golden-post',
    title: 'Golden 🐝 post', body: UNICODE_BODY, json_metadata: '{"app":"ecency/4.0.0","tags":["hive-125125","ecency"]}',
  }]]],
  ['comment_reply_long', [['comment', {
    parent_author: 'ecency', parent_permlink: 'native-golden-post', author: 'spike-author',
    permlink: 're-ecency-native-golden-post', title: '', body: LONG_BODY, json_metadata: '{}',
  }]]],
  ['comment_options_beneficiaries', [['comment_options', {
    author: 'spike-author', permlink: 'native-golden-post', max_accepted_payout: '1000000.000 HBD', percent_hbd: 10000,
    allow_votes: true, allow_curation_rewards: false,
    extensions: [[0, { beneficiaries: [{ account: 'ecency', weight: 500 }, { account: 'spike-bene', weight: 1000 }] }]],
  }]]],
  ['custom_json_follow', [['custom_json', {
    required_auths: [], required_posting_auths: ['spike-voter'], id: 'follow',
    json: '["follow",{"follower":"spike-voter","following":"ecency","what":["blog"]}]',
  }]]],
  ['custom_json_ignore', [['custom_json', {
    required_auths: [], required_posting_auths: ['spike-voter'], id: 'follow',
    json: '["follow",{"follower":"spike-voter","following":"ecency","what":["ignore"]}]',
  }]]],
  ['custom_json_unfollow', [['custom_json', {
    required_auths: [], required_posting_auths: ['spike-voter'], id: 'follow',
    json: '["follow",{"follower":"spike-voter","following":"ecency","what":[]}]',
  }]]],
  ['account_update2_profile', [['account_update2', {
    account: 'spike-voter', json_metadata: '', extensions: [],
    posting_json_metadata: JSON.stringify({ profile: { name: 'Spike 🐝 Voter', about: 'Привет', website: 'https://ecency.com', profile_image: '', version: 2 } }),
  }]]],
  ['account_update2_long', [['account_update2', {
    account: 'spike-voter', json_metadata: '{"beneficiaries":[]}', extensions: [],
    posting_json_metadata: JSON.stringify({ profile: { about: LONG_BODY, version: 2 }, other: { keep: true } }),
  }]]],
  ['delete_comment', [['delete_comment', { author: 'spike-author', permlink: 're-ecency-native-golden-post' }]]],
  ['custom_json_reblog_undo', [['custom_json', {
    required_auths: [], required_posting_auths: ['spike-voter'], id: 'follow',
    json: '["reblog",{"account":"spike-voter","author":"ecency","permlink":"native-golden-post","delete":"delete"}]',
  }]]],
  ['account_update2_pin', [['account_update2', {
    account: 'spike-voter', json_metadata: '', extensions: [],
    posting_json_metadata: '{"profile":{"name":"Spike","pinned":"native-golden-post","version":2}}',
  }]]],
  ['transfer_hive_memo', [['transfer', { from: 'spike-voter', to: 'ecency', amount: '0.001 HIVE', memo: 'golden vector' }]]],
  ['transfer_hbd_large', [['transfer', { from: 'spike-voter', to: 'ecency', amount: '123456.789 HBD', memo: '' }]]],
  ['post_with_options', [
    ['comment', { parent_author: '', parent_permlink: 'ecency', author: 'spike-author', permlink: 'native-golden-multi',
      title: 'Multi op', body: 'Body', json_metadata: '{}' }],
    ['comment_options', { author: 'spike-author', permlink: 'native-golden-multi', max_accepted_payout: '0.000 HBD',
      percent_hbd: 0, allow_votes: false, allow_curation_rewards: true, extensions: [] }],
  ]],
]
