import {useEffect, useMemo, useRef, useState} from 'react';
import {Alert, Collapse, Tag} from 'antd';
import {createIcon} from '@simplepoint/shared/types/icon';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {getQueryScopeSnapshot} from '@simplepoint/shared/api/queryScope';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import SForm, {type SFormHandle} from '../SForm';
import TableActions from '../Table/Actions';
import DataTable from '../DataTable';
import HelpTip from '../HelpTip';
import type {CalculationPreviewResult} from '../FormRuntime/calculationPreview';
import type {DesignDocument} from './contracts';
import {designFields, fieldKey, type FieldTarget} from './fieldAnalysis';
import {calculationSampleFields, calculationTrialInputs, sampleObjectSchema, type CalculationSamples} from './calculationTrialModel';
import {designerFormContext} from './presentation';
import {calculationErrorText} from './calculationDiagnostics';

export type DesignerCalculationTrial = (document: DesignDocument, samples: CalculationSamples, signal: AbortSignal) => Promise<CalculationPreviewResult>;
type Props = {document?: DesignDocument; target: FieldTarget; expression: string; loader?: DesignerCalculationTrial; disabled: boolean};

/** Explicit read-only trials. Results belong to the exact document, input and authorization scope. */
export default function CalculationTrial({document, target, expression, loader, disabled}: Props) {
  const {t} = useI18n(), selected = usePageSelected(), scope = useQueryScope();
  const [samples, setSamples] = useState<CalculationSamples>({}), [open, setOpen] = useState(false);
  const [outcome, setOutcome] = useState<{key: string; result?: CalculationPreviewResult; error?: string; busy?: boolean}>();
  const request = useRef<AbortController | undefined>(undefined), form = useRef<SFormHandle>(null);
  const signature = JSON.stringify([document, target, expression, samples, scope, selected, disabled, open]);
  const token = useMemo(() => ({}), [signature, loader]);
  const active = useRef(token); active.current = token;
  useEffect(() => () => {request.current?.abort();}, [token]);
  const state = outcome?.key === signature ? outcome : undefined;
  const field = document && designFields(document).find(field => field.key === fieldKey(target));
  const inputs = document && calculationSampleFields(document);
  const inputDocument = document && inputs ? {...document, ...inputs} : undefined;
  const run = async (input: CalculationSamples) => {
    if (!document || !loader || disabled || !selected || !open || request.current && !request.current.signal.aborted) return;
    const controller = new AbortController(); request.current = controller;
    const current = () => active.current === token && !controller.signal.aborted && JSON.stringify(scope) === getQueryScopeSnapshot();
    setOutcome({key: signature, busy: true});
    try {
      const result = await loader(document, calculationTrialInputs(inputDocument!, input), controller.signal);
      if (current()) setOutcome({key: signature, result});
    } catch (error) {
      if (current()) {
        const diagnostic = (error as {data?: {calculation?: {code?: string; scope?: string; fieldId?: string; row?: number}}})?.data?.calculation;
        const owner = diagnostic && designFields(document).find(field => field.scope === diagnostic.scope && field.fieldId === diagnostic.fieldId);
        const row = Number.isInteger(diagnostic?.row) && diagnostic!.row! >= 0 && diagnostic!.row! < 40
          ? t('app.calculation.errorRow', '第 {row} 行', {row: diagnostic!.row! + 1}) : '';
        setOutcome({key: signature, error: diagnostic?.code && /^[A-Z_]{1,80}$/.test(diagnostic.code)
          ? [owner?.label, row, calculationErrorText(diagnostic.code, t), diagnostic.code].filter(Boolean).join(' · ')
          : resolveApiErrorMessage(error, t('app.calculation.trialFailed', '试算失败，请检查输入值、公式和结果类型。'))});
      }
    } finally {if (request.current === controller) request.current = undefined;}
  };
  const rows = state?.result && field ? (target.scope === 'parent' ? [state.result.values] : state.result.details[target.scope] ?? [])
    .map((values, index) => ({key: String(index), row: index + 1, value: values[target.fieldId]})) : [];
  return <Collapse className="sp-form-calculation-trial" activeKey={open ? ['trial'] : []} onChange={keys => setOpen(keys.includes('trial'))} items={[
    {key: 'trial', label: <span>{createIcon('ExperimentOutlined')} {t('app.calculation.trial', '公式试算')}</span>, children: <>
      <HelpTip>{t('app.calculation.trialHint', '使用临时样例对整张表单及明细进行试算，不保存设计或业务数据。其他计算字段的必需输入也应填写。')}</HelpTip>
      {!loader || !document ? <Alert type="info" showIcon title={!document ? t('app.calculation.trialInvalid', '请先修正字段属性和公式，再进行试算。')
        : t('app.calculation.trialUnavailable', '当前环境未提供试算能力')}/> : <>
        <SForm ref={form} idPrefix="calculation_trial" formContext={designerFormContext} hideSubmit disabled={disabled || !selected}
          schema={{type: 'object', properties: {
            values: {...sampleObjectSchema(inputs!.fields), title: t('app.calculation.parentInputs', '主表样例')},
            details: {type: 'object', title: t('app.calculation.detailInputs', '明细样例'), properties: Object.fromEntries(inputs!.details.map(detail => [detail.detailId,
              {type: 'array', title: detail.label, maxItems: 40, items: sampleObjectSchema(detail.fields)}]))},
          }}} formData={calculationTrialInputs(inputDocument!, samples)} onChange={({formData}, changed) => {
            if (changed) {request.current?.abort(); setSamples(formData ?? {}); setOutcome(undefined);}
          }} onSubmit={({formData}) => void run(formData ?? {})}/>
        <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
          {key: 'trial', sort: 0, icon: 'PlayCircleOutlined', text: t('app.calculation.runTrial', '开始试算'), loading: state?.busy,
            disabled: disabled || !selected || state?.busy},
        ]} onButtonEvents={{trial: () => form.current?.submit()}}/>
      </>}
      {state?.error && <Alert type="error" showIcon title={state.error}/>}
      {state?.result && field && <>
        <Tag>{field.field.type}{field.field.type === 'DECIMAL' ? ` (${field.field.precision}, ${field.field.scale})` : ''}</Tag>
        <DataTable rowKey="key" dataSource={rows} cards={false} settings={false} selectable={false} pagination={rows.length > 10 ? {defaultPageSize: 50} : false}
          scroll={{x: 350, y: 250}} columns={[
            {key: 'row', dataIndex: 'row', title: t('app.calculation.sampleRow', '样例行'), width: 90},
            {key: 'value', title: field.label, render: (_, row) => row.value == null ? 'NULL' : typeof row.value === 'object' ? JSON.stringify(row.value) : String(row.value)},
          ]}/>
      </>}
    </>},
  ]}/>;
}
