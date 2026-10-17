import type {ApplicationFormDocument} from '../../FormRuntime/contracts.ts';
import type {RecordState} from '../../FormRuntime/recordTableModel.ts';
import {fieldWriteAllowed} from '../../FormRuntime/writePolicy.ts';

/** Keep row identity/version while omitting columns the server marks non-editable. */
export function detailPayload(document: ApplicationFormDocument, values: Record<string, unknown>, state?: RecordState) {
  const result = {...values};
  for (const detail of document.details ?? []) {
    if (!Object.prototype.hasOwnProperty.call(values, detail.detailId)) continue;
    const rows = values[detail.detailId];
    result[detail.detailId] = rows == null ? [] : Array.isArray(rows) ? rows.map(row => ({
      ...(row.id == null ? {} : {id: row.id, version: row.version}),
      values: Object.fromEntries(detail.fields.filter(field => !detail.readOnlyFieldIds?.includes(field.fieldId)
        && fieldWriteAllowed(field, row.id == null ? undefined : state)
        && Object.prototype.hasOwnProperty.call(row.values ?? {}, field.fieldId))
        .map(field => [field.fieldId, row.values[field.fieldId]])),
    })) : rows;
  }
  return result;
}
