export type FormFieldType =
  | 'STRING'
  | 'TEXT'
  | 'BOOLEAN'
  | 'INT32'
  | 'INT64'
  | 'DECIMAL'
  | 'DATE'
  | 'TIME'
  | 'DATETIME'
  | 'UUID'
  | 'JSON';

export type FormValueKind =
  | 'STRING'
  | 'BOOLEAN'
  | 'INTEGER'
  | 'DECIMAL'
  | 'DATE'
  | 'TIME'
  | 'DATETIME'
  | 'UUID'
  | 'JSON'
  | 'NULL';

export type FormValue = {kind: FormValueKind; value?: string | null};
export type FormChoiceOption = {value: string; label: string};
export type FormFieldWritePolicy = 'EDITABLE' | 'CREATE_ONLY' | 'UNTIL_SUBMITTED';

export type FormFieldValidation = {minLength?: number; maxLength?: number; pattern?: string; patternMessage?: string};
export type FormFieldAccess = {readOnly?: boolean; create?: 'WRITE' | 'HIDDEN'; edit?: 'WRITE' | 'READ' | 'HIDDEN'};
export type FormPresentation = {listFieldOrder: string[]; labelWidth?: number | null};
export type FormFieldDefinition = {
  access?: FormFieldAccess | null;
  validation?: FormFieldValidation | null;
  fieldId: string;
  label: string;
  type: FormFieldType;
  submitRequired: boolean;
  length?: number | null;
  precision?: number | null;
  scale?: number | null;
  defaultValue?: FormValue | null;
  choices: FormChoiceOption[];
  writePolicy?: FormFieldWritePolicy | null;
};

export type FormComponentDefinition = {
  componentId: string;
  componentType: string;
  componentVersion: string;
  fieldId?: string | null;
  columnSpan: number;
  label?: string | null;
  description?: string | null;
  placeholder?: string | null;
  targetSceneId?: string | null;
  readOnly: boolean;
  properties: Record<string, FormValue>;
  children: FormComponentDefinition[];
};

export type FormSceneDefinition = {
  sceneId: string;
  code: string;
  name: string;
  type: 'MAIN' | 'DIALOG' | 'DRAWER';
  width?: number | null;
  root: FormComponentDefinition;
};

export type FormConditionOperator =
  | 'AND'
  | 'OR'
  | 'NOT'
  | 'EQUALS'
  | 'NOT_EQUALS'
  | 'IN'
  | 'NOT_IN'
  | 'GREATER_THAN'
  | 'GREATER_THAN_OR_EQUAL'
  | 'LESS_THAN'
  | 'LESS_THAN_OR_EQUAL'
  | 'IS_EMPTY'
  | 'IS_NOT_EMPTY';

export type FormCondition = {
  operator: FormConditionOperator;
  fieldId?: string | null;
  values: FormValue[];
  children: FormCondition[];
};

export type FormRuleDefinition = {
  ruleId: string;
  targetComponentId: string;
  effect: 'SHOW' | 'HIDE' | 'ENABLE' | 'DISABLE' | 'REQUIRE' | 'OPTIONAL';
  condition: FormCondition;
};

export type FormDetailDefinition = {detailId: string; label: string; fields: FormFieldDefinition[]; readOnlyFieldIds?: string[]};

export type FormValidationConstraint = {
  constraintId: string; kind: 'RANGE' | 'AFTER' | 'DETAIL_COUNT' | 'DETAIL_RANGE' | 'DETAIL_SUM';
  fieldId?: string | null; otherFieldId?: string | null; detailId?: string | null; detailFieldId?: string | null;
  minimum?: string | null; maximum?: string | null; message: string;
};

export type ApplicationFormDocument = {
  presentation?: FormPresentation | null;
  constraints?: FormValidationConstraint[];
  contractVersion: string;
  details?: FormDetailDefinition[];
  fields: FormFieldDefinition[];
  scenes: FormSceneDefinition[];
  rules: FormRuleDefinition[];
};

export type ApplicationFormRuntimeView = {
  applicationId: string;
  pageId: string;
  pageName: string;
  formId: string;
  formName: string;
  formVersion: number;
  /** Highest activated version governing form-wide write restrictions; older servers omit it. */
  writePolicyVersion?: number;
  sceneId: string;
  resourceCode: string;
  capabilities: {
    canCreate: boolean;
    canEdit: boolean;
    canDelete: boolean;
    canStartProcess: boolean;
  };
  document: ApplicationFormDocument;
};
