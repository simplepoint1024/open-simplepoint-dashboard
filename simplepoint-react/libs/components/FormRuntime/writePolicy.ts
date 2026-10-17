import type {FormFieldDefinition} from './contracts.ts';
import type {RecordState} from './recordTableModel.ts';

/** Uses the persisted session state, never the mutable values or requested submission state. */
export function fieldWriteAllowed(field: FormFieldDefinition, currentState?: RecordState) {
  if (field.access?.readOnly || (currentState === undefined ? field.access?.create === 'HIDDEN'
    : field.access?.edit === 'READ' || field.access?.edit === 'HIDDEN')) return false;
  switch (field.writePolicy) {
    case undefined: case null: case 'EDITABLE': return true;
    case 'CREATE_ONLY': return currentState === undefined;
    case 'UNTIL_SUBMITTED': return currentState !== 'SUBMITTED';
    default: return false;
  }
}

/** Operation visibility is independent of record detail/list read authorization. */
export function fieldOperationHidden(field: FormFieldDefinition, state?: RecordState) {
  return state === undefined ? field.access?.create === 'HIDDEN' : field.access?.edit === 'HIDDEN';
}
