import assert from 'node:assert/strict';
import {test} from 'node:test';
import {debugTarget, modelIdentity, modelInput, modelEditorSchema, providerLookupBatches, providerOption} from './model.ts';

test('only positively enabled available language models with readable literal IDs can be debugged', () => {
  const row = {id: 'literal/model?', modelId: 'actual', modelType: 'LLM', enabled: true, available: true};
  assert.deepEqual(debugTarget(row), {id: row.id, modelId: 'actual', displayName: undefined});
  for (const value of [false, null, undefined, '***', 'true']) {
    assert.equal(debugTarget({...row, enabled: value}), undefined); assert.equal(debugTarget({...row, available: value}), undefined);
  }
  assert.equal(debugTarget({...row, id: '***'}), undefined); assert.equal(debugTarget({...row, modelType: 'IMAGE'}), undefined);
  assert.equal(modelIdentity({...row, enabled: false}), row.id);
});
test('selected provider hydration is count- and URL-bounded without inventing labels or masking boolean meaning', () => {
  const ids = Array.from({length: 521}, (_, index) => `actual-provider-${index}`), batches = providerLookupBatches([...ids, '***', ids[0]]);
  assert.deepEqual(batches.flat(), ids);
  assert.ok(batches.every(batch => batch.length <= 100 && new URLSearchParams({ids: batch.join(',')}).toString().length <= 2500));
  assert.deepEqual(providerOption({id: 'real', name: 'Actual', enabled: false}, 'disabled'), {value: 'real', label: 'Actual (disabled)'});
  assert.deepEqual(providerOption({id: 'real', name: '***', code: 'Readable', enabled: '***'}, 'disabled'), {value: 'real', label: 'Readable'});
  assert.equal(providerOption({id: '***', name: 'Actual'}, 'disabled'), undefined);
  assert.equal(providerOption({id: 'real', name: '***', code: null}, 'disabled'), undefined);
});
test('model writes preserve false/clears and omit readonly prices, provider projections, discovery and derived auto flags', () => {
  const schema = {properties: {displayName: {}, enabled: {}, description: {}, providerId: {readOnly: true}, inputTokenPrice: {'x-field-access': 'MASKED'}}};
  assert.deepEqual(modelInput(schema, {id: 'spoofed', displayName: 'Actual', enabled: false, description: null, providerId: 'real',
    inputTokenPrice: '***', pricingAutoDetected: false, metadataJson: '{}', available: true}), {displayName: 'Actual', enabled: false, description: null});
});
test('native form metadata keeps policy-protected required fields and typed masks out of validation and submission', () => {
  const schema = {properties: {providerId: {readOnly: true}, modelId: {}, modelType: {'x-field-access': 'MASKED'}, enabled: {type: 'boolean', 'x-field-access': 'MASKED'},
    billingEnabled: {readOnly: true}, inputTokenPrice: {type: 'number', 'x-field-access': 'MASKED'}, providerName: {}, pricingAutoDetected: {}}};
  const next = modelEditorSchema(schema, {modelType: '***', enabled: '***', inputTokenPrice: '***'}, [{const: 'LLM', title: 'Language'}]);
  assert.deepEqual(next.required, ['modelId']); assert.equal(next.properties.enabled.type, 'string');
  assert.equal(next.properties.inputTokenPrice.const, '***'); assert.equal(next.properties.billingEnabled.default, undefined);
  assert.equal(next.properties.modelType.oneOf, undefined); assert.equal(next.properties.providerName, undefined); assert.equal(next.properties.pricingAutoDetected, undefined);
  assert.equal(schema.properties.enabled.type, 'boolean');
});
