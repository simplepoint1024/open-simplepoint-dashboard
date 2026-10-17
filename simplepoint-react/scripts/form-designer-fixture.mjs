import {standardComponents} from './form-standard-components-fixture.mjs';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {fixturePage, fixtureSchema} from './query-fixture.mjs';
import {createFormPublicationFixture} from './form-publication-fixture.mjs';
const fields = {code: {type: 'string'}, name: {type: 'string'}, description: {type: 'string'}};
const descriptor = (type, container = false, types = []) => ({type, version: '1', container, fieldRequired: types.length > 0,
  supportedFieldTypes: types, propertySchema: {}, allowedParentTypes: [], allowedChildTypes: []});
const components = [descriptor('layout.form', true), descriptor('layout.grid', true), descriptor('layout.group', true), descriptor('layout.tabs', true),
  descriptor('field.text', false, ['STRING']), descriptor('field.textarea', false, ['STRING', 'TEXT']),
  descriptor('field.number', false, ['INT32', 'INT64', 'DECIMAL']), descriptor('field.select', false, ['STRING']),
  ...['save', 'submit', 'reset', 'open-dialog', 'open-drawer', 'apply', 'close'].map(action => descriptor('action.' + action))];
components.push(...standardComponents,
  {...descriptor('layout.matrix', true), propertySchema: {
    rows: {name: 'rows', acceptedKinds: ['INTEGER'], required: true, defaultValue: {kind: 'INTEGER', value: '2'}},
    columns: {name: 'columns', acceptedKinds: ['INTEGER'], required: true, defaultValue: {kind: 'INTEGER', value: '2'}},
  }, allowedChildTypes: ['layout.cell']},
  {...descriptor('layout.cell', true), allowedParentTypes: ['layout.matrix']},
  {...descriptor('data.detail'), propertySchema: {detailId: {name: 'detailId', acceptedKinds: ['STRING'], required: true}}},
);
const initial = () => ({contractVersion: '1', fields: [], indexes: [], rules: [], scenes: [{sceneId: 'main', code: 'main', name: '主页面', type: 'MAIN',
  width: null, root: {componentId: 'root-main', componentType: 'layout.form', componentVersion: '1', columnSpan: 24,
    readOnly: false, properties: {}, children: []}}]});
const canonical = document => ({...structuredClone(document), fields: [...document.fields].sort((a, b) => a.fieldId.localeCompare(b.fieldId))});
const checksum = document => createHash('sha256').update(JSON.stringify(document)).digest('hex');

/** Independent HTTP state machine for browser editing/response-loss/conflict scenarios. */
export function createFormDesignerFixture() {
  let active = false, tenant, readonly = false, loseResponse = false, hold = false, release;
  const forms = [], drafts = new Map(), writes = [];
  const publication = createFormPublicationFixture(forms, drafts);
  return {forms, drafts, writes, publication, enable(id, applications) {active = true; tenant = id; publication.enable(applications);}, readonly(value) {readonly = value;},
    loseSaveResponse() {loseResponse = true;}, holdNextSave() {hold = true;}, isHeld() {return Boolean(release);},
    releaseSave() {release?.(); release = undefined;},
    externalEdit() {const draft = [...drafts.values()][0]; draft.revision++; draft.document.scenes[0].name = '外部修改'; draft.checksum = checksum(draft.document);},
    async handle(req, url, respond, expectedFailure) {
      if (!active) return false;
      if (/^\/app\/(runtime\/applications\/|applications\/[^/]+\/(forms|publication-pages))/.test(url.pathname))
        assert.equal(req.headers['x-tenant-id'], tenant);
      if (await publication.handle(req, url, respond, expectedFailure, readonly)) return true;
      const match = url.pathname.match(/^\/app\/applications\/([^/]+)\/forms(.*)$/);
      if (!active || !match) return false;
      assert.equal(req.headers['x-tenant-id'], tenant);
      const [, applicationId, path] = match;
      if (path === '/designer-contract') return respond({canCreate: !readonly, canEdit: !readonly, canPublish: !readonly,
        components, writePolicies: ['EDITABLE', 'CREATE_ONLY', 'UNTIL_SUBMITTED']}), true;
      if (path === '/catalog/schema') {
        const result = fixtureSchema(fields); result.schema.required = ['code', 'name'];
        result.buttons = [...(readonly ? [] : [{key: 'add', text: '新建表单', argumentMinSize: 0, argumentMaxSize: 0}]),
          {key: 'design', text: '设计表单', argumentMinSize: 1, argumentMaxSize: 1}];
        return respond(result), true;
      }
      if (path === '/catalog') {
        const page = fixturePage(forms.filter(form => form.applicationId === applicationId), fields, url);
        return respond({content: page.content, page: {number: page.page, size: page.size, totalElements: page.totalElements,
          totalPages: Math.ceil(page.totalElements / page.size)}}), true;
      }
      const id = path.split('/')[1];
      if (req.method === 'GET' && path === '/' + id) return respond(forms.find(form => form.id === id && form.applicationId === applicationId)), true;
      if (req.method === 'GET' && path.endsWith('/draft')) return respond(structuredClone(drafts.get(id))), true;
      assert.equal(readonly, false, 'view-only designers cannot dispatch mutations');
      let raw = ''; for await (const chunk of req) raw += chunk;
      const data = JSON.parse(raw); writes.push({path: url.pathname, method: req.method, data});
      if (req.method === 'POST' && path === '') {
        assert.match(data.code, /^[a-z][a-z0-9_]{0,62}$/); assert.ok(data.name.trim());
        const form = {id: `design-form-${forms.length + 1}`, applicationId, ...data, status: 'DRAFT', draftRevision: 0, latestVersion: 0, publishedVersion: null};
        forms.push(form); const document = initial();
        drafts.set(form.id, {formId: form.id, revision: 0, document, checksum: checksum(document), updatedAt: new Date().toISOString()});
        return respond(form), true;
      }
      assert.equal(req.method, 'PUT'); assert.ok(path.endsWith('/draft'));
      const draft = drafts.get(id); assert.ok(draft);
      if (data.expectedRevision !== draft.revision) {
        expectedFailure(409); return respond({code: 'DRAFT_REVISION_CONFLICT', message: '草稿已更新'}, 409), true;
      }
      if (hold) {hold = false; await new Promise(resolve => {release = resolve;});}
      assert.equal(data.document.scenes.filter(scene => scene.type === 'MAIN').length, 1);
      assert.equal(new Set(data.document.fields.map(field => field.fieldId)).size, data.document.fields.length);
      assert.ok(data.document.fields.every(field => field.label?.trim()));
      draft.revision++; draft.document = canonical(data.document); draft.checksum = checksum(draft.document);
      draft.updatedAt = new Date().toISOString(); forms.find(form => form.id === id).draftRevision = draft.revision;
      if (loseResponse) {loseResponse = false; expectedFailure(503); return respond({message: '保存响应丢失（测试）'}, 503), true;}
      return respond(structuredClone(draft)), true;
    }};
}
