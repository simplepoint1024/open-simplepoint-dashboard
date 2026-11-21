import {FormulaError, parseFormula, validateFormula, type FormulaFunction} from '@simplepoint/calculation';
import type {TranslateFn} from '@simplepoint/shared/i18n/translator';
import type {DesignDocument} from './contracts.ts';
import {calculationFields} from './calculationModel.ts';
import {fieldDependencies, type AnalyzedField} from './fieldAnalysis.ts';
import {validateDesignField} from './dataModel.ts';
import {businessReferenceField} from './relations/model.ts';

export function calculationErrorText(code: string, t: TranslateFn): string {
  const messages: Record<string, string> = {
    UNKNOWN_FIELD: t('app.calculation.errorField', '字段不存在或不能在此处引用'),
    UNKNOWN_FUNCTION: t('app.calculation.errorFunction', '函数不存在'),
    ARGUMENT_COUNT: t('app.calculation.errorArguments', '函数参数数量不正确'),
    SYNTAX: t('app.calculation.errorSyntax', '表达式语法不正确'),
    DEPENDENCY_CYCLE: t('app.calculation.errorCycle', '字段之间存在循环依赖'),
    FIELD_VALIDATION: t('app.calculation.errorValidation', '字段校验规则不正确'),
    EXPRESSION_SIZE: t('app.calculation.errorSize', '表达式为空或超过长度限制'),
    EXPRESSION_DEPTH: t('app.calculation.errorDepth', '表达式嵌套过深或节点过多'),
    DIV_ZERO: t('app.calculation.errorDivision', '除数不能为零'),
    TYPE: t('app.calculation.errorType', '参数类型不正确'),
    RESULT_TYPE: t('app.calculation.errorResultType', '计算结果与字段类型不兼容'),
    RESULT_RANGE: t('app.calculation.errorRange', '数值超出字段范围或小数精度'),
    RESULT_REQUIRED: t('app.calculation.errorRequired', '必需的输入或计算结果为空'),
    NUMBER_RANGE: t('app.calculation.errorNumber', '数值超出计算范围'),
    DATE: t('app.calculation.errorDate', '日期或时间值不正确'),
  };
  return messages[code] ?? t('app.calculation.invalid', '表达式无效');
}
export type DesignIssue = AnalyzedField & {code: string; line?: number; column?: number};
export function designIssues(document: DesignDocument, functions?: readonly FormulaFunction[]): DesignIssue[] {
  const graph = fieldDependencies(document), issues: DesignIssue[] = [];
  for (const target of graph.fields) {
    const reference = target.field.storageMode === 'REFERENCE'
      && businessReferenceField(document, target.field, target.scope === 'parent' ? undefined : target.scope);
    try {validateDesignField(target.field, reference);}
    catch {issues.push({...target, code: 'FIELD_VALIDATION'});}
    if (!target.field.calculation) continue;
    if (graph.cyclic.has(target.key)) {issues.push({...target, code: 'DEPENDENCY_CYCLE'}); continue;}
    try {
      const tree = parseFormula(target.field.calculation.expression);
      if (functions) validateFormula(tree, calculationFields(document, target.scope, target.fieldId), functions);
    } catch (error) {
      const failure = error instanceof FormulaError ? error : new FormulaError('INVALID_AST');
      issues.push({...target, code: failure.code, line: failure.line, column: failure.column});
    }
  }
  return issues;
}
