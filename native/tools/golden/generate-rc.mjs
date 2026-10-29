// Golden vectors for the resource credit estimate: JS BigInt for the big integer helper, and the SDK's own
// computeResourceCost, estimateCommentTransactionBytes, countCommentResourceUsage and estimateRcPrecheck.
//
// Run from the RN app root (which has node_modules):
//   node --no-warnings native/tools/golden/generate-rc.mjs native/shared/src/commonTest/kotlin/com/ecency/core/math/RcGoldenVectors.kt
//
// Offline: the chain values below are a snapshot shaped like rc_api's answers, nothing is fetched.
import { createRequire } from 'node:module'
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'

const out = process.argv[2]
if (!out) throw new Error('usage: generate-rc.mjs <output.kt>')
const require = createRequire(join(process.cwd(), 'package.json'))
const sdk = await import(require.resolve('@ecency/sdk'))

const kStr = (s) => JSON.stringify(s).replace(/\$/g, '\\$')
// a double exactly as Kotlin reads it back
const kNum = (n) => (Number.isFinite(n) ? `${n}`.includes('e') || `${n}`.includes('.') ? `${n}` : `${n}.0` : n > 0 ? 'Double.POSITIVE_INFINITY' : 'Double.NaN')

let seed = 0x2c0de
const rand = () => {
  seed |= 0; seed = (seed + 0x6d2b79f5) | 0
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed)
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296
}
const bigRand = (bits) => {
  let v = 0n
  for (let i = 0; i < bits; i += 16) v = (v << 16n) | BigInt(Math.floor(rand() * 65536))
  return v & ((1n << BigInt(bits)) - 1n)
}

// ---- big integer operations ----
const fixed = [0n, 1n, 2n, 0xffffffffn, 0x100000000n, 0xffffffffffffffffn, 0x10000000000000000n, 10500000000000000000n, 2n ** 53n, 2n ** 53n + 1n, 2n ** 53n + 2n, 2n ** 53n + 3n,
  2n ** 54n + 2n, 2n ** 54n + 6n, 2n ** 64n - 1n, 2n ** 127n - 1n, 2n ** 128n, 10n ** 30n, 123456789012345678901234567890n, (2n ** 53n - 1n) * 2n ** 70n + 2n ** 69n, (2n ** 53n) * 2n ** 70n + 2n ** 69n]
const pairs = []
for (const a of fixed) for (const b of [0n, 1n, 3n, 0xffffffffn, 0x100000001n, 9973n, 10500000000000000000n]) pairs.push([a, b])
for (let i = 0; i < 60; i++) pairs.push([bigRand(8 + Math.floor(rand() * 180)), bigRand(1 + Math.floor(rand() * 130))])
const ops = pairs.map(([a, b]) => {
  const shift = Math.floor(rand() * 140)
  return `        Op(${kStr(a.toString())}, ${kStr(b.toString())}, ${kStr((a + b).toString())}, ${kStr((a * b).toString())}, ${b === 0n ? 'null' : kStr((a / b).toString())}, ${shift}, ${kStr((a >> BigInt(shift)).toString())}, ${kNum(Number(a))}, ${Math.sign(Number(a > b) - Number(a < b))}),`
})
const doubles = [0, 0.9, 1, 1.5, 4294967296, 9007199254740991, 9007199254740992, 1.05e19, 1.8446744073709552e19, 2.5e25, 1e30, 3.4e38].map((d) => `        ${kNum(d)} to ${kStr(BigInt(Math.trunc(d)).toString())},`)

// ---- the chain's numbers (a snapshot in the shape of rc_api.get_resource_params / get_rc_stats) ----
const curve = (a, b, shift) => ({ coeff_a: a, coeff_b: b, shift })
const params = {
  resource_params: {
    resource_history_bytes: { resource_dynamics_params: { resource_unit: 1, budget_per_time_unit: 43403, pool_eq: '216404314004', max_pool_size: '432808628007' }, price_curve_params: curve('12981647055416481792', 216404314004, 49) },
    resource_new_accounts: { resource_dynamics_params: { resource_unit: 10000, budget_per_time_unit: 797, pool_eq: 157691079, max_pool_size: 157691079 }, price_curve_params: curve('16484671763857882971', 157691079, 51) },
    resource_market_bytes: { resource_dynamics_params: { resource_unit: 10, budget_per_time_unit: 1736, pool_eq: '8655485248', max_pool_size: '17310970496' }, price_curve_params: curve('12981647055416481792', 8655485248, 54) },
    resource_state_bytes: { resource_dynamics_params: { resource_unit: 1, budget_per_time_unit: 231481, pool_eq: '1154131337088', max_pool_size: '2308262674175' }, price_curve_params: curve('12981647055416481792', 1154131337088, 47) },
    resource_execution_time: { resource_dynamics_params: { resource_unit: 1, budget_per_time_unit: 82191781, pool_eq: '409799085056', max_pool_size: '819598170112' }, price_curve_params: curve('12981647055416481792', 409799085056, 48) },
  },
  size_info: {
    resource_state_bytes: { comment_base_size: 1299600, comment_permlink_char_size: 5475, comment_beneficiaries_member_size: 365, vote_size: 4745, transaction_base_size: 4672 },
    resource_execution_time: { comment_time: 114100, comment_options_time: 13200, vote_time: 138800, transaction_time: 22000, verify_authority_time: 117000 },
  },
}
const stats = { regen: 2538464281472, pool: ['211123049728', '129371531', '8600000000', '1100000000000', '300000000000'], share: [2500, 0, 180, 6400, 920], ops: { comment_operation: { avg_cost: 1500000000 } } }

const cost = []
for (const [name, entry] of Object.entries(params.resource_params)) {
  for (const count of [0, 1, 250, 46620, 1500000, 123456789]) for (const regenShare of [0, 1, 634616070368, 2538464281472, 1.2e15]) for (const pool of [0, 1, Number(entry.resource_dynamics_params.pool_eq), 5e12]) {
    cost.push(`        Cost(${kStr(String(entry.price_curve_params.coeff_a))}, ${kStr(String(entry.price_curve_params.coeff_b))}, ${entry.price_curve_params.shift}, ${kNum(pool)}, ${kNum(count)}, ${kNum(regenShare)}, ${kNum(sdk.computeResourceCost(entry.price_curve_params, pool, count, regenShare))}), // ${name}`)
  }
}

const bodies = ['', 'short reply', 'x'.repeat(127), 'x'.repeat(128), 'x'.repeat(16383), 'y'.repeat(16384), 'Ünïcödé 😀 '.repeat(500), 'z'.repeat(46620)]
const comments = []
let n = 0
for (const body of bodies) {
  for (const variant of [0, 1, 2, 3]) {
    const op = {
      author: ['spike-voter', 'a.b-c1'][n % 2], permlink: ['my-first-post', 're-alice-20261006t120000000z', 'x'.repeat(200)][n % 3], parent_author: variant === 1 ? 'alice' : '',
      parent_permlink: variant === 1 ? 'her-post' : 'hive-125125', title: variant === 1 ? '' : ['Title', 'Ünï title 😀', ''][n % 3], body,
      json_metadata: JSON.stringify({ tags: ['ecency', 'test'], app: 'ecency/4.0.0-mobile', format: 'markdown+html', image: n % 2 ? ['https://images.ecency.com/a.png'] : [] }),
    }
    const options = variant === 0 ? undefined : variant === 1 ? undefined : variant === 2 ? {} : { beneficiaries: [{ account: 'ecency', weight: 500 }, { account: 'threespeakfund', weight: 1100 }].slice(0, 1 + (n % 2)) }
    const bytes = sdk.estimateCommentTransactionBytes({ op, options })
    const mana = [0, 5e8, 3e9, 2.5e10, 9e12][n % 5]
    const rcAccount = { max_rc: String(mana * 2), rc_manabar: { current_mana: String(mana), last_update_time: 1791288000 - 3600 * (n % 4) } }
    // the SDK reads the clock: pinned to the test apps' "now"
    const realNow = Date.now
    Date.now = () => 1791288000000
    const result = sdk.estimateRcPrecheck({ rcAccount, rcStats: stats, rcParams: params, operation: 'comment_operation', payload: { kind: 'comment', op, options } })
    Date.now = realNow
    comments.push(`        Comment(${kStr(op.author)}, ${kStr(op.permlink)}, ${kStr(op.parent_author)}, ${kStr(op.parent_permlink)}, ${kStr(op.title)}, ${body.length > 200 ? `${kStr(body[0])}.repeat(${body.length})` : kStr(body)}.let { b -> if (${body.startsWith('Ünï')}) ${kStr('Ünïcödé 😀 ')}.repeat(500) else b }, ${kStr(op.json_metadata)}, ${options ? (options.beneficiaries ? `listOf(${options.beneficiaries.map((b) => `${kStr(b.account)} to ${b.weight}`).join(', ')})` : 'emptyList()') : 'null'}, ${options ? !!options.beneficiaries : false}, ${bytes}, ${kStr(rcAccount.max_rc)}, ${kStr(rcAccount.rc_manabar.current_mana)}, ${rcAccount.rc_manabar.last_update_time}, ${result.ready}, ${kNum(result.cost)}, ${kNum(result.currentMana)}, ${kNum(result.estimatedCost)}, ${result.willLikelyFail}, ${kNum(result.deficit)}),`)
    n++
  }
}

writeFileSync(out, `package com.ecency.core.math

// Generated by native/tools/golden/generate-rc.mjs: JS BigInt, and @ecency/sdk's resource credit estimate over a
// snapshot shaped like rc_api's answers. Do not edit by hand.
internal object RcGoldenVectors {
    class Op(val a: String, val b: String, val sum: String, val product: String, val quotient: String?, val shift: Int, val shifted: String, val asNumber: Double, val compare: Int)
    class Cost(val coeffA: String, val coeffB: String, val shift: Int, val pool: Double, val count: Double, val regenShare: Double, val cost: Double)
    class Comment(
        val author: String, val permlink: String, val parentAuthor: String, val parentPermlink: String, val title: String, val body: String, val jsonMetadata: String,
        /** null: no comment_options; else its beneficiaries. */
        val beneficiaries: List<Pair<String, Int>>?, val hasBeneficiaryExtension: Boolean, val transactionBytes: Int,
        val maxRc: String, val currentMana: String, val lastUpdate: Long,
        val ready: Boolean, val cost: Double, val mana: Double, val estimatedCost: Double, val willLikelyFail: Boolean, val deficit: Double,
    )

    const val NOW_MILLIS = 1791288000000L
    const val PARAMS = ${kStr(JSON.stringify(params))}
    const val STATS = ${kStr(JSON.stringify(stats))}

    val ops: List<Op> = listOf(
${ops.join('\n')}
    )

    val doubles: List<Pair<Double, String>> = listOf(
${doubles.join('\n')}
    )

    val costs: List<Cost> by lazy { ${Array.from({ length: Math.ceil(cost.length / 60) }, (_, i) => `costs${i}()`).join(' + ')} }
${Array.from({ length: Math.ceil(cost.length / 60) }, (_, i) => `    private fun costs${i}(): List<Cost> = listOf(\n${cost.slice(i * 60, i * 60 + 60).join('\n')}\n    )`).join('\n')}

    val comments: List<Comment> = listOf(
${comments.join('\n')}
    )
}
`)
console.log(`wrote ${ops.length} operations, ${cost.length} costs, ${comments.length} comments`)
