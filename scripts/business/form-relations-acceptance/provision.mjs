import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';
import {relationBusinesses, customerWithInverse} from './forms.mjs';

/** Persists accepted identities before proceeding; replay never guesses a failed publication. */
export async function provisionRelations({call, state, save}) {
  state.forms ??= {};
  if (!state.appId) {
    const created = await call('/app/applications', 'POST', {name: '单值关联业务验收',
      nameLocalization: {defaultValue: '单值关联业务验收', defaultLocale: 'zh-CN', translations: {'en-US': 'Single-value relation acceptance'}},
      navigationGroup: true, description: '独立合成样例：客户交易、明细商品及员工档案。', mainPageName: '关联验收客户'});
    state.appId = created.application.id; state.forms.rel_customer = {pageId: created.pages[0].id}; await save();
  }
  async function publish(spec, current, suffix = '') {
    const base = `/app/applications/${state.appId}/forms/${current.formId}`;
    const key = 'release' + suffix;
    current[key] ??= {}; const release = current[key];
    if (!release.version) {
      let draft = await call(base + '/draft');
      draft = await call(base + '/draft', 'PUT', {expectedRevision: draft.revision, document: spec.document});
      const version = await call(base + '/versions', 'POST', {expectedRevision: draft.revision});
      release.version = version.version; await save();
    }
    if (!release.published) {
      release.idempotencyKey ??= randomUUID(); await save();
      for (let n = 0; n < 150; n++) {
        const result = await call(base + `/versions/${release.version}/publication`, 'POST', {pageId: current.pageId, idempotencyKey: release.idempotencyKey});
        release.publication = result; await save();
        if (result.pageBinding?.status === 'ACTIVE') {release.published = true; break;}
        assert(!['FAILED', 'ATTENTION', 'CANCELLED'].includes(result.pageBinding?.status), 'Publication needs diagnosis: ' + spec.code);
        await delay(800);
      }
      assert(release.published, 'Publication incomplete: ' + spec.code);
    }
    current.version = release.version;
    current.runtime = `/app/runtime/applications/${state.appId}/pages/${current.pageId}`;
    const runtime = await call(current.runtime); assert.equal(runtime.formVersion, current.version);
    current.resourceCode = runtime.resourceCode; current.resourceId = current.release.publication.pageBinding.resourceId; await save();
  }
  for (const create of relationBusinesses(state)) {
    const spec = create(), current = state.forms[spec.code] ??= {};
    const catalog = `/app/applications/${state.appId}/forms`;
    if (!current.pageId) {current.pageId = (await call(`/app/applications/${state.appId}/pages`, 'POST', {name: spec.name, nameLocalization: spec.nameLocalization})).id; await save();}
    if (!current.formId) {current.formId = (await call(catalog, 'POST', {code: spec.code, name: spec.name, nameLocalization: spec.nameLocalization})).id; await save();}
    if (!current.releaseInverse?.published) await publish(spec, current);
    console.log('PASS published ' + spec.code);
  }
  const customer = relationBusinesses(state)[0](); customer.document = customerWithInverse(state, customer.document);
  await publish(customer, state.forms.rel_customer, 'Inverse');
  state.provisioned = true; await save();
}
