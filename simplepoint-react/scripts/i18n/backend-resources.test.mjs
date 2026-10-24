import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {test} from 'node:test';
import {readBackendBundles} from './backend-resources.mjs';

const fixture = t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'simplepoint-i18n-'));
  t.after(() => fs.rmSync(root, {recursive: true, force: true}));
  const put = (module, relative, value) => {
    const file = path.join(root, module, 'src/main/resources/META-INF/simplepoint', relative);
    fs.mkdirSync(path.dirname(file), {recursive: true});
    fs.writeFileSync(file, typeof value === 'string' ? value : JSON.stringify(value));
  };
  return {root, put};
};

test('combines legacy assets with indexed resources split across module artifacts', t => {
  const {root, put} = fixture(t);
  put('legacy-host', 'i18n/en-US/common.json', {ready: 'Ready'});
  put('identity-module', 'modules/identity/i18n.imports', '# Owned assets\nen-US/users.json\n');
  put('identity-implementation', 'modules/identity/i18n/en-US/users.json', {name: 'Name'});
  put('identity-implementation', 'modules/identity/i18n/en-US/unpublished.json', {secret: 'Not declared'});
  put('.simplepoint/old-release', 'i18n/en-US/common.json', {old: 'Obsolete'});
  assert.deepEqual(readBackendBundles(root), {'en-US': {common: {ready: 'Ready'}, users: {name: 'Name'}}});
});

test('rejects duplicate declarations and resources instead of choosing by traversal order', t => {
  const {root, put} = fixture(t);
  put('identity-module', 'modules/identity/i18n.imports', 'en-US/users.json');
  put('first', 'modules/identity/i18n/en-US/users.json', {name: 'Name'});
  put('second', 'modules/identity/i18n/en-US/users.json', {name: 'Name'});
  assert.throws(() => readBackendBundles(root), /exactly once/);
  fs.rmSync(path.join(root, 'second'), {recursive: true});
  put('legacy', 'i18n/en-US/users.json', {name: 'Name'});
  assert.throws(() => readBackendBundles(root), /Duplicate backend i18n namespace/);
});

test('rejects missing, duplicate or escaping index entries and non-string messages', t => {
  const {root, put} = fixture(t);
  for (const entry of ['', '../private.json', 'en-US/users.json\nen-US/users.json']) {
    put('owner', 'modules/identity/i18n.imports', entry);
    assert.throws(() => readBackendBundles(root), /[Ii]ndex/);
  }
  put('owner', 'modules/identity/i18n.imports', 'en-US/users.json');
  assert.throws(() => readBackendBundles(root), /exactly once/);
  put('data', 'modules/identity/i18n/en-US/users.json', {invalid: {nested: 'value'}});
  assert.throws(() => readBackendBundles(root), /Invalid backend i18n declaration/);
  put('data', 'modules/identity/i18n/en-US/users.json', {name: 'Name'});
  put('duplicate-owner', 'modules/identity/i18n.imports', 'en-US/users.json');
  assert.throws(() => readBackendBundles(root), /duplicate module i18n index/);
});
