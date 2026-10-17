import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {test} from 'node:test';
import {parse} from '@babel/parser';

test('frontend build cache includes the server-owned workflow modeling contract', () => {
  const rootBuild = readFileSync(new URL('../../build.gradle.kts', import.meta.url), 'utf8');
  assert.match(rootBuild, /val workflowModelingContractFile = layout\.projectDirectory\.file\(/);
  assert.match(rootBuild, /src\/main\/resources\/META-INF\/simplepoint\/workflow\/modeling\.json/);
  const registration = rootBuild.slice(rootBuild.indexOf('fun registerFrontendBuildTask'), rootBuild.indexOf('registerFrontendBuildTask("buildCommonFrontend"'));
  assert.match(registration, /inputs\.file\(workflowModelingContractFile\)\.withPathSensitivity\(PathSensitivity\.RELATIVE\)/);
});

test('translation discovery ignores deployment snapshots while retaining duplicate source detection', () => {
  const source = readFileSync(new URL('./sync-i18n-bundles.mjs', import.meta.url), 'utf8');
  const ast = parse(source, {sourceType: 'module'});
  const ignored = ast.program.body.filter(node => node.type === 'VariableDeclaration')
    .flatMap(node => node.declarations).find(node => node.id.name === 'ignoredDirectories');
  assert.ok(ignored.init.arguments[0].elements.some(node => node.value === '.simplepoint'));
  assert.match(source, /ignoredDirectories\.has\(entry\.name\)/);
  assert.match(source, /throw new Error\(`Duplicate backend i18n namespace:/);
});

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
