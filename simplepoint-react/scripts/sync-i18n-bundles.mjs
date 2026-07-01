#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const args = new Set(process.argv.slice(2));
const checkOnly = args.has('--check');

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const reactRoot = path.resolve(scriptDir, '..');
const repoRoot = path.resolve(reactRoot, '..');
const backendMessagesDir = path.join(
  repoRoot,
  'simplepoint-plugins/simplepoint-plugins-i18n/simplepoint-plugin-i18n-service/src/main/resources/i18n/messages'
);
const frontendBundlesDir = path.join(
  reactRoot,
  'modules/mocks/src/i18n/bundles/local'
);

const frontendNamespaceSources = [
  'modules/simplepoint-host/src/fetches/index.ts',
  'modules/simplepoint-common/src/api/index.ts',
  'modules/simplepoint-audit/src/api/index.ts',
  'modules/simplepoint-dna/src/api/index.ts',
];

const readJson = file => JSON.parse(fs.readFileSync(file, 'utf8'));

const sortObject = obj => Object.fromEntries(
  Object.entries(obj).sort(([left], [right]) => left.localeCompare(right))
);

const stringify = obj => `${JSON.stringify(obj, null, 2)}\n`;

const readBackendBundles = () => {
  if (!fs.existsSync(backendMessagesDir)) {
    throw new Error(`Missing backend i18n resources: ${backendMessagesDir}`);
  }

  const bundles = {};
  for (const locale of fs.readdirSync(backendMessagesDir).sort()) {
    const localeDir = path.join(backendMessagesDir, locale);
    if (!fs.statSync(localeDir).isDirectory()) continue;

    const namespaces = {};
    for (const fileName of fs.readdirSync(localeDir).filter(file => file.endsWith('.json')).sort()) {
      const namespace = fileName.replace(/\.json$/, '');
      namespaces[namespace] = sortObject(readJson(path.join(localeDir, fileName)));
    }
    bundles[locale] = sortObject(namespaces);
  }
  return bundles;
};

const collectDeclaredNamespaces = () => {
  const declared = new Set();
  const pattern = /i18nNamespaces:\s*\[([^\]]*)]/g;
  const stringPattern = /['"]([^'"]+)['"]/g;

  for (const relativeFile of frontendNamespaceSources) {
    const file = path.join(reactRoot, relativeFile);
    if (!fs.existsSync(file)) continue;

    const source = fs.readFileSync(file, 'utf8');
    for (const match of source.matchAll(pattern)) {
      for (const namespaceMatch of match[1].matchAll(stringPattern)) {
        declared.add(namespaceMatch[1]);
      }
    }
  }

  return declared;
};

const assertLocaleParity = bundles => {
  const locales = Object.keys(bundles).sort();
  const baseLocale = locales[0];
  if (!baseLocale) throw new Error('No backend i18n locales found');

  const baseNamespaces = Object.keys(bundles[baseLocale]);
  for (const locale of locales.slice(1)) {
    const namespaces = Object.keys(bundles[locale]);
    const missingNamespaces = baseNamespaces.filter(ns => !namespaces.includes(ns));
    const extraNamespaces = namespaces.filter(ns => !baseNamespaces.includes(ns));
    if (missingNamespaces.length || extraNamespaces.length) {
      throw new Error(`${locale} namespace mismatch. Missing: ${missingNamespaces.join(', ') || '-'}; extra: ${extraNamespaces.join(', ') || '-'}`);
    }

    for (const namespace of baseNamespaces) {
      const baseKeys = Object.keys(bundles[baseLocale][namespace]);
      const keys = Object.keys(bundles[locale][namespace]);
      const missingKeys = baseKeys.filter(key => !keys.includes(key));
      const extraKeys = keys.filter(key => !baseKeys.includes(key));
      if (missingKeys.length || extraKeys.length) {
        throw new Error(`${locale}/${namespace} key mismatch. Missing: ${missingKeys.join(', ') || '-'}; extra: ${extraKeys.join(', ') || '-'}`);
      }
    }
  }
};

const assertDeclaredNamespacesExist = bundles => {
  const namespaces = new Set(Object.values(bundles).flatMap(bundle => Object.keys(bundle)));
  const missing = Array.from(collectDeclaredNamespaces()).filter(namespace => !namespaces.has(namespace)).sort();
  if (missing.length) {
    throw new Error(`Frontend declares i18n namespaces missing from backend resources: ${missing.join(', ')}`);
  }
};

const assertFrontendBundlesMatch = bundles => {
  const expectedFiles = new Set(Object.keys(bundles).map(locale => `${locale}.json`));
  const actualFiles = fs.existsSync(frontendBundlesDir)
    ? fs.readdirSync(frontendBundlesDir).filter(file => file.endsWith('.json')).sort()
    : [];
  const extraFiles = actualFiles.filter(file => !expectedFiles.has(file));
  const missingFiles = Array.from(expectedFiles).filter(file => !actualFiles.includes(file));

  if (extraFiles.length || missingFiles.length) {
    throw new Error(`Frontend i18n bundle files mismatch. Missing: ${missingFiles.join(', ') || '-'}; extra: ${extraFiles.join(', ') || '-'}`);
  }

  for (const [locale, bundle] of Object.entries(bundles)) {
    const file = path.join(frontendBundlesDir, `${locale}.json`);
    const expected = stringify(bundle);
    const actual = fs.readFileSync(file, 'utf8');
    if (actual !== expected) {
      throw new Error(`Frontend i18n bundle is out of sync: ${path.relative(reactRoot, file)}`);
    }
  }
};

const writeFrontendBundles = bundles => {
  fs.mkdirSync(frontendBundlesDir, {recursive: true});

  const expectedFiles = new Set(Object.keys(bundles).map(locale => `${locale}.json`));
  for (const file of fs.readdirSync(frontendBundlesDir).filter(file => file.endsWith('.json'))) {
    if (!expectedFiles.has(file)) {
      fs.rmSync(path.join(frontendBundlesDir, file));
    }
  }

  for (const [locale, bundle] of Object.entries(bundles)) {
    fs.writeFileSync(path.join(frontendBundlesDir, `${locale}.json`), stringify(bundle));
  }
};

try {
  const bundles = readBackendBundles();
  assertLocaleParity(bundles);
  assertDeclaredNamespacesExist(bundles);

  if (checkOnly) {
    assertFrontendBundlesMatch(bundles);
    console.log('i18n bundles are in sync.');
  } else {
    writeFrontendBundles(bundles);
    console.log('i18n bundles synced from backend resources.');
  }
} catch (error) {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
}
