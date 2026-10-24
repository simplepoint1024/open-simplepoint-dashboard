import {test} from 'node:test';
import assert from 'node:assert/strict';
import {localizeFormDocument} from './localization.ts';
import {localizedDraft, changedLocalizedDraft, localizeSchemaTitles} from '../../shared/i18n/localizedSchema.ts';
const translation = {defaultValue: '客户', defaultLocale: 'zh-CN', translations: {en: 'Customer', 'ja-JP': '顧客'}};
const component = (id, fieldId, label, labelLocalization) => ({componentId: id, componentType: fieldId ? 'field.input' : 'layout.form', componentVersion: '1', fieldId, label,
  ...(labelLocalization ? {labelLocalization} : {}), columnSpan: 24, readOnly: false, properties: {}, children: []});
test('ordinary, computed, detail and choice labels switch without mutating the published snapshot', () => {
  const field = {fieldId: 'customer-id', label: '客户', labelLocalization: translation, type: 'STRING', submitRequired: true,
    choices: [{value: 'stable-value', label: '客户', labelLocalization: translation}], calculated: true};
  const root = {...component('root'), children: [component('a', 'customer-id', '客户'), component('b', 'customer-id', '场景别名'),
    component('c', 'customer-id', '客户', {...translation, translations: {en: 'Scene title'}})]};
  const document = {contractVersion: '1', fields: [field], details: [{detailId: 'detail', label: '客户', labelLocalization: translation, fields: [field]}],
    scenes: [{sceneId: 'main', code: 'main', name: 'Main', type: 'MAIN', root}], rules: []};
  const before = JSON.stringify(document), english = localizeFormDocument(document, 'en-US');
  assert.equal(english.fields[0].label, 'Customer');
  assert.equal(english.fields[0].choices[0].value, 'stable-value');
  assert.equal(english.fields[0].choices[0].label, 'Customer');
  assert.equal(english.details[0].fields[0].label, 'Customer');
  assert.deepEqual(english.scenes[0].root.children.map(node => node.label), ['Customer', '场景别名', 'Scene title']);
  assert.equal(localizeFormDocument(document, 'ja-JP').fields[0].label, '顧客');
  assert.equal(localizeFormDocument(document, 'fr-FR').fields[0].label, '客户');
  assert.equal(JSON.stringify(document), before);
});
test('opening and saving legacy field settings does not materialize optional metadata', () => {
  const draft = localizedDraft('Existing', undefined, 'en-US');
  assert.equal(changedLocalizedDraft('Existing', undefined, draft, 'en-US'), undefined);
  assert.equal(changedLocalizedDraft('Existing', undefined, {...draft, translations: {ja: '既存'}}, 'en-US').translations.ja, '既存');
});
test('schema localization is recursive, literal and independent from cached query values', () => {
  const schema = {properties: {id: {title: '客户', 'x-title-localization': translation, oneOf: [{const: 'x', title: '客户', 'x-title-localization': translation}]}}};
  const result = localizeSchemaTitles(schema, 'en-US');
  assert.equal(result.properties.id.title, 'Customer');
  assert.equal(result.properties.id.oneOf[0].title, 'Customer');
  assert.equal(schema.properties.id.title, '客户');
});
