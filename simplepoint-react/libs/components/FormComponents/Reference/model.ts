import type {DesignDocument, DesignField} from '../../FormDesigner/contracts.ts';
import type {FormComponentDefinition} from '../../FormRuntime/contracts.ts';

export const REFERENCE = 'field.reference';
export const initializeReferenceField = (field: DesignField): DesignField => ({...field, type: 'UUID', storageMode: 'REFERENCE'});
export function validateReferenceComponent(node: FormComponentDefinition, document: DesignDocument) {
  const field = document.fields.find(field => field.fieldId === node.fieldId);
  // A newly inserted draft may remain unconfigured; publication must require a complete relation.
  if (!field || field.type !== 'UUID' || field.storageMode !== 'REFERENCE' || field.calculation || field.choices.length
    || field.defaultValue != null && field.defaultValue.kind !== 'NULL') throw new Error('Invalid business reference field');
}
