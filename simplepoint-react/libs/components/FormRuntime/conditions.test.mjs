import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';
import {conditionMatches} from './conditions.ts';

// The server test loads this same fixture as a classpath resource.
const cases = JSON.parse(readFileSync(new URL('../../../../simplepoint-plugins/simplepoint-plugin-app/simplepoint-plugin-app-form-implementation/src/test/resources/form-condition-cases.json', import.meta.url), 'utf8'));
for (const item of cases) test(`rule contract: ${item.name}`, () => {
  assert.equal(conditionMatches(item.condition, item.values, new Map(Object.entries(item.types ?? {}).map(([key, type]) => [key, {type}]))), item.expected);
});
