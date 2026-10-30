// Golden fixtures for the native port of the render-helper post transforms (traverse, a, img,
// iframe, text, linkify, clean-reply, markdown-to-html), postBodySummary, catchPostImage and the
// helpers, produced by @ecency/render-helper 2.5.42 itself. All inputs are synthetic: example
// accounts, example hosts and made-up ids. JS never runs in the app.
//
//   cd native/tools/render && npm ci
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
  ' stripHtmlTags as __stripHtmlTags, youtubeVideoLinkClass as __youtubeVideoLinkClass };\n')
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
for (const [name, body] of Object.entries(handcrafted)) bodies.push([name, body])
for (const [name, body] of Object.entries(bylines2542)) bodies.push([name, body])

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
// Rendered bodies from the render set through the full lookup too.
bodies.filter((_, i) => i % 4 === 1).forEach(([name, body]) => {
  imageCases.push({ name: `body_${name}`, kind: 'string', body, meta: null, w: 0, h: 0, fast: false, expected: rh.catchPostImage(body) })
  imageCases.push({ name: `bodyraw_${name}`, kind: 'rawstring', body, meta: null, w: 0, h: 0, fast: false, expected: rh.getEntryImageRawUrl(body) })
})

// ---- simpleMarkdownToHTML and helpers ------------------------------------------------------

const simpleCases = bodies.filter((_, i) => i % 6 === 0).map(([name, md]) => ({ name, input: md, expected: rh.simpleMarkdownToHTML(md) }))

const helperInputs = {
  extractYtStartTime: urls.filter((u) => /you/i.test(u)).concat(['not a url', 'https://x.com/?t=12abc', 'https://x.com/?t=+7', 'https://x.com/?t=%20%2012',
    'https://x.com/?t=0x', 'https://x.com/?t=1e21', 'https://x.com/?t=123456789012345678901234', 'https://x.com/?t=-0', 'https://x.com/?start=&t=3', 'https://x.com/?start=9']),
  isValidUsername: ['example-user', 'ab', 'abc', 'a.bc', 'abc.de', 'abc.def', 'Abc', 'abc-', '-abc', 'a1c', '1abc', 'abcdefghijklmnopq', 'abc..def', 'abc.d-f', 'ab--c', ''],
  sanitizePermlink: ['post', 'post?x=1', 'post#y', 'post?x#y', '', '?x', 'a/b'],
  isValidPermlink: ['example-post', 'Example', 'photo.png', 'post?x=1', 'a_b', 're-x-20260101t000000z', 'x.jpg?y', '', '-'],
  decodeImageSrc: ['https://example.com/a.png', ' https://example.com/a%20b.png ', 'https://example.com/%zz', '&amp;x=1', '&#0065;', '%E2%82%AC', '%E2%82'],
  stripHtmlTags: ['<b>x</b>', 'a <> b', 'a < b', '<<x>>', '<a href="x">y</a>tail<'],
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
    'a\n--\n*posted via lumen*\nb', 'a\n--\n\u25B6 [Watch on 3speak.tv](https://3speak.tv/watch?v=a/b)', '--\n*posted via lumen*']),
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
const header = '// GENERATED by native/tools/render/generate-transform-fixtures.mjs from @ecency/render-helper 2.5.42. Do not edit by hand.\n' +
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
