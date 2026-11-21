import {useMemo} from 'react';
import {post} from '@simplepoint/shared/api/methods';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import type {ApplicationFormRuntimeView} from './contracts';
import {detailPayload} from '../FormComponents/Detail/payload';
import {editableFieldIds, editableValues, runtimeIdentity, type RecordRow, type RecordState} from './recordTableModel';
import {hasPreviewFields, type CalculationPreviewLoader, type CalculationPreviewResult} from './calculationPreview';

/** Published-page adapter; the standalone calculator and generic form renderer own no HTTP endpoints. */
export function useRecordCalculationPreview(descriptor: ApplicationFormRuntimeView,
  record?: RecordRow | null, state?: RecordState, fixedReferenceField?: string): CalculationPreviewLoader | undefined {
  const scope = useQueryScope();
  const {id, version, recordState} = record ?? {};
  return useMemo(() => {
    if (!hasPreviewFields(descriptor.document)) return undefined;
    const runtime = runtimeIdentity(descriptor);
    const endpoint = `/app/runtime/applications/${encodeURIComponent(descriptor.applicationId)}/pages/${encodeURIComponent(descriptor.pageId)}/records/preview`;
    return async (values, signal, referenceMappings) => {
      const inputs = editableValues(values, editableFieldIds(descriptor, undefined, recordState), record);
      // The inverse create API supplies this readonly field before writes. Standard preview needs
      // the same field as a candidate input and still checks its normal authorization.
      if (!record && fixedReferenceField) inputs[fixedReferenceField] = values[fixedReferenceField];
      const body = {runtime, ...(referenceMappings ? {referenceMappings} : {}), recordId: id, expectedVersion: version, state: state ?? recordState ?? 'DRAFT',
        values: detailPayload(descriptor.document, inputs, recordState)};
      const result = await post<CalculationPreviewResult & {runtime: typeof runtime}>(endpoint, body, {signal, notifyOnError: false});
      if (result.runtime.formId !== runtime.formId || result.runtime.formVersion !== runtime.formVersion) throw new Error('Calculation preview runtime changed');
      return result;
    };
  }, [descriptor, record, id, version, recordState, state, fixedReferenceField, scope]);
}
