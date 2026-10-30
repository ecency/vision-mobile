# Native post rendering

Post bodies are rendered natively. No JS engine, no WebView for text (#3575).

```
markdown --Markdown.render--> HTML --HtmlParser--> DOM --transforms--> DOM --Sanitizer--> HTML
                                                                         |
                                                       BlockBuilder --> List<Block> --> Compose / SwiftUI
```

- `render.markdown`: port of remarkable 2.0.1 with render-helper's options.
- `render.dom`, `render.sanitize`: DOM, xmldom-compatible parse and serialize, xss sanitizer.
- `render.transform`: port of render-helper's traverse handlers (links, images, iframes, text, mentions, tags, image proxy).
- `render.blocks`: native block model (`Blocks.kt`) drawn by the UI layers.

The HTML stages exist so every step can be compared byte for byte with `@ecency/render-helper`
(`native/tools/render`). Gate 1 runs the whole pipeline over a 500-post local corpus
(`node corpus.mjs fetch && node corpus.mjs render`, kept out of git) and must show cosmetic
mismatches only.

WebView is used only for third-party video players after the user taps a thumbnail.

## Custom rules (render-helper 2.5.42, all ported)

Every Ecency-specific rule of `@ecency/render-helper` and where the Kotlin port has it
(`render/methods` unless noted). Goldens: `TransformGoldenTest`; real content: the corpus run.

| Rule | Kotlin |
|---|---|
| App bylines and promo footers dropped before rendering (`cleanReply`): Partiko, Dapplr, LeoFinance, InLeo, Neoxian, First Context, StemGeeks, Bilpcoin, SportsTalkSocial, aeneas.blog, ProofOfBrain, Hypnochain, WeedCash, NaturalMedicine, MusicForLife, Truvvl, TravelFeed (.io and, 2.5.42, .com), dPorn, inji, Liketu, Liketu Speak (two-part match), Liketu Clips (2.5.42, two-part match), scrobble.life, Lumen, HiveSuite (2.5.42), old and new 3Speak footers (2.5.42: whole-line `▶ [Watch on 3speak.tv](https://3speak.tv/watch...)`), `[via apps from]` | `CleanReply` |
| The `---` separator right before a scrobble.life, Lumen or (2.5.42) 3Speak byline | `CleanReply.removeBylineSeparator` |
| Ad and banner removal: D.Buzz promo link, hive.engage banners (two forms), Actifit app badges | `CleanReply.TAIL_REMOVALS` |
| Internal link rewriting for whitelisted Hive frontends (posts, profiles, topics, communities, `ccc`), author and tag chips, inert chips, external profile base | `AMethod`, `Traverse`, `Consts.WHITE_LIST` |
| Video and audio links to embeds or thumbnails: YouTube (shorts, start time), 3Speak video and audio, Vimeo, Twitch, DTube, Spotify, SoundCloud, Loom, BitChute, Rumble, Brighteon, Odysee (watch and embed), LBRY, archive.org, Skatehive, Skatehype, Dapplr, Truvvl, Aureal, VIMM, BrandNewTube, Liketu audio, Twitter | `AMethod`, `IframeMethod`, `Consts` |
| Iframe allowlist with per-host embed paths | `sanitize/EmbedSrc`, `IframeMethod` |
| Image proxy (`/p/` base58, legacy sized URLs, srcset, format), image links promoted to images, IPFS | `ImageProxy`, `ImgMethod`, `AMethod` |
| Bare URL, `@mention` and `#tag` linkify, section anchors | `Linkify`, `Scanners` |
| SEO `rel` on external links, `dir="auto"` on blocks | `AMethod`, `Traverse` |
| Sanitizer whitelist (tags, attributes, classes) | `sanitize/SanitizeHtml`, `sanitize/Xss` |
| Summary and card image | `PostBodySummary`, `CatchPostImage` |

RN app rules outside render-helper (`src/components/postHtmlRenderer`): `<center>` to `text-center`
and span flattening are in `BlockBuilder`; the waves image grid is `BuildOptions.waves`; the waves
trailing video needs no rule because video links are always their own block (last in the body
stays last); Speak voice posts are below.

## Speak voice posts

When `json_metadata.speak.audio_url` is JS-truthy (`PostMetadata.speak`, read as RN reads it: RN never
plays that URL), the app renderer (`RenderHelperPostBodyRenderer`) applies RN's two replacements to
render-helper's HTML (`SpeakAudio.stripBodyAudio`: every `<audio>...</audio>`, then every anchor whose
href is a `cdn.liketu.com` audio file; hand-written ASCII case-insensitive scanners, because a JVM or
Kotlin/Native class range cannot stand in for JS `[\s\S]` over surrogate pairs) and appends
`Block.SpeakAudio(streamUrl, durationMs, peaks)`, so posts, comments and waves all get the player under
the body. `duration_ms` counts numeric strings, as JS `> 0` does; non-numeric `waveform_peaks` entries
are skipped (RN would draw NaN bars). Goldens: `tools/render/generate-speak-fixtures.mjs` (RN's
expressions run on render-helper output, emoji and backtracking cases, 400 seeded combinations),
`SpeakAudioTest`.

The player follows RN's `SpeakAudioPlayer` (`SpeakPlayerState`, `SpeakAudio`, `SpeakPlayback` in shared
code): a 40pt `$primaryBlue` play button, a 34pt waveform of `waveform_peaks` (bars at least 1.5 wide,
2 apart, at least 3 high, played bars blue, others `$borderColor`; a 4pt progress track without peaks)
that seeks on tap, and `m:ss` (the duration until the first play). The stream is
`https://ecency.com/api/speak-audio?author=..&permlink=..` (transcoded AAC) and exists only while the
clip plays: pausing releases it, resuming seeks back, a tap before the first play starts there, the end
rewinds, an error (also one reported while opening) returns to a paused, tappable state, and starting
a player pauses the one playing.

Players belong to the screen, keyed by stream URL (a newer render of the same clip refreshes its peaks
and duration): Android keeps them in the screen's stack entry (`SpeakPlayers`, given to
`ProvideSpeakPlayers` by the post and waves screens), iOS in the screen's state (`.speakPlayersScope()`
on the post, waves and demo screens). A playing clip keeps playing while its row scrolls off and back,
as RN keeps the body mounted; Android polls progress only while the row is on screen and catches up on
return. Everything stops, keeping its place, when the app pauses (Android `ON_PAUSE`, iOS scene not
active; RN `playInBackground={false}`), when the system pauses playback (audio focus lost, headphones
unplugged, an iOS interruption) and when the screen leaves: popped, covered by a pushed screen (after
the pop the clip resumes from where it paused; RN would keep a covered screen playing) or left by a tab
switch. Bodies without a screen scope (editor preview) own their player per row.

The body cleanup is linear: the anchor scanner builds its "next `>`, quote, host, extension, `</a>`"
tables once, so a hostile body cannot make it slow (V8 runs the same expressions in milliseconds;
`SpeakAudioTest.stripStaysFastOnPathologicalBodies`).

Android: Media3 ExoPlayer with speech audio attributes, audio focus and pause on headphones unplugged
(`SpeakAudioPlayer.kt`, tests `SpeakPlayerTest`). iOS: AVPlayer with the `.playback` session in
`.spokenAudio` mode, activated on play and released with `.notifyOthersOnDeactivation` when the last
stream goes (also a stream dropped without a release) (`SpeakAudioPlayerView.swift`, tests `SpeakPlayerTests`, `RendererDemoUITests`). Difference:
no lock-screen now-playing controls (RN registers them on iOS only to activate the audio session, which
the native player does directly).

## UI parity checklist (from the RN renderer, `src/components/postHtmlRenderer`)

Text
- Base: 16pt, `$primaryBlack`, not selectable. Long-press a paragraph opens the copy sheet with its plain text.
- Paragraph margins 6 top and bottom, 0 inside list items. List items margin-bottom 12.
- Headings h1 to h6: 24, 20, 18, 16, 15, 14pt, bold.
- Blockquote: 5px left border, 5px left margin and padding, `$darkIconColor`.
- Inline code: `$darkIconColor` background, editor font.
- Classes: `text-center` (also `<center>`), `text-justify`, `phishy` (red), `data-align` left, center, right. `pull-left` and `pull-right` are not honoured today.
- Spans are flattened (classes dropped).

Links (tap targets)
- `markdown-author-link`: inline chip with avatar and `@name`. Post: quick profile sheet. Comment: profile screen.
- `markdown-tag-link` and `markdown-community-link`: `#tag` chip. Community goes to the community screen, other tags to tag results (`data-filter`).
- `markdown-post-link`: post screen. With `data-is-inline="true"` it is a preview card instead.
- `markdown-external-link`: link sheet (copy, in-app browser after warning, open externally). `hive://` and `ecency://` go to the link processor. Waves URLs open the post screen.
- `markdown-video-link` and YouTube links: video thumbnail (`img.video-thumbnail`, else first metadata image), 16:9, play button. 3Speak uses 4:3 contain and `layout=mobile`.
- YouTube in posts plays inline; elsewhere it opens the player sheet.

Images
- Rounded 8, cover, placeholder `$primaryLightBackground` until loaded.
- Initial height from `metadata.image_ratios` when the proxied URL matches, else 16:9. After load: natural size, capped at content width, never upscaled.
- Inside a table cell: width = content width / cells in the row.
- Tap opens the gallery over every body image in document order. An image inside a link follows the link.
- GIF badge; GIFs animate only while visible in feeds.
- "Show images" off: placeholder of the same size, tap reveals. Bytes are never fetched while hidden.
- Waves: two or more consecutive images become a half-width 4:3 grid; a trailing video becomes a thumbnail under the body.

Tables
- Header cells bold 14pt on `$darkIconColor`, body cells on `$tableTrColor` with 0.5 `$tableBorderColor` borders, padding 10, centered.
- More than 3 header columns: horizontal scroll, each column at least content width / 3 - 12.

Embeds and audio
- Iframes: 3Speak and all comment embeds as thumbnails; other post iframes in the player.
- Speak audio (`metadata.speak.audio_url`): native player row with waveform, seek on tap, one active player at a time; inline audio tags are dropped.

Comments differ only in width (`window - 68`, minus 44 past depth 2), video thumbnails instead of inline players, a paragraph tap that toggles the comment menu, and low-rated comments hidden behind a reveal button.

## HTML to blocks (`BlockBuilder`)

`BlockBuilder.build(html)` / `build(html, BuildOptions(waves, metadataImage, proxyBase))` (or
`fromDocument(document[, options])`) turns render-helper's final HTML (`renderPostBody(body, true,
false)`) into `List<Block>`. It parses with the xmldom port, falls back to render-helper's repair
path, then to `LenientHtml` (a stack-based tree builder that keeps links and images); it never
throws. `BlockText.of(blocks)` gives the visible text (copy actions, fidelity tests).

Rules:
- Whitespace collapses as a browser displays it: runs become one space (the first space of a run
  keeps its element's marks and link), none at the start or end of a line. `pre` keeps its text,
  minus one leading and the trailing newlines.
- Block elements inside inline ones (a `div` inside `b`, an `h2` inside `p`) end the current
  paragraph; marks, link and heading level carry into the inner blocks.
- Images are always blocks: an image in the middle of text splits the paragraph.
- Paragraphs with no visible text are dropped; trailing line breaks are trimmed.
- Unknown tags contribute their children. Past an element depth of 120 the walk goes on
  iteratively without adding marks or styles, still keeping text, breaks, block boundaries, links,
  images, preview cards and embeds.
- Top-level containers are flattened so the lazy list stays lazy: their text blocks already carry
  the style, other blocks keep a one-block `Container` only when they need its alignment.
- `waves = true`: two or more consecutive image-only `<p>` (whitespace between) become one
  `ImageGrid`, at any depth (the RN app's regex runs over the whole body).
- `metadataImage`: thumbnail fallback (proxied) for video links without `img.video-thumbnail`,
  for iframes and for `<video>`, as the RN app uses the first metadata image.

### Safety (`SafeTarget`)

render-helper keeps authored `data-*` attributes, so every target is validated in one place. An
invalid value never drops text: the anchor's text stays, without a link (a chip becomes text).
- External: `http`/`https` with a host only. `hive://` and `ecency://` become `LinkTarget.AppUri`
  (new): the app's link processor handles them and must confirm any signing operation.
  `javascript:`, `data:`, `web+...:`, `//host`, `#frag`, relative paths: no target.
- Author: Hive account rules (3 to 16 chars, dot segments of 3+ that start with a letter, end
  alphanumeric, `[a-z0-9-]`, no `--`). Tag: lower-cased `[a-z0-9-]{1,40}` (render-helper links
  hashtags as `#[-a-z\d]+`). Filter: `[a-z_]{1,24}`, else dropped. Community: `hive-` + digits.
  Permlink: `[a-z0-9-]{1,256}`. YouTube id: `[A-Za-z0-9_-]{11}`; start time clamped to >= 0.
- Post preview cards (`data-is-inline="true"`) need a valid author and permlink (after the
  anchored `#@a/p` and `?query` handling); `href` kept only when http(s).
- Embeds (iframes, video links, `<video>`): the player URL must pass render-helper's
  `EmbedSrc.isAllowedEmbedSrc`. A video link with neither an allowed player nor a valid YouTube id
  shows its label text only. `<video>` with a non-allowlisted source is dropped as in the RN app
  (its fallback text shows).
- Images: `http`/`https` only. Thumbnails and posters must already be served by the image proxy
  (`images.ecency.com`, `i.ecency.com` or `proxyBase`); others are dropped for the metadata
  fallback, so the reader's device never fetches an author-chosen tracking URL.

| HTML | Block / Inline | LinkTarget on tap |
|---|---|---|
| `p`, `section`, `article`, other plain blocks, text directly in `body`/`div`/`td` | `Paragraph` | |
| `h1`..`h6` | `Heading(level)` | |
| `strong` `b` / `em` `i` `cite` / `del` `s` `strike` / `code` `kbd` / `sub` / `sup` / `u` `ins` / `mark` | `Text` marks Bold / Italic / Strike / Code / Sub / Sup / Underline / Highlight | |
| `span` and unknown inline tags | flattened into the surrounding text | |
| `br` | `LineBreak` | |
| `a.markdown-author-link` whose text is `@data-author` | `Mention(author)` chip | (chip) `Author` |
| `a.markdown-author-link` with other text | `Link` | `Author(data-author)` |
| `a.markdown-tag-link` whose text is `#data-tag` (any case) | `Tag(tag, data-filter)` chip | (chip) `Tag` |
| `a.markdown-tag-link` with other text | `Link` | `Tag(data-tag, data-filter)` |
| `a.markdown-community-link` | `Link` | `Community(data-community, data-filter)` |
| `a.markdown-post-link` `data-is-inline="true"` (no image inside) | `PostPreview(author, permlink, data-href)` | `Post` (card) |
| `a.markdown-post-link` otherwise | `Link` | `Post(author, permlink)`; anchored `#@a/p` and `?query` handled as linkDataParser; invalid: `External(data-href)` |
| `a.markdown-external-link` with `data-youtube` | `Link` | `Youtube(id, parseInt(data-start-time) or 0)` |
| `a.markdown-external-link`, `a.markdown-witnesses-link`, `a.markdown-proposal-link` | `Link` | `External(data-href)` or `AppUri` |
| any other `a` with `data-href`/`href` (e.g. `markdown-profile-link`) | `Link` | `External(href)` or `AppUri` |
| `a` without a (valid) target | its text | |
| `a.markdown-video-link` (also `-youtube`, `-speak`, `-vimeo`) | block: `Video(embedUrl, thumbnail, youtubeId, startSeconds)`, then any label text; embed = `data-embed-src`, else `data-video-href`, else inner iframe/first child `src` | `Youtube` or `Video` |
| `iframe` | `Video(src, metadata thumbnail, youtube id from /embed/)` | `Youtube` or `Video` |
| `video` (`src` or `source`) | `Video` only for an allowlisted player; fallback text shown | `Video` |
| `img` | `Image(src, alt)`; `link =` the anchor's target only when the image is the anchor's single child (else the gallery, as RN) | link, else gallery |
| `center`, `div.text-center` | `Container(align Center)` | |
| `div.text-right` | `Container(align End)` (the RN app ignores it) | |
| `div.text-justify` | `Container(justify)` | |
| `div.phishy` | `Container(phishy)` (red text) | |
| `data-align` left/center/right on `p`, `h*`, `div` | style on the text blocks; a `Container` when the element holds images or embeds | |
| `div.pull-left`, `div.pull-right`, plain `div` | transparent (not honoured, as in the RN app) | |
| `blockquote` | `Quote` | |
| `ul` / `ol` (`start`), `li` | `ListBlock(ordered, start, items)`; content between items joins the item above; a lone `li` is a one-item list | |
| `table`, `thead`/`tbody`/`tfoot`, `tr`, `th`/`td` | `Table(rows)`, `Cell(header = th)`; stray content in a row becomes a cell; outside rows it ends the table and later rows start a new one (document order kept) | |
| `details` / `summary` | `Details(summary, blocks)` (summary only as first child; render-helper's sanitizer strips both today) | |
| `pre` | `Code(textContent)` | |
| `hr` | `Rule` | |
| `script`, `style`, `source`, ... | dropped | |

Classes that need no rule: `img.markdown-img-link`, `img.no-replace`, `span.markdown-video-play`,
the `markdown-video-link-*` provider variants, `div.unsupported-iframe` (its text shows).

Differences from the RN app, on purpose: a named author/tag link keeps its text (RN replaced it
with an `@name` chip); `href`-only anchors open the link sheet (RN did nothing); `text-right`
aligns; unsafe targets are not tappable. Chips get surrounding spaces in both renderers, as RN.
Not done here: the waves trailing-video extraction (not needed, video links are blocks), the
3Speak `layout=mobile` rewrite (at tap, in the UI), YouTube portrait sizing, cards for a post link
without any author (RN would draw a card that does nothing on tap).

`Blocks.kt` additions: `LinkTarget.AppUri(uri)`. No other fields: render-helper's sanitizer strips
image sizes, `start`, `colspan` and task-list markup.

### Tests

- `BlockBuilderTest` and `BlockSafetyTest` (common: Android host and iOS) run on real
  render-helper output for synthetic markdown (`BlockInputs.kt`, from
  `tools/render/generate-block-inputs.mjs`, which also writes the demo post `SampleHtml.kt`).
- Text fidelity is a word-sequence comparison (case-insensitive). The expected words come from
  htmlparser2 (`tools/render/block-words.mjs`, independent of the Kotlin parser): committed for the
  synthetic inputs, and `node block-words.mjs corpus` writes `corpus/words.jsonl` for the corpus.
  Joined, split, lost or reordered words fail. `TextFidelity.diff` applies the same rules on the
  Kotlin DOM (iteratively) for hand-written snippets.
- `BlockCorpusTest` (Android host only) runs the local 500-post corpus when
  `tools/render/corpus/expected.jsonl` exists, and writes `shared/build/reports/block-corpus.txt`.
## Status per platform (renderer side, synthetic blocks)

Android = Compose (`androidApp/.../render`), iOS = SwiftUI with TextKit paragraphs (`iosApp/EcencyApp/Render`).
"Done" means implemented and checked on the emulator or simulator in light and dark mode; tests are
named where they exist (`PostBodyTest`, `PostRenderTests`, `RendererDemoUITests`, shared `BlocksTest`).

| Item | Android | iOS | Notes |
| --- | --- | --- | --- |
| Base text 16pt, `$primaryBlack`, not selectable | Done | Done | Line height: Android 1.32x (RN Android keeps font padding), iOS natural. |
| Paragraph margins 6, 0 in list items | Done | Done | |
| Headings 24 to 14, bold, UA margins (.67em to 2.33em) | Done | Done | |
| Lists: 1em margins, trimmed at item edges; marker box 30; li 12 apart; disc, circle, square | Done | Done | Matches RNRH `enableRemove*MarginIfNested`. |
| Blockquote: 5 margin, 5 `$darkIconColor` border, 5 padding; 1em top and bottom, 30 right | Done | Done | RN sets only the border color; quote text stays `$primaryBlack`. |
| Inline code background | Done | Done | Uses `$darkIconColor` at 50% (RN: 100%). |
| `text-center`, `data-align` | Done | Done | |
| `text-justify` | Partial | Done | Android justifies, except paragraphs with chips (Compose places inline content at the unjustified offset); RN Android never justifies. |
| `phishy` red | Done | Done | |
| `@user` chip: hairline `$iconColor` pill, 18 high, 12pt link label, round small avatar | Done | Done | Placeholder disc until the avatar loads. iOS draws the chip into an `NSTextAttachment` image and redraws once `AvatarCache` has the avatar. PostBodyTest, PostRenderTests. |
| `#tag` chip | Done | Done | |
| Link taps map to `LinkTarget` | Done | Done | iOS resolves taps itself (TextKit hit test, custom attribute), never via UIKit link handling. |
| Paragraph long-press gives plain text | Done | Done | Shared `plainText()`. PostBodyTest, RendererDemoUITests. |
| Images: rounded 8, cover, `$primaryLightBackground` until loaded, natural size capped | Done | Done | `metadata.image_ratios` initial height: not yet (needs metadata in the block model). |
| Image tap opens gallery with every body image in order | Done | Done | Shared `imageUrls()`, computed only on tap. Images inside links (they follow the link) and hidden, unrevealed images are left out. |
| GIF badge | Done | Done | Android: Coil animated decoder registered in `MainActivity`. iOS: ImageIO frames into an animated `UIImage` in a `UIImageView`. GIFs animate always; pausing off screen in feeds is not done. |
| "Show images" off: same-size placeholder, tap reveals one image | Done | Done | `PostActions.showImages`; reveals are session scoped by URL (`RevealedImages`). Body images, wave grid tiles and video thumbnails are never fetched before the tap (video keeps its play button). Tests count requests on both platforms. |
| "Show images" off: chip avatars | Fetched | Fetched | Deliberate: RN's UserAvatar ignores the setting too. Revisit as a product decision. |
| Tables: header bold 14 on `$darkIconColor`, cells `$tableTrColor`, 0.5 borders, padding 10, centred | Done | Done | |
| Table scrolls sideways above 3 header columns | Done | Done | |
| Dark theme | Done | Done | `PostTheme.Dark` / `.dark` from the system setting, background `$primaryBackgroundColor`. |
| Video thumbnail 16:9 (3Speak 4:3), play button | Done | Done | Inline YouTube player in posts: not yet. |
| Post preview card | Basic | Basic | Author and permlink only; RN shows title, image and summary from `links_meta`. |
| Details / spoiler | Done | Done | iOS summary is plain text (chips not separately tappable). |
| Waves image grid | Done | Done | Hidden mode supported. |
| Speak audio player | Done | Done | See "Speak voice posts". The renderer demo ends with a sample player (no stream). |
| Comment mode (narrower width, paragraph tap toggles menu, low-rated reveal) | Not started | Not started | |
| RTL paragraphs right aligned | Gap | Gap | Arabic sample renders left aligned on both. |
| Links and chips reachable by screen readers | Done | Done | Android: each LinkAnnotation and chip is its own clickable semantics node. iOS: one text per paragraph plus a VoiceOver custom action per link and chip. |
| System text size | Done | Done | Android: sp text, chips grow with the label. iOS: Dynamic Type through UIFontMetrics, chips grow, caches keyed by size category. |
| Chip long-press | Done | Done | Copies the paragraph (does not navigate). |
| Avatar URL only for valid Hive account names | Done | Done | Shared `isHiveAccountName()`; otherwise the chip has no avatar. |
| Image loading cost (iOS) | n/a | Done | `ImageLoader`: one fetch per URL (shared in-flight task), failures remembered 60 s, decoded images cached by URL and pixel width, decode off the main thread downsampled to display width, animated frames capped at 24 MB. Paragraph text and heights cached across row recycling (`PostTextCache`), chip images cached (`ChipAttachment.images`). Android relies on Coil's memory and disk caches. |

Preview app switches: Android `adb shell am start -n app.esteem.mobile.android/com.ecency.app.MainActivity --ez showImages false`;
iOS `xcrun simctl launch <udid> app.esteem.mobile.ios -showImages NO` (and `-skipBlocks N` to start lower in the sample).
Extra sample blocks for these checks live in `RendererSamples.extras` (GIF, five-column table, justified text, chips).
