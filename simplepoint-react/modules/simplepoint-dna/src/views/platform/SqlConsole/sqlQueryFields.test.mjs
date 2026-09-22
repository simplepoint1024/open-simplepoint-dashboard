import test from 'node:test';
import assert from 'node:assert/strict';
import {sqlQueryFields} from './sqlQueryFields.ts';

test('SQL result query aliases use JDBC types and explicit array paths, not user column names', () => {
  const fields = sqlQueryFields([{name: '1; unsafe alias', jdbcType: 4}, {name: 'total', typeName: 'DECIMAL(38,10)'},
    {name: 'enabled', typeName: 'BOOLEAN'}, {name: 'created', jdbcType: 93}, {name: 'instant', jdbcType: 2014},
    {name: 'label', typeName: 'VARCHAR'}, {name: 'object', jdbcType: 2000}]);
  assert.deepEqual(Object.values(fields).map(field => field.type), ['integer', 'number', 'boolean', 'local-date-time', 'date-time', 'string']);
  assert.deepEqual(fields.column0.path, ['values', 0]);
  assert.equal(fields.column0.columnKey, '1; unsafe alias-0');
  assert.equal(fields.column6, undefined);
  assert.deepEqual(sqlQueryFields([{name: 'empty without type'}, {name: 'binary', jdbcType: -2}]), {});
});
