import assert from 'node:assert/strict';
import {test} from 'node:test';
import {displayOnlyProjection, displaySelection, displayValue} from './displayOnly.ts';
import {fieldSchema} from '../FormRuntime/fieldSchema.ts';

test('read-only reference metadata survives projection while masked fields cannot enable directory reads', () => {
  const reference = {relationId: 'customer', allowDrillThrough: true};
  const source = {type: 'string', format: 'uuid', 'x-display-only': true, 'x-form-reference': reference};
  const projected = displayOnlyProjection(source);
  assert.deepEqual(projected.schema['x-form-reference'], reference);
  assert.equal(projected.uiSchema['ui:field'], 'DisplayValue');
  assert.equal(projected.schema.format, undefined, 'historical/masked values are not UUID inputs to validate');
  assert.equal(displayOnlyProjection({...source, 'x-field-access': 'MASKED'}).schema['x-form-reference'], undefined);
  const historical = {id: 'id', label: 'Original name'};
  assert.deepEqual(displayOnlyProjection({...source, 'x-reference-label': historical}).schema['x-reference-label'], historical);
});

test('explicit display values preserve data and bypass input constraints without weakening writable fields', () => {
  const source = {type: 'object', required: ['masked', 'editable'], properties: {
    masked: {type: 'integer', title: 'Secret', 'x-display-only': true, minimum: 0, pattern: '^\\d+$',
      'x-decimal': {scale: 0}, oneOf: [{const: 1, title: 'One'}]},
    editable: {type: 'string', minLength: 3},
    ordinary: {type: 'string', readOnly: true, minLength: 5},
  }};
  const original = structuredClone(source);
  const result = displayOnlyProjection(source, {masked: {'ui:widget': 'DateWidget'}, 'ui:order': ['masked', 'editable', '*']});
  assert.deepEqual(source, original);
  assert.deepEqual(result.schema.required, ['editable']);
  assert.deepEqual(result.schema.properties.editable, source.properties.editable);
  assert.deepEqual(result.schema.properties.ordinary, source.properties.ordinary, 'readOnly alone is not opt-in');
  for (const key of ['minimum', 'pattern', 'x-decimal', 'oneOf']) assert.equal(result.schema.properties.masked[key], undefined);
  assert.equal(result.uiSchema.masked['ui:field'], 'DisplayValue');
  assert.equal(displayValue('***', result.schema.properties.masked['x-display-choices']), '***');
  assert.equal(displayValue(1, result.schema.properties.masked['x-display-choices']), 'One');
  assert.equal(displayValue('retired', result.schema.properties.masked['x-display-choices']), 'retired');
  assert.equal(displayValue(false), 'false');
  assert.equal(displayValue('9223372036854775807'), '9223372036854775807');
  assert.equal(displayValue(null), '');
});

test('inline object and array projections preserve editable required fields and leave source schemas intact', () => {
  const source = {type: 'object', properties: {lines: {type: 'array', items: {
    type: 'object', required: ['name', 'previous'], properties: {
      name: {type: 'string', minLength: 1}, previous: {type: 'object', 'x-display-only': true, properties: {secret: {type: 'integer'}}},
    },
  }}}};
  const projected = displayOnlyProjection(source);
  assert.deepEqual(projected.schema.properties.lines.items.required, ['name']);
  assert.equal(projected.uiSchema.lines.items.previous['ui:field'], 'DisplayValue');
  assert.equal(projected.schema.properties.lines.items.properties.previous.properties, undefined);
  assert.deepEqual(source.properties.lines.items.required, ['name', 'previous']);
});

test('published selection labels survive read-only projection without querying identities or changing stored keys', () => {
  const options = [{value: 'cn', label: '中国', children: [{value: 'jp', label: '日本語😀'}]}, {value: 'other', label: '其他'}];
  const field = {type: 'JSON', label: '地区', choices: []};
  for (const [componentType, expected] of [['field.cascader', '中国 / 日本語😀'], ['field.transfer', '中国、jp']]) {
    const node = {componentType, properties: {options: {kind: 'JSON', value: JSON.stringify(options)}}};
    const source = {...fieldSchema(field, node), 'x-display-only': true};
    const before = structuredClone(source);
    const projection = displayOnlyProjection(source).schema['x-display-selection'];
    assert.equal(displaySelection('["cn","jp"]', projection), expected);
    assert.equal(displaySelection('["missing","jp"]', projection), 'missing' + (componentType === 'field.cascader' ? ' / jp' : '、jp'));
    assert.equal(displaySelection('[]', projection), '');
    for (const masked of ['***', 'null', '{"key":"cn"}', '[1]', '["cn",null]']) {
      assert.equal(displaySelection(masked, projection), undefined);
      assert.equal(displayValue(masked), masked);
    }
    assert.equal(displaySelection(JSON.stringify(Array(201).fill('cn')), projection), undefined);
    assert.deepEqual(source, before);
  }
});
