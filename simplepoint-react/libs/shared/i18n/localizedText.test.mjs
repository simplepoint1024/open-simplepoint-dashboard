import assert from 'node:assert/strict';
import {test} from 'node:test';
import {canonicalLocale, editLocalizedText, inheritLocalizedText, localizeRouteNames, localizedTextValid, resolveLocalizedText} from './localizedText.ts';
const value = {defaultValue: '客户', defaultLocale: 'zh-CN', translations: {'zh-CN': '客户', en: 'Customers', 'en-GB': 'Clients'}};

test('tenant locale and language overrides precede platform inheritance and preserve literal keys', () => {
  assert.deepEqual(resolveLocalizedText(value, 'en-GB', 'Platform'), {text:'Clients',source:'TENANT_CUSTOM'});
  assert.equal(resolveLocalizedText(value, 'en-US', 'Platform').text, 'Customers');
  assert.deepEqual(resolveLocalizedText(value, 'ja-JP', '顧客'), {text:'顧客',source:'PLATFORM_INHERITED'});
  assert.equal(resolveLocalizedText(value, 'ja-JP').text, '客户');
  assert.equal(resolveLocalizedText({...value, translations: {'en-US':'menu.home'}}, 'en-US').text, 'menu.home');
});
test('editing another language preserves the opening draft; restore removes only selected override', () => {
  const original = structuredClone(value), edited = editLocalizedText(value, 'ja_JP', '顧客');
  assert.deepEqual(value,original); assert.equal(edited.defaultValue,'客户'); assert.equal(edited.translations['ja-JP'],'顧客');
  assert.deepEqual(inheritLocalizedText(edited,'ja-JP'),value);
  const restored = inheritLocalizedText(value,'zh-CN','客户资料');
  assert.equal(restored.defaultValue,'客户资料'); assert.equal(restored.translations.en,'Customers');
  assert.equal(resolveLocalizedText(restored,'zh-CN','客户资料').source,'PLATFORM_INHERITED');
});
test('validation counts code points and rejects ambiguous, extended or unbounded language maps', () => {
  assert.equal(localizedTextValid({...value, defaultValue:'😀'.repeat(100)},100),true);
  assert.equal(localizedTextValid({...value, defaultValue:'😀'.repeat(101)},100),false);
  for (const translations of [{'en-US':'A',en_US:'B'}, {en:' '}, {'en-u-ca-gregory':'A'}])
    assert.equal(localizedTextValid({...value,translations}),false);
  assert.equal(localizedTextValid(value,255,1),false);
  assert.equal(canonicalLocale('zh_Hant_TW'),'zh-Hant-TW'); assert.equal(canonicalLocale('sl-rozaj'),'sl-rozaj');
});
test('localized navigation preserves route identities and its cached source', () => {
  const routes=[{id:'a',title:'menu.customer',label:'旧名称',path:'/customer',code:'customers',nameLocalization:value,
    children:[{id:'b',title:'menu.detail',label:'明细',path:'/customer/detail'}]}];
  const original=structuredClone(routes),english=localizeRouteNames(routes,'en-GB'),japanese=localizeRouteNames(routes,'ja-JP');
  assert.deepEqual(routes,original); assert.equal(english[0].label,'Clients'); assert.equal(english[0].title,'');
  assert.equal(english[0].path,routes[0].path); assert.equal(english[0].code,routes[0].code);
  assert.equal(japanese[0].title,'menu.customer'); assert.equal(japanese[0].label,'客户');
  assert.deepEqual(english[0].children,routes[0].children);
});

test('masked route and list projections never dereference or reveal translation data', () => {
  assert.deepEqual(resolveLocalizedText('***','en-US',undefined,'TENANT','***'),{text:'***',source:'DEFAULT'});
  assert.deepEqual(localizeRouteNames([{id:'a',label:'***',nameLocalization:'***'}],'en-US'),[{id:'a',label:'***',nameLocalization:'***'}]);
});
