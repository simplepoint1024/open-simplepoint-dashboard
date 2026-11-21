import {localizedTextSchema, localizedDraft, changedLocalizedDraft} from '@simplepoint/shared/i18n/localizedSchema';
import {normalizedValidation, textField, validationProperties} from './fieldValidation';
import {Tabs} from 'antd';
import {calculationPropertyTab, dependencyPropertyTab, designerPropertyEditors, type DesignerPropertyRegistry} from './extensions';
import TableActions from '../Table/Actions';
import {useState} from 'react';
import {calculableField} from './fieldCapabilities';
import {FieldCalculationDialog} from './FieldCalculationProperties';
import SForm from '../SForm/Lazy';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {createIcon} from '@simplepoint/shared/types/icon';
import {componentIcon, designerFormContext} from './presentation';
import type {ComponentDescriptor, DesignDocument} from './contracts.ts';
import type {FormFieldWritePolicy} from '../FormRuntime/contracts.ts';
import {changeNode, locate} from './model.ts';
import {businessReferenceField} from './relations/model';

export default function Properties({document, componentId, components, disabled, onChange, writePolicies, propertyRegistry = designerPropertyEditors}: {
  document: DesignDocument; componentId: string; components: readonly ComponentDescriptor[];
  disabled: boolean; onChange: (document: DesignDocument) => void;
  writePolicies: readonly FormFieldWritePolicy[]; propertyRegistry?: DesignerPropertyRegistry;
}) {
  const {t, locale} = useI18n();
  const [editing, setEditing] = useState<{componentId: string; mode: 'stored' | 'calculated'}>();
  const selected = locate(document, componentId);
  if (!selected) return null;
  const {node, scene, parent} = selected;
  const field = document.fields.find(field => field.fieldId === node.fieldId);
  const descriptor = components.find(item => item.type === node.componentType && item.version === node.componentVersion);
  const extensionTabs = propertyRegistry.get(`${node.componentType}@${node.componentVersion}`);
  const tabs = [...(field?.calculation ? [calculationPropertyTab] : []), ...(field ? [dependencyPropertyTab] : []), ...extensionTabs];
  const editField = (mode: 'stored' | 'calculated') => {if (!disabled) setEditing({componentId, mode});};
  const properties: Record<string, any> = {
    labelLocalization: {...localizedTextSchema(t('app.designer.label', '标题')), type: ['object', 'null'], default: null},
    columnSpan: {type: 'integer', title: t('app.designer.columnSpan', '宽度（共 24 格）'), minimum: 1, maximum: 24},
    description: {type: 'string', title: t('app.designer.description', '说明'), maxLength: 500},
    readOnly: {type: 'boolean', title: t('app.designer.readOnly', '界面只读')},
  };
  const data: Record<string, unknown> = {labelLocalization: node.label || node.labelLocalization ? localizedDraft(node.label ?? '', node.labelLocalization, locale) : undefined, columnSpan: node.columnSpan,
    description: node.description ?? undefined, readOnly: node.readOnly};
  const required: string[] = field ? ['fieldLabelLocalization'] : [];
  if (!parent) {
    properties.sceneName = {type: 'string', title: t('app.designer.sceneName', '场景名称'), minLength: 1, maxLength: 120};
    data.sceneName = scene.name; required.push('sceneName');
    if (scene.type !== 'MAIN') {
      properties.width = {type: 'integer', title: t('app.designer.overlayWidth', '容器宽度'), minimum: 320, maximum: 1600};
      data.width = scene.width; required.push('width');
    }
  }
  if (field) {
    if (calculableField(field)) {
      properties.calculationMode = {type: 'string', title: t('app.calculation.mode', '字段来源'), oneOf: [
        {const: 'stored', title: t('app.calculation.stored', '用户填写')},
        {const: 'calculated', title: t('app.calculation.calculated', '公式计算')},
      ]};
      data.calculationMode = field.calculation ? 'calculated' : 'stored';
    }
    properties.fieldLabelLocalization = localizedTextSchema(t('app.designer.fieldLabel', '字段名称'));
    data.fieldLabelLocalization = localizedDraft(field.label, field.labelLocalization, locale);
    properties.placeholder = {type: 'string', title: t('app.designer.placeholder', '输入提示'), maxLength: 240};
    properties.submitRequired = {type: 'boolean', title: t('app.designer.required', '提交时必填')};
    data.placeholder = node.placeholder ?? undefined; data.submitRequired = field.submitRequired;
    if (!field.calculation && writePolicies.length && ['STORED', 'REFERENCE', 'ATTACHMENT'].includes(field.storageMode)) {
      properties.writePolicy = {type: 'string', title: t('app.designer.writePolicy', '字段写入规则'),
        description: t('app.designer.writePolicyHint', '发布后，写入限制同时约束使用旧版本的页面；旧页面原有的更严格限制仍保留。仅新增时填写的必填字段需在首次保存前填写。'),
        oneOf: [
          {const: 'EDITABLE', title: t('app.designer.writeEditable', '始终可编辑')},
          {const: 'CREATE_ONLY', title: t('app.designer.writeCreateOnly', '仅新增时填写')},
          {const: 'UNTIL_SUBMITTED', title: t('app.designer.writeUntilSubmitted', '提交后不可修改')},
        ].filter(option => writePolicies.includes(option.const as FormFieldWritePolicy))};
      data.writePolicy = field.writePolicy ?? 'EDITABLE';
    }
    if (textField(field.type)) {
      properties.validation = validationProperties(t, field.length ?? undefined);
      data.validation = field.validation ?? {};
    }
    if (field.type === 'STRING') {
      properties.length = {type: 'integer', title: t('app.designer.storageLength', '存储长度'), minimum: 1, maximum: 65535};
      data.length = field.length; required.push('length');
    }
    if (field.type === 'DECIMAL') {
      properties.precision = {type: 'integer', title: t('app.designer.precision', '总位数'), minimum: 1, maximum: 38};
      properties.scale = {type: 'integer', title: t('app.designer.scale', '小数位数'), minimum: 0, maximum: field.precision ?? 38};
      data.precision = field.precision; data.scale = field.scale; required.push('precision', 'scale');
    }
    if (!businessReferenceField(document, field) && (node.componentType === 'field.select' || field.choices.length)) {
      properties.choices = {type: 'array', title: t('app.designer.choices', '选项'), maxItems: 200,
        items: {type: 'object', required: ['value', 'labelLocalization'], properties: {
          value: {type: 'string', title: t('app.designer.choiceValue', '选项值'), minLength: 1, maxLength: 256},
          labelLocalization: localizedTextSchema(t('app.designer.choiceLabel', '显示名称')),
        }}};
      data.choices = field.choices.map(choice => ({...choice, labelLocalization: localizedDraft(choice.label, choice.labelLocalization, locale)}));
    }
  }
  if (node.componentType.startsWith('action.open-')) {
    const type = node.componentType === 'action.open-dialog' ? 'DIALOG' : 'DRAWER';
    properties.targetSceneId = {type: 'string', title: t('app.designer.targetScene', '打开场景'),
      oneOf: document.scenes.filter(target => target.type === type && target.sceneId !== scene.sceneId)
        .map(target => ({const: target.sceneId, title: target.name}))};
    data.targetSceneId = node.targetSceneId; required.push('targetSceneId');
  }
  if (!extensionTabs.length && descriptor && Object.keys(descriptor.propertySchema).length) {
    properties.properties = {type: 'object', title: t('app.designer.extensionProperties', '组件属性'),
      required: Object.values(descriptor.propertySchema).filter(property => property.required).map(property => property.name),
      properties: Object.fromEntries(Object.entries(descriptor.propertySchema).map(([key, property]) => [key, {
        type: 'object', title: key, required: ['kind'], properties: {
          kind: {type: 'string', enum: property.acceptedKinds}, value: {type: ['string', 'null']},
        },
      }]))};
    data.properties = node.properties;
  }
  return <div className="sp-form-designer-properties" data-component-id={componentId}>
    <Tabs key={componentId} className="sp-form-property-tabs" defaultActiveKey="general" items={[
      {key: 'general', icon: createIcon(componentIcon(node.componentType)), label: t('app.designer.properties', '属性'), children: <>
    <SForm formContext={designerFormContext} key={`${componentId}:${Boolean(editing)}`} idPrefix="design_property" hideSubmit disabled={disabled} liveValidate="onChange"
      schema={{type: 'object', properties, required}} formData={data}
      onChange={({formData}, changedField) => {
        // RJSF also reports schema/default normalization on mount; only user edits are design commands.
        if (disabled || !formData || !changedField) return;
        if (properties.calculationMode && formData.calculationMode !== data.calculationMode) {
          editField(formData.calculationMode === 'calculated' ? 'calculated' : 'stored');
          return;
        }
        onChange(changeNode(document, componentId, (next, nextField, nextScene) => {
          const values = formData as typeof data;
          const localization = changedLocalizedDraft(node.label ?? '', node.labelLocalization, values.labelLocalization as any, locale);
          if (localization && !localization.defaultValue.trim() && Object.values(localization.translations).every(value => !value.trim())) {
            delete next.labelLocalization; next.label = null;
          } else if (localization) {next.labelLocalization = localization; next.label = localization.defaultValue;}
          if (nextField) {
            const fieldLocalization = changedLocalizedDraft(nextField.label, nextField.labelLocalization, values.fieldLabelLocalization as any, locale);
            if (fieldLocalization) {nextField.labelLocalization = fieldLocalization; nextField.label = fieldLocalization.defaultValue;}
          }
          next.columnSpan = Number(values.columnSpan ?? 24);
          next.description = values.description ? String(values.description) : null;
          next.readOnly = Boolean(values.readOnly);
          if (nextField) {
            next.placeholder = values.placeholder ? String(values.placeholder) : null;
            nextField.submitRequired = Boolean(values.submitRequired);
            if (properties.writePolicy) {
              if (values.writePolicy === 'EDITABLE') delete nextField.writePolicy;
              else nextField.writePolicy = values.writePolicy as FormFieldWritePolicy;
            }
            if (textField(nextField.type)) {
              const validation = normalizedValidation(values.validation as typeof nextField.validation);
              if (validation) nextField.validation = validation; else delete nextField.validation;
            }
            if (nextField.type === 'STRING') nextField.length = Number(values.length ?? 255);
            if (nextField.type === 'DECIMAL') {nextField.precision = Number(values.precision ?? 18); nextField.scale = Number(values.scale ?? 0);}
            if (properties.choices) nextField.choices = Array.isArray(values.choices) ? values.choices.map((choice, index) => {
              const previous = nextField.choices[index];
              const localization = changedLocalizedDraft(previous?.label ?? '', previous?.labelLocalization, choice.labelLocalization, locale);
              return {value: choice.value, label: localization?.defaultValue ?? previous?.label ?? '', ...(localization ? {labelLocalization: localization} : {})};
            }) : [];
          }
          if (nextScene) {
            nextScene.name = String(values.sceneName ?? '');
            if (nextScene.type !== 'MAIN') nextScene.width = Number(values.width ?? 640);
          }
          if (properties.targetSceneId) next.targetSceneId = typeof values.targetSceneId === 'string' ? values.targetSceneId : null;
          if (properties.properties) next.properties = (values.properties ?? {}) as typeof next.properties;
        }));
      }}/>
      {field && <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'fieldSettings', sort: 0, icon: 'SettingOutlined', text: t('app.designer.fieldSettings', '字段设置'), disabled},
      ]} onButtonEvents={{fieldSettings: () => editField(field.calculation ? 'calculated' : 'stored')}}/>}
      </>},
      ...tabs.map(tab => {const Content = tab.component; return {
        key: tab.key, icon: createIcon(tab.icon), label: t(tab.labelKey, tab.label), children:
          <Content node={node} disabled={disabled} document={document} components={components} writePolicies={writePolicies}
            onEditField={editField}
            onDocumentChange={onChange} onChange={updated => onChange(changeNode(document, componentId, target => Object.assign(target, updated)))}/>,
      };}),
    ]}/>
    {field && editing?.componentId === componentId && <FieldCalculationDialog key={componentId} node={node} field={field}
      mode={editing.mode} document={document} components={components} writePolicies={writePolicies} disabled={disabled}
      onDocumentChange={onChange} onClose={() => setEditing(undefined)}/>}
  </div>;
}
