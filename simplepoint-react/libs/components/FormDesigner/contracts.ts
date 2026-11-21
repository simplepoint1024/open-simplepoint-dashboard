import type {ApplicationFormDocument, FormFieldDefinition, FormFieldType, FormFieldWritePolicy, FormValue, FormValueKind} from '../FormRuntime/contracts.ts';
import type {FormCollectionRelation, FormRelationDefinition} from './relationContracts.ts';
import type {FormInverseRelationView} from './relations/inverseContracts.ts';

export type DesignField = FormFieldDefinition & {
  storageCode?: string | null;
  storageLabel?: string | null;
  storageMode: 'STORED' | 'REFERENCE' | 'ATTACHMENT' | 'COMPUTED' | 'TRANSIENT';
  storageRequired: boolean;
  calculation?: {languageVersion: '1'; expression: string} | null;
};
export type DesignDocument = Omit<ApplicationFormDocument, 'fields' | 'details' | 'presentation'> & {
  fields: DesignField[];
  details?: DesignDetail[];
  relations?: FormRelationDefinition[];
  presentation?: NonNullable<ApplicationFormDocument['presentation']> & {inverseViews?: FormInverseRelationView[]} | null;
  indexes: {indexId: string; storageCode: string; unique: boolean; fieldIds: string[]}[];
};
export type ComponentDescriptor = {
  type: string;
  version: string;
  container: boolean;
  fieldRequired: boolean;
  supportedFieldTypes: FormFieldType[];
  propertySchema: Record<string, {name: string; acceptedKinds: FormValueKind[]; required: boolean; defaultValue?: FormValue | null}>;
  allowedParentTypes: string[];
  allowedChildTypes: string[];
};
export type DesignerContract = {
  canCreate: boolean;
  canEdit: boolean;
  canPublish: boolean;
  components: ComponentDescriptor[];
  writePolicies?: FormFieldWritePolicy[];
};
export type FormDraft = {formId: string; revision: number; document: DesignDocument; checksum: string; updatedAt: string;
  convertibleRelationIds?: string[]; publishedCollections?: FormCollectionRelation[]};
export type FormSummary = {id: string; applicationId: string; code: string; name: string; description?: string;
  status: string; draftRevision: number; latestVersion: number; publishedVersion?: number | null};

export type DesignDetail = {detailId: string; storageCode: string; label: string; labelLocalization?: import('../../shared/i18n/localizedText.ts').LocalizedText | null; fields: DesignField[]; indexes: DesignDocument['indexes']};
