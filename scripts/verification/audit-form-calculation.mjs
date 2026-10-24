import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync} from 'node:fs';
import {dirname, resolve, relative, isAbsolute} from 'node:path';
import {fileURLToPath} from 'node:url';

// This audits coverage and evidence references. Passing it never substitutes for reading the
// actual test assertions, published business data and browser/database acceptance results.
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const file = path => {
  const target = resolve(root, path);
  const rel = relative(root, target);
  assert(!isAbsolute(rel) && rel !== '..' && !rel.startsWith('../'), 'Evidence must remain in the repository');
  return readFileSync(target);
};
const read = path => JSON.parse(file(path));
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const matrix = read('doc/verification/form-calculation-acceptance-matrix.json');
const catalog = read(matrix.catalog);
assert.equal(matrix.schemaVersion, 1);
assert.equal(catalog.schemaVersion, 1);
for (const source of matrix.sourceFiles) {
  assert.equal(sha256(file(source.path)), source.sha256, `Refresh catalog and coverage after changing ${source.path}`);
}
const unique = values => {
  assert.equal(new Set(values).size, values.length, 'Duplicate coverage identity');
  return [...values].sort();
};
assert.deepEqual(unique(matrix.functions.map(item => item.name)), unique(catalog.calculation.functions.map(item => item.name)));
assert.deepEqual(unique(matrix.components.map(item => `${item.type}@${item.version}`)),
  unique(catalog.components.map(item => `${item.type}@${item.version}`)));
const plan = file(matrix.plan).toString();
const requirementIds = [...plan.matchAll(/^\| ((?:CAL|DAT|PUB|SEC|UI|WF|OPS)-\d\d) \|/gm)].map(match => match[1]);
assert.deepEqual(unique(matrix.requirements.map(item => item.id)), unique(requirementIds));
for (const category of ['diagramTypes', 'replacementEntries', 'prohibitedElements', 'unsupportedCollaborationElements']) {
  assert.deepEqual(unique(matrix.workflow.filter(item => item.contractCategory === category).map(item => item.capability)),
    unique(catalog.workflow[category]));
}
const entries = [...matrix.requirements, ...matrix.components, ...matrix.functions, ...matrix.workflow];
unique(entries.map(item => item.id));
for (const entry of entries) {
  assert(['pending', 'in_progress', 'failed', 'passed'].includes(entry.status), `Invalid status: ${entry.id}`);
  assert(entry.steps?.length && entry.expected?.length && entry.businessForms?.length, `Missing test plan: ${entry.id}`);
  assert(entry.businessForms.every(id => /^B[1-5]$/.test(id)), `Unknown business form: ${entry.id}`);
  assert(Array.isArray(entry.evidence), `Missing evidence array: ${entry.id}`);
  for (const evidence of entry.evidence) {
    assert.equal(sha256(file(evidence.path)), evidence.sha256, `Evidence hash changed: ${entry.id}`);
    assert(evidence.checkId?.length, `Evidence must identify its assertion: ${entry.id}`);
  }
  if (entry.status === 'passed') assert(entry.evidence.length, `Passed without evidence: ${entry.id}`);
}
const incomplete = entries.filter(entry => entry.status !== 'passed');
if (process.argv.includes('--complete')) {
  assert.equal(incomplete.length, 0, `Acceptance incomplete: ${incomplete.map(entry => entry.id).join(', ')}`);
}
console.log(JSON.stringify({coverageStructure: 'passed', entries: entries.length,
  passed: entries.length - incomplete.length, incomplete: incomplete.length,
  finalAcceptance: incomplete.length ? 'not_passed' : 'requires_evidence_review'}, null, 2));
