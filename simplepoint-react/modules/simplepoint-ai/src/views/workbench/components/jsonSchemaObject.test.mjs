import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildJsonObjectSchema,
  hasInvalidSchemaProperties,
  parseJsonObjectSchema,
  schemaProperties,
} from './jsonSchemaObject.ts';

test('visual schema round trip preserves advanced property constraints', () => {
  const source = parseJsonObjectSchema(JSON.stringify({
    type: 'object',
    title: 'Request',
    properties: {
      request: {type: 'string', minLength: 2, description: 'Original'},
    },
    required: ['request'],
    additionalProperties: false,
  }));
  const properties = schemaProperties(source);
  properties[0].description = 'Updated';

  const result = parseJsonObjectSchema(buildJsonObjectSchema(
    source,
    properties,
    false,
  ));

  assert.equal(result?.title, 'Request');
  assert.deepEqual(result?.required, ['request']);
  assert.equal(result?.additionalProperties, false);
  assert.deepEqual(
    result?.properties.request,
    {type: 'string', minLength: 2, description: 'Updated'},
  );
});

test('visual schema rejects blank and duplicate field names', () => {
  const properties = schemaProperties(parseJsonObjectSchema(JSON.stringify({
    type: 'object',
    properties: {first: {type: 'string'}, second: {type: 'number'}},
  })));

  properties[1].name = 'first';
  assert.equal(hasInvalidSchemaProperties(properties), true);
  properties[1].name = '';
  assert.equal(hasInvalidSchemaProperties(properties), true);
  properties[1].name = 'second';
  assert.equal(hasInvalidSchemaProperties(properties), false);
});

test('invalid JSON and non-object JSON are not accepted as schemas', () => {
  assert.equal(parseJsonObjectSchema('{'), undefined);
  assert.equal(parseJsonObjectSchema('[]'), undefined);
  assert.equal(parseJsonObjectSchema('null'), undefined);
});
