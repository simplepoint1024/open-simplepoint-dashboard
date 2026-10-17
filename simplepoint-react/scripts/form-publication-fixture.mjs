import assert from 'node:assert/strict';
import {fixturePage, fixtureSchema} from './query-fixture.mjs';

/** Independent publication and record state, fed only by the browser's saved design. */
export function createFormPublicationFixture(forms, drafts) {
  let applications, loseFreeze = false, losePublication = false, failPreparation = false, cancelPublication = false;
  const requestIntents = new Map();
  const recordReceipts = new Map();
  let blockPublication = false;
  let loseRecord = false;
  const versions = new Map(), releases = new Map(), records = new Map(), writes = [], receipts = new Map(), intents = new Map();
  const read = async req => {let raw = ''; for await (const chunk of req) raw += chunk; return raw ? JSON.parse(raw) : {};};
  const activate = intent => {
    const form = forms.find(item => item.id === intent.formId);
    const page = applications.pages.get(intent.applicationId).find(item => item.id === intent.pageId);
    for (const previous of intents.values()) {
      if (previous.pageId === page.id && previous.id !== intent.id && previous.status !== 'CANCELLED') previous.status = 'RETIRED';
    }
    intent.status = 'ACTIVE'; delete intent.inspection; delete intent.errorCode;
    const release = {dataPublication: {id: 'publication-' + intent.id, formId: intent.formId,
      formVersion: intent.formVersion, status: 'ACTIVE'}, pageBinding: {...intent}};
    releases.set(intent.key, release); page.formId = intent.formId; page.formVersion = intent.formVersion; page.status = 'PUBLISHED';
    form.status = 'PUBLISHED'; form.publishedVersion = intent.formVersion;
    return release;
  };
  return {versions, releases, records, writes, intents, enable(value) {applications = value;},
    blockNextPublication() {blockPublication = true;},
    loseRecordResponse() {loseRecord = true;},
    loseResponses() {loseFreeze = true; losePublication = true;},
    interruptBeforeData() {failPreparation = true;},
    cancelNextPublication() {cancelPublication = true;},
    async handle(req, url, respond, expectedFailure, readonly) {
      const target = url.pathname.match(/^\/app\/applications\/([^/]+)\/publication-pages$/);
      if (target) {
        const fields = {id: {type: 'string'}, name: {type: 'string'}, formId: {type: 'string'}};
        return respond(fixturePage(applications.pages.get(target[1]) ?? [], fields, url)), true;
      }
      const history = url.pathname.match(/^\/app\/applications\/([^/]+)\/forms\/([^/]+)\/releases(?:\/(schema)|\/([^/]+)\/(resume|cancel|inspection))?$/);
      if (history) {
        const [, appId, formId, schema, releaseId, action] = history;
        const fields = {pageName: {type: 'string', title: '目标页面'}, formVersion: {type: 'integer', title: '表单版本'},
          status: {type: 'string', title: '发布状态'}, updatedAt: {type: 'string', format: 'date-time', title: '更新时间'}};
        if (schema) {
          const result = fixtureSchema(fields);
          for (const name of Object.keys(fields)) result.schema.properties[name].title = 'i18n:app.release.' + name;
          result.schema.properties.status.oneOf = ['DATA_PREPARING', 'RESOURCE_PREPARING', 'RESOURCE_READY',
            'ACTIVATING', 'ACTIVE', 'RETIRED', 'CANCELLED', 'FAILED'].map(value => ({const: value, title: 'i18n:app.release.status.' + value}));
          result.buttons = [...(readonly ? [] : [{key: 'resume', text: '继续发布', argumentMinSize: 1, argumentMaxSize: 1},
            {key: 'cancel', text: '取消本次发布', argumentMinSize: 1, argumentMaxSize: 1},
            {key: 'inspection', text: '查看检查结果', argumentMinSize: 1, argumentMaxSize: 1}]),
            {key: 'data', text: '查看数据', argumentMinSize: 1, argumentMaxSize: 1}];
          return respond(result), true;
        }
        if (releaseId && action === 'inspection') {assert.equal(req.method, 'GET'); return respond(intents.get(releaseId)?.inspection ?? null), true;}
        if (releaseId) {
          assert.equal(readonly, false); assert.equal(req.method, 'POST');
          const data = await read(req); assert.deepEqual(data, {});
          writes.push({path: url.pathname, data});
          const intent = intents.get(releaseId); assert.ok(intent);
          assert.equal(intent.applicationId, appId); assert.equal(intent.formId, formId);
          if (action === 'cancel') {
            assert.ok(['DATA_PREPARING', 'CANCELLED'].includes(intent.status));
            intent.status = 'CANCELLED';
            return respond({dataPublication: null, pageBinding: {...intent}}), true;
          }
          assert.notEqual(intent.status, 'RETIRED');
          assert.notEqual(intent.status, 'CANCELLED');
          return respond(activate(intent)), true;
        }
        return respond(fixturePage([...intents.values()].filter(item => item.applicationId === appId && item.formId === formId)
          .map(item => ({...item, canCancel: !readonly && item.status === 'DATA_PREPARING'})), fields, url)), true;
      }
      const versionPath = url.pathname.match(/^\/app\/applications\/([^/]+)\/forms\/([^/]+)\/versions(?:\/(\d+)\/publication)?$/);
      if (versionPath) {
        const [, appId, id, number] = versionPath, form = forms.find(item => item.id === id);
        assert.equal(form.applicationId, appId);
        const saved = versions.get(id) ?? []; versions.set(id, saved);
        if (req.method === 'GET') return respond(saved), true;
        assert.equal(readonly, false);
        const data = await read(req); writes.push({path: url.pathname, data});
        if (!number) {
          const draft = drafts.get(id);
          if (data.expectedRevision !== draft.revision) {
            expectedFailure(409); return respond({code: 'DRAFT_REVISION_CONFLICT', message: '草稿已更新'}, 409), true;
          }
          let version = saved.find(item => item.checksum === draft.checksum);
          if (!version) {version = {formId: id, version: saved.length + 1, checksum: draft.checksum,
            document: structuredClone(draft.document)}; saved.unshift(version);}
          if (loseFreeze) {loseFreeze = false; expectedFailure(503); return respond({message: '版本响应丢失（测试）'}, 503), true;}
          return respond(version), true;
        }
        const version = saved.find(item => item.version === Number(number)); assert.ok(version);
        const page = applications.pages.get(appId).find(item => item.id === data.pageId); assert.ok(page);
        const key = `${id}:${number}:${data.pageId}`;
        if (receipts.has(data.idempotencyKey)) assert.equal(receipts.get(data.idempotencyKey), key);
        receipts.set(data.idempotencyKey, key);
        let intent = requestIntents.get(data.idempotencyKey) ?? [...intents.values()]
          .find(item => item.key === key && !['RETIRED', 'CANCELLED'].includes(item.status));
        if (!intent) {
          intent = {id: 'release-' + (intents.size + 1), key, applicationId: appId, formId: id, pageId: page.id,
            pageName: page.name, formVersion: Number(number), idempotencyKey: data.idempotencyKey,
            status: 'DATA_PREPARING', updatedAt: new Date().toISOString()};
          intents.set(intent.id, intent);
        }
        requestIntents.set(data.idempotencyKey, intent);
        if (cancelPublication) {cancelPublication = false; intent.status = 'CANCELLED';}
        if (intent.status === 'CANCELLED') return respond({pageBinding: {...intent}}), true;
        if (failPreparation) {
          failPreparation = false; expectedFailure(503);
          return respond({message: '数据准备暂不可用（测试）'}, 503), true;
        }
        if (blockPublication) {
          blockPublication = false; intent.status = 'FAILED'; intent.errorCode = 'PUBLICATION_DATA_CONFLICT';
          intent.inspection = {checkedAt: new Date().toISOString(), repairPageId: page.id,
            issues: [{id: 'required', kind: 'REQUIRED', fields: ['客户姓名'], count: '9007199254740993'}]};
          return respond({pageBinding: {...intent}, inspection: intent.inspection}), true;
        }
        const release = releases.get(key) ?? activate(intent);
        if (losePublication) {losePublication = false; expectedFailure(503); return respond({message: '发布响应丢失（测试）'}, 503), true;}
        return respond(release), true;
      }
      const runtime = url.pathname.match(/^\/app\/runtime\/applications\/([^/]+)\/pages\/([^/]+)(.*)$/);
      if (!runtime) return false;
      const [, appId, pageId, path] = runtime, page = applications.pages.get(appId).find(item => item.id === pageId);
      assert.ok(page?.formId, 'the data workspace requires an actually published page');
      const version = versions.get(page.formId).find(item => item.version === page.formVersion), document = version.document;
      if (path === '') return respond({applicationId: appId, pageId, pageName: page.name, formId: page.formId,
        formName: '客户登记', formVersion: page.formVersion, sceneId: document.scenes.find(item => item.type === 'MAIN').sceneId,
        document, capabilities: {canCreate: !readonly, canEdit: !readonly, canDelete: !readonly, canStartProcess: false}}), true;
      if (path === '/records/schema') {
        // Runtime uses stable field identities (including UUIDs), not local-table property aliases.
        const schema = {schema: {type: 'object', properties: Object.fromEntries(document.fields.map(field =>
          [field.fieldId, {type: 'string', title: field.label}])), 'x-query': {version: 1, maxPageSize: 100,
          maxNodes: 32, maxDepth: 4, maxSort: 3, relations: [], fields: Object.fromEntries(document.fields.map(field =>
            [field.fieldId, {javaType: 'String', sortable: true, operators: ['equals', 'like', 'null', '!null']}]))}}, buttons: []};
        schema.buttons = readonly ? [] : [{key: 'add', text: '新增记录', argumentMinSize: 0, argumentMaxSize: 0},
          {key: 'edit', text: '编辑记录', argumentMinSize: 1, argumentMaxSize: 1},
          {key: 'delete', text: '删除记录', argumentMinSize: 1, argumentMaxSize: 1}];
        return respond(schema), true;
      }
      if (req.method === 'GET' && path.startsWith('/records/')) {
        const record = records.get(path.split('/').at(-1)); assert.ok(record);
        return respond(structuredClone(record)), true;
      }
      const data = await read(req);
      // Lists omit child collections; editing must load the aggregate before opening the form.
      if (path === '/records/query') return respond({page: data.page, size: data.size, total: records.size,
        records: [...records.values()].map(record => ({...record, values: Object.fromEntries(
          Object.entries(record.values).filter(([key]) => !document.details?.some(detail => detail.detailId === key)))}))}), true;
      assert.equal(readonly, false); writes.push({path: url.pathname, data, method: req.method});
      const operationKey = req.method === 'DELETE' ? url.searchParams.get('idempotencyKey') : data.idempotencyKey;
      assert.ok(operationKey, 'record mutations require an operation key');
      const previousRecord = recordReceipts.get(operationKey);
      if (previousRecord) {
        assert.equal(previousRecord.method, req.method); assert.equal(previousRecord.path, url.pathname);
        assert.deepEqual(previousRecord.data, data);
        assert.equal(previousRecord.query, url.search);
        return respond(previousRecord.result), true;
      }
      const acknowledge = (result) => {
        recordReceipts.set(operationKey, {method: req.method, path: url.pathname, query: url.search,
          data: structuredClone(data), result: structuredClone(result)});
        if (loseRecord) {loseRecord = false; expectedFailure(503); respond({message: '写入响应丢失（测试）'}, 503);}
        else respond(result);
        return true;
      };
      if (req.method === 'DELETE') {
        const record = records.get(path.split('/').at(-1)); assert.ok(record);
        assert.equal(Number(url.searchParams.get('expectedVersion')), record.version);
        assert.equal(url.searchParams.get('formId'), page.formId);
        assert.equal(Number(url.searchParams.get('formVersion')), page.formVersion);
        records.delete(record.id); return acknowledge(null);
      }
      assert.deepEqual(data.runtime, {formId: page.formId, formVersion: page.formVersion});
      for (const field of document.fields) if (field.submitRequired) assert.ok(data.values[field.fieldId]?.trim());
      let record;
      if (req.method === 'POST') {record = {id: 'published-record-' + (records.size + 1), version: 0, state: data.state, values: data.values}; records.set(record.id, record);}
      else {record = records.get(path.split('/').at(-1)); assert.ok(record); assert.equal(data.expectedVersion, record.version);
        for (const field of document.fields) {
          if (field.writePolicy === 'CREATE_ONLY' || field.writePolicy === 'UNTIL_SUBMITTED' && record.state === 'SUBMITTED') {
            assert.ok(!Object.hasOwn(data.values, field.fieldId), 'business-locked fields must be omitted from updates');
          }
        }
        record.version++; Object.assign(record.values, data.values);}
      return acknowledge({recordId: record.id, version: record.version, state: record.state, currentRecord: structuredClone(record)});
    }};
}
