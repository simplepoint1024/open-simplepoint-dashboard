import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {variableSchema} from '../../WorkflowForms/model.ts';

test('all workflow identity defaults and runtime schemas use the same public domain widgets', () => {
  for (const [type, widget, multiple] of [['user', 'UserSelect', false], ['users', 'UserSelect', true],
    ['role', 'RoleSelect', false], ['roles', 'RoleSelect', true]]) {
    const schema = variableSchema({name: 'identity', label: 'Identity', type, scope: 'both'});
    assert.equal(schema['x-ui'].widget, widget);
    assert.equal(schema.type, multiple ? 'array' : 'string');
    if (multiple) {assert.equal(schema.maxItems, 50); assert.equal(schema.uniqueItems, true);}
  }
});

test('workflow designer and condition identity editors delegate to shared domain components', () => {
  const designer = readFileSync(new URL('../../WorkflowDesigner/index.tsx', import.meta.url), 'utf8');
  const conditions = readFileSync(new URL('../../ExpressionEditor/ConditionExpression.tsx', import.meta.url), 'utf8');
  assert.equal((designer.match(/<UserSelect\b/g) ?? []).length, 2);
  assert.equal((designer.match(/<RoleSelect\b/g) ?? []).length, 1);
  for (const source of [designer, conditions]) {
    assert.ok(source.includes("from '../UserSelect'")); assert.ok(source.includes("from '../RoleSelect'"));
    assert.equal(source.includes('TenantDirectorySelect'), false);
  }
  const compatibility = readFileSync(new URL('./TenantDirectoryWidget.tsx', import.meta.url), 'utf8');
  assert.ok(compatibility.includes('DomainSelectWidget'));
});
