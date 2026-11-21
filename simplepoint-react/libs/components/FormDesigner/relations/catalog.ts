import type {EntitySelectSource} from '../../EntitySelect/index.tsx';
import type {EntityOption} from '../../EntitySelect/query.ts';
import type {FormFieldType} from '../../FormRuntime/contracts.ts';
import type {FormCollectionRelation, FormRelationTarget} from '../relationContracts.ts';
import {resolveLocalizedText, type LocalizedText} from '../../../shared/i18n/localizedText.ts';

export type RelationTargetOption = {target: FormRelationTarget; pageId: string; applicationName: string; pageName: string;
  formName: string; applicationNameLocalization?: LocalizedText | null; pageNameLocalization?: LocalizedText | null};
export type RelationTargetPage = {page: number; size: number; total: number; records: RelationTargetOption[]};
export type RelationTargetMetadata = {target: FormRelationTarget; pageId: string; formVersion: number;
  fields: {fieldId: string; label: string; labelLocalization?: LocalizedText | null; type: FormFieldType; searchable: boolean}[]};
export type InverseSourceMetadata = {source: FormRelationTarget; formVersion: number; relations: {
  relationId: string; label: string; labelLocalization?: LocalizedText | null;
  detailLabel?: string | null; detailLabelLocalization?: LocalizedText | null; nullable: boolean; collection?: boolean;
}[]};
export type RelationCatalogTransport = {
  inverseSource?: (source: FormRelationTarget, signal: AbortSignal) => Promise<InverseSourceMetadata>;
  search: (keyword: string, page: number, size: number, signal: AbortSignal) => Promise<RelationTargetPage>;
  resolve: (ids: string[], signal: AbortSignal) => Promise<RelationTargetOption[]>;
  describe: (target: FormRelationTarget, signal: AbortSignal) => Promise<RelationTargetMetadata>;
};
export type DesignerRelationDirectory = {key: string; endpoint: string; source: EntitySelectSource;
  convertibleRelationIds?: readonly string[];
  publishedCollections?: readonly FormCollectionRelation[];
  describe: RelationCatalogTransport['describe']; inverseSource?: RelationCatalogTransport['inverseSource']};

/** Read a stable identity from an authorized picker option, never from its translated text. */
export function relationTargetIdentity(value: unknown): FormRelationTarget | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const candidate = value as FormRelationTarget;
  return [candidate.applicationId, candidate.formId, candidate.accessResourceId].every(part =>
    typeof part === 'string' && /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(part))
    ? {applicationId: candidate.applicationId, formId: candidate.formId, accessResourceId: candidate.accessResourceId} : undefined;
}

/** The generic designer receives an injected directory, without knowing APP routes or HTTP. */
export function relationTargetDirectory(key: string, endpoint: string, locale: string, transport: RelationCatalogTransport): DesignerRelationDirectory {
  const identity = JSON.stringify([key, locale]);
  const option = (row: Record<string, unknown>): EntityOption | undefined => {
    const target = relationTargetIdentity(row.target);
    if (!target || typeof row.pageName !== 'string' || typeof row.applicationName !== 'string') return undefined;
    const application = resolveLocalizedText(row.applicationNameLocalization as LocalizedText | null, locale, undefined, 'TENANT', row.applicationName).text;
    const page = resolveLocalizedText(row.pageNameLocalization as LocalizedText | null, locale, undefined, 'TENANT', row.pageName).text;
    return {value: target.accessResourceId, label: `${application} / ${page}`, metadata: {target, pageId: row.pageId}};
  };
  return {key: identity, endpoint, ...(transport.inverseSource ? {inverseSource: async (source: FormRelationTarget, signal: AbortSignal) => {
    signal.throwIfAborted();
    const result = await transport.inverseSource!(source, signal);
    signal.throwIfAborted();
    if (!result.source || result.source.applicationId !== source.applicationId || result.source.formId !== source.formId
      || result.source.accessResourceId !== source.accessResourceId || !Number.isInteger(result.formVersion) || result.formVersion < 1
      || !Array.isArray(result.relations) || result.relations.length > 50
      || new Set(result.relations.map(value => value.relationId)).size !== result.relations.length) throw Error('Inverse source metadata identity mismatch');
    return result;
  }} : {}), source: {key: identity, pageSize: 50, searchParams: (keyword, page, size) => ({keyword, page, size}), option,
    search: async (keyword, page, size, signal) => {
      signal.throwIfAborted();
      const result = await transport.search(keyword, page, size, signal);
      signal.throwIfAborted();
      return {content: result.records.map(row => ({...row, id: row.target.accessResourceId})), page: {
        number: result.page, size: result.size, totalElements: result.total, totalPages: Math.ceil(result.total / result.size),
      }};
    },
    resolve: async (ids, signal) => {
      const distinct = [...new Set(ids)], found = new Map<string, EntityOption>();
      for (let start = 0; start < distinct.length; start += 100) {
        signal.throwIfAborted();
        const batch = distinct.slice(start, start + 100);
        const rows = await transport.resolve(batch, signal);
        signal.throwIfAborted();
        for (const row of rows) {
          const candidate = option(row);
          if (candidate && batch.includes(candidate.value)) found.set(candidate.value, candidate);
        }
      }
      return distinct.filter(id => found.has(id)).map(id => found.get(id)!);
    }}, describe: async (target, signal) => {
      signal.throwIfAborted();
      const result = await transport.describe(target, signal);
      signal.throwIfAborted();
      const actual = relationTargetIdentity(result.target);
      if (!actual || actual.applicationId !== target.applicationId || actual.formId !== target.formId
        || actual.accessResourceId !== target.accessResourceId || !Number.isSafeInteger(result.formVersion) || result.formVersion < 1) {
        throw new Error('Relation target metadata identity mismatch');
      }
      return result;
    }};
}
