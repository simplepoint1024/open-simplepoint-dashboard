import assert from 'node:assert/strict';
import test from 'node:test';
import {MB, providerFormSchema, providerPayload, providerCanCreate, quotaFormSchema, quotaPayload} from './forms.ts';

const t = (_key, fallback) => fallback;
const provider = {properties: Object.fromEntries(['name', 'code', 'type', 'endpoint', 'region', 'accessKey',
  'secretKey', 'secretKeyCiphertext', 'bucket', 'basePath', 'publicBaseUrl', 'enabled', 'description']
  .map(field => [field, {type: 'string'}]))};
provider.properties.type.enum = ['MINIO', 'S3'];
const quota = {properties: {tenantId: {type: 'string'}, quotaBytes: {type: 'integer'}, enabled: {type: 'boolean'},
  description: {type: 'string'}, tenantName: {type: 'string', readOnly: true}}};

test('provider form reuses declared fields, never exposes ciphertext, and preserves blank edit secrets', () => {
  const schema = providerFormSchema(provider, {id: 'p1'}, t);
  assert.equal(schema.properties.secretKeyCiphertext, undefined);
  assert.equal(schema.properties.code.readOnly, true);
  assert.equal(schema.properties.secretKey['x-ui'].widget, 'password');
  assert.equal(schema.required.includes('secretKey'), false);
  assert.equal(providerFormSchema(provider, null, t).required.includes('secretKey'), true);
  assert.equal(providerCanCreate(provider), true);
  const payload = providerPayload(provider, {name: ' Updated ', secretKey: '', secretKeyCiphertext: 'forged'}, {id: 'p1'});
  assert.equal(payload.name, 'Updated');
  assert.equal(payload.secretKey, null);
  assert.equal('secretKeyCiphertext' in payload, false);
  assert.equal(payload.id, 'p1');
  assert.deepEqual(provider.properties.type.enum, ['MINIO', 'S3'], 'transforms never mutate cached server schema');
});

test('protected and absent fields cannot be made editable or submitted through form transforms', () => {
  const protectedProvider = structuredClone(provider);
  protectedProvider.properties.accessKey.readOnly = true;
  delete protectedProvider.properties.endpoint;
  const schema = providerFormSchema(protectedProvider, null, t);
  assert.equal(schema.properties.accessKey, undefined);
  assert.equal(schema.properties.endpoint, undefined);
  assert.equal(providerCanCreate(protectedProvider), false);
  const payload = providerPayload(protectedProvider, {accessKey: 'forged', endpoint: 'forged'}, {id: 'p1'});
  assert.equal('accessKey' in payload, false);
  assert.equal('endpoint' in payload, false);
  const protectedQuota = structuredClone(quota);
  protectedQuota.properties.quotaBytes.readOnly = true;
  assert.equal(quotaFormSchema(protectedQuota, {quotaBytes: 123}, t).properties.quotaMb, undefined);
  assert.equal('quotaBytes' in quotaPayload(protectedQuota, {quotaMb: 99}, {id: 'q1'}), false);
});

test('unchanged two-decimal MB presentation never rounds an existing byte-exact quota', () => {
  for (const bytes of [1, 123, MB + 7, 42 * MB + 12345, null]) {
    const editing = {id: 'q1', quotaBytes: bytes};
    const schema = quotaFormSchema(quota, editing, t);
    const payload = quotaPayload(quota, {quotaMb: schema.properties.quotaMb.default, description: 'edited'}, editing);
    assert.equal(payload.quotaBytes, bytes);
  }
});

test('changed quota amounts support zero and unlimited without unsafe numeric conversions', () => {
  const editing = {id: 'q1', quotaBytes: MB};
  assert.equal(quotaPayload(quota, {quotaMb: 2.5}, editing).quotaBytes, 2.5 * MB);
  assert.equal(quotaPayload(quota, {quotaMb: 0}, editing).quotaBytes, 0);
  assert.equal(quotaPayload(quota, {}, editing).quotaBytes, null);
  assert.throws(() => quotaPayload(quota, {quotaMb: Infinity}, editing), /QUOTA_RANGE_EXCEEDED/);
  assert.throws(() => quotaPayload(quota, {quotaMb: Number.MAX_SAFE_INTEGER}, editing), /QUOTA_RANGE_EXCEEDED/);
});
