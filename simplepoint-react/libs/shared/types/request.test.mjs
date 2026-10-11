import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';
import {normalizePage} from './request.ts';

test('workflow PageResult preserves its numeric zero-based page alongside flat totals', () => {
  const rows = [{id: 'action:withdrawal'}];
  assert.deepEqual(normalizePage({content: rows, page: 3, size: 10, totalElements: 31}), {
    content: rows, page: {number: 3, size: 10, totalElements: 31, totalPages: 4},
  });
});

for (const [number, length] of [[0, 20], [26, 1]]) {
  test(`native resource HTTP page ${number} preserves its real count, offset and projected rows`, () => {
    const raw = JSON.parse(readFileSync(new URL(`../../../scripts/application-resource-contracts/root-page-${number}.json`, import.meta.url), 'utf8'));
    assert.equal(raw.page, undefined, 'the captured controller response uses flat Spring PageImpl metadata');
    const normalized = normalizePage(raw);
    assert.deepEqual(normalized.page, {number, size: 20, totalElements: 521, totalPages: 27});
    assert.equal(normalized.content, raw.content, 'normalization must not fabricate or re-project resource fields');
    assert.equal(normalized.content.length, length);
    assert.equal(normalized.content[0].id, number === 0 ? 'resource-000' : 'resource-520');
  });
}

test('the same page adapter preserves the nested production/legacy contract', () => {
  const raw = JSON.parse(readFileSync(new URL('../../../scripts/application-resource-contracts/root-page-26.json', import.meta.url), 'utf8'));
  const page = {number: raw.number, size: raw.size, totalElements: raw.totalElements, totalPages: raw.totalPages};
  assert.deepEqual(normalizePage({content: raw.content, page}), {content: raw.content, page});
});

for (const [number, id, length] of [[0, 'resource-001', 20], [25, 'resource-501', 1]]) {
  test(`native assigned page ${number} retains complete 501-record metadata and actual checked identities`, () => {
    const raw = JSON.parse(readFileSync(new URL(`../../../scripts/application-resource-contracts/assigned-page-${number}.json`, import.meta.url), 'utf8'));
    const normalized = normalizePage(raw);
    assert.deepEqual(normalized.page, {number, size: 20, totalElements: 501, totalPages: 26});
    assert.equal(normalized.content, raw.content);
    assert.equal(normalized.content.length, length);
    assert.equal(normalized.content[0].id, id);
    assert.ok(normalized.content.every(row => row.checked === true && row.grantable === true && row.disabled === false));
  });
}

for (const [number, length] of [[0, 20], [26, 1]]) {
  test(`native management page ${number} retains actual editable metadata and complete root pagination`, () => {
    const raw = JSON.parse(readFileSync(new URL(`../../../scripts/resource-management-contracts/page-${number}.json`, import.meta.url), 'utf8'));
    const normalized = normalizePage(raw);
    assert.deepEqual(normalized.page, {number, size: 20, totalElements: 521, totalPages: 27});
    assert.equal(normalized.content, raw.content);
    assert.equal(normalized.content.length, length);
    assert.equal(normalized.content[0].description, number === 0 ? 'Actual description 000' : 'Actual description 520');
    if (number === 0) {
      assert.equal(normalized.content[0].children.length, 41);
      assert.equal(normalized.content[0].children[40].method, 'POST');
      assert.equal(normalized.content[0].children[40].pattern, '/actual/40');
    }
  });
}
