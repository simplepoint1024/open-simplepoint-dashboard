import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {test} from 'node:test';
import {parse} from '@babel/parser';

test('every production host/remote disables the persistent module graph that returned stale App sources', () => {
  for (const name of ['common', 'audit', 'dna', 'ai', 'workflow', 'app', 'host']) {
    const file = `../modules/simplepoint-${name}/${name === 'host' ? 'rsbuild' : 'rslib'}.config.ts`;
    const ast = parse(readFileSync(new URL(file, import.meta.url), 'utf8'), {sourceType: 'module', plugins: ['typescript']});
    const config = ast.program.body.find(node => node.type === 'ExportDefaultDeclaration')?.declaration?.arguments?.[0];
    const property = (object, key) => object?.properties?.find(node => node.type === 'ObjectProperty' && (node.key.name ?? node.key.value) === key)?.value;
    const cache = property(property(config, 'performance'), 'buildCache');
    assert.equal(cache?.type, 'BooleanLiteral', name); assert.equal(cache?.value, false, name);
    for (const library of property(config, 'lib')?.elements ?? []) {
      const override = property(property(library, 'performance'), 'buildCache');
      assert.ok(!override || (override.type === 'BooleanLiteral' && override.value === false), `${name} cannot reenable stale snapshots in a format override`);
    }
  }
});
