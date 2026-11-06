// Golden vectors for the operations hive:// and ecency://sign links ask key accounts to sign (LinkOpGoldenVectors.kt),
// fully offline (#3635): create_proposal, update_proposal, remove_proposal, limit_order_create2.
// - operation JSON from the @ecency/sdk builders where the SDK has one (limit_order_create2 has none: the JSON is the
//   chain's own condenser form, as a link carries it);
// - serialized bytes from dhive's serializer (@hiveio/dhive Types.Transaction), which matches hived byte for byte;
// - the transaction id and digest from the SDK (hive-tx), which must hash the dhive bytes;
// - deterministic RFC 6979 signatures (noble, extraEntropy false), plus the SDK's own randomised signature must recover.
// account_update2 (metadata only) is pinned in GoldenVectors.kt already (generate.mjs).
//
// After `npm ci --prefix tools`, from the repository root:
//   node tools/golden/generate-link-ops.mjs shared/src/commonTest/kotlin/com/ecency/core/hive/LinkOpGoldenVectors.kt
//
// Every key derives from the public throwaway seed of generate.mjs and controls no funds. No network.
import { createRequire } from 'node:module'
import { readFileSync, writeFileSync } from 'node:fs'

const require = createRequire(new URL('../package.json', import.meta.url))
const sdk = await import(require.resolve('@ecency/sdk'))
const dhive = require('@hiveio/dhive')
const ByteBuffer = require('@ecency/bytebuffer')
const { secp256k1 } = await import(require.resolve('@noble/curves/secp256k1.js'))
const { sha256 } = await import(require.resolve('@noble/hashes/sha2.js'))
const { HiveTxTransaction, PrivateKey, Signature } = sdk

const out = process.argv[2]
if (!out) throw new Error('usage: generate-link-ops.mjs <output.kt>')
const version = (pkg) => JSON.parse(readFileSync(new URL(`../node_modules/${pkg}/package.json`, import.meta.url), 'utf8')).version
const sdkVersion = version('@ecency/sdk')
const dhiveVersion = version('@hiveio/dhive')

const hex = (b) => Buffer.from(b).toString('hex')
const SEED = 'ecency-native-spike-golden-vector-throwaway'
const key = PrivateKey.fromSeed(SEED)
const pub = key.createPublic().toString()
const HEADER = { ref_block_num: 12345, ref_block_prefix: 3367463123, expiration: '2026-10-06T12:00:00' }
const U = 'spike-voter'

const cases = [
  ['create_proposal', [sdk.buildProposalCreateOp(U, {
    receiver: 'spike-bob', subject: 'Golden proposal 🐝', permlink: 'native-golden-proposal',
    start: '2026-11-01T00:00:00', end: '2027-05-01T00:00:00', dailyPay: '125.500 HBD',
  })]],
  ['create_proposal_zero_pay', [sdk.buildProposalCreateOp(U, {
    receiver: U, subject: 'Return proposal', permlink: 'return-proposal',
    start: '2026-10-07T00:00:00', end: '2036-10-07T00:00:00', dailyPay: '0.000 HBD',
  })]],
  ['update_proposal', [sdk.buildUpdateProposalOp(342, U, '100.000 HBD', 'Golden proposal, smaller', 'native-golden-proposal')]],
  // the one update_proposal extension: a new (earlier) end date, variant 1 (update_proposal_end_date)
  ['update_proposal_end_date', [['update_proposal', {
    proposal_id: 4294967296, creator: U, daily_pay: '1.000 HBD', subject: 'Ends sooner', permlink: 'native-golden-proposal',
    extensions: [[1, { end_date: '2027-01-01T00:00:00' }]],
  }]]],
  ['remove_proposal', [sdk.buildRemoveProposalOp(U, [342])]],
  ['remove_proposal_many', [sdk.buildRemoveProposalOp(U, [0, 7, 4294967296])]],
  ['limit_order_create2_sell_hive', [['limit_order_create2', {
    owner: U, orderid: 1791288001, amount_to_sell: '10.000 HIVE', exchange_rate: { base: '1.000 HIVE', quote: '0.250 HBD' },
    fill_or_kill: false, expiration: '2026-11-02T12:00:00',
  }]]],
  ['limit_order_create2_sell_hbd_fok', [['limit_order_create2', {
    owner: U, orderid: 4294967295, amount_to_sell: '2.500 HBD', exchange_rate: { base: '1.000 HBD', quote: '3.999 HIVE' },
    fill_or_kill: true, expiration: '2026-10-06T13:00:00',
  }]]],
]

function dhiveBytes(raw) {
  const buf = new ByteBuffer(ByteBuffer.DEFAULT_CAPACITY, ByteBuffer.LITTLE_ENDIAN)
  dhive.Types.Transaction(buf, raw)
  buf.flip()
  return Buffer.from(buf.toBuffer())
}

const kStr = (s) => JSON.stringify(s).replace(/\$/g, '\\$')
const vectors = []
for (const [name, operations] of cases) {
  const raw = { ...HEADER, operations, extensions: [] }
  const bytes = dhiveBytes(raw)
  const tx = new HiveTxTransaction({ transaction: raw })
  const { digest, txId } = tx.digest()
  if (hex(sha256(bytes)).slice(0, 40) !== txId) throw new Error(`${name}: dhive bytes do not hash to the SDK transaction id`)
  const rv = secp256k1.sign(digest, key.key, { extraEntropy: false, format: 'recovered', prehash: false })
  const signature = (rv[0] + 31).toString(16) + hex(rv.subarray(1))
  if (Signature.from(signature).getPublicKey(digest).toString() !== pub) throw new Error(`${name}: signature does not recover`)
  const sdkSig = tx.sign(key).signatures[0]
  if (Signature.from(sdkSig).getPublicKey(digest).toString() !== pub) throw new Error(`${name}: sdk signature does not recover`)
  vectors.push(`        WalletVector(
            name = ${kStr(name)},
            operationsJson = ${kStr(JSON.stringify(operations))},
            serialized = ${kStr(hex(bytes))},
            digest = ${kStr(hex(digest))},
            txId = ${kStr(txId)},
            signature = ${kStr(signature)},
        ),`)
}

writeFileSync(out, `// GENERATED by tools/golden/generate-link-ops.mjs from @ecency/sdk ${sdkVersion} and @hiveio/dhive ${dhiveVersion}.
// Do not edit by hand. All keys derive from public throwaway seeds and control no funds.
package com.ecency.core.hive

object LinkOpGoldenVectors {
    const val USER = ${kStr(U)}

    val transactions = listOf(
${vectors.join('\n')}
    )
}
`)
console.log(`wrote ${vectors.length} transactions to ${out}`)
