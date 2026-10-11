import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {expressionLabels, permissionLabels} from './permissionLabels.ts';
import {createTranslator} from './translator.ts';
const words = {AND: '且', OR: '或', NOT: '非', IN: '属于', IS: '为', NULL: '空值', CONTAINS: '包含', TRUE: '是', FALSE: '否', UNKNOWN: '未知字段'};
const labels = expressionLabels({name: '名称', score: '分数', enabled: '启用'}, {'user.id': '当前用户'}, words);

for (const access of ['masked', 'hidden']) test(`actual ${access} HTTP presentation retains metadata without restoring protected source names`, () => {
  const read = kind => JSON.parse(readFileSync(new URL(`../../../scripts/data-scope-contracts/${access}-${kind}.json`, import.meta.url), 'utf8'));
  const presentation = read('presentation'), schema = read('schema'), page = read('list'), effective = read('effective');
  const display = permissionLabels(createTranslator({'permissions.unavailable': 'Unavailable', 'permissions.configuredRule': 'Configured rule'}), presentation);
  assert.equal(display.page('organizations.view'), access === 'masked' ? '***' : 'Unavailable');
  assert.equal(display.organizations(effective.deptIds), access === 'masked' ? '***' : 'Unavailable');
  assert.equal(display.rule('scope-policy', 'data'), access === 'masked' ? '***' : 'Configured rule');
  assert.equal(display.rule('field-rule', 'field'), access === 'masked' ? '***' : 'Configured rule');
  assert.equal(presentation.pages['organizations.view'].models.length, 1);
  assert.equal(page.totalElements, 1); assert.equal(page.content[0].id, 'scope-policy');
  assert.equal(page.content[0].name, access === 'masked' ? '***' : undefined);
  if (access === 'masked') assert.equal(schema.schema.properties.name.readOnly, true); else assert.equal(schema.schema.properties.name, undefined);
  assert.equal(schema.schema['x-query'].fields.name, undefined, 'Protected names cannot become query side channels');
  for (const source of ['验证用户', '验证工作空间', 'Private role', 'Private field rule', '已授权组织', '真实表达式策略', 'private@example.invalid']) assert.ok(!JSON.stringify(presentation).includes(source), source);
});

test('translated expressions preserve quoted literals, variables and scientific numbers', () => {
  const source = 'name = "AND name $user.id \\"是\\"" AND score >= 1e3 OR name = $user.id';
  const displayed = labels.encode(source);
  assert.match(displayed, /【名称】/);
  assert.match(displayed, /@【当前用户】/);
  assert.match(displayed, /1e3/);
  assert.equal(labels.decode(displayed).replace(/ +/g, ' '), source);
  assert.equal(labels.decode('【名称】="x"且【启用】=是'), 'name="x" AND enabled=true');
});
test('duplicate field names remain unambiguous and unresolved identifiers cannot be submitted', () => {
  const duplicate = expressionLabels({a: '名称', b: '名称'}, {}, words);
  assert.equal(duplicate.decode(duplicate.encode('a = b')), 'a = b');
  assert.throws(() => labels.decode('name="x"'));
  assert.throws(() => labels.decode(labels.encode('secretField = "x"')));
  assert.throws(() => labels.decode('【名称】="unfinished'));
});
test('labels resolve translations and never fall back to model names, keys or rule identifiers', () => {
  const t = createTranslator({'permissions.unavailable': 'Unavailable', 'permissions.unnamedField': 'Unnamed field',
    'permissions.fieldGroup': 'Fields {number}', 'permissions.configuredRule': 'Configured rule', 'people.name': 'Full name', 'people.type.INTERNAL': 'Internal member'});
  const display = permissionLabels(t, {pages: {'people.view': {title: 'i18n:missing.page', models: []}}, dataRules: {}, fieldRules: {}, organizations: {}});
  assert.equal(display.page('people.view'), 'Unavailable');
  assert.equal(display.field('nickname', {title: 'i18n:people.name'}), 'Full name');
  assert.equal(display.field('unknownField', {title: 'unknownField'}), 'Unnamed field');
  assert.equal(display.model('org.private.DomainModel', 0), 'Fields 1');
  assert.equal(display.rule('private-id', 'field'), 'Configured rule');
  assert.equal(display.option('INTERNAL', 'people.title.type', 0), 'Internal member');
  assert.equal(display.fieldKey('org.private.DomainModel#unknownField'), 'Unnamed field');
});
test('field-key indexes retain canonical and first legacy aliases, page isolation and new metadata snapshots', () => {
  let visits = 0;
  const model = (name, title) => ({model: name, get properties() {visits++; return {privateField: {title}};}});
  const data = {pages: {a: {title: 'A', models: [model('one.Shared', '首个字段'), model('two.Shared', '第二字段')]},
    b: {title: 'B', models: [model('three.Shared', '其他页面字段')]}}, dataRules: {}, fieldRules: {}, organizations: {}};
  const display = permissionLabels(createTranslator({}), data);
  assert.equal(display.fieldKey('Shared#privateField', 'a'), '首个字段');
  assert.equal(display.fieldKey('two.Shared#privateField', 'a'), '第二字段');
  assert.equal(display.fieldKey('Shared#privateField', 'b'), '其他页面字段');
  assert.equal(display.fieldKey('Shared#privateField'), '首个字段');
  for (let index = 0; index < 1000; index++) assert.equal(display.fieldKey('two.Shared#privateField', 'a'), '第二字段');
  assert.equal(visits, 1004, 'lookup reads the chosen model once per row, not every model for every row');
  const fresh = permissionLabels(createTranslator({}), {...data, pages: {a: {title: 'A', models: [model('one.Shared', '新会话字段')]}}});
  assert.equal(fresh.fieldKey('Shared#privateField', 'a'), '新会话字段');
});
