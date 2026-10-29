// Golden vectors for the posting authority grant (account_update, posting only), from the @ecency/sdk the RN app
// ships: buildGrantPostingPermissionOp as RN's _handleSchedulePress calls it, serialized and signed by hive-tx.
//
// Run from the RN app root (which has node_modules):
//   node native/tools/golden/generate-grant.mjs native/shared/src/commonTest/kotlin/com/ecency/core/api/GrantGoldenVectors.kt
//
// Offline only: nothing is broadcast. Every key derives from a public throwaway seed and controls nothing.
import { createRequire } from 'node:module'
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'

const require = createRequire(join(process.cwd(), 'package.json'))
const sdk = await import(require.resolve('@ecency/sdk'))
const { secp256k1 } = await import(require.resolve('@noble/curves/secp256k1.js'))
const { HiveTxTransaction, PrivateKey, buildGrantPostingPermissionOp } = sdk
const out = process.argv[2]
if (!out) throw new Error('usage: generate-grant.mjs <output.kt>')

const hex = (b) => Buffer.from(b).toString('hex')
const kStr = (s) => JSON.stringify(s).replace(/\$/g, '\\$')
const signer = PrivateKey.fromSeed('ecency-native-spike-golden-vector-throwaway')
const pub = (n) => PrivateKey.fromSeed(`ecency-native-spike-grant-fixture-${n}`).createPublic().toString()
const HEADER = { ref_block_num: 12345, ref_block_prefix: 3367463123, expiration: '2026-10-06T12:00:00' }

const account = (name, posting, json_metadata = '') => ({
  name,
  owner: { weight_threshold: 1, account_auths: [], key_auths: [[pub('owner'), 1]] },
  active: { weight_threshold: 1, account_auths: [], key_auths: [[pub('active'), 1]] },
  posting,
  memo_key: pub('memo'),
  json_metadata,
  posting_json_metadata: '{"profile":{"name":"Spike"}}',
})
const cases = [
  ['one_key', account('spike-voter', { weight_threshold: 1, account_auths: [], key_auths: [[pub('p1'), 1]] })],
  ['auths_before_and_after', account('spike-voter', { weight_threshold: 1, account_auths: [['a-first', 1], ['peakd.app', 1], ['zz.last', 1]], key_auths: [[pub('p1'), 1]] }, '{"profile":{"about":"legacy \\"meta\\" 🐝"}}')],
  ['two_keys_threshold_two', account('a.b-c', { weight_threshold: 2, account_auths: [['other', 2]], key_auths: [[pub('p1'), 1], [pub('p2'), 1]] })],
  ['keys_in_chain_order', account('spike-voter', { weight_threshold: 1, account_auths: [], key_auths: [pub('p3'), pub('p1'), pub('p2')].sort().map((k) => [k, 1]) })],
  ['account_auths_only', account('spike-voter', { weight_threshold: 3, account_auths: [['guardian', 3]], key_auths: [] })],
]
const vectors = cases.map(([name, acc]) => {
  // RN passes the posting authority, 'ecency.app', the weight threshold as the weight, and the memo key. RN's
  // json argument is posting_json_metadata; the native app writes json_metadata back unchanged (see PostingGrant).
  const op = buildGrantPostingPermissionOp(acc.name, JSON.parse(JSON.stringify(acc.posting)), 'ecency.app', acc.posting.weight_threshold, acc.memo_key, acc.json_metadata)
  const tx = new HiveTxTransaction({ transaction: { ...HEADER, operations: [op], extensions: [] } })
  const { digest, txId } = tx.digest()
  const rv = secp256k1.sign(digest, signer.key, { extraEntropy: false, format: 'recovered', prehash: false })
  return `        GrantVector(${kStr(name)}, ${kStr(acc.name)}, ${kStr(JSON.stringify(acc))}, ${kStr(JSON.stringify(op))}, ${kStr(txId)}, ${kStr((rv[0] + 31).toString(16) + hex(rv.subarray(1)))}),`
})

// hived keeps authorities as sorted maps and signs over that order: a row whose lists arrive in another order must
// serialize exactly like the sorted one. Expected values come from the sorted input; the Kotlin test feeds the unsorted row.
const keyBytes = (n) => Buffer.from(PrivateKey.fromSeed(`ecency-native-spike-grant-fixture-${n}`).createPublic().key)
const sortedNames = ['p1', 'p2', 'p3', 'p4'].sort((a, b) => Buffer.compare(keyBytes(a), keyBytes(b)))
if (sortedNames.map(pub).join() === sortedNames.map(pub).sort().join()) console.warn('note: byte order equals string order for these fixture keys')
const sortedAcc = account('spike-voter', { weight_threshold: 2, account_auths: [['aa.first', 1], ['zz.last', 2]], key_auths: sortedNames.map((n, i) => [pub(n), i + 1]) })
const unsortedAcc = JSON.parse(JSON.stringify(sortedAcc))
unsortedAcc.posting.key_auths.reverse()
unsortedAcc.posting.account_auths.reverse()
const unsorted = (() => {
  const op = buildGrantPostingPermissionOp(sortedAcc.name, JSON.parse(JSON.stringify(sortedAcc.posting)), 'ecency.app', 2, sortedAcc.memo_key, sortedAcc.json_metadata)
  const tx = new HiveTxTransaction({ transaction: { ...HEADER, operations: [op], extensions: [] } })
  const { digest, txId } = tx.digest()
  const rv = secp256k1.sign(digest, signer.key, { extraEntropy: false, format: 'recovered', prehash: false })
  return `GrantVector("unsorted_input", ${kStr(sortedAcc.name)}, ${kStr(JSON.stringify(unsortedAcc))}, ${kStr(JSON.stringify(op))}, ${kStr(txId)}, ${kStr((rv[0] + 31).toString(16) + hex(rv.subarray(1)))})`
})()

writeFileSync(out, `// GENERATED by native/tools/golden/generate-grant.mjs from @ecency/sdk. Do not edit by hand.
// All keys derive from public throwaway seeds and control no funds.
package com.ecency.core.api

data class GrantVector(val name: String, val username: String, val accountJson: String, val opJson: String, val txId: String, val signature: String)

object GrantGoldenVectors {
    val grants = listOf(
${vectors.join('\n')}
    )

    /** The account row lists its keys and account auths out of hived's order; everything expected is from the sorted row. */
    val unsorted = ${unsorted}
}
`)
console.log(`grants ${vectors.length}`)
