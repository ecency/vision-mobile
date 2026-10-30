// Inputs for BlockBuilderTest: real @ecency/render-helper HTML (renderPostBody(md, true, false),
// the app's call) for synthetic markdown, so the block builder is tested on the exact shapes it
// will see. No real user content.
//
//   cd native/tools/render && npm ci
//   node generate-block-inputs.mjs   # writes commonTest/.../blocks/BlockInputs.kt and commonMain/.../blocks/SampleHtml.kt
import { writeFileSync } from 'node:fs'
import { kt } from './kotlin-literal.mjs'
import { visibleWords } from './block-words.mjs'

globalThis.window = globalThis // browser-build behaviour, as in the app
const rh = await import('@ecency/render-helper')
rh.setProxyBase('https://images.ecency.com')

const out = process.argv[2] ?? new URL('../../shared/src/commonTest/kotlin/com/ecency/core/render/blocks/BlockInputs.kt', import.meta.url).pathname
const sampleWordsOut = new URL('../../shared/src/commonTest/kotlin/com/ecency/core/render/blocks/SampleHtmlWords.kt', import.meta.url).pathname
const sampleOut = new URL('../../shared/src/commonMain/kotlin/com/ecency/core/render/blocks/SampleHtml.kt', import.meta.url).pathname

// The demo post both apps show: one synthetic body that exercises every block kind.
const SAMPLE_POST = [
  '# Native renderer preview',
  'Hi @ecency, this is **bold**, *italic*, ~~struck~~ and `inline code` text with a [link](https://ecency.com) and a tag #hive.',
  '![Ecency logo](https://images.ecency.com/u/ecency/avatar/large)',
  '## Lists',
  '1. First\n2. Second\n   - nested a\n   - nested b\n3. Third',
  '> A quote over  \n> two lines',
  '```\nconst x = 1 < 2 && "a";\nconsole.log(x)\n```',
  '---',
  '| Coin | Price |\n|:-----|------:|\n| HIVE | 0.30 |\n| HBD | 1.00 |',
  '<center>Centered text</center>',
  '<div class="text-justify">Justified paragraph text that runs long enough to wrap over more than one line on a phone screen.</div>',
  'https://www.youtube.com/watch?v=dQw4w9WgXcQ',
  'https://ecency.com/hive-125125/@ecency/some-post',
  'Read [another post](https://ecency.com/hive-125125/@ecency/another-post) or visit https://ecency.com/c/hive-125125/created.',
  'مرحبا بالعالم',
  'Привет мир 🐝 你好 x<sup>2</sup> H<sub>2</sub>O',
].join('\n\n')

export const cases = {
  paragraphs: 'First   paragraph\nwraps here.\n\nSecond with a hard  \nbreak.',
  marks: 'Some **bold**, *italic*, ~~struck~~, `code`, <u>under</u>, <sub>2</sub>, <sup>3</sup> and ***both***.',
  headings: '# H1\n\n## H2\n\n### H3\n\n#### H4\n\n##### H5\n\n###### H6',
  mention: 'Hi @spike-user, thanks.',
  tag: 'Posted in #spiketag today.',
  community_tag_link: 'Join [our community](https://ecency.com/created/hive-123456).',
  community_link: 'Join [our community](https://ecency.com/c/hive-123456/created) or https://ecency.com/c/hive-123456/trending',
  profile_link: 'https://ecency.com/@spike-user/posts',
  post_link_inline: 'See https://ecency.com/hive-123456/@spike-author/spike-permlink for more.',
  post_link_named: 'Read [this post](https://ecency.com/hive-123456/@spike-author/spike-permlink).',
  author_link_named: 'Thanks to [my friend](https://ecency.com/@spike-user).',
  external_link: 'Visit [example](https://example.com/page?x=1&y=2) and https://example.org.',
  youtube: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ',
  youtube_start: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42',
  youtube_named: 'Watch [the clip](https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1m30s) now.',
  threespeak: 'https://3speak.tv/watch?v=spike-user/abcdefgh',
  youtube_short: 'https://www.youtube.com/shorts/dQw4w9WgXcQ',
  video_tag: '<video src="https://example.com/v.mp4" controls></video>',
  vimeo: 'https://vimeo.com/123456789',
  iframe: '<iframe src="https://player.vimeo.com/video/123456789" width="640" height="360" frameborder="0"></iframe>',
  witnesses: 'Vote https://hivesigner.com/sign/account-witness-vote?witness=spike-user&approve=1',
  proposal: 'Support [proposal 123](https://ecency.com/proposals/123).',
  phishy: '<div class="phishy">Danger zone</div>',
  text_justify: '<div class="text-justify">Justified paragraph text.</div>',
  text_right: '<div class="text-right">Right by class.</div>',
  text_center: '<div class="text-center">Centered by class.</div>',
  center: '<center>\n\n![centered](https://images.ecency.com/p/abc.png)\n\nCentered caption\n\n</center>',
  pull: '<div class="pull-left">\n\n![left](https://images.ecency.com/p/left.png)\n\n</div>\n\n<div class="pull-right">Right text</div>',
  data_align: '<p data-align="center">Aligned centre</p>\n\n<p data-align="right">Aligned right</p>',
  table: '| Coin | Price |\n|:-----|------:|\n| HIVE | 0.30 |\n| HBD | **1.00** |',
  table_no_head: '<table><tr><th>h</th><td>d</td></tr></table>',
  table_wide: '| a | b | c | d | e |\n|---|---|---|---|---|\n| 1 | 2 | 3 | 4 | 5 |',
  details_sanitized: '<details><summary>Spoiler</summary>Hidden</details>',
  hr: 'above\n\n---\n\nbelow',
  code_block: '```js\nconst x = 1 < 2 && "a";\n  indented()\n```',
  code_indented: '    four space\n    code',
  lists: '1. First\n2. Second\n   - nested a\n   - nested b\n3. Third',
  ordered_start: '3. three\n4. four',
  loose_list: '- one\n\n- two\n\n  second para',
  blockquote: '> A quote\n> over two lines\n>\n> > nested',
  image: '![alt text](https://images.ecency.com/p/abc.png)',
  image_bare: 'https://i.imgur.com/abc123.jpg',
  image_in_link: '[![linked](https://images.ecency.com/p/abc.png)](https://example.com/target)',
  image_in_text: 'before ![mid](https://images.ecency.com/p/mid.png) after',
  spans: '<span class="foo">span <span>nested</span> text</span>',
  html_block_in_inline: '<b>bold <div>block in bold</div> tail</b>',
  heading_with_div: '<h2><div class="phishy">Div in heading</div></h2>',
  entities: 'Fish &amp; chips &lt;tag&gt; &nbsp;nbsp &copy;',
  rtl: 'مرحبا بالعالم\n\nשלום עולם',
  wave_images: '![a](https://images.ecency.com/p/a.png)\n\n![b](https://images.ecency.com/p/b.png)\n\n![c](https://images.ecency.com/p/c.png)\n\ntext\n\n![d](https://images.ecency.com/p/d.png)',
  hive_uri: '[sign](hive://sign/op/WyJ2b3RlIix7fV0.)',
  unknown_tags: '<section><article>Inside <abbr title="x">abbr</abbr> <custom-el>custom</custom-el></article></section>',
  empty_paragraphs: '<p></p>\n\n<p> </p>\n\ntext\n\n<p><br></p>',
}

const rendered = Object.entries(cases).map(([name, md]) => [name, rh.renderPostBody(md, true, false)])
const entries = rendered.map(([name, html]) => `        ${kt(name)} to ${kt(html)},`)
const wordEntries = rendered.map(([name, html]) => `        ${kt(name)} to listOf(${visibleWords(html).map(kt).join(', ')}),`)

writeFileSync(out, `// GENERATED by native/tools/render/generate-block-inputs.mjs from @ecency/render-helper. Do not edit by hand.
package com.ecency.core.render.blocks

/** render-helper HTML for synthetic markdown, keyed by case name. */
object BlockInputs {
    val html: Map<String, String> = mapOf(
${entries.join('\n')}
    )

    /** The visible words of each case, from htmlparser2 (block-words.mjs), the independent text oracle. */
    val words: Map<String, List<String>> = mapOf(
${wordEntries.join('\n')}
    )

    operator fun get(name: String): String = html.getValue(name)
}
`)
console.log(`wrote ${entries.length} cases to ${out}`)

writeFileSync(sampleOut, `// GENERATED by native/tools/render/generate-block-inputs.mjs from @ecency/render-helper. Do not edit by hand.
package com.ecency.core.render.blocks

/** render-helper HTML of a synthetic demo post, so the apps show the whole HTML-to-blocks pipeline. */
object SampleHtml {
    const val MARKDOWN = ${kt(SAMPLE_POST)}
    const val POST = ${kt(rh.renderPostBody(SAMPLE_POST, true, false))}
}
`)
console.log(`wrote ${sampleOut}`)

writeFileSync(sampleWordsOut, `// GENERATED by native/tools/render/generate-block-inputs.mjs. Do not edit by hand.
package com.ecency.core.render.blocks

/** Visible words of [SampleHtml.POST] from htmlparser2 (block-words.mjs). */
object SampleHtmlWords {
    val POST: List<String> = listOf(${visibleWords(rh.renderPostBody(SAMPLE_POST, true, false)).map(kt).join(', ')})
}
`)
console.log(`wrote ${sampleWordsOut}`)
