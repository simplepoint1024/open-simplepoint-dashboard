import {createContext, forwardRef, useContext, useMemo, type ReactNode} from 'react';
import type {FieldTemplateProps, ObjectFieldTemplateProps, RJSFSchema, WidgetProps} from '@rjsf/utils';
import SForm from '@simplepoint/components/SForm/Lazy';
import type {SFormHandle} from '@simplepoint/components/SForm';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import EntriesConfig from './config/entries';
import {duplicateFieldEntries, fieldScopeSchema, type FieldScopeDraft} from './forms';

const BasicInfo = createContext<ReactNode>(null);
// Compose standard schema fields into the existing matrix; no nested form or private RJSF state.
function MatrixLayout({properties}: ObjectFieldTemplateProps) {
  return <BasicInfo.Provider value={<>{properties.filter(field => field.name !== 'entries').map(field => field.content)}</>}>
    {properties.find(field => field.name === 'entries')?.content}
  </BasicInfo.Provider>;
}
function MatrixContainer({children, errors}: FieldTemplateProps) {
  return <div className="field-permission-form-content">{errors}{children}</div>;
}
function MatrixWidget({value, onChange, disabled, readonly, schema, registry}: WidgetProps) {
  const basicInfo = useContext(BasicInfo);
  const properties = (schema.items as any)?.properties ?? {};
  return <EntriesConfig value={value} onChange={onChange} disabled={disabled} canInteract={registry.formContext.canInteract} basicInfo={basicInfo} readOnlyEntries={readonly}
    readOnlyAccess={readonly || properties.access?.readOnly} allowNew={!readonly && !['resource', 'field', 'access'].some(key => properties[key]?.readOnly)}/>;
}
const widgets = {FieldMatrix: MatrixWidget};
const uiSchema = {'ui:ObjectFieldTemplate': MatrixLayout, 'ui:FieldTemplate': MatrixContainer,
  description: {'ui:widget': 'textarea', 'ui:options': {autoSize: {minRows: 2, maxRows: 3}}},
  entries: {'ui:widget': 'FieldMatrix', 'ui:FieldTemplate': MatrixContainer},
};
type Props = {draft: FieldScopeDraft; source?: RJSFSchema; disabled: boolean; canInteract: () => boolean;
  onBeforeSubmit: () => boolean; onError: () => void; onChange: (draft: FieldScopeDraft) => void; onSubmit: (draft: FieldScopeDraft) => void};
export default forwardRef<SFormHandle, Props>(function MatrixForm({draft, source, disabled, canInteract, onBeforeSubmit, onError, onChange, onSubmit}, ref) {
  const {t} = useI18n(), schema = useMemo(() => fieldScopeSchema(t, source), [t, source]);
  return <SForm ref={ref} className="field-permission-form" hideSubmit schema={schema} uiSchema={uiSchema} widgets={widgets}
    formContext={{canInteract}} onBeforeSubmit={onBeforeSubmit} onError={onError} submitDisabled={disabled}
    formData={draft} disabled={disabled} onChange={event => onChange(event.formData)} onSubmit={event => onSubmit(event.formData)}
    customValidate={(data, errors) => {if (duplicateFieldEntries(data)) errors.entries?.addError(t('field-scopes.rule.duplicate', '同一资源字段不能重复配置'));
      return errors;}}/>;
});
