import fs from 'node:fs';
import path from 'node:path';

const ignoredDirectories = new Set([
  '.git', '.gradle', '.pnpm-store', '.cache', '.kotlin', '.simplepoint', '.idea',
  'build', 'dist', 'node_modules', 'out', 'fixtures', '__fixtures__',
]);
const platformPrefix = path.join('META-INF', 'simplepoint');
const indexedPath = /^[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*\/[a-zA-Z0-9_-][a-zA-Z0-9_.-]*\.json$/;
const sortObject = obj => Object.fromEntries(Object.entries(obj).sort(([a], [b]) => a.localeCompare(b)));
const readResource = file => {
  const stat = fs.lstatSync(file);
  if (!stat.isFile() || stat.size > 2 * 1024 * 1024) throw new Error(`Invalid or oversized i18n resource: ${file}`);
  return fs.readFileSync(file, 'utf8');
};
const findResourceRoots = repoRoot => {
  const roots = [];
  const visit = dir => {
    if (ignoredDirectories.has(path.basename(dir))) return;
    const candidate = path.join(dir, 'src', 'main', 'resources');
    if (fs.existsSync(candidate) && fs.statSync(candidate).isDirectory()) roots.push(candidate);
    for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
      if (entry.isDirectory() && !ignoredDirectories.has(entry.name)) visit(path.join(dir, entry.name));
    }
  };
  visit(repoRoot);
  return roots.sort();
};

/** Build-time union of legacy declarations and exact indexed module assets; no runtime scanning. */
export const readBackendBundles = repoRoot => {
  const roots = findResourceRoots(repoRoot);
  const bundles = new Map();
  const indexes = new Map();
  const add = (locale, namespace, file) => {
    const namespaces = bundles.get(locale) ?? new Map();
    if (namespaces.has(namespace)) throw new Error(`Duplicate backend i18n namespace: ${locale}/${namespace}`);
    const messages = JSON.parse(readResource(file));
    if (!messages || Array.isArray(messages) || typeof messages !== 'object'
      || Object.entries(messages).some(([key, value]) => !key.trim() || typeof value !== 'string')) {
      throw new Error(`Invalid backend i18n declaration: ${file}`);
    }
    namespaces.set(namespace, sortObject(messages));
    bundles.set(locale, namespaces);
  };
  for (const root of roots) {
    const legacy = path.join(root, platformPrefix, 'i18n');
    if (fs.existsSync(legacy)) {
      for (const locale of fs.readdirSync(legacy, {withFileTypes: true})) {
        if (!locale.isDirectory()) continue;
        const dir = path.join(legacy, locale.name);
        for (const file of fs.readdirSync(dir).filter(name => name.endsWith('.json')).sort()) {
          add(locale.name, file.slice(0, -5), path.join(dir, file));
        }
      }
    }
    const modules = path.join(root, platformPrefix, 'modules');
    if (!fs.existsSync(modules)) continue;
    for (const module of fs.readdirSync(modules, {withFileTypes: true})) {
      if (!module.isDirectory()) continue;
      const index = path.join(modules, module.name, 'i18n.imports');
      if (!fs.existsSync(index)) continue;
      if (!/^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/.test(module.name) || indexes.has(module.name)) {
        throw new Error(`Invalid or duplicate module i18n index: ${module.name}`);
      }
      indexes.set(module.name, index);
    }
  }
  for (const [module, index] of [...indexes.entries()].sort()) {
    const paths = new Set();
    for (const line of readResource(index).split(/\r?\n/)) {
      const relative = line.split('#', 1)[0].trim();
      if (!relative) continue;
      if (paths.size >= 512 || !indexedPath.test(relative) || paths.has(relative)) {
        throw new Error(`Invalid module i18n index entry: ${module}/${relative}`);
      }
      paths.add(relative);
    }
    if (!paths.size) throw new Error(`Empty module i18n index: ${module}`);
    for (const relative of paths) {
      const candidates = roots.map(root => path.join(root, platformPrefix, 'modules', module, 'i18n', relative))
        .filter(file => fs.existsSync(file));
      if (candidates.length !== 1) throw new Error(`Module i18n resource must resolve exactly once: ${module}/${relative}`);
      const [locale, filename] = relative.split('/');
      add(locale, filename.slice(0, -5), candidates[0]);
    }
  }
  return sortObject(Object.fromEntries([...bundles].map(([locale, namespaces]) =>
    [locale, sortObject(Object.fromEntries(namespaces))])));
};
