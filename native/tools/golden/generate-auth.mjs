// Generates golden vectors for the Kotlin auth core (com.ecency.core.auth) from what the RN app ships:
// @ecency/sdk (keys, signatures), crypto-js (HiveAuth payload encryption), hive-uri (signing links),
// the app's operations.json and Node's WHATWG URL (callback URLs).
//
// Run from the RN app root (it has node_modules):
//   node native/tools/golden/generate-auth.mjs native/shared/src/commonTest/kotlin/com/ecency/core/auth/AuthGoldenVectors.kt
//
// Every key derives from a public throwaway seed and controls no funds. Nothing here talks to the network.
import { createRequire } from 'node:module'
import { writeFileSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { kt } from '../render/kotlin-literal.mjs'

const require = createRequire(join(process.cwd(), 'package.json'))
const sdk = await import(require.resolve('@ecency/sdk'))
const { secp256k1 } = await import(require.resolve('@noble/curves/secp256k1.js'))
const CryptoJS = require('crypto-js')
const hiveuri = require('hive-uri')
const operations = JSON.parse(readFileSync(join(process.cwd(), 'src/utils/operations.json'), 'utf8'))
const { PrivateKey, Signature, sha256 } = sdk

const out = process.argv[2]
if (!out) throw new Error('usage: generate-auth.mjs <output.kt>')

const hex = (b) => Buffer.from(b).toString('hex')
const USER = 'spike-user'
const PASSWORD = 'P5ThrowawayGoldenVectorPasswordNotReal'
const posting = PrivateKey.fromLogin(USER, PASSWORD, 'posting')

// RN utils/b64.ts and hive-uri: base64 of the UTF-8 bytes, URL-safe alphabet with '.' padding.
const b64uEnc = (s) => Buffer.from(s).toString('base64').replace(/[+/=]/g, (m) => ({ '+': '-', '/': '_', '=': '.' })[m])

// Deterministic (RFC 6979) signature encoded like PrivateKey.sign(), so Kotlin must match byte for byte.
const detSign = (message, key) => {
  const rv = secp256k1.sign(sha256(message), key.key, { extraEntropy: false, format: 'recovered', prehash: false })
  return (rv[0] + 31).toString(16) + hex(rv.subarray(1))
}

// ---- HiveSigner messages (RN utils/hive-signer-helper.ts) ----
const messages = []
for (const [type, audience, millis] of [
  ['code', undefined, 1759800000123],
  ['code', undefined, 1759800000000],
  ['code', undefined, 1759800000100],
  ['login', 'honeyback://hive', 1759800000456],
]) {
  const signed_message = audience ? { type, app: 'ecency.app', audience } : { type, app: 'ecency.app' }
  const obj = { signed_message, authors: [USER], timestamp: millis / 1000 }
  const message = JSON.stringify(obj)
  const signature = detSign(message, posting)
  const code = b64uEnc(JSON.stringify({ ...obj, signatures: [signature] }))
  // The SDK's own randomised signature, as the RN app produces it, must recover to the posting key.
  const sdkSignature = posting.sign(sha256(message)).toString()
  if (Signature.from(sdkSignature).getPublicKey(sha256(message)).toString() !== posting.createPublic().toString()) {
    throw new Error('sdk signature does not recover')
  }
  messages.push(`        MessageVector(${kt(type)}, ${kt(audience ?? null)}, ${millis}L, ${kt(message)}, ${kt(code)}, ${kt(sdkSignature)}),`)
}

// HiveAuth login challenge (RN useHiveAuth): type refresh, signed by the wallet, sent as btoa(JSON).
const hasMillis = 1759800000789
const challengeObj = { signed_message: { type: 'refresh', app: 'ecency.app' }, authors: [USER], timestamp: hasMillis / 1000 }
const challenge = JSON.stringify(challengeObj)
const challengeSig = detSign(challenge, posting)
const hiveAuthCode = Buffer.from(JSON.stringify({ ...challengeObj, signatures: [challengeSig] }), 'binary').toString('base64')

// ---- crypto-js AES (hive-auth-wrapper payloads) ----
const AUTH_KEY = '3f0c2e9a-1b7d-4c55-9e2a-6d8f0b1c2a3e'
const plaintexts = ['hello', '', '{"app":{"name":"Ecency"}}', 'unicode \u{1F41D} üñï', 'x'.repeat(100)]
const decryptVectors = plaintexts.map((p) => `        CipherVector(${kt(p)}, ${kt(AUTH_KEY)}, ${kt(CryptoJS.AES.encrypt(p, AUTH_KEY).toString())}),`)
const SALT = '0102030405060708'
const fixedSalt = plaintexts.map((p) => {
  const salt = CryptoJS.enc.Hex.parse(SALT)
  const derived = CryptoJS.kdf.OpenSSL.execute(AUTH_KEY, 8, 4, salt)
  const enc = CryptoJS.AES.encrypt(p, derived.key, { iv: derived.iv })
  const formatted = CryptoJS.format.OpenSSL.stringify(CryptoJS.lib.CipherParams.create({ ciphertext: enc.ciphertext, salt }))
  if (CryptoJS.AES.decrypt(formatted, AUTH_KEY).toString(CryptoJS.enc.Utf8) !== p) throw new Error('fixed-salt roundtrip')
  return `        CipherVector(${kt(p)}, ${kt(AUTH_KEY)}, ${kt(formatted)}),`
})

// ---- HiveAuth payloads (hive-auth-wrapper + RN useHiveAuth) ----
const APP_META = { name: 'Ecency', description: 'Decentralised Social Blogging', icon: undefined }
const authPayload = JSON.stringify({ app: APP_META, challenge: { key_type: 'posting', challenge }, token: undefined })
const authPayloadNoChallenge = JSON.stringify({ app: APP_META, challenge: undefined, token: undefined })
const voteOp = ['vote', { voter: USER, author: 'ecency', permlink: 'native-golden-vote', weight: 10000 }]
const signPayload = JSON.stringify({ key_type: 'posting', ops: [voteOp], broadcast: true, nonce: 1759800000999 })
const HAS_HOST = 'wss://hive-auth.arcange.eu/'
const authLink = 'has://auth_req/' + Buffer.from(JSON.stringify({ account: USER, uuid: 'b5a9c1d2-0000-4000-8000-000000000001', key: AUTH_KEY, host: HAS_HOST })).toString('base64')

// ---- hive-uri ----
const transferOp = ['transfer', { from: '__signer', to: 'ecency', amount: '1.500 HIVE', memo: 'line one\nline "two" \u{1F41D}' }]
const uriCases = [
  ['op_vote', hiveuri.encodeOp(voteOp)],
  ['ops_two', hiveuri.encodeOps([voteOp, transferOp])],
  ['op_params', hiveuri.encodeOp(transferOp, { callback: 'https://example.com/cb?id={{id}}', no_broadcast: true, authority: 'active', signer: USER })],
  ['tx', hiveuri.encodeTx({ ref_block_num: 1, ref_block_prefix: 2, expiration: '2026-10-06T12:00:00', extensions: [], operations: [voteOp] })],
].map(([name, uri]) => {
  const d = hiveuri.decode(uri)
  return `        UriVector(${kt(name)}, ${kt(uri)}, ${kt(JSON.stringify(d.tx))}, ${kt(d.params.callback ?? null)}, ${!!d.params.no_broadcast}, ${kt(d.params.authority ?? null)}, ${kt(d.params.signer ?? null)}),`
})
const resolved = hiveuri.resolveTransaction(hiveuri.decode(hiveuri.encodeOp(transferOp)).tx, {}, {
  ref_block_num: 12345, ref_block_prefix: 3367463123, expiration: '2026-10-06T12:00:00', signers: [USER], preferred_signer: USER,
}).tx
resolved.ref_block_num = parseInt(`${resolved.ref_block_num}`, 10)
resolved.ref_block_prefix = parseInt(`${resolved.ref_block_prefix}`, 10)

// ---- operations.json, as RN's findParentKey reads it ----
const findParentKey = (obj, value, parentKey = null) => {
  for (const key in obj) {
    if (obj[key] === value) return parentKey
    if (typeof obj[key] === 'object') { const f = findParentKey(obj[key], value, key); if (f) return f }
  }
  return null
}
const opRows = Object.keys(operations).map((k) =>
  `        OpRow(${kt(k)}, ${kt(operations[k].name)}, ${kt(operations[k].authority || '')}, ${kt(findParentKey(operations[k], '__signer'))}),`)

// ---- callback URLs (RN _openCallback: URL + searchParams.set) ----
const callbackCases = [
  ['honeyback://hive', 'r1', [['status', 'success'], ['username', USER], ['code', 'a+b/c=']]],
  ['https://Games-API.ecency.com/v1/cb?x=1&status=old#frag', null, [['status', 'error'], ['error', 'user_cancelled'], ['message', 'two words & more']]],
  ['myapp:callback', 'id 2', [['status', 'success'], ['empty', '']]],
  ['anyapp://cb/path?y=a%20b&y=c', '', [['y', 'z'], ['posting_key', '5Kfake']]],
].map(([cb, rid, payload]) => {
  const u = new URL(cb)
  payload.forEach(([k, v]) => { if (v) u.searchParams.set(k, v) })
  if (rid) u.searchParams.set('request_id', rid)
  return `        CallbackVector(${kt(cb)}, ${kt(rid)}, listOf(${payload.map(([k, v]) => `${kt(k)} to ${kt(v)}`).join(', ')}), ${kt(u.toString())}),`
})

const b64uCases = ['hello', 'unicode \u{1F41D} ü', '{"a":[1,2]}', 'ab?'].map((s) => `        ${kt(s)} to ${kt(b64uEnc(s))},`)

writeFileSync(out, `// GENERATED by native/tools/golden/generate-auth.mjs from @ecency/sdk, crypto-js, hive-uri and the RN app.
// Do not edit by hand. All keys derive from public throwaway seeds and control no funds.
package com.ecency.core.auth

object AuthGoldenVectors {
    const val USER = ${kt(USER)}
    const val PASSWORD = ${kt(PASSWORD)}
    const val POSTING_WIF = ${kt(posting.toString())}
    const val POSTING_PUBLIC = ${kt(posting.createPublic().toString())}

    val messages = listOf(
${messages.join('\n')}
    )

    const val HIVE_AUTH_MILLIS = ${hasMillis}L
    const val HIVE_AUTH_CHALLENGE = ${kt(challenge)}
    const val HIVE_AUTH_SIGNATURE = ${kt(challengeSig)}
    const val HIVE_AUTH_CODE = ${kt(hiveAuthCode)}

    const val AUTH_KEY = ${kt(AUTH_KEY)}
    const val FIXED_SALT_HEX = ${kt(SALT)}
    val cryptoJsDecrypt = listOf(
${decryptVectors.join('\n')}
    )
    val cryptoJsFixedSalt = listOf(
${fixedSalt.join('\n')}
    )

    const val AUTH_PAYLOAD = ${kt(authPayload)}
    const val AUTH_PAYLOAD_NO_CHALLENGE = ${kt(authPayloadNoChallenge)}
    const val SIGN_PAYLOAD = ${kt(signPayload)}
    const val SIGN_OP = ${kt(JSON.stringify([voteOp]))}
    const val AUTH_LINK = ${kt(authLink)}
    const val AUTH_LINK_UUID = "b5a9c1d2-0000-4000-8000-000000000001"

    val uris = listOf(
${uriCases.join('\n')}
    )
    const val RESOLVE_INPUT = ${kt(hiveuri.encodeOp(transferOp))}
    const val RESOLVED_TX = ${kt(JSON.stringify(resolved))}

    val operations = listOf(
${opRows.join('\n')}
    )

    val callbacks = listOf(
${callbackCases.join('\n')}
    )

    val b64u = listOf(
${b64uCases.join('\n')}
    )
}

data class MessageVector(val type: String, val audience: String?, val millis: Long, val message: String, val code: String, val sdkSignature: String)
data class CipherVector(val plain: String, val passphrase: String, val cipher: String)
data class UriVector(val name: String, val uri: String, val txJson: String, val callback: String?, val noBroadcast: Boolean, val authority: String?, val signer: String?)
data class OpRow(val name: String, val title: String, val authority: String, val signerField: String?)
data class CallbackVector(val callback: String, val requestId: String?, val payload: List<Pair<String, String>>, val url: String)
`)
console.log(`wrote ${messages.length} message, ${decryptVectors.length + fixedSalt.length} cipher, ${uriCases.length} uri, ${opRows.length} operation and ${callbackCases.length} callback vectors to ${out}`)
