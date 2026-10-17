import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';
import {jsonValue, emptyJsonValue} from './jsonValue.ts';

const cases = JSON.parse(readFileSync(new URL('../../../../simplepoint-plugins/simplepoint-plugin-dna/simplepoint-plugin-dna-runtime-api/src/test/resources/json-value-cases.json', import.meta.url), 'utf8'));
for (const item of cases) test(`JSON contract: ${item.name}`, () => {
  if (item.invalid) assert.throws(() => jsonValue(item.source));
  else {
    assert.deepEqual(jsonValue(item.source), {canonical: item.canonical, empty: item.empty});
    assert.equal(jsonValue(item.canonical).canonical, item.canonical);
  }
});
test('JSON budgets and unfilled editor values', () => {
  const depth = '['.repeat(64) + '0' + ']'.repeat(64);
  assert.equal(jsonValue(depth).canonical, depth);
  assert.equal(jsonValue('[' + '0,'.repeat(9998) + '0]').empty, false);
  for (const invalid of ['[' + depth + ']', '[' + '0,'.repeat(9999) + '0]', '"' + 'x'.repeat(1048575) + '"', '1'.repeat(2001)]) {
    assert.throws(() => jsonValue(invalid));
  }
  for (const empty of [undefined, null, '', ' \u3000']) assert.equal(emptyJsonValue(empty), true);
  for (const invalid of ['invalid', {}, []]) assert.equal(emptyJsonValue(invalid), false);
});
