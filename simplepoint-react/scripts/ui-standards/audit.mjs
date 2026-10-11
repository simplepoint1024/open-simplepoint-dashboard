import {parse} from '@babel/parser';
import {createHash} from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const ignored = new Set(['node_modules', 'dist', 'build', '.cache', '@mf-types']);
const nativeControls = new Set(['Table', 'Button', 'Form', 'Pagination', 'Select', 'TreeSelect',
  'DatePicker', 'TimePicker', 'Cascader']);
const rawControls = new Set(['table', 'form', 'button', 'select']);
const layoutMembers = new Set(['Group', 'Compact', 'Item', 'List', 'ErrorList']);
const files = directory => fs.readdirSync(directory, {withFileTypes: true}).flatMap(entry => {
  const name = path.join(directory, entry.name);
  return entry.isDirectory() ? ignored.has(entry.name) ? [] : files(name)
    : entry.name.endsWith('.tsx') ? [name] : [];
});
const visit = (node, callback) => {
  if (!node || typeof node !== 'object') return;
  if (Array.isArray(node)) {node.forEach(child => visit(child, callback)); return;}
  if (node.type) callback(node);
  for (const [key, child] of Object.entries(node)) {
    if (!['loc', 'comments', 'tokens', 'extra'].includes(key) && child && typeof child === 'object') visit(child, callback);
  }
};

/** Resolve import aliases: a DataTable called Table is still a platform table. */
export function inspectSource(source, filename) {
  const ast = parse(source, {sourceType: 'module', plugins: ['typescript', 'jsx']});
  const imports = new Map();
  for (const statement of ast.program.body) {
    if (statement.type !== 'ImportDeclaration') continue;
    for (const item of statement.specifiers) imports.set(item.local.name, {
      module: statement.source.value,
      name: item.type === 'ImportNamespaceSpecifier' ? '*' : item.imported?.name ?? item.imported?.value ?? 'default',
    });
  }
  const controls = [];
  visit(ast, node => {
    if (node.type !== 'JSXOpeningElement') return;
    const names = [];
    const flatten = name => {
      if (name.type === 'JSXIdentifier') names.push(name.name);
      else if (name.type === 'JSXMemberExpression') {flatten(name.object); flatten(name.property);}
    };
    flatten(node.name);
    if (!names.length) return;
    const definition = imports.get(names[0]);
    const component = definition?.name === '*' ? names[1] : definition?.name;
    const suffix = names.slice(definition?.name === '*' ? 2 : 1);
    // Form.Item/Button.Group are layout helpers, not a separate form or action contract.
    if (suffix.some(name => layoutMembers.has(name))) return;
    const raw = !definition && names.length === 1 && rawControls.has(names[0]);
    const native = raw || definition?.module === 'antd' && nativeControls.has(component);
    const platform = definition?.module.startsWith('@simplepoint/components/');
    if (native || platform) controls.push({
      component: raw ? `html:${names[0]}` : native ? [component, ...suffix].join('.')
        : definition.module.split('/').slice(2).join('/'),
      native: Boolean(native),
      line: node.loc.start.line,
      fingerprint: createHash('sha256').update(source.slice(node.start, node.end)).digest('hex'),
      attributes: node.attributes.filter(attribute => attribute.type === 'JSXAttribute').map(attribute => attribute.name.name),
    });
  });
  return {file: filename, controls};
}

/** Exceptions are exact reviewed controls, never directory/component-wide suppression rules. */
export function classifyControls(findings, ledger, evidenceExists = file => fs.existsSync(path.join(root, file))) {
  const categories = new Set(['navigation-control', 'local-editor-command', 'local-enum-filter', 'specialized-calendar',
    'paged-projection', 'hierarchy-navigation', 'query-parameter-control']);
  const key = value => JSON.stringify([value.file, value.component, value.fingerprint]);
  const invalid = [], entries = ledger?.version === 1 && Array.isArray(ledger.controls) ? ledger.controls : [];
  if (ledger?.version !== 1 || !Array.isArray(ledger?.controls)) invalid.push('Missing or unsupported review ledger');
  const reviewed = new Map();
  for (const entry of entries) {
    const signature = key(entry);
    if (!categories.has(entry.category) || typeof entry.reason !== 'string' || entry.reason.trim().length < 30
      || !/^[a-f0-9]{64}$/.test(entry.fingerprint ?? '') || !Array.isArray(entry.evidence) || !entry.evidence.length
      || entry.evidence.some(file => typeof file !== 'string' || path.isAbsolute(file) || file.includes('..') || !evidenceExists(file))
      || reviewed.has(signature)) {invalid.push(`Invalid or duplicate review: ${entry.file}:${entry.component}`); continue;}
    reviewed.set(signature, entry);
  }
  const present = new Set(findings.map(key));
  return {unreviewed: findings.filter(finding => !reviewed.has(key(finding))),
    stale: entries.filter(entry => !present.has(key(entry))), invalid};
}

const main = () => {
  const pages = files(path.join(root, 'modules')).sort().filter(file => !file.includes('/modules/mocks/'))
    .map(file => inspectSource(fs.readFileSync(file, 'utf8'), path.relative(root, file)));
  const findings = pages.flatMap(page => page.controls.filter(control => control.native)
    .map(control => ({file: page.file, ...control})));
  const ledgerPath = path.join(root, 'scripts/ui-standards/reviewed-controls.json');
  const classification = classifyControls(findings, fs.existsSync(ledgerPath) ? JSON.parse(fs.readFileSync(ledgerPath, 'utf8')) : null);
  const report = {sourceFiles: pages.length, filesWithNativeControls: new Set(findings.map(item => item.file)).size,
    nativeControls: findings.length, reviewedControls: findings.length - classification.unreviewed.length,
    classification, findings, pages};
  if (process.argv.includes('--json')) console.log(JSON.stringify(report, null, 2));
  else {
    for (const page of pages) {
      const native = page.controls.filter(control => control.native);
      if (native.length) console.log(`${page.file}: ${native.map(control => `${control.component}:${control.line}`).join(', ')}`);
    }
    console.log(`Inspected ${pages.length} source files; ${report.reviewedControls}/${findings.length} native controls reviewed individually; ${classification.unreviewed.length} still unreviewed.`);
    for (const finding of classification.unreviewed) console.log(`UNREVIEWED ${finding.file}:${finding.line} ${finding.component}`);
    for (const entry of classification.stale) console.log(`STALE REVIEW ${entry.file} ${entry.component}`);
    classification.invalid.forEach(error => console.log(error));
  }
  if (process.argv.includes('--strict') && (classification.unreviewed.length || classification.stale.length || classification.invalid.length)) process.exitCode = 1;
};
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
