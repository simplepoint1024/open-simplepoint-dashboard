import assert from 'node:assert/strict';
import {test} from 'node:test';
import {providerTarget, connectionTestResult, providerInput, providerEditorSchema} from './model.ts';

test('provider targets use literal readable IDs and minimal immutable snapshots', () => {
  for (const id of [undefined, null, '', ' ', '***', ' ***** ', 12]) assert.equal(providerTarget({id}), undefined);
  const row = {id: 'literal/a b', displayName: 'Actual', registrationId: '***', clientId: 'private', enabled: true};
  const target = providerTarget(row); row.id = 'changed';
  assert.deepEqual(target, {id: 'literal/a b', label: 'Actual', registrationId: undefined});
});
test('test results require a real boolean; false is not a successful or malformed result', () => {
  for (const value of [null, {}, {success: 'true'}, {success: 'false'}, {success: 1}]) assert.equal(connectionTestResult(value), undefined);
  assert.equal(connectionTestResult({success: false, message: 'failed'}).success, false);
  assert.equal(connectionTestResult({success: true, tokenUri: '***', clientSecret: 'never-retain'}).tokenUri, undefined);
  assert.equal('clientSecret' in connectionTestResult({success: true, clientSecret: 'never-retain'}), false);
});
test('callback output remains the server value, including relative paths and a different public origin', () => {
  for (const callbackUri of ['/login/oauth2/code/actual', 'https://auth.example.test/base/login/oauth2/code/actual']) {
    assert.equal(connectionTestResult({success: true, callbackUri}).callbackUri, callbackUri);
  }
});
test('writes omit projections, immutable IDs and policy-protected values while preserving false, zero and explicit clearing', () => {
  const schema = {properties: {description: {}, enabled: {}, sortOrder: {}, clientId: {readOnly: true},
    protocol: {'x-field-access': 'MASKED'}, registrationId: {}, clientSecret: {}}};
  assert.deepEqual(providerInput(schema, {id: 'spoof', registrationId: 'changed', clientId: '***', protocol: '***',
    clientSecretCiphertext: 'never-write', clientSecretConfigured: true, enabled: false, sortOrder: 0, description: null, clientSecret: '  '}, true),
    {description: null, enabled: false, sortOrder: 0});
  assert.deepEqual(providerInput(schema, {registrationId: 'new', clientSecret: ' actual secret '}, false),
    {registrationId: 'new', clientSecret: ' actual secret '});
});
test('defaults never override readonly policy or falsify masked booleans/enums; source schema is unchanged', () => {
  const schema = {properties: {displayName: {type: ['string', 'null']}, enabled: {type: 'boolean', 'x-field-access': 'MASKED'},
    protocol: {type: 'string', enum: ['OIDC', 'OAUTH2'], 'x-field-access': 'MASKED'}, requireVerifiedEmail: {type: 'boolean', readOnly: true},
    sortOrder: {type: 'integer'}, clientSecretConfigured: {}, clientSecretCiphertext: {}, registrationId: {type: 'string'}}, required: ['enabled']};
  const next = providerEditorSchema(schema, {id: 'actual', enabled: '***', protocol: '***', requireVerifiedEmail: false, sortOrder: 0}, 'Keep encrypted secret');
  for (const field of ['enabled', 'protocol']) {
    assert.equal(next.properties[field].const, '***'); assert.equal(next.properties[field].readOnly, true);
    assert.equal(next.properties[field].type, 'string'); assert.equal(next.properties[field].default, undefined);
  }
  assert.equal(next.properties.requireVerifiedEmail.default, undefined); assert.equal(next.properties.registrationId.readOnly, true);
  assert.equal(next.properties.clientSecretConfigured, undefined); assert.equal(next.properties.clientSecretCiphertext, undefined);
  assert.deepEqual(next.required, ['displayName']); assert.equal(schema.properties.enabled.type, 'boolean');
});
test('creation uses genuine schema validation for mandatory text and conditional secret; editing does not require a new secret', () => {
  const schema = {properties: {registrationId: {}, displayName: {}, clientId: {}, clientSecret: {}, clientAuthenticationMethod: {}}};
  const added = providerEditorSchema(schema, null, 'keep');
  assert.deepEqual(added.required, ['registrationId', 'displayName', 'clientId']);
  assert.equal(added.properties.clientId.pattern, '\\S'); assert.equal(added.allOf[0].if.properties.clientAuthenticationMethod.const, 'none');
  assert.deepEqual(added.allOf[0].else.required, ['clientSecret']);
  assert.equal(providerEditorSchema(schema, {id: 'actual'}, 'keep').allOf, undefined);
});
test('protocol-dependent username and subject defaults remain service-owned instead of imposing OIDC sub on OAuth2', () => {
  const schema = {properties: {protocol: {}, userNameAttribute: {}, subjectClaim: {}}};
  const next = providerEditorSchema(schema, null, 'keep');
  assert.equal(next.properties.userNameAttribute.default, undefined); assert.equal(next.properties.subjectClaim.default, undefined);
});
