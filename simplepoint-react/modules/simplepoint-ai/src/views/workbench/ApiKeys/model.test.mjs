import assert from 'node:assert/strict';
import {test} from 'node:test';
import {issuedKeyOf, apiKeyDate, apiKeyPolicyData, apiKeyEditorSchema} from './model.ts';

test('one-time secret disclosure rejects missing, masked and non-string output without changing the actual value', () => {
  for (const result of [null, {}, {issuedKey: ''}, {issuedKey: '   '}, {issuedKey: '***'}, {issuedKey: ' ***** '}, {issuedKey: 123}]) assert.equal(issuedKeyOf(result), undefined);
  assert.equal(issuedKeyOf({issuedKey: 'actual-one-time-value'}), 'actual-one-time-value');
});
test('masked or invalid API-key dates remain unknown, never Invalid Date', () => {
  for (const value of [null, undefined, '***', '', 'not-a-date', 123]) assert.equal(apiKeyDate(value), '—');
  assert.notEqual(apiKeyDate('2026-10-10T00:00:00Z'), '—');
});
test('policy writes cannot resend secret/list metadata or readonly values; explicit clearing is preserved', () => {
  const schema = {properties: {name: {}, description: {}, expiresAt: {}, enabled: {readOnly: true}, rateLimitPerMinute: {'x-field-access': 'MASKED'}}};
  assert.deepEqual(apiKeyPolicyData(schema, {id: 'captured', name: 'Actual', description: '', expiresAt: null,
    enabled: true, rateLimitPerMinute: '***', issuedKey: 'never-post', keyPrefix: 'prefix', usageCount: 100}),
    {name: 'Actual', description: '', expiresAt: null});
});
test('masked policy values remain readonly placeholders without falsifying numeric/date/boolean meaning or changing the server schema', () => {
  const schema = {properties: {name: {type: 'string'}, enabled: {type: 'boolean', 'x-field-access': 'MASKED'},
    expiresAt: {type: 'string', format: 'date-time', 'x-field-access': 'MASKED'},
    rateLimitPerMinute: {type: 'integer', minimum: 1, 'x-field-access': 'MASKED'}, issuedKey: {type: 'string'}}, required: ['name', 'issuedKey']};
  const result = apiKeyEditorSchema(schema, {enabled: '***', expiresAt: '***', rateLimitPerMinute: '***'});
  for (const field of ['enabled', 'expiresAt', 'rateLimitPerMinute']) {
    assert.equal(result.properties[field].type, 'string'); assert.equal(result.properties[field].readOnly, true);
    assert.equal(result.properties[field].const, '***'); assert.equal(result.properties[field].format, undefined);
  }
  assert.deepEqual(result.required, ['name']); assert.equal(result.properties.issuedKey, undefined);
  assert.equal(schema.properties.enabled.type, 'boolean'); assert.equal(schema.properties.expiresAt.format, 'date-time');
});
