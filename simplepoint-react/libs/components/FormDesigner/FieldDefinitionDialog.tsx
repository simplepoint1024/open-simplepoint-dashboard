import {useContext, useRef, useState} from 'react';
import {Alert, Modal} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import TableActions from '../Table/Actions';
import type {SFormHandle} from '../SForm';
import type {DesignerPropertyEditorProps} from './extensions';
import type {DesignField} from './contracts';
import FieldEditor from './FieldEditor';
import {DesignerCalculationContext} from './CalculationFieldEditor';
import {applyFieldEdit, DataEditError, fieldEdit, type FieldEdit} from './dataModel';
import {sameDesignValue} from './draftState';
import {fieldUsages} from './fieldAnalysis';

type DialogProps = Omit<DesignerPropertyEditorProps, 'onChange' | 'node'> & {
  field: DesignField; mode?: NonNullable<FieldEdit['calculationMode']>; onClose: () => void;
  scope?: string; modelScope?: 'form' | 'detail'; creating?: boolean; className?: string; node?: DesignerPropertyEditorProps['node'];
};

/** A mode change is atomic with its validated expression/defaults; cancel never changes the document. */
export default function FieldDefinitionDialog({document, field, components, writePolicies, disabled, mode, onDocumentChange, onClose, scope = 'parent', modelScope, creating = false, className = 'sp-form-field-calculation-modal'}: DialogProps) {
  const {t} = useI18n(), selected = usePageSelected();
  const context = useContext(DesignerCalculationContext);
  const usages = fieldUsages(context?.document ?? document, {scope, fieldId: field.fieldId});
  const [base] = useState(() => structuredClone(field));
  const [value, setValue] = useState<FieldEdit>(() => ({...fieldEdit(field), calculationMode: mode ?? fieldEdit(field).calculationMode,
    ...(mode === 'calculated' ? {defaultMode: 'NONE', defaultText: '', writePolicy: 'EDITABLE', expression: field.calculation?.expression ?? 'NULL'} : {})}));
  const [failure, setFailure] = useState(''), [discarding, setDiscarding] = useState(false);
  const form = useRef<SFormHandle>(null);
  const locked = disabled || !selected;
  usePageRetention(false, true);
  const close = () => {if (selected) sameDesignValue(fieldEdit(base), value) ? onClose() : setDiscarding(true);};
  return <>
    <Modal open={selected} width={value.calculationMode === 'calculated' ? 1040 : 720} destroyOnHidden maskClosable={false}
      wrapClassName={className} title={`${value.label || field.label} · ${value.calculationMode === 'calculated'
        ? t('app.calculation.title', '计算公式') : t('app.designer.editField', '编辑字段')}`} onCancel={close}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'cancel', icon: 'CloseOutlined', sort: 0, text: t('app.designer.cancelData', '取消')},
        {key: 'apply', icon: 'CheckOutlined', sort: 1, type: 'primary', text: creating ? t('app.designer.addData', '添加') : t('app.designer.applyData', '应用修改'), disabled: locked},
      ]} onButtonEvents={{cancel: close, apply: () => {if (!locked) form.current?.submit();}}}/>}
    >
      {usages.length > 1 && <Alert type="info" showIcon title={t('app.designer.sharedField', '共享字段：修改将同步影响这些场景') }
        description={[...new Set(usages.map(usage => usage.sceneName))].join('、')}/>}
      {failure && <Alert type="error" showIcon title={failure}/>}
      <FieldEditor calculationScope={scope} initialTab={mode === 'calculated' ? 'calculation' : 'general'} document={document} field={base} value={value}
        components={components} writePolicies={writePolicies} disabled={locked} formRef={form} onChange={next => {setValue(next); setFailure('');}}
        onSubmit={edit => {
          if (locked) return;
          try {
            const next = applyFieldEdit(document, base, edit, components, writePolicies, creating, modelScope ?? (scope === 'parent' ? 'form' : 'detail'));
            onDocumentChange(next); onClose();
          } catch (error) {
            setFailure(error instanceof DataEditError && error.code === 'conflict'
              ? t('app.designer.dataConflict', '该定义已发生变化，请关闭后重新编辑。')
              : error instanceof DataEditError && error.code === 'defaultValue'
                ? t('app.designer.dataDefaultError', '默认值须符合字段类型、长度、精度和选项；存储非空字段须有非空默认值。')
              : error instanceof DataEditError && error.code === 'choices'
                ? t('app.designer.dataChoicesError', '选项须符合字段类型与存储约束，且不能有语义相同的重复值。')
              : t('app.designer.invalid', '无法应用此修改，请检查表单配置。'));
          }
        }}/>
    </Modal>
    <Modal wrapClassName="sp-form-data-discard" open={discarding && selected} title={t('app.designer.discardDataEdit', '放弃尚未应用的修改？')}
      onCancel={() => setDiscarding(false)} footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'continue', icon: 'EditOutlined', sort: 0, text: t('app.designer.continueDataEdit', '继续编辑')},
        {key: 'discard', icon: 'DeleteOutlined', sort: 1, danger: true, text: t('app.designer.discardData', '放弃修改')},
      ]} onButtonEvents={{continue: () => setDiscarding(false), discard: () => {if (selected) onClose();}}}/>}/>
  </>;
}
