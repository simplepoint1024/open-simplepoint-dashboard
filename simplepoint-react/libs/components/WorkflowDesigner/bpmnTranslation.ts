import type {TranslateFn} from '@simplepoint/shared/i18n/translator';

/** Translate supported native editor actions through the same platform bundles as the property panel. */
const messages: Record<string, (t: TranslateFn, values: Record<string, unknown>) => string> = {
  "Activate hand tool": (t, values) => t('workflow.bpmn.hand', "拖动画布", values),
  "Activate lasso tool": (t, values) => t('workflow.bpmn.lasso', "框选元素", values),
  "Activate create/remove space tool": (t, values) => t('workflow.bpmn.space', "增加／移除画布间距", values),
  "Activate global connect tool": (t, values) => t('workflow.bpmn.connect', "连接元素", values),
  "Create start event": (t, values) => t('workflow.bpmn.createStart', "创建开始事件", values),
  "Create end event": (t, values) => t('workflow.bpmn.createEnd', "创建结束事件", values),
  "Create gateway": (t, values) => t('workflow.bpmn.createGateway', "创建网关", values),
  "Create data object reference": (t, values) => t('workflow.bpmn.createData', "创建数据对象（说明）", values),
  "Create data store reference": (t, values) => t('workflow.bpmn.createStore', "创建数据存储（说明）", values),
  "Create expanded sub-process": (t, values) => t('workflow.bpmn.createSubprocess', "创建展开子流程", values),
  "Create pool/participant": (t, values) => t('workflow.bpmn.createPool', "创建泳池（仅支持一个可执行流程）", values),
  "Create group": (t, values) => t('workflow.bpmn.createGroup', "创建分组", values),
  "Delete": (t, values) => t('workflow.bpmn.delete', "删除", values),
  "Add lane above": (t, values) => t('workflow.bpmn.laneAbove', "在上方添加泳道", values),
  "Add lane below": (t, values) => t('workflow.bpmn.laneBelow', "在下方添加泳道", values),
  "Divide into two lanes": (t, values) => t('workflow.bpmn.laneTwo', "划分为两个泳道", values),
  "Divide into three lanes": (t, values) => t('workflow.bpmn.laneThree', "划分为三个泳道", values),
  "Append end event": (t, values) => t('workflow.bpmn.appendEnd', "追加结束事件", values),
  "Append gateway": (t, values) => t('workflow.bpmn.appendGateway', "追加网关", values),
  "Append task": (t, values) => t('workflow.bpmn.appendTask', "追加任务", values),
  "Change element": (t, values) => t('workflow.bpmn.change', "更改元素", values),
  "Add text annotation": (t, values) => t('workflow.bpmn.annotation', "添加文字注释", values),
  "Connect to other element": (t, values) => t('workflow.bpmn.connectElement', "连接到其他元素", values),
  "Connect using association": (t, values) => t('workflow.bpmn.association', "使用关联线连接", values),
  "Connect using data input association": (t, values) => t('workflow.bpmn.dataAssociation', "使用数据输入关联连接（说明）", values),
  "Parallel multi-instance": (t, values) => t('workflow.bpmn.parallel', "并行多实例", values),
  "Sequential multi-instance": (t, values) => t('workflow.bpmn.sequential', "串行多实例", values),
  "Loop": (t, values) => t('workflow.bpmn.loop', "标准循环", values),
  "Collection": (t, values) => t('workflow.bpmn.collection', "集合", values),
  "Participant multiplicity": (t, values) => t('workflow.bpmn.multiplicity', "参与者多实例", values),
  "User task": (t, values) => t('workflow.bpmn.userTask', "用户任务", values),
  "Exclusive gateway": (t, values) => t('workflow.bpmn.exclusive', "排他网关", values),
  "Parallel gateway": (t, values) => t('workflow.bpmn.parallelGateway', "并行网关", values),
  "Inclusive gateway": (t, values) => t('workflow.bpmn.inclusive', "包容网关", values),
  "Sub-process": (t, values) => t('workflow.bpmn.subprocess', "子流程", values),
  "Sub-process (collapsed)": (t, values) => t('workflow.bpmn.collapsed', "折叠子流程", values),
  "Sub-process (expanded)": (t, values) => t('workflow.bpmn.expanded', "展开子流程", values),
  "Start event": (t, values) => t('workflow.bpmn.start', "开始事件", values),
  "End event": (t, values) => t('workflow.bpmn.end', "结束事件", values),
  "Data object reference": (t, values) => t('workflow.bpmn.dataObject', "数据对象引用", values),
  "Data store reference": (t, values) => t('workflow.bpmn.dataStore', "数据存储引用", values),
  "Sequence flow": (t, values) => t('workflow.bpmn.sequence', "顺序流", values),
  "Default flow": (t, values) => t('workflow.bpmn.default', "默认流", values),
  "Conditional flow": (t, values) => t('workflow.bpmn.conditional', "条件流", values),
  "Search": (t, values) => t('workflow.bpmn.search', "搜索", values),
  "No entries found": (t, values) => t('workflow.bpmn.noEntries', "没有匹配的元素", values),
};

export function translateBpmn(t: TranslateFn, template: string, replacements: Record<string, unknown> = {}): string {
  const message = messages[template];
  if (message) return message(t, replacements);
  // Unknown library diagnostics retain their original wording and standard placeholder substitution.
  return template.replace(/\{([^}]+)\}/g, (original, key: string) =>
    Object.prototype.hasOwnProperty.call(replacements, key) ? String(replacements[key]) : original);
}
