import assert from 'node:assert/strict';
import test from 'node:test';
import {loadResourcePreview, PreviewChangedError} from './previewLoader.ts';

const row = (id, parentId) => ({id, parentId, code: 'code-' + id, revision: 'server-v2', grantable: true, granted: true});
const response = (rows, index, total = rows.length) => ({content: rows.slice(index * 100, (index + 1) * 100),
  page: {number: index, size: 100, totalElements: total, totalPages: Math.ceil(total / 100)}});

test('complete grant preview uses sequential 100-row pages and 200-ID ancestor batches without truncating off-page changes', async () => {
  const rows = Array.from({length: 521}, (_, i) => row('row-' + i, 'parent-' + i));
  const baseline = {...rows[520], revision: 'original-v1'}, draft = {[baseline.id]: {baseline, granted: false}};
  const pages = [], batches = [], controller = new AbortController();
  const result = await loadResourcePreview(draft, controller.signal, () => true,
    async (index, signal) => {assert.equal(signal, controller.signal); pages.push(index); return response(rows, index);},
    async (ids, signal) => {assert.equal(signal, controller.signal); batches.push(ids); return ids.map(id => row(id));});
  assert.deepEqual(pages, [0, 1, 2, 3, 4, 5]); assert.deepEqual(batches.map(ids => ids.length), [200, 200, 121]);
  assert.equal(result.length, 1042); assert.equal(result.find(current => current.id === baseline.id).revision, 'original-v1');
  assert.equal(draft[baseline.id].granted, false);
});

test('a cancelled or no-longer-current owner does not continue its remaining preview pages or ancestor reads', async () => {
  for (const abort of [true, false]) {
    const controller = new AbortController(), rows = Array.from({length: 201}, (_, i) => row(String(i), 'parent'));
    let current = true, calls = 0;
    await assert.rejects(loadResourcePreview({}, controller.signal, () => current, async index => {
      calls++; if (abort) controller.abort(); else current = false; return response(rows, index);
    }, async () => {throw Error('Cancelled context read started');}), {name: 'AbortError'});
    assert.equal(calls, 1);
  }
});

test('changing totals and invalid metadata fail visibly instead of presenting a partial result as complete', async () => {
  const rows = Array.from({length: 101}, (_, i) => row(String(i)));
  for (const invalid of [page => ({...page, page: {...page.page, number: 9}}),
    page => ({...page, page: {...page.page, totalPages: Infinity}}),
    page => ({...page, page: {...page.page, totalElements: 102}})]) {
    await assert.rejects(loadResourcePreview({}, new AbortController().signal, () => true,
      async index => index === 0 ? response(rows, index) : invalid(response(rows, index)), async () => []), PreviewChangedError);
  }
});

test('duplicate rows across result pages cannot hide an omitted grant in the full preview', async () => {
  const rows = Array.from({length: 101}, (_, i) => row(String(i)));
  await assert.rejects(loadResourcePreview({}, new AbortController().signal, () => true,
    async index => index === 0 ? response(rows, index) : {...response(rows, index), content: [rows[0]]}, async () => []), PreviewChangedError);
});

test('an empty current grant set still retains every staged addition and its original ancestor context', async () => {
  const baseline = row('addition', 'parent'); baseline.granted = false;
  const result = await loadResourcePreview({addition: {baseline, granted: true}}, new AbortController().signal, () => true,
    async index => response([], index), async ids => ids.map(id => row(id)));
  assert.deepEqual(result.map(current => current.id), ['addition', 'parent']);
});
