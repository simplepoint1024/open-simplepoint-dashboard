import assert from 'node:assert/strict';
import {fixtureSchema} from './query-fixture.mjs';

export const appFormRuntimeRoute = {id: 'app-form-runtime-test', code: 'app.runtime.form.test', type: 'PAGE',
  routeKind: 'item', path: '/app/runtime/app-forms/page-main', component: 'app/FormRuntime',
  label: '表单运行测试', scopeTypes: ['TENANT'], requireOrgTenant: true};
const base = '/app/runtime/applications/app-forms/pages/page-main';
const component = (componentId, componentType, fieldId = null, extra = {}) => ({componentId, componentType,
  componentVersion: '1', fieldId, columnSpan: 24, readOnly: false, properties: {}, children: [], ...extra});
const field = (fieldId, required = false) => ({fieldId, label: fieldId, type: 'STRING', length: 120,
  submitRequired: required, defaultValue: null, choices: []});

/** Isolated HTTP fixture: state and receipts are independent of React's implementation. */
export function createAppFormRuntimeFixture() {
  let active = false, tenant, failAfterCommit = false, version = 1, writePolicyVersion = 1;
  const writes = [], records = new Map(), receipts = new Map();
  const descriptor = () => ({applicationId: 'app-forms', pageId: 'page-main', pageName: '客户登记',
    formId: 'form-customer', formName: 'Customer', formVersion: version, writePolicyVersion, sceneId: 'customer-main',
    resourceCode: appFormRuntimeRoute.code,
    capabilities: {canCreate: true, canEdit: true, canDelete: true, canStartProcess: false},
    document: {contractVersion: '1', fields: [field('name', true), {...field('payload', true), type: 'JSON'},
      {...field('readonly', true), type: 'DECIMAL', precision: 30, scale: 8},
      {...field('maskedDate', true), type: 'DATE'}, {...field('maskedBoolean', true), type: 'BOOLEAN'},
      {...field('maskedLong', true), type: 'INT64'}, {...field('maskedJson', true), type: 'JSON'},
      {...field('retiredChoice', true), choices: [{value: 'current', label: 'Current choice'}]},
      field('detail'), field('city'),
      {...field('note'), writePolicy: 'UNTIL_SUBMITTED'},
      {...field('code', true), writePolicy: 'CREATE_ONLY', defaultValue: {kind: 'STRING', value: 'CUSTOMER-001'}},
      {...field('approved'), type: 'BOOLEAN', defaultValue: {kind: 'BOOLEAN', value: 'false'},
        ...(writePolicyVersion > 1 ? {writePolicy: 'CREATE_ONLY'} : {}),
        choices: [{value: 'false', label: '待审核'}, {value: 'true', label: '已审核'}]},
      {...field('amount'), type: 'DECIMAL', precision: 30, scale: 8,
        defaultValue: {kind: 'DECIMAL', value: '9999999999999999999.123456780'},
        choices: [{value: '9999999999999999999.123456780', label: '精确金额'}]},
      {...field('serial'), type: 'INT64', defaultValue: {kind: 'INTEGER', value: '9223372036854775807'},
        choices: [{value: '9223372036854775807', label: '完整长整数'}]},
    ],
      scenes: [{sceneId: 'customer-main', code: 'main', name: 'Customer', type: 'MAIN',
        root: component('root', 'layout.form', null, {children: [
          component('name-input', 'field.text', 'name'),
          component('json-input', 'field.json', 'payload'),
          component('code-input', 'field.text', 'code'),
          component('approved-input', 'field.select', 'approved'),
          component('amount-input', 'field.select', 'amount'),
          component('serial-input', 'field.select', 'serial'),
          component('protected-input', 'field.number', 'readonly', {readOnly: true}),
          component('protected-date', 'field.date', 'maskedDate', {readOnly: true}),
          component('protected-boolean', 'field.switch', 'maskedBoolean', {readOnly: true}),
          component('protected-long', 'field.number', 'maskedLong', {readOnly: true}),
          component('protected-json', 'field.textarea', 'maskedJson', {readOnly: true}),
          component('protected-choice', 'field.select', 'retiredChoice', {readOnly: true}),
          component('open-address', 'action.open-dialog', null, {label: '编辑地址', targetSceneId: 'address'}),
          component('save', 'action.save'), component('submit', 'action.submit'),
        ]})},
        {sceneId: 'address', code: 'address', name: '地址设置', type: 'DIALOG', width: 720,
          root: component('address-root', 'layout.form', null, {children: [
            component('address-tabs', 'layout.tabs', null, {children: [
              component('address-group', 'layout.group', null, {label: '地址', children: [
                component('detail-input', 'field.text', 'detail'),
                component('open-city', 'action.open-drawer', null, {label: '选择城市', targetSceneId: 'city'}),
              ]}),
              component('note-group', 'layout.group', null, {label: '备注', children: [
                component('note-input', 'field.text', 'note'),
              ]}),
            ]}),
            component('address-save', 'action.save'),
          ]})},
        {sceneId: 'city', code: 'city', name: '城市设置', type: 'DRAWER', width: 480,
          root: component('city-root', 'layout.form', null, {children: [
            component('city-input', 'field.text', 'city'),
            component('city-code-input', 'field.text', 'code'),
          ]})},
      ],
      rules: [{ruleId: 'enable', targetComponentId: 'protected-input', effect: 'ENABLE',
        condition: {operator: 'IS_NOT_EMPTY', fieldId: 'name', values: [], children: []}}],
    }});
  return {writes, records, enable(id) {active = true; tenant = id;},
    loseSaveResponse() {failAfterCommit = true;}, reviseRecord(id, values) {const record = records.get(id); record.version++; Object.assign(record.values, values);},
    tightenPolicy() {writePolicyVersion++;}, publish() {version++; writePolicyVersion = Math.max(version, writePolicyVersion);},
    async handle(req, url, respond, expectedFailure) {
      if (!active || !url.pathname.startsWith(base)) return false;
      assert.equal(req.headers['x-tenant-id'], tenant);
      if (url.pathname === base) return respond(descriptor()), true;
      if (url.pathname.endsWith('/schema')) {
        return respond(fixtureSchema({name: {type: 'string'}, recordState: {type: 'string'}})), true;
      }
      let raw = ''; for await (const chunk of req) raw += chunk;
      const data = raw ? JSON.parse(raw) : {};
      if (url.pathname.endsWith('/query')) {
        return respond({page: data.page, size: data.size, total: records.size, records: [...records.values()]}), true;
      }
      writes.push({method: req.method, path: url.pathname, data});
      await new Promise(resolve => setTimeout(resolve, 250));
      if (data.runtime?.formId !== 'form-customer' || data.runtime?.formVersion !== version) {
        expectedFailure(409);
        return respond({code: 'RUNTIME_VERSION_CONFLICT', message: '表单版本已更新'}, 409), true;
      }
      assert.ok(!Object.hasOwn(data.values, 'readonly'), 'read-only fields must not be written');
      for (const id of ['maskedDate', 'maskedBoolean', 'maskedLong', 'maskedJson', 'retiredChoice'])
        assert.ok(!Object.hasOwn(data.values, id), 'display-only values never join a write');
      assert.ok(data.idempotencyKey, 'every write carries an operation key');
      const response = (receipt) => ({recordId: receipt.record.id, version: receipt.record.version,
        state: receipt.record.state, currentRecord: records.get(receipt.record.id) ?? null});
      const previous = receipts.get(data.idempotencyKey);
      if (previous) {
        assert.equal(previous.method, req.method); assert.equal(previous.path, url.pathname);
        assert.deepEqual(data, previous.request, 'uncertain writes retry exactly the original request');
        return respond(response(previous)), true;
      }
      if (req.method === 'PUT' && writePolicyVersion > 1 && Object.hasOwn(data.values, 'approved')) {
        expectedFailure(400);
        return respond({code: 'RECORD_INVALID', message: '字段写入规则已更新，请检查表单版本'}, 400), true;
      }
      let record;
      if (req.method === 'POST') {
        record = {id: `record-${records.size + 1}`, version: 0, state: data.state, values: {...data.values,
          readonly: '***', maskedDate: '***', maskedBoolean: '***', maskedLong: '***', maskedJson: '***', retiredChoice: 'retired'}};
        records.set(record.id, record);
      } else if (req.method === 'PUT') {
        record = records.get(url.pathname.split('/').at(-1));
        assert.ok(record); assert.equal(data.expectedVersion, record.version);
        assert.ok(!Object.hasOwn(data.values, 'code'), 'create-only field is absent from updates in every scene');
        if (record.state === 'SUBMITTED') assert.ok(!Object.hasOwn(data.values, 'note'), 'submitted fields remain locked');
        record.version++; record.state = data.state; Object.assign(record.values, data.values);
      } else throw Error(`Unexpected form record operation: ${req.method}`);
      const receipt = {method: req.method, path: url.pathname, request: structuredClone(data), record: structuredClone(record)};
      receipts.set(data.idempotencyKey, receipt);
      if (data.state === 'SUBMITTED') assert.ok(record.values.name?.trim());
      if (failAfterCommit) {
        failAfterCommit = false; expectedFailure(503);
        return respond({message: '保存响应暂时不可用'}, 503), true;
      }
      return respond(response(receipt)), true;
    }};
}
