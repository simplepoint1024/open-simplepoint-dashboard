import assert from 'node:assert/strict';
import test from 'node:test';
import {relationTargetDirectory, relationTargetIdentity} from './catalog.ts';

const target = id => ({applicationId: 'customers', formId: 'customer', accessResourceId: id});
const row = id => ({target: target(id), pageId: 'page', applicationName: '客户管理', pageName: '客户', formName: 'Customer',
  pageNameLocalization: {defaultLocale: 'zh-CN', defaultValue: '客户', translations: {'en-US': 'Customers'}}});

test('selection maps server paging and localized names while preserving exact relation identity', async () => {
  const signal = new AbortController().signal;
  const directory = relationTargetDirectory('scope/source', '/directory', 'en-US', {
    search: async (...args) => {assert.deepEqual(args, ['日本', 1, 50, signal]); return {page: 1, size: 50, total: 61, records: [row('resource')]};},
    resolve: async () => [], describe: async () => {throw new Error('unused');},
  });
  const page = await directory.source.search('日本', 1, 50, signal, {});
  assert.deepEqual(page.page, {number: 1, size: 50, totalElements: 61, totalPages: 2});
  assert.deepEqual(directory.source.option(page.content[0]), {value: 'resource', label: '客户管理 / Customers', metadata: {target: target('resource'), pageId: 'page'}});
  assert.equal(directory.source.option({...page.content[0], target: {applicationId: '../foreign'}}), undefined);
  assert.equal(relationTargetIdentity('Customers'), undefined);
});

test('exact hydration is bounded, deduplicated, ordered and omits unavailable or unrequested targets', async () => {
  const calls = [];
  const directory = relationTargetDirectory('scope/source', '/directory', 'zh-CN', {
    search: async () => {throw new Error('Hydration cannot search');}, describe: async () => {throw new Error('unused');},
    resolve: async ids => {calls.push(ids); return [row(ids.at(-1)), row(ids[0]), row('unrequested')];},
  });
  const ids = Array.from({length: 201}, (_, index) => `resource-${index}`);
  const options = await directory.source.resolve([...ids, ids[0]], new AbortController().signal);
  assert.deepEqual(calls.map(ids => ids.length), [100, 100, 1]);
  assert.deepEqual(options.map(option => option.value), ['resource-0', 'resource-99', 'resource-100', 'resource-199', 'resource-200']);
});

test('cancelled metadata and hydration cannot finish an obsolete designer selection', async () => {
  for (const operation of ['describe', 'resolve']) {
    const controller = new AbortController();
    const transport = {search: async () => {throw new Error('unused');},
      resolve: async () => {controller.abort(); return [row('resource')];},
      describe: async () => {controller.abort(); return {target: target('resource'), pageId: 'page', formVersion: 1, fields: []};}};
    const directory = relationTargetDirectory('scope/source', '/directory', 'zh-CN', transport);
    await assert.rejects(operation === 'describe' ? directory.describe(target('resource'), controller.signal)
      : directory.source.resolve(['resource'], controller.signal), {name: 'AbortError'});
  }
});

test('changed target identities never become the old selection metadata', async () => {
  const directory = relationTargetDirectory('scope/source', '/directory', 'zh-CN', {
    search: async () => {throw new Error('unused');}, resolve: async () => [],
    describe: async () => ({target: {...target('resource'), formId: 'replacement'}, pageId: 'page', formVersion: 1, fields: []}),
  });
  await assert.rejects(directory.describe(target('resource'), new AbortController().signal), /identity mismatch/);
});
