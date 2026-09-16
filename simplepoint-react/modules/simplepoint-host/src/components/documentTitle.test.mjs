import assert from 'node:assert/strict';
import test from 'node:test';
import {
  DEFAULT_APP_TITLE,
  resolveDocumentTitle,
} from './documentTitle.ts';

test('root and route-like labels never leak into the browser tab', () => {
  assert.equal(resolveDocumentTitle('/', '简点平台'), '简点平台');
  assert.equal(resolveDocumentTitle('/ai/workbench', '简点平台'), '简点平台');
});

test('page titles include the localized platform identity', () => {
  assert.equal(
    resolveDocumentTitle('AI 工作台', 'Simple·Point 平台'),
    'AI 工作台 · Simple·Point 平台',
  );
});

test('empty platform titles use a stable build-time fallback', () => {
  assert.equal(resolveDocumentTitle(), DEFAULT_APP_TITLE);
  assert.equal(resolveDocumentTitle('  ', '  '), DEFAULT_APP_TITLE);
});

test('the platform title is not duplicated', () => {
  assert.equal(
    resolveDocumentTitle('Simple·Point Platform', 'Simple·Point Platform'),
    'Simple·Point Platform',
  );
});
