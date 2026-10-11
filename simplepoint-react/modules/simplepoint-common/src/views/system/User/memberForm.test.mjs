import assert from 'node:assert/strict';
import {test} from 'node:test';
import {memberCommand, memberDraft, memberDraftChanged} from './memberForm.ts';

const row = {userId: 'member-1', name: 'Member', orgId: 'org-1', enabled: true, userType: 'EXTERNAL', revision: 4};
test('member draft keeps actual values and uses the established internal default only for additions', () => {
  assert.deepEqual(memberDraft(), {userType: 'INTERNAL'});
  assert.deepEqual(memberDraft(row), {orgId: 'org-1', enabled: true, userType: 'EXTERNAL'});
  assert.deepEqual(memberDraft({...row, orgId: null}), {orgId: undefined, enabled: true, userType: 'EXTERNAL'});
  assert.equal(memberDraftChanged({orgId: undefined, userType: 'INTERNAL'}, {userType: 'INTERNAL'}), false);
  assert.equal(memberDraftChanged(memberDraft(row), {...memberDraft(row), enabled: false}), true);
});
test('member command preserves captured revision and read-only organization without leaking extra fields', () => {
  const draft = {...memberDraft(row), orgId: 'forged', email: 'forged@example.test', userId: 'forged', revision: 99, enabled: false};
  assert.deepEqual(memberCommand('edit', row, draft, field => field === 'enabled'), {revision: 4, orgId: 'org-1', enabled: false});
  assert.deepEqual(memberCommand('edit', row, {...memberDraft(row), orgId: undefined}, () => true),
    {revision: 4, orgId: null, userType: 'EXTERNAL', enabled: true});
  assert.deepEqual(memberCommand('add', undefined, {email: '  member@example.test ', userType: 'INTERNAL', enabled: false, revision: 9}, () => true),
    {email: 'member@example.test', orgId: null, userType: 'INTERNAL'});
});
test('masked identities, missing revisions and unreadable organization never become edit commands', () => {
  for (const invalid of [{...row, userId: '***'}, {...row, userId: ''}, {...row, revision: undefined}, {...row, revision: Infinity},
    {...row, orgId: '***'}, {userId: 'member-1', name: 'Member', enabled: true, revision: 4}])
    assert.throws(() => memberCommand('edit', invalid, memberDraft(row), () => true));
  assert.throws(() => memberCommand('edit', row, {enabled: '***'}, () => true));
  assert.throws(() => memberCommand('edit', row, {orgId: '***'}, () => true));
  assert.throws(() => memberCommand('add', undefined, {email: 'member@example.test', userType: '***'}, () => true));
});
