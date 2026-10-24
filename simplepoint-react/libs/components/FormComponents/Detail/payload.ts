import type {ApplicationFormDocument} from '../../FormRuntime/contracts.ts';
import type {RecordState} from '../../FormRuntime/recordTableModel.ts';
import {fieldWriteAllowed} from '../../FormRuntime/writePolicy.ts';

/** Read envelopes also contain server audit metadata. Only the row editing contract enters SForm. */
export function detailFormValues(document: ApplicationFormDocument, values: Record<string, unknown>) {
  const result = {...values};
  for (const detail of document.details ?? []) {
    const rows = values[detail.detailId];
    if (!Array.isArray(rows)) continue;
    result[detail.detailId] = rows.map(row => row && typeof row === 'object' && !Array.isArray(row) ? {
      ...(Object.prototype.hasOwnProperty.call(row, 'id') ? {id: row.id} : {}),
      ...(Object.prototype.hasOwnProperty.call(row, 'version') ? {version: row.version} : {}),
      values: row.values,
    } : row);
  }
  return result;
}

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
