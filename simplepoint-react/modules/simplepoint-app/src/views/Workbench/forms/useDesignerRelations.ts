import {useMemo} from 'react';
import {get, post} from '@simplepoint/shared/api/methods';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {relationTargetDirectory, type InverseSourceMetadata, type RelationTargetMetadata, type RelationTargetOption, type RelationTargetPage} from '@simplepoint/components/FormDesigner/relations/catalog';

/** APP transport only; the generic designer receives an authorized, abortable directory contract. */
export default function useDesignerRelations(applicationId: string, formId: string) {
  const scope = useQueryScope(), {locale} = useI18n();
  return useMemo(() => {
    const endpoint = `/app/applications/${encodeURIComponent(applicationId)}/forms/${encodeURIComponent(formId)}/relation-targets`;
    return relationTargetDirectory(JSON.stringify([scope, applicationId, formId]), endpoint, locale, {
      search: (keyword, page, size, signal) => get<RelationTargetPage>(endpoint, {keyword, page, size}, {signal, notifyOnError: false}),
      resolve: (ids, signal) => post<RelationTargetOption[]>(`${endpoint}/resolve`, ids, {signal, notifyOnError: false}),
      inverseSource: (source, signal) => post<InverseSourceMetadata>(`${endpoint}/inverse-source`, source, {signal, notifyOnError: false}),
      describe: (target, signal) => post<RelationTargetMetadata>(`${endpoint}/describe`, target, {signal, notifyOnError: false}),
    });
  }, [scope, applicationId, formId, locale]);
}
