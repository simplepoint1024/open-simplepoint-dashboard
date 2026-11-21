import type {MappingView, MappingSourceRow} from './referenceMapping/model';
import {useMemo} from 'react';
import {get, post} from '@simplepoint/shared/api/methods';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import type {ApplicationFormRuntimeView} from './contracts';
import {contextualReferenceSource, referenceSource, type ReferenceOption, type ReferencePage} from '../FormComponents/Reference/source';
import type {ReferenceSources, ReferenceDetail} from '../FormComponents/Reference/RuntimeContext';
import {batchReferenceLookups} from '../FormComponents/Reference/batch';
import {formReferenceLocation} from './referenceLocation';
import type {LiveReferenceView} from './liveReferenceValues';

export type ReferenceRuntimeIdentity = Pick<ApplicationFormRuntimeView, 'applicationId' | 'pageId' | 'formId' | 'formVersion'>;

/** Published-page transport; standalone form rendering and design previews do not own HTTP paths. */
export function useRecordReferences(descriptor: ReferenceRuntimeIdentity, ownerKey?: string, record?: {id: string; version: number}): ReferenceSources {
  const scope = useQueryScope();
  return useMemo(() => {
    const bindings = new Map<string, ReturnType<ReferenceSources>>();
    const base = (relationId: string) => {
      const previous = bindings.get(relationId);
      if (previous) return previous;
      const endpoint = `/app/runtime/applications/${encodeURIComponent(descriptor.applicationId)}/pages/${encodeURIComponent(descriptor.pageId)}/references/${encodeURIComponent(relationId)}`;
      const runtime = {formId: descriptor.formId, formVersion: descriptor.formVersion};
      const identity = new URLSearchParams({formId: runtime.formId, formVersion: String(runtime.formVersion)}).toString();
      const binding = {endpoint,
        mapping: (targetRecordId: string | null, applyDefaultMappingIds: string[], signal: AbortSignal, sourceRow?: MappingSourceRow) =>
          post<MappingView>(`${endpoint}/mapping`, {runtime, recordId: record?.id, expectedVersion: record?.version,
            targetRecordId, applyDefaultMappingIds, ...sourceRow}, {signal, notifyOnError: false}),
        live: (targetRecordIds: string[], signal: AbortSignal) => post<LiveReferenceView[]>(`${endpoint}/live/resolve`,
          {runtime, targetRecordIds}, {signal, notifyOnError: false}),
        pageLocation: (recordId: string) => formReferenceLocation(descriptor, relationId, recordId),
        detail: (recordId: string, signal: AbortSignal) => get<ReferenceDetail>(`${endpoint}/${encodeURIComponent(recordId)}`,
          runtime, {signal, notifyOnError: false}),
        source: referenceSource(JSON.stringify([scope, descriptor.formId, descriptor.formVersion, relationId]), {
        search: (keyword, page, size, signal) => get<ReferencePage>(endpoint, {keyword, page, size, ...runtime}, {signal, notifyOnError: false}),
        resolve: batchReferenceLookups((ids, signal) => post<ReferenceOption[]>(`${endpoint}/resolve?${identity}`, ids, {signal, notifyOnError: false})),
      })};
      bindings.set(relationId, binding);
      return binding;
    };
    return (relationId, context) => {
      const binding = base(relationId);
      if (!context) return binding;
      // Do not retain a map of every keystroke/row context. The owning widget stabilizes its binding.
      return {...binding, source: contextualReferenceSource(binding.source, context, (keyword, page, size, signal) => {
        const query = new URLSearchParams({keyword, page: String(page), size: String(size),
          formId: descriptor.formId, formVersion: String(descriptor.formVersion)});
        return post<ReferencePage>(`${binding.endpoint}/search?${query}`, context, {signal, notifyOnError: false});
      })};
    };
  }, [scope, descriptor.applicationId, descriptor.pageId, descriptor.formId, descriptor.formVersion, ownerKey, record?.id, record?.version]);
}
