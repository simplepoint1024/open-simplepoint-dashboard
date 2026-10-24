import {useMemo} from 'react';
import {post} from '@simplepoint/shared/api/methods';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import type {DesignDocument, DesignField} from '@simplepoint/components/FormDesigner/contracts';
import type {CalculationPreviewLoader, CalculationPreviewResult} from '@simplepoint/components/FormRuntime/calculationPreview';
import type {DesignerCalculationTrial} from '@simplepoint/components/FormDesigner/CalculationTrial';
import {calculationTrialInputs} from '@simplepoint/components/FormDesigner/calculationTrialModel';

export function useDesignerCalculationTrial(applicationId: string, formId: string, enabled: boolean): DesignerCalculationTrial | undefined {
  const scope = useQueryScope();
  return useMemo(() => enabled ? (document, samples, signal) => post<CalculationPreviewResult>(
    `/app/applications/${encodeURIComponent(applicationId)}/forms/${encodeURIComponent(formId)}/calculations/preview`,
    {document, ...calculationTrialInputs(document, samples)}, {signal, notifyOnError: false}) : undefined,
  [applicationId, formId, enabled, scope]);
}

export default function useDesignerCalculationPreview(applicationId: string, formId: string, document: DesignDocument,
  enabled: boolean): CalculationPreviewLoader | undefined {
  const scope = useQueryScope();
  return useMemo(() => {
    if (!enabled) return undefined;
    const inputs = (fields: DesignField[], values: Record<string, unknown>) => Object.fromEntries(fields
      .filter(field => ['STORED', 'REFERENCE', 'ATTACHMENT'].includes(field.storageMode) && Object.prototype.hasOwnProperty.call(values, field.fieldId))
      .map(field => [field.fieldId, values[field.fieldId]]));
    return async (values, signal) => post<CalculationPreviewResult>(
      `/app/applications/${encodeURIComponent(applicationId)}/forms/${encodeURIComponent(formId)}/calculations/preview`,
      {document, values: inputs(document.fields, values), details: Object.fromEntries((document.details ?? []).map(detail => {
        const rows = values[detail.detailId];
        return [detail.detailId, Array.isArray(rows) ? rows.map(row => inputs(detail.fields, row.values ?? {})) : []];
      }))}, {signal, notifyOnError: false});
  }, [applicationId, formId, document, enabled, scope]);
}
