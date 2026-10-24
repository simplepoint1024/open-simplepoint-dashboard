import assert from 'node:assert/strict';

/** Exercises public designer transport budgets and validates drafts without changing persisted state. */
export async function verifyCalculationBoundaries({call, request, appId, spec, state, save}) {
  const base = `/app/applications/${appId}/forms/${spec.formId}`;
  const original = await call('admin', base + '/draft');
  const total = original.document.fields.find(field => field.fieldId === 'total'); assert(total?.calculation);
  const details = Object.fromEntries(original.document.details.map(detail => [detail.detailId, []]));
  const candidate = expression => {
    const document = structuredClone(original.document);
    document.fields.find(field => field.fieldId === total.fieldId).calculation.expression = expression;
    return {document, values: {quantity: '3', rate: '4'}, details};
  };
  const marker = 'private_probe_日本語_7b2c';
  const cases = [
    ['expression-length', candidate('1+'.repeat(10000) + '1')],
    ['expression-depth', candidate('('.repeat(65) + '1' + ')'.repeat(65))],
    ['expression-nodes', candidate(Array.from({length: 2200}, () => '1').join('+'))],
    ['number-range', candidate('1e999999')],
    ['output-size', candidate('IFERROR(REPT("' + marker + '",65536),0)')],
    ['unknown-reference', candidate('[' + marker + ']')],
    ['dependency-cycle', candidate('[normalized_total]+1')],
    ['invalid-syntax', candidate('Runtime.exec("' + marker + '");')],
  ];
  if (original.document.details.length) cases.push(['sample-row-budget', {...candidate('1'),
    details: {...details, [original.document.details[0].detailId]: Array.from({length: 41}, () => ({}))}}]);
  state.checks ??= [];
  for (const [name, command] of cases) {
    const started = performance.now();
    const result = await request('admin', base + '/calculations/preview', 'POST', command);
    const elapsedMs = Math.round(performance.now() - started);
    assert.equal(result.status, 400, name + ' rejects invalid computation');
    assert(!result.body.includes(marker), name + ' does not disclose submitted values');
    assert(!result.body.includes('java.lang.') && !result.body.includes('stackTrace'));
    state.checks.push({name, status: result.status, elapsedMs, envelope: JSON.parse(result.body)}); await save();
  }
  const invalid = candidate('[normalized_total]+1');
  const saveInvalid = await request('admin', base + '/draft', 'PUT', {expectedRevision: original.revision, document: invalid.document});
  assert.equal(saveInvalid.status, 400, 'Cyclic calculation cannot become a saved publication source');
  assert.deepEqual(await call('admin', base + '/draft'), original, 'Rejected calculations leave the entire draft unchanged');
  const denied = await request('employee', base + '/calculations/preview', 'POST', candidate('1'));
  assert.equal(denied.status, 403, 'Runtime users cannot submit their own design expressions');
  state.rejectedDraftUnchanged = true; state.nonDesignerStatus = denied.status;
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
  console.log('PASS real designer computation budgets, dependency errors, immutable rejected drafts and design authority');
}
