import {forwardRef, lazy, Suspense, useCallback, useImperativeHandle, useMemo, useRef, useState} from 'react';
import {Alert, Radio, Space, Spin} from 'antd';
import type {WidgetProps} from '@rjsf/utils';
import validator from '@rjsf/validator-ajv8';
import SForm, {type SFormHandle} from '@simplepoint/components/SForm';
import JsonObjectEditor from '@simplepoint/components/JsonObjectEditor';
import {parseJsonObject} from '@simplepoint/components/JsonObjectEditor/model';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {metadataFormSchema, validateMetadataDraft, type DialogTarget, type MetadataDraft} from './forms';

const Sql = lazy(() => import('../SqlConsole/SqlEditor').then(module => ({default: module.SqlEditor})));
function SqlWidget({value, onChange, disabled, readonly, registry}: WidgetProps) {
  return <div style={{height: 220}}><Suspense fallback={<Spin/>}>
    <Sql value={value ?? ''} onChange={next => {
      if (!disabled && !readonly) registry.formContext.commitInput(() => onChange(next));
    }} readOnly={disabled || readonly}/>
  </Suspense></div>;
}
const widgets = {Sql: SqlWidget};
const fields = {Hidden: () => null};
const constraintUi = (type?: string) => ({
  ...(type === 'CHECK' ? {columns: {'ui:field': 'Hidden'}} : {}),
  ...(type === 'FOREIGN_KEY' ? {} : {reference: {'ui:field': 'Hidden'}}),
  ...(type === 'CHECK' ? {checkExpression: {'ui:widget': 'Sql'}} : {checkExpression: {'ui:field': 'Hidden'}}),
});
export type MetadataFormHandle = {submit: () => void};

/** Lazy dialog-only dependencies; ordinary and advanced editing share one typed request model. */
export default forwardRef<MetadataFormHandle, {target: DialogTarget; namespaceTypes: string[]; columns: string[];
  disabled: boolean; onSubmit: (data: MetadataDraft) => void}>(function MetadataForm(
  {target, namespaceTypes, columns, disabled, onSubmit}, ref) {
  const {t} = useI18n();
  const [data, setData] = useState(target.data);
  const [advanced, setAdvanced] = useState(false);
  const [json, setJson] = useState('');
  const [error, setError] = useState('');
  const form = useRef<SFormHandle>(null);
  const schema = useMemo(() => metadataFormSchema(target.kind, t, namespaceTypes, columns), [target.kind, t, namespaceTypes, columns]);
  const logicalError = useCallback((error: unknown) => {
    const code = (error as Error).message;
    const messages: Record<string, string> = {
      DUPLICATE_COLUMNS: t('dna.metadata.page.validation.duplicateColumns', 'Column names must be unique.'),
      INVALID_SCALE: t('dna.metadata.page.validation.invalidScale', 'Scale cannot exceed precision.'),
      FOREIGN_KEY_ARITY: t('dna.metadata.page.validation.foreignKeyArity', 'Local and reference columns must have the same count.'),
      FOREIGN_KEY_TABLE: t('dna.metadata.page.validation.foreignKeyTable', 'The reference path must end with a table.'),
      UNKNOWN_COLUMN: t('dna.metadata.page.validation.unknownColumn', 'A constraint refers to an undefined local column.'),
    };
    return messages[code] ?? code;
  }, [t]);
  const parse = useCallback((source: string) => {
    const value = parseJsonObject(source);
    const errors = validator.rawValidation(schema, value).errors;
    if (errors?.length) throw Error(errors.map(error => `${error.instancePath || '/'} ${error.message}`).join('; '));
    validateMetadataDraft(target.kind, value);
    return value;
  }, [schema, target.kind]);
  useImperativeHandle(ref, () => ({submit: () => {
    if (disabled) return;
    if (!advanced) {form.current?.submit(); return;}
    try {const next = parse(json); setData(next); setError(''); onSubmit(next);}
    catch (error) {setError(logicalError(error));}
  }}));
  return <Space orientation="vertical" style={{width: '100%'}}>
    {target.kind === 'table' && <Radio.Group value={advanced} disabled={disabled} onChange={event => {
      if (event.target.value) {setJson(JSON.stringify(data, null, 2)); setAdvanced(true); setError('');}
      else {try {setData(parse(json)); setAdvanced(false); setError('');} catch (error) {setError(logicalError(error));}}
    }} options={[{value: false, label: t('dna.metadata.page.form.ordinary', 'Ordinary')},
      {value: true, label: t('dna.metadata.page.form.advanced', 'Advanced JSON')}]}/>}
    {error && <Alert type="error" showIcon title={error}/>}
    {advanced ? <JsonObjectEditor value={json} disabled={disabled} onChange={setJson} validate={parse}/>
      : <SForm ref={form} hideSubmit disabled={disabled} schema={schema} formData={data} widgets={widgets} fields={fields}
        experimental_defaultFormStateBehavior={{emptyObjectFields: 'skipEmptyDefaults', arrayMinItems: {populate: 'never'}}}
        uiSchema={{remarks: {'ui:widget': 'textarea'}, ...(target.kind === 'constraint' ? constraintUi(data.type) : {}),
          constraints: {items: (item: MetadataDraft | undefined) => constraintUi(item?.type)}}}
        customValidate={(value, errors) => {try {validateMetadataDraft(target.kind, value);} catch (error) {errors.addError(logicalError(error));} return errors;}}
        onChange={event => setData(event.formData ?? {})} onSubmit={event => onSubmit(event.formData ?? {})}/>}
  </Space>;
});
