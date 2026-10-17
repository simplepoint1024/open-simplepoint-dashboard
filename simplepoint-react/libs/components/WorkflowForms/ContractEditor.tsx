import DataTable from '../DataTable';
import TableActions from '../Table/Actions';
import SForm from '../SForm';
import {App, Modal} from 'antd';
import {useLayoutEffect, useMemo, useRef, useState, type ComponentProps} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {WorkflowFormContract, WorkflowVariable} from '../WorkflowDesigner/contracts';
import {reservedVariable} from '../WorkflowDesigner/contracts';
import {variableSchema} from './model';
import BindingEditor from './BindingEditor';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';

type FormSchema = ComponentProps<typeof SForm>['schema'];
const types: WorkflowVariable['type'][] = ['string', 'number', 'decimal', 'boolean', 'date', 'date-time', 'user', 'users', 'role', 'roles'];
const scopes: WorkflowVariable['scope'][] = ['start', 'task', 'both'];

/** Client-only, bounded variable declarations. All persistence still uses the owning draft's revision. */
export default function ContractEditor({value, onChange, disabled, onBlockedChange}: {
  value: WorkflowFormContract; onChange: (value: WorkflowFormContract) => void; disabled?: boolean; onBlockedChange?: (blocked: boolean) => void;
}) {
  const {t} = useI18n();
  const {message} = App.useApp(), selected = usePageSelected();
  const [editing, setEditing] = useState<WorkflowVariable>();
  const [original, setOriginal] = useState<string>();
  const [removing, setRemoving] = useState<string>(), removingRef = useRef<string | undefined>(undefined);
  const [discard, setDiscard] = useState(false), initial = useRef<WorkflowVariable | undefined>(undefined);
  const editingRef = useRef<WorkflowVariable | undefined>(undefined), valueRef = useRef(value);
  valueRef.current = value;
  const blocked = !!editing || !!removing || discard;
  usePageRetention(!blocked, blocked);
  useLayoutEffect(() => {onBlockedChange?.(blocked); return () => {onBlockedChange?.(false);};}, [blocked, onBlockedChange]);
  const typeLabels: Record<string, string> = {
    string: t('workflow.variable.type.string', '文本'), number: t('workflow.variable.type.number', '数值'),
    decimal: t('workflow.variable.type.decimal', '精确数值'),
    boolean: t('workflow.variable.type.boolean', '布尔值'), date: t('workflow.variable.type.date', '日期'),
    'date-time': t('workflow.variable.type.date-time', '日期时间'), user: t('workflow.variable.type.user', '用户'),
    users: t('workflow.variable.type.users', '多个用户'), role: t('workflow.variable.type.role', '角色'),
    roles: t('workflow.variable.type.roles', '多个角色'),
  };
  const scopeLabels: Record<string, string> = {start: t('workflow.variable.scope.start', '发起时'),
    task: t('workflow.variable.scope.task', '办理时'), both: t('workflow.variable.scope.both', '发起和办理时')};
  const typeLabel = (type: string) => typeLabels[type] ?? type;
  const scopeLabel = (scope: string) => scopeLabels[scope] ?? scope;
  const schema = useMemo<FormSchema>(() => ({type: 'object', required: ['name', 'label', 'type', 'scope'], properties: {
    name: {type: 'string', title: t('workflow.variable.name', '变量标识'), pattern: '^[A-Za-z_][A-Za-z0-9_]{0,79}$', maxLength: 80},
    label: {type: 'string', title: t('workflow.variable.label', '显示名称'), minLength: 1, maxLength: 160, pattern: '\\S'},
    type: {type: 'string', title: t('workflow.variable.type', '类型'), oneOf: types.map(type => ({const: type, title: typeLabel(type)}))},
    scope: {type: 'string', title: t('workflow.variable.scope', '填写阶段'), oneOf: scopes.map(scope => ({const: scope, title: scopeLabel(scope)}))},
    required: {type: 'boolean', title: t('workflow.variable.required', '必填')},
    ...(editing ? {defaultValue: {...variableSchema(editing), title: t('workflow.variable.default', '默认值')}} : {}),
  }}), [editing?.type, t]);
  const open = (variable?: WorkflowVariable) => {
    if (disabled || !selected || editingRef.current || removingRef.current || !variable && value.variables.length >= 100) return;
    setOriginal(variable?.name);
    const draft = variable ? structuredClone(variable) : {name: '', label: '', type: 'string' as const, scope: 'both' as const, required: false};
    editingRef.current = draft; initial.current = structuredClone(draft); setEditing(draft); setDiscard(false);
  };
  const abandon = () => {editingRef.current = undefined; initial.current = undefined; setEditing(undefined); setDiscard(false);};
  const close = () => {if (JSON.stringify(editingRef.current) !== JSON.stringify(initial.current)) setDiscard(true); else abandon();};
  const save = (variable?: WorkflowVariable) => {
    if (!variable || disabled || !selected || !editingRef.current) return;
    const name = variable.name.trim();
    if (reservedVariable(name)
      || value.variables.some(item => item.name === name && item.name !== original)) {
      message.error(t('workflow.variable.duplicate', '变量标识重复或使用了系统保留标识')); return;
    }
    const variables = value.variables.filter(item => item.name !== original);
    if (variables.length >= 100) return;
    // Renaming a bound variable would silently break an external form; require the binding to be updated first.
    if (original && original !== name && [value.startBinding, ...Object.values(value.taskBindings)]
      .some(binding => binding && Object.values(binding.variableMappings).includes(original))) {
      message.error(t('workflow.variable.bound', '此变量已被表单绑定，请先调整映射')); return;
    }
    const next = {...variable, name, label: variable.label.trim()};
    onChange({...value, variables: original ? value.variables.map(item => item.name === original ? next : item)
      : [...variables, next]});
    abandon();
  };
  const bound = (name: string) => [valueRef.current.startBinding, ...Object.values(valueRef.current.taskBindings)]
    .some(binding => binding && Object.values(binding.variableMappings).includes(name));
  const remove = () => {
    const name = removingRef.current;
    if (!name || disabled || !selected) return;
    if (bound(name)) {message.error(t('workflow.variable.bound', '此变量已被表单绑定，请先调整映射')); return;}
    onChange({...valueRef.current, variables: valueRef.current.variables.filter(item => item.name !== name)});
    removingRef.current = undefined; setRemoving(undefined);
  };
  return <>
    <BindingEditor elementId="process:start" binding={value.startBinding} variables={value.variables} disabled={disabled || blocked}
      onChange={startBinding => onChange({...value, startBinding})}/>
    <DataTable rowKey="name" storageKey="workflow.variable.contract" dataSource={value.variables}
      pagination={{pageSize: 10, showSizeChanger: true}}
      query={{mode: 'local', scope: 'complete', fields: {
        name: {type: 'string'}, label: {type: 'string'}, type: {type: 'string'}, scope: {type: 'string'},
      }}}
      toolbarExtra={<TableActions selectedKeys={[]} selectedRows={[]} buttons={disabled ? [] : [{key: 'add', sort: 10,
        text: t('workflow.variable.add', '添加变量'), type: 'primary', icon: 'PlusOutlined',
        disabled: value.variables.length >= 100 || blocked, argumentMinSize: 0, argumentMaxSize: 0}]}
        onButtonEvents={{add: () => open()}}/>}
      columns={[
        {key: 'name', dataIndex: 'name', title: t('workflow.variable.name', '变量标识')},
        {key: 'label', dataIndex: 'label', title: t('workflow.variable.label', '显示名称')},
        {key: 'type', dataIndex: 'type', title: t('workflow.variable.type', '类型'), render: typeLabel},
        {key: 'scope', dataIndex: 'scope', title: t('workflow.variable.scope', '填写阶段'), render: scopeLabel},
        {key: 'actions', title: t('workflow.actions', '操作'), render: (_, row) => <TableActions
          selectedKeys={[row.name]} selectedRows={[row]} buttons={disabled ? [] : [
            {key: 'edit', sort: 10, text: t('workflow.action.editMetadata', '基础信息'), type: 'link'},
            {key: 'remove', sort: 20, text: t('workflow.variable.remove', '删除'), type: 'link', danger: true},
          ]} isButtonDisabled={() => blocked || !selected} onButtonEvents={{edit: () => open(row), remove: () => {
            if (disabled || !selected || removingRef.current || editingRef.current) return;
            if (bound(row.name)) {
              message.error(t('workflow.variable.bound', '此变量已被表单绑定，请先调整映射')); return;
            }
            removingRef.current = row.name; setRemoving(row.name);
          }}}/>},
      ]}/>
    <Modal wrapClassName="workflow-variable-editor" title={t('workflow.variable.configure', '配置变量')} open={Boolean(editing) && selected} footer={null}
      destroyOnHidden={!editing} onCancel={close}>
      {editing && <SForm schema={schema} formData={editing} disabled={disabled}
        onChange={event => {
          const next = event.formData as WorkflowVariable;
          const draft = next.type === editing.type ? next : {...next, defaultValue: undefined};
          editingRef.current = draft; setEditing(draft);
        }} onSubmit={event => save(event.formData as WorkflowVariable)}/>}
    </Modal>
    <Modal wrapClassName="workflow-variable-removal" title={t('workflow.variable.removeConfirm', '删除此变量？')}
      open={!!removing && selected} destroyOnHidden={!removing} onCancel={() => {removingRef.current = undefined; setRemoving(undefined);}}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'cancel', sort: 1, text: t('workflow.dialog.cancel', '取消')},
        {key: 'remove', sort: 2, text: t('workflow.variable.remove', '删除'), danger: true, type: 'primary', disabled},
      ]} onButtonEvents={{cancel: () => {removingRef.current = undefined; setRemoving(undefined);}, remove}}/>}>
      {t('workflow.variable.removeBoundary', '请同步检查节点表达式；已发布版本不受影响。')}
    </Modal>
    <Modal wrapClassName="workflow-variable-discard" open={discard && selected} destroyOnHidden={!discard}
      title={t('workflow.unsaved.title', '放弃未保存的修改？')} onCancel={() => setDiscard(false)}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'continue', sort: 1, text: t('workflow.unsaved.continue', '继续编辑')},
        {key: 'discard', sort: 2, text: t('workflow.unsaved.discard', '放弃修改'), danger: true, type: 'primary'},
      ]} onButtonEvents={{continue: () => setDiscard(false), discard: abandon}}/>}/>
  </>;
}
