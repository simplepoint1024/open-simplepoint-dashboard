import test from 'node:test';
import assert from 'node:assert/strict';
import validator from '@rjsf/validator-ajv8';
import {getDefaultFormState} from '@rjsf/utils';
import {profileDraft, profilePayload, profileSchema, passwordSchema} from './forms.ts';
const t = (_key, fallback) => fallback;
const valid = (schema, data) => !validator.rawValidation(schema, data).errors?.length;
const original = {id: 'actual-user', nickname: 'Actual', birthdate: '1990-01-02T12:30:41.123Z',
  gender: 'custom-value', locale: 'fr-FR', zoneinfo: 'UTC', picture: '/common/object-storage/images/old'};
test('profile dates and existing non-enumerated preferences survive untouched edits', () => {
  const draft = profileDraft(original), schema = profileSchema(t, draft, [{code: 'zh-CN', name: '中文'}]);
  assert.equal(valid(schema, draft), true);
  assert.deepEqual(profilePayload({...draft, nickname: 'Changed'}, original).birthdate, original.birthdate);
  const generated = getDefaultFormState(validator, schema, draft);
  for (const key of ['birthdate', 'gender', 'locale', 'zoneinfo']) assert.equal(generated[key], draft[key]);
});
test('profile writes whitelist personal fields and use the current persisted avatar', () => {
  const values = profilePayload({...profileDraft(original), enabled: false, id: 'different', superAdmin: true,
    picture: 'stale-avatar'}, {...original, picture: '/common/object-storage/images/new'});
  for (const key of ['id', 'enabled', 'superAdmin']) assert.equal(key in values, false);
  assert.equal(values.picture, '/common/object-storage/images/new');
  assert.equal(values.email, null);
  assert.equal(profilePayload({...profileDraft(original), birthdate: ''}, original).birthdate, null);
  assert.match(profilePayload({...profileDraft(original), birthdate: '1991-01-03'}, original).birthdate, /T.*Z$/);
});
test('profile validation requires an actual nickname and optional valid email/date', () => {
  const draft = profileDraft(original), schema = profileSchema(t, draft, []);
  assert.equal(valid(schema, {...draft, email: undefined, birthdate: undefined}), true);
  for (const data of [{nickname: ' '}, {nickname: 'x', email: 'invalid'}, {nickname: 'x', birthdate: 'not-a-date'},
    {nickname: 'x'.repeat(51)}, {nickname: 'x', phoneNumber: '1'.repeat(19)}]) assert.equal(valid(schema, data), false);
});
test('password schema retains all required fields and established lengths', () => {
  const schema = passwordSchema(t);
  assert.equal(valid(schema, {currentPassword: 'old', newPassword: 'actual-password', confirmPassword: 'actual-password'}), true);
  assert.equal(valid(schema, {newPassword: 'short', confirmPassword: 'short'}), false);
  assert.equal(valid(schema, {currentPassword: 'a'.repeat(65), newPassword: 'actual-password', confirmPassword: 'actual-password'}), false);
});

test('explicit full name is editable, optional, bounded and preserved independently of legacy parts', () => {
  const profile = {...original, name: '完整姓名', familyName: '旧姓', givenName: '旧名'};
  const draft = profileDraft(profile), schema = profileSchema(t, draft, []);
  assert.equal(draft.name, '完整姓名');
  assert.equal(profilePayload({...draft, nickname: 'Changed'}, profile).name, '完整姓名');
  assert.equal(profilePayload({...draft, name: ''}, profile).name, null);
  assert.equal(valid(schema, {...draft, name: 'x'.repeat(101)}), false);
  assert.equal(valid(schema, {...draft, name: ''}), true);
});
