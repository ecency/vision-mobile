// Generates golden vectors for the Kotlin Hive core from the @ecency/sdk the RN app ships.
//
// After `npm install` in tools/, from the repository root:
//   node tools/golden/generate.mjs shared/src/commonTest/kotlin/com/ecency/core/hive/GoldenVectors.kt
//
// Every key here is derived from a public throwaway seed. Never put a real key in this file.
import { createRequire } from 'node:module'
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { HEADER, cases } from './tx-cases.mjs'

const require = createRequire(new URL('../package.json', import.meta.url))
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

writeFileSync(out, `// GENERATED by tools/golden/generate.mjs from @ecency/sdk. Do not edit by hand.
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
