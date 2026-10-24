import {useContext} from 'react';
import {Tag} from 'antd';
import {displayFormula, parseFormula, validateFormula} from '@simplepoint/calculation';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import TableActions from '../Table/Actions';
import type {DesignerPropertyEditorProps} from './extensions';
import {DesignerCalculationContext} from './CalculationFieldEditor';
import {calculationFields} from './calculationModel';

/** Field-owned metadata: every scene and the data designer edit the same definition. */
export default function FieldCalculationProperties({node, document, disabled, onEditField}: DesignerPropertyEditorProps) {
  const {t} = useI18n();
  const context = useContext(DesignerCalculationContext);
  const field = document.fields.find(item => item.fieldId === node.fieldId);
  if (!field?.calculation) return null;
  let valid = false;
  let summary = field.calculation.expression;
  try {summary = displayFormula(parseFormula(summary), calculationFields(document, 'parent', field.fieldId));} catch { /* Keep invalid drafts readable. */ }
  if (context?.catalog) {
    try {
      validateFormula(parseFormula(field.calculation.expression), calculationFields(document, 'parent', field.fieldId), context.catalog.functions);
      valid = true;
    } catch { /* The editor exposes the diagnostic and lets the user repair the draft. */ }
  }
  return <div className="sp-form-calculation-properties">
    <Tag color={!context?.catalog ? 'default' : valid ? 'success' : 'error'}>{!context?.catalog
      ? t('app.calculation.catalogRequired', '加载计算函数后可编辑公式')
      : valid ? t('app.calculation.valid', '公式校验通过') : t('app.calculation.invalid', '表达式无效')}</Tag>
    <pre className="sp-form-calculation-expression">{summary}</pre>
    <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
      {key: 'editFormula', icon: 'CalculatorOutlined', sort: 0, text: t('app.calculation.edit', '编辑公式'), disabled},
    ]} onButtonEvents={{editFormula: () => onEditField?.('calculated')}}/>
  </div>;
}

export {default as FieldCalculationDialog} from './FieldDefinitionDialog';
