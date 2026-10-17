import type {TranslateFn} from '@simplepoint/shared/i18n/translator';
import type {MultiInstanceIssue} from './multiInstance';

export function multiInstanceMessage(issue: MultiInstanceIssue, t: TranslateFn): string {
  switch (issue) {
    case 'multiOwner': return t('workflow.modeling.multiOwner', '多实例只能配置在人工任务或子流程上，且不能重复配置');
    case 'multiMode': return t('workflow.modeling.multiMode', '多实例执行方式必须为串行或并行');
    case 'multiSource': return t('workflow.modeling.multiSource', '多实例必须且只能选择办理集合或执行次数');
    case 'multiCount': return t('workflow.modeling.multiCount', '多实例执行次数必须为 1 至 50 的整数');
    case 'multiCollection': return t('workflow.modeling.multiCollection', '办理集合必须引用已声明的多用户或多角色变量');
    case 'multiItem': return t('workflow.modeling.multiItem', '当前项变量需要办理集合，且不能使用系统或重复变量名');
    case 'multiCompletion': return t('workflow.modeling.multiCompletion', '多实例结束条件必须为有效的布尔表达式');
    case 'multiExpression': return t('workflow.modeling.multiExpression', '多实例表达式格式无效或包含不允许的调用');
  }
}
