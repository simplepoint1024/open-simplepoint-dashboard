import test from 'node:test';
import assert from 'node:assert/strict';
import validator from '@rjsf/validator-ajv8';
import {identifiableMember, membershipPayload, memberTypeSchema} from './memberForms.ts';
const row = {userId: 'actual', name: 'Actual', orgId: 'original-child', revision: 7};
test('member types use the real dictionary and standard required enum validation', () => {
  const schema = memberTypeSchema((_key, fallback) => fallback, [{value: 'INTERNAL', label: '内部'}, {value: 'REFERENCE', label: '引用'}]);
  const valid = data => !validator.rawValidation(schema, data).errors?.length;
  assert.equal(valid({userType: 'REFERENCE'}), true);
  assert.equal(valid({userType: ''}), false); assert.equal(valid({}), false); assert.equal(valid({userType: 'fake'}), false);
  const empty = memberTypeSchema((_key, fallback) => fallback, []);
  assert.ok(validator.rawValidation(empty, {userType: 'INTERNAL'}).errors?.length, 'an unavailable dictionary cannot authorize an invented choice');
});
test('type editing preserves its captured child organization and revision; transfers use the selected target', () => {
  assert.deepEqual(membershipPayload(row, row.orgId, {userType: 'REFERENCE', userId: 'foreign', enabled: false}, true),
    {orgId: 'original-child', revision: 7, userType: 'REFERENCE'});
  assert.deepEqual(membershipPayload(row, 'selected-target', {userType: 'INTERNAL'}, false), {orgId: 'selected-target', revision: 7});
});
test('redacted, absent or malformed identities/revisions cannot be written', () => {
  for (const invalid of [{...row, userId: '***'}, {...row, revision: '***'}, {...row, revision: undefined}, {...row, revision: 1.5},
    {...row, revision: -1}]) {assert.equal(identifiableMember(invalid), false); assert.throws(() => membershipPayload(invalid, 'actual', {}, false));}
});
