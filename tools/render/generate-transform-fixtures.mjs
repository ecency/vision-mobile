// Golden fixtures for the native port of the render-helper post transforms (traverse, a, img,
// iframe, text, linkify, clean-reply, markdown-to-html), postBodySummary, catchPostImage and the
// helpers, produced by @ecency/render-helper 2.5.58 itself. All inputs are synthetic: example
// accounts, example hosts and made-up ids. JS never runs in the app.
//
//   cd tools/render && npm ci
//   node generate-transform-fixtures.mjs ../../shared/src/commonTest/kotlin/com/ecency/core/render/transform
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const outDir = process.argv[2]
if (!outDir) throw new Error('usage: generate-transform-fixtures.mjs <output dir>')

// Browser-build behaviour (no lolight), as in the app. Internals are exposed from a copy of the bundle.
globalThis.window = globalThis
const bundlePath = join(here, 'node_modules/@ecency/render-helper/dist/node/index.mjs')
const internalsPath = join(here, 'node_modules/.rh-transform-internals.mjs')
writeFileSync(internalsPath, readFileSync(bundlePath, 'utf8') +
  '\nexport { cleanReply as __cleanReply, linkify as __linkify, extractYtStartTime as __extractYtStartTime,' +
  ' isValidUsername as __isValidUsername, sanitizePermlink as __sanitizePermlink, decodeImageSrc as __decodeImageSrc,' +
  ' stripHtmlTags as __stripHtmlTags, youtubeVideoLinkClass as __youtubeVideoLinkClass, imageUrlEnd as __imageUrlEnd, decodeDomImageSrc as __decodeDomImageSrc,' +
  ' plainDestination as __plainDestination, withoutLoneSurrogates as __withoutLoneSurrogates };\n')
const rh = await import(pathToFileURL(internalsPath).href)
rh.setSlowRenderThresholdMs(0)

const PROXY_BASE = 'https://images.ecency.com'
rh.setProxyBase(PROXY_BASE)

// ---- URL catalogue ------------------------------------------------------------------------------

const YT = 'dQw4w9WgXcQ'
const urls = [
  // YouTube
  `https://www.youtube.com/watch?v=${YT}`, `https://youtube.com/watch?v=${YT}&t=42`, `https://youtu.be/${YT}`,
  `https://youtu.be/${YT}?t=90`, `https://youtu.be/${YT}?t=1m30s`, `https://www.youtube.com/watch?v=${YT}&start=30`,
  `https://www.youtube.com/watch?feature=share&v=${YT}`, `https://m.youtube.com/watch?v=${YT}`, `https://music.youtube.com/watch?v=${YT}`,
  `https://www.youtube.com/shorts/${YT}`, `https://youtube.com/shorts/${YT}?feature=share`, `https://www.youtube.com/embed/${YT}`,
  `https://www.youtube.com/v/${YT}`, `https://www.youtube.com/e/${YT}`, `https://www.youtube.com/user/example/videos/${YT}`,
  `HTTPS://WWW.YOUTUBE.COM/WATCH?V=${YT}`, `https://www.youtube.com/watch?v=a-b_c-d_e-f`, `https://www.youtube.com/watch?v=short`,
  `https://www.youtube.com/channel/UCexampleexampleexample`, `https://www.youtube.com/watch?v=${YT}&t=0x1A`,
  `https://www.youtube.com/watch?v=${YT}&t=`, `https://www.youtube.com/watch?v=${YT}&t=-5`, `https://www.youtube.com/watch?v=${YT}&t=99999999999999999999`,
  `https://www.youtube.com/watch?v=${YT}&t=%2012`, `https://youtu.be/${YT}#t=10`, `https://www.youtube.com/playlist?list=PLexample`,
  `https://youtube.com/watch?v=abcdefghi\u{1F41D}x`,
  // 3Speak
  'https://3speak.tv/watch?v=example-user/abcdefgh', 'https://play.3speak.tv/watch?v=example-user/abcdefgh&mode=iframe',
  'https://3speak.online/watch?v=example-user/xyz12345', 'https://3speak.tv/embed?v=example-user/abcdefgh',
  'https://3speak.tv/watch?v=example-user/abc&autoplay=true', 'https://audio.3speak.tv/play?a=example-user/clip1',
  'https://audio.3speak.tv/play?a=example-user/clip1&iframe=1', 'https://audio.3speak.tv/play?a=example-user/clip1&mode=full',
  // Vimeo, Twitch, DTube
  'https://vimeo.com/123456789', 'https://www.vimeo.com/channels/staffpicks/987654', 'https://vimeo.com/album/1/video/555',
  'https://www.twitch.tv/examplechannel', 'https://twitch.tv/videos/123456789', 'https://www.twitch.tv/abc',
  'https://d.tube/#!/v/exampleuser/QmHashHash', 'https://d.tube/v/exampleuser/QmHashHash', 'https://d.tube/#!/c/exampleuser',
  // Spotify, Loom, Bitchute, Rumble, Brighteon, Odysee
  'https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M', 'https://open.spotify.com/playlist/37i9dQZF1DX?si=abc',
  'https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC', 'https://open.spotify.com/playlist/',
  'https://www.loom.com/share/abc123def456', 'https://www.loom.com/share/abc?sid=1', 'https://www.bitchute.com/video/Ws-mhts2KlA/',
  'https://bitchute.com/embed/abc123/', 'https://rumble.com/embed/v1abcde/?pub=4', 'https://rumble.com/v1abc-example-title.html',
  'https://www.brighteon.com/abc-123-def', 'https://brighteon.com/embed/e1f2a3b4', 'https://www.brighteon.com/channels/example',
  'https://odysee.com/@examplechannel:2/example-video:e', 'https://odysee.com/example-video:e', 'https://odysee.com/@examplechannel:2',
  'https://odysee.com/@examplechannel:2/example-video:e?r=abc',
  // Twitter, social
  'https://twitter.com/example/status/1234567890123456789', 'https://twitter.com/example/status/123?s=20',
  'https://mobile.twitter.com/example/status/1', 'https://x.com/example/status/1234567890', 'https://www.instagram.com/p/ABCdef123/',
  'https://www.facebook.com/example/posts/123', 'https://t.me/example',
  // inleo / leofinance
  'https://inleo.io/threads/view/example-user/re-leothreads-abc', 'https://leofinance.io/posts/example-user/example-post',
  'https://inleo.io/@example-user/example-post', 'https://leofinance.io/threads/example-user/re-abc',
  // Hive frontends
  'https://ecency.com/hive-125125/@example-user/example-post', 'https://www.ecency.com/hive-125125/@example-user/example-post',
  'https://hive.blog/photography/@example-user/example-post', 'https://peakd.com/@example-user/example-post',
  'https://ecency.com/@example-user/example-post?referral=x', 'https://ecency.com/@example-user/example-post#@other/re-x',
  'https://ecency.com/hive-1/@example-user/Bad_Permlink', 'https://ecency.com/hive-1/@example-user/photo.png',
  'https://ecency.com/@example-user', 'https://peakd.com/@Example-User', 'https://hive.blog/@ab', 'https://ecency.com/@example-user/wallet',
  'https://ecency.com/@example-user/posts?sort=new', 'https://peakd.com/@example-user/followers', 'https://ecency.com/@example-user/points/x',
  'https://ecency.com/trending/hive', 'https://ecency.com/created/photography', 'https://peakd.com/hot/hive-125125',
  'https://ecency.com/c/hive-125125', 'https://ecency.com/c/hive-125125/about', 'https://peakd.com/c/hive-125125/trending',
  'https://ecency.com/ccc/example-user/example-post', 'https://example.com/tag/@example-user/example-post', 'https://example.com/@example-user',
  'https://steemit.com/tag/@example-user/old-post', 'https://3speak.tv/@example-user', 'https://inji.com/hive-1/@example-user/p1',
  // Relative and schemes
  '/@example-user', '/@Example-User', '/@example-user/example-post', '/@example-user/wallet', '/@example-user/wallet?x=1',
  '/hive-125125/@example-user/example-post', '/hive-125125/@example-user/followers', '/trending/hive', '/created/',
  '/@ab', '/@example-user/re-post_x', '#section', '#', '?q=1', 'mailto:someone@example.com', 'tel:+123', 'hive://sign/op/abc',
  'web+hive:op', 'javascript:alert(1)', 'JavaScript:alert(1)', 'java\nscript:alert(1)', 'data:text/html,x', 'vbscript:x', 'ftp://example.com/file',
  'example.com/path', 'Https://Example.com/A', 'HTTP://EXAMPLE.COM', '//example.com/proto-relative', 'relative/path.html', 'bare',
  'esteem://x', 'ecency://x', 'steem://x', 'https://example.com/page#fragment', 'https://example.com/#', 'file:///etc/passwd',
  // hivesigner
  'https://hivesigner.com/sign/account-witness-vote?witness=example-witness&approve=1',
  'https://hivesigner.com/sign/update-proposal-votes?proposal_ids=[123]&approve=true',
  'https://hivesigner.com/sign/update-proposal-votes?proposal_ids=%5B45%5D&approve=true',
  'https://hivesigner.com/sign/update-proposal-votes?proposal_ids=%E0%A4%A&approve=true',
  // Images
  'https://images.ecency.com/p/3W72119s5BjW4PvRk9nXBzqrPWMsMTjNrXDPFFf12CAfRz5MY8jCm5vGDUj7o96hwAT3YCMBJgcTgcWmsqeuBbZL1YbmxtaV4gyfByenDkmAkgv6rkm9ZP.png?format=match&mode=fit',
  'https://images.ecency.com/DQmExampleHash/photo.png', 'https://i.imgur.com/abc123.jpg', 'https://i.imgur.com/abc.gif',
  'https://files.peakd.com/file/peakd-hive/example-user/23tGx.jpeg', 'https://example.com/a.webp', 'https://example.com/a.svg',
  'https://example.com/PHOTO.PNG', 'https://example.com/photo.jpg?w=100&h=50', 'http://example.com/old.png',
  'https://images.hive.blog/0x0/https://files.peakd.com/file/peakd-hive/u/a.jpg', 'https://steemitimages.com/DQmAbc/x.png',
  'https://ipfs.io/ipfs/QmPziJCkx8w62UZCz1TNxAMsgf1uTnPZdVKAYjLBWvMzeD', 'https://gateway.example.com/ipfs/QmHash/file?x=1',
  'https://ipfs.io/ipns/example.eth', 'https://ipfs.io/ipfs/QmHash#frag', 'https://i.ecency.com/u/example-user/avatar/small',
  'https://example.com/a%20b.png', 'https://example.com/café.jpg', 'https://example.com/x.png?a=1&amp;b=2',
  'https://cdn.liketu.com/audio/x.webm', 'https://cdn.liketu.com/audio/x.webp',
]

// ---- bodies ------------------------------------------------------------------------------------

const linkForms = [
  (u) => u,
  (u) => `[${u}](${u})`,
  (u) => `[a label](${u})`,
  (u) => `<a href="${u}">${u}</a>`,
  (u) => `Text before ${u} and after.`,
  (u) => `<a href="${u}" title="${u}">x</a>`,
]

const bodies = []
urls.forEach((u, i) => {
  bodies.push([`url_${i}_a`, linkForms[i % linkForms.length](u)])
  bodies.push([`url_${i}_b`, linkForms[(i + 3) % linkForms.length](u)])
  if (i % 4 === 0) bodies.push([`url_${i}_c`, linkForms[(i + 1) % linkForms.length](u)])
})

const handcrafted = {
  // mentions
  mention_basic: 'Hello @example-user and @another.user!',
  mention_upper: 'Hi @Example-User, @EXAMPLE',
  mention_short: '@ab @abc @a-b @abc- @abc.',
  mention_email: 'mail someone@example.com or a@bcd.com',
  mention_paren: '(@example-user) "@example-user" @example-user\'s post',
  mention_fullwidth: '＠example-user and x＠example-user',
  mention_symbols: '!@example-user #@example-user $@example-user %@example-user &@example-user *@example-user +@example-user ~@example-user .@example-user /@example-user',
  mention_chain: '@example-user@other-user @user1/@user2',
  mention_slash: '@example-user/example-post and @scope/package',
  mention_long: '@averyveryverylongusername and @a.b.c',
  mention_dots: '@example.user.name @ex..user @-bad',
  mention_inline_code: 'code `@example-user` and\n\n    @indented-user\n',
  mention_link: '[@example-user](https://example.com) and <a href="https://example.com">@example-user</a>',
  mention_start_line: '@example-user\n@second-user',
  // tags
  tag_basic: '#hive #Photography #a-b #123 #1a',
  tag_glued: 'x#tag a#b #tag1#tag2 &#35;tag',
  tag_after_html: '<span>#inside</span> <b>#bold</b>',
  tag_entity: '&#x23;notag #ok; #semi',
  tag_unicode: '#café #мир #tag_underscore',
  tag_heading: '#not-a-tag-heading\n\n# Heading\n\n#tag at start',
  tag_in_code: '`#code` ```\n#fenced\n```',
  // internal links in text
  internal_tag_post: 'see /hive-125125/@example-user/example-post now',
  internal_post: 'see /@example-user/example-post and /@example-user/wallet',
  internal_upper: 'see /@Example-User/example-post and /Tag/@Example-User/Example-Post',
  internal_bad: 'see /@example-user/photo.png and /x/@a/b?c',
  internal_section: '/hive-1/@example-user/followers /@example-user/feed',
  internal_start: '/@example-user/first-post\n/tag/@example-user/second-post',
  post_url_text: 'https://ecency.com/hive-125125/@example-user/example-post',
  // images
  md_image: '![alt text](https://i.imgur.com/abc123.jpg)',
  md_image_title: '![alt](https://i.imgur.com/abc123.jpg "A title")',
  md_image_proxied: '![](https://images.ecency.com/p/abc.png?format=match&mode=fit)',
  md_image_relative: '![rel](photo.jpg) ![abs](/photo.jpg) ![js](javascript:alert(1))',
  html_img_dims: '<img src="https://example.com/a.png" width="640" height="480">',
  html_img_dims_bad: '<img src="https://example.com/a.png" width="100%" height="480"><img src="https://example.com/b.png" width="1" height="8000">',
  html_img_attrs: '<img src="https://example.com/a.png" onerror="x()" dynsrc="x" lowsrc="y" alt="a" class="no-replace">',
  html_img_x: '<img src="x"><img src=""><img><img src="vbscript:x">',
  html_img_avatar: '<img src="https://images.ecency.com/u/example-user/avatar/small"><img class="er-author-link-image" src="https://example.com/a.png">',
  html_img_proxied: '<img src="https://images.ecency.com/p/abcdef?format=match&mode=fit"><img src="https://images.ecency.com/600x400/https://x/y.png">',
  html_img_upper: '<IMG SRC="https://example.com/up.png"><img SRC="https://example.com/up2.png">',
  html_img_entities: '<img src="https://example.com/a.png?x=1&amp;y=2"><img src="https://example.com/%E2%82%AC.png"><img src="https://example.com/%zz.png">',
  images_many: '![1](https://example.com/1.png)\n![2](https://example.com/2.png)\n![3](https://example.com/3.png)\n![4](https://example.com/4.png)',
  images_in_text: 'look https://example.com/a.png and https://example.com/b.jpg\nnext https://example.com/c.gif end',
  image_link: '[![thumb](https://example.com/t.png)](https://example.com/page)',
  dtube_thumb: '[![thumb](https://example.com/t.png)](https://d.tube/#!/v/exampleuser/QmHash)',
  speak_thumb: '[![thumb](https://example.com/t.png)](https://3speak.tv/watch?v=example-user/abcdefgh)',
  speak_thumb_nested: '<a href="https://3speak.tv/watch?v=example-user/abcdefgh"><span><img src="https://example.com/t.png"></span></a>',
  picture_eligible: '![](https://files.peakd.com/file/peakd-hive/example-user/photo.jpg)\n\nhttps://example.com/x.jpeg',
  // iframes
  iframe_youtube: '<iframe src="https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1&controls=0"></iframe>',
  iframe_youtube_m: '<iframe src="//m.youtube.com/embed/dQw4w9WgXcQ?x=1" width="560"></iframe>',
  iframe_youtube_shorts: '<iframe src="https://youtube.com/shorts/dQw4w9WgXcQ?feature=share"></iframe>',
  iframe_youtube_shorts_bad: '<iframe src="https://youtube.com/shorts/abc"></iframe>',
  iframe_bitchute: '<iframe src="https://www.bitchute.com/embed/abc123/"></iframe>',
  iframe_vimeo: '<iframe src="https://player.vimeo.com/video/123456?h=abc"></iframe>',
  iframe_twitch: '<iframe src="https://player.twitch.tv/?channel=example"></iframe>',
  iframe_twitch_parent: '<iframe src="https://player.twitch.tv/?channel=example&parent=x.com&autoplay=true"></iframe>',
  iframe_twitch_bare: '<iframe src="//player.twitch.tv/"></iframe>',
  iframe_3speak: '<iframe src="https://3speak.tv/embed?v=example-user/abcdefgh"></iframe>',
  iframe_3speak_play: '<iframe src="https://play.3speak.tv/watch?v=example-user/abc&mode=iframe&autoplay=true&layout=desktop"></iframe>',
  iframe_3speak_online: '<iframe src="https://3speak.online/embed?v=example-user/abcdefgh&AUTOPLAY=1"></iframe>',
  iframe_3speak_audio: '<iframe src="https://audio.3speak.tv/play?a=example-user/clip"></iframe>',
  iframe_spotify: '<iframe src="https://open.spotify.com/embed/playlist/37i9dQZF1DX"></iframe>',
  iframe_soundcloud: '<iframe src="https://w.soundcloud.com/player/?url=https%3A//api.soundcloud.com/tracks/123&color=ff5500"></iframe>',
  iframe_soundcloud_nourl: '<iframe src="https://w.soundcloud.com/player/?x=1"></iframe>',
  iframe_dtube: '<iframe src="https://emb.d.tube/#!/exampleuser/QmHash"></iframe>',
  iframe_vimm: '<iframe src="https://www.vimm.tv/example/embed"></iframe>',
  iframe_dapplr: '<iframe src="https://player.eu.dapplr.in/file/dapplr-videos/example/x.mp4"></iframe>',
  iframe_truvvl: '<iframe src="https://embed.truvvl.com/@example-user/story"></iframe>',
  iframe_lbry: '<iframe src="https://lbry.tv/$/embed/example/abc"></iframe>',
  iframe_odysee: '<iframe src="https://odysee.com/$/embed/@example:2/video:e"></iframe>',
  iframe_odysee_enc: '<iframe src="https://odysee.com/%24/embed/%40example%3A2%2Fvideo%3Ae?r=abc"></iframe>',
  iframe_skatehive: '<p><iframe src="https://ipfs.skatehive.app/ipfs/QmVideoHash"></iframe></p>',
  iframe_skatehype: '<iframe src="http://www.skatehype.com/ifplay.php?v=12345"></iframe>',
  iframe_archive: '<iframe src="https://archive.org/embed/example-item"></iframe>',
  iframe_rumble: '<iframe src="https://rumble.com/embed/v1abc/?pub=4"></iframe>',
  iframe_brighteon: '<iframe src="https://www.brighteon.com/embed/abc123"></iframe>',
  iframe_bnt: '<iframe src="https://brandnewtube.com/embed/abc123"></iframe>',
  iframe_loom: '<iframe src="https://www.loom.com/embed/abc123"></iframe>',
  iframe_aureal: '<iframe src="//aureal-embed.web.app/12345"></iframe>',
  iframe_unsupported: '<iframe src="https://evil.example.com/embed"></iframe>',
  iframe_nosrc: '<p>before<iframe></iframe>after</p>',
  iframe_upper: '<IFRAME SRC="https://www.youtube.com/embed/dQw4w9WgXcQ"></IFRAME>',
  // layout, entities, misc
  entities: 'Tom &amp; Jerry &nbsp; &copy; &#169; &#x1F41D; &bogus; &AMP; &#1234567; &lt;b&gt;',
  entities_repeat: '&amp; &amp; &nbsp;&nbsp; ​0​ literal placeholder',
  entities_in_link: '[a &amp; b](https://example.com/?a=1&amp;b=2) <a href="https://example.com/?x&amp;y">x &amp; y</a>',
  p_dir: '<p dir="rtl">right</p>\n\n<p>plain</p>\n\nmarkdown paragraph',
  center_block: '<center>\n\n![](https://example.com/c.png)\n\n</center>',
  div_classes: '<div class="pull-left">L</div><div class="pull-right">R</div><div class="text-justify">J</div>',
  table: '| a | b |\n|---|---|\n| @example-user | #tag |',
  code_block: '```\nhttps://example.com/a.png @user #tag\n```\n\n`https://example.com/b.png`',
  blockquote: '> quote with @example-user and https://example.com/q.png',
  heading_ids: '# Title\n\n## Sub @example-user',
  details: '<details><summary>Spoiler</summary>Hidden @example-user</details>',
  html_mix: '<div><a href="https://ecency.com/@example-user">profile</a> text <b>bold #tag</b></div>',
  malformed: '<div><p>unclosed <b>bold</div>',
  malformed2: '<center>text</p></center><span>',
  script_style: '<script>alert(1)</script><style>p{}</style>text',
  footer_partiko: 'Body text\n\nPosted using [Partiko Android](https://partiko.app/referral/x)',
  footer_lumen: 'Body\n\n---\n*Posted via Lumen by example-user*',
  footer_scrobble: 'Body\n\n----  \n\n_Originally posted through [scrobble.life/x](https://scrobble.life)_',
  footer_dbuzz: 'Body Posted via <a href="https://d.buzz" data-link="promote-link">D.Buzz</a>',
  footer_liketu: 'Body\n\nPosted from Liketu Speak, auto-transcribed',
  footer_3speak: 'Body\n\n▶️ [Watch on 3Speak](https://3speak.tv/watch?v=a/b)',
  footer_inbox: 'Body [via Inbox] text',
  rtl: 'مرحبا @example-user #وسم',
  twitter_html: '<a href="https://twitter.com/example/status/123">https://twitter.com/example/status/123</a> and more',
  twitter_lt: '[https://twitter.com/ex<b>/status/1](https://twitter.com/ex<b>/status/1)',
  anchors_classes: '<a class="markdown-author-link" href="https://evil.example" target="_blank">x</a><a class="er-tag x" href="/x">y</a>',
  anchor_no_href: '<a>no href</a><a href="">empty</a><a name="x">named</a>',
  anchor_nested_img: '<a href="https://example.com/a.png"><img src="https://example.com/a.png"></a>',
  anchor_text_img: '<a href="https://example.com/a.png">https://example.com/a.png</a>',
  anchor_ipfs_text: '<a href="https://ipfs.io/ipfs/QmX">https://ipfs.io/ipfs/QmX</a>',
  linkified_in_heading: '### https://example.com/h.png',
  bare_youtube_text: 'watch this https://www.youtube.com/watch?v=dQw4w9WgXcQ later',
  bare_youtube_html: '<p>https://youtu.be/dQw4w9WgXcQ?t=5</p>',
  youtube_in_div: '<div>https://www.youtube.com/watch?v=dQw4w9WgXcQ</div>',
  liketu_audio: '[Listen to the original voice](https://cdn.liketu.com/audio/abc.mp3)',
  ccc: '[x](https://ecency.com/ccc/example-user/example-post)',
  sub_sup_mark: '~sub~ ^sup^ ==mark== ++ins++ H~2~O',
  footnote: 'Text[^1]\n\n[^1]: Note with @example-user',
  abbr: '*[HTML]: Hyper Text\n\nHTML is fine',
  deflist: 'Term\n: Definition @example-user',
  empty: '',
  whitespace: '   \n\n  ',
  only_entity: '&amp;',
  long_link_text: `[${'word '.repeat(40)}](https://example.com/long)`,
  hr_and_br: 'a  \nb\n\n---\n\nc',
  nbsp_mention: ' @example-user #tag',
  line_sep: 'a @example-user b https://example.com/ls.png tail',
  emoji: '🐝 @example-user 🐝 #bee',
  lone_surrogate: 'x\uD83D @example-user',
  ws_url: 'https://example.com/a.png\thttps://example.com/b.png',
  zwnj_placeholder: 'x\u200C0\u200C y @example-user',
  // On-host uploads (images.ecency.com/DQm...): img() must still route them through /p/ (forceProxy).
  upload_md: '![](https://images.ecency.com/DQmExampleHashExampleHashExample1234/upload.png)',
  upload_html: '<img src="https://images.ecency.com/DQmExampleHashExampleHashExample1234/photo.jpg">',
  upload_html_sized: '<img src="https://images.ecency.com/DQmExampleHash/photo.jpg" width="640" height="480">',
  upload_bare: 'https://images.ecency.com/DQmExampleHashExampleHashExample1234/bare.png',
  upload_link: '[https://images.ecency.com/DQmExample/l.png](https://images.ecency.com/DQmExample/l.png)',
}
// Bylines added in 2.5.42. Kept out of FRAGMENTS so the seeded random cases (and the security
// deviation list keyed by their names) stay as they were; merged into handcrafted after FRAGMENTS.
const bylines2542 = {
  footer_3speak_tv: 'Body\n\n---\n\u25B6 [Watch on 3speak.tv](https://3speak.tv/watch?v=example-user/abcdefgh)',
  footer_3speak_tv_emoji: 'Body\n\n---  \n\n\u25B6\uFE0F [Watch on 3Speak.tv](https://3speak.tv/watch?v=example-user/abcdefgh) \nAfter',
  footer_3speak_tv_inline: 'Body\n\n---\nSee \u25B6 [Watch on 3speak.tv](https://3speak.tv/watch?v=a/b) here',
  footer_3speak_tv_text_after: 'Body\n\n---\n\u25B6 [Watch on 3speak.tv](https://3speak.tv/watch?v=a/b) and my notes',
  footer_hivesuite: 'Body\n\n<sub>Posted via [HiveSuite](https://hivesuite.app/@example-user/example-post)</sub>',
  footer_travelfeed_com: 'Body\n\nView this post <a href="https://travelfeed.com/@example-user/example-post">on TravelFeed</a> for the best experience.',
  footer_liketu_clips: 'Body\n\n_Posted from Liketu Clips: a short video, trimmed and shared_',
  footer_liketu_clips_half: 'I _posted from Liketu Clips_ yesterday',
}
// Rules added in 2.5.43 to 2.5.52 (#3711). Kept out of FRAGMENTS, as the bylines above.
const rules2552 = {
  yt_t_minutes: 'Watch https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1m30s now',
  yt_t_hms_link: '[clip](https://youtu.be/dQw4w9WgXcQ?t=1h2m3s) and [bad](https://youtu.be/dQw4w9WgXcQ?t=-5&start=007)',
  img_query_suffix: 'https://example.com/i.png?name=a.jpg&sig=x',
  img_text_after: 'see https://example.com/i.png & friends',
  img_balanced_parens: '![p](https://example.com/photo_(1).png) and https://example.com/photo_(2).png',
  not_image_ext: 'https://example.com/.icons/readme and https://example.com/photo.jpg.html',
  tel_link: '[call](tel:+15550100) <a href="TEL:+15550101">x</a> [h](web+example:op)',
  img_entity: '![x](https://example.com/a.png?x=1&amp;y=2) <img src="https://example.com/b.png?x=1&times=2">',
  img_escaped_amp: '![x](https://example.com/a.png?q=\\&copy;) [t](https://example.com/?a=1\\&amp;b)',
  iframe_refused: '<iframe src="https://evil.example.com/watch?v=dQw4w9WgXcQ"></iframe> and <iframe src="https://www.youtube.com/shorts/abc"></iframe>',
  list_tab_fence: '-\t```\n\t&amp; <b>\n\t```\n',
  list_space_tab_fence: '- a\n \t```\n &amp; &lt;\n```',
  author_marker: '<a class="markdown-author-link" data-author="example-user" href="https://evil.example.com">x</a> <span class="markdown-tag-link" data-tag="x">t</span>',
  author_id_fragment: '<h2 id="intro">Intro</h2>\n\n[jump](#intro) <a href="#missing">no</a> <a href="#in%74ro">enc</a>',
  author_classes: '<div class="pull-right fixed inset-0">x</div><span class="ecency-text-color-ff0000 z-50">y</span>',
  video_poster: '<video src="https://example.com/v.mp4" poster="https://example.com/p.jpg?a=1&amp;times=2"></video>',
  code_entities: '`&amp; &lt;b&gt;`\n\n```\n&copy; &#169;\n```',
}
// Rules added in 2.5.53 and 2.5.54 (#2154 entity protection, #2155 render edges, #2165 placeholder guard), from the
// upstream specs' inputs with example hosts and accounts. Kept out of FRAGMENTS, as the rules above.
const allDelimiters = '​⁠⁡⁢⁣⁤' + Array.from({ length: 0xf8ff - 0xe100 + 1 }, (_, i) => String.fromCharCode(0xe100 + i)).join('')
const rules2554 = {
  angle_amp_plain: '[x](https://example.com/?q=&amp;amp;amp;)',
  angle_amp: '[x](<https://example.com/?q=&amp;amp;amp;>)',
  angle_paren: '[open](<https://example.test/a)b?x=1&amp;y=2>)',
  angle_space: '[x](<https://example.com/a b?q=1&amp;r=2>)',
  angle_img: '![x](<https://example.com/a.png?q=&amp;amp;amp;>)',
  angle_img_plain: '![x](https://example.com/a.png?q=&amp;amp;amp;)',
  angle_ref: '[x][r]\n\n[r]: <https://example.com/?q=&amp;amp;amp;>',
  angle_ref_plain: '[x][r]\n\n[r]: https://example.com/?q=&amp;amp;amp;',
  lookalike_code: '`​0​` and &amp;',
  lookalike_text: '​0​ and &amp;',
  indented_code_entity: 'para\n\n    a &amp; b\n',
  no_fence_entity: '```bad`info\n\nx &copy;\n',
  backticks_per_block: 'one ` tick\n\ntwo &copy;\n\nthree `code`',
  path_entity_slash: '[x](/&#47;evil.example/@example-user/X_y)',
  path_entity_post: 'zero​width\n\n[x](/&#47;evil.example/@example-user/some-post)',
  path_entity_private: 'a​⁠⁡⁢⁣⁤b\n\n[x](/&#92;evil.example/@example-user/posts)',
  path_entity_pair: `${allDelimiters}\n\n[x](/&#47;evil.example/@example-user/some-post)`,
  ref_first_wins: '[a]: https://example.com/?q=&amp;\n[a]: <https://example.com/?q=&amp;amp;>\n\n[x][a]',
  ref_failed_first: '[a]: <https://example.com/?q=&amp;amp;> junk\n\n[a]: https://example.com/?q=&amp;\n\n[x][a]',
  tab_fence: '```\nx&lt;\ty\n```',
  tab_indented: '    x&lt;\ty &amp;\tz\n',
  tab_after_text: 'a\tb\n\n```\n&copy;\t|\n```',
  angle_text_no_link: 'see <https://example.com/my page &copy; more> end',
  angle_text_escaped: 'x &copy; <https://example.com/a\\> y &copy; z > w &copy;',
  encoded_delimiter: 'https://example.com/&#8203;0&#8203; and &copy;',
  angle_after_bare_url: '[https://a.example](<https://example.com/a b?x=1&amp;y=2>)',
  angle_after_bare_url_text: '[see https://a.example](<https://example.com/a b?x=1&amp;y=2>)',
  angle_ref_bare_url_label: '[https://a.example]: <https://example.com/a b?x=1&amp;y=2>\n\n[x][https://a.example]',
  cr_breaks: 'a\r\r\n\r\r\nb &amp;',
  cr_breaks_lead: '&amp;\r\r\nx',
  ls_tab: 'a \tb &amp;\r\n\tc',
  heading_definition: '[a]: <https://example.com/?q=&amp;amp;amp;>\n\n# [a]: <https://example.com/?q=&amp;amp;amp;>\n\n[x][a]',
  angle_ls: 'x (<https://example.com/a b &copy; c>) y',
  angle_nel: 'x (<https://example.com/a\u0085b &copy; c>) y',
  angle_2424: 'x (<https://example.com/a␤b &copy; c>) y',
  angle_ref_ls: '[r]: <https://example.com/a b &copy; c>',
  angle_ref_nel: '[r]: <https://example.com/a\u0085b &copy; c>',
  angle_escaped_ls: '[x](<https://example.com/a\\ b?q=&copy;&amp;z=1>)',
  angle_escaped_lf: '[x](<https://example.com/a\\\nb?q=&copy;&amp;z=1>)',
  angle_escaped_crlf: '[x](<https://example.com/a\\\r\nb?q=&copy;&amp;z=1>)',
  angle_escaped_crnel: '[x](<https://example.com/a\\\r\u0085b?q=&copy;&amp;z=1>)',
  prose_angle_paren: 'see (<https://example.com/a b &copy; c>) end',
  prose_angle_paren_space: 'see ( <https://example.com/a b &copy;>) end',
  prose_angle_label: 'text ]: <https://example.com/a b &copy;> end',
  angle_mailto: '[x](<mailto:example-user@example.com?subject=One&amp;body=Two>)',
  angle_relative: '[x](</path?q=&amp;amp;>)',
  angle_lt_inside: '[x](<https://example.com/a<b?q=&amp;z>)',
  angle_quote_escaped_lf: '> [x](<https://example.com/a\\\n> b?q=&copy;>)',
  empty_label_plain: '[]: https://example.com/?q=&amp;amp;\n\n[x][]',
  empty_label_angle: '[]: <https://example.com/?q=&amp;amp;>\n\n[x][]',
  empty_label_first_wins: '[]: https://example.com/?q=&amp;\n[ ]: <https://example.com/?q=&amp;amp;>\n\n[x][]',
  all_delimiters_taken: `${allDelimiters}\n\nx &copy; y`,
  ref_paragraph_end: '[q] and ]: stuff\n[b]: <https://example.com/a b?q=&amp;amp;>\n\n[b]: <https://example.com/a b?q=&amp;amp;>\n\n[x][b]',
  ref_note_line: '[a]: <https://example.com/u>\nNote: [b]: <https://example.com/a b?q=&amp;amp;>\n\n[b]: <https://example.com/a b?q=&amp;amp;>\n\n[x][b]',
  ref_empty_after_title: '[a]: <https://example.com/u> "t\n[]: <https://example.com/c d?q=&copy;&amp;>"\n[]: <https://example.com/a b?q=&amp;amp;>\n\n[][]',
  ref_empty_wide_indent: '[r]: <https://example.com/u>\n　[]: <https://example.com/a b?q=&amp;amp;>\n\n[][]',
  ref_title_quote: '[a]: <https://example.com/u> "t\n[]: https://example.com/fake"\n[]: <https://example.com/a b?q=&amp;amp;>\n\n[][]',
  ref_title_paren: '[a]: <https://example.com/u> (t\n[]: https://example.com/fake)\n[]: <https://example.com/a b?q=&amp;amp;>\n\n[][]',
  ref_empty_chain: '[x]:\n[]:\n[]: <https://example.com/a b?q=&amp;amp;>\n\n[][]',
  ref_title_not_definition: '[a]: <https://example.com/u> "t\n[]: not-a-definition"\n[]: <https://example.com/?q=&amp;amp;>\n\n[][]',
  img_paren_query: '(https://host.example/photo.jpg?size=1)',
  img_paren_query_div: '<div>(https://host.example/photo.jpg?size=1)</div>',
  img_paren_query_p: '<p>(https://host.example/photo.jpg?size=1)</p>',
  dapplr_nested: '<iframe src="https://player.eu.dapplr.in/file/dapplr-videos/x.mp4"></iframe>',
  dapplr_cdn: '<iframe src="https://cdn.dapplr.in/file/dapplr-videos/x.mp4"></iframe>',
  dapplr_refused: ['https://.dapplr.in/file/dapplr-videos/x.mp4', 'https://a..dapplr.in/file/dapplr-videos/x.mp4',
    'https://a_b.dapplr.in/file/dapplr-videos/x.mp4', 'https://a.dapplr.in:8443/file/dapplr-videos/x.mp4',
    'https://u:p@a.b.dapplr.in/file/dapplr-videos/x.mp4', 'https://a.dapplr.in\\file\\dapplr-videos\\x',
    'https:///a.b.dapplr.in/file/dapplr-videos/x.mp4', 'https://a%2eb.dapplr.in/file/dapplr-videos/x.mp4']
    .map((src) => `<iframe src="${src}"></iframe>`).join('\n\n'),
  web_path_2060: '<a href="/topic⁠/@example-user/some-post">x</a>',
  guard_2060: '[x](/topic⁠/@example-user/some-post)',
  guard_2060_entity: 'a &amp; b\n\n[x](/topic⁠/@example-user/some-post)',
  guard_private: '[x](/topic/@example-user/some-post)',
  guard_private_entity: 'a &amp; b\n\n[x](/topic/@example-user/some-post)',
  guard_zwsp_path: '[x](/topic​/@example-user/some-post) [y](/​/evil.example/@example-user/some-post)',
  guard_zwsp_path_entity: 'a &amp; b\n\n[x](/topic​/@example-user/some-post) [y](/​/evil.example/@example-user/some-post)',
}
// Rules added in 2.5.55 (#2156 to #2160, #2167, #2169, #2170, #2174), from the upstream specs' inputs with example
// hosts and accounts. Kept out of FRAGMENTS, as the rules above.
const rules2555 = {
  angle_img_amp: '![x](<https://example.com/a.png?q=&amp;amp;>)',
  angle_img_space: '![x](<https://example.com/a b.png>)',
  plain_img_space: '![x](https://example.com/a%20b.png)',
  bare_paren_entity: 'https://h.example/p.jpg?a=1&#41;',
  bare_paren_balanced: 'https://h.example/p.jpg?a=(1)',
  bare_paren_double: 'https://h.example/p.jpg?a=1))',
  bare_paren_text: '(https://h.example/p.jpg?size=1)text',
  angle_lone_surrogate: 'text\n\n[y](<https://example.com/\ud800?q=1>)',
  autolink_entities: '<https://example.com/a b?q=&copy;&amp;z=1>',
  ref_percent_plain: '[x][k]\n\n[k]: https://example.com/%',
  ref_percent_angle: '[x][k]\n\n[k]: <https://example.com/%>',
  link_accent_plain: '[x](https://example.com/é)',
  link_accent_angle: '[x](<https://example.com/é>)',
  upper_scheme: 'HTTPS://A.example/p and Http://a.example and HTTP://a.example/x?y=1',
  paren_entity_tail: 'see https://example.com/a)&amp;x end',
  adjacent_images_entity: '![a](https://example.com/i.png)![b](https://example.com/j.png?x=1&amp;y=2)',
  link_then_bare_entity: '[a](https://example.com/1)https://example.com/2?x=1&amp;y=2',
  linkify_paren_entity_1: '(https://example.com/a)&amp;x',
  linkify_quote_entity: "https://example.com/a'&amp;x",
  linkify_parens_entity: 'https://example.com/a))&amp;x',
  linkify_open_entity: 'https://example.com/a)(&amp;x',
  linkify_text_paren_entity: 'x https://example.com/a)&amp;x',
  card_emphasis: '**https://h.example/p.jpg** *https://h.example/q.jpg* ~~https://h.example/r.jpg~~',
  card_constructs: 'https://h.example/p.jpg[^1] https://h.example/p.jpg[x](https://example.com)',
  underscore_host: 'https://my_bucket.s3.example.com/a/p.jpg',
  angle_img_percent: '![x](<https://example.com/50% off.png>)',
  plain_img_percent: '![x](https://example.com/50%25%20off.png)',
  angle_img_escape: '![x](<https://example.com/a\\_b.png>)',
  angle_link_percent: '[x](<https://example.com/50% off>)',
  emphasis_star_url: '*italic*https://example.com/a*b.png',
  emphasis_under_url: '_**https://example.com/a_b.png**_',
  url_then_label: 'https://example.com/a.png[a[b]](c)',
  url_then_ref: 'https://example.com/a.png[x]\n\n[x]: /u',
  invalid_numeric_ref: 'see www.example.com/it&#146;s now',
  app_paren_entity_img: 'https://example.com/p.png)&amp;x',
  app_quote_entity_img: "https://example.com/p.png'&amp;",
  app_copy_img: 'https://example.com/p.png&copy;',
  entity_boundary_semicolon: 'Visit www.example.com/q?x=1&y; now',
  entity_boundary_foo: 'see https://example.com/a)&foo; there',
  entity_boundary_email: 'joe@exam&bne;ple.com',
  nested_bare_iframes: '<iframe>a<iframe>b<iframe>c</iframe>d</iframe>e</iframe>f',
  private_use: 'ab ab ab a\u{F0000}b',
  img_hash_escape: '![x](https://example.com/a%23b.png)',
  img_signed_query: '![x](https://example.com/i.png?sig=ab%2Bcd%3D&u=a%26b)',
  img_slash_escape: '![x](https://example.com/dir%2Fa.png)',
  img_query_escape: '![x](https://example.com/a.png?x=%3F)',
  img_double_escape: '![x](https://example.com/a%2520b.png)',
  img_wrapper_url: '![x](https://wsrv.example/?url=https%3A%2F%2Fother.example%2Fx.png&w=600)',
  img_ref_param: '![x](https://cdn.example.com/img.png?ref=https%3A%2F%2Fsite.example%2Fp&sig=a%2Bb%3D)',
  img_utf8_escape: '![x](https://example.com/caf%C3%A9.png)',
  html_img_hash: '<img src="https://example.com/a%23b.png">',
  html_img_space: '<img src="https://example.com/a%20b.png">',
  video_poster_hash: '<video poster="https://example.com/a%23b.png"></video>',
  legacy_steemit: '![x](https://steemitimages.com/0x0/https%3A%2F%2Fexample.com%2Fa.png)',
  legacy_hive: '![x](https://images.hive.blog/640x0/https%3A%2F%2Fexample.com%2Fa%20b.png)',
  legacy_ecency: '![x](https://images.ecency.com/0x0/https%3A%2F%2Fexample.com%2Fa.png)',
  legacy_half: '![x](https://steemitimages.com/0x0/https:%2F%2Fexample.com%2Fa.png)',
  malformed_escape: '![x](https://example.com/a%zz%23.png)',
  legacy_passthrough_hash: '![x](https://images.ecency.com/0x0/https://example.com/p/a%23b.png)',
  legacy_passthrough_nl: '![x](https://images.hive.blog/0x0/https://example.com/p/a%0Ab.png)',
  angle_img_surrogate: '![x](<https://example.com/\ud800 a%zz.png>)',
  angle_img_surrogate_low: '![x](<https://example.com/a b\udc00.png>)',
  angle_img_surrogate_pct: '![x](<https://example.com/\ud800%.png>)',
  wmp_anchor: "<a href='http://worldmappin.com/@example-user'>This user on Worldmappin</a>",
  wmp_user: 'http://worldmappin.com/@example-user https://worldmappin.com/@example-user',
  wmp_post: '[x](https://worldmappin.com/@example-user/example-post) https://worldmappin.com/@example-user/example-post',
  wmp_tag_post: '[x](https://worldmappin.com/travel/@example-user/example-post)',
  wmp_map_route: "<a href='http://worldmappin.com/p/example-post'>post</a>",
}
// Card and post image bodies of 2.5.55 (#2157, #2169, #2174): the card reads a bare URL as the render's linkify does.
const cardBodies2555 = [
  'https://h.example/p.jpg?a=1&#41;', 'https://h.example/p.jpg?a=(1)', 'https://h.example/p.jpg?a=1))', '(https://h.example/p.jpg?size=1)text',
  '![x](<https://example.com/a.png?q=&amp;amp;>)', '![x](https://example.com/a.png?q=&amp;amp;)', '![x](<https://example.com/a b.png>)',
  '**https://h.example/p.jpg**', '*https://h.example/p.jpg*', '~~https://h.example/p.jpg~~', 'text **https://h.example/p.jpg**.',
  'https://h.example/p.jpg[^1]', 'https://h.example/p.jpg[x](https://example.com)', 'https://h.example/p.jpg![x](https://example.com/q.png)',
  'https://my_bucket.s3.example.com/a/p.jpg', '![x](<https://example.com/50% off.png>)', '![x](https://example.com/50%25%20off.png)',
  '![x](<https://example.com/a\\_b.png>)', '*italic*https://example.com/a*b.png', '_**https://example.com/a_b.png**_',
  'https://example.com/a.png[a[b]](c)', 'https://example.com/a.png[x]\n\n[x]: /u',
  '**https://example.com/p.png.**', '**https://example.com/p.png)**', '*https://example.com/p.png&#42;', '*Source: https://example.com/p.png*',
  '_Source: https://example.com/p.png_', '**Source: https://example.com/p.png**', '~~old https://example.com/p.png~~', '*Source: https://example.com/p.png.*',
  '(https://x.y_z.example/p.jpg)', '(https://a.b_c.example/p.jpg).', 'https://example.com/p.gif&#91;_', 'https://example.com/p.png&lt;~',
  'https://example.com/p.png?_', 'Is it https://example.com/p.png?*', 'https://example.com/p.png?v=2_',
  'https://a_b.c_d.example/p.jpg', 'https://example.com?i=p.png..', 'a*https://example.com/p.gif*', 'x~https://example.com/p.gif~',
  '**https://example.com/p.png**;', '*https://example.com/p.png*_', '**https://example.com/p.png**_', '(**https://example.com/p.jpeg**)',
  '"**https://example.com/p.jpeg**"', '**https://example.com/p.jpeg**)', '*https://example.com/p.png*)',
  '_(https://example.com/p.png)_', '_(Source: https://example.com/p.png)_', '(https://example.com/p.png)_a_', '- _(https://example.com/p.png)_',
  '==(https://example.com/p.png)==', '++(https://example.com/p.png)++', '| a | b |\n|---|---|\n|(https://example.com/p.png)|x|',
  'https://example.com/p.png&#41;*', 'a@https://example.com/p.png))', '@HTTPS://example.com/p.jpg))',
  'x # https://example.com/p.png.)', '^https://example.com/p.png))^', 'https://example.com/p.png\ud83d', 'https://example.com/\ud83dp.png',
  'x # y https://example.com/p.png))', 'Step # 2 (see image https://example.com/p.png))', 'x # y https://example.com/p.png?a=1))',
  'x &num; y https://example.com/p.png))', 'x &#35; y https://example.com/p.png))', 'x \\# y https://example.com/p.png))', 'x (#) y https://example.com/p.png))',
  '- x #, y https://example.com/p.png))', 'C# y https://example.com/p.png))', 'C# https://example.com/p.png))', 'x # y\nhttps://example.com/p.png?a=(1)',
  '## Title https://example.com/p.png?a=(1)', '#tag https://example.com/p.png?a=(1)', 'x #1 https://example.com/p.png?a=(1)',
  '_x_# https://example.com/p.png))', 'a_# https://example.com/p.png))', '²# https://example.com/p.png))', '#² https://example.com/p.png))',
  '(#hive) https://example.com/p.png))', '"#hive" https://example.com/p.png))', '#😀 https://example.com/p.png))', '#_x https://example.com/p.png))',
  '> x #! y https://example.com/p.png))',
  '![x](https://example.com/a%20b.png)', '![x](https://example.com/caf%C3%A9.png)', '<img src="https://example.com/a%20b.png">',
  '![x](https://images.ecency.com/0x0/https://example.com/a%23b.png)', '![x](https://images.ecency.com/0x0/https://example.com/p/a%23b.png)',
  '![x](https://steemitimages.com/0x0/https://www.example.com/p/Bx%23y/media.jpg)', '![x](https://images.hive.blog/0x0/https://example.com/p/a%0Ab.png)',
  '![x](<https://example.com/\ud800 a%zz.png>)', '![x](<https://example.com/a b\udc00.png>)', '![x](<https://example.com/\ud800%.png>)',
  '![x](https://example.com/a%23b.png)', '![x](https://example.com/i.png?sig=ab%2Bcd%3D&u=a%26b)', '![x](https://example.com/dir%2Fa.png)',
  '![x](https://example.com/a.png?x=%3F)', '![x](https://example.com/a%2520b.png)', '![x](https://wsrv.example/?url=https%3A%2F%2Fother.example%2Fx.png&w=600)',
  '![x](https://cdn.example.com/img.png?ref=https%3A%2F%2Fsite.example%2Fp&sig=a%2Bb%3D)', '<img src="https://example.com/a%23b.png">',
  '![x](https://steemitimages.com/0x0/https%3A%2F%2Fexample.com%2Fa.png)', '![x](https://images.hive.blog/640x0/https%3A%2F%2Fexample.com%2Fa%20b.png)',
  '![x](https://images.ecency.com/0x0/https%3A%2F%2Fexample.com%2Fa.png)', '![x](https://steemitimages.com/0x0/https:%2F%2Fexample.com%2Fa.png)',
  '![x](https://example.com/a%zz%23.png)',
  // a reference definition's destination and a raw anchor's text are not autolinked: develop's reading
  '[r]: https://h.example/p.jpg?a=(1)', '[r]:\n  https://h.example/p.jpg?a=(1)\n\n[x][r]', '[r]: \t https://h.example/p.jpg?a=1))',
  '<a href="https://example.com">https://h.example/p.jpg?a=(1)</a>', '<a href="https://example.com">https://h.example/p.jpg?a=1)</A >',
  'x <a href="https://h.example/p.jpg?a=1&#41;"> https://h.example/p.jpg?a=1&#41;</a> y',
  'x <a href="https://h.example/p.jpg?a=&#40;1&#41;"> https://h.example/p.jpg?a=&#40;1&#41;</a> y',
]
// Rules of 2.5.56 (#2176): the render reads its input as before (shared helpers, no output change); these bodies pin
// the input pass the card now copies. From the upstream spec's inputs with example hosts. Kept out of FRAGMENTS.
const rules2556 = {
  leo_post_img: '![x](https://inleo.io/posts/a/b.png)',
  leo_thread_img: '![x](https://leofinance.io/threads/view/a/b.png)',
  leo_tab_stop: 'https://inleo.io/posts/a/b ![x](<https://example.com/c\td.png>)',
  angle_tab_mid: 'a\tb\n![x](<https://example.com/c\td.png>)',
  angle_tab_after: 'ab\t\n x\t![x](<https://example.com/c\t\td.png>)',
  angle_nbsp: '![x](<https://example.com/a\u00a0b.png>)',
  angle_line_sep: '![x](<https://example.com/a\u2028b.png>)',
  plain_escaped_paren: '![x](https://example.com/a\\).png)',
  plain_escaped_quote: '![x](https://example.com/a\\"b.png)',
  title_mismatch: '![x](<https://example.com/a b.png> "t\')',
  img_nbsp: '<img src="https://example.com/a\u00a0b.png">',
  img_tab: 'x\t<img src="https://example.com/a\tb\tc.png">',
  label_setext: '![a\n--\nb](<https://example.com/a b.png>)',
  label_table: '![a|b\n-|- \nc](<https://example.com/a b.png>)',
  label_list_indent: '10. ![a\n    --\n    b](<https://example.com/a b.png>)',
  byline_then_img: '![x](https://example.com/a.png) Posted using [LeoFinance](https://leofinance.io)\n\n![y](https://example.com/b.png)',
}
// Card and post image bodies of 2.5.56 (#2176): the card reads the text the parser reads (the render's input pass,
// then Remarkable's), and its markdown image pattern follows the parser's separators, escapes and block starts.
const angleDestinations2556 = ['a b', 'a\\b', '50%', '50%%20off', 'a%zz', 'a%20b', 'café', 'caf%C3%A9', '\ud800x', 'x\udc00 y', 'a"b',
  'a[1]', 'a{b}|c', 'a^b`c', 'a\tb', '\ta', 'ab\t\tc', 'a\u00a0b', 'a\u2028b', 'a\u0085b', 'a\u2424b']
const cardBodies2556 = [
  ...angleDestinations2556.map((d) => `![x](<https://example.com/${d}.png>)`),
  'a\tb\n![x](<https://example.com/c\td.png>)', 'ab\t\n x\t![x](<https://example.com/c\t\td.png>)', '\t\t\n![x](<https://example.com/\tc\td.png>)',
  '![](\u3000<https://example.com/a b.png>)', '![](<https://example.com/a b.png>\ufeff)', '![](<https://example.com/a.png>\u3000"t")',
  '![](\r<https://example.com/a b.png>)', '![](https://example.com/a.png\u3000)', '![](\u000b<https://example.com/a b.png>)',
  '![]( \n <https://example.com/a b.png> \n )', '![](<https://example.com/a b.png>\n\n)', '![x\n\ny](<https://example.com/a b.png>)',
  '![](<https://example.com/a b.png>\n \t\n)', '![](<https://example.com/a b.png>\u2028\u2028)',
  '![](https://example.com/a.png\u0085)', '![](https://example.com/a\u0085b.png)', '![](https://example.com/a\u007fb.png)',
  '![](https://example.com/a\u0001b.png)', '![](https://example.com/a\u2424b.png)',
  '<img src="https://example.com/a\u00a0b.png">', '<img src="https://example.com/a\tb.png">', 'x\t<img src="https://example.com/a\tb\tc.png">',
  'https://example.com/a.png\u0085text', 'text\u0085https://example.com/a.png', 'text\u00a0https://example.com/a.png',
  '![x](https://example.com/a\\).png)', '![x](https://example.com/a\\_b.png)', '![x](https://example.com/a\\\\b.png)',
  '![x](https://example.com/a\\(b.png)', '![x](https://example.com/a\\&amp;b.png)', '![x](https://example.com/a\\"b.png)',
  '![x](<https://example.com/a b.png> "t\')', "![x](<https://example.com/a b.png> 't\")",
  '![a\n# b](<https://example.com/a b.png>)', '![a\n> b](<https://example.com/a b.png>)', '![a\n- b](<https://example.com/a b.png>)',
  '> ![a\n>\n> b](<https://example.com/a b.png>)',
  '![x](https://inleo.io/posts/a/b.png)', '![x](https://leofinance.io/threads/view/a/b.png)', 'https://inleo.io/posts/a/b ![x](<https://example.com/c\td.png>)',
  '![x](https://example.com/\ud800x.png)', '<img src="https://example.com/\udc00x.png">',
  '![x](https://example.com/a\\ b.png)', '![x](https://example.com/a\\\nb.png)',
  '![a\n___\nb](<https://example.com/a b.png>)', '![a\n<div>b</div>](<https://example.com/a b.png>)',
  '![a\n--\nb](<https://example.com/a b.png>)', '![a|b\n-|- \nc](<https://example.com/a b.png>)', '![a|b\n:-|-:\nc](<https://example.com/a b.png>)',
  '![a\n: b](<https://example.com/a b.png>)', '![a\n~ b](<https://example.com/a b.png>)',
  '![a|\n:-:\nb](<https://example.com/a b.png>)', '![a|\n-:\nb](<https://example.com/a b.png>)', '|![a\n:-\nb](<https://example.com/a b.png>)',
  '10. ![a\n    --\n    b](<https://example.com/a b.png>)', '10. ![a\n    : b](<https://example.com/a b.png>)',
  '10. ![a|b\n    -|-\n    c](<https://example.com/a b.png>)', '10. ![a\n    # h\n    b](<https://example.com/a b.png>)',
  '- item\n  - ![a\n    ===\n    b](<https://example.com/a b.png>)', '1. - ![a|b\n     -|-\n     c](<https://example.com/a b.png>)',
  '![x](https://example.com/a.png) Posted using [LeoFinance](https://leofinance.io)\n\n![y](https://example.com/b.png)',
  // multi-line images the card still reads in fast mode
  '![Sunset over the bay\n2023 trip](https://example.com/a.png)', '![a\n-b](https://example.com/a.png)', '![a\n#b](https://example.com/a.png)',
  '![a\n1999-01 photo](https://example.com/a.png)', '![x](\n<https://example.com/a b.png>)', '![x](\nhttps://example.com/a.png)',
  '![a\n<3 you](https://example.com/a.png)', '![a\n--b](https://example.com/a.png)', '![a\n:b](https://example.com/a.png)',
  '![a\n~b](https://example.com/a.png)', '![a\n-:b](https://example.com/a.png)',
  // titles and separators around a plain destination, a stray escape and the length bound
  '![x](https://example.com/a.png "t")', '![x](https://example.com/a.png\n"t"\n)', '![x](https://example.com/a.png"t")',
  '![x](https://example.com/a.png \'t\n\nu\')', '![x](https://example.com/a.png "t\nu")', '![x](https://example.com/a.png\\)',
  '![x](https://example.com/50%\\\\x.png)', `![x](https://example.com/${'a'.repeat(2100)}.png) ![y](https://example.com/b.png)`,
  '![x](<https://example.com/a.png>\n"t")', '![x](<https://example.com/a.png>  \n  )', 'a\u00a0![x](https://example.com/a.png)',
  '<div>\n![a\n<p>b](https://example.com/a.png)\n</div>', '![a\n   <section>b](https://example.com/a.png)', '![a\n    <div>b](https://example.com/a.png)',
]
// Rules of 2.5.57 (#2178, #2179): cleanReply drops a whole-line [via Inbox] byline in any case; render bodies with a
// stray `%` beside an escape, bracketed labels and an escaped `!`. Kept out of FRAGMENTS.
const rules2557 = {
  inbox_byline: 'Hello\n<sub>Posted [via Inbox](https://example.com)</sub>',
  inbox_byline_upper: 'Hello\n<SUB>POSTED [VIA INBOX](https://example.com/x)</SUB>',
  inbox_bare: 'Hello\n[via Inbox](https://example.com)\nnext',
  inbox_empty_dest: 'Hello\n[via Inbox]()',
  inbox_prose: 'I got this [via Inbox] yesterday ![x](https://example.com/a.png)\nnext',
  inbox_no_link: 'Hello\nvia inbox, no link',
  stray_pct_escape: '![x](https://example.com/50%\\x.png)',
  stray_pct_escape_hex: '![x](https://example.com/50%\\x%41.png)',
  label_brackets: '![a [b] c](https://example.com/a.png)\n\n![y](https://example.com/b.png)',
  escaped_bang: '\\![a](https://example.com/a.png) ![c](https://example.com/b.png)',
  escaped_bang_url_label: '\\![https://example.com/a.png](https://example.com/a.png) ![d](https://example.com/b.png)',
  lone_surrogate_bare: 'https://example.com/\ud800x.png',
}
// Card and post image bodies of 2.5.57 (#2179), from the upstream spec's inputs with example hosts.
const cardBodies2557 = [
  '![x](https://example.com/50%\\x.png)', '![x](https://example.com/50%\\x%41.png)', '![x](https://example.com/50%\\_x.png)',
  '![x](https://example.com/50%\\\\_x.png)', '![x](https://example.com/50%\\.png)', '![x](https://example.com/50%25\\_x.png)',
  '![x](https://example.com/a\\_x%zz.png)', '![x](<https://example.com/50%\\x.png>)',
  '![a [b] c](https://example.com/a.png)\n\n![y](https://example.com/b.png)',
  '![a [b [c]] d](https://example.com/a.png) <img src="https://example.com/b.png">',
  '![a [b [c [d]]] e](https://example.com/a.png)\n\n![y](https://example.com/b.png)',
  '![a [b [c [d]]] e](https://example.com/a.png) <img src="https://example.com/b.png">',
  '![a [b [c]] d](https://example.com/a.png)', '![a [b] c](<https://example.com/a b.png>)',
  '![a [b\n] c](https://example.com/a.png)', '![a [b\n\nc]](https://example.com/a.png) ![d](https://example.com/b.png)',
  '\\![a](https://example.com/a.png) ![c](https://example.com/b.png)',
  '\\![a [b] c](https://example.com/a.png) ![d](https://example.com/b.png)',
  '\\![a \\[b](https://example.com/a.png) ![d](https://example.com/b.png)',
  'x \\![a [b] c](https://example.com/a.png)\n\n![d](https://example.com/b.png)',
  '\\\\![a [b] c](https://example.com/a.png) ![d](https://example.com/b.png)',
  '\\![https://example.com/a.png](https://example.com/a.png) ![d](https://example.com/b.png)',
  '\\![https://example.com/a.png](https://example.com/p) ![d](https://example.com/b.png)',
  '\\\\\\![a](https://example.com/a.png) ![c](https://example.com/b.png)',
  '![\\[1] my photo](https://example.com/page)\n\n![d](https://example.com/b.png)',
  '![a \\[b] c](https://example.com/page) ![d](https://example.com/b.png)',
  '![a [b\\] c](https://example.com/a.png) x](https://example.com/b.png)',
  '![a\\](https://example.com/page) b](https://example.com/b.png)',
  '![a \\] b](https://example.com/a.png)\n\n<img src="https://example.com/b.png">',
  '![a\\\nb](https://example.com/a.png)',
  '![a](<https://example.com/a b.png> "t\n[^1]: x")', '![a\n[^1]: b](<https://example.com/a b.png>)',
  '![a\n1234567890. b](<https://example.com/a b.png>)', '![a\n1234567890) b](<https://example.com/a b.png>)',
  '![a\n12345678901. b](https://example.com/a.png)',
  'https://example.com/\ud800x.png', 'x https://example.com/\udc00x.png\n\n![y](https://example.com/b.png)',
  `https://www.youtube.com/watch?v=${YT}\n\nhttps://example.com/\ud800x.png`,
  `https://example.com/\ud800x.png\n\nhttps://www.youtube.com/watch?v=${YT}`,
  '\\![a [b\n\n![c](https://example.com/b.png)', '\\![a [b] c](x) ![d](https://example.com/b.png)',
  'Hello\n<sub>Posted [via Inbox](https://example.com/a.png)</sub>\n\n![y](https://example.com/b.png)',
  `![x](https://example.com/${'a'.repeat(2100)}.png) ![y](https://example.com/b.png)`,
  // an escaped image whose text holds a URL: one the image pattern captures, one only the presence check sees
  '\\![a [b] https://example.com/x.png](https://example.com/a.png) ![d](https://example.com/b.png)',
  '\\![https://example.com/a.png](https://example.com/a b.png) ![d](https://example.com/b.png)',
]
// Card and post image bodies of 2.5.58 (#2180, #2182, #2183, #2185, #2191, #2194), from the upstream specs'
// inputs (card-and-summary-2182-2183.spec.ts, card-image-2185.spec.ts) with example hosts.
const cardBodies2558 = (() => {
  const A = 'https://example.com/a.png', B = 'https://example.com/b.png', C = 'https://example.com/c.png', D = 'https://example.com/d.png'
  const S = 'https://example.com/s.png', Z = 'https://z.example/z.png', LONG = `https://example.com/${'a'.repeat(2100)}.png`
  const TAIL = `\n\n<img data-src="${D}" src="${S}">`
  const H = 'https://i.ecency.com/p/2bP4pJr4wVimqCWjYimXJe2cnCgnAhYxiBtbXg5JBpk.png', W = 'https://i.ecency.com/600x400/https://example.com/a.png'
  return [
    '![x](<https://e.example/a.png?a=1&copy=2>)',
    '![x](https://e.example/a.png?a=1&copy=2)',
    '<img src="https://e.example/a.png?a=1&copy=2">',
    'https://e.example/a.png?a=1&copy=2',
    'https://example.com/a.png?name=cat(dog))',
    `[y]: ${A}\n\n![x](${B})`,
    `Read [the docs][y] first.\n\n[y]: ${A}\n\n![x](${B})`,
    `t[^1]\n\n[^1]: ${A}\n\n![x](${B})`,
    `*[HTML]: ${A}\n\n![x](${B})`,
    `[x](https://example.net/q "${A}") ![x](${B})`,
    `[see ${A} here](https://example.net/q) ![x](${B})`,
    `[see ${A} here](https://example.net/q)\n\n<img src="${B}">`,
    `[Image source ${A}](${A}) ![d](${B})`,
    `[x]((${A})) ![x](${B})`,
    `[x](${A}(${C})) ![d](${B})`,
    `<iframe src="https://example.net">${A}</iframe> ![x](${B})`,
    `<${A}?a=1&amp;b=2>\n\n![z](${B})`,
    `${A}?a=1&amp;amp;b=2\n\n![z](${B})`,
    `<a href="${A}?a=1&copy">${A}?a=1&copy</a>\n\n![z](${B})`,
    `${LONG}`,
    `${LONG} ![x](${B})`,
    `![x](${LONG}) <img src="${LONG}">`,
    `<${A}?a=1.>`,
    `<${A}?a=1,>`,
    `[x](<${A}>(${B})`,
    `[x](<${A}>x) ![d](${B})`,
    `[x](<${A}>"t") ![d](${B})`,
    `[x]( ${A}\n\n) ![d](${B})`,
    `[x](\n${A}\n\n"t") ![d](${B})`,
    `[x](<${A}>\n\n) ![d](${B})`,
    `[a\n\nb]( ${A}) ![d](${B})`,
    `[a\n\nb](\n${A}) ![d](${B})`,
    `[a\n\nb](${A}) ![x](${B})`,
    `[x][y](${A})\n\n[y]: https://example.net/z\n\n![x](${B})`,
    `[![x](https://example.com/${'a'.repeat(2100)}[.png)](${C}) ![d](${B})`,
    `![x](${LONG}) [x][y]\n\n[y]: ${B}`,
    `![x](${LONG}) [x](https://example.net/q "${B}")`,
    `![x](${LONG}) [see ${B} here](https://example.net/q)`,
    `![x](${LONG})\n\n*[HTML]: ${B}`,
    `![x](${LONG}) <iframe src="https://example.net">${B}</iframe>`,
    `_${A}_ ![x](${LONG})(${B})`,
    `| a | b |\n|---|---|\n| ![a|b](${LONG}) | x | ${B}`,
    // A bare URL before a markdown image that is not a line of prose.
    `    ${A}\n\n![x](${B})`,
    `<div>\n${A}\n</div>\n\n![x](${B})`,
    `[see\n${A}\nhere](https://example.net/q)\n\n![x](${B})`,
    `<${A}?a=1&amp;b=2>`,
    `<https://example.com/\ud800a.png>`,
    // A bare URL before a markdown image, even one that opens a line: the
    // scans cannot tell it from one the render shows as no image.
    `${A}\n\n![x](${B})`,
    `   ${A} ![x](${B})`,
    `[${A}](${A})\n\n![x](${B})`,
    `[x\n\n${A}\n\n![x](${B})`,
    `<center>${A}</center>\n\n![x](${B})`,
    `<div>text</div>\n<p> <br> </p>\n<center> ${A}</center>\n\n<center>![x](${B})</center>`,
    // Final review.
    `<img,src="${A}">\n\n![d](${B})`,
    `<img alt="x" :src="${A}">\n\n![d](${B})`,
    `<img data-src="${C}"src="${A}">`,
    `[${A}](<${A}>)\n\n![d](${B})`,
    `[${A}](<${A}>)\n\n${B}`,
    `![x](${LONG})\n\n\`c\` ${A}\n\n![d](${B})`,
    `<img alt="a src='${C}'" src="${A}">\n\n![x](${LONG})`,
    `\`c\` ${A}\n\n![x](${LONG}) ![d](${B})`,
    // Third review.
    `<img/src="${A}">\n\n![d](${B})`,
    `<img alt="x"src="${A}">\n\n![d](${B})`,
    `[x](https://example.net/q(${A}))\n${A}`,
    `<center [>\n${A}](https://example.net/q)\n\n![d](${B})`,
    `\`\`\`\ncode\n\n${A}\n\n![d](${B})`,
    `~~~~\n~~~\n${A}\n~~~\n~~~~\n\n![d](${B})`,
    `<center>${A}*</center>\n\n![d](${B})`,
    // Second review: a line that only looks like prose.
    `<center><a href="https://example.net/q">${A}</a></center>\n\n![d](${B})`,
    `\\[x\n${A}\n\n![x](${B})`,
    `[y]:\n${A}\n\n![d](${B})`,
    `[y]:\n[${A}](${A})\n\n![d](${B})`,
    `[x](https://example.net/q "t\n${A}")\n\n![d](${B})`,
    `[y]: https://example.net/q "t\n${A}"\n\n![d](${B})`,
    `t[^1]\n\n[^1]: note\n${A}\n\n![d](${B})`,
    `t[^1]\n\n[^1]: note\n\n    ${A}\n\n![d](${B})`,
    `\`text\n${A}\`\n\n![d](${B})`,
    `\`\`\`\`\n\`\`\`\n${A}\n\`\`\`\n\`\`\`\`\n\n![d](${B})`,
    `<iframe src="https://example.net">\n\n${A}\n\n</iframe>\n\n![d](${B})`,
    `<head>\n${A}\n</head>\n\n![d](${B})`,
    `<head>\n\n${A}\n\n</head>\n\n![d](${B})`,
    `<a href="https://example.net/q">\n${A}\n</a>\n\n![d](${B})`,
    `x]\n${A}\n\n![d](${B})`,
    `[text <span title="]">\n${A}](https://example.net/q)\n\n![d](${B})`,
    `\\![x](<${A}>(${C}))`,
    `\\![x](<${A}>x) ![d](${B})`,
    `[x]( ${A}(${C})`,
    `[x](\n${A}(${C})`,
    `<img data-src="${A}" src=""> ![x](${LONG})`,
    `![x](${LONG}) <img data-src="${B}" src="">`,
    `<img class="lazy" data-src="${A}" src="${B}">`,
    `![x](${LONG}) https://www.youtube.com/watch?v=dQw4w9WgXcQ\n\n![d](${B})`,
    `| a | b |\n|---|---|\n| ![a|b](${LONG}) | x | https://www.youtube.com/watch?v=dQw4w9WgXcQ`,
    // Regression rerun: a `;` after a bare URL, a `-src` after the first image.
    `[${A}&quot;][r]\n\n[r]: ${A}&quot;`,
    `[${A}&amp;][r]\n\n[r]: ${A}&amp;`,
    `[${A}&copy;][r]\n\n[r]: ${A}&copy;`,
    `\`c\` <img\nsrc="${A}&quot;">`,
    `![x](https://example.com/${'a'.repeat(1984)}.png)\n\n<img data-src="${C}">`,
    `![x](${A})\n\n<img data-src="${C}" src="${B}">`,
    `${A}&foo;`,
    `${A}\\&amp;`,
    `x ${A}&quot; y`,
    `~~${A}&quot;~~~~`,
    `${A}&foo;\n\n![d](${B})`,
    `![x](https://example.com/${'a'.repeat(2014)}${'%41'.repeat(10)}.png) ![d](${B})`,
    // Reserved escapes, a legacy host's encoded inner URL: shown although long as written.
    `![x](https://example.com/x%2F%7e${'a'.repeat(2018)}.png) ![d](${B})`,
    `![x](https://example.com/%3Fq%3D%41%41${'a'.repeat(2013)}.png) ![d](${B})`,
    `![x](https://images.hive.blog/0x0/https%3A%2F%2Fexample.com%2F${'a'.repeat(1989)}.png)\n\n![d](${B})`,
    `![x](https://images.hive.blog/0x0/https%3A%2F%2Fexample.com%2F${'a'.repeat(2016)}.png)\n\n![d](${B})`,
    // A lone surrogate: no lookup throws.
    `![x](https://example.com/%20\ud800${'a'.repeat(2034)}.png) ![d](${B})`,
    // The candidate's whole line decides a closer, not the text before it.
    `\`\`\`\ncode\n\`\`\`js\nmore\n\`\`\`<img src="${B}">`,
    // A closer behind a container the opener was not in, or indented four.
    `\`\`\`\ncode\n\`\`\`js\n> \`\`\`\n![x](${A})`,
    `\`\`\`\ncode\n    \`\`\`\n![x](${A})\n\`\`\`\n\n![d](${B})`,
    `\`\`\`\ncode\n- \`\`\`\n![x](${A})\n\`\`\`\n\n![d](${B})`,
    `\`\`\`\ncode\n\`\`\`![x](${A})\n\`\`\`\n\n![d](${B})`,
    // Multi-byte escapes decode shorter than two per escape.
    `![x](https://example.com/%C3%A9${'a'.repeat(2023)}.png) ![d](${B})`,
    `![x](https://example.com/%E2%82%AC${'a'.repeat(2022)}.png) ![d](${B})`,
    `![x](https://example.com/${'%C3%A9'.repeat(100)}${'a'.repeat(1876)}.png) ![d](${B})`,
    `![x](https://example.com/${'%F0%9F%98%80'.repeat(20)}${'a'.repeat(1814)}.png) ![d](${B})`,
    // A closer indented up to three spaces closes whatever the opener's indent.
    `\`\`\`\na\n   \`\`\`\n\`\`\`\nb\n\`\`\`\n\`\`\`\n<img src="${B}">`,
    `\`\`\`\na\n   \`\`\`\n\`\`\`\nb\n\`\`\`\n\`\`\`\n![x](${A})`,
    `\`\`\`\n\n   \`\`\`\nx\n\`\`\`\n\`\`A\`\`\n\n![d](${B})`,
    // A fence holding a blank line, then inline code before the URL.
    `\`\`\`\ncode\n\n\`\`\`\n\`\`${A}\`\`\n\n![d](${B})`,
    // A list fence closed by its indented closer, then a fence the list
    // marker line does not close.
    `- \`\`\`\n  a\n  \`\`\`\n\n\`\`\`\n- \`\`\`\n![x](${A})`,
    // A marker indented four or by a tab is indented code, not an opener.
    `\t\`\`\`\n</pre>   \`\`\`\n    \t\`\`\`\n    \`\`\`\n![x](${A})\`\`\`\t\n`,
    `\t\`\`\`\n\n\n<code>--- \`\`\`\n\`\`\`\t\nhttps://youtu.be/dQw4w9WgXcQ`,
    `text\n\n    \`\`\`\n    indented code\n\n\`\`\`\n${A}\n\`\`\`\n\n![d](${B})`,
    `    \`\`\`\n\n\`\`\`\n![x](${A})`,
    `    \`\`\`\n\n\`\`\`\nhttps://youtu.be/dQw4w9WgXcQ`,
    // An info string holding a backtick opens nothing.
    `\`\`\` a\`b\n\n\`\`\`\n![x](${A})\n\`\`\`\n\n![d](${B})`,
    `~~~ a\`b\n\n~~~\n![x](${A})\n~~~\n\n![d](${B})`,
    // A list item's fence, then a marker under its indent.
    `1. x\n   \`\`\`\n   a\n\`\`\`\n![x](${A})\n\`\`\`\n\n![d](${B})`,
    // A src inside another attribute's value is not the image's src.
    `<img alt="x src='${A}'" src="${B}">`,
    `<img title='a src="${A}"' src="${B}">`,
    `<img alt="a > src='${A}'" src="${B}">`,
    // A tag the render shows as text reads nothing past its own text.
    `<img alt="a>b"\n\n![d](${B})\n\n<img src="${C}">`,
    `Intro text.\n\n<img alt="1 > 0" width="100"\n\n![d](${B})\n\nMore\n\n<img src="${C}">`,
    `<img alt="a>b"\n![d](${B}) <img src="${C}">`,
    `<img alt="a>b"\n\n${B}\n\n<img src="${C}">`,
    `<img alt="it's" title='a>b'\n\n![d](${B})\n\n<img src="${C}">`,
    `<img alt="<img src='${A}'>"src="${B}">`,
    `<img title="<img src='${A}'>"src="${B}">\n\n![d](${C})`,
    `<IMG=data-src = "${A}"\n\n<img src='${B}'>`,
    `<img='x src="${A}"'\n\n<img src="${B}">`,
    `<IMG = ">"x src='${A}'"`,
    `<img src="${A}"x="1"\n\n![d](${B})`,
    `<img src="${A}" alt="x"y\n\n![d](${B})`,
    `<img @a src="${A}">\n\n![d](${B})`,
    `<img 1x src="${A}">\n\n![d](${B})`,
    // Inline, a tag outside the grammar is text.
    `text <img src="${A}"x="1">\n\n![d](${B})`,
    `text <img @a src="${A}">\n\n![d](${B})`,
    // The first src decides, empty or not.
    `<img src="" src="${A}">\n\n![d](${B})`,
    // A tag across a blank line is text: the paragraph ends there.
    `<img alt="a>b"\n\n src="${A}">\n\n![d](${B})`,
    `<img alt="a>b"\n\n src="https://example.com/cover">\n\n![d](${B})`,
    `<img alt="x"\n\n src="https://example.com/cover">\n\n![d](${B})`,
    `<img alt="a\n\nb" src="${A}">\n\n![d](${B})`,
    `<img\n\nsrc="${A}">\n\n![d](${B})`,
    `<img alt="x"\n  \n src="${A}">\n\n![d](${B})`,
    // A line inside a tag that starts a block ends the paragraph (#2194).
    `<img alt="a\n---\nb" src="https://example.com/cover">\n\n![d](${B})`,
    `<img alt="a\n===\nb" src="https://example.com/cover">\n\n![d](${B})`,
    `<img alt="a\n# h\nb" src="https://example.com/cover">\n\n![d](${B})`,
    `<img alt="a\n* x\nb" src="https://example.com/cover">\n\n![d](${B})`,
    `<img alt="a\n> x\nb" src="https://example.com/cover">\n\n![d](${B})`,
    `<img alt="a\n<div>\nb" src="https://example.com/cover">\n\n![d](${B})`,
    `<img alt="a\n<table>\nb" src="https://example.com/cover">\n\n![d](${B})`,
    `<img alt="a\n\`\`\`\nb" src="https://example.com/cover">\n\n![d](${B})`,
    `> <img alt="a\n>\n> b" src="https://example.com/cover">\n\n![d](${B})`,
    `> <img alt="a\n> \n> b" src="https://example.com/cover">\n\n![d](${B})`,
    // A tag on a line that is a block of one line (heading, table row,
    // footnote) ends at its line break.
    `# <img alt="a\nb" src="https://example.com/cover">\n\n![d](${B})`,
    `## <img alt="a\nb" src="https://example.com/cover">\n\n![d](${B})`,
    `| h |\n|---|\n| <img alt="a\nb" src="https://example.com/cover"> |\n\n![d](${B})`,
    `[^1]: <img alt="a\nb" src="https://example.com/cover">\n\n![d](${B})`,
    // A definition, delimiter row or footnote line inside a tag.
    `<img alt="a\n: b" src="https://example.com/cover">\n\n![d](${B})`,
    `<img alt="x"\n:\n src="https://example.com/cover">\n\n![d](${B})`,
    `<img\n: src="https://example.com/cover">\n\n![d](${B})`,
    `<img alt="a | c\n:--|--:\nb" src="https://example.com/cover">\n\n![d](${B})`,
    `x | <img alt="a\n:--|--:\nb" src="https://example.com/cover">\n\n![d](${B})`,
    `<img alt="a\n[^1]: x\nb" src="https://example.com/cover">\n\n![d](${B})`,
    // Inside a footnote definition, a tag across any line break.
    `[^1]: <img alt="a\rb" src="https://example.com/cover">\n\n![d](${B})`,
    `[^1]: <img alt="a\r\rb" src="https://example.com/cover">\n\n![d](${B})`,
    `[^1]: <img alt="a\rb" src="https://example.com/cover"> ![d](${B})`,
    `[^1]: x\n<img alt="a\nb" src="https://example.com/cover">\n\n![d](${B})`,
    `[^1]: x\n  <img alt="a\nb" src="https://example.com/cover">\n\n![d](${B})`,
    // A fence marker with text after it closes nothing.
    `\`\`\`\n\`\`\` not a closer\n![x](${A})\n\`\`\`\n\n![d](${B})`,
    `~~~\n~~~ x\n![x](${A})\n~~~\n\n![d](${B})`,
    `\`\`\`\n\`\`\` x\n\`\`\`\n${A}\n\`\`\`\n\n![d](${B})`,
    `\`\`\`\n\`\`\` x\n\`\`\`\n\`a\`\n\`\`\`\n\n${A}\n\n![d](${B})`,
    // 2. Bare image URLs the scans never read.
    `https://example.com/a(1).png\n\n![d](${B})`,
    `_${A}_ ![x](${B})`,
    `==${A}== ![x](${B})`,
    `[${A}] ![x](${B})`,
    `x${A} ![x](${B})`,
    `=${A} ![x](${B})`,
    `\`x\` ${A}\n\n![d](${B})`,
    `text\n    ${A}\n\n![d](${B})`,
    `- x\n\n    ${A}\n\n![d](${B})`,
    `<a href="https://example.net/q">y\n${A}\n\n![d](${B})`,
    `<div\n${A}\n>x</div>\n\n![d](${B})`,
    // 3. A spaced HTML src.
    `<img src=" ${A} ">\n\n![z](${B})`,
    `<img src=" https://images.hive.blog/600x400/https%3A%2F%2Fe.com%2Fa.png "> ![b](${B})`,
    // 4. A video poster before a markdown image.
    `https://www.youtube.com/watch?v=dQw4w9WgXcQ ![x](${B})`,
    // 5. A URL the render shows as no image, nothing after it.
    `[x](https://example.net/q "${A}")`,
    `[see ${A} here](https://example.net/q)`,
    `[y]: ${A}`,
    `[^1]: ${A}`,
    `*[HTML]: ${A}`,
    `<iframe src="https://example.net">${A}</iframe>`,
    // 6. A legacy sized proxy URL written bare.
    `https://images.hive.blog/1536x0/https://files.example/x.png`,
    `https://images.hive.blog/1536x0/https://files.example/x.png\n\n![d](${B})`,
    // 7. Trailing characters.
    `${A}*`,
    `${A}_`,
    `${A}~`,
    `${A}'`,
    `${A}\\`,
    `${A}*\n\n![d](${B})`,
    `${A}'\n\n![d](${B})`,
    // An existing proxy URL over the proxy's length limit is kept as is.
    `![x](${PROXY_BASE}/p/${'Z'.repeat(2100)}) ![d](${B})`,
    `![x](${PROXY_BASE}/u/${'z'.repeat(2100)}.png) ![d](${B})`,
    // A bare URL over the limit, then another URL or image.
    `https://e.example/${'a'.repeat(2040)}.png\n\n${Z}`,
    `https://e.example/${'a'.repeat(2040)}.png\n\n[${Z}](${Z})`,
    `https://e.example/${'a'.repeat(2040)}.png\n\nhttps://youtu.be/abcdefghijk`,
    `https://e.example/${'a'.repeat(2040)}.png\n\n![z](${Z})`,
    `<div>\n\n${LONG}\n\n![d](${B})`,
    `<center>\n\n${LONG}\n\n![d](${B})`,
    // Image syntax the render does not show as the first image.
    `<img src=${C}>\n\n![x](${A})${TAIL}`,
    `https://youtu.be/abcdefghijk\n\n![x](${A})${TAIL}`,
    `[^1]: ![x](${A})\n\nt[^1]${TAIL}`,
    `[t](https://l.example/ "![x](${A})")${TAIL}`,
    `[r]: https://r.example/ "![x](${A})"\n\n[t][r]${TAIL}`,
    `<div title="![x](${A})">t</div>${TAIL}`,
    `<iframe src="https://example.net">![x](${A})</iframe>${TAIL}`,
    `<img alt="![x](${A})">${TAIL}`,
    `\`\`\`\ncode\n![x](${A})${TAIL}`,
    `[^1]: ![x](${A})\n\nt[^1]`,
    `[t](https://l.example/ "![x](${A})") ![d](${B})`,
    // A reference image after another image, or after text the scans cannot read.
    `[r]: ${A}\n\n![b](${B})\n\n![][r]`,
    `[r]: ${A}\n\n_${C}_ ![][r]`,
    // Third review: a `]:` that is not a definition, a duplicate label, a spaced reference.
    `Sources\n[1]: ${A}\n\n![d](${B})`,
    `Sources\n[1]: ${A}\n\n${B}`,
    `note]: ${A}\n\n![d](${B})`,
    `[r]: ${A} trailing words\n\n![d](${B})`,
    `[r]: ${A}<img src="${B}">`,
    `text\n*[HTML]: ${A}\n\n![d](${B})`,
    `![x] [r]\n\n[r]: ${A}\n\n![d](${B})`,
    `[r]: https://example.net/q\n[r]: ${A}\n\n![x][r]\n\n![d](${B})`,
    `![x][r]\n\n[r]: https://i.ecency.com/768x0/${A}\n\n![d](${B})`,
    // A video URL the render does not turn into a poster.
    `[video]: https://www.youtube.com/watch?v=dQw4w9WgXcQ\n\n![cover](${B})\n\nWatch the [video].`,
    `[Watch https://www.youtube.com/watch?v=dQw4w9WgXcQ here](https://example.net/q)\n\n![d](${B})`,
    `[x](https://example.net/q "https://www.youtube.com/watch?v=dQw4w9WgXcQ")\n\n![d](${B})`,
    `\`\`https://www.youtube.com/watch?v=dQw4w9WgXcQ\`\`\n\n![d](${B})`,
    // Bare URL contexts.
    `\`\`${A}\`\`\n\n![d](${B})`,
    `Use \`\`${A}\`\` as the URL.\n\n![d](${B})`,
    `'${A}' ![d](${B})`,
    `++${A}++\n\n![d](${B})`,
    `^${A}^\n\n![d](${B})`,
    `|${A}|x|\n|-|-|\n|y|z|\n\n![d](${B})`,
    `${A}\`![x](${B})`,
    `<a\nhref="https://example.net/q">${A}</a>\n\n![d](${B})`,
    `<div title="[${A}](${A})">x</div>\n\n![d](${B})`,
    `\\[${A}](${A})\n\n![d](${B})`,
    `<div><${A}>\n\n![d](${B})`,
    `<hr><${A}>\n\n![d](${B})`,
    `[^1]: ${A}\n\n![d](${B})`,
    `[r]: ${A}\n\n![r](${C})`,
    `<a href="https://example.net/q"><a href="${A}">${A}</a></a>\n\n![d](${B})`,
    `_${A}_ https://www.youtube.com/watch?v=dQw4w9WgXcQ`,
    // Second review round.
    `[r]: https://example.com/r.png\n\n![x](${A})`,
    `[r]: https://example.com/r.png\n\n${A}`,
    `https://i.ecency.com/768x0/${A}`,
    `<center>https://i.ecency.com/768x0/${A}</center>\n\n![d](${B})`,
    `[https://i.ecency.com/768x0/${A}](https://i.ecency.com/768x0/${A})`,
    `![x][é]\n\n[É]: ${A}\n\n![d](${B})`,
    `![x][foo\nbar]\n\n[foo bar]: ${A}\n\n![d](${B})`,
    `text\n[a]: https://example.net/q\n[r]: ${A}\n\n![d](${B})`,
    `[a]: https://example.net/q junk words\n[r]: ${A}\n\n![d](${B})`,
    `[r]: ${A}\n"title" junk\n\n![d](${B})`,
    `<div class="text-justify">\n![x][r]\n</div>\n\n[r]: ${A}\n\n![d](${B})`,
    `\\![x][r]\n\n[r]: ${A}\n\n![d](${B})`,
    `<div title="![x][r]">y</div>\n\n[r]: ${A}\n\n![d](${B})`,
    `[t](https://example.net/q "![x][r]")\n\n[r]: ${A}\n\n![d](${B})`,
    `[r]: ${A}\n[q]: https://example.net/q\n\n![x][q] ![y][r]\n\n![d](${B})`,
    `<a href="https://example.net/q"><a href="https://example.net/q">y</a>${A}</a>\n\n![d](${B})`,
    `<a title="[${A}](${A})" href="https://example.net/q">y</a>\n\n![d](${B})`,
    `<div>https://example.com/c.jpg</span>![x](${A})`,
    `<col>https://www.youtube.com/watch?v=dQw4w9WgXcQ</col>![d](${B})`,
    `[]( https://www.youtube.com/watch?v=dQw4w9WgXcQ ) ![d](${B})`,
    `<a href="https://example.net/q"><a href="https://example.net/q">y</a>https://www.youtube.com/watch?v=dQw4w9WgXcQ</a>\n\n![d](${B})`,
    `<a x="[https://www.youtube.com/watch?v=dQw4w9WgXcQ](https://www.youtube.com/watch?v=dQw4w9WgXcQ)">y</a><img src="${B}">`,
    `<?x <a href="https://www.youtube.com/watch?v=dQw4w9WgXcQ">https://www.youtube.com/watch?v=dQw4w9WgXcQ</a> ?>\n\n![d](${B})`,
    `</style>https://www.youtube.com/watch?v=dQw4w9WgXcQ</span>![y](${B})`,
    // Third review round.
    `${A}[x] ![d](${B})`,
    `${A}[1]: https://example.net/q\n\n![d](${B})`,
    `[]( [${A}](${A}) ) ![d](${B})`,
    `[]( [https://www.youtube.com/watch?v=dQw4w9WgXcQ](https://www.youtube.com/watch?v=dQw4w9WgXcQ) ) ![d](${B})`,
    `<blockquote>${A}</textarea>\n\n\`\`\`\n![x](${A})`,
    `![r]<pre>https://example.com/c.jpg</a>![y](${B})`,
    // Fourth review round: brackets after a bare URL.
    `${A}[ \n\n![d](${B})`,
    `${A}[x\ny]\n\n![d](${B})`,
    `${A}[[x]]\n\n![d](${B})`,
    `${A}[${'x'.repeat(120)}]\n\n![d](${B})`,
    `${A}[x][y]\n\n![d](${B})`,
    `${A}[x][]\n\n![d](${B})`,
    `${A}[x](\n\n![d](${B})`,
    `${A}[x](y z)\n\n![d](${B})`,
    `${A}[^1]\n\n![d](${B})`,
    `${A}[^1]\n\n[^1]: note\n\n![d](${B})`,
    `${A}[r]\n\ntext\n[r]: https://example.net/q\n\n![d](${B})`,
    `${A}[r]\n\n[r]: https://example.net/q junk words\n\n![d](${B})`,
    `${A}[r]\n\n\`\`\`\n[r]: https://example.net/q\n\`\`\`\n\n![d](${B})`,
    `${A}[r]\n\n[r]: https://example.net/q\n\n![d](${B})`,
    `${A}[x](https://example.net/q)\n\n![d](${B})`,
    `${A}[r]\n\n\`\`\`\n\n[r]: https://example.net/q\n\n\`\`\`\n\n![d](${B})`,
    `![x](https://example.com/${'a'.repeat(2044)}%20b.png) ![d](${B})`,
    `![x](https://example.com/${'a'.repeat(2044)}%C3%A9.png)\n\n![d](${B})`,
    `<center>${A}</center>\n\n![x](${B})`,
    `<center>\n\n${A}\n\n</center>\n\n![x](${B})`,
    `| a |\n|---|\n| ${A} |\n\n![x](${B})`,
    `${A}\n\n![x](${B})`,
    `intro\n\n${A}\n\n![x](${B})`,
    `${A}`,
    `<center>${A}</center>`,
    `<div>${A}</div>\n\n![x](${B})`,
    `<img src=" ${A} ">\n\n![x](${B})`,
    `<img alt="x src='${B}'" src="${A}">\n\n![x](${B})`,
    `<img xsrc="${B}" src="${A}">\n\n![x](${B})`,
    // A tag across lines that start nothing is read (real posts write these).
    `<img \nsrc="${A}">\n\n![x](${B})`,
    `<img \n src="${A}"/>\n\n![x](${B})`,
    // Code before the paragraph, or fenced in it, is not the URL's code.
    `\`x\`\n\n${A}\n\n![x](${B})`,
    `\`\`\`\nx\n\`\`\`\n${A}\n\n![x](${B})`,
    // A fence in a quote, closed behind the same prefix.
    `> \`\`\`\n> code\n> \`\`\`\n\n![x](${A})`,
    // A linked image.
    `<a href="${A}"><img src="${A}"></a>`,
    `<center><a href="https://example.com/full.jpg"><img src="${A}"></a></center>\n\n![x](${B})`,
    '![](https://images.ecency.com/DQmTest/thumbnail.png)',
    '![](https://images.hive.blog/DQmTest/a.png)',
    '<img src="https://steemitimages.com/DQmTest/a.png">',
    // #2191: a proxy URL is the same src bare or in markdown
    W, `![x](${W})`, `<center>${W}</center>\n\n![x](${B})`, H, `![x](${H})`, `<img src=" ${W} ">`, `<img src="${H} ">`,
    // #2182: an over-long first image, with and without a percent escape
    ...[2024, 2100, 3000].map((n) => `![x](https://example.com/${'a'.repeat(n)}.png) ![y](https://example.com/b.png)`),
    `![x](https://example.com/${'a'.repeat(2100)}%41.png) ![y](https://example.com/b.png)`,
    // #2185: a long bare URL, an IPFS one, as a link
    `https://ipfs.io/ipfs/QmTest/${'a'.repeat(2100)}.png\n\n![x](${B})`,
    // review of the port: reference labels JS folds differently from a simple upper case, a destination on the next
    // line, and images already on this proxy base (the specs' H and W use another host)
    `${A}[\u1f80]\n\n[\u1f88]: https://example.net/q`, `${A}[\u1fb3]\n\n[\u1fbc]: https://example.net/q`, `${A}[\u1f88]\n\n[\u1f88]: https://example.net/q`,
    `[x](\n${A}) ![d](${B})`, `[x](\n  ${A}) ![d](${B})`, `[x](  ${A}) ![d](${B})`,
    `![x](${PROXY_BASE}/p/2bP4pJr4wVimqCWjYimXJe2cnCgnAhYxiBtbXg5JBpk.png)`, `${PROXY_BASE}/p/2bP4pJr4wVimqCWjYimXJe2cnCgnAhYxiBtbXg5JBpk.png`,
    `<img src=" ${PROXY_BASE}/600x400/https://example.com/a.png ">`, `![x](${PROXY_BASE}/600x400/https://example.com/a.png)`,
    `<img src="${PROXY_BASE}/u/example-user/avatar/small">`, `${PROXY_BASE}/u/${'z'.repeat(2100)}.png`, `[https://ipfs.io/ipfs/QmTest/${'a'.repeat(2100)}.png](https://ipfs.io/ipfs/QmTest/${'a'.repeat(2100)}.png)\n\n![x](${B})`,
  ]
})()
// Rules of 2.5.58 (#2182, #2183, #2185, #2191): an image link the proxy refuses stays a link, an IPFS one too, and the
// render goes on; a spaced or legacy proxy src; one paragraph of many links (linkify rebuilds it once). Kept out of FRAGMENTS.
const rules2558 = {
  refused_bare: `https://example.org/${'a'.repeat(2040)}.png\n\nhttps://z.example/z.png`,
  refused_link: `[https://example.org/${'a'.repeat(2040)}.png](https://example.org/${'a'.repeat(2040)}.png) ![y](https://example.com/b.png)`,
  refused_ipfs: `https://ipfs.io/ipfs/QmTest/${'a'.repeat(2100)}.png\n\n![x](https://example.com/b.png)`,
  kept_proxy_long: `![x](https://images.ecency.com/p/${'Z'.repeat(2100)}) ![d](https://example.com/b.png)`,
  spaced_src: '<img src=" https://example.com/a.png ">',
  spaced_legacy_src: '<img src=" https://images.hive.blog/600x400/https%3A%2F%2Fexample.com%2Fa.png "> ![b](https://example.com/b.png)',
  proxy_bare_sized: 'https://i.ecency.com/600x400/https://example.com/a.png',
  proxy_bare_p: 'https://i.ecency.com/p/2bP4pJr4wVimqCWjYimXJe2cnCgnAhYxiBtbXg5JBpk.png',
  legacy_p: '![x](https://images.hive.blog/p/2bP4pJr4wVimqCWjYimXJe2cnCgnAhYxiBtbXg5JBpk?format=match&mode=fit)',
  legacy_p_bare: 'https://steemitimages.com/p/2bP4pJr4wVimqCWjYimXJe2cnCgnAhYxiBtbXg5JBpk?format=match&mode=fit',
  legacy_space: '![x](https://images.hive.blog/0x0/https://example.com/p/a%20b.png)',
  linkify_many: 'see https://example.com/a and www.example.com/b, or mail a@example.com\n\n' + '**x** user&#64;example&#46;com '.repeat(40),
  linkify_nested: '[outer [inner](https://example.com/i) https://example.com/text](https://example.com/o) https://example.com/end',
  linkify_html: '<a href="https://example.com/z">https://example.com/inside</a> https://example.com/after and a@b.co and https://example.com/(paren) and ftp://example.com/f',
  linkify_lists: '> quote https://example.com/q\n\n- item https://example.com/l\n- item2 x@y.io',
  // img() guards older than 2.5.58 the fixtures had not reached: an author's no-replace class, an author srcset off
  // the proxy, an HTML image the proxy refuses
  author_no_replace: '<img class="no-replace video-thumbnail" src="https://example.com/a.png">',
  foreign_srcset: '<img src="https://images.ecency.com/p/2bP4pJr4wVimqCWjYimXJe2cnCgnAhYxiBtbXg5JBpk" srcset="https://example.org/a.png 1x, https://images.ecency.com/p/x 2x">',
  kept_p_png: '![x](https://images.ecency.com/p/2bP4pJr4wVimqCWjYimXJe2cnCgnAhYxiBtbXg5JBpk.png)',
  kept_sized_html: '<img src="https://images.ecency.com/600x400/https://example.com/a.png">',
  refused_img_html: `<img src="https://example.org/${'a'.repeat(2040)}.png"> ![y](https://example.com/b.png)`,
}
for (const [name, body] of Object.entries(handcrafted)) bodies.push([name, body])
for (const [name, body] of Object.entries(bylines2542)) bodies.push([name, body])
for (const [name, body] of Object.entries(rules2552)) bodies.push([name, body])
for (const [name, body] of Object.entries(rules2554)) bodies.push([name, body])
for (const [name, body] of Object.entries(rules2555)) bodies.push([name, body])
for (const [name, body] of Object.entries(rules2556)) bodies.push([name, body])
for (const [name, body] of Object.entries(rules2557)) bodies.push([name, body])
for (const [name, body] of Object.entries(rules2558)) bodies.push([name, body])

// ---- render configurations ---------------------------------------------------------------------

const extraConfigs = [
  { name: 'embed', parentDomain: 'ecency.com', opts: { embedVideosDirectly: true } },
  { name: 'inert', parentDomain: 'ecency.com', opts: { inertAuthorAndTagChips: true } },
  { name: 'ext', parentDomain: 'example.org', opts: { externalProfileBase: 'https://ecency.com' } },
  { name: 'dims', parentDomain: 'ecency.com', opts: { preserveImageDimensions: true } },
  { name: 'seo', parentDomain: 'ecency.com', seo: { authorReputation: 55, postPayout: 12.5 }, opts: {} },
  { name: 'seolow', parentDomain: '', seo: { authorReputation: 40, postPayout: 5 }, opts: { embedVideosDirectly: true, inertAuthorAndTagChips: true } },
]

function render(md, forApp, parentDomain = 'ecency.com', seo, opts) {
  return rh.renderPostBody(md, forApp, false, parentDomain, seo, opts)
}

const renderCases = []
bodies.forEach(([name, md], i) => {
  renderCases.push({ name: `${name}_app`, md, forApp: true, parentDomain: 'ecency.com', seo: null, opts: {}, html: render(md, true) })
  renderCases.push({ name: `${name}_web`, md, forApp: false, parentDomain: 'ecency.com', seo: null, opts: {}, html: render(md, false) })
  const cfg = extraConfigs[i % extraConfigs.length]
  const forApp = i % 2 === 0
  renderCases.push({
    name: `${name}_${cfg.name}_${forApp ? 'app' : 'web'}`, md, forApp, parentDomain: cfg.parentDomain, seo: cfg.seo ?? null,
    opts: cfg.opts, html: render(md, forApp, cfg.parentDomain, cfg.seo, cfg.opts),
  })
})

// ---- seeded random combinations (hash only) ---------------------------------------------------

function prng(seed) {
  return function () {
    seed |= 0
    seed = (seed + 0x6d2b79f5) | 0
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
function fnv(s) {
  let h = 0x811c9dc5
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return (h >>> 0).toString(16)
}

const FRAGMENTS = [
  ...Object.values(handcrafted).filter((s) => s.length < 200),
  '\n', '\n\n', ' ', '**bold** ', '_em_ ', '> ', '- ', '1. ', '    ', '<center>', '</center>', '<div>', '</div>', '<p>', '</p>',
  '<b>', '</b>', '<br>', '<hr>', '| x | y |\n|---|---|\n', '```\n', '`', '<a href="', '">', '</a>', '![](', ')', '[', '](', '&amp;',
  '@example-user', '#tag', 'text ', 'https://', '/@', 'é', '🐝', '<img src="', '" />', '<iframe src="', '"></iframe>',
]
Object.assign(handcrafted, bylines2542)
Object.assign(handcrafted, rules2552)
Object.assign(handcrafted, rules2554)
Object.assign(handcrafted, rules2555)
Object.assign(handcrafted, rules2556)
Object.assign(handcrafted, rules2557)
const SEED = Number(process.env.SEED || 0x7a11)
const RANDOM_COUNT = Number(process.env.RANDOM_COUNT || 400)
const rnd = prng(SEED)
const pick = (arr) => arr[Math.floor(rnd() * arr.length)]
const randomCases = []
for (let i = 0; i < RANDOM_COUNT; i++) {
  const n = 2 + Math.floor(rnd() * 9)
  let md = ''
  for (let j = 0; j < n; j++) {
    const r = rnd()
    if (r < 0.35) md += pick(linkForms)(pick(urls))
    else md += pick(FRAGMENTS)
    if (rnd() < 0.3) md += pick([' ', '\n', '\n\n'])
  }
  const forApp = rnd() < 0.6
  const cfg = rnd() < 0.3 ? pick(extraConfigs) : null
  const html = render(md, forApp, cfg?.parentDomain ?? 'ecency.com', cfg?.seo, cfg?.opts ?? {})
  randomCases.push({ name: `random_${i}`, md, forApp, parentDomain: cfg?.parentDomain ?? 'ecency.com', seo: cfg?.seo ?? null, opts: cfg?.opts ?? {}, length: html.length, hash: fnv(html) })
}

// Entry overload (string body inside an object).
const entryCases = bodies.filter((_, i) => i % 25 === 0).map(([name, md]) => {
  const entry = { author: 'example-user', permlink: `p-${name}`, last_update: '2026-10-06T00:00:00', body: md, json_metadata: '{}' }
  return { name: `entry_${name}`, md, html: rh.renderPostBody(entry, true, false) }
})

// ---- postBodySummary ---------------------------------------------------------------------------

const summaryBodies = [
  ...bodies.filter((_, i) => i % 3 === 0),
  ['summary_long', 'Lorem ipsum dolor sit amet, consectetur adipiscing elit. '.repeat(10)],
  ['summary_urls', 'Visit https://example.com/page and ftp://files.example.com/x or http://x.y\nnext line'],
  ['summary_entities', 'Fish &amp; chips &nbsp; caf&eacute; &#169; &#x1F41D; &#0065; &#x0041;'],
  ['summary_spaces', 'a    b \r\n c\r d'],
  ['summary_html', '<div class="x"><p>Para <b>bold</b></p></div><img src="https://example.com/a.png">'],
  ['summary_word', 'Supercalifragilisticexpialidocious'.repeat(8) + ' tail'],
  ['summary_footer', 'Hello\n\nPosted using [Partiko iOS](https://partiko.app)'],
  ['summary_overlong', '&#' + '9'.repeat(400) + '; text'],
  ['summary_lt', 'a < b > c <not closed'],
  // 2.5.58 (#2182): a lone surrogate reads as U+FFFD instead of emptying the summary
  ['summary_surrogate_dest', 'text\n\n[y](<https://example.com/\ud800?q=1>)'],
  ['summary_surrogate_bare', 'https://example.com/\ud800x.png'],
  ['summary_surrogate_prose', 'see https://example.com/\udc00x.png now'],
]
const summaryCases = []
summaryBodies.forEach(([name, md], i) => {
  for (const [length, platform] of [[200, 'web'], [i % 2 ? 50 : 0, i % 3 ? 'ios' : 'android'], [120, 'web']]) {
    summaryCases.push({ name: `${name}_${length}_${platform}`, md, length, platform, expected: rh.postBodySummary(md, length, platform) })
  }
})

// ---- catchPostImage and raw URL lookups -------------------------------------------------------------

const metas = [
  '{}', '', 'not json', 'null', '123', '"str"', '[]',
  '{"image":["https://example.com/meta.png"]}', '{"image":"https://example.com/meta-string.jpg"}', '{"image":""}', '{"image":[]}',
  '{"image":[""]}', '{"image":[5]}', '{"image":[{"u":1}]}', '{"image":{"length":1,"0":"https://example.com/obj.png"}}', '{"image":5}',
  '{"image":["https://example.com/a.png?x=1&amp;y=2"]}', '{"image":["https://example.com/%E2%82%AC.png"]}',
  '{"thumbnails":["https://example.com/thumb.png"],"image":["https://example.com/meta.png"]}', '{"thumbnails":"https://example.com/t.jpg"}',
  '{"thumbnails":[" ", 7, "https://example.com/t2.png"]}', '{"thumbnails":["not a url"],"image":["https://example.com/m.png"]}',
  '{"thumbnails":{}}', '{"image":["https://images.hive.blog/0x0/https://example.com/x.gif"]}', '{"image":["https://example.com/' + 'a'.repeat(2100) + '.png"]}',
  '{"image":["javascript:alert(1)"]}', '{"image":["https://example.com/m.png"],"image":["https://example.com/dup.png"]}',
]
const imageBodies = [
  '![](https://example.com/md.png)', 'Text <img src="https://example.com/html.jpg"> more', 'https://example.com/bare.png',
  '[https://example.com/link.png](https://example.com/link.png)', 'https://www.youtube.com/watch?v=dQw4w9WgXcQ',
  '```\n![](https://example.com/code.png)\n```\n\nhttps://example.com/after.png', '`![](https://example.com/inline.png)`',
  '<!-- ![](https://example.com/comment.png) -->https://example.com/real.jpg', 'no images here', '',
  '![](https://example.com/a_(b).png)', '![](relative.png)', '<img src="data:image/png;base64,AA">', '![x](https://example.com/' + 'b'.repeat(2100) + '.png)',
  '<pre>https://example.com/pre.png</pre>\n\nhttps://example.com/next.png', '<div>\n<pre>https://example.com/blockpre.png</pre>\n</div>\n\nhttps://example.com/n2.png',
  '<code><pre>https://example.com/inline-pre.png</pre></code>', '    https://example.com/indented.png\n\nhttps://example.com/x.png',
  '> https://example.com/quote.png', '- <pre>https://example.com/list.png</pre>', '<a href="https://example.com/x">https://example.com/text.png</a>',
  '<a href="https://example.com/s.png">https://example.com/s.png</a>', 'https://youtu.be/dQw4w9WgXcQ then ![](https://example.com/later.png)',
  '[https://youtu.be/dQw4w9WgXcQ](https://youtu.be/dQw4w9WgXcQ)', '<style>https://example.com/style.png</style>https://example.com/ok.png',
  '![a](https://example.com/one.png "t") ![b](https://example.com/two.png)', '<img src=\'https://example.com/single.png\'>', '<IMG SRC="https://example.com/UP.PNG">',
  'x https://example.com/q.jpg?w=1&h=2 y', '(https://example.com/paren.png)', 'a=https://example.com/eq.png', '<center>https://example.com/c.png</center>',
  '[![](https://example.com/inner.png)](https://example.com/outer)', 'https://www.youtube.com/channel/UCx then https://youtu.be/dQw4w9WgXcQ',
  '<img src="https://example.com/a.png?x=1&amp;y=2">', 'https://3speak.tv/watch?v=example-user/abcdefgh', '![](https://example.com/%E2%82%AC.png)',
  'Text https://example.com/photo.PNG.', '![](ftp://example.com/x.png) https://example.com/y.png', '<div><img src="https://example.com/d.png"></div>',
  '* * *\n![](https://example.com/after-hr.png)', '<a href="x"><img src="https://example.com/in-a.png"></a>',
]
const sizes = [[0, 0], [600, 500]]
const imageCases = []
let imgIdx = 0
for (const body of imageBodies) {
  for (const meta of [metas[imgIdx % metas.length], '{}']) {
    const entry = { author: 'example-user', permlink: `img-${imgIdx}`, last_update: '2026-10-06T00:00:00', body, json_metadata: meta }
    for (const [w, h] of sizes) {
      for (const fast of [false, true]) {
        imageCases.push({ name: `img_${imgIdx}_${w}_${fast ? 'fast' : 'full'}`, kind: 'entry', body, meta, w, h, fast,
          expected: rh.catchPostImage(entry, w, h, 'match', { fast }) })
      }
    }
    imageCases.push({ name: `raw_${imgIdx}`, kind: 'raw', body, meta, w: 0, h: 0, fast: false, expected: rh.getEntryImageRawUrl(entry) })
    imageCases.push({ name: `card_${imgIdx}`, kind: 'card', body, meta, w: 0, h: 0, fast: false, expected: rh.getEntryCardImageRawUrl(entry) })
    imgIdx++
  }
}
for (const meta of metas) {
  const entry = { author: 'example-user', permlink: `meta-${imgIdx}`, last_update: '2026-10-06T00:00:00', body: 'https://example.com/body.png', json_metadata: meta }
  imageCases.push({ name: `meta_${imgIdx}`, kind: 'entry', body: entry.body, meta, w: 0, h: 0, fast: false, expected: rh.catchPostImage(entry) })
  imageCases.push({ name: `metaraw_${imgIdx}`, kind: 'raw', body: entry.body, meta, w: 0, h: 0, fast: false, expected: rh.getEntryImageRawUrl(entry) })
  imageCases.push({ name: `metacard_${imgIdx}`, kind: 'card', body: entry.body, meta, w: 0, h: 0, fast: false, expected: rh.getEntryCardImageRawUrl(entry) })
  imgIdx++
}
imageBodies.forEach((body, i) => {
  imageCases.push({ name: `str_${i}`, kind: 'string', body, meta: null, w: 0, h: 0, fast: false, expected: rh.catchPostImage(body) })
  imageCases.push({ name: `strfast_${i}`, kind: 'string', body, meta: null, w: 300, h: 0, fast: true, expected: rh.catchPostImage(body, 300, 0, 'match', { fast: true }) })
  imageCases.push({ name: `strraw_${i}`, kind: 'rawstring', body, meta: null, w: 0, h: 0, fast: false, expected: rh.getEntryImageRawUrl(body) })
})
// 2.5.53 (#2155): a bare image URL with a query inside parentheses, on the card and the post image.
;['(https://host.example/photo.jpg?size=1)', '<div>(https://host.example/photo.jpg?size=1)</div>', '<p>(https://host.example/photo.jpg?size=1)</p>'].forEach((body, i) => {
  const entry = { author: 'example-user', permlink: `paren-${i}`, last_update: '2026-11-20T00:00:00', body, json_metadata: '{}' }
  imageCases.push({ name: `paren_${i}`, kind: 'entry', body, meta: '{}', w: 0, h: 0, fast: false, expected: rh.catchPostImage(entry, 0, 0, 'match') })
  imageCases.push({ name: `parencard_${i}`, kind: 'card', body, meta: '{}', w: 0, h: 0, fast: false, expected: rh.getEntryCardImageRawUrl(entry) })
  imageCases.push({ name: `parenraw_${i}`, kind: 'raw', body, meta: '{}', w: 0, h: 0, fast: false, expected: rh.getEntryImageRawUrl(entry) })
})
cardBodies2555.forEach((body, i) => {
  const entry = { author: 'example-user', permlink: `card2555-${i}`, last_update: '2026-11-21T00:00:00', body, json_metadata: '{}' }
  imageCases.push({ name: `c2555_${i}`, kind: 'entry', body, meta: '{}', w: 0, h: 0, fast: false, expected: rh.catchPostImage(entry, 0, 0, 'match') })
  imageCases.push({ name: `c2555sized_${i}`, kind: 'entry', body, meta: '{}', w: 600, h: 500, fast: false, expected: rh.catchPostImage(entry, 600, 500, 'match') })
  imageCases.push({ name: `c2555card_${i}`, kind: 'card', body, meta: '{}', w: 0, h: 0, fast: false, expected: rh.getEntryCardImageRawUrl(entry) })
  imageCases.push({ name: `c2555raw_${i}`, kind: 'raw', body, meta: '{}', w: 0, h: 0, fast: false, expected: rh.getEntryImageRawUrl(entry) })
})
cardBodies2557.forEach((body, i) => {
  const entry = { author: 'example-user', permlink: `card2557-${i}`, last_update: '2026-11-21T00:00:00', body, json_metadata: '{}' }
  imageCases.push({ name: `c2557_${i}`, kind: 'entry', body, meta: '{}', w: 0, h: 0, fast: false, expected: rh.catchPostImage(entry, 0, 0, 'match') })
  imageCases.push({ name: `c2557fast_${i}`, kind: 'entry', body, meta: '{}', w: 0, h: 0, fast: true, expected: rh.catchPostImage(entry, 0, 0, 'match', { fast: true }) })
  imageCases.push({ name: `c2557card_${i}`, kind: 'card', body, meta: '{}', w: 0, h: 0, fast: false, expected: rh.getEntryCardImageRawUrl(entry) })
  imageCases.push({ name: `c2557raw_${i}`, kind: 'raw', body, meta: '{}', w: 0, h: 0, fast: false, expected: rh.getEntryImageRawUrl(entry) })
  imageCases.push({ name: `c2557str_${i}`, kind: 'string', body, meta: null, w: 0, h: 0, fast: false, expected: rh.catchPostImage(body) })
  imageCases.push({ name: `c2557strfast_${i}`, kind: 'string', body, meta: null, w: 300, h: 0, fast: true, expected: rh.catchPostImage(body, 300, 0, 'match', { fast: true }) })
})
// 2.5.58: a body over 1,500 characters (a refused URL) is pinned on the full and fast lookups and the raw URL only.
;[...new Set(cardBodies2558)].forEach((body, i) => {
  const entry = { author: 'example-user', permlink: `card2558-${i}`, last_update: '2026-11-22T00:00:00', body, json_metadata: '{}' }
  imageCases.push({ name: `c2558_${i}`, kind: 'entry', body, meta: '{}', w: 0, h: 0, fast: false, expected: rh.catchPostImage(entry, 0, 0, 'match') })
  imageCases.push({ name: `c2558fast_${i}`, kind: 'entry', body, meta: '{}', w: 0, h: 0, fast: true, expected: rh.catchPostImage(entry, 0, 0, 'match', { fast: true }) })
  imageCases.push({ name: `c2558raw_${i}`, kind: 'raw', body, meta: '{}', w: 0, h: 0, fast: false, expected: rh.getEntryImageRawUrl(entry) })
  // a kept proxy URL over the limit: sized, the proxy will not take it again
  if (body.length > 1500 && body.includes(PROXY_BASE)) {
    imageCases.push({ name: `c2558sized_${i}`, kind: 'entry', body, meta: '{}', w: 600, h: 500, fast: false, expected: rh.catchPostImage(entry, 600, 500, 'match') })
    imageCases.push({ name: `c2558strsized_${i}`, kind: 'string', body, meta: null, w: 600, h: 500, fast: false, expected: rh.catchPostImage(body, 600, 500, 'match') })
  }
  if (body.length > 1500) return
  imageCases.push({ name: `c2558card_${i}`, kind: 'card', body, meta: '{}', w: 0, h: 0, fast: false, expected: rh.getEntryCardImageRawUrl(entry) })
  imageCases.push({ name: `c2558sized_${i}`, kind: 'entry', body, meta: '{}', w: 600, h: 500, fast: false, expected: rh.catchPostImage(entry, 600, 500, 'match') })
  imageCases.push({ name: `c2558str_${i}`, kind: 'string', body, meta: null, w: 0, h: 0, fast: false, expected: rh.catchPostImage(body) })
  imageCases.push({ name: `c2558strfast_${i}`, kind: 'string', body, meta: null, w: 300, h: 0, fast: true, expected: rh.catchPostImage(body, 300, 0, 'match', { fast: true }) })
})
cardBodies2556.forEach((body, i) => {
  const entry = { author: 'example-user', permlink: `card2556-${i}`, last_update: '2026-11-21T00:00:00', body, json_metadata: '{}' }
  imageCases.push({ name: `c2556_${i}`, kind: 'entry', body, meta: '{}', w: 0, h: 0, fast: false, expected: rh.catchPostImage(entry, 0, 0, 'match') })
  imageCases.push({ name: `c2556fast_${i}`, kind: 'entry', body, meta: '{}', w: 0, h: 0, fast: true, expected: rh.catchPostImage(entry, 0, 0, 'match', { fast: true }) })
  imageCases.push({ name: `c2556card_${i}`, kind: 'card', body, meta: '{}', w: 0, h: 0, fast: false, expected: rh.getEntryCardImageRawUrl(entry) })
  imageCases.push({ name: `c2556raw_${i}`, kind: 'raw', body, meta: '{}', w: 0, h: 0, fast: false, expected: rh.getEntryImageRawUrl(entry) })
  imageCases.push({ name: `c2556str_${i}`, kind: 'string', body, meta: null, w: 0, h: 0, fast: false, expected: rh.catchPostImage(body) })
  imageCases.push({ name: `c2556strfast_${i}`, kind: 'string', body, meta: null, w: 300, h: 0, fast: true, expected: rh.catchPostImage(body, 300, 0, 'match', { fast: true }) })
})
// Rendered bodies from the render set through the full lookup too.
bodies.filter((_, i) => i % 4 === 1).forEach(([name, body]) => {
  imageCases.push({ name: `body_${name}`, kind: 'string', body, meta: null, w: 0, h: 0, fast: false, expected: rh.catchPostImage(body) })
  imageCases.push({ name: `bodyraw_${name}`, kind: 'rawstring', body, meta: null, w: 0, h: 0, fast: false, expected: rh.getEntryImageRawUrl(body) })
})

// ---- simpleMarkdownToHTML and helpers ------------------------------------------------------

// Since 2.5.57 (#2178) simpleMarkdownToHTML reads a lone surrogate as U+FFFD, as the render does, instead of throwing.
const simpleBodies2557 = [
  ['surrogate_high_dest', 'text\n\n[y](<https://example.com/\ud800?q=1>)'],
  ['surrogate_low_dest', 'text\n\n[y](<https://example.com/\udc00?q=1>)'],
  ['surrogate_plain_dest', '[y](https://example.com/\ud800x)'],
  ['surrogate_autolink', 'see https://example.com/\udc00x and <https://example.com/\ud800>'],
  ['surrogate_text', 'a\ud800b'],
  ['surrogate_pair', 'a😀b'],
  ['surrogate_reversed', '\udc00\ud800 *x*'],
  ['surrogate_image', '![a\ud800](https://example.com/\ud800.png)'],
]
const simpleCases = bodies.filter((_, i) => i % 6 === 0).concat(simpleBodies2557)
  .map(([name, md]) => ({ name, input: md, expected: rh.simpleMarkdownToHTML(md) }))

const helperInputs = {
  extractYtStartTime: urls.filter((u) => /you/i.test(u)).concat(['not a url', 'https://x.com/?t=12abc', 'https://x.com/?t=+7', 'https://x.com/?t=%20%2012',
    'https://x.com/?t=0x', 'https://x.com/?t=1e21', 'https://x.com/?t=123456789012345678901234', 'https://x.com/?t=-0', 'https://x.com/?start=&t=3', 'https://x.com/?start=9']),
  isValidUsername: ['example-user', 'ab', 'abc', 'a.bc', 'abc.de', 'abc.def', 'Abc', 'abc-', '-abc', 'a1c', '1abc', 'abcdefghijklmnopq', 'abc..def', 'abc.d-f', 'ab--c', ''],
  sanitizePermlink: ['post', 'post?x=1', 'post#y', 'post?x#y', '', '?x', 'a/b'],
  isValidPermlink: ['example-post', 'Example', 'photo.png', 'post?x=1', 'a_b', 're-x-20260101t000000z', 'x.jpg?y', '', '-'],
  decodeImageSrc: ['https://example.com/a.png', ' https://example.com/a%20b.png ', 'https://example.com/%zz', '&amp;x=1', '&#0065;', '%E2%82%AC', '%E2%82'],
  stripHtmlTags: ['<b>x</b>', 'a <> b', 'a < b', '<<x>>', '<a href="x">y</a>tail<'],
  // 2.5.53 (#2155): an unbalanced `)` after a query or fragment is prose
  imageUrlEnd: ['https://host.example/photo.jpg?size=1)', 'https://host.example/photo.jpg)', 'https://host.example/photo.jpg#a)',
    'https://host.example/photo.jpg?a=(1)', 'https://host.example/photo.jpg?a=)&amp;b=(1)', 'https://host.example/photo.jpg?a=)&b=(1))',
    'https://host.example/photo.jpg?a=1))', 'https://host.example/photo.jpg?a=((1)', 'https://host.example/photo.jpg', 'https://host.example/a.png?x=1).'],
  // 2.5.55 (#2174): escaped delimiters and %25 stay escaped; a legacy sized proxy path still decodes whole
  decodeDomImageSrc: ['https://example.com/a%23b.png', 'https://example.com/a%20b.png', 'https://example.com/caf%C3%A9.png', 'https://example.com/a%2520b.png',
    'https://example.com/i.png?sig=ab%2Bcd%3D&u=a%26b', 'https://example.com/a%zz%23.png', ' https://example.com/a%2Fb.png ', 'https://example.com/%E2%82',
    'https://steemitimages.com/0x0/https%3A%2F%2Fexample.com%2Fa.png', 'https://images.hive.blog/640x0/https%3A%2F%2Fexample.com%2Fa%20b.png',
    'https://IMAGES.ECENCY.COM/0x0/HTTPS%3a%2f%2fexample.com%2Fa.png', 'https://i.ecency.com/10x20/https%3A//example.com/a%23.png',
    'https://steemitimages.com/0x0/https:%2F%2Fexample.com%2Fa.png', 'https://steemitimages.com/0x0/https://example.com/a%23b.png', '%25', '%2525', '%',
    // 2.5.58 (#2183): trimmed before the legacy branch is chosen
    ' https://images.hive.blog/600x400/https%3A%2F%2Fexample.com%2Fa.png ', '\thttps://steemitimages.com/0x0/https%3A%2F%2Fexample.com%2Fa%20b.png\n'],
  // 2.5.56 (#2176): the destination helpers the card and the render share
  plainDestination: ['https://example.com/café a%20b.png', 'a\\b"c<d>[e]^f`g{h|i}', 'https://example.com/ok.png', '50%%20off a', 'a%zz b', 'a\u00a0b', 'a\u2028b', '%',
    'a b%20c', 'café %E2%82', 'https://example.com/a%2Fb c'],
  withoutLoneSurrogates: ['a\ud800b\udc00c😀', '\udc00\ud800', 'plain'],
  youtubeVideoLinkClass: ['https://www.youtube.com/shorts/x', 'https://YOUTUBE.com/Shorts/x', 'https://youtu.be/x'],
  cleanReply: Object.values(handcrafted).filter((s) => /posted|via|watch on/i.test(s)).concat(['', 'a\n---\n\n\n*posted via lumen*\nb', '---\noriginally posted through [scrobble.life', 'x\n-----\t\n_Originally Posted Through [Scrobble.Life_',
    // 2.5.42: 3Speak separator lookahead and whole-line footer filter
    '---\n\u25B6[watch on 3speak.tv](https://3speak.tv/watch)', 'a\n----\n\n \t\u25B6\uFE0F\t[WATCH ON 3SPEAK.TV](HTTPS://3SPEAK.TV/WATCH?v=x)\u00A0\r\nb',
    'a\n---\n\u25B6 [Watch on 3speak.tv](https://3speak.tv/watch?v=a b)', 'a\n---\n\u25B6 [Watch on 3speak.tv](https://3speak.tv/watch?v=a/b',
    'a\n---\n\u25B6 [Watch on 3speak.tv](https://3speak.tv/watchlater?v=a/b))', 'a\n---\n\u25B6\uFE0F\uFE0F [Watch on 3speak.tv](https://3speak.tv/watch?v=a/b)',
    'a\n---\n\u25B6 [Watch on 3speak.tv](http://3speak.tv/watch?v=a/b)', '\u25B6 [Watch on 3speak.tv](https://3speak.tv/watch?v=a/b)\u2028',
    'a\n---\n\u25B6 [Watch on 3speak.tv](https://3speak.tv/watch?v=a/b) x\n\u25B6 [Watch on 3speak.tv](https://3speak.tv/watch?v=c/d)',
    'a\n<sub>posted via [hivesuite](x)</sub>\n<SUB>Posted Via [HIVESUITE](y)</SUB>\n<sub>posted via [hivesuite] (z)</sub>',
    'a\n_posted from liketu clips_ short video, trimmed\nshort video, trimmed\n_POSTED FROM LIKETU CLIPS_ - SHORT VIDEO, TRIMMED',
    // Two dashes are not a separator; three are.
    'a\n--\n*posted via lumen*\nb', 'a\n--\n\u25B6 [Watch on 3speak.tv](https://3speak.tv/watch?v=a/b)', '--\n*posted via lumen*',
    // 2.5.57 (#2179): a whole-line [via Inbox] byline in any case, with or without <sub> and "posted"
    'Hello\n<sub>Posted [via Inbox](https://example.com)</sub>', 'Hello\n[via Inbox]()', 'Hello\nvia inbox, no link',
    'I got this [via Inbox] yesterday ![x](https://example.com/a.png)\nnext', ' \t<SUB>\tPOSTED\t[VIA INBOX](https://example.com/x)\t</SUB> \r\nb',
    'a\nposted [via inbox](https://example.com/x)\nb', 'a\n[via Inbox](https://example.com/a b)\nb', 'a\n<sub>[via Inbox](x)</sub> tail\nb',
    'a\n<sub>Posted  [via Inbox](x)</sub>\nb', 'a\nPosted[via Inbox](x)\nb', 'a\n[via Inbox](x)\u00a0\nb', 'a\n[via Inbox](x)\r']),
}
const helperCases = []
for (const [fn, inputs] of Object.entries(helperInputs)) {
  for (const input of inputs) {
    const f = rh[`__${fn}`] ?? rh[fn]
    helperCases.push({ fn, input, expected: String(f(input)) })
  }
}
const linkifyCases = []
for (const [name, body] of Object.entries(handcrafted)) {
  for (const forApp of [true, false]) {
    linkifyCases.push({ name: `${name}_${forApp}`, input: body, forApp, inert: false, ext: null, expected: rh.__linkify(body, forApp, {}) })
  }
  linkifyCases.push({ name: `${name}_inert`, input: body, forApp: false, inert: true, ext: 'https://ecency.com', expected: rh.__linkify(body, false, { inertAuthorAndTagChips: true, externalProfileBase: 'https://ecency.com' }) })
}

// ---- write Kotlin ----------------------------------------------------------------------------

function k(s) {
  if (s === null || s === undefined) return 'null'
  let out = '"'
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i)
    const ch = s[i]
    if (c >= 0x20 && c < 0x7f && ch !== '"' && ch !== '\\' && ch !== '$') out += ch
    else if (ch === '\n') out += '\\n'
    else out += '\\u' + c.toString(16).toUpperCase().padStart(4, '0')
  }
  return out + '"'
}
function parts(items, render, size = 20) {
  const fns = []
  for (let i = 0; i < items.length; i += size) {
    fns.push(`    private fun part${fns.length}() = listOf(\n${items.slice(i, i + size).map((x) => '        ' + render(x) + ',').join('\n')}\n    )`)
  }
  return { list: fns.map((_, i) => `part${i}()`).join(' + ') || 'emptyList()', fns: fns.join('\n\n') }
}
const ktOpts = (o, seo) => `TOpts(${!!o.embedVideosDirectly}, ${!!o.inertAuthorAndTagChips}, ${k(o.externalProfileBase ?? null)}, ${!!o.preserveImageDimensions}, ` +
  `${seo ? seo.authorReputation.toFixed(2) : 'null'}, ${seo ? seo.postPayout.toFixed(2) : 'null'})`

mkdirSync(outDir, { recursive: true })
const header = '// GENERATED by tools/render/generate-transform-fixtures.mjs from @ecency/render-helper 2.5.42. Do not edit by hand.\n' +
  'package com.ecency.core.render.transform\n'

const rp = parts(renderCases, (c) => `RenderCase(${k(c.name)}, ${k(c.md)}, ${c.forApp}, ${k(c.parentDomain)}, ${ktOpts(c.opts, c.seo)}, ${k(c.html)})`)
writeFileSync(join(outDir, 'RenderCases.kt'), `${header}
internal class TOpts(
    val embedVideosDirectly: Boolean,
    val inertAuthorAndTagChips: Boolean,
    val externalProfileBase: String?,
    val preserveImageDimensions: Boolean,
    val authorReputation: Double?,
    val postPayout: Double?,
)

internal class RenderCase(val name: String, val markdown: String, val forApp: Boolean, val parentDomain: String, val opts: TOpts, val html: String)

internal object RenderCases {
    const val PROXY_BASE = ${k(PROXY_BASE)}

    val cases: List<RenderCase> by lazy { ${rp.list} }

${rp.fns}
}
`)

// Pathological shapes, hash only. Nesting past V8's stack makes render-helper fall back to the
// sanitized markdown; the native port has an explicit depth limit (Traverse.MAX_DEPTH = 5000).
const pathological = [
  ['tag_chain_3000', '', '#a', 3000, ''],
  ['tag_chain_7000', '', '#a', 7000, ''],
  ['span_nest_4000', '', '<span>', 4000, 'x' + '</span>'.repeat(4000)],
  ['span_nest_7000', '', '<span>', 7000, 'x' + '</span>'.repeat(7000)],
  ['mention_chain', '@', 'a.', 20000, 'a'],
  ['mention_repeat', '', '@a.', 10000, ''],
].map(([name, prefix, unit, count, suffix]) => {
  const t = Date.now()
  const html = render(prefix + unit.repeat(count) + suffix, true)
  console.log('pathological', name, Date.now() - t, 'ms')
  return { name, prefix, unit, count, suffix, length: html.length, hash: fnv(html) }
})

const randp = parts(randomCases, (c) => `RandomCase(${k(c.name)}, ${k(c.md)}, ${c.forApp}, ${k(c.parentDomain)}, ${ktOpts(c.opts, c.seo)}, ${c.length}, ${k(c.hash)})`)
const entp = parts(entryCases, (c) => `StringCase(${k(c.name)}, ${k(c.md)}, ${k(c.html)})`)
writeFileSync(join(outDir, 'RandomCases.kt'), `${header}
internal class RandomCase(val name: String, val markdown: String, val forApp: Boolean, val parentDomain: String, val opts: TOpts, val length: Int, val hash: String)

internal class StringCase(val name: String, val input: String, val expected: String)

internal object RandomCases {
    // SEED=${SEED} RANDOM_COUNT=${RANDOM_COUNT}
    val cases: List<RandomCase> by lazy { ${randp.list} }

${randp.fns}
}

internal class PathologicalCase(val name: String, val prefix: String, val unit: String, val count: Int, val suffix: String, val length: Int, val hash: String)

internal object PathologicalCases {
    val cases = listOf(
${pathological.map((c) => `        PathologicalCase(${k(c.name)}, ${k(c.prefix)}, ${k(c.unit)}, ${c.count}, ${k(c.suffix)}, ${c.length}, ${k(c.hash)}),`).join('\n')}
    )
}

internal object EntryCases {
    val cases: List<StringCase> by lazy { ${entp.list} }

${entp.fns}
}
`)

const sump = parts(summaryCases, (c) => `SummaryCase(${k(c.name)}, ${k(c.md)}, ${c.length}, ${k(c.platform)}, ${k(c.expected)})`)
const imgp = parts(imageCases, (c) => `ImageCase(${k(c.name)}, ${k(c.kind)}, ${k(c.body)}, ${k(c.meta)}, ${c.w}, ${c.h}, ${c.fast}, ${k(c.expected)})`)
const simp = parts(simpleCases, (c) => `StringCase(${k(c.name)}, ${k(c.input)}, ${k(c.expected)})`)
const helpp = parts(helperCases, (c) => `HelperCase(${k(c.fn)}, ${k(c.input)}, ${k(c.expected)})`, 40)
const linkp = parts(linkifyCases, (c) => `LinkifyCase(${k(c.name)}, ${k(c.input)}, ${c.forApp}, ${c.inert}, ${k(c.ext)}, ${k(c.expected)})`)
writeFileSync(join(outDir, 'OtherCases.kt'), `${header}
internal class SummaryCase(val name: String, val body: String, val length: Int, val platform: String, val expected: String)

/** [kind]: entry (catchPostImage on an entry), raw / card (getEntry(Card)ImageRawUrl), string, rawstring. */
internal class ImageCase(val name: String, val kind: String, val body: String, val meta: String?, val width: Int, val height: Int, val fast: Boolean, val expected: String?)

internal class HelperCase(val fn: String, val input: String, val expected: String)

internal class LinkifyCase(val name: String, val input: String, val forApp: Boolean, val inert: Boolean, val externalProfileBase: String?, val expected: String)

internal object SummaryCases {
    val cases: List<SummaryCase> by lazy { ${sump.list} }

${sump.fns}
}

internal object ImageCases {
    val cases: List<ImageCase> by lazy { ${imgp.list} }

${imgp.fns}
}

internal object SimpleCases {
    val cases: List<StringCase> by lazy { ${simp.list} }

${simp.fns}
}

internal object HelperCases {
    val cases: List<HelperCase> by lazy { ${helpp.list} }

${helpp.fns}
}

internal object LinkifyCases {
    val cases: List<LinkifyCase> by lazy { ${linkp.list} }

${linkp.fns}
}
`)

console.log(`render ${renderCases.length}, random ${randomCases.length}, entry ${entryCases.length}, summary ${summaryCases.length}, ` +
  `image ${imageCases.length}, simple ${simpleCases.length}, helper ${helperCases.length}, linkify ${linkifyCases.length}`)
