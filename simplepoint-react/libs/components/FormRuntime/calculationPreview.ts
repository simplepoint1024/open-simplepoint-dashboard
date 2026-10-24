import type {ApplicationFormDocument} from './contracts.ts';
import type {FormValues} from './sceneState.ts';

export type CalculationPreviewResult = {values: FormValues; details: Record<string, FormValues[]>};
export type CalculationPreviewLoader = (values: FormValues, signal: AbortSignal) => Promise<CalculationPreviewResult>;
export const hasCalculatedFields = (document: ApplicationFormDocument) => document.fields.some(field => field.calculated)
  || Boolean(document.details?.some(detail => detail.fields.some(field => field.calculated)));

/** Never reuse an old computed value as an input or echo it back as a user-written value. */
export function calculationInputs(document: ApplicationFormDocument, values: FormValues): FormValues {
  const result = {...values};
  for (const field of document.fields) if (field.calculated) delete result[field.fieldId];
  for (const detail of document.details ?? []) {
    const rows = values[detail.detailId];
    if (!Array.isArray(rows)) continue;
    result[detail.detailId] = rows.map(row => {
      const fields = {...row.values};
      for (const field of detail.fields) if (field.calculated) delete fields[field.fieldId];
      return {...row, values: fields};
    });
  }
  return result;
}

/** Preview positions refer only to the exact request. Preserve all user edits and row identities. */
export function mergeCalculationPreview(document: ApplicationFormDocument, values: FormValues,
  preview?: CalculationPreviewResult): FormValues {
  const result = {...values};
  for (const field of document.fields) if (field.calculated) result[field.fieldId] = preview?.values[field.fieldId] ?? null;
  for (const detail of document.details ?? []) {
    if (!detail.fields.some(field => field.calculated)) continue;
    const rows = values[detail.detailId];
    if (!Array.isArray(rows)) continue;
    const outputs = preview?.details[detail.detailId];
    if (preview && (!outputs || rows.length !== outputs.length)) throw new Error('Calculation preview row mismatch');
    result[detail.detailId] = rows.map((row, index) => ({...row, values: {...row.values,
      ...Object.fromEntries(detail.fields.filter(field => field.calculated).map(field => [field.fieldId, outputs?.[index][field.fieldId] ?? null])),
    }}));
  }
  return result;
}
