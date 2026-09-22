import assert from 'node:assert/strict';
import test from 'node:test';
import {defaultColumnOperator, parseColumnFilter} from './filters.ts';

test('filter reopening preserves compound operators and colon-containing values', () => {
  for (const op of ['than:equal:greater', 'than:equal:less', 'than:greater', 'than:less',
    'not:between', 'not:equals', 'not:like', 'not:in', 'between', 'equals', 'like', 'in']) {
    const text = '2026-09-18T10:30:00Z,2026-09-19T20:40:00Z';
    assert.deepEqual(parseColumnFilter(`${op}:${text}`), {op, text});
  }
  for (const op of ['is:null', 'is:not:null']) assert.deepEqual(parseColumnFilter(op), {op, text: ''});
  assert.deepEqual(parseColumnFilter(), {text: ''});
  assert.deepEqual(parseColumnFilter('unknown:value'), {text: ''});
});

test('number and date columns default to equality, not unsupported text matching', () => {
  for (const type of ['number', 'integer', 'date', 'boolean']) assert.equal(defaultColumnOperator(type), 'equals');
  for (const type of ['string', 'unknown']) assert.equal(defaultColumnOperator(type), 'like');
});
