import type {LocalizedText} from '../../shared/i18n/localizedText.ts';
import type {ApplicationFormRuntimeView} from './contracts.ts';
import type {InverseUnlink} from './inverseUnlink.ts';
import {referenceIdentity} from '../FormComponents/Reference/display.ts';

export type InverseViewSummary = {viewId: string; label: string; labelLocalization?: LocalizedText | null};
export type InverseRuntimeView = {source: ApplicationFormRuntimeView; fieldId: string; detailId?: string | null; canUnlink: boolean};
type Parent = Pick<ApplicationFormRuntimeView, 'applicationId' | 'pageId' | 'formId' | 'formVersion'>;

/** Stable source metadata comes from the server; browser URLs name only an observed parent/view. */
export function inverseCatalog(parent: Parent, recordId: string) {
  const id = referenceIdentity(recordId);
  if (!id || !Number.isInteger(parent.formVersion) || parent.formVersion < 1 || parent.formVersion > 2147483647) throw new Error('A saved parent is required');
  return {baseUrl: `/app/runtime/applications/${encodeURIComponent(parent.applicationId)}`
    + `/pages/${encodeURIComponent(parent.pageId)}/records/${id}/relations`,
  params: {parentFormId: parent.formId, parentFormVersion: String(parent.formVersion)}};
}

/** Preferences follow the configured view; records and publication identity fence requests separately. */
export function inverseEndpoint(parent: Parent, recordId: string, viewId: string, runtime: InverseRuntimeView) {
  if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(viewId)) throw new Error('Invalid published inverse view');
  const catalog = inverseCatalog(parent, recordId);
  const field = (runtime.detailId ? runtime.source.document?.details?.find(detail => detail.detailId === runtime.detailId)?.fields
    : runtime.source.document?.fields)?.find(field => field.fieldId === runtime.fieldId);
  const unlink: InverseUnlink | undefined = runtime.canUnlink && field
    ? {targetRecordId: referenceIdentity(recordId)!, field, detailId: runtime.detailId} : undefined;
  const values = {[runtime.fieldId]: referenceIdentity(recordId)!};
  return {baseUrl: `${catalog.baseUrl}/${encodeURIComponent(viewId)}/records`, params: catalog.params,
    preferenceKey: `app.runtime.inverse.${parent.pageId}.${viewId}`, unlink,
    submission: {baseUrl: `${catalog.baseUrl}/${encodeURIComponent(viewId)}/submissions`, params: catalog.params},
    fixedReferenceField: runtime.detailId ? undefined : runtime.fieldId,
    initialValues: runtime.detailId ? {[runtime.detailId]: [{values}]} : values};
}

/** The root parent is fixed by this entry; create injection and enforcement still belong to APP/DNA. */
export function inverseEditor(runtime: InverseRuntimeView): ApplicationFormRuntimeView {
  if (runtime.detailId) return runtime.source;
  return {...runtime.source, document: {...runtime.source.document,
    fields: runtime.source.document.fields.map(field => field.fieldId === runtime.fieldId
      ? {...field, access: {...field.access, readOnly: true}} : field),
  }};
}
