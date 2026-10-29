// Kotlin string literal for generated fixtures: escapes quotes, backslashes, `$` and every
// non-printable or non-ASCII code unit, so generated files are plain ASCII.
export const kt = (s) => s == null ? 'null' : '"' + [...s].map((ch) => {
  if (ch === '"' || ch === '\\' || ch === '$') return '\\' + ch
  return ch.length === 1 && ch >= ' ' && ch <= '~'
    ? ch
    : [...Array(ch.length).keys()].map((i) => '\\u' + ch.charCodeAt(i).toString(16).padStart(4, '0')).join('')
}).join('') + '"'
