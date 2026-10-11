import {test} from 'node:test';
import assert from 'node:assert/strict';
import {canNotificationAction, notificationDate, notificationFormData, notificationFormUi, usableNotification} from './model.ts';

test('notification actions require the exact real source identity and server lifecycle state', () => {
  for (const [action, status] of [['publish', 'DRAFT'], ['revoke', 'PUBLISHED'], ['duplicate', 'REVOKED']]) {
    assert.equal(canNotificationAction(action, {id: 'literal/id', status}), true);
    for (const id of [undefined, '', ' ', '***', ' ***** ', 1]) assert.equal(canNotificationAction(action, {id, status}), false);
    for (const wrong of ['DRAFT', 'PUBLISHED', 'REVOKED', '***', undefined].filter(value => value !== status))
      assert.equal(canNotificationAction(action, {id: 'literal/id', status: wrong}), false);
  }
  assert.equal(usableNotification({key: 'presentation-only', status: 'DRAFT'}), false);
});
test('notification dates preserve valid instant presentation without Invalid Date or masked truthiness', () => {
  const instant = '2026-10-01T12:30:00Z';
  assert.equal(notificationDate(instant), new Date(instant).toLocaleString());
  for (const value of [undefined, null, '', ' ', '***', 'not-a-date', 1, false]) assert.equal(notificationDate(value), '-');
});
test('audience controls use actual platform registries and keep unknown discriminants read-only', () => {
  assert.deepEqual(notificationFormUi({}), {audienceId: {'ui:widget': 'hidden'}});
  assert.deepEqual(notificationFormUi({audienceType: 'USER'}), {audienceId: {'ui:widget': 'UserPicker'}});
  assert.deepEqual(notificationFormUi({audienceType: 'TENANT'}), {audienceId: {'ui:widget': 'EntitySelect', 'ui:options': {endpoint: '/common/platform/tenants'}}});
  assert.deepEqual(notificationFormUi({audienceType: '***'}), {audienceId: {'ui:readonly': true}});
  assert.deepEqual(notificationFormUi({audienceType: 'USER', audienceId: '***'}, false), {audienceType: {'ui:readonly': true}, audienceId: {'ui:readonly': true}});
  assert.deepEqual(notificationFormUi({audienceType: 'USER'}, true, false), {audienceType: {'ui:readonly': true}, audienceId: {'ui:widget': 'UserPicker'}});
});
test('same audience retains its choice while switching kinds clears only the identifier, never other draft values', () => {
  const next = {audienceType: 'USER', audienceId: 'user-1', title: 'Draft', content: 'Text'};
  assert.equal(notificationFormData(next, {audienceType: 'USER'}), next);
  assert.deepEqual(notificationFormData({...next, audienceType: 'TENANT'}, next), {...next, audienceType: 'TENANT', audienceId: undefined});
  assert.deepEqual(notificationFormData({...next, audienceType: 'ALL'}, next), {...next, audienceType: 'ALL', audienceId: undefined});
});
