import {test} from 'node:test';
import assert from 'node:assert/strict';
import {encodeDocument, readDocument, summarizeNode, transportFilters, validateNode, validateView} from './query.ts';
const contract = {version: 1, maxNodes: 32, maxDepth: 4, maxSort: 3, fields: {
  title: {operators: ['in', 'like', 'equals'], sortable: true}, amount: {operators: ['between', 'is:null'], sortable: true},
}};
test('structured arrays preserve commas, colons, booleans and repeated field predicates', () => {
  const node = {op: 'and', children: [{field: 'title', op: 'in', value: ['a,b', 'c:d']}, {field: 'title', op: 'like', value: 'x'}]};
  validateNode(node, contract);
  assert.deepEqual(readDocument(encodeDocument(node)), node);
  assert.equal(summarizeNode(node, {title: {title: 'Title'}}, op => op), '(Title in "a,b", "c:d" AND Title like "x")');
});

test('pagination and same-named column predicates have independent transport namespaces', () => {
  const advanced = {op: 'or', children: [{field: 'title', op: 'like', value: 'Ho'}, {field: 'title', op: 'equals', value: 'Other'}]};
  const column = {field: 'sort', op: 'equals', value: 3};
  const filters = {$query: encodeDocument(advanced), sort: encodeDocument(column), amount: 'is:null'};
  const wire = transportFilters(filters);
  assert.equal(wire.sort, undefined);
  assert.equal(wire.amount, 'is:null');
  assert.deepEqual(readDocument(wire.$query), {op: 'and', children: [advanced, column]});
  assert.equal(filters.sort, encodeDocument(column), 'does not mutate table state/saved views');
  assert.equal(({page: 0, size: 20, ...wire, sort: ['sort,desc', 'title,asc']}).sort[0], 'sort,desc');
  for (const field of ['page', 'size', 'sort']) {
    assert.deepEqual(readDocument(transportFilters({[field]: encodeDocument({...column, field})}).$query), {...column, field});
  }
});

test('AND merging does not add depth and combined transport budgets are validated', () => {
  const caps = {...contract, fields: {...contract.fields, sort: {operators: ['equals'], sortable: true, javaType: 'Integer'}}};
  const filters = {$query: encodeDocument({op: 'and', children: [{field: 'title', op: 'equals', value: 'Home'}]}), sort: 'equals:3'};
  assert.equal(readDocument(transportFilters(filters).$query).children.length, 2);
  validateView({name: 'sorted', filters, sort: 'sort,desc'}, caps);
  assert.throws(() => validateView({name: 'too many', filters, sort: 'sort,desc'}, {...caps, maxNodes: 2}));
  assert.throws(() => transportFilters({sort: encodeDocument({field: 'secret', op: 'equals', value: 1})}));
});
test('saved views reject removed fields, unauthorized operations and excessive sorting', () => {
  const view = {name: 'my view', filters: {$query: encodeDocument({field: 'title', op: 'equals', value: 'x'})}, sort: ['title,asc', 'amount,desc']};
  validateView(view, contract);
  assert.throws(() => validateView({...view, sort: ['title,asc', 'title,desc']}, contract));
  assert.throws(() => validateView({...view, sort: ['secret,asc']}, contract));
  assert.throws(() => validateView({...view, filters: {$query: encodeDocument({field: 'secret', op: 'equals', value: 'x'})}}, contract));
  assert.throws(() => validateNode({field: 'amount', op: 'between', value: [1]}, contract));
  assert.throws(() => validateNode({op: 'and', children: []}, contract));
});
