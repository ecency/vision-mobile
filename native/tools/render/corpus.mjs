// Gate 1 corpus: fetches public Hive posts and renders them with @ecency/render-helper, so the
// Kotlin renderer can be compared against real content. Output stays local (corpus/ is
// gitignored) because the repo is public and posts are other people's content.
//
//   cd native/tools/render && npm ci
//   node corpus.mjs fetch [count]   # snapshot posts into corpus/posts.jsonl (default 500)
//   node corpus.mjs render          # (re)render the snapshot into corpus/expected.jsonl
import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs'

globalThis.window = globalThis // browser-build behaviour: no lolight, as in the app
const rh = await import('@ecency/render-helper')
rh.setProxyBase('https://images.ecency.com')

const DIR = new URL('./corpus/', import.meta.url)
const POSTS = new URL('posts.jsonl', DIR)
const EXPECTED = new URL('expected.jsonl', DIR)
const NODES = ['https://api.hive.blog', 'https://api.openhive.network']

async function rpc(method, params) {
  let lastError
  for (const node of NODES) {
    try {
      const res = await fetch(node, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }),
      })
      const json = await res.json()
      if (json.error) throw new Error(JSON.stringify(json.error))
      return json.result
    } catch (e) {
      lastError = e
    }
  }
  throw lastError
}

// A spread of sorts and communities so the corpus covers plain prose, heavy HTML layouts,
// embeds, tables and non-Latin scripts. Communities are public and active.
const SOURCES = [
  ['trending', ''], ['hot', ''], ['created', ''], ['payout', ''],
  ['trending', 'hive-125125'], ['created', 'hive-167922'], ['trending', 'hive-194913'],
  ['trending', 'hive-163772'], ['created', 'hive-196387'], ['trending', 'hive-148441'],
  ['created', 'hive-188619'], ['trending', 'hive-174578'], ['created', 'hive-101145'],
  // 3Speak: video posts carry the 3Speak footer that cleanReply strips.
  ['created', 'hive-181335'],
]

async function fetchCorpus(count) {
  // Round-robin one page per source until the target is reached, so a quiet community
  // just contributes less instead of leaving the corpus short.
  const seen = new Set()
  const posts = []
  const cursors = SOURCES.map(() => ({ start: {}, done: false, taken: 0 }))
  while (posts.length < count && cursors.some((c) => !c.done)) {
    for (const [i, [sort, tag]] of SOURCES.entries()) {
      const c = cursors[i]
      if (c.done || posts.length >= count) continue
      const page = await rpc('bridge.get_ranked_posts', { sort, tag, limit: 20, observer: '', ...c.start })
      if (!page || page.length < 20) c.done = true
      for (const p of page ?? []) {
        const key = `${p.author}/${p.permlink}`
        if (seen.has(key) || posts.length >= count) continue
        seen.add(key)
        c.taken++
        posts.push({ author: p.author, permlink: p.permlink, body: p.body, json_metadata: JSON.stringify(p.json_metadata ?? {}), created: p.created, last_update: p.updated, source: `${sort}/${tag || 'all'}` })
      }
      if (page?.length) c.start = { start_author: page.at(-1).author, start_permlink: page.at(-1).permlink }
    }
  }
  SOURCES.forEach(([sort, tag], i) => console.log(`${sort}/${tag || 'all'}: ${cursors[i].taken}`))
  mkdirSync(DIR, { recursive: true })
  writeFileSync(POSTS, posts.map((p) => JSON.stringify(p)).join('\n') + '\n')
  console.log(`saved ${posts.length} posts`)
}

function render() {
  if (!existsSync(POSTS)) throw new Error('run `node corpus.mjs fetch` first')
  const posts = readFileSync(POSTS, 'utf8').trim().split('\n').map((l) => JSON.parse(l))
  const out = posts.map((p) => JSON.stringify({
    key: `${p.author}/${p.permlink}`,
    html: rh.renderPostBody(p.body, true, false),
    summary: rh.postBodySummary(p.body, 200),
    image: rh.catchPostImage(p) || '',
  }))
  writeFileSync(EXPECTED, out.join('\n') + '\n')
  console.log(`rendered ${posts.length} posts`)
}

const [cmd, arg] = process.argv.slice(2)
if (cmd === 'fetch') await fetchCorpus(Number(arg) || 500)
else if (cmd === 'render') render()
else console.log('usage: node corpus.mjs fetch [count] | render')
