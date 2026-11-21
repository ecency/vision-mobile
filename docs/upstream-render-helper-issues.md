# Upstream issues found while porting @ecency/render-helper

Found during the native port (oracle: `@ecency/render-helper` 2.5.41). Each item says what the
native port does instead, so the deviation is deliberate and tested. Status as of 2.5.56 under each.

## Iframe allow list is skipped by the sanitize-only fallback

**Where.** `markdownToHTML` (`methods/markdown-to-html.method.ts`) together with `sanitizeHtml`
(`methods/sanitize-html.method.ts`) and `iframe()` (`methods/iframe.method.ts`).

**What happens.** The embed host allow list for iframes lives only in the DOM transform
`iframe()`: it recognises known embed sources, rewrites them, and replaces any other iframe with
an "unsupported" placeholder. `markdownToHTML` runs that transform after parsing the body with
xmldom. When xmldom rejects the body, it sanitizes, repairs with htmlparser2 and parses again;
when that second parse also fails, it returns `sanitizeHtml(output)` without ever building a DOM,
so `iframe()` never runs.

`sanitizeHtml` itself does not check iframe hosts: its whitelist allows `src` and `sandbox` on
`<iframe>`, and the default xss URL filter on `src` removes script-like schemes, not foreign
hosts. So on that fallback path an author-written iframe keeps an arbitrary `https:` source and
an author-chosen `sandbox` value. Reaching the fallback only takes markup that both parsers
reject, which an author controls.

A sandbox value matters on its own: some tokens grant an embedded page abilities (top-level
navigation, form submission) that an unsandboxed cross-origin frame does not get by default.

The normal path has a smaller version of the same gap: `iframe()` leaves an author-supplied
`sandbox` untouched on most recognised embeds (only some branches overwrite it).

**Suggested upstream fix.** Enforce the policy in `sanitizeHtml`, which runs last on every path,
instead of only in the transform:

- in the `onTagAttr` hook, drop an iframe `src` unless `isAllowedEmbedSrc` accepts it (judging
  protocol-relative and `http:` sources by their `https:` form, since `iframe()` keeps those for
  allowed hosts);
- drop an iframe `sandbox` unless every token is one `iframe()` itself grants
  (`allow-scripts`, `allow-same-origin`, `allow-popups`);
- optionally, drop iframes entirely in the sanitize-only fallback, since none of them went
  through `iframe()`.

**Native port.** Implements the first two points in `HtmlSanitizer` (`render/sanitize`), so the
rule holds on the xmldom, repaired and sanitize-only paths alike. Effect on output that went
through `iframe()`: iframes of allowed hosts whose path the allow list rejects (a non-embed page
path, for example) lose their `src`, and author sandbox tokens beyond the three above are
dropped. Golden cases that differ for this reason are listed in `DomGoldenTest.securityDeviations`.

An iframe whose `src` the allow list rejects becomes, on the DOM paths, the placeholder `iframe()`
itself emits for unsupported embeds (`<div class="unsupported-iframe">(Unsupported <src>)</div>`):
the native traverse builds it right after `iframe()`, so it is processed exactly like one `iframe()`
built. The sanitize-only path builds no DOM and no placeholder: the sanitizer drops the refused `src`,
then the iframe left without one (its opening tag and a directly following `</iframe>`), as
render-helper 2.5.45 does. The pre-sanitize of the repair path leaves iframe sources to `iframe()`
and the final sanitizer, as render-helper does, so an allowed embed written in a form `iframe()`
normalizes (protocol-relative, `m.youtube.com`) still plays.

**Status (2.5.45, #1919): fixed upstream.** `sanitizeHtml` enforces the allow list and the sandbox grants on every path and drops an iframe left without a src; the port now matches it and lists no deviation.

## Embeds `iframe()` accepts but the embed allow list rejects

**Where.** `iframe()` (`methods/iframe.method.ts`) and `isAllowedEmbedSrc`
(`consts/embed-hosts.const.ts`).

**What happens.** The transform and the allow list encode the same providers with different
patterns, and some sources pass the first but not the second, for example:

- a Spotify `embed-podcast` URL: `SPOTIFY_EMBED_REGEX` accepts `/embed-podcast/...`, the allow list
  only `/embed/...`;
- a case variant of an embed path (`/EMBED/...`): the transform's patterns are case-insensitive,
  the allow list's path checks are not;
- a YouTube Shorts URL whose id is not 11 characters, and BitChute or Brighteon page URLs, which
  `iframe()` keeps as written.

In render-helper the sanitizer does not consult the allow list, so these iframes render; with the
allow list enforced they become the unsupported placeholder.

**Suggested upstream fix.** Derive both checks from one table of provider patterns, or make the
allow list accept what `iframe()` emits (and normalize case in `iframe()`).

**Native port.** Keeps the allow list as upstream defines it and shows the placeholder.

**Status (2.5.45): fixed upstream.** Every host has a path shape (Spotify `embed-podcast` included), `iframe()` lower-cases `/EMBED/`, frames BitChute and Brighteon pages as their embed route, refuses a Shorts URL without an 11-character id, and `canonicalEmbedSrc` gives both checks one spelling. Ported.

## Unbounded nesting from re-linkified text

**Where.** `text()` and `traverse()` (`methods/text.method.ts`, `methods/traverse.method.ts`).

**What happens.** `text()` replaces a text node by a wrapper span holding the linkified HTML, and
`traverse()` then recurses into that span. When linkify only changes the start of the text (a run
of `#a#a#a...` links one tag per pass), the remainder becomes a new text node inside the new
span, so every tag adds a nesting level and re-processes the rest of the body: quadratic work, and
recursion one frame per level. Around 5,000 levels V8 overflows its stack, `markdownToHTML` catches
the RangeError, retries on the repaired body, overflows again and returns the sanitized markdown.
A 60 KB body of tags takes several seconds before that happens.

**Suggested upstream fix.** Linkify the whole text node in one pass (or loop over the remainder
instead of recursing), and give `traverse()` an explicit depth limit.

**Native port.** Traverses iteratively and tracks the a/code/pre and chip ancestry while
descending (no per-node ancestor walk), and imposes `Traverse.MAX_DEPTH = 5000`: deeper than that it
throws and takes the same sanitize-only fallback. Bodies nested between 5,000 levels and V8's actual
limit (about 5,075 nested elements, about 5,450 chained tags in Node) render sanitize-only natively
but linkified in Node; Hermes has its own limit. Tested in `PathologicalRenderTest`.

**Status (2.5.45, #1920): fixed upstream.** Adjacent tag and mention runs are linked in one pass and traverse has a 2,000-level cap that flattens deeper subtrees to text. Ported (`Traverse.MAX_DEPTH` is now 2,000, no sanitize-only fallback).

## Deep nesting overflows the stack in the repair step

**Where.** The htmlparser2 repair path of `markdownToHTML` (dom-serializer is recursive).

**What happens.** Bodies nested a few thousand elements deep (around 2,500 to 5,000 in Node)
overflow the stack while serializing the repaired tree, and the result falls back to
sanitize-only output (with the iframe gap above). Not a security issue on its own.

**Native port.** Repairs iteratively and returns the repaired body with every element closed
(`DomGoldenTest.deepNestingPastTheJsStackIsRepaired`).

## An author's `class="no-replace"` skips the image proxy

**Where.** `img()` (`methods/img.method.ts`), with the sanitizer allowing any `class` on `<img>`.

**What happens.** `no-replace` is render-helper's own marker for the video thumbnails it builds,
but nothing strips it from author markup, so `<img class="no-replace" src="https://example.com/a.png">`
renders with that `src` and readers load it straight from the author's server (their IP addresses
go to the author). URLs over the proxy's 2,048 character limit are also left unproxied.

**Native.** The HTML stays identical to render-helper's. `BlockBuilder.image` loads body images
only through the image proxy and leaves out an image the proxy cannot serve
(`BlockBuilderTest.bodyImagesAlwaysLoadThroughTheProxy`). Upstream: ecency/vision-web#1999.

**Status (2.5.45, #1919): fixed upstream.** Author classes are limited to the formatting set, so `no-replace` is dropped and the image is proxied; the full render also holds every image to the proxy. The block layer's own guard stays.

## App render entity placeholders and render edges (#2154, #2155, #2165)

**Where.** `markdownToHTML` (`methods/markdown-to-html.method.ts`), `sanitizeHtml`, `imageUrlEnd` (`helper.ts`),
`DAPPLR_REGEX` and `isAllowedEmbedSrc`, `a()`'s in-site path guard.

**What happened.** Found while porting 2.5.52: the app render's zero-width-space placeholders could be spelled by
the author (`\u200B0\u200B`) or misplaced by the fenced-code scan (indented code, backticks paired across blocks,
a line that opens no fence), an angle-bracket destination (`[x](<url>)`, `[r]: <url>`) was decoded twice, a tab
after a placeholder landed on another tab stop, a refused iframe on the sanitize-only path left its fallback text
and close tag, a bare image URL with a query kept a closing `)` of the prose around it, and the Dapplr transform
and allow list disagreed on hosts such as `player.eu.dapplr.in`.

**Status (2.5.53, #2162; 2.5.54, #2166): fixed upstream, ported.** The placeholder delimiter is a character the body
does not hold (`EntityProtection.placeholderDelimiter`), tabs are expanded first as the parser would, code gets its
entities back from the parser's own code tokens (`Markdown.restoreCodeEntities`; `CodeRanges` is gone), angle
destinations keep their source undecoded (`InlineRules.undecodedAngleDestination`, read where the native links and
reference rules parse them instead of re-walking the source as upstream's wrappers do), refused iframes go whole
(`HtmlSanitizer.dropRefusedIframes`), `Helper.imageUrlEnd` drops an unbalanced trailing `)`, `DAPPLR_REGEX` takes any
label chain and the allow list also requires it. 2.5.54 rejects only the render's chosen delimiter (and a zero-width
space) in an in-site path (`RenderContext.placeholderDelimiter`, per render instead of upstream's module global, so
concurrent renders never share it). Goldens: `rules2554` in `generate-transform-fixtures.mjs`, the untransformed
cases in `generate-dom-fixtures.mjs`.

## Follow-ups to the entity and render edges (#2156 to #2160, #2167, #2169, #2170, #2174)

**What happened.** After 2.5.54: the card image missed angle-bracket image destinations and read bare URLs with
parentheses differently from the render's linkify; a lone surrogate in a destination emptied the whole body; angle and
plain destinations and autolinks could name different URLs; `HTTP://` hrefs lost their URL on the web sanitizer; on
the app render linkify stopped a URL at an entity placeholder; the LCP preload and card proxied a different URL than
the body for percent-escaped images, and the image decode turned escaped delimiters (`%23`, `%2F`) into live ones; the
sanitize-only path kept the text of a refused iframe nested in other iframes.

**Status (2.5.55, #2173): fixed upstream, ported.** `Helper.withoutLoneSurrogates` before the parse,
`InlineRules.plainForm` (since 2.5.56 `Helper.plainDestination`) for angle destinations and autolinks, `DecodedLinkify` (linkify over the decoded text, the
placeholders put back outside links), the lower-cased http(s) scheme in `HtmlSanitizer`, `Helper.decodeDomImageSrc`
(escaped delimiters and `%25` kept, legacy sized proxy paths still unwrapped; also the video poster),
`HtmlSanitizer.wholeIframeElements`, `worldmappin.com` in `Consts.WHITE_LIST`, and in `CatchPostImage` the angle image
destination, `proxifyFound`'s percent decode and the bare URL read as Autolinker reads it (`autolinkUrlEnd`, with its
word character table generated from the oracle by `tools/render/generate-card-tables.mjs`). The LCP preload
(`getEntryLcpImage`) has no native counterpart.

## Card and render read different text (#2176)

**What happened.** After 2.5.55 the card scanner still read the raw body, where the render's parser reads it after
cleanReply, the leofinance and inleo link rewrite and remarkable's block pre-pass (U+00A0 as a space, line separators
as newlines, tabs to their stops); its markdown image pattern skipped any whitespace around a destination, let a label
or title run across a blank line, closed a title with either quote and stopped a plain destination at `\)`. The card
then named another image than the body showed for such bodies.

**Status (2.5.56, #2175): fixed upstream, ported.** `CatchPostImage.parserInput` (with
`BlockParser.parserText`), `mdImageFind` after the new `MD_IMAGE_RE`, `opensBlockInside` and the plain destination's
escapes; the destination helpers live in `Helper` (`plainDestination`, `uriNormalized`, `internalizeLeoLinks`), shared
by the render and the card. The rare bodies the card still reads differently are upstream's open #2179; native follows
upstream's output for them.
