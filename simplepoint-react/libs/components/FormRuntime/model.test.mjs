import assert from 'node:assert/strict';
import {test} from 'node:test';
import validator from '@rjsf/validator-ajv8';
import {runtimeFormModel} from './model.ts';

const value = (kind, raw) => ({kind, value: raw});
const field = (fieldId, type = 'STRING', required = false) => ({
  fieldId, label: fieldId, type, submitRequired: required,
  length: type === 'STRING' ? 120 : null, precision: type === 'DECIMAL' ? 18 : null,
  scale: type === 'DECIMAL' ? 2 : null, defaultValue: null, choices: [],
});
const component = (componentId, fieldId, componentType = 'field.text', columnSpan = 24) => ({
  componentId, componentType, componentVersion: '1', fieldId, columnSpan,
  label: null, description: null, placeholder: null, targetSceneId: null,
  readOnly: false, properties: {}, children: [],
});
const document = (rules = []) => ({
  contractVersion: '1', fields: [field('kind'), field('name', 'STRING', true), field('amount', 'DECIMAL')],
  rules, scenes: [{sceneId: 'main', code: 'main', name: 'Main', type: 'MAIN', width: null,
    root: {...component('root', null, 'layout.form'), children: [
      component('kind-control', 'kind', 'field.select', 12),
      component('name-control', 'name', 'field.text', 12),
      component('amount-control', 'amount', 'field.number', 24),
    ]}}],
});

test('compiles fields in scene order and retains platform decimal semantics', () => {
  const model = runtimeFormModel(document(), 'main');
  assert.deepEqual(model.uiSchema['ui:order'], ['kind', 'name', 'amount', '*']);
  assert.deepEqual(model.schema.required, ['name']);
  assert.equal(model.schema.properties.amount['x-ui'].widget, 'Decimal');
  assert.equal(model.uiSchema.amount['ui:widget'], 'Decimal');
  assert.deepEqual(model.layout.map(item => item.columnSpan), [12, 12, 24]);
});

test('numeric inputs and defaults use the platform exact-string validation contract', () => {
  const doc = document();
  const amount = doc.fields[2];
  Object.assign(amount, {precision: 38, scale: 18, defaultValue: value('DECIMAL', '12345678901234567890.123456789012345678')});
  const schema = runtimeFormModel(doc, 'main').schema.properties.amount;
  assert.deepEqual(schema.type, ['string', 'null']);
  assert.equal(schema.default, '12345678901234567890.123456789012345678');
  assert.equal(schema['x-decimal'].maximum, '99999999999999999999.999999999999999999');
  amount.type = 'INT64'; amount.defaultValue = value('INTEGER', '9223372036854775807');
  const long = runtimeFormModel(doc, 'main');
  assert.deepEqual(long.schema.properties.amount.type, ['string', 'null']);
  assert.equal(long.schema.properties.amount.default, '9223372036854775807');
  assert.equal(long.schema.properties.amount['x-decimal'].minimum, '-9223372036854775808');
  assert.equal(long.uiSchema.amount['ui:widget'], 'Decimal');
});

test('readonly numeric and historical choice values use display-only validation without granting writes', () => {
  const doc = document();
  doc.scenes[0].root.children[2].readOnly = true;
  doc.fields[2].submitRequired = true;
  doc.fields[2].choices = [{value: '1.25', label: 'Current option'}];
  const model = runtimeFormModel(doc, 'main', {amount: '***'});
  assert.equal(model.schema.properties.amount['x-display-only'], true);
  assert.equal(model.schema.properties.amount.readOnly, true);
  assert.equal(model.schema.required.includes('amount'), false);
  assert.equal(model.editableFieldIds.includes('amount'), false);
  assert.equal(model.schema.properties.name['x-display-only'], undefined);
  assert.equal(model.schema.required.includes('name'), true);
});

test('evaluates bounded visibility, editability and required rules without expressions', () => {
  const rules = [
    {ruleId: 'show-name', targetComponentId: 'name-control', effect: 'SHOW',
      condition: {operator: 'EQUALS', fieldId: 'kind', values: [value('STRING', 'person')], children: []}},
    {ruleId: 'require-amount', targetComponentId: 'amount-control', effect: 'REQUIRE',
      condition: {operator: 'GREATER_THAN', fieldId: 'amount', values: [value('DECIMAL', '10')], children: []}},
    {ruleId: 'disable-kind', targetComponentId: 'kind-control', effect: 'DISABLE',
      condition: {operator: 'IS_NOT_EMPTY', fieldId: 'name', values: [], children: []}},
  ];
  const hidden = runtimeFormModel(document(rules), 'main', {kind: 'company', amount: '9'});
  assert.equal(hidden.uiSchema.name['ui:widget'], 'hidden');
  assert.deepEqual(hidden.schema.required ?? [], []);

  const visible = runtimeFormModel(document(rules), 'main', {kind: 'person', name: 'Ada', amount: '11'});
  assert.notEqual(visible.uiSchema.name['ui:widget'], 'hidden');
  assert.equal(visible.uiSchema.kind['ui:disabled'], true);
  assert.deepEqual(visible.schema.required, ['name', 'amount']);
});

test('fails closed when a published scene identity is absent', () => {
  assert.throws(() => runtimeFormModel(document(), 'missing'), /scene is unavailable/);
});

test('keeps JSON defaults compatible with the textarea string contract', () => {
  const jsonField = {
    ...field('payload', 'JSON'),
    defaultValue: value('JSON', '{"enabled":true}'),
  };
  const jsonDocument = {
    contractVersion: '1',
    fields: [jsonField],
    rules: [],
    scenes: [{
      sceneId: 'main', code: 'main', name: 'Main', type: 'MAIN', width: null,
      root: {...component('root', null, 'layout.form'), children: [
        component('payload-control', 'payload', 'field.textarea'),
      ]},
    }],
  };

  const model = runtimeFormModel(jsonDocument, 'main');

  assert.equal(model.schema.properties.payload.default, '{"enabled":true}');
  assert.equal(model.uiSchema.payload['ui:widget'], 'textarea');
});

test('separates draft validation from submit validation and compiles scene actions', () => {
  const source = document();
  source.scenes[0].root.children.push(
    {...component('save-action', null, 'action.save'), label: 'Save draft'},
    {...component('submit-action', null, 'action.submit'), label: 'Submit'},
    component('reset-action', null, 'action.reset'),
  );

  const draft = runtimeFormModel(source, 'main', {}, {enforceRequired: false});
  const submitted = runtimeFormModel(source, 'main', {}, {enforceRequired: true});

  assert.deepEqual(draft.schema.required ?? [], []);
  assert.deepEqual(submitted.schema.required, ['name']);
  assert.deepEqual(draft.actions, [
    {id: 'save-action', key: 'save', label: 'Save draft', targetSceneId: undefined, hidden: false, disabled: false},
    {id: 'submit-action', key: 'submit', label: 'Submit', targetSceneId: undefined, hidden: false, disabled: false},
    {id: 'reset-action', key: 'reset', label: undefined, targetSceneId: undefined, hidden: false, disabled: false},
  ]);
});

test('business rules cannot grant edit access to a read-only field', () => {
  for (const effect of ['ENABLE', 'DISABLE']) {
    const source = document([{ruleId: 'rule', targetComponentId: 'name-control', effect,
      condition: {operator: 'IS_NOT_EMPTY', fieldId: 'kind', values: [], children: []}}]);
    source.scenes[0].root.children[1].readOnly = true;
    for (const kind of ['', 'person']) {
      assert.equal(runtimeFormModel(source, 'main', {kind}).uiSchema.name['ui:disabled'], true);
    }
  }
});

test('rule values preserve exact decimal precision and server-compatible empty semantics', () => {
  const required = (operator, literal, actual, expectedRequired) => {
    const source = document([{ruleId: 'numeric', targetComponentId: 'amount-control', effect: 'REQUIRE',
      condition: {operator, fieldId: 'amount', values: literal ? [value('DECIMAL', literal)] : [], children: []}}]);
    assert.equal((runtimeFormModel(source, 'main', {amount: actual}).schema.required ?? []).includes('amount'), expectedRequired);
  };
  required('EQUALS', '1.00', '1.0', true);
  required('GREATER_THAN', '9007199254740992', '9007199254740993', true);
  required('EQUALS', '9007199254740992', '9007199254740993', false);
  required('IS_EMPTY', undefined, '   ', true);
});

test('container rules propagate to fields and positioned actions without losing nested layouts', () => {
  const source = document([{ruleId: 'group-hide', targetComponentId: 'details', effect: 'HIDE',
    condition: {operator: 'EQUALS', fieldId: 'kind', values: [value('STRING', 'hide')], children: []}}]);
  const main = source.scenes[0].root;
  main.children = [main.children[0], {...component('details', null, 'layout.group'), label: 'Details',
    children: [main.children[1], {...component('open-address', null, 'action.open-dialog'), targetSceneId: 'address'}]}];
  const hidden = runtimeFormModel(source, 'main', {kind: 'hide'});
  assert.equal(hidden.layout[1].kind, 'container');
  assert.equal(hidden.layout[1].hidden, true);
  assert.equal(hidden.actions[0].hidden, true);
  assert.deepEqual(hidden.schema.required ?? [], []);
  main.children[1].readOnly = true;
  const visible = runtimeFormModel(source, 'main', {kind: 'show'});
  assert.equal(visible.uiSchema.name['ui:disabled'], true);
  assert.equal(visible.actions[0].disabled, true);
});

test('component versions are resolved explicitly and repeated action kinds keep separate identities', async () => {
  const source = document();
  source.scenes[0].root.children.push(
    {...component('open-a', null, 'action.open-dialog'), targetSceneId: 'a'},
    {...component('open-b', null, 'action.open-dialog'), targetSceneId: 'b'},
  );
  assert.deepEqual(runtimeFormModel(source, 'main').actions.map(action => action.targetSceneId), ['a', 'b']);
  source.scenes[0].root.children[0].componentVersion = '99';
  assert.throws(() => runtimeFormModel(source, 'main'), /Unsupported form component renderer/);
  const {FormComponentRegistry, platformFormComponents} = await import('./componentRegistry.ts');
  const registry = new FormComponentRegistry([...platformFormComponents.descriptors(),
    {type: 'field.select', version: '99', kind: 'field', widget: 'radio'}]);
  assert.equal(runtimeFormModel(source, 'main', {}, {registry}).uiSchema.kind['ui:widget'], 'radio');
  assert.throws(() => new FormComponentRegistry([...registry.descriptors(), registry.descriptors()[0]]), /Duplicate/);
});


test('persisted write policies override enable rules and apply to every scene', () => {
  const design = document([{ruleId: 'enable', targetComponentId: 'name-control', effect: 'ENABLE',
    condition: {operator: 'IS_NOT_EMPTY', fieldId: 'kind', values: [], children: []}}]);
  design.fields.find(field => field.fieldId === 'name').writePolicy = 'CREATE_ONLY';
  design.fields.find(field => field.fieldId === 'amount').writePolicy = 'UNTIL_SUBMITTED';
  const create = runtimeFormModel(design, 'main', {kind: 'person'}, {enforceRequired: false});
  assert.deepEqual(create.schema.required, ['name'], 'required create-only values cannot be deferred');
  assert.equal(create.uiSchema.name['ui:disabled'], undefined);
  const draft = runtimeFormModel(design, 'main', {kind: 'person'}, {recordState: 'DRAFT'});
  assert.equal(draft.uiSchema.name['ui:disabled'], true);
  assert.equal(draft.uiSchema.amount['ui:disabled'], undefined);
  const submitted = runtimeFormModel(design, 'main', {kind: 'person'}, {recordState: 'SUBMITTED'});
  assert.equal(submitted.uiSchema.amount['ui:disabled'], true);
  assert.ok(!submitted.editableFieldIds.includes('name'));
  design.scenes.push({...structuredClone(design.scenes[0]), sceneId: 'drawer', type: 'DRAWER'});
  assert.equal(runtimeFormModel(design, 'drawer', {kind: 'person'}, {recordState: 'DRAFT'}).uiSchema.name['ui:disabled'], true);
});

test('conditional create-only requirements are enforced before the first draft is saved', () => {
  const design = document([{ruleId: 'require', targetComponentId: 'name-control', effect: 'REQUIRE',
    condition: {operator: 'EQUALS', fieldId: 'kind', values: [value('STRING', 'person')], children: []}}]);
  Object.assign(design.fields.find(field => field.fieldId === 'name'), {writePolicy: 'CREATE_ONLY', submitRequired: false});
  const draftOptions = {enforceRequired: false};
  assert.deepEqual(runtimeFormModel(design, 'main', {kind: 'person'}, draftOptions).schema.required, ['name']);
  assert.deepEqual(runtimeFormModel(design, 'main', {kind: 'company'}, draftOptions).schema.required ?? [], []);
  design.rules.push({ruleId: 'hide', targetComponentId: 'root', effect: 'HIDE',
    condition: {operator: 'EQUALS', fieldId: 'kind', values: [value('STRING', 'person')], children: []}});
  assert.deepEqual(runtimeFormModel(design, 'main', {kind: 'person'}, draftOptions).schema.required ?? [], []);
  design.rules.pop();
  const existing = runtimeFormModel(design, 'main', {kind: 'person', name: 'Ada'}, {...draftOptions, recordState: 'DRAFT'});
  assert.equal(existing.uiSchema.name['ui:disabled'], true);
  assert.deepEqual(existing.schema.required ?? [], []);
});


test('optional stored nulls can be edited without replacing defaults or relaxing required fields', () => {
  const types = ['STRING', 'TEXT', 'BOOLEAN', 'INT32', 'INT64', 'DECIMAL', 'DATE', 'TIME', 'DATETIME', 'UUID', 'JSON'];
  for (const type of types) {
    const doc = document();
    doc.fields = [field('value', type)];
    doc.fields[0].defaultValue = value(type === 'JSON' ? 'JSON' : 'STRING', type === 'JSON' ? '{}' : 'default');
    doc.scenes[0].root.children = [component('input', 'value', type === 'JSON' ? 'field.json' : 'field.text')];
    for (const choices of type === 'STRING' ? [[], [{value: 'allowed', label: 'Allowed'}]] : [[]]) {
      doc.fields[0].choices = choices;
      const optional = runtimeFormModel(doc, 'main', {value: null});
      const data = {value: null};
      assert.equal(validator.validateFormData(data, optional.schema).errors.length, 0, type);
      assert.equal(data.value, null, 'SQL NULL must not become the historical default');
      doc.fields[0].submitRequired = true;
      const required = runtimeFormModel(doc, 'main', {value: null});
      assert(validator.validateFormData({value: null}, required.schema).errors.length > 0, type);
      doc.fields[0].submitRequired = false;
    }
  }
});

test('immutable workflow snapshots render exact and masked fields as display values without edit validation', () => {
  const doc = document();
  const model = runtimeFormModel(doc, 'main', {name: '原始申请', amount: '***'}, {readOnly: true, recordState: 'SUBMITTED'});
  assert.deepEqual(model.editableFieldIds, []);
  assert.deepEqual(model.schema.required ?? [], []);
  assert.equal(model.schema.properties.amount['x-display-only'], true);
  assert.equal(model.schema.properties.name['x-display-only'], true);
});

test('readonly snapshots exclude detail aggregates from write projection while retaining their layout', () => {
  const doc = document();
  doc.details = [{detailId: 'lines', label: '明细', fields: [field('item')]}];
  doc.scenes[0].root.children.push({...component('detail', null, 'data.detail'), properties: {detailId: value('STRING', 'lines')}});
  const snapshot = {name: '申请', lines: [{id: 'row-1', version: 1, values: {item: '原始明细'}}]};
  const model = runtimeFormModel(doc, 'main', snapshot, {readOnly: true, recordState: 'SUBMITTED'});
  assert.deepEqual(model.editableFieldIds, []);
  assert.equal(model.uiSchema.lines['ui:options'].readOnly, true);
  assert.equal(model.uiSchema.lines['ui:readonly'], true);
  assert(model.layout.some(item => item.kind === 'field' && item.fieldId === 'lines'));
  assert(runtimeFormModel(doc, 'main', snapshot).editableFieldIds.includes('lines'));
});
