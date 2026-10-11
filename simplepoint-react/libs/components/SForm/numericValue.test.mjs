import assert from 'node:assert/strict';
import {test} from 'node:test';
import {numericInputSchema, numericFormValue} from './numericValue.ts';
test('numeric input does not clamp user intent; the original validation schema retains all bounds', () => {
  const schema = {type: 'integer', minimum: 2, maximum: 5, exclusiveMinimum: 1, exclusiveMaximum: 6, multipleOf: 1, title: '实际副本数'};
  const input = numericInputSchema(schema);
  assert.deepEqual(input, {type: 'integer', multipleOf: 1, title: '实际副本数'});
  assert.notStrictEqual(input, schema); assert.equal(schema.minimum, 2); assert.equal(schema.maximum, 5);
  assert.equal(numericFormValue(schema, 1), 1, 'invalid numeric values reach the schema validator unchanged');
});
test('text schemas retain their identity and numeric empty values follow actual nullability', () => {
  const text = {type: 'string', minimum: 2}; assert.strictEqual(numericInputSchema(text), text);
  assert.equal(numericFormValue({type: 'integer'}, null), undefined);
  assert.equal(numericFormValue({type: ['number', 'null']}, null), null);
  assert.equal(numericFormValue({type: 'number'}, 0), 0);
});
