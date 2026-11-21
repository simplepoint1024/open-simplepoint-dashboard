import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {resolveLocalizedText} from '@simplepoint/shared/i18n/localizedText';
import DataTable from '../../DataTable';
import TableActions from '../../Table/Actions';
import SForm from '../../SForm';
import HelpTip from '../../HelpTip';
import type {DesignDocument} from '../contracts';
import type {FormReferenceMapping, FormRelationField} from '../relationContracts';
import {designerFormContext} from '../presentation';
import type {RelationTargetMetadata} from './catalog';
import {mappingDestinationFields, mappingTypesCompatible} from './mappingModel';

type Props = {document: DesignDocument; source: FormRelationField; relationId?: string;
  metadata?: RelationTargetMetadata; value: FormReferenceMapping[]; disabled: boolean;
  onChange: (value: FormReferenceMapping[]) => void};

/** Mapping edits share the owning relation draft; no field storage or business data is mutated. */
export default function Mappings({document, source, relationId, metadata, value, disabled, onChange}: Props) {
  const {t, locale} = useI18n(), locked = disabled || !metadata;
  const modes = {LIVE: t('app.relation.mappingLive', '实时只读'), SERVER_SNAPSHOT: t('app.relation.mappingSnapshot', '保存快照'),
    EDITABLE_DEFAULT: t('app.relation.mappingDefault', '可编辑默认值')};
  const conversions = {IDENTITY: t('app.relation.mappingIdentity', '保持类型'), STRING: t('app.relation.mappingString', '转为文本'),
    INTEGER: t('app.relation.mappingInteger', '转为整数'), DECIMAL: t('app.relation.mappingDecimal', '转为小数')};
  const nulls = {CLEAR: t('app.relation.mappingClear', '清空'), PRESERVE: t('app.relation.mappingPreserve', '保留原值'),
    REJECT: t('app.relation.mappingReject', '拒绝保存')};
  const labels = {targetFieldId: t('app.relation.mappingTarget', '目标表单字段'), destinationFieldId: t('app.relation.mappingDestination', '本行字段'),
    mode: t('app.relation.mappingMode', '取值方式'), conversion: t('app.relation.mappingConversion', '类型转换'), nullPolicy: t('app.relation.mappingNull', '来源为空')};
  const total = (document.relations ?? []).filter(relation => relation.relationId !== relationId)
    .reduce((count, relation) => count + (relation.mappings?.length ?? 0), value.length);
  const update = (row: FormReferenceMapping, patch: Partial<FormReferenceMapping>) => {
    if (!locked) onChange(value.map(item => item.mappingId === row.mappingId ? {...item, ...patch} : item));
  };
  const destinations = (row: FormReferenceMapping) => mappingDestinationFields(document, source, row.mode, relationId)
    .filter(field => !value.some(other => other.mappingId !== row.mappingId && other.destinationFieldId === field.fieldId));
  const select = (row: FormReferenceMapping, property: keyof typeof labels, options: {const: string; title: string}[],
    change: (selected: string) => void) => <SForm hideSubmit disabled={locked} formContext={designerFormContext}
      idPrefix={`mapping_${row.mappingId}_${property}`} schema={{type: 'object', required: ['value'], properties: {
        value: {type: 'string', title: labels[property], oneOf: [{const: '', title: t('app.relation.chooseField', '请选择字段')}, ...options]},
      }}} uiSchema={{value: {'ui:options': {label: false}}}} formData={{value: row[property]}}
      onChange={({formData}, changed) => {if (!locked && changed && formData && options.some(option => option.const === formData.value)) change(formData.value);}}/>;
  const entries = (options: Record<string, string>) => Object.entries(options).map(([key, title]) => ({const: key, title}));
  return <>
    <HelpTip>{t('app.relation.mappingHint', '实时值使用临时字段；快照和默认值使用已存储字段。不会自动改变字段存储。换绑或刷新默认值需要确认，未选择覆盖的编辑值会保留。')}</HelpTip>
    <div className="sp-form-relation-mappings" style={{height: 350, minHeight: 0}}>
    <DataTable<FormReferenceMapping> rowKey="mappingId" dataSource={value} cards={false} settings={false} selectable={false}
      defaultOrder="provided" pagination={false} sticky={false} tableLayout="fixed" scroll={{x: 1080}}
      toolbarExtra={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'add', icon: 'PlusOutlined', sort: 0, text: t('app.relation.mappingAdd', '新增映射'), disabled: locked || value.length >= 32 || total >= 128},
      ]} onButtonEvents={{add: () => {
        if (!locked && value.length < 32 && total < 128) onChange([{mappingId: crypto.randomUUID(), targetFieldId: '', destinationFieldId: '',
          mode: 'SERVER_SNAPSHOT', conversion: 'IDENTITY', nullPolicy: 'CLEAR'}, ...value]);
      }}}/>}
      columns={[
        {key: 'target', title: labels.targetFieldId, width: 220, render: (_, row) => select(row, 'targetFieldId', metadata?.fields.map(field => ({const: field.fieldId,
          title: resolveLocalizedText(field.labelLocalization, locale, undefined, 'TENANT', field.label).text})) ?? [], targetFieldId => update(row, {targetFieldId}))},
        {key: 'destination', title: labels.destinationFieldId, width: 220, render: (_, row) => select(row, 'destinationFieldId', destinations(row).map(field => ({const: field.fieldId,
          title: resolveLocalizedText(field.labelLocalization, locale, undefined, 'TENANT', field.label).text})), destinationFieldId => update(row, {destinationFieldId}))},
        {key: 'mode', title: labels.mode, width: 170, render: (_, row) => select(row, 'mode', entries(modes), selected => {
          const mode = selected as FormReferenceMapping['mode'];
          const destinationFieldId = mappingDestinationFields(document, source, mode, relationId).some(field => field.fieldId === row.destinationFieldId) ? row.destinationFieldId : '';
          update(row, {mode, destinationFieldId, nullPolicy: mode === 'LIVE' || mode === 'SERVER_SNAPSHOT' && row.nullPolicy === 'PRESERVE' ? 'CLEAR' : row.nullPolicy});
        })},
        {key: 'conversion', title: labels.conversion, width: 150, render: (_, row) => {
          const from = metadata?.fields.find(field => field.fieldId === row.targetFieldId), to = destinations(row).find(field => field.fieldId === row.destinationFieldId);
          return select(row, 'conversion', entries(conversions).filter(option => !from || !to || mappingTypesCompatible(option.const as FormReferenceMapping['conversion'], from.type, to.type)),
            conversion => update(row, {conversion: conversion as FormReferenceMapping['conversion']}));
        }},
        {key: 'nullPolicy', title: labels.nullPolicy, width: 150, render: (_, row) => select(row, 'nullPolicy', entries(nulls).filter(option =>
          row.mode === 'LIVE' ? option.const === 'CLEAR' : row.mode === 'EDITABLE_DEFAULT' || option.const !== 'PRESERVE'),
        nullPolicy => update(row, {nullPolicy: nullPolicy as FormReferenceMapping['nullPolicy']}))},
        {key: 'actions', title: t('app.designer.actions', '操作'), width: 110, fixed: 'right', render: (_, row) => <TableActions appearance="row"
          selectedKeys={[]} selectedRows={[]} buttons={[{key: 'remove', icon: 'DeleteOutlined', sort: 0, text: t('table.button.delete', '删除'), danger: true, disabled: locked}]}
          onButtonEvents={{remove: () => {if (!locked) onChange(value.filter(item => item.mappingId !== row.mappingId));}}}/>},
      ]}/>
    </div>
  </>;
}
