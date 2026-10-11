import test from 'node:test';
import assert from 'node:assert/strict';
import validator from '@rjsf/validator-ajv8';
import {appSelection, homeDraft, homeSchema} from './forms.ts';
import {emptyPreferences, parsePreferences} from './preferences.ts';
const t = (_key, fallback) => fallback;
const valid = value => !validator.rawValidation(homeSchema(t, value.timeZone), value).errors?.length;
test('ordinary home settings are typed and preserve valid persisted time zone aliases', () => {
  const value = {...emptyPreferences(), timeZone: 'US/Eastern', groups: [{id: 'actual', name: 'Actual', paths: ['/actual']}]};
  assert.equal(valid(value), true); assert.equal(valid({...value, groups: [{...value.groups[0], name: ' '}]}), false);
  assert.equal(valid({...value, groups: [{...value.groups[0], paths: ['//foreign'] }]}), false);
  assert.equal(valid({...value, groups: [{...value.groups[0], paths: Array(25).fill('/actual')}]}), false);
  assert.equal(valid({...value, region: 'a'.repeat(61)}), false); assert.equal(valid({...value, version: 2}), false);
});
test('a new array item gets a unique identity once; edits and reorder retain all existing identities', () => {
  let count = 0; const ids = () => 'new-' + ++count;
  const first = homeDraft({...emptyPreferences(), groups: [{name: 'Actual', paths: []}, {name: 'Other', paths: []}]}, ids);
  const second = homeDraft({...first, groups: first.groups.toReversed()}, ids);
  assert.equal(count, 2); assert.deepEqual(second.groups.map(g => g.id), ['new-2', 'new-1']);
});
test('editing current authorized favorites preserves inaccessible paths and their quota', () => {
  const paths = appSelection(['/other-role', '/visible'], ['/new'], [{value: '/visible'}, {value: '/new'}]);
  assert.deepEqual(paths, ['/other-role', '/new']);
  assert.deepEqual(parsePreferences({...emptyPreferences(), groups: [{id: 'actual', name: 'Actual', paths}]}).groups[0].paths, paths);
});
