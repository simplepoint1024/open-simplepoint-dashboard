import {createContext, useContext, useMemo} from 'react';
import {Alert} from 'antd';
import {CalculationEditor, type CalculatorLabels} from '@simplepoint/calculation-editor';
import type {FormulaFunction} from '@simplepoint/calculation';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {DesignDocument, DesignField} from './contracts';
import {calculationFields} from './calculationModel';
import {calculationErrorText} from './calculationDiagnostics';
import type {FieldTarget} from './fieldAnalysis';
import CalculationTrial, {type DesignerCalculationTrial} from './CalculationTrial';

/** The host supplies versioned metadata; the editor and canvas perform no network requests. */
export type DesignerCalculations = {languageVersion: '1'; functions: readonly FormulaFunction[]} | undefined;
export const DesignerCalculationContext = createContext<{document: DesignDocument; catalog: DesignerCalculations;
  trial?: DesignerCalculationTrial; onLocate?: (target: FieldTarget) => void} | undefined>(undefined);

type Props = {field: DesignField; expression: string; scope?: string; trialDocument?: DesignDocument; disabled: boolean; onChange: (expression: string) => void};
export default function CalculationFieldEditor({field, expression, scope = 'parent', trialDocument, disabled, onChange}: Props) {
  const {t} = useI18n();
  const context = useContext(DesignerCalculationContext);
  const fields = useMemo(() => context ? calculationFields(context.document, scope, field.fieldId) : [], [context?.document, scope, field.fieldId]);
  const labels: CalculatorLabels = {
    visual: t('app.calculation.visual', '可视化'), expression: t('app.calculation.expression', '表达式'),
    fields: t('app.calculation.fields', '字段'), functions: t('app.calculation.functions', '函数'),
    operators: t('app.calculation.operators', '运算符'), literals: t('app.calculation.literals', '常量'),
    search: t('app.calculation.search', '搜索字段或函数'), empty: t('app.calculation.empty', '拖入或选择左侧内容'),
    insert: t('app.calculation.insert', '插入'), selected: t('app.calculation.selected', '当前节点'),
    remove: t('app.calculation.remove', '清空节点'), addArgument: t('app.calculation.addArgument', '添加参数'),
    text: t('app.calculation.text', '文本'), number: t('app.calculation.number', '数字'), boolean: t('app.calculation.boolean', '布尔值'),
    null: t('app.calculation.null', '空值'), value: t('app.calculation.value', '值'),
    undo: t('app.calculation.undo', '撤销'), redo: t('app.calculation.redo', '重做'),
    movePrevious: t('app.calculation.movePrevious', '参数左移'), moveNext: t('app.calculation.moveNext', '参数右移'),
    locateError: t('app.calculation.locateError', '定位错误'),
    error: (code, line, column) => `${calculationErrorText(code, t)} · ${code} (${line}:${column + 1})`,
  };
  if (!context) return <Alert type="warning" showIcon title={t('app.calculation.unavailable', '当前编辑环境未提供计算配置')}/>;
  if (!context.catalog) return <div><Alert type="info" showIcon title={t('app.calculation.catalogRequired', '加载计算函数后可编辑公式')}/>
    {expression && <pre className="sp-form-calculation-expression">{expression}</pre>}</div>;
  return <CalculationEditor key={JSON.stringify([scope, field.fieldId])} value={expression} onChange={onChange} fields={fields} functions={context.catalog.functions}
    labels={labels} readOnly={disabled} footer={<CalculationTrial document={trialDocument} loader={context.trial} target={{scope, fieldId: field.fieldId}}
      disabled={disabled} expression={expression}/>}/>;
}
