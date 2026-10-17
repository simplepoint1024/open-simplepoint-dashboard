import type {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {DesignError} from './model.ts';
type Translate = ReturnType<typeof useI18n>['t'];

export function componentLabels(t: Translate): Record<string, string> {
  return {
    'display.divider': t('app.designer.component.divider', '分割线'),
    'display.image': t('app.designer.component.image', '图片'),
    'display.alert': t('app.designer.component.alert', '警告提示'),
    'display.progress': t('app.designer.component.progress', '进度条'),
    'display.icon': t('app.designer.component.icon', '图标'),
    'display.markdown': t('app.designer.component.markdown', 'Markdown'),
    'field.rating': t('app.designer.component.rating', '评分'),
    'field.cascader': t('app.designer.component.cascader', '级联选择'),
    'field.transfer': t('app.designer.component.transfer', '穿梭框'),

    'data.detail': t('app.designer.detail', '明细行'),
    'layout.form': t('app.designer.component.form', '表单布局'),
    'layout.matrix': t('app.designer.matrix', '网格布局'),
    'layout.cell': t('app.designer.cell', '单元格'),
    'layout.grid': t('app.designer.component.grid', '栅格'),
    'layout.group': t('app.designer.component.group', '分组'),
    'layout.tabs': t('app.designer.component.tabs', '标签页'),
    'display.text': t('app.designer.component.display', '说明文本'),
    'field.text': t('app.designer.component.text', '单行文本'),
    'field.json': t('app.designer.component.json', 'JSON 数据'),
    'field.textarea': t('app.designer.component.textarea', '多行文本'),
    'field.number': t('app.designer.component.number', '数字'),
    'field.switch': t('app.designer.component.switch', '开关'),
    'field.select': t('app.designer.component.select', '选项'),
    'field.date': t('app.designer.component.date', '日期'),
    'field.time': t('app.designer.component.time', '时间'),
    'field.datetime': t('app.designer.component.datetime', '日期时间'),
    'field.user': t('app.designer.component.user', '用户'),
    'field.role': t('app.designer.component.role', '角色'),
    'field.organization': t('app.designer.component.organization', '组织'),
    'field.tenant': t('app.designer.component.tenant', '租户'),
    'field.attachment': t('app.designer.component.attachment', '附件'),
    'action.save': t('app.designer.component.save', '保存草稿'),
    'action.submit': t('app.designer.component.submit', '提交'),
    'action.reset': t('app.designer.component.reset', '重置'),
    'action.open-dialog': t('app.designer.component.dialog', '打开弹窗'),
    'action.open-drawer': t('app.designer.component.drawer', '打开抽屉'),
    'action.close': t('app.designer.component.close', '取消'),
    'action.apply': t('app.designer.component.apply', '确认'),
  };
}
export function designError(error: unknown, t: Translate) {
  if (!(error instanceof DesignError)) return t('app.designer.invalid', '无法应用此修改，请检查表单配置。');
  switch (error.code) {
    case 'parent': return t('app.designer.invalidParent', '请选择允许放置该组件的布局容器。');
    case 'field': return t('app.designer.invalidField', '字段类型不匹配，或该字段已在当前场景中使用。');
    case 'cycle': return t('app.designer.cycle', '不能形成循环嵌套或循环打开场景。');
    case 'limit': return t('app.designer.limit', '表单超出允许的字段、组件或嵌套层数。');
    case 'referenced': return t('app.designer.referenced', '请检查打开场景的动作；删除场景前需先移除引用它的动作。');
    default: return t('app.designer.unsupported', '当前设计器不支持此组件版本，请更新后再编辑。');
  }
}
