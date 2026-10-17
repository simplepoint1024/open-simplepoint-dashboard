import {createIcon} from '@simplepoint/shared/types/icon';
import HelpTip from '../HelpTip';
import {designerFormContext} from './presentation';
import {useRef, useState} from 'react';
import {Alert, Modal, Tabs} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import DataTable from '../DataTable';
import TableActions from '../Table/Actions';
import SForm, {type SFormHandle} from '../SForm';
import type {ComponentDescriptor, DesignDocument, DesignField} from './contracts.ts';
import type {FormFieldWritePolicy} from '../FormRuntime/contracts.ts';
import {newDataField} from './model.ts';
import {sameDesignValue} from './draftState.ts';
import FieldEditor from './FieldEditor';
import {applyFieldEdit, applyIndexEdit, DataEditError, fieldEdit, newIndex, persistentField, removeIndex,
  type DesignIndex, type FieldEdit} from './dataModel.ts';

type IndexEdit = {unique: boolean; fields: {fieldId: string}[]};
type Session = {kind: 'field'; base: DesignField; value: FieldEdit; creating: boolean}
  | {kind: 'index'; base: DesignIndex; value: IndexEdit; creating: boolean}
  | {kind: 'remove'; base: DesignIndex};
type Props = {modelScope?: 'form' | 'detail'; section?: 'fields' | 'indexes'; compact?: boolean; document: DesignDocument; components: readonly ComponentDescriptor[]; writePolicies: readonly FormFieldWritePolicy[];
  disabled: boolean; onChange: (document: DesignDocument) => void; onReuse?: (field: DesignField) => void; maxFields?: number; maxIndexes?: number};

export default function DataSchemaEditor({document, components, writePolicies, disabled, onChange, onReuse, maxFields = 300, maxIndexes = 50, section, compact = false, modelScope = 'form'}: Props) {
  const {t} = useI18n(), selected = usePageSelected();
  const [tab, setTab] = useState('fields'), [session, setSession] = useState<Session>();
  const [failure, setFailure] = useState('');
  const [discarding, setDiscarding] = useState(false);
  const form = useRef<SFormHandle>(null);
  const locked = disabled || !selected;
  const initial = session?.kind === 'field' ? fieldEdit(session.base) : session?.kind === 'index'
    ? {unique: session.base.unique, fields: session.base.fieldIds.map(fieldId => ({fieldId}))} : undefined;
  const dirty = Boolean(session && session.kind !== 'remove' && !sameDesignValue(initial, session.value));
  usePageRetention(!session, Boolean(session));
  const close = () => {
    if (!selected) return;
    if (dirty) setDiscarding(true);
    else {setSession(undefined); setFailure('');}
  };
  const open = (next: Session) => {if (!locked) {setSession(next); setFailure('');}};
  const apply = (action: () => DesignDocument) => {
    if (locked) return;
    try {onChange(action()); setSession(undefined); setFailure('');}
    catch (error) {
      const messages: Record<DataEditError['code'], string> = {
        conflict: t('app.designer.dataConflict', '该定义已发生变化，请关闭后重新编辑。'),
        fieldType: t('app.designer.dataFieldType', '此类型与已有控件或规则不兼容。'),
        constraints: t('app.designer.dataConstraints', '请检查字段名称、长度、精度和写入规则。'),
        defaultValue: t('app.designer.dataDefaultError', '默认值须符合字段类型、长度、精度和选项；存储非空字段须有非空默认值。'),
        choices: t('app.designer.dataChoicesError', '选项须符合字段类型与存储约束，且不能有语义相同的重复值。'),
        indexFields: t('app.designer.dataIndexError', '请选择 1 至 8 个不重复的持久化字段，排列顺序就是索引顺序。'),
        limit: t('app.designer.dataLimit', '已达到字段、选项或索引数量上限。'),
      };
      setFailure(error instanceof DataEditError ? messages[error.code] : t('app.designer.invalid', '无法应用此修改，请检查表单配置。'));
    }
  };
  const indexFields = (index: DesignIndex) => index.fieldIds.map(id => document.fields.find(field => field.fieldId === id)?.label ?? id).join(' → ');
  const newField = () => {const base = newDataField(document, 'STRING', t('app.designer.newFieldName', '新字段'));
    open({kind: 'field', base, value: fieldEdit(base), creating: true});};
  const editIndex = (base: DesignIndex, creating = false) => open({kind: 'index', base, creating,
    value: {unique: base.unique, fields: base.fieldIds.map(fieldId => ({fieldId}))}});
  const buttons = (creating: boolean) => [
    {key: 'cancel', icon: 'CloseOutlined', sort: 0, text: t('app.designer.cancelData', '取消')},
    {key: 'apply', icon: 'CheckOutlined', sort: 1, type: 'primary' as const, text: creating ? t('app.designer.addData', '添加') : t('app.designer.applyData', '应用修改'), disabled: locked},
  ];
  return <div className="sp-form-data-editor">
    <div className="sp-form-editor-help"><HelpTip>{t('app.designer.dataMigrationHint', '修改先保存为草稿，发布后生效。结构变更由 DNA 评估，影响存量数据的变更需要迁移审核。')}</HelpTip></div>
    {!section && <Tabs activeKey={section ?? tab} onChange={setTab} items={[
      {key: 'fields', icon: createIcon('UnorderedListOutlined'), label: t('app.designer.fields', '字段')}, {key: 'indexes', icon: createIcon('DatabaseOutlined'), label: t('app.designer.indexes', '索引')},
    ]}/>}
    {(section ?? tab) === 'fields' ? <DataTable<DesignField> cards={compact ? false : undefined} settings={!compact} selectable={!compact} scroll={compact ? {x: 300} : undefined} rowKey="fieldId" storageKey="app.form.designer.fields" dataSource={document.fields}
      pagination={compact && document.fields.length <= 20 ? false : {pageSize: 20, simple: compact}} query={compact ? undefined : {mode: 'local', scope: 'complete', contextKey: 'fields',
        fields: {label: {type: 'string'}, storageCode: {type: 'string'}, type: {type: 'string'}}}}
      toolbarExtra={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'addField', icon: 'PlusOutlined', sort: 0, text: t('app.designer.addField', '新增字段'), disabled: locked || document.fields.length >= maxFields},
      ]} onButtonEvents={{addField: newField}}/>}
      columns={[
        {key: 'label', dataIndex: 'label', width: compact ? 140 : undefined, title: t('app.designer.fieldLabel', '字段名称')},
        {key: 'storageCode', dataIndex: 'storageCode', title: t('app.designer.storageCode', '字段编码')},
        {key: 'type', dataIndex: 'type', width: compact ? 80 : undefined, title: t('app.designer.fieldType', '数据类型')},
        {key: 'default', title: t('app.designer.defaultMode', '默认值'), render: (_: unknown, field: DesignField) => field.defaultValue == null ? '—'
          : field.defaultValue.kind === 'NULL' ? 'NULL' : <span title={field.defaultValue.value ?? ''}>{(field.defaultValue.value ?? '').slice(0, 80)}</span>},
        {key: 'actions', width: compact ? 80 : undefined, fixed: compact ? 'right' as const : undefined, title: t('app.designer.actions', '操作'), render: (_: unknown, field: DesignField) => <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
          {key: 'edit', icon: 'EditOutlined', sort: 0, text: compact ? t('table.button.edit', '编辑') : t('app.designer.editField', '编辑字段'), disabled: locked},
          ...(onReuse ? [{key: 'reuse', icon: 'PlusOutlined', sort: 1, text: t('app.designer.reuse', '添加到当前场景'), disabled: locked}] : []),
        ]} onButtonEvents={{edit: () => open({kind: 'field', base: structuredClone(field), value: fieldEdit(field), creating: false}),
          reuse: () => {if (!locked) onReuse?.(field);}}}/>},
      ].filter(column => !compact || !['storageCode', 'default'].includes(column.key))}/> : <DataTable<DesignIndex> cards={compact ? false : undefined} settings={!compact} selectable={!compact} scroll={compact ? {x: 370} : undefined} rowKey="indexId" storageKey="app.form.designer.indexes" dataSource={document.indexes} pagination={compact ? false : {pageSize: 20}}
      toolbarExtra={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'addIndex', icon: 'PlusOutlined', sort: 0, text: t('app.designer.addIndex', '新增索引'), disabled: locked || document.indexes.length >= maxIndexes || !document.fields.some(persistentField)},
      ]} onButtonEvents={{addIndex: () => editIndex(newIndex(), true)}}/>}
      columns={[
        {key: 'code', dataIndex: 'storageCode', title: t('app.designer.indexCode', '索引编码')},
        {key: 'fields', width: compact ? 140 : undefined, title: t('app.designer.indexFields', '索引字段（按顺序）'), render: (_: unknown, index: DesignIndex) => indexFields(index)},
        {key: 'unique', width: compact ? 80 : undefined, title: t('app.designer.indexType', '索引类型'), render: (_: unknown, index: DesignIndex) => index.unique
          ? t('app.designer.uniqueIndex', '唯一索引') : t('app.designer.regularIndex', '普通索引')},
        {key: 'actions', width: compact ? 150 : undefined, fixed: compact ? 'right' as const : undefined, title: t('app.designer.actions', '操作'), render: (_: unknown, index: DesignIndex) => <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
          {key: 'edit', icon: 'EditOutlined', sort: 0, text: compact ? t('table.button.edit', '编辑') : t('app.designer.editIndex', '编辑索引'), disabled: locked},
          {key: 'remove', icon: 'DeleteOutlined', sort: 1, text: compact ? t('table.button.delete', '删除') : t('app.designer.removeIndex', '移除索引'), danger: true, disabled: locked},
        ]} onButtonEvents={{edit: () => editIndex(structuredClone(index)), remove: () => open({kind: 'remove', base: structuredClone(index)})}}/>},
      ].filter(column => !compact || column.key !== 'code')}/>}
    <Modal open={Boolean(session) && selected} width={720} destroyOnHidden maskClosable={false} onCancel={close}
      wrapClassName="sp-form-data-modal" title={session?.kind === 'field' ? t('app.designer.editField', '编辑字段') : t('app.designer.editIndex', '编辑索引')}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={session?.kind === 'remove' ? [
        {key: 'cancel', icon: 'CloseOutlined', sort: 0, text: t('app.designer.cancelData', '取消')},
        {key: 'apply', icon: 'CheckOutlined', sort: 1, text: compact ? t('table.button.delete', '删除') : t('app.designer.removeIndex', '移除索引'), danger: true, disabled: locked},
      ] : buttons(Boolean(session && session.creating))} onButtonEvents={{cancel: close,
        apply: () => {if (!locked && session) session.kind === 'remove' ? apply(() => removeIndex(document, session.base)) : form.current?.submit();}}}/>}
    >
      {failure && <Alert type="error" showIcon title={failure}/>}
      {session?.kind === 'field' && <FieldEditor document={document} field={session.base} value={session.value}
        components={components} writePolicies={writePolicies} disabled={locked} formRef={form}
        onChange={value => setSession({...session, value})}
        onSubmit={value => apply(() => applyFieldEdit(document, session.base, value, components, writePolicies, session.creating, modelScope))}/>}
      {session?.kind === 'index' && <SForm formContext={designerFormContext} ref={form} idPrefix="data_index" hideSubmit disabled={locked}
        schema={{type: 'object', required: ['fields'], properties: {
          unique: {type: 'boolean', title: t('app.designer.uniqueIndex', '唯一索引')},
          fields: {type: 'array', title: t('app.designer.indexFields', '索引字段（按顺序）'), minItems: 1, maxItems: 8,
            items: {type: 'object', required: ['fieldId'], properties: {fieldId: {type: 'string', title: t('app.designer.fieldLabel', '字段名称'),
              oneOf: document.fields.filter(persistentField).map(field => ({const: field.fieldId, title: `${field.label} (${field.storageCode})`}))}}}},
        }}} formData={session.value} onChange={({formData}, changedField) => {
          if (!locked && changedField && formData) setSession({...session, value: formData as IndexEdit});
        }} onSubmit={({formData}) => {if (formData) {const value = formData as IndexEdit;
          apply(() => applyIndexEdit(document, session.base, {unique: Boolean(value.unique), fieldIds: value.fields.map(field => field.fieldId)}, session.creating));}}}/>}
      {session?.kind === 'remove' && <Alert type="warning" showIcon title={t('app.designer.removeIndexHint', '从草稿中移除此索引？数据库中的变更将在发布时评估。')}
        description={`${session.base.storageCode}: ${indexFields(session.base)}`}/>}
    </Modal>
    <Modal open={discarding && selected} title={t('app.designer.discardDataEdit', '放弃尚未应用的修改？')}
      wrapClassName="sp-form-data-discard" onCancel={() => setDiscarding(false)} footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'continue', icon: 'EditOutlined', sort: 0, text: t('app.designer.continueDataEdit', '继续编辑')},
        {key: 'discard', icon: 'DeleteOutlined', sort: 1, text: t('app.designer.discardData', '放弃修改'), danger: true},
      ]} onButtonEvents={{continue: () => setDiscarding(false), discard: () => {
        if (selected) {setDiscarding(false); setSession(undefined); setFailure('');}
      }}}/>}/>
  </div>;
}
