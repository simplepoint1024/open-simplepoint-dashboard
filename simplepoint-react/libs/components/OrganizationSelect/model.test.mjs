import {test} from 'node:test';
import assert from 'node:assert/strict';
import {organizationId, organizationLabel, organizationPage, mergeOptionWindow, optionRows, putOptionWindow,
  organizationProjection, branchChannel, rootChannel, ORGANIZATION_BRANCHES, ORGANIZATION_WINDOW_PAGES} from './model.ts';
const row = id => ({id, name: 'Name ' + id, enabled: true, hasChildren: true});
const page = number => ({number, size: 1, hasNext: true, content: [row(String(number))]});

test('organization identities and protected Boolean/display fields never become writable mask values', () => {
  for (const value of ['', ' ', '***', ' **** ', 0, null, undefined]) assert.equal(organizationId(value), undefined);
  assert.equal(organizationId('literal-id'), 'literal-id');
  assert.equal(organizationLabel({...row('a'), name: '***', code: 'HQ'}), 'HQ');
  assert.equal(organizationLabel({...row('a'), name: '***', code: '***'}), 'a');
  const result = organizationPage({number: 0, size: 3, hasNext: false, content: [row('***'), {...row('a'), enabled: '***', hasChildren: 'false'}]}, 0);
  assert.equal(result.content.length, 1); assert.equal(result.content[0].enabled, false); assert.equal(result.content[0].hasChildren, false);
});
test('organization slices reject stale metadata, unbounded responses and ambiguous continuation values', () => {
  for (const raw of [undefined, {...page(1)}, {...page(0), size: 101}, {...page(0), size: 0},
    {...page(0), hasNext: 'true'}, {...page(0), content: [row('a'), row('b')]}]) assert.throws(() => organizationPage(raw, 0));
  assert.deepEqual(organizationPage(page(0), 0), {...page(0), content: [{...row('0'), code: ''}]});
});
test('windowed browsing can move forward beyond 500 and back without materializing the whole directory', () => {
  let window;
  for (let number = 0; number <= 520; number++) window = mergeOptionWindow(window, page(number), number ? 'next' : 'replace');
  assert.equal(window.pages.length, ORGANIZATION_WINDOW_PAGES); assert.equal(window.pages[0].number, 511);
  assert.equal(optionRows(window).at(-1).id, '520');
  window = mergeOptionWindow(window, page(510), 'previous');
  assert.equal(window.pages[0].number, 510); assert.equal(window.pages.at(-1).number, 519);
  assert.equal(window.pages.length, ORGANIZATION_WINDOW_PAGES);
});
test('branch cache is bounded and recently used branches are not evicted before older ones', () => {
  let cache = new Map([[rootChannel, {pages: [page(0)]}]]);
  for (let index = 0; index < ORGANIZATION_BRANCHES; index++) cache = putOptionWindow(cache, branchChannel(String(index)), {pages: [page(index)]}).cache;
  cache = putOptionWindow(cache, branchChannel('0'), cache.get(branchChannel('0'))).cache;
  const result = putOptionWindow(cache, branchChannel('new'), {pages: [page(0)]});
  assert.deepEqual(result.evicted, [branchChannel('1')]); assert.equal(result.cache.size, ORGANIZATION_BRANCHES + 1);
  assert.equal(result.cache.has(rootChannel), true); assert.equal(result.cache.has(branchChannel('0')), true);
});
test('projection only follows actual reachable branch pages and stops cycles/duplicate nodes', () => {
  const cache = new Map([[rootChannel, {pages: [{...page(0), content: [row('a')]}]}],
    [branchChannel('a'), {pages: [{...page(1), content: [row('b')]}]}],
    [branchChannel('b'), {pages: [{...page(2), content: [row('a')]}]}],
    [branchChannel('orphan'), {pages: [page(99)]}]]);
  const projection = organizationProjection(cache);
  assert.equal(projection.length, 1); assert.equal(projection[0].children[0].option.id, 'b');
  assert.deepEqual(projection[0].children[0].children, []);
});
