import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {scopeTableSchema, scopeFormSchema, scopeFormUiSchema, prepareScopeData} from './model.ts';
import {effectiveScope} from './effectiveModel.ts';
const contract = name => JSON.parse(readFileSync(new URL('../../../../../../scripts/data-scope-contracts/' + name + '.json', import.meta.url), 'utf8'));
const pages = [{const: 'organizations.view', title: '组织管理'}];

test('conditional rule inputs extend the real schema without reconstructing hidden fields', () => {
  const original = contract('schema').schema, source = structuredClone(original);
  delete source.properties.expression;
  const table = scopeTableSchema(source, String), form = scopeFormSchema(table, {type: 'EXPRESSION', resourceCode: pages[0].const}, pages);
  assert.equal(source.properties.resourceCode.oneOf, undefined);
  assert.equal(form.dependencies.type.oneOf[1].properties.expression, undefined);
  assert.deepEqual(form.dependencies.type.oneOf[1].required, ['resourceCode']);
  assert.equal(table.properties.customDeptIds['x-ui']['x-list-visible'], false);
  assert.ok(form.required.includes('name') && form.required.includes('type'));
});
test('masked discriminator keeps readable dependencies and uses actual readonly text widgets', () => {
  const source = contract('protected-schema').schema;
  const draft = {type: '***', name: '真实表达式策略', expression: 'name = "可见组织"', resourceCode: 'organizations.view'};
  const form = scopeFormSchema(source, draft, pages), ui = scopeFormUiSchema(draft, form);
  assert.equal(form.properties.type.type, 'string'); assert.equal(form.properties.type.const, '***');
  assert.equal(form.properties.expression.readOnly, true); assert.equal(ui.expression['ui:widget'], 'textarea');
  assert.ok(!form.required.includes('name') && !form.required.includes('type'));
  assert.strictEqual(prepareScopeData(draft), draft, 'an unreadable type never clears expression or organizations');
});
test('protected dependencies freeze discriminators; missing catalogs preserve current target without a substitute', () => {
  const source = contract('schema').schema;
  source.properties.expression['x-field-access'] = 'VISIBLE'; source.properties.expression.readOnly = true;
  const form = scopeFormSchema(source, {type: 'EXPRESSION', resourceCode: 'unavailable.view'}, pages);
  assert.equal(form.properties.type.readOnly, true);
  assert.equal(form.dependencies.type.oneOf[1].properties.resourceCode.readOnly, true);
  assert.equal(form.dependencies.type.oneOf[1].properties.resourceCode.oneOf, undefined);
  assert.deepEqual(form.dependencies.type.oneOf[1].required, []);
});
test('native custom selection constraints and widget do not depend on an already populated draft', () => {
  const form = scopeFormSchema(contract('schema').schema, null, pages);
  const branch = form.dependencies.type.oneOf[0];
  assert.equal(branch.properties.customDeptIds.maxItems, 2000);
  assert.equal(branch.properties.customDeptIds.minItems, 1);
  assert.equal(scopeFormUiSchema({type: 'CUSTOM'}, form).customDeptIds['ui:widget'], 'OrgTreeMultiSelect');
  assert.deepEqual(prepareScopeData({type: 'SELF', resourceCode: 'old', expression: 'old', customDeptIds: ['old']}),
    {type: 'SELF', resourceCode: null, expression: null, customDeptIds: []});
});
test('effective preview accepts genuine contract and rejects truthy flags, unknown policy and malformed field maps', () => {
  const value = contract('effective'); assert.ok(effectiveScope(value));
  for (const patch of [{includeSelf: 'false'}, {dataScopeType: 'UNKNOWN'}, {resourceFieldPermissions: {page: null}},
    {resourceDataScopes: {page: {type: 'CUSTOM', includeSelf: 'true'}}}, {deptIds: [true]}, {fieldPermissions: {field: 'WRITE'}}])
    assert.equal(effectiveScope({...value, ...patch}), undefined);
  assert.equal(effectiveScope({}), undefined);
  assert.deepEqual(effectiveScope({...value, resourceDataScopes: null}).resourceDataScopes, {});
});
