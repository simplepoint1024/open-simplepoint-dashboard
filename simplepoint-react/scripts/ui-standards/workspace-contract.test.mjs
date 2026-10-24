import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
const root = new URL('../../../', import.meta.url);
const declarations = async path => {
  const result = new Map();
  const document = JSON.parse(await readFile(new URL(path, root), 'utf8'));
  const visit = (rows, scopes) => {for (const row of rows) {const effective = row.scopeTypes ?? scopes; result.set(row.code, {...row, scopes: effective}); visit(row.children ?? [], effective);}};
  visit(document.resources, []); return result;
};
const common = await declarations('simplepoint-plugins/simplepoint-plugin-common-ui/src/main/resources/META-INF/simplepoint/modules/common-ui/resources.json');
const dna = await declarations('simplepoint-plugins/simplepoint-plugin-dna/simplepoint-plugin-dna-http/src/main/resources/META-INF/simplepoint/modules/dna-http/resources.json');
const app = await declarations('simplepoint-plugins/simplepoint-plugin-app/simplepoint-plugin-app-http/src/main/resources/META-INF/simplepoint/modules/app-http/resources.json');
test('tenant workflow and form storage entries never register unsupported platform access', () => {
  for (const rows of [common, dna, app]) for (const [code, row] of rows) {
    if (/^personal\.(items|todos)\.|^dna\.runtime\.|^app\./.test(code)) {
      assert.deepEqual(row.scopes, ['TENANT'], code); assert.equal(row.requireOrgTenant, true, code);
    }
  }
  assert.equal(common.get('dashboard.view').scopes.includes('PLATFORM'), true);
  assert.equal(common.get('personal.messages.view').scopes.includes('PLATFORM'), true);
});
test('workspace administration has independent authorized component identities', () => {
  for (const [code, component, path] of [['settings','host/TenantSettings','/tenant/settings'],['resources','host/TenantResources','/tenant/resources'],['packages','host/TenantPackages','/tenant/packages']]) {
    const resource = common.get(`workspace.${code}.view`);
    assert.equal(resource.component, component); assert.equal(resource.path, path);
    assert.deepEqual(resource.scopes, ['TENANT', 'PERSONAL']); assert.equal(resource.publicAccess, false);
  }
  for (const code of ['app.templates.view','app.marketplace.view']) assert.equal(app.get(code).disabled, true);
});
