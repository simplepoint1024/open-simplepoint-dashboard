import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';
import {calculationWorkflow} from './workflows.mjs';
import {withAcceptanceDiagram} from './workflow-diagram.mjs';

/** Resumable public-API provisioner. The caller persists each accepted identity before continuing. */
export async function provisionCalculationBusinesses({call, specs, state, save, progress = console.log}) {
  state.forms ??= {};
  if (!state.appId) {
    const created = await call('/app/applications', 'POST', {name: '计算引擎业务验收', navigationGroup: true,
      description: '合成数据；表单、DNA、计算、流程与权限的独立验收，不用于真实付款。', mainPageName: specs[0].name});
    state.appId = created.application.id; state.forms[specs[0].code] = {pageId: created.pages[0].id}; await save();
  }
  for (const spec of specs) {
    const current = state.forms[spec.code] ??= {};
    if (!current.pageId) {
      current.pageId = (await call(`/app/applications/${state.appId}/pages`, 'POST', {name: spec.name, description: spec.description})).id;
      await save();
    }
    const catalog = `/app/applications/${state.appId}/forms`;
    if (!current.formId) {
      current.formId = (await call(catalog, 'POST', {code: spec.code, name: spec.name, description: spec.description})).id;
      await save();
    }
    const base = catalog + '/' + current.formId;
    if (!current.version) {
      let draft = await call(base + '/draft');
      draft = await call(base + '/draft', 'PUT', {expectedRevision: draft.revision, document: spec.document});
      const version = await call(base + '/versions', 'POST', {expectedRevision: draft.revision});
      current.version = version.version; current.checksum = version.checksum; await save();
    }
    if (!current.published) {
      current.releaseKey ??= randomUUID(); await save();
      for (let attempt = 0; attempt < 150; attempt++) {
        const result = await call(base + `/versions/${current.version}/publication`, 'POST', {
          pageId: current.pageId, idempotencyKey: current.releaseKey});
        current.publication = result; await save();
        if (result.pageBinding?.status === 'ACTIVE') {current.published = true; break;}
        assert(!['FAILED', 'CANCELLED', 'ATTENTION'].includes(result.pageBinding?.status), 'Publication requires diagnosis: ' + spec.code);
        await delay(800);
      }
      assert(current.published, 'Publication incomplete: ' + spec.code); await save();
    }
    const workflow = calculationWorkflow(spec, current.formId, current.version, state.appId.replaceAll('-', '').slice(0, 12));
    workflow.bpmnXml = await withAcceptanceDiagram(workflow.bpmnXml);
    if (!current.workflowId) {
      const created = await call('/workflow/definitions', 'POST', workflow);
      current.workflowId = created.definition.id; current.workflowRevision = created.definition.draftRevision; await save();
    }
    if (!current.workflowDraftSaved) {
      await call('/workflow/definitions/' + current.workflowId + '/draft', 'PUT', {...workflow, expectedRevision: current.workflowRevision});
      current.workflowRevision++; current.workflowDraftSaved = true; await save();
    }
    if (!current.workflowVersion) {
      const published = await call('/workflow/definitions/' + current.workflowId + '/publish', 'POST', {
        expectedRevision: current.workflowRevision, comment: '公式业务集成验收'});
      current.workflowVersion = published.version ?? published.versionNumber ?? 1; await save();
    }
    current.runtime = `/app/runtime/applications/${state.appId}/pages/${current.pageId}`;
    const runtime = await call(current.runtime);
    assert.equal(runtime.formVersion, current.version);
    for (const f of spec.document.fields.filter(f => f.calculation)) {
      const projected = runtime.document.fields.find(item => item.fieldId === f.fieldId);
      assert.equal(projected?.calculated, true); assert.equal(projected.calculation, undefined);
    }
    current.resourceCode = runtime.resourceCode; await save();
    progress('Published business, immutable calculation projection and workflow: ' + spec.name);
  }
  return state;
}
