// Golden vectors for the email digest sheet (RN components/newsletterDigestSheet, providers/queries/newsletterQueries.ts,
// utils/firstPublishDigest.ts) and the relay requests of the @ecency/sdk the RN app ships. Run from the RN app root:
//   node native/tools/golden/generate-newsletter.mjs native/shared/src/commonTest/kotlin/com/ecency/core/api/ecency/NewsletterGoldens.kt
//
// Offline: fetch is replaced by a recorder. Synthetic accounts, addresses and tokens only.
import { createRequire } from 'node:module'
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'

const require = createRequire(join(process.cwd(), 'package.json'))
const sdk = await import(require.resolve('@ecency/sdk'))

const out = process.argv[2]
if (!out) throw new Error('usage: generate-newsletter.mjs <output.kt>')
const k = (s) => (s === null || s === undefined ? 'null' : JSON.stringify(s).replace(/\$/g, '\\$'))

// ---- the SDK's relay requests, recorded ----
const calls = []
let answer = { status: 200, body: {} }
globalThis.fetch = async (url, init = {}) => {
  calls.push({ url: String(url), method: init.method || 'GET', headers: init.headers || {}, body: init.body ?? null })
  return { ok: answer.status >= 200 && answer.status < 300, status: answer.status, json: async () => answer.body }
}
const CODE = 'token-spike-alice'
const requestCases = []
const record = async (name, run) => {
  calls.length = 0
  await run()
  const c = calls[0]
  const headers = Object.entries(c.headers).map(([h, v]) => `${k(h)} to ${k(v)}`).join(', ')
  requestCases.push(`        RequestCase(${k(name)}, ${k(c.method)}, ${k(c.url)}, mapOf(${headers}), ${k(c.body)}),`)
}
answer = { status: 200, body: { subscriptions: [] } }
await record('subscriptions', () => sdk.getDigestSubscriptionsRequest(CODE))
answer = { status: 200, body: { status: 'pending_confirmation' } }
// newsletterDigestSheet._handleSubscribe: { email, type, target, cadence, source }
await record('subscribe_own', () => sdk.subscribeDigestRequest({ email: 'alice@example.com', type: 'own', target: 'spike-alice', cadence: 'weekly', source: 'mobile-app' }, CODE))
await record('subscribe_quotes', () => sdk.subscribeDigestRequest({ email: 'a"b\\c@example.com', type: 'community', target: 'hive-125125', cadence: 'monthly', source: 'mobile-app' }, CODE))
answer = { status: 200, body: {} }
await record('leave', () => sdk.leaveDigestRequest('sub-123', CODE))
await record('leave_encoded', () => sdk.leaveDigestRequest('a/b c?d#e&f=g+h%i:é🐝', CODE))

// how the SDK reads answers: a non-2xx status and a body that is not an object are errors
const answerCases = []
for (const [name, status, body] of [
  ['ok_list', 200, { subscriptions: [{ id: 's1', email: 'a@example.com', account: 'spike-alice', type: 'own', target: 'spike-alice', cadence: 'weekly', status: 'active', created_at: '2026-08-25T00:00:00Z' }] }],
  ['ok_without_list', 200, {}],
  ['not_an_object', 200, 'nope'],
  ['null_body', 200, null],
  ['too_many', 429, { error: 'rate_limited' }],
  ['server_error', 500, {}],
  ['unauthorized', 401, { error: 'unauthorized' }],
]) {
  answer = { status, body }
  let result
  try {
    const list = await sdk.getDigestSubscriptionsRequest(CODE)
    result = `ok:${list.length}`
  } catch (e) {
    result = `error:${e.status}`
  }
  answerCases.push(`        AnswerCase(${k(name)}, ${status}, ${k(JSON.stringify(body))}, ${k(result)}),`)
}

// ---- RN src/providers/queries/newsletterQueries.ts @ cbcca86791: findDigestSubscription, knownDigestAddress ----
const findDigestSubscription = (subscriptions, type, target) =>
  (subscriptions ?? []).find((s) => s.type === type && s.target.toLowerCase() === target.toLowerCase())
const knownDigestAddress = (subscriptions) => subscriptions?.find((s) => s.email)?.email ?? null
// ---- RN src/components/newsletterDigestSheet/newsletterDigestSheet.tsx @ cbcca86791: EMAIL_RE and the form's derived values ----
const EMAIL_RE = /^\S+@\S+\.\S+$/
const form = ({ subscriptions, type, target, email, cadence, pending }) => {
  const subscription = findDigestSubscription(subscriptions, type, target)
  const knownAddress = subscription?.email || knownDigestAddress(subscriptions)
  const effectiveCadence = cadence ?? subscription?.cadence ?? 'weekly'
  const needsEmailInput = !knownAddress
  const emailValid = EMAIL_RE.test(email.trim())
  const isPendingConfirmation = subscription?.status === 'pending_confirmation'
  const isActive = subscription?.status === 'active'
  const cadenceUnchanged = isActive && effectiveCadence === subscription?.cadence
  const primaryLabelId = (() => {
    if (isPendingConfirmation && effectiveCadence === subscription?.cadence) return 'newsletter.resend'
    if (isActive || isPendingConfirmation) return 'newsletter.update'
    return 'newsletter.subscribe'
  })()
  return {
    subscriptionId: subscription?.id ?? null, knownAddress: knownAddress ?? null, effectiveCadence, needsEmailInput,
    primaryLabelId, disabled: !!(pending || (needsEmailInput && !emailValid) || cadenceUnchanged), showsLeave: !!subscription,
    address: knownAddress || email.trim(),
  }
}
const sub = (over) => ({ id: 'id-1', email: 'alice@example.com', account: 'spike-alice', type: 'creator', target: 'spike-alice', cadence: 'weekly', status: 'active', created_at: '2026-08-25T00:00:00Z', ...over })
const formInputs = [
  { name: 'nothing_known', subscriptions: [], type: 'own', target: 'spike-alice', email: '', cadence: null, pending: false },
  { name: 'typed_address', subscriptions: [], type: 'own', target: 'spike-alice', email: '  alice@example.com ', cadence: 'monthly', pending: false },
  { name: 'address_from_another_list', subscriptions: [sub({ id: 'c1', type: 'community', target: 'hive-125125' })], type: 'own', target: 'spike-alice', email: '', cadence: null, pending: false },
  { name: 'active_same_cadence', subscriptions: [sub({ id: 'o1', type: 'own', target: 'Spike-Alice' })], type: 'own', target: 'spike-alice', email: '', cadence: null, pending: false },
  { name: 'active_other_cadence', subscriptions: [sub({ id: 'o1', type: 'own', target: 'spike-alice' })], type: 'own', target: 'spike-alice', email: '', cadence: 'monthly', pending: false },
  { name: 'pending_same_cadence', subscriptions: [sub({ id: 'o1', type: 'own', target: 'spike-alice', status: 'pending_confirmation', cadence: 'monthly' })], type: 'own', target: 'spike-alice', email: '', cadence: null, pending: false },
  { name: 'pending_other_cadence', subscriptions: [sub({ id: 'o1', type: 'own', target: 'spike-alice', status: 'pending_confirmation' })], type: 'own', target: 'spike-alice', email: '', cadence: 'monthly', pending: false },
  { name: 'ended', subscriptions: [sub({ id: 'o1', type: 'own', target: 'spike-alice', status: 'ended' })], type: 'own', target: 'spike-alice', email: '', cadence: null, pending: false },
  { name: 'own_row_without_address', subscriptions: [sub({ id: 'o1', type: 'own', target: 'spike-alice', email: '' }), sub({ id: 'c1', type: 'creator', target: 'bob', email: 'other@example.com' })], type: 'own', target: 'spike-alice', email: '', cadence: null, pending: false },
  { name: 'while_pending', subscriptions: [], type: 'own', target: 'spike-alice', email: 'alice@example.com', cadence: null, pending: true },
  { name: 'type_must_match', subscriptions: [sub({ id: 'x1', type: 'creator', target: 'spike-alice', email: '' })], type: 'own', target: 'spike-alice', email: 'not an address', cadence: null, pending: false },
]
const formCases = formInputs.map((c) => {
  const r = form(c)
  const subs = c.subscriptions.map((s) => `Sub(${k(s.id)}, ${k(s.email)}, ${k(s.type)}, ${k(s.target)}, ${k(s.cadence)}, ${k(s.status)})`).join(', ')
  return `        FormCase(${k(c.name)}, listOf(${subs}), ${k(c.type)}, ${k(c.target)}, ${k(c.email)}, ${k(c.cadence)}, ${c.pending},
            ${k(r.subscriptionId)}, ${k(r.knownAddress)}, ${k(r.effectiveCadence)}, ${r.needsEmailInput}, ${k(r.primaryLabelId)}, ${r.disabled}, ${r.showsLeave}, ${k(r.address)}),`
})
const emailInputs = ['alice@example.com', ' alice@example.com ', 'a@b.c', 'a@b', '@b.c', 'a@.c', 'a b@c.d', 'a@b c.d', 'a@b.c d', '', 'a@@b.c', 'a@b..c', 'пчела@пример.рф', 'a b@c.d', 'x@y.z\n']
const emailCases = emailInputs.map((e) => `        ${k(e)} to ${EMAIL_RE.test(e.trim())},`)

// ---- RN src/utils/firstPublishDigest.ts @ cbcca86791: shouldOfferFirstPublishDigest ----
const shouldOfferFirstPublishDigest = (postCount, alreadyOffered) => !alreadyOffered && typeof postCount === 'number' && postCount === 0
const offerCases = [[0, false], [0, true], [1, false], [null, false], [undefined, false], [-1, false]].map(([count, offered]) =>
  `        OfferCase(${count === null || count === undefined ? 'null' : count}, ${offered}, ${shouldOfferFirstPublishDigest(count, offered)}),`)

writeFileSync(out, `// GENERATED by native/tools/golden/generate-newsletter.mjs from @ecency/sdk and the RN digest sheet's helpers. Do not edit by hand.
package com.ecency.core.api.ecency

object NewsletterGoldens {
    data class RequestCase(val name: String, val method: String, val url: String, val headers: Map<String, String>, val body: String?)
    data class AnswerCase(val name: String, val status: Int, val body: String, val result: String)
    data class Sub(val id: String, val email: String, val type: String, val target: String, val cadence: String, val status: String)
    data class FormCase(
        val name: String, val subscriptions: List<Sub>, val type: String, val target: String, val email: String, val cadence: String?, val pending: Boolean,
        val subscriptionId: String?, val knownAddress: String?, val effectiveCadence: String, val needsEmailInput: Boolean, val primaryLabelId: String,
        val disabled: Boolean, val showsLeave: Boolean, val address: String,
    )
    data class OfferCase(val postCount: Int?, val alreadyOffered: Boolean, val offers: Boolean)

    const val CODE = ${k(CODE)}

    /** getDigestSubscriptionsRequest, subscribeDigestRequest, leaveDigestRequest: what the SDK fetched. */
    val requests = listOf(
${requestCases.join('\n')}
    )

    /** getDigestSubscriptionsRequest over these answers: "ok:<rows>" or "error:<status>". */
    val answers = listOf(
${answerCases.join('\n')}
    )

    /** The sheet's form: what it shows and sends for these subscriptions and inputs. */
    val forms = listOf(
${formCases.join('\n')}
    )

    /** EMAIL_RE over the trimmed input. */
    val emails = listOf(
${emailCases.join('\n')}
    )

    val offers = listOf(
${offerCases.join('\n')}
    )
}
`)
console.log(`wrote ${out}`)
