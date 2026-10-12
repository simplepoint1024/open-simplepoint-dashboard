import assert from 'node:assert/strict';
import {test} from 'node:test';
import {serverViolations, serverErrorSchema, serverValidationMessage} from './server.ts';
import {createTranslator} from '../i18n/translator.ts';

test('server validation attaches localized errors only to visible editable fields and never prototype paths', () => {
  const payload = {code: 'VALIDATION_FAILED', fieldErrors: [
    {field: 'name', keyword: 'required', params: {rejectedValue: 'secret'}, messageKey: 'form.custom.name'},
    {field: 'rows[0].code', keyword: 'pattern', params: {}},
    {field: 'protected', keyword: 'pattern', params: {}},
    {field: 'invisible', keyword: 'required', params: {}},
    {field: '__proto__.polluted', keyword: 'required', params: {}},
  ]};
  const issues = serverViolations(payload), t = createTranslator({'form.custom.name': 'Please enter a name'});
  const schema = {properties: {name: {}, protected: {readOnly: true}, rows: {type: 'array', items: {properties: {code: {}}}}}};
  assert.deepEqual(serverErrorSchema(issues, schema, t), {
    name: {__errors: ['Please enter a name']}, rows: {0: {code: {__errors: ['格式不符合要求，请参考字段说明']}}},
  });
  assert.equal({}.polluted, undefined);
  assert.ok(!JSON.stringify(issues).includes('secret'));
  assert.equal(serverValidationMessage({code: 'OTHER', message: 'raw'}, t), undefined);
  assert.match(serverValidationMessage(payload, t), /Please enter a name/);
});

test('local referenced and nullable-array fields receive safe errors without crossing protected parents', () => {
  const schema = {definitions: {row: {properties: {code: {type: 'string'}}}}, properties: {
    rows: {type: ['array', 'null'], items: {$ref: '#/definitions/row'}},
    protected: {$ref: '#/definitions/row', readOnly: true},
  }};
  const violations = ['rows[0].code', 'protected.code'].map(field => ({field, keyword: 'required', params: {}}));
  assert.deepEqual(serverErrorSchema(violations, schema, createTranslator({})), {rows: {0: {code: {__errors: ['请填写此项']}}}});
});
