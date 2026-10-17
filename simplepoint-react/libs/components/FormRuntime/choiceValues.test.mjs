import assert from 'node:assert/strict';
import test from 'node:test';
import {choiceValue} from './choiceValues.ts';
import {runtimeFormModel} from './model.ts';

test('choice transport retains scalar types and all integer/decimal digits', () => {
  assert.equal(choiceValue('BOOLEAN', 'false'), false);
  assert.equal(choiceValue('INT32', '12.00'), 12);
  assert.equal(choiceValue('INT64', '9223372036854775807'), '9223372036854775807');
  assert.equal(choiceValue('DECIMAL', '9999999999999999999.123456780'), '9999999999999999999.12345678');
  assert.equal(choiceValue('DECIMAL', '-0.000'), '0');
  assert.equal(choiceValue('DECIMAL', '1e-3'), '0.001');
  assert.equal(choiceValue('STRING', '日本語'), '日本語');
  assert.throws(() => choiceValue('BOOLEAN', 'yes'));
  assert.throws(() => choiceValue('INT32', '2147483648'));
  assert.throws(() => choiceValue('INT64', '9223372036854775808'));
  assert.throws(() => choiceValue('INT64', '1.2'));
  assert.throws(() => choiceValue('DECIMAL', '1e999999999'));
});

test('numeric and boolean choices compile to usable platform select schemas', () => {
  for (const [type, value, expected] of [['INT32', '12', 12], ['BOOLEAN', 'false', false],
    ['INT64', '9223372036854775807', '9223372036854775807'], ['DECIMAL', '1.200', '1.2']]) {
    const field = {fieldId: 'value', label: 'Value', type, choices: [{value, label: 'Choice'}],
      defaultValue: {kind: type === 'BOOLEAN' ? 'BOOLEAN' : type === 'DECIMAL' ? 'DECIMAL' : 'INTEGER', value}};
    const control = {componentId: 'control', componentType: 'field.select', componentVersion: '1',
      fieldId: 'value', children: [], properties: {}, columnSpan: 24, readOnly: false};
    const root = {...control, componentId: 'root', componentType: 'layout.form', fieldId: null, children: [control]};
    const model = runtimeFormModel({contractVersion: '1', fields: [field], rules: [],
      scenes: [{sceneId: 'main', type: 'MAIN', root}]}, 'main', {});
    assert.equal(model.schema.properties.value.oneOf[0].const, expected);
    assert.equal(model.schema.properties.value.default, expected);
    assert.deepEqual(model.schema.properties.value.type, [typeof expected === 'number' ? 'integer' : typeof expected, 'null']);
    assert.deepEqual(model.schema.properties.value.oneOf.at(-1), {const: null, title: ''});
    assert.equal(model.uiSchema.value['ui:widget'], 'select');
  }
});
