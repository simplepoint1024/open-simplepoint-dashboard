import test from 'node:test';
import assert from 'node:assert/strict';
import {sameDesignDocument} from './draftState.ts';

const relation = (id, target = 'customers') => ({contractVersion: '1', relationId: id, cardinality: 'MANY_TO_ONE',
  sourceField: {fieldId: id}, target: {applicationId: 'app', formId: target, accessResourceId: 'customer-page'}});
const document = {contractVersion: '1', fields: [], indexes: [], scenes: [], rules: []};

test('lost-save reconciliation preserves relation identity without treating metadata order as an edit', () => {
  assert.equal(sameDesignDocument(document, {...document, relations: []}), true);
  const first = {...document, relations: [relation('customer'), relation('supplier')]};
  const reordered = {...document, relations: [...first.relations].reverse()};
  assert.equal(sameDesignDocument(first, reordered), true);
  assert.equal(sameDesignDocument(first, {...first, relations: [relation('customer', 'other-form'), relation('supplier')]}), false);
  assert.equal(sameDesignDocument(first, {...first, relations: [relation('customer')]}), false);
  const moved = structuredClone(first);
  moved.relations[0].sourceField.detailId = 'lines';
  assert.equal(sameDesignDocument(first, moved), false);
});

test('changing qualification or submission recheck is an unsaved business policy change', () => {
  const initial = {...document, relations: [relation('customer')]};
  const qualified = structuredClone(initial);
  qualified.relations[0].qualification = {condition: {operator: 'EQUALS', fieldId: 'active',
    values: [{kind: 'BOOLEAN', value: 'true'}], children: []}, recheck: 'ON_CHANGE'};
  assert.equal(sameDesignDocument(initial, qualified), false);
  assert.equal(sameDesignDocument(qualified, structuredClone(qualified)), true);
  const submitting = structuredClone(qualified);
  submitting.relations[0].qualification.recheck = 'ON_SUBMIT';
  assert.equal(sameDesignDocument(qualified, submitting), false);
});
