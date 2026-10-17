import {Alert, Form, Input, InputNumber, Select, Typography} from 'antd';
import {useEffect, useMemo, useRef} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {ExpressionVariable} from '../ExpressionEditor/model';
import ValueExpression, {type ValueExpressionDraft} from '../ExpressionEditor/ValueExpression';
import ConditionExpression, {type ConditionExpressionDraft} from '../ExpressionEditor/ConditionExpression';
import {MULTI_INSTANCE_TYPE, multiInstanceCounters, multiInstanceIssues} from './multiInstance';
import {multiInstanceMessage} from './multiInstanceMessages';
import {STANDARD_LOOP_TYPE, type ActivityLoop, type ExecutionMode} from './loopCharacteristics';
import StandardLoopEditor from './StandardLoopEditor';

/** Specialized BPMN properties; values remain modeler commands so undo/redo and XML are authoritative. */
export default function ActivityExecutionEditor({loop, variables, declarations, ancestorNames, disabled, onModeChange, onChange,
  onValidityChange, completionDraft, onCompletionDraftChange, onCompletionValidityChange,
  sourceDraft, onSourceDraftChange, onSourceValidityChange}: {
  loop?: ActivityLoop;
  variables: readonly ExpressionVariable[];
  declarations: readonly ExpressionVariable[];
  ancestorNames: readonly string[];
  disabled?: boolean;
  onModeChange: (mode: ExecutionMode) => void;
  onChange: (patch: Partial<ActivityLoop>) => void;
  onValidityChange: (valid: boolean) => void;
  completionDraft?: ConditionExpressionDraft;
  onCompletionDraftChange: (draft: ConditionExpressionDraft) => void;
  onCompletionValidityChange: (valid: boolean) => void;
  sourceDraft?: ValueExpressionDraft;
  onSourceDraftChange: (draft: ValueExpressionDraft) => void;
  onSourceValidityChange: (valid: boolean) => void;
}) {
  const {t} = useI18n();
  const multi = loop?.$type === MULTI_INSTANCE_TYPE;
  const standard = loop?.$type === STANDARD_LOOP_TYPE;
  const mode = multi ? loop.isSequential ? 'sequential' : 'parallel' : standard ? 'standard' : loop ? 'legacy' : 'none';
  const issues = multiInstanceIssues(loop, declarations, ancestorNames);
  const validity = useRef(onValidityChange); validity.current = onValidityChange;
  const issueKey = issues.join(',');
  useEffect(() => {if (!standard) validity.current(!issues.length);}, [issueKey, standard]);
  const collections = useMemo(() => declarations.filter(variable => ['users', 'roles'].includes(variable.kind ?? '')), [declarations]);
  const numbers = useMemo(() => variables.filter(variable => variable.type === 'number'), [variables]);
  const completionVariables = useMemo(() => [...variables.filter(variable => variable.type !== 'array'),
    ...multiInstanceCounters({total: t('workflow.multi.total', '实例总数'), active: t('workflow.multi.active', '未完成实例数'),
      completed: t('workflow.multi.completed', '已完成实例数'), index: t('workflow.multi.index', '当前序号（从 0 开始）')})], [variables, t]);
  const update = (patch: Partial<ActivityLoop>) => {
    if (disabled || !multi) return;
    validity.current(!multiInstanceIssues({...loop, ...patch}, declarations, ancestorNames).length);
    onChange(patch);
  };
  return <section className="workflow-multi-instance" aria-label={t('workflow.execution.title', '执行配置')}>
    <Typography.Title level={5}>{t('workflow.execution.title', '执行配置')}</Typography.Title>
    <Form.Item label={t('workflow.multi.mode', '执行方式')}>
      <Select aria-label={t('workflow.multi.mode', '执行方式')} value={mode} disabled={disabled} options={[
        {value: 'none', label: t('workflow.multi.once', '执行一次')},
        {value: 'parallel', label: t('workflow.multi.parallel', '并行多实例')},
        {value: 'sequential', label: t('workflow.multi.sequential', '串行多实例')},
        {value: 'standard', label: t('workflow.loop.title', '标准循环')},
        ...(mode === 'legacy' ? [{value: 'legacy', label: t('workflow.multi.existingLoop', '现有循环配置'), disabled: true}] : []),
      ]} onChange={value => {if (!disabled && value !== 'legacy') onModeChange(value as ExecutionMode);}}/>
    </Form.Item>
    {standard && <StandardLoopEditor loop={loop} variables={variables} disabled={disabled} onChange={onChange}
      onValidityChange={onValidityChange} conditionDraft={completionDraft} onConditionDraftChange={onCompletionDraftChange}
      onConditionValidityChange={onCompletionValidityChange}/>}
    {multi && <>
      {issues.map(issue => <Alert key={issue} type="warning" showIcon title={multiInstanceMessage(issue, t)}/>)}
      <Form.Item label={t('workflow.multi.source', '实例来源')}>
        <Select aria-label={t('workflow.multi.source', '实例来源')} disabled={disabled}
          value={loop.collection != null ? 'collection' : 'count'} options={[
            {value: 'count', label: t('workflow.multi.count', '执行次数')},
            {value: 'collection', label: t('workflow.multi.collection', '办理集合')},
          ]} onChange={value => update(value === 'collection'
            ? {collection: '', loopCardinality: undefined}
            : {collection: undefined, elementVariable: undefined, loopCardinality: {body: '1'}})}/>
      </Form.Item>
      {loop.collection != null ? <>
        <Form.Item label={t('workflow.multi.collection', '办理集合')}>
          <ValueExpression value={loop.collection} variables={collections} disabled={disabled}
            initialDraft={sourceDraft} onDraftChange={onSourceDraftChange} onValidityChange={onSourceValidityChange}
            onChange={value => update({collection: value ?? ''})}
            renderLiteral={(value, change) => <Select aria-label={t('workflow.multi.collection', '办理集合')}
              disabled={disabled} value={value} options={[
                ...collections.map(variable => ({value: variable.name, label: variable.label})),
                ...(value && !collections.some(variable => variable.name === value) ? [{value, label: value}] : []),
              ]}
              onChange={change}/>}/>
        </Form.Item>
        <Form.Item label={t('workflow.multi.item', '当前项变量')}>
          <Input aria-label={t('workflow.multi.item', '当前项变量')} disabled={disabled} value={loop.elementVariable ?? ''}
            maxLength={80} placeholder="reviewer" onChange={event => update({elementVariable: event.target.value || undefined})}/>
        </Form.Item>
        <Typography.Paragraph type="secondary">{t('workflow.multi.itemHint', '在办理人或候选角色中选择当前项变量，才能让每个实例使用集合中的对应人员或角色。')}</Typography.Paragraph>
      </> : <Form.Item label={t('workflow.multi.count', '执行次数')}>
        <ValueExpression value={loop.loopCardinality?.body} variables={numbers} disabled={disabled}
          initialDraft={sourceDraft} onDraftChange={onSourceDraftChange} onValidityChange={onSourceValidityChange}
          onChange={value => update({loopCardinality: {body: value ?? ''}})}
          renderLiteral={(value, change) => <InputNumber aria-label={t('workflow.multi.count', '执行次数')}
            disabled={disabled} min={1} max={50} precision={0} value={value ? Number(value) : null}
            onChange={next => change(next == null ? undefined : String(next))}/>}/>
      </Form.Item>}
      <Form.Item label={t('workflow.multi.counter', '自定义序号变量（可选）')}>
        <Input aria-label={t('workflow.multi.counter', '自定义序号变量（可选）')} disabled={disabled}
          value={loop.elementIndexVariable ?? ''} maxLength={80}
          onChange={event => update({elementIndexVariable: event.target.value || undefined})}/>
      </Form.Item>
      <Form.Item label={t('workflow.multi.completion', '提前结束条件（可选）')}>
        <ConditionExpression value={loop.completionCondition?.body} variables={completionVariables} disabled={disabled}
          initialDraft={completionDraft} onDraftChange={onCompletionDraftChange} onValidityChange={onCompletionValidityChange}
          onChange={value => update({completionCondition: value ? {body: value} : undefined})}/>
      </Form.Item>
      <Typography.Paragraph type="secondary">{t('workflow.multi.completionHint', '默认等待所有实例完成。提前结束条件成立时，剩余实例取消并保留历史；驳回仍终止整个流程。集合和执行次数均限制为 1 至 50。')}</Typography.Paragraph>
    </>}
  </section>;
}
