// Golden vectors for the community operations and the search query builder, from the @ecency/sdk the RN app ships.
//
// Run from a checkout that has node_modules installed (the RN app root; the RN post URL parser is TypeScript):
//   node --experimental-strip-types --no-warnings native/tools/golden/generate-communities.mjs native/shared/src/commonTest/kotlin/com/ecency/core/api/community/CommunityGoldenVectors.kt
//
// Offline only: nothing here talks to a node. Keys derive from the public throwaway seed of generate.mjs.
import { createRequire } from 'node:module'
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'

const require = createRequire(join(process.cwd(), 'package.json'))
const sdk = await import(require.resolve('@ecency/sdk'))
const { secp256k1 } = await import(require.resolve('@noble/curves/secp256k1.js'))
const {
  HiveTxTransaction, PrivateKey,
  buildSubscribeOp, buildUnsubscribeOp, buildSetRoleOp, buildUpdateCommunityOp, buildPinPostOp, buildMutePostOp,
  buildMuteUserOp, buildFlagPostOp, buildSearchQuery, SearchQuery, MAX_SEARCH_TAGS, MAX_SEARCH_QUERY_LENGTH, isCommunity,
} = sdk

const out = process.argv[2]
if (!out) throw new Error('usage: generate-communities.mjs <output.kt>')

const hex = (b) => Buffer.from(b).toString('hex')
const key = PrivateKey.fromSeed('ecency-native-spike-golden-vector-throwaway')
const HEADER = { ref_block_num: 12345, ref_block_prefix: 3367463123, expiration: '2026-10-06T12:00:00' }

// Kotlin string literal: JSON escaping is valid Kotlin except `$`. Lone surrogates come out as \udXXX escapes.
const kStr = (s) => JSON.stringify(s).replace(/\$/g, '\\$')

const U = 'spike-mod'
const C = 'hive-125125'
const TRICKY = 'Line "one"\nTab\there \\ back / slash \u0001\u001f\u007f é 🐝 Привет   <b>&amp;</b> $x'
const LONE = 'lone \ud83d surrogate'

const props = (title, about, lang, description, flag_text, is_nsfw) => ({ title, about, lang, description, flag_text, is_nsfw })
const kProps = (p) => `CommunityProps(${[p.title, p.about, p.lang, p.description, p.flag_text].map(kStr).join(', ')}, ${p.is_nsfw})`

const opCases = [
  ['subscribe', buildSubscribeOp(U, C), `CommunityOps.subscribe(${kStr(U)}, ${kStr(C)})`],
  ['unsubscribe', buildUnsubscribeOp(U, C), `CommunityOps.unsubscribe(${kStr(U)}, ${kStr(C)})`],
  ['set_role_mod', buildSetRoleOp(U, C, 'spike-alice', 'mod'), `CommunityOps.setRole(${kStr(U)}, ${kStr(C)}, "spike-alice", "mod")`],
  ['set_role_muted', buildSetRoleOp(U, C, 'spike-bob', 'muted'), `CommunityOps.setRole(${kStr(U)}, ${kStr(C)}, "spike-bob", "muted")`],
  ...[
    ['update_props_plain', props('Golden', 'About it', 'en', 'Long description', 'Be nice', false)],
    ['update_props_tricky', props('Tr 🐝 "q"', TRICKY, 'ru', TRICKY, `${TRICKY}\n${TRICKY}`, true)],
    ['update_props_lone_surrogate', props('Lone', LONE, 'en', '', '', false)],
  ].map(([name, p]) => [name, buildUpdateCommunityOp(U, C, p), `CommunityOps.updateProps(${kStr(U)}, ${kStr(C)}, ${kProps(p)})`]),
  ['pin_post', buildPinPostOp(U, C, 'spike-author', 'golden-post', true), `CommunityOps.pinPost(${kStr(U)}, ${kStr(C)}, "spike-author", "golden-post", true)`],
  ['unpin_post', buildPinPostOp(U, C, 'spike-author', 'golden-post', false), `CommunityOps.pinPost(${kStr(U)}, ${kStr(C)}, "spike-author", "golden-post", false)`],
  ['mute_post', buildMutePostOp(U, C, 'spike-author', 'golden-post', TRICKY, true), `CommunityOps.mutePost(${kStr(U)}, ${kStr(C)}, "spike-author", "golden-post", ${kStr(TRICKY)}, true)`],
  ['unmute_post', buildMutePostOp(U, C, 'spike-author', 'golden-post', 'Restored', false), `CommunityOps.mutePost(${kStr(U)}, ${kStr(C)}, "spike-author", "golden-post", "Restored", false)`],
  ['mute_user', buildMuteUserOp(U, C, 'spike-bob', 'Spam', true), `CommunityOps.muteUser(${kStr(U)}, ${kStr(C)}, "spike-bob", "Spam", true)`],
  ['unmute_user', buildMuteUserOp(U, C, 'spike-bob', 'Appeal accepted', false), `CommunityOps.muteUser(${kStr(U)}, ${kStr(C)}, "spike-bob", "Appeal accepted", false)`],
  ['flag_post', buildFlagPostOp(U, C, 'spike-author', 'golden-post', 'Off topic'), `CommunityOps.flagPost(${kStr(U)}, ${kStr(C)}, "spike-author", "golden-post", "Off topic")`],
]

const opVectors = opCases.map(([name, op, call]) => {
  const tx = new HiveTxTransaction({ transaction: { ...HEADER, operations: [op], extensions: [] } })
  const { digest, txId } = tx.digest()
  const rv = secp256k1.sign(digest, key.key, { extraEntropy: false, format: 'recovered', prehash: false })
  const signature = (rv[0] + 31).toString(16) + hex(rv.subarray(1))
  return `        OpVector(
            name = ${kStr(name)},
            build = { ${call} },
            opJson = ${kStr(JSON.stringify(op))},
            json = ${kStr(op[1].json)},
            txId = ${kStr(txId)},
            signature = ${kStr(signature)},
        ),`
})

// ---- search query builder (RN searchFilters + SDK buildSearchQuery / SearchQuery) ----
const queryCases = [
  { search: 'hive', type: 'post' },
  { search: '  many   spaces here ', author: '@Demo user', type: '', category: '#Hive-125125 extra', tags: 'Travel, photography #travel,,food' },
  { search: '', author: 'alice', type: 'comment' },
  { search: 'ecency', tags: 'a,b,c,d,e,f', type: 'post' },
  { search: 'x'.repeat(95), type: 'post' },
  { search: 'author:bob type:comment free text tag:x,y category:c', type: 'post' },
  { search: '', type: 'post' },
  { search: 'Ünïcödé 🐝', category: 'ÜBER', type: '' },
]
const validate = (q) => {
  const parsed = new SearchQuery(q)
  if (!parsed.search && !parsed.author && !parsed.category && parsed.tags.length === 0) return 'needs_criteria'
  if (parsed.tags.length > MAX_SEARCH_TAGS) return 'too_many_tags'
  if (q.length > MAX_SEARCH_QUERY_LENGTH) return 'too_long'
  return ''
}
const buildVectors = queryCases.map((c) => {
  const b = buildSearchQuery(c)
  return `        BuildVector(${[c.search ?? '', c.author ?? '', c.type ?? '', c.category ?? '', c.tags ?? ''].map(kStr).join(', ')}, ` +
    `${kStr(b.q)}, ${kStr(b.search)}, ${kStr(b.author)}, ${kStr(b.category)}, listOf(${b.tags.map(kStr).join(', ')}), ${kStr(validate(b.q))}),`
})

const parseInputs = [
  'author:alice hive', 'prototype:v2 subcategory:hive-1 words', 'type:post type:comment', 'tag:a, tag:b,c, tag:a', 'tag:a,',
  'mid author:x  word  tag:t end', 'type:bogus hello', 'category:  spaced', '', '  ',
]
const parseVectors = parseInputs.map((q) => {
  const p = new SearchQuery(q)
  return `        ParseVector(${kStr(q)}, ${kStr(p.search)}, ${kStr(p.author)}, ${kStr(p.type)}, ${kStr(p.category)}, listOf(${p.tags.map(kStr).join(', ')})),`
})

// RN src/utils/postUrlParser.ts (the search screen's post URL detection), from the RN source itself.
const { default: postUrlParser } = await import(join(process.cwd(), 'src/utils/postUrlParser.ts'))
const urlInputs = [
  'hive', '', '@good-karma/esteem-london', '/@good-karma/esteem-london?x=1', 'esteem/@good-karma/esteem-london#@bob/re-x',
  'hive-125125/@alice/post-one#comments', 'https://ecency.com/hive-125125/@alice/post-one', 'https://peakd.com/@alice/post-two?ref=x',
  'https://hive.blog/@alice', 'https://ecency.com/@alice', 'https://ecency.com/trending/hive-125125', 'https://ecency.com/trending',
  'https://ecency.com/waves/alice/wave-1', 'ecency://@alice/post-three', 'https://leofinance.io/@alice/post-four',
  'https://example.com/@alice/post-five', 'https://example.com/a/b/c/d', 'some words @alice/post', 'https://inleo.io/@alice/x',
]
const urlVectors = urlInputs.map((u) => {
  const r = postUrlParser(u.toLowerCase())
  if (!r) return `        UrlVector(${kStr(u)}, null, null, null),`
  return `        UrlVector(${kStr(u)}, ${r.author == null ? 'null' : kStr(r.author)}, ${r.permlink == null ? 'null' : kStr(r.permlink)}, ${r.feedType == null ? 'null' : kStr(r.feedType)}),`
})

const communityNames = ['hive-125125', 'hive-1', 'hive-12345', 'hive-1234567', 'hive-412345', 'ecency', 'xhive-125125', 'hive-125125a', 'hive-3999999']
const communityVectors = communityNames.map((n) => `        ${kStr(n)} to ${isCommunity(n)},`)

writeFileSync(out, `// GENERATED by native/tools/golden/generate-communities.mjs from @ecency/sdk. Do not edit by hand.
// All keys derive from public throwaway seeds and control no funds.
package com.ecency.core.api.community

import com.ecency.core.api.broadcast.CommunityOps
import com.ecency.core.api.broadcast.CommunityProps
import com.ecency.core.hive.CustomJson

object CommunityGoldenVectors {
    val ops = listOf(
${opVectors.join('\n')}
    )

    val builds = listOf(
${buildVectors.join('\n')}
    )

    val parses = listOf(
${parseVectors.join('\n')}
    )

    /** RN postUrlParser on the lowercased input (the search value is already lowercased). */
    val urls = listOf(
${urlVectors.join('\n')}
    )

    /** SDK isCommunity. */
    val communityNames = listOf(
${communityVectors.join('\n')}
    )
}

data class OpVector(val name: String, val build: () -> CustomJson, val opJson: String, val json: String, val txId: String, val signature: String)

/** Inputs (search, author, type, category, tags), then buildSearchQuery's output and RN validateSearchQuery's verdict. */
data class BuildVector(
    val search: String, val author: String, val type: String, val category: String, val tags: String,
    val q: String, val outSearch: String, val outAuthor: String, val outCategory: String, val outTags: List<String>, val error: String,
)

data class UrlVector(val input: String, val author: String?, val permlink: String?, val feedType: String?)

data class ParseVector(val q: String, val search: String, val author: String, val type: String, val category: String, val tags: List<String>)
`)
console.log(`wrote ${opVectors.length} op, ${buildVectors.length} build, ${parseVectors.length} parse vectors to ${out}`)
