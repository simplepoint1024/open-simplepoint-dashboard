import assert from 'node:assert/strict';
import test from 'node:test';
import {jsonFieldIssue} from './jsonValue.ts';

test('platform JSON editor separates syntax, optional values and structural required values', () => {
  for (const value of ['', null, 'null', '{}', '[]', '""', '" \u3000"']) {
    assert.equal(jsonFieldIssue(value, true, true), 'required');
    assert.equal(jsonFieldIssue(value, true, false), undefined);
  }
  for (const value of ['0', 'false', '"[]"', '[null]', '{"a":null}', '9007199254740993']) {
    assert.equal(jsonFieldIssue(value, true, true), undefined);
  }
  for (const value of ['{} true', '{"a":1,"a":2}', {}, []]) {
    assert.equal(jsonFieldIssue(value, true, false), 'invalid');
  }
  assert.equal(jsonFieldIssue('{}', false, true), undefined);
  assert.equal(jsonFieldIssue('{"a":1,"a":2}', false, true), undefined);
});
