import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {resolveLocalizedText} from '@simplepoint/shared/i18n/localizedText';
import DataTable from '../../DataTable';
import SForm from '../../SForm';
import TableActions from '../../Table/Actions';
import HelpTip from '../../HelpTip';
import type {DesignDocument} from '../contracts';
import type {FormReferenceContextFilter, FormRelationField} from '../relationContracts';
import type {RelationTargetMetadata} from './catalog';
import {designerFormContext} from '../presentation';
import {contextOperators, contextSourceFields, contextTypesMatch, type ContextFilterDraft} from './contextModel';

/** Rows edit the relation dialog's draft directly; no second apply/cancel buffer. */
export default function ContextFilters({document, source, metadata, value, disabled, onChange}: {
  document: DesignDocument; source: FormRelationField; metadata?: RelationTargetMetadata;
  value: ContextFilterDraft[]; disabled: boolean; onChange: (value: ContextFilterDraft[]) => void;
}) {
  const {t, locale} = useI18n(), locked = disabled || !metadata;
  const fields = contextSourceFields(document, source);
  const labels = {
    source: t('app.relation.contextSource', '条件取值'), target: t('app.relation.contextTarget', '目标字段'),
    operator: t('app.relation.contextOperator', '比较方式'),
  };
  const choose = {const: '', title: t('app.relation.chooseField', '请选择字段')};
  const operatorLabels: Record<FormReferenceContextFilter['operator'], string> = {
    EQUALS: t('app.relation.equal', '等于'), NOT_EQUALS: t('app.relation.notEqual', '不等于'),
    GREATER_THAN: t('app.relation.greater', '大于'), GREATER_THAN_OR_EQUAL: t('app.relation.greaterEqual', '大于或等于'),
    LESS_THAN: t('app.relation.less', '小于'), LESS_THAN_OR_EQUAL: t('app.relation.lessEqual', '小于或等于'),
  };
  const sourceOptions = [choose, {const: 'ORGANIZATION', title: t('app.relation.currentOrganization', '当前组织')},
    ...fields.map(input => ({const: `${input.source}:${input.field.fieldId}`, title: `${input.source === 'FORM'
      ? t('app.relation.mainField', '主表字段') : t('app.relation.rowField', '当前明细行')} · ${resolveLocalizedText(
      input.field.labelLocalization, locale, undefined, 'TENANT', input.field.label).text}`}))];
  const update = (row: ContextFilterDraft, next: FormReferenceContextFilter) => {
    if (!locked) onChange(value.map(item => item.key === row.key ? {...item, value: next} : item));
  };
  const control = (row: ContextFilterDraft, name: keyof typeof labels, selected: string,
    options: {const: string; title: string}[], change: (value: string) => void) =>
    <SForm formContext={designerFormContext} hideSubmit disabled={locked} idPrefix={`context_${row.key}_${name}`}
      schema={{type: 'object', properties: {value: {type: 'string', title: labels[name], oneOf: options}}}}
      uiSchema={{value: {'ui:options': {label: false}}}} formData={{value: selected}}
      onChange={({formData}, changed) => {if (changed && formData && !locked) change(formData.value ?? '');}}/>;
  return <>
    <HelpTip>{t('app.relation.contextHint', '所有条件同时满足才会列入候选项。取值为空时不返回候选项；当前组织由服务端确定。候选筛选不替代保存校验。')}</HelpTip>
    <div style={{height: 350, minHeight: 0}}>
      <DataTable<ContextFilterDraft> rowKey="key" dataSource={value} pagination={false} cards={false} settings={false} selectable={false}
        scroll={{x: 730}} toolbarExtra={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
          {key: 'add', icon: 'PlusOutlined', sort: 0, text: t('app.relation.addContext', '新增联动条件'), disabled: locked || value.length >= 16},
        ]} onButtonEvents={{add: () => {if (!locked && value.length < 16) onChange([{key: crypto.randomUUID(),
          value: {targetFieldId: '', operator: 'EQUALS', source: 'FORM', sourceFieldId: ''}}, ...value]);}}}/>}
        columns={[
          {key: 'source', title: labels.source, width: 260, render: (_: unknown, row: ContextFilterDraft) => control(row, 'source',
            row.value.source === 'ORGANIZATION' ? 'ORGANIZATION' : row.value.sourceFieldId ? `${row.value.source}:${row.value.sourceFieldId}` : '',
            sourceOptions, selected => {
              const input = fields.find(input => `${input.source}:${input.field.fieldId}` === selected);
              update(row, selected === 'ORGANIZATION' ? {targetFieldId: '', operator: 'EQUALS', source: 'ORGANIZATION'}
                : {targetFieldId: '', operator: 'EQUALS', source: input?.source ?? 'FORM', sourceFieldId: input?.field.fieldId ?? ''});
            })},
          {key: 'target', title: labels.target, width: 220, render: (_: unknown, row: ContextFilterDraft) => {
            const filter = row.value, input = fields.find(input => input.source === filter.source && input.field.fieldId === filter.sourceFieldId);
            const options = metadata?.fields.filter(field => field.type !== 'JSON' && (filter.source === 'ORGANIZATION'
              ? ['STRING', 'TEXT', 'UUID'].includes(field.type) : input && contextTypesMatch(input.field.type, field.type))) ?? [];
            return control(row, 'target', filter.targetFieldId, [choose, ...options.map(field => ({const: field.fieldId,
              title: resolveLocalizedText(field.labelLocalization, locale, undefined, 'TENANT', field.label).text}))],
            selected => update(row, {...filter, targetFieldId: selected, operator: 'EQUALS'}));
          }},
          {key: 'operator', title: labels.operator, width: 150, render: (_: unknown, row: ContextFilterDraft) => control(row, 'operator', row.value.operator,
            contextOperators(metadata?.fields.find(field => field.fieldId === row.value.targetFieldId)?.type)
              .map(operator => ({const: operator, title: operatorLabels[operator]})),
            selected => {if (selected in operatorLabels) update(row, {...row.value, operator: selected as FormReferenceContextFilter['operator']});})},
          {key: 'actions', title: t('app.designer.actions', '操作'), width: 100, fixed: 'right', render: (_: unknown, row: ContextFilterDraft) =>
            <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
              {key: 'remove', icon: 'DeleteOutlined', sort: 0, text: t('table.button.delete', '删除'), danger: true, disabled: locked},
            ]} onButtonEvents={{remove: () => {if (!locked) onChange(value.filter(item => item.key !== row.key));}}}/>},
        ]}/>
    </div>
  </>;
}
