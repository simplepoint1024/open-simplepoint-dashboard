import test from 'node:test';
import assert from 'node:assert/strict';
import {referenceContextReady, referenceSearchContext} from './inputs.ts';
import {contextualReferenceSource, referenceSource} from './source.ts';
import {batchReferenceLookups} from './batch.ts';

test('only declared inputs leave the form and parent/row fields never shadow one another', () => {
  const reference = {contextual: true, contextInputs: [
    {source: 'FORM', fieldId: 'name', type: 'TEXT'}, {source: 'ROW', fieldId: 'name', type: 'STRING'},
    {source: 'ROW', fieldId: 'amount', type: 'DECIMAL'}, {source: 'FORM', fieldId: 'number', type: 'INT64'},
    {source: 'FORM', fieldId: 'active', type: 'BOOLEAN'},
  ]};
  const values = {fields: {name: '客户・日本語😀', number: '9007199254740993', active: false, organization: 'FORGED',
    uploads: [{token: 'DO-NOT-SEND'}]}, row: {name: '分支', amount: '9007199254740993.01', other: 'secret'}};
  assert.deepEqual(referenceSearchContext(reference, values), {
    fields: {name: {kind: 'STRING', value: '客户・日本語😀'}, number: {kind: 'INTEGER', value: '9007199254740993'}, active: {kind: 'BOOLEAN', value: 'false'}},
    row: {name: {kind: 'STRING', value: '分支'}, amount: {kind: 'DECIMAL', value: '9007199254740993.01'}},
  });
  assert.equal(referenceSearchContext({}, values), undefined);
  assert.deepEqual(referenceSearchContext({contextual: true}, values), {fields: {}, row: {}});
});

test('missing, masked, complex, oversized and inexact numeric inputs cannot become wire values', () => {
  const data = {empty: '', nil: null, mask: '***', object: {}, array: [], big: 'a'.repeat(2001), int: 9007199254740992, decimal: NaN};
  const contextInputs = Object.keys(data).map(fieldId => ({source: 'FORM', fieldId,
    type: fieldId === 'int' ? 'INT64' : fieldId === 'decimal' ? 'DECIMAL' : 'STRING'}));
  assert.deepEqual(referenceSearchContext({contextual: true, contextInputs}, {fields: data}), {fields: {}, row: {}});
});

test('incomplete dates and numbers disable browsing without discarding selected identities', () => {
  const reference = {contextual: true, contextInputs: [{source:'FORM', fieldId:'date',type:'DATE'}, {source:'ROW',fieldId:'number',type:'INT64'}]};
  const incomplete = referenceSearchContext(reference, {fields:{date:'2026-02-30'},row:{number:'-'}});
  assert.equal(referenceContextReady(reference, incomplete), false);
  assert.deepEqual(incomplete, {fields:{},row:{}});
  const complete = referenceSearchContext(reference, {fields:{date:'2026-10-29'},row:{number:'9007199254740993'}});
  assert.equal(referenceContextReady(reference, complete), true);
  assert.equal(referenceContextReady({contextual:true}, {fields:{},row:{}}), true, 'organization is supplied by the server');
  assert.equal(referenceContextReady(undefined, undefined), true);
});

test('forty contextual selectors isolate candidates but share bounded exact hydration', async () => {
  const calls = [], base = referenceSource('tenant/account/form/relation', {
    search: async () => {throw Error('Contextual browsing must not use the unfiltered source');},
    resolve: batchReferenceLookups(async ids => {
      calls.push(ids); return ids.map(id => ({id, label: id, version: 1, values: {}}));
    }),
  });
  const controller = new AbortController();
  const sources = Array.from({length: 40}, (_, index) => contextualReferenceSource(base,
    {fields: {}, row: {region: {kind: 'STRING', value: String(index)}}}, async (keyword, page, size, signal) => {
      assert.equal(signal, controller.signal);
      return {page, size, total: 61, records: [{id: String(index), label: keyword, version: 1, values: {}}]};
    }));
  assert.equal(new Set(sources.map(source => source.key)).size, 40);
  assert(sources.every(source => source.resolve === base.resolve));
  const resolved = await Promise.all(sources.map((source, index) => source.resolve([String(index)], controller.signal)));
  assert.equal(calls.length, 1);
  assert.equal(calls[0].length, 40);
  assert.equal(resolved[39][0].value, '39');
  const page = await sources[39].search('东京', 1, 50, controller.signal, {});
  assert.equal(page.content[0].id, '39');
  assert.deepEqual(page.page, {number: 1, size: 50, totalElements: 61, totalPages: 2});
});
