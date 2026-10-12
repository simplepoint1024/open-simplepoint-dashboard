import assert from 'node:assert/strict';
import {test} from 'node:test';
import validator from '@rjsf/validator-ajv8';
import {createTranslator} from '../../shared/i18n/translator.ts';
import {localizeValidationErrors} from '../../shared/validation/messages.ts';

test('create and edit run the same schema rules, preserving field paths and safe localized instructions', () => {
  const schema = {type: 'object', required: ['code'], properties: {
    code: {type: 'string', title: '编号', minLength: 2, maxLength: 5, pattern: '^[A-Z]+$'},
    optional: {type: ['string', 'null'], minLength: 2}, count: {type: 'integer', minimum: 1, maximum: 10},
    email: {type: 'string', format: 'email'}, choices: {type: 'array', minItems: 1, maxItems: 2, uniqueItems: true, items: {type: 'string'}},
  }};
  const t = createTranslator({});
  const validate = data => validator.validateFormData(data, schema, undefined, errors => localizeValidationErrors(errors, t));
  assert.match(validate({}).errors[0].message, /请填写/);
  assert.equal(validate({code: 'OK'}).errors.length, 0, 'optional absent fields stay optional');
  assert.equal(validate({code: 'OK', optional: null}).errors.length, 0);
  for (const previous of [{}, {id: 'existing', code: 'OLD'}]) {
    const invalid = validate({...previous, code: 'bad!', count: 0, email: 'invalid', choices: ['x', 'x', 'x']});
    assert.ok(invalid.errors.some(error => error.name === 'pattern' && error.message.includes('格式')));
    assert.ok(invalid.errors.some(error => error.name === 'minimum' && error.message.includes('1')));
    assert.ok(invalid.errors.some(error => error.name === 'format' && error.message.includes('邮箱')));
    assert.ok(invalid.errors.every(error => !/must |bad!|\^\[A-Z\]/.test(error.message)));
    assert.ok(invalid.errors.some(error => error.property === '.code'));
    assert.equal(validate({...previous, code: 'OK', count: 1, email: 'a@b.test', choices: ['x']}).errors.length, 0);
  }
});

test('errors can be translated again without mutating the engine errors or exposing input data', () => {
  const raw = [{name: 'maxLength', property: '.rows.0.name', params: {limit: 4}, message: 'must NOT have more than 4 characters',
    schemaPath: '#/properties/rows/items/properties/name/maxLength', stack: 'old', title: 'i18n:field.name'}];
  const original = structuredClone(raw);
  const en = createTranslator({'form.validation.maxLength': 'Use at most {limit} characters', 'field.name': 'Name'});
  const localized = localizeValidationErrors(raw, en);
  assert.equal(localized[0].message, 'Use at most 4 characters');
  assert.equal(localized[0].stack, 'Name：Use at most 4 characters');
  assert.equal(localized[0].property, '.rows.0.name');
  assert.deepEqual(raw, original);
});

test('readonly/masked fields cannot block edits, while writable nested errors and annotation keys remain enforced', () => {
  const schema = {type: 'object', required: ['protected', 'name'], properties: {
    protected: {type: 'string', readOnly: true, pattern: '^[A-Z]+$'},
    name: {type: 'string', allOf: [{pattern: '^[A-Z]+$', 'x-validation-messages': {pattern: 'form.custom.uppercase'}}]},
  }};
  const t = createTranslator({'form.custom.uppercase': 'Use uppercase letters'});
  const validate = data => validator.validateFormData(data, schema, undefined, errors => localizeValidationErrors(errors, t, schema));
  assert.equal(validate({name: 'OK'}).errors.length, 0);
  assert.equal(validate({name: 'OK', protected: '***'}).errors.length, 0);
  const invalid = validate({name: 'bad', protected: '***'});
  assert.equal(invalid.errors.length, 1);
  assert.equal(invalid.errors[0].message, 'Use uppercase letters');
});

test('referenced schema errors respect the actual protected property, not only the shared definition', () => {
  const schema = {type: 'object', definitions: {name: {type: 'string', minLength: 5}}, properties: {
    protected: {$ref: '#/definitions/name', readOnly: true}, writable: {$ref: '#/definitions/name'},
  }};
  const result = validator.validateFormData({protected: '***', writable: 'x'}, schema, undefined,
    errors => localizeValidationErrors(errors, createTranslator({}), schema));
  assert.deepEqual(result.errors.map(error => error.property), ['.writable']);
});
