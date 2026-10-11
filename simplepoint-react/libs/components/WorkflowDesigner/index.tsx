import {
  CompressOutlined,
  RedoOutlined,
  UndoOutlined,
  ZoomInOutlined,
  ZoomOutOutlined,
} from '@ant-design/icons';
import Modeler from 'bpmn-js/lib/Modeler';
import NavigatedViewer from 'bpmn-js/lib/NavigatedViewer';
import {Alert, Button, Empty, Form, Input, InputNumber, Space, Tooltip, Typography} from 'antd';
import {forwardRef, useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState} from 'react';
import DueDateValue from './DueDateValue';
import TenantDirectorySelect from '../TenantDirectorySelect';
import ValueExpression from '../ExpressionEditor/ValueExpression';
import ConditionExpression from '../ExpressionEditor/ConditionExpression';
import {preserveXmlWhitespace} from './xmlWhitespace';
import {priorityBooleanLiteral} from './priority';
import {nonTemporalDueDateLiteral} from './dueDate';
import type {ConditionExpressionDraft} from '../ExpressionEditor/ConditionExpression';
import type {ExpressionVariable} from '../ExpressionEditor/model';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import BindingEditor from '../WorkflowForms/BindingEditor';
import type {WorkflowFormContract} from './contracts';
export type {WorkflowFormAdapter, WorkflowFormBinding} from './contracts';
import flowableModdle from './flowableModdle';
import platformModeling from './platformModeling';
import 'bpmn-js/dist/assets/diagram-js.css';
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css';
import './style.css';

type BpmnElement = {
  id: string;
  type?: string;
  businessObject?: Record<string, unknown> & {
    $type?: string;
    id?: string;
    name?: string;
    assignee?: string;
    candidateUsers?: string;
    candidateGroups?: string;
    dueDate?: string;
    priority?: string;
    documentation?: Array<{text?: string}>;
    conditionExpression?: {body?: string};
  };
};

export type WorkflowDesignerValidation = {
  valid: boolean;
  errors: string[];
  warnings: string[];
};

export type WorkflowDesignerProps = {
  xml: string;
  onChange?: (xml: string, validation: WorkflowDesignerValidation) => void;
  onDirtyChange?: (dirty: boolean) => void;
  readOnly?: boolean;
  /** Temporarily blocks edits during save without recreating the modeler or discarding its state. */
  disabled?: boolean;
  height?: number | string;
  className?: string;
  /** Form/variable metadata is supplied by the owning definition, never fetched by the canvas. */
  variables?: readonly ExpressionVariable[];
  /** The owning page supplies an authorized, scoped and cached read; the canvas makes no business requests. */
  resolveNumericLiteral?: import('../ExpressionEditor/ValueExpression').NumericLiteralResolver;
  resolvePriorityLiteral?: import('../ExpressionEditor/ValueExpression').LiteralResolver;
  formContract?: WorkflowFormContract;
  onFormContractChange?: (contract: WorkflowFormContract) => void;
  labels?: Partial<{
    empty: string;
    properties: string;
    noSelection: string;
    elementName: string;
    documentation: string;
    assignee: string;
    candidateUsers: string;
    candidateGroups: string;
    dueDate: string;
    priority: string;
    condition: string;
    fit: string;
    undo: string;
    redo: string;
    zoomIn: string;
    zoomOut: string;
  }>;
};

export type WorkflowDesignerHandle = {
  /** Flushes the latest canvas state before a save, independently of debounced change notifications. */
  getXml: () => Promise<string>;
};

const defaultLabels: Required<NonNullable<WorkflowDesignerProps['labels']>> = {
  empty: '暂无 BPMN 内容',
  properties: '元素属性',
  noSelection: '选择画布中的流程元素以编辑属性',
  elementName: '名称',
  documentation: '说明',
  assignee: '办理人',
  candidateUsers: '候选用户',
  candidateGroups: '候选角色',
  dueDate: '到期表达式',
  priority: '优先级',
  condition: '流转条件',
  fit: '适配画布',
  undo: '撤销',
  redo: '重做',
  zoomIn: '放大',
  zoomOut: '缩小',
};

/** Lightweight client validation; the server remains authoritative at save and publish time. */
export function validateWorkflowXml(xml: string): WorkflowDesignerValidation {
  const errors: string[] = [];
  const warnings: string[] = [];
  if (!xml.trim()) return {valid: false, errors: ['BPMN XML 不能为空'], warnings};
  const document = new DOMParser().parseFromString(xml, 'application/xml');
  if (document.querySelector('parsererror')) {
    return {valid: false, errors: ['BPMN XML 格式不正确'], warnings};
  }
  const processes = Array.from(document.getElementsByTagNameNS(
    'http://www.omg.org/spec/BPMN/20100524/MODEL', 'process'));
  if (processes.length !== 1) errors.push('流程必须且只能包含一个 process');
  const process = processes[0];
  if (process && !process.getAttribute('id')) errors.push('process id 不能为空');
  if (!document.getElementsByTagNameNS(
    'http://www.omg.org/spec/BPMN/20100524/MODEL', 'startEvent').length) {
    errors.push('流程必须包含开始事件');
  }
  if (!document.getElementsByTagNameNS(
    'http://www.omg.org/spec/BPMN/20100524/MODEL', 'endEvent').length) {
    errors.push('流程必须包含结束事件');
  }
  for (const task of Array.from(document.getElementsByTagNameNS(
    'http://www.omg.org/spec/BPMN/20100524/MODEL', 'userTask'))) {
    const assignment = ['assignee', 'candidateUsers', 'candidateGroups'].some(name =>
      Boolean(task.getAttributeNS('http://flowable.org/bpmn', name)));
    if (!assignment) warnings.push(`用户任务 ${task.getAttribute('name') || task.id} 尚未设置办理范围`);
  }
  return {valid: errors.length === 0, errors, warnings};
}

const WorkflowDesigner = forwardRef<WorkflowDesignerHandle, WorkflowDesignerProps>(function WorkflowDesigner({
  xml,
  onChange,
  onDirtyChange,
  readOnly = false,
  disabled = false,
  height = 640,
  className,
  variables: variableDeclarations,
  resolveNumericLiteral,
  resolvePriorityLiteral,
  formContract,
  onFormContractChange,
  labels: labelOverrides,
}: WorkflowDesignerProps, ref) {
  const {t} = useI18n();
  const labels = useMemo(() => ({...defaultLabels,
    empty: t('workflow.designer.empty', '暂无 BPMN 内容'), properties: t('workflow.designer.properties', '元素属性'),
    noSelection: t('workflow.designer.noSelection', '选择画布中的流程元素以编辑属性'),
    elementName: t('workflow.designer.name', '名称'), documentation: t('workflow.designer.documentation', '说明'),
    assignee: t('workflow.task.assignee', '办理人'), candidateUsers: t('workflow.designer.candidateUsers', '候选用户'),
    candidateGroups: t('workflow.designer.candidateGroups', '候选角色'), dueDate: t('workflow.designer.dueDate', '到期时间'),
    priority: t('workflow.task.priority', '优先级'), condition: t('workflow.designer.condition', '流转条件'),
    fit: t('workflow.designer.fit', '适配画布'), undo: t('workflow.designer.undo', '撤销'),
    redo: t('workflow.designer.redo', '重做'), zoomIn: t('workflow.designer.zoomIn', '放大'),
    zoomOut: t('workflow.designer.zoomOut', '缩小'), ...labelOverrides}), [labelOverrides, t]);
  const variables = useMemo(() => [{name: 'initiator', label: t('workflow.designer.initiator', '流程发起人'), type: 'string' as const,
    kind: 'user', readOnly: true}, ...(variableDeclarations ?? [])], [variableDeclarations, t]);
  // Property edits do not rebuild each value editor's declaration index/options.
  const variableViews = useMemo(() => ({
    assignee: variables.filter(variable => variable.kind === 'user'),
    users: variables.filter(variable => ['user', 'users'].includes(variable.kind ?? '')),
    roles: variables.filter(variable => ['role', 'roles'].includes(variable.kind ?? '')),
    dates: variables.filter(variable => ['date', 'date-time'].includes(variable.kind ?? '')),
    numbers: variables.filter(variable => variable.type === 'number'),
    conditions: variables.filter(variable => variable.type !== 'array'),
  }), [variables]);
  const canvasRef = useRef<HTMLDivElement>(null);
  const modelerRef = useRef<any>(undefined);
  const onChangeRef = useRef(onChange);
  const onDirtyChangeRef = useRef(onDirtyChange);
  const importedXmlRef = useRef('');
  const exportSequenceRef = useRef(0);
  const exportTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const importingRef = useRef(false);
  const importQueueRef = useRef<Promise<void>>(Promise.resolve());
  const [importing, setImporting] = useState(false);
  const propertyErrorsRef = useRef(new Set<string>());
  const conditionDraftsRef = useRef(new Map<string, ConditionExpressionDraft>());
  const [selected, setSelected] = useState<BpmnElement>();
  const [importError, setImportError] = useState<string>();
  const [, setRevision] = useState(0);

  useEffect(() => {
    onChangeRef.current = onChange;
    onDirtyChangeRef.current = onDirtyChange;
  }, [onChange, onDirtyChange]);

  // NavigatedViewer deliberately has no modeling/command-stack services.
  const service = useCallback((name: string) => modelerRef.current?.get(name, false), []);
  const commandStack = service('commandStack');
  const canUndo = Boolean(commandStack?.canUndo?.());
  const canRedo = Boolean(commandStack?.canRedo?.());

  const exportXml = useCallback(async () => {
    const modeler = modelerRef.current;
    if (!modeler || readOnly) return;
    const sequence = ++exportSequenceRef.current;
    try {
      const result = await modeler.saveXML({format: true});
      if (sequence !== exportSequenceRef.current || typeof result?.xml !== 'string') return;
      const exported = preserveXmlWhitespace(result.xml);
      importedXmlRef.current = exported;
      onDirtyChangeRef.current?.(true);
      onChangeRef.current?.(exported, validateWorkflowXml(exported));
      return exported;
    } catch {
      // The next command-stack change retries serialization; import errors are shown separately.
    }
  }, [readOnly]);

  useImperativeHandle(ref, () => ({getXml: async () => {
    if (exportTimerRef.current) clearTimeout(exportTimerRef.current);
    if (importingRef.current || propertyErrorsRef.current.size) throw new Error('请完成当前属性配置后保存');
    if (readOnly) return importedXmlRef.current;
    const result = await exportXml();
    if (typeof result !== 'string') throw new Error('流程设计器尚未完成加载');
    return result;
  }}), [exportXml, readOnly]);

  useEffect(() => {
    if (!canvasRef.current) return;
    importedXmlRef.current = '';
    importQueueRef.current = Promise.resolve();
    const Viewer = readOnly ? NavigatedViewer : Modeler;
    const options: Record<string, unknown> = {
      container: canvasRef.current,
      moddleExtensions: {flowable: flowableModdle},
    };
    if (!readOnly) options.additionalModules = [platformModeling, {
      workflowDesignLabels: ['value', {userTask: t('workflow.designer.createUserTask', '创建用户任务')}],
    }];
    const modeler = new Viewer(options);
    modelerRef.current = modeler;
    const eventBus = modeler.get('eventBus') as any;
    const selectionChanged = (event: {newSelection?: BpmnElement[]}) => {
      setSelected(event.newSelection?.length === 1 ? event.newSelection[0] : undefined);
    };
    const commandChanged = () => {
      const registry = modeler.get('elementRegistry') as {get: (id: string) => unknown};
      for (const key of propertyErrorsRef.current) {
        if (!registry.get(key.slice(0, key.lastIndexOf(':')))) propertyErrorsRef.current.delete(key);
      }
      for (const id of conditionDraftsRef.current.keys()) {
        if (!registry.get(id)) conditionDraftsRef.current.delete(id);
      }
      setRevision(value => value + 1);
      if (importingRef.current || readOnly) return;
      onDirtyChangeRef.current?.(true);
      if (exportTimerRef.current) clearTimeout(exportTimerRef.current);
      exportTimerRef.current = setTimeout(() => {void exportXml();}, 180);
    };
    eventBus.on('selection.changed', selectionChanged);
    eventBus.on('commandStack.changed', commandChanged);
    return () => {
      exportSequenceRef.current += 1;
      if (exportTimerRef.current) clearTimeout(exportTimerRef.current);
      modeler.destroy();
      modelerRef.current = undefined;
    };
  }, [exportXml, readOnly]);

  useEffect(() => {
    const modeler = modelerRef.current;
    if (!modeler || importedXmlRef.current === xml && !importingRef.current) return;
    let active = true;
    importingRef.current = true;
    setImporting(true);
    // bpmn-js imports mutate the canvas asynchronously. Serialize them so an older import
    // cannot overwrite a newer document after a rapid definition/version change.
    importQueueRef.current = importQueueRef.current.catch(() => {}).then(async () => {
      if (!active || modelerRef.current !== modeler) return;
      if (xml) await modeler.importXML(xml);
      else modeler.clear();
      if (!active) return;
      importingRef.current = false;
      setImporting(false);
      importedXmlRef.current = xml;
      propertyErrorsRef.current.clear();
      conditionDraftsRef.current.clear();
      setSelected(undefined);
      setImportError(undefined);
      if (xml) modeler.get('canvas').zoom('fit-viewport');
      onDirtyChangeRef.current?.(false);
      setRevision(value => value + 1);
    }).catch((error: unknown) => {
      if (active) {importingRef.current = false;
        setImporting(false);
        setImportError(error instanceof Error ? error.message : 'BPMN 导入失败');}
    });
    return () => { active = false; };
  }, [readOnly, xml]);

  const updateProperties = useCallback((properties: Record<string, unknown>) => {
    if (!selected || readOnly || disabled) return;
    service('modeling')?.updateProperties(selected, properties);
    // diagram-js elements own non-enumerable parent/connection accessors. Never spread-copy
    // them into plain objects; command-stack changes already trigger a React revision.
  }, [disabled, readOnly, selected, service]);

  const updateDocumentation = useCallback((value: string) => {
    const moddle = service('moddle');
    updateProperties({
      documentation: value ? [moddle.create('bpmn:Documentation', {text: value})] : [],
    });
  }, [service, updateProperties]);

  const updateCondition = useCallback((value: string) => {
    const moddle = service('moddle');
    updateProperties({
      conditionExpression: value ? moddle.create('bpmn:FormalExpression', {body: value}) : undefined,
    });
  }, [service, updateProperties]);

  const business = selected?.businessObject;
  const propertyValidity = (field: string) => (valid: boolean) => {
    if (!selected) return;
    const key = `${selected.id}:${field}`;
    if (valid) propertyErrorsRef.current.delete(key);
    else {propertyErrorsRef.current.add(key); onDirtyChangeRef.current?.(true);}
  };
  const type = business?.$type ?? selected?.type ?? '';
  const isUserTask = type === 'bpmn:UserTask';
  const isSequenceFlow = type === 'bpmn:SequenceFlow';
  const isLabelEditable = Boolean(selected && !['bpmn:Definitions', 'label'].includes(type));
  const zoom = (delta: number) => {
    const canvas = service('canvas');
    const current = Number(canvas?.zoom?.()) || 1;
    canvas?.zoom?.(Math.max(0.2, Math.min(4, current + delta)));
  };

  return <div className={`workflow-designer ${readOnly ? 'is-read-only' : ''} ${className ?? ''}`}
    inert={disabled || importing || undefined} aria-busy={disabled || importing}
    style={{height}}>
    <div className="workflow-designer__toolbar" role="toolbar" aria-label="BPMN designer tools">
      {/* BPMN navigation is a specialized canvas interaction not represented by table actions. */}
      <Space.Compact>
        <Tooltip title={labels.undo}><Button aria-label={labels.undo} icon={<UndoOutlined/>}
          disabled={readOnly || !canUndo} onClick={() => service('commandStack')?.undo?.()}/></Tooltip>
        <Tooltip title={labels.redo}><Button aria-label={labels.redo} icon={<RedoOutlined/>}
          disabled={readOnly || !canRedo} onClick={() => service('commandStack')?.redo?.()}/></Tooltip>
        <Tooltip title={labels.zoomOut}><Button aria-label={labels.zoomOut} icon={<ZoomOutOutlined/>}
          onClick={() => zoom(-0.1)}/></Tooltip>
        <Tooltip title={labels.zoomIn}><Button aria-label={labels.zoomIn} icon={<ZoomInOutlined/>}
          onClick={() => zoom(0.1)}/></Tooltip>
        <Tooltip title={labels.fit}><Button aria-label={labels.fit} icon={<CompressOutlined/>}
          onClick={() => service('canvas')?.zoom?.('fit-viewport')}/></Tooltip>
      </Space.Compact>
    </div>
    {importError && <Alert className="workflow-designer__error" type="error" showIcon
      message={importError}/>}
    <div className="workflow-designer__body">
      <div ref={canvasRef} className="workflow-designer__canvas"/>
      <aside className="workflow-designer__properties" aria-label={labels.properties}>
        <Typography.Title level={5}>{labels.properties}</Typography.Title>
        {!selected || !business ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={labels.noSelection}/> : <Form layout="vertical" size="small">
          {/* A stable layout form avoids retained native form DOM on node changes. Only the
              node-specific expression controls reset; their values are modeler commands, not submissions. */}
          <Typography.Text type="secondary" className="workflow-designer__element-id">
            {business.id ?? selected.id} · {type}
          </Typography.Text>
          {isLabelEditable && <Form.Item label={labels.elementName}>
            <Input value={String(business.name ?? '')} disabled={readOnly || disabled}
              onChange={event => updateProperties({name: event.target.value || undefined})}/>
          </Form.Item>}
          {isLabelEditable && <Form.Item label={labels.documentation}>
            <Input.TextArea value={String(business.documentation?.[0]?.text ?? '')}
              disabled={readOnly || disabled} autoSize={{minRows: 2, maxRows: 6}}
              onChange={event => updateDocumentation(event.target.value)}/>
          </Form.Item>}
          {isUserTask && <>
            <Form.Item label={labels.assignee}><ValueExpression key={`${selected.id}:assignee`} value={String(business.assignee ?? '')}
              literalIdentity resolveNumericLiteral={resolveNumericLiteral}
              onValidityChange={propertyValidity('assignee')}
              disabled={readOnly || disabled} variables={variableViews.assignee}
              onChange={assignee => updateProperties({assignee})}
              renderLiteral={(value, onChange) => <TenantDirectorySelect kind="users" multiple={false}
                disabled={readOnly || disabled} value={value} onChange={next => onChange(next)}/>}/></Form.Item>
            <Form.Item label={labels.candidateUsers}><ValueExpression key={`${selected.id}:candidateUsers`} value={String(business.candidateUsers ?? '')}
              literalIdentity resolveNumericLiteral={resolveNumericLiteral}
              onValidityChange={propertyValidity('candidateUsers')}
              disabled={readOnly || disabled} variables={variableViews.users}
              onChange={candidateUsers => updateProperties({candidateUsers})}
              renderLiteral={(value, onChange) => <TenantDirectorySelect kind="users" multiple maxCount={50}
                disabled={readOnly || disabled} value={value?.split(',').filter(Boolean) ?? []}
                onChange={next => onChange(next.join(',') || undefined)}/>}/></Form.Item>
            <Form.Item label={labels.candidateGroups}><ValueExpression key={`${selected.id}:candidateGroups`} value={String(business.candidateGroups ?? '')}
              literalIdentity resolveNumericLiteral={resolveNumericLiteral}
              onValidityChange={propertyValidity('candidateGroups')}
              disabled={readOnly || disabled} variables={variableViews.roles}
              onChange={candidateGroups => updateProperties({candidateGroups})}
              renderLiteral={(value, onChange) => <TenantDirectorySelect kind="roles" multiple maxCount={50}
                disabled={readOnly || disabled} value={value?.split(',').filter(Boolean) ?? []}
                onChange={next => onChange(next.join(',') || undefined)}/>}/></Form.Item>
            <Form.Item label={labels.dueDate}><ValueExpression key={`${selected.id}:dueDate`} value={String(business.dueDate ?? '')}
              validateValue={value => nonTemporalDueDateLiteral(value)
                ? t('workflow.editor.dueDateType', '到期时间常量必须是日期或时长文本，不能使用布尔值或数值') : undefined}
              onValidityChange={propertyValidity('dueDate')}
              disabled={readOnly || disabled} variables={variableViews.dates}
              onChange={dueDate => updateProperties({dueDate})}
              renderLiteral={(value, onChange) => <DueDateValue value={value} onChange={onChange}
                disabled={readOnly || disabled}/>}/></Form.Item>
            <Form.Item label={labels.priority}><ValueExpression key={`${selected.id}:priority`} value={String(business.priority ?? '')}
              resolveLiteral={resolvePriorityLiteral}
              validateValue={value => priorityBooleanLiteral(value)
                ? t('workflow.editor.priorityType', '优先级常量必须是数值或整数文本，不能使用布尔值') : undefined}
              literalPreviewFailure={t('workflow.editor.priorityPreviewFailed', '优先级预览暂不可用，原配置已保留；可返回高级模式或稍后重试')}
              onValidityChange={propertyValidity('priority')}
              disabled={readOnly || disabled} variables={variableViews.numbers}
              onChange={priority => updateProperties({priority})}
              renderLiteral={(value, onChange) => <InputNumber disabled={readOnly || disabled} min={0} max={100} precision={0}
                style={{width: '100%'}} value={value ? Number(value) : undefined}
                onChange={next => onChange(next == null ? undefined : String(next))}/>}/></Form.Item>
          </>}
          {isSequenceFlow && <Form.Item label={labels.condition}>
            <ConditionExpression key={`${selected.id}:condition`} value={String(business.conditionExpression?.body ?? '')}
              disabled={readOnly || disabled} variables={variableViews.conditions}
              initialDraft={conditionDraftsRef.current.get(selected.id)}
              onDraftChange={draft => {conditionDraftsRef.current.set(selected.id, draft);}}
              onValidityChange={propertyValidity('condition')}
              onChange={next => updateCondition(next ?? '')}/>
          </Form.Item>}
        </Form>}
        {selected && isUserTask && formContract && <BindingEditor key={selected.id}
          elementId={selected.id} binding={formContract.taskBindings[selected.id]}
          disabled={readOnly || disabled || !onFormContractChange} onChange={binding => {
            const taskBindings = {...formContract.taskBindings};
            if (binding) taskBindings[selected.id] = binding;
            else delete taskBindings[selected.id];
            onFormContractChange?.({...formContract, taskBindings});
          }}/>}
      </aside>
    </div>
    {!xml && <div className="workflow-designer__empty"><Empty description={labels.empty}/></div>}
  </div>;
});

export default WorkflowDesigner;
