import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import test from 'node:test';
import {collectPageExposes} from './module.exposes.ts';

for (const [module, service] of [
  ['common', 'common'],
  ['audit', 'auditing'],
  ['dna', 'dna'],
  ['ai', 'ai'],
  ['app', 'app'],
  ['workflow', 'workflow'],
]) {
  test(`${service}: every declared backend route remains exposed; private drawers do not`, () => {
    const directory = fileURLToPath(new URL(`../../../modules/simplepoint-${module}/`, import.meta.url));
    const exposes = collectPageExposes(directory);
    const resources = JSON.parse(readFileSync(new URL(`../../../../simplepoint-services/simplepoint-service-${service}/src/main/resources/META-INF/simplepoint/resources/${service}.json`, import.meta.url)));
    const walk = value => {
      if (!value || typeof value !== 'object') return;
      if (typeof value.component === 'string' && value.component.startsWith(`${service}/`)) {
        assert.ok(exposes[`./${value.component.slice(service.length + 1)}`], value.component);
      }
      Object.values(value).forEach(walk);
    };
    walk(resources);
    assert.ok(Object.keys(exposes).length > 0);
    assert.ok(Object.keys(exposes).every(key => !/\/(config|components)\//.test(key)));
    if (service === 'ai') {
      assert.equal(Object.keys(exposes).length, 7);
      assert.ok(Object.keys(exposes).every(key => !/\/(Skills|Agents|Workflows)(\/|$)/.test(key)));
    }
  });
}
