import type {ApplicationFormDocument} from './contracts';
import type {RecordRow} from './recordTableModel';
import type {SimpleTableColumnOverride} from '../SimpleTable/types';
import FormReferenceValue from '../FormComponents/Reference/Value';
import {ReferenceActionsScope} from '../FormComponents/Reference/NavigationContext';

/** Presentation only: the authorized server schema still owns visibility, query fields and paging. */
export function referenceColumns(document: ApplicationFormDocument, actionsDisabled = false): Record<string, SimpleTableColumnOverride<RecordRow>> {
  return Object.fromEntries(document.fields.filter(field => field.reference).map(field => [field.fieldId, {
    render: (value: unknown) => <ReferenceActionsScope disabled={actionsDisabled}>
      <FormReferenceValue value={value} relationId={field.reference!.relationId}/>
    </ReferenceActionsScope>,
  }]));
}
