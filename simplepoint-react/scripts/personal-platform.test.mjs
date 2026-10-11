import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {test} from 'node:test';

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');
test('historical personal items and messages retain the schema-driven SimpleTable lifecycle', () => {
  const table = read('../modules/simplepoint-common/src/views/personal/components/PersonalTable.tsx');
  assert.match(table, /import SimpleTable from '@simplepoint\/components\/SimpleTable'/);
  assert.doesNotMatch(table, /mode="controlled"/);
  assert.match(table, /loadPage=\{loadPage\}/);
  assert.match(table, /customButtonEvents=/);
  assert.match(table, /formSchemaTransform=/);
  for (const page of ['components/LegacyItems.tsx', 'components/Inbox.tsx']) {
    const source = read('../modules/simplepoint-common/src/views/personal/' + page);
    assert.match(source, /<PersonalTable</);
    assert.doesNotMatch(source, /import\s*\{[^}]*\bTable\b[^}]*\}\s*from\s*['"]antd['"]/);
  }
});

test('historical personal lists use header filters without duplicate toolbar query state', () => {
  for (const page of ['components/LegacyItems.tsx', 'components/Inbox.tsx']) {
    const source = read('../modules/simplepoint-common/src/views/personal/' + page);
    assert.doesNotMatch(source, /Input\.Search|sp-personal-filters|setKeyword|setCategory|setStatus|setPriority/);
    assert.doesNotMatch(source, /setPage\(|setFilters\(|PageHeading|<Button/);
    assert.match(source, /<Tabs activeKey=\{view\}/);
  }
});

test('primary personal routes own workflow handling and retain only explicit legacy entry points', () => {
  const todos = read('../modules/simplepoint-common/src/views/personal/Todos/index.tsx');
  const items = read('../modules/simplepoint-common/src/views/personal/Items/index.tsx');
  assert.match(todos, /WorkflowRuntime\/Inbox/);
  assert.match(todos, /<WorkflowInbox\/>/);
  assert.match(todos, /<InboxDetail kind="todos" id=\{legacyId\} readOnly/);
  assert.match(items, /WorkflowRuntime\/Matters/);
  assert.match(items, /!params.has\('item'\) && params.get\('legacy'\) !== '1'/);
  for (const page of ['Inbox', 'Matters']) {
    const source = read(`../libs/components/WorkflowRuntime/${page}.tsx`);
    assert.match(source, /useRemoteList</);
    assert.match(source, /<DataTable/);
    assert.doesNotMatch(source, /personalApi|<PersonalTable|Input\.Search/);
  }
});

test('my workspace is ordered immediately after home across all declared root menus', () => {
  const common = JSON.parse(read('../../simplepoint-services/simplepoint-service-common/src/main/resources/META-INF/simplepoint/resources/common.json'));
  const home = common.resources.find(route => route.code === 'dashboard.view');
  const mine = common.resources.find(route => route.code === 'personal.view');
  assert.equal(home.sort, 0);
  assert.equal(mine.sort, 1);
  assert.deepEqual(mine.children.map(route => route.path), ['/my/items', '/my/todos', '/my/messages']);
  for (const service of ['common', 'dna', 'ai', 'auditing']) {
    const declaration = JSON.parse(read(`../../simplepoint-services/simplepoint-service-${service}/src/main/resources/META-INF/simplepoint/resources/${service}.json`));
    for (const route of declaration.resources.filter(route => !['dashboard.view', 'personal.view'].includes(route.code))) {
      assert.ok(route.sort > mine.sort, route.code);
    }
  }
});

test('all personal schema buttons are registered action resources with unique codes', () => {
  const common = JSON.parse(read('../../simplepoint-services/simplepoint-service-common/src/main/resources/META-INF/simplepoint/resources/common.json'));
  const declarations = read('../../simplepoint-plugins/simplepoint-plugin-personal/simplepoint-plugin-personal-api/src/main/java/org/simplepoint/plugin/personal/api/PersonalTableModels.java');
  const codes = [];
  const visit = resource => {codes.push(resource.code); resource.children?.forEach(visit);};
  common.resources.forEach(visit);
  assert.equal(new Set(codes).size, codes.length);
  const pages = common.resources.find(resource => resource.code === 'personal.view').children;
  const actions = pages.flatMap(page => page.children);
  for (const [, authority] of declarations.matchAll(/authority = "([^"]+)"/g)) {
    const action = actions.find(resource => resource.code === authority);
    assert.equal(action?.type, 'ACTION', authority);
    assert.equal(action.publicAccess, true);
    assert.equal(action.grantable, false);
    assert.equal(action.routeKind, undefined, 'actions are not duplicate menu entries');
  }
});
