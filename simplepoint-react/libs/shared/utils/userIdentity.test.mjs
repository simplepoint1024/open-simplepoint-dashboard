import assert from 'node:assert/strict';
import test from 'node:test';
import {userDisplayName, userFullName} from './userIdentity.ts';

test('identity uses explicit name, structured legacy name, then email and never ID/nickname/phone', () => {
  const user = {id: 'internal', nickname: 'Nickname', phoneNumber: '138', email: ' person@example.test '};
  assert.equal(userDisplayName(user), 'person@example.test');
  assert.equal(userFullName(user), undefined);
  assert.equal(userDisplayName({...user, familyName: ' Family ', middleName: ' ', givenName: ' Given '}), 'Family Given');
  assert.equal(userDisplayName({...user, name: ' Full name ', givenName: 'Other'}), 'Full name');
  assert.equal(userDisplayName({...user, email: ' '}), undefined);
});

test('OIDC name components work and explicit profile clearing overrides stale snake-case claims', () => {
  assert.equal(userDisplayName({family_name: 'Family', given_name: 'Given'}), 'Family Given');
  assert.equal(userDisplayName({given_name: 'Old', givenName: null, email: 'new@example.test'}), 'new@example.test');
  assert.equal(userDisplayName(null), undefined);
});
