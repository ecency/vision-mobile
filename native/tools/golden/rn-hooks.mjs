// Node module hooks that let the offline golden generators import the RN app's own TypeScript (src/utils/editor.ts,
// the markdown format helpers) without React Native: extensionless relative imports get their .ts, and the few
// native or network modules those files touch are replaced by tiny stand-ins. Offline tooling only.
import { existsSync, statSync } from 'node:fs'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { dirname, join } from 'node:path'
import { createRequire } from 'node:module'

const data = (src) => 'data:text/javascript,' + encodeURIComponent(src)
const require = createRequire(join(process.cwd(), 'package.json'))
const real = (name) => JSON.stringify(pathToFileURL(require.resolve(name)).href)

const STUBS = {
  // the translation client: requests are answered by globalThis.__translate(path, body), never by the service
  '@sentry/react-native': () => `export const captureException = () => {};`,
  '../../config/translationApi': () => `export default { post: async (path, body) => ({ data: await globalThis.__translate(path, body) }) };`,
  // Image.getSize answers from globalThis.__imageSizes (url -> [width, height]); anything else fails like a dead url
  'react-native': () => `export const Image = { getSize: (u, ok, err) => { const r = (globalThis.__imageSizes || {})[u]; r ? ok(r[0], r[1]) : err() } };
export const Platform = { OS: 'android' };`,
  'react-native-version-number': () => `export default { appVersion: globalThis.__appVersion || '4.0.0' };`,
  lodash: () => `import _ from ${real('lodash')}; export const unionBy = _.unionBy; export const isNumber = _.isNumber; export default _;`,
  'diff-match-patch': () => `import d from ${real('diff-match-patch')}; export const diff_match_patch = d.diff_match_patch || d; export default d;`,
  'mime-types': () => `import m from ${real('mime-types')}; export default m;`,
  // the SDK itself, except the query client: linked posts come from globalThis.__posts (query key -> post), never a node
  '@ecency/sdk': () => `export * from ${real('@ecency/sdk')};
export const getQueryClient = () => ({ fetchQuery: async (o) => { const r = (globalThis.__posts || (() => { throw new Error('offline') }))(o.queryKey); if (r instanceof Error) throw r; return r } });`,
}

export async function resolve(specifier, context, next) {
  const parent = context.parentURL || ''
  const fromRn = parent.startsWith('file:') && parent.includes('/src/') && parent.endsWith('.ts')
  if (fromRn && STUBS[specifier]) return { url: data(STUBS[specifier]()), shortCircuit: true }
  if (fromRn && specifier.startsWith('.')) {
    const base = join(dirname(fileURLToPath(parent)), specifier)
    for (const candidate of [base + '.ts', base + '.tsx', join(base, 'index.ts'), base]) {
      if (existsSync(candidate) && statSync(candidate).isFile()) {
        try {
          return { url: pathToFileURL(candidate).href, shortCircuit: true }
        } catch { /* fall through */ }
      }
    }
  }
  return next(specifier, context)
}

// RN sources are compiled with the app's own TypeScript (enums, type-only imports dropped), in memory.
export async function load(url, context, next) {
  if (url.startsWith('file:') && url.includes('/src/') && (url.endsWith('.ts') || url.endsWith('.tsx'))) {
    const ts = require('typescript')
    const { readFileSync } = await import('node:fs')
    const out = ts.transpileModule(readFileSync(fileURLToPath(url), 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.Preserve },
    })
    return { format: 'module', source: out.outputText, shortCircuit: true }
  }
  return next(url, context)
}
