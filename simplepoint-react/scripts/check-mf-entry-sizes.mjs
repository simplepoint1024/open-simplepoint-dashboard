import {readdirSync, readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {gzipSync} from 'node:zlib';

const root = fileURLToPath(new URL('../', import.meta.url));
const limit = 512 * 1024;
const representativePages = {common: 'platform/Package', audit: 'monitoring/LoginLog', dna: 'platform/JdbcDataSource', ai: 'workbench/Models'};
const hostProvidedShared = new Set([
  'react',
  'react/jsx-runtime',
  'react/jsx-dev-runtime',
  'react-dom',
  'antd',
  'dayjs',
  'react-router',
  '@tanstack/react-query',
  '@simplepoint/shared/types/icon',
  '@simplepoint/shared/loading/context',
  '@simplepoint/shared/page/context',
  '@simplepoint/shared/api/feedbackBridge',
]);
let failed = false;
for (const name of ['common', 'ai', 'dna', 'audit']) {
  const dir = path.join(root, 'modules', `simplepoint-${name}`, 'dist', 'mf');
  const manifest = JSON.parse(readFileSync(path.join(dir, 'mf-manifest.json'), 'utf8'));
  const entry = manifest.metaData.remoteEntry;
  // Checking only shared metadata misses relative imports bypassing Federation.
  for (const file of readdirSync(dir).filter(file => file.endsWith('.js'))) {
    const source = readFileSync(path.join(dir, file), 'utf8');
    const definitions = source.match(/name:"[a-z\d-]+",theme:"(?:outlined|filled|twotone)"/g) ?? [];
    if (source.includes('AccountBookOutlined') || definitions.length > 150) {
      console.error(`${name}: duplicated full icon library in ${file}; use the host-owned icon import.`);
      failed = true;
    }
  }
  if (manifest.exposes.some(expose => /\/(config|components)\//.test(expose.name))) {
    console.error(`${name}: private components leaked into MF exposes.`);
    failed = true;
  }
  const representative = manifest.exposes.find(expose => expose.name === representativePages[name]);
  if (!representative) throw new Error(`${name}: representative page is missing`);
  const syncFiles = [...new Set(representative.assets.js.sync)];
  const syncGzip = syncFiles.reduce((total, file) => total + gzipSync(readFileSync(path.join(dir, file))).length, 0);
  console.log(`${name}: ${representative.name} synchronous JS ${syncFiles.length} requests, gzip ${syncGzip} bytes (excluding host/entry)`);
  if (syncFiles.length > 8 || syncGzip > 90 * 1024) {
    console.error(`${name}: representative table page exceeds 8 synchronous JS requests or 90 KiB gzip.`);
    failed = true;
  }
  const source = readFileSync(path.join(dir, entry.path || '', entry.name));
  console.log(`${name}: entry ${source.length} bytes, gzip ${gzipSync(source).length} bytes`);
  if (source.length > limit) {
    console.error(`${name}: remote entry exceeds the ${limit}-byte budget; check eager shared dependencies.`);
    failed = true;
  }
  const exposeAssets = manifest.exposes.flatMap(expose => [
    ...expose.assets.js.sync,
    ...expose.assets.js.async,
    ...expose.assets.css.sync,
    ...expose.assets.css.async,
  ]);
  if (exposeAssets.length === 0) {
    console.error(`${name}: manifest expose assets are empty; precise MF preloading is disabled.`);
    failed = true;
  }
  const invalidWorkspaceRequirements = manifest.shared
    .filter(shared => String(shared.requiredVersion ?? '').startsWith('workspace:'))
    .map(shared => shared.name);
  if (invalidWorkspaceRequirements.length > 0) {
    console.error(`${name}: workspace protocol leaked into MF version requirements: ${invalidWorkspaceRequirements.join(', ')}`);
    failed = true;
  }

  const sharedAssets = shared => [
    ...shared.assets.js.sync,
    ...shared.assets.js.async,
    ...shared.assets.css.sync,
    ...shared.assets.css.async,
  ];
  const missingRemoteFallbacks = manifest.shared
    .filter(shared => !hostProvidedShared.has(shared.name) && sharedAssets(shared).length === 0)
    .map(shared => shared.name);
  if (missingRemoteFallbacks.length > 0) {
    console.error(`${name}: shared dependencies have no remote fallback and may cause MF RUNTIME-012: ${missingRemoteFallbacks.join(', ')}`);
    failed = true;
  }
  const hostOnlyFallbacks = manifest.shared
    .filter(shared => (
      hostProvidedShared.has(shared.name) && sharedAssets(shared).length > 0
    ))
    .map(shared => shared.name);
  if (hostOnlyFallbacks.length > 0) {
    console.error(`${name}: host-owned dependencies contain remote fallbacks: ${hostOnlyFallbacks.join(', ')}`);
    failed = true;
  }
}

const hostAsyncDir = path.join(root, 'modules', 'simplepoint-host', 'dist', 'assets', 'js', 'async');
const hostAsyncFiles = readdirSync(hostAsyncDir)
  .filter(name => name.endsWith('.js'))
  .map(name => ({
    name,
    source: readFileSync(path.join(hostAsyncDir, name)),
  }));
const dynamicIconContextFiles = hostAsyncFiles
  .filter(({source}) => source.includes('./AccountBookOutlined.js'))
  .map(({name}) => name);
const iconRegistryFiles = hostAsyncFiles
  .filter(({source}) => source.includes('AccountBookOutlined'))
  .map(({name, source}) => ({name, size: source.length, gzipSize: gzipSync(source).length}));

if (dynamicIconContextFiles.length > 0) {
  console.error(`host: dynamic icon contexts found in ${dynamicIconContextFiles.join(', ')}; icons must not be split.`);
  failed = true;
} else {
  console.log('host: icon provider has no dynamic icon contexts');
}

if (iconRegistryFiles.length !== 1) {
  console.error(`host: expected one complete icon registry chunk, found ${iconRegistryFiles.length}`);
  failed = true;
} else {
  const [{name, size, gzipSize}] = iconRegistryFiles;
  console.log(`host: icon registry ${name}, ${size} bytes, gzip ${gzipSize} bytes`);
}

process.exitCode = failed ? 1 : 0;
