import test from 'node:test';
import assert from 'node:assert/strict';
import validator from '@rjsf/validator-ajv8';
import {getDefaultFormState} from '@rjsf/utils';
import {metadataFormSchema, metadataPayload, validateMetadataDraft} from './forms.ts';
const t = (_key, fallback) => fallback;
const path = [{type: 'SCHEMA', name: 'public'}, {type: 'TABLE', name: 'table,quoted'}];
const validates = (kind, value) => !(validator.rawValidation(metadataFormSchema(kind, t, ['SCHEMA'], ['a,b', 'id']), value).errors?.length);

test('CHECK requires an expression, not a fake local column list', () => {
  assert.equal(validates('constraint', {type: 'CHECK', checkExpression: 'amount >= 0'}), true);
  assert.equal(validates('constraint', {type: 'CHECK'}), false);
  assert.equal(validates('constraint', {type: 'UNIQUE', columns: []}), false);
});
test('RJSF default generation never invents required foreign-key fields for CHECK', () => {
  const schema = metadataFormSchema('constraint', t, [], ['id']);
  const generated = getDefaultFormState(validator, schema, {type: 'CHECK', name: 'ck', columns: [null], checkExpression: 'id >= 0'},
    schema, false, {emptyObjectFields: 'skipEmptyDefaults', arrayMinItems: {populate: 'never'}});
  assert.equal(generated.reference, null);
  assert.equal(validates('constraint', generated), true);
  assert.deepEqual(metadataPayload({kind: 'constraint', path, data: {}}, generated).payload.constraint.columns, []);
});
test('column array values preserve identifiers containing commas', () => {
  const data = {type: 'UNIQUE', name: 'quoted', columns: ['a,b']};
  assert.equal(validates('constraint', data), true);
  const request = metadataPayload({kind: 'constraint', path, data: {}}, data);
  assert.deepEqual(request.payload.constraint.columns, ['a,b']);
  assert.deepEqual(request.payload.tablePath, path);
  assert.equal(validates('constraint', {...data, columns: ['a', 'b']}), false);
});
test('foreign keys require a typed table path and matching column counts', () => {
  const data = {type: 'FOREIGN_KEY', columns: ['id'], reference: {tablePath: path, columns: ['external,id']}};
  assert.equal(validates('constraint', data), true);
  assert.doesNotThrow(() => validateMetadataDraft('constraint', data));
  assert.throws(() => validateMetadataDraft('constraint', {...data, columns: ['id', 'a,b']}), /FOREIGN_KEY_ARITY/);
  assert.throws(() => validateMetadataDraft('constraint', {...data, reference: {...data.reference, tablePath: path.slice(0, 1)}}), /FOREIGN_KEY_TABLE/);
  assert.equal(validates('constraint', {type: 'FOREIGN_KEY', columns: ['id']}), false);
});
test('table definitions reject fake columns, duplicate names and invalid nested definitions', () => {
  assert.equal(validates('table', {name: 'actual', columns: []}), false);
  assert.equal(validates('table', {name: 'actual', columns: [{name: 'id', typeName: 'INTEGER'}]}), true);
  assert.equal(validates('table', {name: 'actual', columns: [{name: 'id'}]}), false);
  assert.throws(() => validateMetadataDraft('table', {columns: [{name: 'id'}, {name: 'id'}]}), /DUPLICATE_COLUMNS/);
  assert.throws(() => validateMetadataDraft('table', {columns: [{name: 'id'}], constraints: [{type: 'UNIQUE', columns: ['missing']}]}), /UNKNOWN_COLUMN/);
});
test('column precision is integral and scale cannot exceed it', () => {
  assert.equal(validates('columnAdd', {name: 'money', typeName: 'NUMERIC', size: 18, scale: 2}), true);
  assert.equal(validates('columnAdd', {name: 'money', typeName: 'NUMERIC', size: 1.5}), false);
  assert.throws(() => validateMetadataDraft('columnAdd', {size: 2, scale: 3}), /INVALID_SCALE/);
});
test('destructive requests default to no cascade and keep the captured target', () => {
  const target = {kind: 'drop', path, data: {}};
  assert.deepEqual(metadataPayload(target, {}).payload, {path, cascade: false});
  assert.equal(metadataPayload(target, {cascade: 'true'}).payload.cascade, false);
  assert.equal(metadataPayload(target, {cascade: true}).payload.cascade, true);
  const alter = metadataPayload({kind: 'columnAlter', path, currentName: 'a,b', data: {}}, {name: 'renamed', typeName: 'TEXT'});
  assert.equal(alter.payload.currentName, 'a,b');
  assert.equal(alter.suffix, 'columns/alter');
});
test('ordinary/advanced requests use the same lossless object, without synthetic JSON fields', () => {
  const data = {name: 'real_table', columns: [{name: 'a,b', typeName: 'TEXT', defaultValue: "''", remarks: ''}],
    constraints: [{type: 'CHECK', checkExpression: '1 = 1'}]};
  const original = structuredClone(data);
  const target = {kind: 'table', path: path.slice(0, 1), data: {}};
  assert.deepEqual(metadataPayload(target, data), metadataPayload(target, JSON.parse(JSON.stringify(data))));
  assert.deepEqual(data, original);
  assert.equal(validates('table', {...data, columnsJson: '[]'}), false);
  assert.equal(validates('view', {name: 'real_view', definitionSql: '   '}), false);
});
