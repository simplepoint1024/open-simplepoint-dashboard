import assert from 'node:assert/strict';
import test from 'node:test';
import {
  DEFAULT_TABLE_DISPLAY_SETTINGS,
  mergeColumnSettings,
  normalizeColumnSettings,
  normalizeTableDisplaySettings,
} from './settings.ts';

test('fixed columns are kept at their required table edges', () => {
  const settings = [
    {key: 'name', label: 'Name', visible: true},
    {key: 'actions', label: 'Actions', visible: true, fixed: 'right'},
    {key: 'id', label: 'ID', visible: true, fixed: 'left'},
    {key: 'status', label: 'Status', visible: true},
  ];

  assert.deepEqual(
    normalizeColumnSettings(settings).map(item => item.key),
    ['id', 'name', 'status', 'actions'],
  );
});

test('stored settings follow current columns and preserve current labels', () => {
  const defaults = [
    {key: 'name', label: 'Current name', visible: true},
    {key: 'status', label: 'Status', visible: true},
  ];
  const saved = [
    {key: 'removed', label: 'Removed', visible: true},
    {key: 'name', label: 'Old name', visible: false, fixed: 'right', width: 240},
  ];

  assert.deepEqual(mergeColumnSettings(defaults, saved), [
    {key: 'status', label: 'Status', visible: true},
    {key: 'name', label: 'Current name', visible: false, fixed: 'right', width: 240},
  ]);
});

test('stored drag order survives reload and new columns are appended', () => {
  const defaults = [
    {key: 'name', label: 'Name', visible: true},
    {key: 'status', label: 'Status', visible: true},
    {key: 'createdAt', label: 'Created', visible: true},
  ];
  const saved = [
    {key: 'status', label: 'Status', visible: true},
    {key: 'name', label: 'Name', visible: true},
  ];

  assert.deepEqual(mergeColumnSettings(defaults, saved).map(item => item.key), [
    'status', 'name', 'createdAt',
  ]);
});

test('an explicitly cleared fixed position overrides a new default', () => {
  const defaults = [{key: 'actions', label: 'Actions', visible: true, fixed: 'right'}];
  const saved = [{key: 'actions', label: 'Actions', visible: true, fixed: null}];

  assert.deepEqual(mergeColumnSettings(defaults, saved), [
    {key: 'actions', label: 'Actions', visible: true},
  ]);
});

test('default table display preferences expose every supported switch', () => {
  assert.deepEqual(DEFAULT_TABLE_DISPLAY_SETTINGS, {
    size: 'middle',
    bordered: true,
    striped: true,
    wrapText: false,
    stickyHeader: true,
    rowHover: true,
    showSizeChanger: true,
    showQuickJumper: true,
  });
});

test('invalid persisted appearance and width values are safely normalized', () => {
  assert.deepEqual(normalizeTableDisplaySettings({size: 'giant', striped: 'yes'}), {
    ...DEFAULT_TABLE_DISPLAY_SETTINGS,
  });
  assert.equal(mergeColumnSettings(
    [{key: 'name', label: 'Name', visible: true}],
    [{key: 'name', label: 'Name', visible: true, width: 99999}],
  )[0].width, 1200);
});
