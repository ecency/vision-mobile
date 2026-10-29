// Generates golden vectors for the Kotlin Hive core from the @ecency/sdk the RN app ships.
//
// Run from a checkout that has node_modules installed (the RN app root):
//   node native/tools/golden/generate.mjs native/shared/src/commonTest/kotlin/com/ecency/core/hive/GoldenVectors.kt
//
// Every key here is derived from a public throwaway seed. Never put a real key in this file.
import { createRequire } from 'node:module'
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'

const require = createRequire(join(process.cwd(), 'package.json'))
const sdk = await import(require.resolve('@ecency/sdk'))
const { secp256k1 } = await import(require.resolve('@noble/curves/secp256k1.js'))
const { sha256 } = await import(require.resolve('@noble/hashes/sha2.js'))
const { HiveTxTransaction, PrivateKey, Signature } = sdk

// The bundled SDK does not expose its serializer, so the bytes come from hived itself
// (condenser_api.get_transaction_hex) and are cross-checked against the SDK's transaction id.
const NODES = ['https://api.hive.blog', 'https://api.openhive.network']
async function chainHex(tx) {
  for (const node of NODES) {
    try {
      const res = await fetch(node, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'condenser_api.get_transaction_hex', params: [tx] }),
        signal: AbortSignal.timeout(15000),
      })
      const json = await res.json()
      if (json.result) return json.result
      throw new Error(JSON.stringify(json.error))
    } catch (e) {
      console.warn(`${node}: ${e.message}`)
    }
  }
  throw new Error('no node could serialize the transaction')
}

const out = process.argv[2]
if (!out) throw new Error('usage: generate.mjs <output.kt>')

const hex = (b) => Buffer.from(b).toString('hex')
const SEED = 'ecency-native-spike-golden-vector-throwaway'
const key = PrivateKey.fromSeed(SEED)
const pub = key.createPublic().toString()

const HEADER = { ref_block_num: 12345, ref_block_prefix: 3367463123, expiration: '2026-10-06T12:00:00' }
const LONG_BODY = 'Native spike golden vector. '.repeat(12) // > 127 bytes, multi-byte varint length
const UNICODE_BODY = 'Hive 🐝 Привет مرحبا 你好 — ünïcödé'

const cases = [
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

const kStr = (s) => JSON.stringify(s).replace(/\$/g, '\\$')
const kOp = ([name, d]) => {
  switch (name) {
    case 'vote': return `Vote(${kStr(d.voter)}, ${kStr(d.author)}, ${kStr(d.permlink)}, ${d.weight})`
    case 'comment': return `Comment(${[d.parent_author, d.parent_permlink, d.author, d.permlink, d.title, d.body, d.json_metadata].map(kStr).join(', ')})`
    case 'comment_options': {
      const benes = (d.extensions[0]?.[1].beneficiaries ?? []).map((b) => `Beneficiary(${kStr(b.account)}, ${b.weight})`)
      return `CommentOptions(${kStr(d.author)}, ${kStr(d.permlink)}, Asset.parse(${kStr(d.max_accepted_payout)}), ${d.percent_hbd}, ${d.allow_votes}, ${d.allow_curation_rewards}, listOf(${benes.join(', ')}))`
    }
    case 'custom_json': return `CustomJson(listOf(${d.required_auths.map(kStr).join(', ')}), listOf(${d.required_posting_auths.map(kStr).join(', ')}), ${kStr(d.id)}, ${kStr(d.json)})`
    case 'delete_comment': return `DeleteComment(${kStr(d.author)}, ${kStr(d.permlink)})`
    case 'account_update2': return `AccountUpdate2(${kStr(d.account)}, ${kStr(d.json_metadata)}, ${kStr(d.posting_json_metadata)})`
    case 'transfer': return `Transfer(${kStr(d.from)}, ${kStr(d.to)}, Asset.parse(${kStr(d.amount)}), ${kStr(d.memo)})`
    default: throw new Error(`no Kotlin emitter for ${name}`)
  }
}

const txVectors = []
for (const [name, operations] of cases) {
  const raw = { ...HEADER, operations, extensions: [] }
  const tx = new HiveTxTransaction({ transaction: raw })
  const { digest, txId } = tx.digest()
  // Unsigned transaction: hived appends the signature count (varint 0) after the body.
  const signedHex = await chainHex({ ...raw, signatures: [] })
  if (!signedHex.endsWith('00')) throw new Error(`${name}: unexpected hex tail`)
  const serialized = signedHex.slice(0, -2)
  if (hex(sha256(Buffer.from(serialized, 'hex'))).slice(0, 40) !== txId) {
    throw new Error(`${name}: hived bytes do not match the SDK transaction id`)
  }
  // Deterministic RFC 6979 signature (same nonce scheme as libsecp256k1), encoded like PrivateKey.sign().
  const rv = secp256k1.sign(digest, key.key, { extraEntropy: false, format: 'recovered', prehash: false })
  const signature = (rv[0] + 31).toString(16) + hex(rv.subarray(1))
  if (Signature.from(signature).getPublicKey(digest).toString() !== pub) throw new Error(`${name}: signature does not recover`)
  // The SDK's own (randomised) signature must recover to the same key too.
  const sdkSig = tx.sign(key).signatures[0]
  if (Signature.from(sdkSig).getPublicKey(digest).toString() !== pub) throw new Error(`${name}: sdk signature does not recover`)
  txVectors.push(`        TxVector(
            name = ${kStr(name)},
            operations = listOf(${operations.map(kOp).join(', ')}),
            serialized = ${kStr(serialized)},
            digest = ${kStr(hex(digest))},
            txId = ${kStr(txId)},
            signature = ${kStr(signature)},
            sdkSignature = ${kStr(sdkSig)},
        ),`)
}

const loginVectors = ['owner', 'active', 'posting', 'memo'].map((role) => {
  const k = PrivateKey.fromLogin('spike-user', 'P5ThrowawayGoldenVectorPasswordNotReal', role)
  return `        LoginVector(${kStr(role)}, ${kStr(k.toString())}, ${kStr(k.createPublic().toString())}),`
})

writeFileSync(out, `// GENERATED by native/tools/golden/generate.mjs from @ecency/sdk. Do not edit by hand.
// All keys derive from public throwaway seeds and control no funds.
package com.ecency.core.hive

object GoldenVectors {
    const val SEED = ${kStr(SEED)}
    const val WIF = ${kStr(key.toString())}
    const val PUBLIC_KEY = ${kStr(pub)}
    const val REF_BLOCK_NUM = ${HEADER.ref_block_num}
    const val REF_BLOCK_PREFIX = ${HEADER.ref_block_prefix}L
    const val EXPIRATION = ${kStr(HEADER.expiration)}

    const val LOGIN_USER = "spike-user"
    const val LOGIN_PASSWORD = "P5ThrowawayGoldenVectorPasswordNotReal"

    val transactions = listOf(
${txVectors.join('\n')}
    )

    val logins = listOf(
${loginVectors.join('\n')}
    )
}

data class TxVector(
    val name: String,
    val operations: List<Operation>,
    val serialized: String,
    val digest: String,
    val txId: String,
    val signature: String,
    val sdkSignature: String,
)

data class LoginVector(val role: String, val wif: String, val publicKey: String)
`)
console.log(`wrote ${txVectors.length} transaction and ${loginVectors.length} login vectors to ${out}`)
