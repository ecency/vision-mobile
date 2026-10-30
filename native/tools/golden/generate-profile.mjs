// Golden fixtures for the profile edit's posting_json_metadata, from the @ecency/sdk the RN app ships.
//
// Run from the RN app root (it has node_modules):
//   node native/tools/golden/generate-profile.mjs native/shared/src/commonTest/kotlin/com/ecency/core/api/ProfileMetadataFixtures.kt
//
// Inputs are synthetic. Each case is (existing posting_json_metadata, profile partial as JSON) and the SDK's
// buildPostingJsonMetadata output, or the error it throws.
import { createRequire } from 'node:module'
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'

const require = createRequire(join(process.cwd(), 'package.json'))
const sdk = await import(require.resolve('@ecency/sdk'))
const { buildPostingJsonMetadata } = sdk

const out = process.argv[2]
if (!out) throw new Error('usage: generate-profile.mjs <output.kt>')

const edit = (o) => JSON.stringify(o)
const cases = [
  ['empty_account', '', edit({ profile_image: '', name: 'Spike', version: 2 })],
  ['null_metadata', null, edit({ about: 'Hi', version: 2 })],
  ['invalid_json', '{not json', edit({ name: 'Spike', version: 2 })],
  ['array_root', '[1,2]', edit({ name: 'Spike', version: 2 })],
  ['keeps_other_keys', '{"beneficiaries":[1],"profile":{"name":"Old","about":"A"},"zzz":{"a":1}}', edit({ name: 'New', version: 2 })],
  ['profile_appended_last', '{"app":"x"}', edit({ name: 'Spike', location: 'Earth', version: 2 })],
  ['existing_order_kept', '{"profile":{"website":"w","name":"n","about":"a","version":1}}',
    edit({ profile_image: 'https://i/a.png', cover_image: 'https://i/c.png', name: 'N2', website: 'w2', about: 'a2', location: 'l', version: 2 })],
  ['deep_merge_nested', '{"profile":{"nested":{"a":1,"b":{"c":2}},"list":[1,2]}}', edit({ nested: { b: { d: 3 }, e: 4 }, list: [9], version: 2 })],
  ['denied_keys', '{"profile":{"name":"n"}}', '{"__proto__":{"polluted":true},"constructor":"x","prototype":1,"name":"m","version":2}'],
  ['proto_in_existing', '{"__proto__":{"a":1},"profile":{"__proto__":"keep","name":"n"}}', edit({ name: 'm', version: 2 })],
  ['tokens_sanitized', '{"profile":{"tokens":[{"symbol":"HIVE","type":"CHAIN","meta":{"address":"a","privateKey":"p","username":"u"}}]}}',
    '{"tokens":[{"meta":{"address":"a","privateKey":"p","username":"u","show":true},"symbol":"HIVE","type":"CHAIN"}],"version":2}'],
  ['tokens_from_existing_only', '{"profile":{"tokens":[{"meta":{"privateKey":"p"},"symbol":"BTC"}],"name":"n"}}', edit({ name: 'm', version: 2 })],
  ['tokens_not_array', '{"profile":{"tokens":"bad","name":"n"}}', edit({ name: 'm', version: 2 })],
  ['tokens_object', '{"profile":{"tokens":{"a":1},"name":"n"}}', edit({ about: 'x', version: 2 })],
  ['tokens_falsy_kept_position', '{"profile":{"tokens":0,"name":"n"}}', edit({ about: 'x', version: 2 })],
  ['tokens_odd_entries', '{"profile":{}}', '{"tokens":["ab",5,true,[1,2],{"meta":"str"},{"meta":null},{"meta":[3,4]},{"x":1}],"version":2}'],
  ['tokens_null_entry_throws', '{"profile":{}}', '{"tokens":[null],"version":2}'],
  ['tokens_partial_empty', '{"profile":{"tokens":[{"symbol":"A"}]}}', '{"tokens":[],"version":2}'],
  ['numbers', '{"profile":{"n1":1.0,"n2":1e21,"n3":0.000001,"n4":1e-7,"n5":123456789012345680000,"n6":-0,"n7":0.1,"n8":2.5e-324,"n9":1.7976931348623157e308,"n10":100,"n11":12.5e3,"n12":-1.5e-10}}', edit({ version: 2 })],
  ['integer_keys_order', '{"profile":{"b":1,"10":2,"2":3,"a":4,"01":5,"4294967295":6,"4294967294":7}}', '{"1":"x","c":"y","version":2}'],
  ['escapes', '{"profile":{}}', JSON.stringify({ name: 'q"b\\s\n\r\t\b\f\u0001\u001f\u007f/é🐝', about: 'lone \ud800 x \udc00', version: 2 })],
  ['profile_array_existing', '{"profile":["a","b"],"x":1}', edit({ name: 'n', version: 2 })],
  ['profile_null_existing', '{"profile":null}', edit({ name: 'n', version: 2 })],
  ['duplicate_keys', '{"profile":{"name":"a","about":"b","name":"c"},"profile2":1}', edit({ about: 'z', version: 2 })],
  ['rn_edit_shape', '{"profile":{"name":"Alice","about":"Hive","profile_image":"https://img/a.png","pinned":"my-post","version":2}}',
    edit({ name: 'Alice', about: 'Hive dev', profile_image: 'https://img/a.png', pinned: 'my-post', version: 2, cover_image: 'https://img/c.png', website: 'https://alice.example', location: 'Berlin' })],
  ['unicode_values', '{"profile":{"name":"Привет"}}', edit({ about: 'مرحبا 你好 🐝', version: 2 })],
]

const kStr = (s) => (s === null ? 'null' : JSON.stringify(s).replace(/\$/g, '\\$'))
const rows = cases.map(([name, existing, partial]) => {
  let expected = null
  let error = null
  try {
    expected = buildPostingJsonMetadata({ existingPostingJsonMetadata: existing, profile: JSON.parse(partial) })
  } catch (e) {
    error = e.constructor.name
  }
  return `        ProfileMetadataCase(${kStr(name)}, ${kStr(existing)}, ${kStr(partial)}, ${kStr(expected)}, ${kStr(error)}),`
})

// JSON.stringify(JSON.parse(literal)) for number formatting
const numbers = ['0', '-0', '1', '1.0', '1.5', '0.1', '0.2', '0.30000000000000004', '123456789', '1e21', '1e20', '123456789012345680000',
  '0.000001', '0.0000001', '1e-7', '5e-324', '2.5e-324', '1.7976931348623157e308', '1e400', '-1e400', '12.5e3', '-1.5e-10',
  '9007199254740993', '18014398509481985', '4.35', '87.35', '1.005', '100', '1e3', '0.5', '3.14159265358979',
  '1234567890123456789', '98765.4321e-2', '7e22', '2e-6', '1.2345678901234567e-7', '6.02214076e23']
const numRows = numbers.map((n) => `        ${kStr(n)} to ${kStr(JSON.stringify(JSON.parse(n)))},`)

writeFileSync(out, `// GENERATED by native/tools/golden/generate-profile.mjs from @ecency/sdk. Do not edit by hand.
package com.ecency.core.api

data class ProfileMetadataCase(
    val name: String,
    val existing: String?,
    val partial: String,
    val expected: String?,
    val error: String?,
)

object ProfileMetadataFixtures {
    val cases = listOf(
${rows.join('\n')}
    )

    /** JSON number literal to what JSON.stringify(JSON.parse(literal)) writes. */
    val numbers = listOf(
${numRows.join('\n')}
    )
}
`)
console.log(`wrote ${rows.length} metadata cases and ${numRows.length} numbers to ${out}`)
