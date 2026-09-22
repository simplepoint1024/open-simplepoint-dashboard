import {test} from 'node:test';
import assert from 'node:assert/strict';
import {accountActionDisabled} from './accountActions.ts';

const account = {id: 'account', revision: 3, name: 'Example', enabled: true, superAdmin: false, roles: ['AUDITOR']};
const grants = ['platform.accounts.create', 'platform.accounts.edit', 'platform.identity.manage'];

test('platform actions require current capabilities and complete single-row input', () => {
  assert.equal(accountActionDisabled('add', [], grants), false);
  for (const action of ['edit', 'config.identity']) {
    assert.equal(accountActionDisabled(action, [account], grants), false);
    assert.equal(accountActionDisabled(action, [account]), true);
    assert.equal(accountActionDisabled(action, [account], []), true);
    assert.equal(accountActionDisabled(action, [], grants), true);
    assert.equal(accountActionDisabled(action, [account, account], grants), true);
    for (const patch of [{id: '***'}, {id: undefined}, {revision: '***'}, {revision: undefined}, {revision: NaN}]) {
      assert.equal(accountActionDisabled(action, [{...account, ...patch}], grants), true);
    }
  }
});

test('hidden and masked identity fields cannot be turned into a replacement authorization command', () => {
  for (const patch of [{roles: '***'}, {roles: undefined}, {roles: ['unknown']}, {superAdmin: '***'}, {superAdmin: undefined}]) {
    assert.equal(accountActionDisabled('config.identity', [{...account, ...patch}], grants), true);
  }
  assert.equal(accountActionDisabled('config.identity', [{...account, roles: []}], grants), false);
  assert.equal(accountActionDisabled('edit', [{...account, name: null}], grants), false);
  assert.equal(accountActionDisabled('edit', [{...account, name: '***'}], grants), true);
  assert.equal(accountActionDisabled('edit', [{...account, enabled: '***'}], grants), true);
});
