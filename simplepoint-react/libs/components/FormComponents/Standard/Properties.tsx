import type {RJSFSchema} from '@rjsf/utils';
import SForm from '../../SForm/Lazy';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {DesignerPropertyEditorProps} from '../../FormDesigner/extensions';
import {designerFormContext} from '../../FormDesigner/presentation';
import {optionDraft} from './model';
import type {FormValue} from '../../FormRuntime/contracts';

/** Declarative property editors stay with their component family, outside the canvas. */
export default function StandardProperties({node, document, disabled, onChange, onDocumentChange}: DesignerPropertyEditorProps) {
  const {t} = useI18n();
  const text = (title: string, maxLength = 4000): RJSFSchema => ({type: 'string', title, maxLength});
  const number = (title: string, minimum: number, maximum: number): RJSFSchema => ({type: 'integer', title, minimum, maximum});
  const option = (depth: number): RJSFSchema => ({type: 'array', title: t('app.designer.choices', '选项'), maxItems: 200,
    items: {type: 'object', required: ['value', 'label'], properties: {
      value: {...text(t('app.designer.choiceValue', '选项值'), 256), minLength: 1},
      label: {...text(t('app.designer.choiceLabel', '显示名称'), 120), minLength: 1},
      ...(depth > 1 ? {children: option(depth - 1)} : {}),
    }}});
  const schemas: Record<string, Record<string, RJSFSchema>> = {
    'display.divider': {},
    'display.image': {src: text(t('app.designer.imageSource', '图片地址'), 2048), height: number(t('app.designer.imageHeight', '图片高度'), 40, 800)},
    'display.alert': {text: {...text(t('app.designer.content', '内容')), 'x-ui': {widget: 'textarea'}}, severity: {type: 'string', title: t('app.designer.alertSeverity', '提示类型'),
      oneOf: [{const: 'info', title: t('app.designer.alertInfo', '信息')}, {const: 'success', title: t('app.designer.alertSuccess', '成功')},
        {const: 'warning', title: t('app.designer.alertWarning', '警告')}, {const: 'error', title: t('app.designer.alertError', '错误')}]}},
    'display.progress': {percent: number(t('app.designer.progressPercent', '进度百分比'), 0, 100)},
    'display.icon': {icon: {...text(t('app.designer.component.icon', '图标'), 100), 'x-ui': {widget: 'IconPicker'}}, size: number(t('app.designer.iconSize', '图标大小'), 12, 96)},
    'display.markdown': {text: {...text(t('app.designer.content', '内容'), 16000), 'x-ui': {widget: 'textarea'}}},
    'field.rating': {count: number(t('app.designer.ratingCount', '评分上限'), 1, 10)},
    'field.cascader': {options: option(6)},
    'field.transfer': {options: option(1)},
  };
  const properties = schemas[node.componentType] ?? {};
  const data = Object.fromEntries(Object.keys(properties).map(key => {const value = node.properties[key]; return [key,
    key === 'options' ? optionDraft(value?.value ?? undefined) : value?.kind === 'INTEGER' ? Number(value.value) : value?.value ?? ''];}));
  return <SForm idPrefix="standard_property" hideSubmit formContext={designerFormContext} disabled={disabled} formData={data}
    schema={{type: 'object', properties, required: Object.keys(properties).filter(key => key !== 'src')}}
    onChange={({formData}, changed) => {
      if (disabled || !changed || !formData) return;
      const next: Record<string, FormValue> = {...node.properties};
      Object.keys(properties).forEach(key => {const kind = node.properties[key]?.kind ?? 'STRING';
        next[key] = {kind, value: kind === 'JSON' ? JSON.stringify(formData[key] ?? []) : String(formData[key] ?? '')};});
      if (node.fieldId) {
        const documentCopy = structuredClone(document);
        const visit = (item: typeof node) => {
          if (item.fieldId === node.fieldId && item.componentType === node.componentType) item.properties = structuredClone(next);
          item.children.forEach(visit);
        };
        documentCopy.scenes.forEach(scene => visit(scene.root));
        onDocumentChange(documentCopy);
      } else onChange({...node, properties: next});
    }}/>;
}
