import test from 'node:test';
import assert from 'node:assert/strict';
import {canImport, catalogLink, catalogParameters} from './model.ts';
test('catalog parameters preserve literal search and use bounded server paging, not incomplete local filters', () => {
  assert.deepEqual(catalogParameters(26, 20, ' Actual%_! ', 'INTERNAL', 'MCP_SERVER'),
    {page: 26, size: 20, query: 'Actual%_!', source: 'INTERNAL', kind: 'MCP_SERVER'});
  assert.deepEqual(catalogParameters(0, 20, ' '), {page: 0, size: 20});
  for (const [page, size, query] of [[-1, 20, ''], [0, 101, ''], [0, 0, ''], [0.5, 20, ''], [2147483647, 100, ''], [0, 20, 'a'.repeat(201)]]) {
    assert.throws(() => catalogParameters(page, size, query));
  }
});
test('format support is not authorization and remote/runtime imports cannot be confused', () => {
  const item = {id: 'actual', source: 'OFFICIAL_MCP', status: 'ACTIVE', importable: true, remoteImportable: false, runtimeImportable: true};
  assert.equal(canImport(item, 'remote', {importRemote: true}), false);
  assert.equal(canImport(item, 'runtime', {importRuntime: true}), true);
  assert.equal(canImport(item, 'runtime'), false);
  assert.equal(canImport({...item, source: 'INTERNAL'}, 'runtime', {importRuntime: true}), false);
  assert.equal(canImport({...item, status: 'DELETED'}, 'runtime', {importRuntime: true}), false);
  assert.equal(canImport({...item, id: '***'}, 'runtime', {importRuntime: true}), false);
});
test('untrusted registry links cannot execute scripts or expose URL credentials', () => {
  assert.equal(catalogLink('https://example.com/project'), 'https://example.com/project');
  for (const link of ['javascript:alert(1)', 'data:text/html,unsafe', 'http://example.com', 'https://user:password@example.com', 'not-url']) assert.equal(catalogLink(link), undefined);
});
