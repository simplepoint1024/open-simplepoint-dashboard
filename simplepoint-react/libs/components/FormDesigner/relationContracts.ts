import type {LocalizedText} from '../../shared/i18n/localizedText.ts';
import type {FormCondition, FormReferenceValueMode} from '../FormRuntime/contracts.ts';

/** Owning-side metadata; inverse lists reuse the same relation ID and never duplicate ownership. */
export type FormRelationTarget = {
  applicationId: string;
  formId: string;
  accessResourceId: string;
};
export type FormRelationField = {detailId?: string | null; fieldId: string};
export type FormReferenceContextFilter = {targetFieldId: string;
  operator: 'EQUALS' | 'NOT_EQUALS' | 'GREATER_THAN' | 'GREATER_THAN_OR_EQUAL' | 'LESS_THAN' | 'LESS_THAN_OR_EQUAL';
} & ({source: 'FORM' | 'ROW'; sourceFieldId: string} | {source: 'ORGANIZATION'; sourceFieldId?: null});
export type FormReferenceView = {
  labelFieldId: string;
  searchFieldIds: string[];
  displayFieldIds: string[];
  candidateFilter?: FormCondition | null;
  allowDrillThrough: boolean;
  contextFilters?: FormReferenceContextFilter[];
};
/** Values stay separate from labels and candidate filters; destinations belong to this row. */
export type FormReferenceMapping = {
  mappingId: string; targetFieldId: string; destinationFieldId: string;
  mode: FormReferenceValueMode;
  conversion: 'IDENTITY' | 'STRING' | 'INTEGER' | 'DECIMAL';
  nullPolicy: 'CLEAR' | 'PRESERVE' | 'REJECT';
};
export type FormRelationDefinition = {
  mappings?: FormReferenceMapping[];
  collectionReplacement?: {sourceRelationId: string; targetRecords: Record<string, string>};
  relationId: string;
  target: FormRelationTarget;
  referenceView?: FormReferenceView | null;
  qualification?: {
    condition: FormCondition;
    recheck: 'ON_CHANGE' | 'ON_SUBMIT' | 'ALWAYS';
  } | null;
} & (
  | {contractVersion: '1'; cardinality: 'ONE_TO_ONE' | 'MANY_TO_ONE'; sourceField: FormRelationField; retiredByCollectionId?: never}
  | {contractVersion: '1' | '3'; cardinality: 'MANY_TO_MANY'; sourceField?: null; collectionLabel?: LocalizedText | null; retiredByCollectionId?: never}
  | {contractVersion: '2'; cardinality: 'MANY_TO_ONE'; sourceField: FormRelationField; retiredByCollectionId: string}
);

export type FormCollectionRelation = Extract<FormRelationDefinition, {cardinality: 'MANY_TO_MANY'}>;
