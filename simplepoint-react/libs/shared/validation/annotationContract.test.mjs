import {readFileSync} from 'node:fs';
import assert from 'node:assert/strict';
import {test} from 'node:test';
import validator from '@rjsf/validator-ajv8';
import {localizeValidationErrors} from './messages.ts';
import {createTranslator} from '../i18n/translator.ts';

const contract = JSON.parse(readFileSync(new URL('../../../scripts/form-validation-contract.fixture.json', import.meta.url), 'utf8'));
test('real generated Java annotations and server verdicts agree with the actual browser validator', () => {
  for (const item of contract.cases) {
    for (const mode of ['add', 'edit']) {
      const result = validator.validateFormData({...item.data}, contract.schema, undefined,
        errors => localizeValidationErrors(errors, createTranslator({}), contract.schema));
      assert.equal(result.errors.length === 0, item.valid, `${mode}: ${JSON.stringify(item.data)}`);
      assert.ok(result.errors.every(error => !error.message.includes('must ')));
    }
  }
  assert.equal(contract.schema.properties.initialCount.default, 3);
  assert.equal(contract.schema.properties.initialEnabled.default, true);
  assert.deepEqual(contract.schema.properties.serverOnlyPattern['x-server-validation'], ['pattern']);
  assert.equal(contract.schema.properties.serverOnlyPattern.pattern, undefined, 'Java-only flags never silently change meaning in JavaScript');
});
