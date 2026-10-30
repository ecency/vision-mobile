// Golden vectors for the wallet operations (WalletOperations.kt, WalletOps.kt), fully offline:
// - operation JSON from the @ecency/sdk 2.4.26 builders and mutations themselves (the shape the RN app broadcasts);
// - serialized bytes from dhive's serializer (@hiveio/dhive Types.Transaction), which matches hived byte for byte;
// - the transaction id and digest from the SDK (hive-tx), which must hash the dhive bytes;
// - deterministic RFC 6979 signatures (noble, extraEntropy false), plus the SDK's own randomised signature must recover.
//
// The SDK, dhive and noble come from a scratch install (not from the RN app's node_modules, which pins an older SDK):
//   npm install --prefix /tmp/golden-wallet @ecency/sdk@2.4.26 @hiveio/dhive@1.3.6 @noble/curves@2 @noble/hashes@2 @tanstack/react-query@5 react
//   GOLDEN_MODULES=/tmp/golden-wallet node native/tools/golden/generate-wallet.mjs \
//     native/shared/src/commonTest/kotlin/com/ecency/core/hive/WalletGoldenVectors.kt
//
// Every key derives from the public throwaway seed of generate.mjs and controls no funds. No network.
import { createRequire } from 'node:module'
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const base = process.env.GOLDEN_MODULES
if (!base) throw new Error('set GOLDEN_MODULES to the scratch install (see the header)')
const require = createRequire(join(base, 'package.json'))
const sdk = await import(require.resolve('@ecency/sdk'))
const dhive = require('@hiveio/dhive')
const ByteBuffer = require('@ecency/bytebuffer')
const { secp256k1 } = await import(require.resolve('@noble/curves/secp256k1.js'))
const { sha256 } = await import(require.resolve('@noble/hashes/sha2.js'))
const { HiveTxTransaction, PrivateKey, Signature } = sdk

const out = process.argv[2]
if (!out) throw new Error('usage: generate-wallet.mjs <output.kt>')
const version = (pkg) => JSON.parse(readFileSync(join(base, 'node_modules', pkg, 'package.json'), 'utf8')).version
const sdkVersion = version('@ecency/sdk')
const dhiveVersion = version('@hiveio/dhive')
if (sdkVersion !== '2.4.26') throw new Error(`expected @ecency/sdk 2.4.26, found ${sdkVersion}`)

const hex = (b) => Buffer.from(b).toString('hex')
const SEED = 'ecency-native-spike-golden-vector-throwaway'
const key = PrivateKey.fromSeed(SEED)
const pub = key.createPublic().toString()
const HEADER = { ref_block_num: 12345, ref_block_prefix: 3367463123, expiration: '2026-10-06T12:00:00' }
const U = 'spike-voter'

// Hive Engine custom_json exactly as the SDK mutations build it (use-*-engine-token.ts): keys required_auths,
// required_posting_auths, id, json.
const engine = (action, payload, contractName = 'tokens') => ['custom_json', {
  required_auths: [U], required_posting_auths: [], id: 'ssc-mainnet-hive',
  json: JSON.stringify({ contractName, contractAction: action, contractPayload: payload }),
}]
const scot = (tokens) => ['custom_json', {
  id: 'scot_claim_token', required_auths: [], required_posting_auths: [U],
  json: JSON.stringify(tokens.map((symbol) => ({ symbol }))),
}]

const cases = [
  ['transfer_multi', sdk.buildMultiTransferOps(U, 'ecency, spike-bob  spike-carol', '1.250 HIVE', 'thanks')],
  ['transfer_hbd_unicode_memo', [sdk.buildTransferOp(U, 'ecency', '0.001 HBD', 'Привет 🐝 memo')]],
  ['recurrent_transfer_daily', [sdk.buildRecurrentTransferOp(U, 'ecency', '2.000 HIVE', 'monthly', 24, 12)]],
  ['recurrent_transfer_delete', [sdk.buildRecurrentTransferOp(U, 'ecency', '0.000 HBD', '', 731, 2)]],
  ['transfer_to_savings', [sdk.buildTransferToSavingsOp(U, U, '10.500 HBD', '')]],
  ['transfer_from_savings', [sdk.buildTransferFromSavingsOp(U, U, '3.000 HIVE', 'out', 1791288000 >>> 0)]],
  ['cancel_transfer_from_savings', [sdk.buildCancelTransferFromSavingsOp(U, 4294967295)]],
  ['claim_interest', sdk.buildClaimInterestOps(U, U, '0.001 HBD', '', 123456789)],
  ['transfer_to_vesting', [sdk.buildTransferToVestingOp(U, 'spike-bob', '100.000 HIVE')]],
  ['withdraw_vesting', [sdk.buildWithdrawVestingOp(U, '123456.789012 VESTS')]],
  ['withdraw_vesting_stop', [sdk.buildWithdrawVestingOp(U, '0.000000 VESTS')]],
  ['delegate_vesting_shares', [sdk.buildDelegateVestingSharesOp(U, 'ecency', '2000000.000000 VESTS')]],
  ['undelegate_vesting_shares', [sdk.buildDelegateVestingSharesOp(U, 'ecency', '0.000000 VESTS')]],
  ['set_withdraw_vesting_route', [sdk.buildSetWithdrawVestingRouteOp(U, 'spike-bob', 2500, true)]],
  ['set_withdraw_vesting_route_remove', [sdk.buildSetWithdrawVestingRouteOp(U, 'spike-bob', 0, false)]],
  ['convert', [sdk.buildConvertOp(U, '5.000 HBD', 1791288000123 >>> 0)]],
  ['collateralized_convert', [sdk.buildCollateralizedConvertOp(U, '5.000 HIVE', 77)]],
  ['claim_reward_balance_hp', [sdk.buildClaimRewardBalanceOp(U, '0.000 HIVE', '0.000 HBD', '1234.567890 VESTS')]],
  ['claim_reward_balance_all', [sdk.buildClaimRewardBalanceOp(U, '1.234 HIVE', '0.567 HBD', '10.000000 VESTS')]],
  ['limit_order_create', [sdk.buildLimitOrderCreateOp(U, '10.000 HIVE', '2.345 HBD', false, '2026-11-02T12:00:00', 912345678)]],
  ['limit_order_create_hbd', [sdk.buildLimitOrderCreateOp(U, '1.000 HBD', '4.100 HIVE', false, '2026-11-02T12:00:00', 999999999)]],
  ['limit_order_cancel', [sdk.buildLimitOrderCancelOp(U, 912345678)]],
  ['point_transfer', [sdk.buildPointTransferOp(U, 'ecency', '12.500 POINTS', 'points memo')]],
  ['point_transfer_multi', sdk.buildMultiPointTransferOps(U, 'ecency,spike-bob', '1.000 POINT', '')],
  ['engine_transfer', [engine('transfer', { symbol: 'LEO', to: 'ecency', quantity: '1.5', memo: 'engine' })]],
  ['engine_stake', [engine('stake', { symbol: 'LEO', to: U, quantity: '0.001' })]],
  ['engine_unstake', [engine('unstake', { symbol: 'LEO', to: U, quantity: '2' })]],
  ['engine_delegate', [engine('delegate', { symbol: 'LEO', to: 'ecency', quantity: '10.12345678' })]],
  ['engine_undelegate', [engine('undelegate', { symbol: 'LEO', from: 'ecency', quantity: '10' })]],
  ['scot_claim_token', [scot(['LEO'])]],
  ['promote', [sdk.buildPromoteOp(U, 'spike-author', 'native-golden-post', 7)]],
  ['boost_plus', [sdk.buildBoostPlusOp(U, U, 30)]],
  ['rc_delegation', [sdk.buildRcDelegationOp(U, 1)]],
]

/** dhive's serializer on the SDK's JSON: the bytes hived signs (no signatures). */
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

writeFileSync(out, `// GENERATED by native/tools/golden/generate-wallet.mjs from @ecency/sdk ${sdkVersion} and @hiveio/dhive ${dhiveVersion}.
// Do not edit by hand. All keys derive from public throwaway seeds and control no funds.
package com.ecency.core.hive

object WalletGoldenVectors {
    const val USER = ${kStr(U)}

    val transactions = listOf(
${vectors.join('\n')}
    )
}

/** [operationsJson] is the SDK builders' own JSON (key order included); bytes are dhive's, the id and digest the SDK's. */
data class WalletVector(
    val name: String,
    val operationsJson: String,
    val serialized: String,
    val digest: String,
    val txId: String,
    val signature: String,
)
`)
console.log(`wrote ${vectors.length} wallet vectors to ${out}`)
