import test from 'node:test';
import assert from 'node:assert/strict';
import {dateFormValue} from './dateValue.ts';
test('empty date values follow the schema nullability without replacing actual dates', () => {
  for (const value of [null, undefined, '']) {
    assert.equal(dateFormValue({type: 'string', format: 'date'}, value), undefined);
    assert.equal(dateFormValue({type: ['string', 'null'], format: 'date-time'}, value), null);
  }
  for (const value of ['1990-01-02', '1990-01-02T12:30:41.123Z']) assert.equal(dateFormValue({type: 'string'}, value), value);
});
