import {Alert, Form, InputNumber, Select, Typography} from 'antd';
import {useEffect, useMemo, useRef} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import ConditionExpression, {type ConditionExpressionDraft} from '../ExpressionEditor/ConditionExpression';
import type {ExpressionVariable} from '../ExpressionEditor/model';
import type {ActivityLoop} from './loopCharacteristics';
import {standardLoopIssues, standardLoopMessage} from './standardLoop';

/** Specialized BPMN properties use modeling commands, not a separate business form submission. */
export default function StandardLoopEditor({loop, variables, disabled, onChange, onValidityChange,
  conditionDraft, onConditionDraftChange, onConditionValidityChange}: {
  loop: ActivityLoop;
  variables: readonly ExpressionVariable[];
  disabled?: boolean;
  onChange: (patch: Partial<ActivityLoop>) => void;
  onValidityChange: (valid: boolean) => void;
  conditionDraft?: ConditionExpressionDraft;
  onConditionDraftChange: (draft: ConditionExpressionDraft) => void;
  onConditionValidityChange: (valid: boolean) => void;
}) {
  const {t} = useI18n();
  const conditions = useMemo<ExpressionVariable[]>(() => [...variables.filter(variable => variable.name !== 'loopCounter'),
    {name: 'loopCounter', label: t('workflow.loop.counter', '本节点已完成次数'), type: 'number', readOnly: true}], [variables, t]);
  const issues = standardLoopIssues(loop);
  const report = useRef(onValidityChange); report.current = onValidityChange;
  const issueKey = issues.join(',');
  useEffect(() => {report.current(!issues.length);}, [issueKey]);
  const update = (patch: Partial<ActivityLoop>) => {
    if (disabled) return;
    report.current(!standardLoopIssues({...loop, ...patch}).length);
    onChange(patch);
  };
  return <section className="workflow-standard-loop" aria-label={t('workflow.loop.title', '标准循环')}>
    {issues.map(issue => <Alert key={issue} showIcon type="warning" title={standardLoopMessage(issue, t)}/>)}
    <Form.Item label={t('workflow.loop.test', '判断时机')}>
      <Select aria-label={t('workflow.loop.test', '判断时机')} disabled={disabled} value={loop.testBefore ? 'before' : 'after'}
        options={[{value: 'after', label: t('workflow.loop.after', '执行后判断（至少执行一次）')},
          {value: 'before', label: t('workflow.loop.before', '执行前判断（可不执行）')}]}
        onChange={value => update({testBefore: value === 'before'})}/>
    </Form.Item>
    <Form.Item label={t('workflow.loop.maximum', '最大执行次数')}>
      <InputNumber aria-label={t('workflow.loop.maximum', '最大执行次数')} value={loop.loopMaximum}
        disabled={disabled} min={1} max={50} precision={0} style={{width: '100%'}}
        onChange={value => update({loopMaximum: value ?? undefined})}/>
    </Form.Item>
    <Form.Item label={t('workflow.loop.condition', '继续循环条件（可选）')}>
      <ConditionExpression value={loop.loopCondition?.body} variables={conditions} disabled={disabled}
        initialDraft={conditionDraft} onDraftChange={onConditionDraftChange} onValidityChange={onConditionValidityChange}
        onChange={value => update({loopCondition: value ? {body: value} : undefined})}/>
    </Form.Item>
    <Typography.Paragraph type="secondary">{t('workflow.loop.hint', '条件为真时继续循环；达到最大次数后结束。留空条件表示按最大次数执行。执行前判断可能不产生待办；每次实际办理都会保留独立记录。')}</Typography.Paragraph>
  </section>;
}
