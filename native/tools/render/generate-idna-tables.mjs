// UTS 46 validity tables for the Kotlin IDNA code (render.url.Idna), taken from the IDNA
// implementation inside the Node that produces the URL goldens: Ada (process.versions.ada),
// file src/ada_idna.cpp, dual licensed Apache-2.0 / MIT. Ada derives them from the Unicode
// Character Database. Using Ada's own copy keeps the Kotlin checks in step with the oracle,
// including the Unicode version it was built from.
//
// Extracted: the UTS 46 mapping table, the NFC normalization tables, the Bidi_Class ranges
// (CheckBidi), the code points a label may not start with (General_Category=Mark), and the
// CheckJoiners sets (viramas, joining types R, L and D).
//
//   node generate-idna-tables.mjs ../../shared/src/commonMain/kotlin/com/ecency/core/render/url/IdnaTables.kt
import { writeFileSync } from 'node:fs'

const out = process.argv[2]
if (!out) throw new Error('usage: generate-idna-tables.mjs <output.kt>')

const version = process.versions.ada
const src = await (await fetch(`https://raw.githubusercontent.com/ada-url/ada/v${version}/src/ada_idna.cpp`)).text()

function arrayBody(name) {
  const m = src.match(new RegExp(`\\b${name}\\[\\]\\s*=\\s*\\{([^}]*)\\}`))
  if (!m) throw new Error(`array ${name} not found`)
  return m[1]
}
const ints = (body) => body.split(',').map((s) => s.trim()).filter(Boolean).map((s) => parseInt(s, 16))

// Bidi classes the label rules look at; every other class (and unlisted code points) is "other".
const CLASS = { L: 'L', R: 'R', AL: 'A', AN: 'N', EN: 'E', ES: 'S', CS: 'C', ET: 'T', ON: 'O', BN: 'B', NSM: 'M' }
const dirStart = src.indexOf('dir_table[] = {')
const dirEnd = src.indexOf('};', dirStart)
const ranges = []
for (const m of src.slice(dirStart, dirEnd).matchAll(/\{(0x[0-9a-f]+),\s*(0x[0-9a-f]+),\s*direction::(\w+)\}/gi)) {
  const cls = CLASS[m[3]]
  if (!cls) continue
  const start = parseInt(m[1], 16)
  const end = parseInt(m[2], 16)
  const last = ranges[ranges.length - 1]
  if (last && last[2] === cls && last[1] + 1 === start) last[1] = end
  else ranges.push([start, end, cls])
}
if (ranges.length < 500) throw new Error(`dir_table looks wrong: ${ranges.length} ranges`)

function toRanges(cps) {
  const sorted = [...new Set(cps)].sort((a, b) => a - b)
  const out = []
  for (const c of sorted) {
    const last = out[out.length - 1]
    if (last && last[1] + 1 === c) last[1] = c
    else out.push([c, c])
  }
  return out
}

const combining = toRanges(ints(arrayBody('combining')))
const virama = ints(arrayBody('virama'))
const joinR = ints(arrayBody('R'))
const joinL = ints(arrayBody('L'))
const joinD = ints(arrayBody('D'))

// Flat numeric tables, zero-padded to their declared size (C zero-fills missing rows).
function numericArray(name) {
  const m = src.match(new RegExp(`\\b${name}((?:\\[\\d+\\])+)\\s*=\\s*\\{`))
  if (!m) throw new Error(`table ${name} not found`)
  const size = [...m[1].matchAll(/\d+/g)].reduce((a, d) => a * Number(d[0]), 1)
  const start = m.index + m[0].length
  const nums = src.slice(start, src.indexOf('};', start)).match(/0x[0-9a-f]+|\d+/gi).map(Number)
  if (nums.length > size) throw new Error(`${name}: ${nums.length} > ${size}`)
  while (nums.length < size) nums.push(0)
  return nums
}

// Delta then run-length encoding, base 36: "d" or "d*count" tokens, comma separated.
function pack(nums) {
  const tokens = []
  let prev = 0
  let i = 0
  const deltas = nums.map((n) => { const d = n - prev; prev = n; return d })
  while (i < deltas.length) {
    let j = i + 1
    while (j < deltas.length && deltas[j] === deltas[i]) j++
    const run = j - i
    tokens.push(deltas[i].toString(36) + (run > 1 ? '*' + run.toString(36) : ''))
    i = j
  }
  return tokens.join(',')
}

function packedLiteral(nums) {
  const packed = pack(nums)
  const chunks = []
  for (let i = 0; i < packed.length; i += 120) chunks.push(`"${packed.slice(i, i + 120)}"`)
  // Several array elements, so no single string constant gets near the JVM's 64 KB limit.
  const groups = []
  for (let i = 0; i < chunks.length; i += 200) groups.push(chunks.slice(i, i + 200).join(' +\n            '))
  return `arrayOf(\n            ${groups.join(',\n            ')},\n        )`
}

const mapTable = numericArray('table')
const tables = {
  MAPPING_KEYS: mapTable.filter((_, i) => i % 2 === 0),
  MAPPING_DESCRIPTORS: mapTable.filter((_, i) => i % 2 === 1),
  MAPPINGS: numericArray('mappings'),
  DECOMPOSITION_INDEX: numericArray('decomposition_index'),
  DECOMPOSITION_BLOCK: numericArray('decomposition_block'),
  DECOMPOSITION_DATA: numericArray('decomposition_data'),
  CCC_INDEX: numericArray('canonical_combining_class_index'),
  CCC_BLOCK: numericArray('canonical_combining_class_block'),
  COMPOSITION_INDEX: numericArray('composition_index'),
  COMPOSITION_BLOCK: numericArray('composition_block'),
  COMPOSITION_DATA: numericArray('composition_data'),
}
const idnaVersion = (src.match(/\/\/ IDNA\s+([\d.]+)/) || [])[1] || 'unknown'

const hex = (n) => n.toString(16)
const encodeRanges = (rs) => rs.map(([a, b, c]) => (a === b ? hex(a) : `${hex(a)}-${hex(b)}`) + (c ?? '')).join(',')

function literal(s) {
  // Split long strings into concatenated pieces to keep lines readable.
  const parts = []
  for (let i = 0; i < s.length; i += 100) parts.push(`"${s.slice(i, i + 100)}"`)
  return parts.join(' +\n        ')
}

writeFileSync(out, `// GENERATED by native/tools/render/generate-idna-tables.mjs from Ada ${version} (src/ada_idna.cpp,
// Apache-2.0 OR MIT), the IDNA code inside the Node that produces the URL goldens. Do not edit by hand.
package com.ecency.core.render.url

internal object IdnaTables {
    /** IDNA mapping table version Ada was generated from. */
    const val IDNA_VERSION = "${idnaVersion}"

${Object.entries(tables).map(([name, nums]) => `    /** Ada \`${name.toLowerCase()}\` (${nums.length} entries), packed: see [IdnaData.unpack]. */\n    val ${name}: Array<String> = ${packedLiteral(nums)}\n`).join('\n')}
    /**
     * Bidi_Class ranges as \`start-end\` (hex) followed by one class letter: L, R, A (AL),
     * N (AN), E (EN), S (ES), C (CS), T (ET), O (ON), B (BN), M (NSM). Unlisted code points
     * have a class the label rules never accept.
     */
    const val BIDI =
        ${literal(encodeRanges(ranges))}

    /** Code points a label must not start with (General_Category=Mark), as hex ranges. */
    const val LEADING_COMBINING =
        ${literal(encodeRanges(combining))}

    /** CheckJoiners: viramas (Canonical_Combining_Class=9). */
    val VIRAMA = intArrayOf(${virama.map((c) => '0x' + hex(c)).join(', ')})

    /** CheckJoiners: Joining_Type=R. */
    val JOINING_R = intArrayOf(${joinR.map((c) => '0x' + hex(c)).join(', ')})

    /** CheckJoiners: Joining_Type=L. */
    val JOINING_L = intArrayOf(${joinL.map((c) => '0x' + hex(c)).join(', ')})

    /** CheckJoiners: Joining_Type=D. */
    val JOINING_D = intArrayOf(${joinD.map((c) => '0x' + hex(c)).join(', ')})
}
`)
console.log(`ada ${version} (IDNA ${idnaVersion}): ${Object.entries(tables).map(([n, a]) => `${n} ${a.length}`).join(', ')}, ${ranges.length} bidi ranges, ${combining.length} mark ranges, ${virama.length} viramas, joining R/L/D ${joinR.length}/${joinL.length}/${joinD.length}`)
