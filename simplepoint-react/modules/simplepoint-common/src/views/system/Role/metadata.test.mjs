import assert from 'node:assert/strict';
import test from 'node:test';
import {normalizeRoleMetadata, RoleMetadataError} from './metadata.ts';

test('role metadata trims actual writable fields without altering hidden/readonly values or immutable identities', () => {
  const data = {id: ' literal-id ', roleName: '  Role  ', authority: ' ROLE_LITERAL ', description: ' notes ', name: ' untouched '};
  const result = normalizeRoleMetadata(data, {roleName: {minLength: 1}, authority: {readOnly: true}, description: {'x-field-access': 'VISIBLE'}});
  assert.deepEqual(result, {...data, roleName: 'Role'}); assert.equal(data.roleName, '  Role  ');
});

test('schema lengths still apply after normalization and unknown field permissions never authorize rewriting', () => {
  assert.throws(() => normalizeRoleMetadata({roleName: '   '}, {roleName: {minLength: 1}}), RoleMetadataError);
  assert.deepEqual(normalizeRoleMetadata({authority: ' protected '}, {authority: {'x-field-access': 'MASKED'}}), {authority: ' protected '});
});
