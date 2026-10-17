import HelpTip from '../HelpTip';
import {designerFormContext} from './presentation';
import {useMemo, useRef, useState} from 'react';
import {Alert, Modal, Typography} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import DataTable from '../DataTable';
import TableActions from '../Table/Actions';
import ConditionEditor from '../Table/ConditionEditor';
import QueryValue from '../Table/QueryValue';
import SForm, {type SFormHandle} from '../SForm';
import type {DesignDocument} from './contracts.ts';
import type {FormCondition, FormRuleDefinition} from '../FormRuntime/contracts.ts';
import {sameDesignValue} from './draftState.ts';
import {applyRuleEdit, editRule, moveRule, newRule, removeRule, ruleEffects, ruleQueryContract, ruleTargets,
  RuleEditError, type RuleEdit, type RuleEffect} from './ruleModel.ts';

type Session = {base?: FormRuleDefinition; value: RuleEdit; initial: RuleEdit; remove?: boolean};
type Props = {document: DesignDocument; disabled: boolean; onChange: (document: DesignDocument) => void};

/** Rule metadata uses SForm; condition construction reuses the platform condition-tree editor. */
export default function RuleEditor({document, disabled, onChange}: Props) {
  const {t} = useI18n(), selected = usePageSelected();
  const [session, setSession] = useState<Session>(), [error, setError] = useState(''), [discard, setDiscard] = useState(false);
  const form = useRef<SFormHandle>(null), locked = disabled || !selected;
  usePageRetention(!session, Boolean(session));
  const effects: Record<RuleEffect, string> = {
    SHOW: t('app.rules.show', '条件成立时显示，否则隐藏'), HIDE: t('app.rules.hide', '条件成立时隐藏，否则显示'),
    ENABLE: t('app.rules.enable', '条件成立时启用，否则禁用'), DISABLE: t('app.rules.disable', '条件成立时禁用，否则启用'),
    REQUIRE: t('app.rules.require', '条件成立时必填，否则可选'), OPTIONAL: t('app.rules.optional', '条件成立时可选，否则必填'),
  };
  const contract = useMemo(() => ruleQueryContract(document), [document.fields]);
  const properties = useMemo(() => Object.fromEntries(document.fields.map(field => [field.fieldId,
    {title: `${field.label} (${field.storageCode ?? field.fieldId})`, type: field.type === 'BOOLEAN' ? 'boolean' : 'string'}])), [document.fields]);
  const targetNames = new Map(ruleTargets(document, 'SHOW').map(target => [target.id, target.label]));
  const messages: Record<RuleEditError['code'], string> = {
    conflict: t('app.rules.conflict', '该规则已发生变化，请关闭后重新编辑。'),
    target: t('app.rules.invalidTarget', '请选择与效果匹配的目标控件；必填规则只适用于持久化字段。'),
    condition: t('app.rules.invalidCondition', '请选择有效字段和比较方式，条件组不能为空。'),
    literal: t('app.rules.invalidLiteral', '条件值须符合字段类型；数值保留精度，日期时间需包含时区。'),
    limit: t('app.rules.limit', '每条规则最多 32 个条件节点、4 层嵌套、100 个集合值；每个表单最多 500 条规则。'),
  };
  const fail = (reason: unknown) => setError(reason instanceof RuleEditError ? messages[reason.code] : t('app.designer.invalid', '无法应用此修改，请检查表单配置。'));
  const commit = (action: () => DesignDocument, close = false) => {
    if (locked) return;
    try {onChange(action()); setError(''); if (close) setSession(undefined);} catch (reason) {fail(reason);}
  };
  const open = (base?: FormRuleDefinition, remove = false) => {
    if (locked) return;
    const value = base ? editRule(base) : newRule(document);
    setSession({base: base && structuredClone(base), value, initial: structuredClone(value), remove}); setError('');
  };
  const close = () => {
    if (!selected) return;
    if (session && !session.remove && !sameDesignValue(session.value, session.initial)) setDiscard(true);
    else {setSession(undefined); setError('');}
  };
  const summary = (condition: FormCondition): string => {
    if (condition.children.length) {
      const label = condition.operator === 'AND' ? t('table.query.and', '满足全部')
        : condition.operator === 'OR' ? t('table.query.or', '满足任一') : t('table.query.not', '不满足');
      return `${label} (${condition.children.map(summary).join('；')})`;
    }
    const field = document.fields.find(field => field.fieldId === condition.fieldId);
    const names: Record<string, string> = {
      EQUALS: t('table.query.operator.equals', '等于'), NOT_EQUALS: t('table.query.operator.not:equals', '不等于'),
      IN: t('table.query.operator.in', '属于'), NOT_IN: t('table.query.operator.not:in', '不属于'),
      IS_EMPTY: t('table.query.operator.is:null', '为空'), IS_NOT_EMPTY: t('table.query.operator.is:not:null', '非空'),
      GREATER_THAN: t('table.query.operator.than:greater', '大于'), GREATER_THAN_OR_EQUAL: t('table.query.operator.than:equal:greater', '大于等于'),
      LESS_THAN: t('table.query.operator.than:less', '小于'), LESS_THAN_OR_EQUAL: t('table.query.operator.than:equal:less', '小于等于'),
    };
    return `${field?.label ?? condition.fieldId} ${names[condition.operator]} ${condition.values.map(value => value.value ?? '').join('、')}`;
  };
  const targets = session ? ruleTargets(document, session.value.effect) : [];
  return <div className="sp-form-rule-editor">
    <div className="sp-form-editor-help"><HelpTip>{t('app.rules.orderHint', '规则按列表顺序执行，同类效果以后面的规则为准。')}<br/>{t('app.rules.scopeHint', '显隐和启用控制界面交互；必填由服务端在提交时校验。启用不能解除权限或字段写入限制。JSON 按内容比较，空对象、空数组和空白字符串视为空。')}</HelpTip></div>
    {error && !session && <Alert type="error" showIcon title={error}/>}
    <DataTable<FormRuleDefinition> rowKey="ruleId" storageKey="app.form.designer.rules" dataSource={document.rules} pagination={{pageSize: 20}}
      toolbarExtra={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'add', icon: 'PlusOutlined', sort: 0, text: t('app.rules.add', '新增规则'), disabled: locked || !document.fields.length || document.rules.length >= 500},
      ]} onButtonEvents={{add: () => open()}}/>}
      columns={[
        {key: 'order', title: t('app.rules.order', '执行顺序'), width: 90, render: (_, rule) => document.rules.indexOf(rule) + 1},
        {key: 'target', title: t('app.rules.target', '目标控件'), render: (_, rule) => targetNames.get(rule.targetComponentId) ?? rule.targetComponentId},
        {key: 'effect', title: t('app.rules.effect', '效果'), render: (_, rule) => effects[rule.effect]},
        {key: 'condition', title: t('app.rules.condition', '条件'), render: (_, rule) => {
          const text = summary(rule.condition); return <span title={text}>{text.slice(0, 180)}</span>;
        }},
        {key: 'actions', title: t('app.designer.actions', '操作'), render: (_, rule) => <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
          {key: 'edit', icon: 'EditOutlined', sort: 0, text: t('app.rules.edit', '编辑规则'), disabled: locked},
          {key: 'up', icon: 'ArrowUpOutlined', sort: 1, text: t('app.designer.up', '上移'), disabled: locked || document.rules[0] === rule},
          {key: 'down', icon: 'ArrowDownOutlined', sort: 2, text: t('app.designer.down', '下移'), disabled: locked || document.rules[document.rules.length - 1] === rule},
          {key: 'remove', icon: 'DeleteOutlined', sort: 3, text: t('app.rules.remove', '移除规则'), danger: true, disabled: locked},
        ]} onButtonEvents={{edit: () => open(rule), remove: () => open(rule, true),
          up: () => commit(() => moveRule(document, rule, -1)), down: () => commit(() => moveRule(document, rule, 1))}}/>},
      ]}/>
    <Modal open={Boolean(session) && selected} title={t('app.rules.edit', '编辑规则')} width={880}
      wrapClassName="sp-form-rule-modal" destroyOnHidden maskClosable={false} onCancel={close} footer={
        <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
          {key: 'cancel', icon: 'CloseOutlined', sort: 0, text: t('app.designer.cancelData', '取消')},
          {key: 'apply', icon: 'CheckOutlined', sort: 1, type: 'primary', danger: session?.remove, disabled: locked,
            text: session?.remove ? t('app.rules.remove', '移除规则') : t('app.designer.applyData', '应用修改')},
        ]} onButtonEvents={{cancel: close, apply: () => {
          if (locked || !session) return;
          if (session.remove && session.base) commit(() => removeRule(document, session.base!), true);
          else form.current?.submit();
        }}}/>
      }>
      {error && <Alert type="error" showIcon title={error}/>}
      {session?.remove ? <Alert type="warning" showIcon title={t('app.rules.removeHint', '从草稿中移除此规则？发布后生效。')}/>
        : session && <>
          <SForm formContext={designerFormContext} ref={form} idPrefix="form_rule" hideSubmit disabled={locked} schema={{type: 'object', required: ['targetComponentId', 'effect'], properties: {
            effect: {type: 'string', title: t('app.rules.effect', '效果'), oneOf: ruleEffects.map(effect => ({const: effect, title: effects[effect]}))},
            targetComponentId: {type: 'string', minLength: 1, title: t('app.rules.target', '目标控件'), oneOf: [
              {const: '', title: t('app.rules.chooseTarget', '请选择目标控件')}, ...targets.map(target => ({const: target.id, title: target.label})),
            ]},
          }}} formData={{effect: session.value.effect, targetComponentId: session.value.targetComponentId}}
          onChange={({formData}, changedField) => {
            if (!locked && formData && changedField) setSession({...session, value: {...session.value, ...formData}});
          }} onSubmit={({formData}) => {
            if (formData) commit(() => applyRuleEdit(document, session.base, {...session.value, ...formData}), true);
          }}/>
          <Typography.Title level={5}>{t('app.rules.condition', '条件')}</Typography.Title>
          <fieldset className="sp-form-rule-condition" disabled={locked} style={{border: 0, padding: 0, margin: 0, pointerEvents: locked ? 'none' : undefined}}>
            <ConditionEditor contract={contract} properties={properties} preserveGroups multiline
              node={session.value.condition.children ? session.value.condition : {op: 'and', children: [session.value.condition]}}
              // ISO temporal text retains offsets and nanoseconds that date pickers would normalize.
              renderValue={(node, change) => <QueryValue op={node.op} value={node.value} multiline maxLength={16384}
                type={properties[node.field ?? '']?.type} javaType={contract.fields[node.field ?? '']?.javaType}
                onChange={value => {if (!locked) change({...node, value});}}/>}
              onChange={condition => {if (!locked) setSession({...session, value: {...session.value, condition}});}}/>
          </fieldset>
        </>}
    </Modal>
    <Modal open={discard && selected} wrapClassName="sp-form-rule-discard" title={t('app.designer.discardDataEdit', '放弃尚未应用的修改？')}
      onCancel={() => setDiscard(false)} footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'continue', icon: 'EditOutlined', sort: 0, text: t('app.designer.continueDataEdit', '继续编辑')},
        {key: 'discard', icon: 'DeleteOutlined', sort: 1, text: t('app.designer.discardData', '放弃修改'), danger: true},
      ]} onButtonEvents={{continue: () => setDiscard(false), discard: () => {
        if (selected) {setDiscard(false); setSession(undefined); setError('');}
      }}}/>}/>
  </div>;
}
