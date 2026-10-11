import assert from 'node:assert/strict';
import {test} from 'node:test';
import {providerIdentity, providerTarget, providerInput, providerEditorSchema, providerTestResult, providerSyncResult, discoveredModels} from './model.ts';

test('only readable literal IDs target CRUD, and only positively enabled captured providers target external actions', () => {
  for (const id of ['', ' ', '***', ' **** ', 12, null]) assert.equal(providerIdentity({id}), undefined);
  for (const enabled of [false, '***', 'true', undefined]) assert.equal(providerTarget({id: 'actual', enabled}), undefined);
  const row = {id: 'actual/a?', name: 'Actual', enabled: true, credentialCiphertext: 'never-retain'};
  const captured = providerTarget(row); row.id = 'changed';
  assert.deepEqual(captured, {id: 'actual/a?', name: 'Actual', enabled: true});
});
test('connection and synchronization results must belong to the captured provider and carry real booleans/integer counts', () => {
  const result = {providerId: 'actual', success: false, discoveredModelCount: 0};
  assert.equal(providerTestResult(result, 'actual').success, false);
  assert.equal(providerTestResult({...result, success: 'false'}, 'actual'), undefined);
  assert.equal(providerTestResult(result, 'different'), undefined);
  assert.equal(providerTestResult({...result, discoveredModelCount: -1}, 'actual'), undefined);
  const synced = {providerId: 'actual', discovered: 2, created: 1, updated: 1, unavailable: 0};
  assert.deepEqual(providerSyncResult(synced, 'actual'), synced);
  assert.equal(providerSyncResult({...synced, created: '1'}, 'actual'), undefined);
});
test('discovery keeps only documented display metadata and deduplicates literal models without fabricated identifiers', () => {
  assert.equal(discoveredModels({}), undefined);
  assert.deepEqual(discoveredModels([{modelId: '***'}, {modelId: 'one', credential: 'never-retain'}, {modelId: 'one', displayName: 'Actual'}]),
    [{modelId: 'one', displayName: 'Actual', modelType: undefined, ownedBy: undefined, pricing: undefined}]);
});
test('discovered decimal prices retain exact wire strings without losing numeric sorting semantics', () => {
  const models = discoveredModels([{modelId: 'actual', pricing: {currency: 'USD', inputTokenPrice: '99999999999.99999999', outputTokenPrice: '0.00000001'}},
    {modelId: 'legacy', pricing: {inputTokenPrice: 0.25, outputTokenPrice: '***'}}]);
  assert.equal(models[0].pricing.inputTokenPrice, '99999999999.99999999'); assert.equal(models[0].pricing.outputTokenPrice, '0.00000001');
  assert.equal(models[1].pricing.inputTokenPrice, '0.25'); assert.equal(models[1].pricing.outputTokenPrice, undefined);
});
test('provider writes retain explicit false/clear values but never submit projections, readonly values or blank editing credentials', () => {
  const schema = {properties: {name: {}, description: {}, enabled: {}, apiKey: {}, baseUrl: {readOnly: true}, vendor: {'x-field-access': 'MASKED'}}};
  assert.deepEqual(providerInput(schema, {id: 'spoofed', name: 'Actual', description: null, enabled: false, apiKey: '  ',
    baseUrl: 'masked', vendor: '***', credentialCiphertext: 'never', lastStatus: 'SUCCESS'}, true), {name: 'Actual', enabled: false, description: null});
});
test('genuine form extensions preserve masked typed fields and protect server-owned metadata/defaults', () => {
  const schema = {properties: {name: {}, vendor: {'x-field-access': 'MASKED'}, enabled: {type: 'boolean', 'x-field-access': 'MASKED'},
    autoSyncEnabled: {readOnly: true}, providerType: {}, lastStatus: {}, apiKey: {}}};
  const next = providerEditorSchema(schema, {id: 'actual', enabled: '***', vendor: '***'}, {OPENAI: 'OpenAI'}, 'Keep credential');
  assert.equal(next.properties.vendor.oneOf, undefined); assert.equal(next.properties.enabled.type, 'string');
  assert.equal(next.properties.enabled.const, '***'); assert.equal(next.properties.autoSyncEnabled.default, undefined);
  assert.equal(next.properties.providerType, undefined); assert.equal(next.properties.lastStatus, undefined);
  assert.deepEqual(next.required, ['name']); assert.equal(schema.properties.enabled.type, 'boolean');
});
