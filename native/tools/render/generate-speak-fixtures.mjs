// Golden fixtures for render.blocks.SpeakAudio: the RN app's Speak voice-post handling
// (src/components/postHtmlRenderer/postHtmlRenderer.tsx and speakAudioPlayer.utils.ts), applied to
// @ecency/render-helper output. The RN expressions are copied verbatim below. Inputs are synthetic.
//
//   node generate-speak-fixtures.mjs ../../shared/src/commonTest/kotlin/com/ecency/core/render/blocks/SpeakFixtures.kt
import { writeFileSync } from 'node:fs'
import { kt } from './kotlin-literal.mjs'

globalThis.window = globalThis
const rh = await import('@ecency/render-helper')
rh.setProxyBase('https://images.ecency.com')
const out = process.argv[2]
if (!out) throw new Error('usage: generate-speak-fixtures.mjs <output.kt>')

// postHtmlRenderer.tsx, `if (speak)` branch
const strip = (html) => html
  .replace(/<audio[\s\S]*?<\/audio>/gi, '')
  .replace(/<a[^>]*href="[^"]*cdn\.liketu\.com[^"]*\.(?:webm|mp3|m4a|ogg|wav)[^"]*"[^>]*>[\s\S]*?<\/a>/gi, '')

// speakAudioPlayer.utils.ts
const SPEAK_AUDIO_ENDPOINT = 'https://ecency.com/api/speak-audio'
const getSpeakAudioStreamUrl = (author, permlink) =>
  author && permlink ? `${SPEAK_AUDIO_ENDPOINT}?author=${encodeURIComponent(author)}&permlink=${encodeURIComponent(permlink)}` : ''
const formatDuration = (seconds) => {
  const s = Number.isFinite(seconds) && seconds > 0 ? Math.floor(seconds) : 0
  const m = Math.floor(s / 60)
  return `${m}:${(s % 60).toString().padStart(2, '0')}`
}
const playedBarCount = (barCount, progress) => {
  if (barCount <= 0) return 0
  const clamped = Math.max(0, Math.min(1, Number.isFinite(progress) ? progress : 0))
  return Math.min(barCount, Math.round(clamped * barCount))
}
const playbackProgress = (currentTime, duration) => {
  if (!Number.isFinite(duration) || duration <= 0) return 0
  return Math.max(0, Math.min(1, currentTime / duration))
}

const bodies = {
  liketu_md_link: 'Hello from my walk\n\n[Listen to the original voice](https://cdn.liketu.com/audio/example.webm)',
  liketu_bare: 'Text https://cdn.liketu.com/audio/example.mp3 after',
  liketu_query: '[voice](https://cdn.liketu.com/a/b.m4a?x=1) and [also](https://CDN.LIKETU.COM/a/c.OGG)',
  liketu_wav_and_text: 'one [a](https://cdn.liketu.com/a.wav) two [b](https://example.com/b.wav) three',
  liketu_image: 'Thumb ![](https://cdn.liketu.com/thumb/x.webp) stays',
  liketu_webp_link: '[x](https://cdn.liketu.com/audio/x.webp)',
  speak_audio_3speak: 'Clip https://audio.3speak.tv/play?a=example-user/clip1 end',
  html_audio: '<audio controls src="https://example.com/a.mp3"></audio> text <AUDIO>x</AUDIO>',
  unclosed_audio: 'before <audio src="https://example.com/a.mp3"> after',
  two_links: '[1](https://cdn.liketu.com/1.webm)\n\n#tag @example-user [2](https://cdn.liketu.com/2.webm)',
  plain: 'Nothing to strip here, just @example-user and #tag',
  video_and_voice: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ\n\n[voice](https://cdn.liketu.com/v.webm)',
}
const strips = Object.entries(bodies).map(([name, body]) => {
  const html = rh.renderPostBody(body, true, false)
  return `        StripCase(${kt(name)}, ${kt(html)}, ${kt(strip(html))}),`
})
// Raw HTML strings, applied without render-helper (the expressions alone).
const rawHtml = [
  '<a class="x" href="https://cdn.liketu.com/a.webm" title="t">label <b>b</b></a>tail',
  '<a href="https://example.com/x" data-href="https://cdn.liketu.com/a.webm">keep?</a>',
  '<a title="x>y" href="https://cdn.liketu.com/a.webm">t</a>',
  '<a href="https://cdn.liketu.com/a.webm">open',
  '<abbr href="https://cdn.liketu.com/a.webm">abbr</abbr></a>',
  '<a href="https://cdn.liketu.com.example.com/a.mp3x">x</a>',
  '<audio>\n<source src="x">\n</audio><audio></Audio>',
  '',
  // Astral characters (emoji) inside what [\s\S]*? must cross
  '<audio controls>\u{1F3A4} voice \u{1F41D}</audio> after',
  '<a href="https://cdn.liketu.com/a.webm">\u{1F3A4} listen \u{1F41D}</a> after',
  '<a title="\u{1F41D}" href="https://cdn.liketu.com/\u{1F3A4}.mp3?x=\u{1F41D}">\u{1F41D}</a>',
  '\u{1F41D}<AUDIO>\uD83D</AUDIO>\uDC1D<a href="https://CDN.liketu.COM/x.WAV">\uDC1D</A>',
  // Backtracking: the rightmost href=" is tried first, an earlier one can still match
  '<a href="https://cdn.liketu.com/a.webm" data-x=\'href="nope"\'>t</a>',
  '<a href="https://cdn.liketu.com/a.webm" href="https://example.com/x">t</a>',
  '<a href="https://cdn.liketu.com/x"><a href="https://cdn.liketu.com/y.ogg">in</a></a>',
  '<a href="https://cdn.liketu.com/.webm"x>no close',
  '<a href="cdn.liketu.com.webm">t</a><a href="x.webm cdn.liketu.com">u</a>',
  // Rightmost href=" first: it matches here and ends the anchor at a different </a> than the first one would
  '<a href="https://cdn.liketu.com/a.webm" href="https://cdn.liketu.com/b></a>c.mp3">t</a>',
  '<a href="https://cdn.liketu.com/a.webm" href="https://cdn.liketu.com/b></a>c.txt">t</a>',
]
// Seeded combinations of the pieces the two expressions look at.
let seed = 20261017
const rnd = () => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff }
const pick = (a) => a[Math.floor(rnd() * a.length)]
const pieces = ['<a', '<A ', ' href="', ' HREF="', '"', '>', '</a>', '</A>', '<audio', '<AUDIO ', '</audio>', '</Audio>',
  'https://cdn.liketu.com/', 'CDN.LIKETU.COM/', 'cdn.liketu.com', '.webm', '.MP3', '.m4a', '.ogg', '.wav', '.webp', 'x', ' ',
  '\u{1F41D}', '\u{1F3A4}', '\uD83D', '\uDC1D', '\n', 'title="a>b"', '<b>', '</b>', '?q=1', '<p>', '</p>']
for (let i = 0; i < 400; i++) {
  let html = ''
  const n = 3 + Math.floor(rnd() * 14)
  for (let k = 0; k < n; k++) html += pick(pieces)
  rawHtml.push(html)
}
for (const [i, html] of rawHtml.entries()) strips.push(`        StripCase(${kt('raw_' + i)}, ${kt(html)}, ${kt(strip(html))}),`)

const urlInputs = [['example-user', 're-liketu-speak-x'], ['a b', 'p/q'], ['', 'p'], ['a', ''], [null, null], ['u.ser', 'x?y=1&z#h+é;,:@$=']]
const durations = [60, 5, 0, 125, 59.9, -10, NaN, Infinity, 3600, 0.4, 61.99]
const bars = [[48, 0], [48, 1], [48, 0.5], [48, 2], [48, -1], [0, 0.5], [7, 0.5], [3, 0.5], [5, 0.3], [48, NaN], [10, 0.25]]
const progress = [[5, 10], [0, 0], [5, -1], [15, 10], [-1, 10], [3, NaN], [3, Infinity]]
const num = (n) => Number.isNaN(n) ? 'Double.NaN' : n === Infinity ? 'Double.POSITIVE_INFINITY' : (Number.isInteger(n) ? `${n}.0` : `${n}`)

writeFileSync(out, `// GENERATED by native/tools/render/generate-speak-fixtures.mjs from @ecency/render-helper and the RN app's Speak code. Do not edit by hand.
package com.ecency.core.render.blocks

data class StripCase(val name: String, val html: String, val stripped: String)

object SpeakFixtures {
    val strips = listOf(
${strips.join('\n')}
    )

    /** author, permlink, expected stream URL */
    val streamUrls = listOf(
${urlInputs.map(([a, p]) => `        Triple(${kt(a)}, ${kt(p)}, ${kt(getSpeakAudioStreamUrl(a, p))}),`).join('\n')}
    )

    val durations = listOf(
${durations.map((d) => `        ${num(d)} to ${kt(formatDuration(d))},`).join('\n')}
    )

    /** bar count, progress, played bars */
    val playedBars = listOf(
${bars.map(([c, p]) => `        Triple(${c}, ${num(p)}, ${playedBarCount(c, p)}),`).join('\n')}
    )

    /** current time, duration, progress */
    val progress = listOf(
${progress.map(([t, d]) => `        Triple(${num(t)}, ${num(d)}, ${num(playbackProgress(t, d))}),`).join('\n')}
    )
}
`)
console.log(`wrote ${strips.length} strip cases`)
