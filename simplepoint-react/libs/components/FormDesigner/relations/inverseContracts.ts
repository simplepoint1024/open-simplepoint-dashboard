import type {LocalizedText} from '../../../shared/i18n/localizedText.ts';
import type {FormRelationTarget} from '../relationContracts.ts';

/** References an existing owning relation. These flags never replace source-page capabilities. */
export type FormInverseRelationView = {
  viewId: string;
  label: string;
  labelLocalization?: LocalizedText | null;
  source: FormRelationTarget;
  sourceRelationId: string;
  allowCreate: boolean;
  allowEdit: boolean;
  allowDelete: boolean;
  allowUnlink: boolean;
};
