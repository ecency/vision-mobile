// Golden vectors for the platform wave (PlatformGoldenVectors.kt), fully offline:
// - governance operations (update_proposal_votes, account_witness_vote, account_witness_proxy): the SDK builders'
//   JSON, dhive's bytes, the SDK's transaction id and digest, the deterministic RFC 6979 signature;
// - the dApp bridge: the operations RN's useHiveBridgeHandler._buildOperations builds for each Keychain request
//   (the function is repeated below over the SDK's own builders, line for line), for synthetic requests;
// - signBuffer: the SDK's sha256 of the message and the deterministic signature over it (the SDK's own randomised
//   signature must recover to the same key).
//
// Same scratch install as generate-wallet.mjs:
//   npm install --prefix /tmp/golden-wallet @ecency/sdk@2.4.26 @hiveio/dhive@1.3.6 @noble/curves@2 @noble/hashes@2 @tanstack/react-query@5 react
//   GOLDEN_MODULES=/tmp/golden-wallet node native/tools/golden/generate-platform.mjs \
//     native/shared/src/commonTest/kotlin/com/ecency/core/hive/PlatformGoldenVectors.kt
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
if (!out) throw new Error('usage: generate-platform.mjs <output.kt>')
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

// ---- governance transactions ----
const cases = [
  ['update_proposal_votes', [sdk.buildProposalVoteOp(U, [342], true)]],
  ['update_proposal_votes_remove_many', [sdk.buildProposalVoteOp(U, [0, 7, 4294967296], false)]],
  ['account_witness_vote', [sdk.buildWitnessVoteOp(U, 'good-karma', true)]],
  ['account_witness_unvote', [sdk.buildWitnessVoteOp(U, 'ecency', false)]],
  ['account_witness_proxy', [sdk.buildWitnessProxyOp(U, 'ecency')]],
  ['account_witness_proxy_clear', [sdk.buildWitnessProxyOp(U, '')]],
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

// ---- the dApp bridge: RN useHiveBridgeHandler._buildOperations, line for line ----
const HIVE_PER_MVESTS = 591.234567
// RN utils/conversions hpToVests
const hpToVests = (hp, hivePerMVests) => {
  if (!hp || !hivePerMVests) return 0
  return (hp * 1e6) / hivePerMVests
}
function buildOperations(type, data, account) {
  switch (type) {
    case 'vote':
      return [sdk.buildVoteOp(account, data.author, data.permlink, data.weight)]
    case 'post': {
      const ops = [
        sdk.buildCommentOp(
          account, data.permlink, data.parent_username || '', data.parent_perm || '', data.title || '', data.body || '',
          typeof data.json_metadata === 'string' ? JSON.parse(data.json_metadata) : data.json_metadata || {},
        ),
      ]
      if (data.comment_options) {
        const opts = typeof data.comment_options === 'string' ? JSON.parse(data.comment_options) : data.comment_options
        ops.push(sdk.buildCommentOptionsOp(
          account, data.permlink, opts.max_accepted_payout || '1000000.000 HBD',
          opts.percent_hbd ?? opts.percent_steem_dollars ?? 10000,
          opts.allow_votes !== false, opts.allow_curation_rewards !== false, opts.extensions || [],
        ))
      }
      return ops
    }
    case 'custom': {
      const jsonStr = typeof data.json === 'string' ? data.json : JSON.stringify(data.json)
      const method = data.method?.toLowerCase?.()
      const isActive = method === 'active'
      return [['custom_json', {
        required_auths: isActive ? [account] : [],
        required_posting_auths: isActive ? [] : [account],
        id: data.id,
        json: jsonStr,
      }]]
    }
    case 'transfer':
      return [sdk.buildTransferOp(account, data.to, `${parseFloat(data.amount).toFixed(3)} ${data.currency}`, data.memo || '')]
    case 'delegation': {
      const unit = (data.unit || 'HP').toUpperCase()
      if (unit === 'HP') {
        const vests = hpToVests(parseFloat(data.amount), HIVE_PER_MVESTS)
        return [sdk.buildDelegateVestingSharesOp(account, data.delegatee, `${vests.toFixed(6)} VESTS`)]
      }
      return [sdk.buildDelegateVestingSharesOp(account, data.delegatee, `${parseFloat(data.amount).toFixed(6)} VESTS`)]
    }
    case 'witnessVote':
      return [sdk.buildWitnessVoteOp(account, data.witness, data.vote)]
    case 'proxy':
      return [sdk.buildWitnessProxyOp(account, data.proxy || '')]
    case 'powerUp':
      return [sdk.buildTransferToVestingOp(account, data.recipient || account, `${parseFloat(data.steem).toFixed(3)} HIVE`)]
    case 'powerDown': {
      const vestsPD = hpToVests(parseFloat(data.steem_power), HIVE_PER_MVESTS)
      return [sdk.buildWithdrawVestingOp(account, `${vestsPD.toFixed(6)} VESTS`)]
    }
    case 'recurrentTransfer':
      return [sdk.buildRecurrentTransferOp(account, data.to, `${parseFloat(data.amount).toFixed(3)} ${data.currency}`, data.memo || '', data.recurrence, data.executions)]
    case 'savings': {
      const savingsAmount = `${parseFloat(data.amount).toFixed(3)} ${data.currency}`
      if (data.operation === 'deposit') return [sdk.buildTransferToSavingsOp(account, data.to || account, savingsAmount, data.memo || '')]
      if (data.operation === 'withdraw_cancel') return [sdk.buildCancelTransferFromSavingsOp(account, data.request_id || 0)]
      // RN takes a time-based request id; the vectors pin it (REQUEST_ID)
      return [sdk.buildTransferFromSavingsOp(account, data.to || account, savingsAmount, data.memo || '', REQUEST_ID)]
    }
    case 'convert': {
      if (data.collaterized) return [sdk.buildCollateralizedConvertOp(account, `${parseFloat(data.amount).toFixed(3)} HIVE`, REQUEST_ID)]
      return [sdk.buildConvertOp(account, `${parseFloat(data.amount).toFixed(3)} HBD`, REQUEST_ID)]
    }
    case 'sendToken':
      return [sdk.buildEngineOp(account, 'transfer', { symbol: data.currency, to: data.to, quantity: data.amount, memo: data.memo || '' })]
    case 'broadcast':
      return Array.isArray(data.operations) ? data.operations : null
    default:
      return null
  }
}
const REQUEST_ID = 1791288001

const bridge = [
  ['vote', { username: U, author: 'spike-author', permlink: 'native-golden-post', weight: 5000 }],
  ['vote', { username: U, author: 'spike-author', permlink: 'native-golden-post', weight: -10000 }],
  ['post', { username: U, title: 'Hello', body: 'Body text 🐝', parent_perm: 'hive-125125', parent_username: '', json_metadata: '{"tags":["hive"],"app":"dapp/1.0"}', permlink: 'hello-dapp' }],
  ['post', { username: U, title: '', body: 'a reply', parent_perm: 'native-golden-post', parent_username: 'spike-author', json_metadata: { app: 'dapp' }, permlink: 're-hello', comment_options: '{"max_accepted_payout":"0.000 HBD","percent_hbd":0,"allow_votes":true,"allow_curation_rewards":false,"extensions":[]}' }],
  ['post', { username: U, title: 'Opts', body: 'b', parent_perm: 'hive', parent_username: '', json_metadata: '{}', permlink: 'opts-object', comment_options: { percent_steem_dollars: 5000, extensions: [[0, { beneficiaries: [{ account: 'ecency', weight: 500 }] }]] } }],
  ['custom', { username: U, id: 'follow', method: 'Posting', json: '["follow",{"follower":"spike-voter","following":"ecency","what":["blog"]}]', display_msg: 'Follow' }],
  ['custom', { username: U, id: 'ssc-mainnet-hive', method: 'Active', json: { contractName: 'tokens', contractAction: 'transfer', contractPayload: { symbol: 'LEO', to: 'ecency', quantity: '1', memo: '' } } }],
  ['transfer', { username: U, to: 'ecency', amount: '1.5', memo: 'dapp memo', currency: 'HIVE', enforce: false }],
  ['transfer', { username: U, to: 'ecency', amount: '0.001', memo: '', currency: 'HBD' }],
  ['sendToken', { username: U, to: 'ecency', amount: '12.5', memo: 'engine', currency: 'LEO' }],
  ['delegation', { username: U, delegatee: 'ecency', amount: '100.000', unit: 'HP' }],
  ['delegation', { username: U, delegatee: 'ecency', amount: '2000000.123456', unit: 'VESTS' }],
  ['delegation', { username: U, delegatee: 'ecency', amount: '0', unit: 'HP' }],
  ['witnessVote', { username: U, witness: 'good-karma', vote: true }],
  ['witnessVote', { username: U, witness: 'ecency', vote: false }],
  ['proxy', { username: U, proxy: 'ecency' }],
  ['proxy', { username: U, proxy: '' }],
  ['powerUp', { username: U, recipient: 'spike-bob', steem: '10.000' }],
  ['powerUp', { username: U, recipient: '', steem: '0.5' }],
  ['powerDown', { username: U, steem_power: '50.000' }],
  ['powerDown', { username: U, steem_power: '0' }],
  ['recurrentTransfer', { username: U, to: 'ecency', amount: '2', currency: 'HBD', memo: 'monthly', recurrence: 720, executions: 12 }],
  ['savings', { username: U, to: U, amount: '3', currency: 'HBD', operation: 'deposit', memo: '' }],
  ['savings', { username: U, to: '', amount: '1.250', currency: 'HIVE', operation: 'withdraw', memo: 'out' }],
  ['savings', { username: U, operation: 'withdraw_cancel', request_id: 4242, amount: '0', currency: 'HIVE' }],
  ['convert', { username: U, amount: '5', collaterized: false }],
  ['convert', { username: U, amount: '5.5', collaterized: true }],
  ['broadcast', { username: U, method: 'Active', operations: [['transfer', { from: U, to: 'ecency', amount: '1.000 HIVE', memo: '' }], ['vote', { voter: U, author: 'a', permlink: 'p', weight: 100 }]] }],
]
const bridgeVectors = bridge.map(([type, data]) => {
  const ops = buildOperations(type, data, U)
  return `        BridgeVector(${kStr(type)}, ${kStr(JSON.stringify(data))}, ${kStr(JSON.stringify(ops))}),`
})

// ---- signBuffer: sha256(message) signed with the posting key ----
const messages = ['ecency-login-1791288000', 'Привет 🐝 sign me', '{"login":"spike-voter","ts":1791288000}', '']
const signBuffers = messages.map((message) => {
  const digest = sdk.sha256(message)
  const rv = secp256k1.sign(digest, key.key, { extraEntropy: false, format: 'recovered', prehash: false })
  const signature = (rv[0] + 31).toString(16) + hex(rv.subarray(1))
  if (Signature.from(signature).getPublicKey(digest).toString() !== pub) throw new Error('sign buffer: no recovery')
  const sdkSignature = key.sign(digest).toString()
  if (Signature.from(sdkSignature).getPublicKey(digest).toString() !== pub) throw new Error('sign buffer: sdk signature does not recover')
  if (hex(digest) !== hex(sha256(Buffer.from(message, 'utf8')))) throw new Error('sign buffer: sdk sha256 is not sha256 of the UTF-8 bytes')
  return `        SignBufferVector(${kStr(message)}, ${kStr(hex(digest))}, ${kStr(signature)}),`
})

writeFileSync(out, `// GENERATED by native/tools/golden/generate-platform.mjs from @ecency/sdk ${sdkVersion} and @hiveio/dhive ${dhiveVersion}.
// Do not edit by hand. All keys derive from public throwaway seeds and control no funds.
package com.ecency.core.hive

object PlatformGoldenVectors {
    const val USER = ${kStr(U)}

    /** The rate the bridge vectors convert HP with. */
    const val HIVE_PER_MVESTS = ${HIVE_PER_MVESTS}

    /** The request id RN's time-based savings withdraw and convert take, pinned. */
    const val REQUEST_ID = ${REQUEST_ID}L

    val transactions = listOf(
${vectors.join('\n')}
    )

    /** RN useHiveBridgeHandler._buildOperations on synthetic Keychain requests: [dataJson] in, [operationsJson] out. */
    val bridge = listOf(
${bridgeVectors.join('\n')}
    )

    /** signBuffer: the SDK's sha256 of [message] and the deterministic posting key signature over it. */
    val signBuffer = listOf(
${signBuffers.join('\n')}
    )
}

data class BridgeVector(val type: String, val dataJson: String, val operationsJson: String)

data class SignBufferVector(val message: String, val digest: String, val signature: String)
`)
console.log(`wrote ${vectors.length} transactions, ${bridgeVectors.length} bridge cases, ${signBuffers.length} sign buffers to ${out}`)
