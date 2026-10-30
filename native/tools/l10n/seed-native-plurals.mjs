// Seeds the translations of the native plural keys (`native.plurals.<RN key path>` in en-US.json) from the
// translations of the RN keys they replace, so a language keeps its text until Crowdin translators add the
// plural forms. RN's own keys are not touched (RN keeps reading them as they are).
//
// Run from the repo root after a fresh Crowdin download (no dependencies):
//   node native/tools/l10n/seed-native-plurals.mjs [--check]
//
// A language that already has a native key keeps it (Crowdin's translation wins); only missing keys are seeded.
// --check writes nothing and exits 1 when a file would change.
//
// How a translation is converted (mechanical, the same text the old RN message showed):
//   suffix   `{count} word{suffix}` becomes `{count, plural, <every CLDR form of the language> {# word}}`;
//            a translation without `{suffix}` is copied as it is; one where the suffix was the word itself
//            (nothing between `{count}` and `{suffix}`), a stem listed in SKIP, or a word that cannot sit in a
//            plural is skipped.
//   postfix  RN showed "<count> <text>": becomes `{count, plural, <every form> {# <text>}}`.
//   plain    copied as it is (a plain `{n}` where English has a plural is valid ICU and shows as before).
// Skipped (English shows): no RN translation, one equal to RN's English (untranslated), unbalanced braces, or a
// placeholder the native English lacks.
import { readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const KEYS = {
  'notification.monthly_posts': 'suffix',
  'notification.checkin': 'suffix',
  'notification.tags_bundle': 'plain',
  'pincode.attempts_postfix': 'postfix',
  'perks.streak': 'plain',
  'post_poll.age_limit': 'plain',
  'post_poll.max_choices': 'plain',
  'recurrent.remaining_executions': 'plain',
  'recurrent.schedule_summary': 'plain',
  'editor.short_content_hint': 'plain',
  'chats.people_joined': 'plain',
  'chats.ban-remaining-minutes': 'plain',
  'chats.ban-remaining-hours': 'plain',
  'chats.ban-remaining-days': 'plain',
  'search_result.filters.too_many_tags': 'plain',
  'search_result.filters.too_long': 'plain',
  'ai_assist.min_chars': 'plain',
  'ai_assist.chars_remaining': 'plain',
  'alert.too_many_images': 'plain',
  'dictation.stop_hint': 'plain',
  'dictation.rate': 'plain',
  'transfer.powering_down_info': 'plain',
  'community_settings.title_length': 'plain',
}
// Translations a mechanical conversion would break: the word before `{suffix}` is a stem, not a word.
const SKIP = new Set(['ru-RU notification.checkin'])
const PREFIX = 'native.plurals.'
const ORDER = ['zero', 'one', 'two', 'few', 'many', 'other']

const dir = 'src/config/locales'
const check = process.argv.includes('--check')
const rules = JSON.parse(readFileSync('native/shared/l10n/cldr-plural-rules.json', 'utf8')).rules

const get = (o, path) => path.split('.').reduce((d, k) => (d && typeof d === 'object' ? d[k] : undefined), o)
const put = (o, path, v) => {
  const parts = path.split('.')
  let d = o
  for (const k of parts.slice(0, -1)) d = d[k] ??= {}
  d[parts.at(-1)] = v
}
const names = (m) => new Set([...m.matchAll(/\{\s*([\w-]+)/g)].map((x) => x[1]))
const balanced = (m) => {
  let depth = 0
  for (const c of m) {
    if (c === '{') depth++
    else if (c === '}' && --depth < 0) return false
  }
  return depth === 0
}
const plural = (lang, text) => {
  const have = new Set([...Object.keys(rules[lang] ?? {}), 'other'])
  return `{count, plural, ${ORDER.filter((c) => have.has(c)).map((c) => `${c} {${text}}`).join(' ')}}`
}

function convert(lang, kind, rnText, rnEnglish, nativeEnglish) {
  if (typeof rnText !== 'string' || rnText === rnEnglish || !balanced(rnText)) return null
  let out = rnText
  if (kind === 'suffix' && rnText.includes('{suffix}')) {
    const m = /\{count\}(.*?)\{suffix\}/.exec(rnText)
    if (!m || !m[1].trim() || /['#{}]/.test(m[1]) || rnText.split('{suffix}').length !== 2) return null
    out = rnText.slice(0, m.index) + plural(lang, '#' + m[1]) + rnText.slice(m.index + m[0].length)
  } else if (kind === 'postfix') {
    if (/['#{}]/.test(rnText)) return null
    out = plural(lang, '# ' + rnText)
  }
  const allowed = names(nativeEnglish)
  for (const n of names(out)) if (!allowed.has(n)) return null
  return out
}

const en = JSON.parse(readFileSync(join(dir, 'en-US.json'), 'utf8'))
let changed = 0
const report = []
for (const f of readdirSync(dir).filter((x) => x.endsWith('.json') && x !== 'en-US.json').sort()) {
  const lang = f.slice(0, -5)
  const file = join(dir, f)
  const raw = readFileSync(file, 'utf8')
  const data = JSON.parse(raw)
  let seeded = 0
  const skipped = []
  for (const [key, kind] of Object.entries(KEYS)) {
    const nativeEnglish = get(en, PREFIX + key)
    if (typeof nativeEnglish !== 'string') throw new Error(`en-US lacks ${PREFIX + key}`)
    if (get(data, PREFIX + key) !== undefined) continue
    const rnText = get(data, key)
    const out = SKIP.has(`${lang} ${key}`) ? null : convert(lang, kind, rnText, get(en, key), nativeEnglish)
    if (out == null) {
      if (typeof rnText === 'string' && rnText !== get(en, key)) skipped.push(key)
      continue
    }
    put(data, PREFIX + key, out)
    seeded++
  }
  if (seeded) {
    const text = JSON.stringify(data, null, 2) + (raw.endsWith('\n') ? '\n' : '')
    if (text !== raw) {
      changed++
      if (!check) writeFileSync(file, text)
    }
  }
  report.push(`${lang}: seeded ${seeded}${skipped.length ? `, skipped ${skipped.join(' ')}` : ''}`)
}
console.log(report.join('\n'))
console.log(`${changed} file(s) ${check ? 'would change' : 'changed'}`)
if (check && changed) process.exit(1)
