import test from 'node:test';
import assert from 'node:assert/strict';
import {compileLocalQuery, localQuerySchema, queryLocalRows} from './localQuery.ts';
import {encodeDocument} from '../Table/query.ts';

const fields = {name: {type: 'string'}, amount: {type: 'number'}, enabled: {type: 'boolean'}, at: {type: 'date-time'}};
const rows = [{id: 1, name: 'Home a,b:%_', amount: '9007199254740993.01', enabled: true, at: '2026-09-19T00:00:00.000000001Z'},
  {id: 2, name: 'Other', amount: '9007199254740993.02', enabled: false, at: '2026-09-19T08:00:00.000000002+08:00'},
  {id: 3, name: null, amount: null, enabled: null, at: null}];
const run = (node, sort) => queryLocalRows(rows, fields, {filters: {$query: encodeDocument(node)}, sort}).map(r => r.id);

test('local snapshots support every scalar operator, numeric precision and SQL null semantics', () => {
  const checks = [
    ['equals', '9007199254740993.01', [1]], ['not:equals', '9007199254740993.01', [2]],
    ['in', ['9007199254740993.02'], [2]], ['not:in', ['9007199254740993.02'], [1]],
    ['between', ['9007199254740993.01', '9007199254740993.02'], [1, 2]],
    ['not:between', ['9007199254740993.01', '9007199254740993.01'], [2]],
    ['than:greater', '9007199254740993.01', [2]], ['than:less', '9007199254740993.02', [1]],
    ['than:equal:greater', '9007199254740993.02', [2]], ['than:equal:less', '9007199254740993.01', [1]],
    ['is:null', undefined, [3]], ['is:not:null', undefined, [1, 2]],
  ];
  for (const [op, value, expected] of checks) assert.deepEqual(run({field: 'amount', op, value}), expected, op);
  assert.deepEqual(run({field: 'name', op: 'like', value: 'a,B:%_'}), [1]);
  assert.deepEqual(run({field: 'name', op: 'not:like', value: 'Home'}), [2]);
  assert.deepEqual(run({op: 'not', children: [{field: 'name', op: 'like', value: 'Home'}]}), [2]);
});

test('AND/OR/NOT and independent header predicates compose without mutating the snapshot', () => {
  const before = structuredClone(rows);
  assert.deepEqual(run({op: 'or', children: [{field: 'name', op: 'is:null'},
    {op: 'and', children: [{field: 'enabled', op: 'equals', value: true}, {field: 'name', op: 'like', value: 'home'}]}]}), [1, 3]);
  assert.deepEqual(queryLocalRows(rows, fields, {filters: {
    name: encodeDocument({field: 'name', op: 'in', value: ['Home a,b:%_', 'Other']}),
    $query: encodeDocument({field: 'enabled', op: 'equals', value: false}),
  }}).map(r => r.id), [2]);
  assert.deepEqual(rows, before);
});

test('sorting uses nulls last in both directions, up to three fields, and stable ties', () => {
  assert.deepEqual(queryLocalRows(rows, fields, {filters: {}, sort: ['amount,desc', 'name,asc']}).map(r => r.id), [2, 1, 3]);
  assert.deepEqual(queryLocalRows(rows, fields, {filters: {}, sort: 'amount,asc'}).map(r => r.id), [1, 2, 3]);
  const ties = [{name: 'b', amount: '1.00'}, {name: 'a', amount: '1e0'}];
  assert.deepEqual(queryLocalRows(ties, fields, {filters: {}, sort: 'amount,asc'}), ties);
  assert.deepEqual(queryLocalRows(ties, fields, {filters: {}, sort: ['amount,asc', 'name,asc']}), [...ties].reverse());
});

test('local filtering runs across all rows before pagination and supports trusted nested mappings', () => {
  const all = Array.from({length: 101}, (_, i) => ({values: [i, `row-${i}`]}));
  const definitions = {column0: {type: 'integer', path: ['values', 0]}, column1: {type: 'string', path: ['values', 1]}};
  const result = queryLocalRows(all, definitions, {filters: {$query: encodeDocument({field: 'column0', op: 'than:greater', value: 95})}, sort: 'column0,desc'});
  assert.equal(result.length, 5);
  assert.equal(result.slice(0, 2)[0].values[0], 100);
  assert.throws(() => compileLocalQuery(definitions, {filters: {$query: encodeDocument({field: 'values.0', op: 'equals', value: 1})}}));
});

test('date-time offsets and nanoseconds compare exactly, including dates before epoch', () => {
  assert.deepEqual(run({field: 'at', op: 'equals', value: '2026-09-19T08:00:00.000000001+08:00'}), [1]);
  assert.deepEqual(run({field: 'at', op: 'than:greater', value: '2026-09-19T00:00:00.000000001Z'}), [2]);
  const times = [{at: '1969-12-31T23:59:59.900Z'}, {at: '1970-01-01T00:00:00Z'}, {at: '1968-01-01T00:00:00Z'}];
  assert.deepEqual(queryLocalRows(times, fields, {filters: {}, sort: 'at,asc'}), [times[2], times[0], times[1]]);
});

test('empty strings remain distinct from NULL and inherited values are not readable', () => {
  const values = [{name: ''}, {name: null}, Object.create({name: 'secret'})];
  const result = queryLocalRows(values, fields, {filters: {name: encodeDocument({field: 'name', op: 'equals', value: ''})}});
  assert.deepEqual(result, [values[0]]);
});

test('invalid views and malformed typed values fail before rows are evaluated', () => {
  for (const node of [{field: 'amount', op: 'between', value: [2, 1]}, {field: 'at', op: 'equals', value: '2026-02-30T00:00:00Z'},
    {field: 'at', op: 'equals', value: '2026-09-19'}, {field: 'enabled', op: 'like', value: 'true'},
    {field: 'enabled', op: 'equals', value: 'false'}, {field: 'secret', op: 'equals', value: 'x'}]) {
    assert.throws(() => compileLocalQuery(fields, {filters: {$query: encodeDocument(node)}}));
  }
  assert.throws(() => localQuerySchema({'bad.field': {type: 'string'}}));
  assert.throws(() => compileLocalQuery(fields, {filters: {}, sort: ['name,asc', 'name,desc']}));
});
