import {useMemo} from 'react';
import {post} from '@simplepoint/shared/api/methods';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import type {ApplicationFormRuntimeView} from './contracts';
import {detailPayload} from '../FormComponents/Detail/payload';
import {editableFieldIds, editableValues, runtimeIdentity, type RecordRow, type RecordState} from './recordTableModel';
import {hasCalculatedFields, type CalculationPreviewLoader, type CalculationPreviewResult} from './calculationPreview';

/** Published-page adapter; the standalone calculator and generic form renderer own no HTTP endpoints. */
export function useRecordCalculationPreview(descriptor: ApplicationFormRuntimeView,
  record?: RecordRow | null, state?: RecordState): CalculationPreviewLoader | undefined {
  const scope = useQueryScope();
  const {id, version, recordState} = record ?? {};
  return useMemo(() => {
    if (!hasCalculatedFields(descriptor.document)) return undefined;
    const runtime = runtimeIdentity(descriptor);
    const endpoint = `/app/runtime/applications/${encodeURIComponent(descriptor.applicationId)}/pages/${encodeURIComponent(descriptor.pageId)}/records/preview`;
    return async (values, signal) => {
      const current = record;
      const body = {runtime, recordId: id, expectedVersion: version, state: state ?? recordState ?? 'DRAFT',
        values: detailPayload(descriptor.document, editableValues(values, editableFieldIds(descriptor, undefined, recordState), current), recordState)};
      const result = await post<CalculationPreviewResult & {runtime: typeof runtime}>(endpoint, body, {signal, notifyOnError: false});
      if (result.runtime.formId !== runtime.formId || result.runtime.formVersion !== runtime.formVersion) throw new Error('Calculation preview runtime changed');
      return result;
    };
  }, [descriptor, record, id, version, recordState, state, scope]);
}
