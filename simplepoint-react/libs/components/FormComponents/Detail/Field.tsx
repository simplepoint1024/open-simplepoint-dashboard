import {fieldOperationHidden} from '../../FormRuntime/writePolicy';
import {formatTemporal} from '@simplepoint/shared/format/temporal';
import {useRef, useState} from 'react';
import {toFieldPathId, type FieldProps, type RJSFSchema} from '@rjsf/utils';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import DataTable from '../../DataTable';
import TableActions from '../../Table/Actions';
import {displayValue} from '../../SForm/displayOnly';
import {schemaUi} from '../../SForm/schemaUi';
import type {FormDetailDefinition} from '../../FormRuntime/contracts';
import type {RecordState} from '../../FormRuntime/recordTableModel';
import {detailRowSchema} from './schema';

type Row = {id?: string; version?: number; values: Record<string, unknown>};

/** Inline fields use the owning form's registry, validation paths and aggregate draft. No nested forms. */
export default function DetailRows({schema, formData, uiSchema, disabled, readonly, onChange, fieldPathId,
  registry, errorSchema, onBlur, onFocus}: FieldProps) {
  const {t, locale} = useI18n(), selected = usePageSelected();
  const [editing, setEditing] = useState<string>();
  const identity = useRef<{keys: string[]; saved: Map<string, string>}>({keys: [], saved: new Map()});
  const options = uiSchema?.['ui:options'] as {detail: FormDetailDefinition; recordState?: RecordState; readOnly?: boolean};
  const detail = options.detail;
  const rows: Row[] = Array.isArray(formData) ? formData : [];
  // Keys are UI-only. Retain widget ownership when rows are prepended/deleted or receive server IDs.
  const keys = rows.map((row, index) => row.id && identity.current.saved.get(row.id)
    || identity.current.keys[index] || crypto.randomUUID());
  identity.current.keys = keys;
  rows.forEach((row, index) => {if (row.id) identity.current.saved.set(row.id, keys[index]);});
  const viewOnly = Boolean(readonly || options.readOnly);
  const locked = disabled || viewOnly || !selected;
  const write = (next: Row[], nextKeys = keys) => {
    if (locked) return;
    identity.current.keys = nextKeys;
    onChange(next, fieldPathId.path);
  };
  const {SchemaField} = registry.fields;
  const required = ((schema.items as RJSFSchema)?.properties?.values as RJSFSchema)?.required ?? [];
  const cell = (field: FormDetailDefinition['fields'][number], row: Row, index: number) => {
    if (!viewOnly && fieldOperationHidden(field, row.id ? options.recordState : undefined)) return null;
    const issue = errorSchema?.[index]?.values?.[field.fieldId];
    if (!viewOnly && (editing === keys[index] || issue?.__errors?.length)) {
      const definition = detailRowSchema(detail, row.id ? options.recordState : undefined, false);
      const fieldSchema = definition.properties![field.fieldId] as RJSFSchema;
      const path = [index, 'values', field.fieldId].reduce((parent, key) => toFieldPathId(key, registry.globalFormOptions, parent), fieldPathId);
      return <div className="sp-form-detail-cell">
        <SchemaField name={field.fieldId} schema={fieldSchema} registry={registry} fieldPathId={path}
          uiSchema={{...schemaUi(fieldSchema), 'ui:label': false}} formData={row.values[field.fieldId]}
          errorSchema={issue} required={required.includes(field.fieldId) || definition.required?.includes(field.fieldId)}
          disabled={locked} readonly={Boolean(fieldSchema.readOnly)} onBlur={onBlur} onFocus={onFocus}
          onChange={value => write(rows.map((item, rowIndex) => rowIndex === index
            ? {...item, values: {...item.values, [field.fieldId]: value}} : item))}/>
      </div>;
    }
    return ['DATE', 'DATETIME', 'TIME'].includes(field.type) && !field.choices.length
      ? formatTemporal(row.values[field.fieldId], {locale, kind: field.type === 'DATE' ? 'date' : field.type === 'TIME' ? 'time' : 'datetime'})
      : displayValue(row.values[field.fieldId], field.choices.map(choice => ({const: choice.value, title: choice.label})));
  };
  return <div className="sp-form-detail" data-detail-id={detail.detailId}>
    <DataTable<Row & {rowKey: string; rowIndex: number}> rowKey="rowKey" storageKey={`form.detail.${detail.detailId}`}
      cards={false} selectable={false} settings={false} defaultOrder="provided" sticky={false} tableLayout="fixed"
      scroll={{x: 'max-content', y: 'min(320px, 45dvh)'}}
      dataSource={rows.map((row, index) => ({...row, rowKey: keys[index], rowIndex: index}))} pagination={false}
      toolbarExtra={<><strong>{schema.title ?? detail.label}</strong>{!viewOnly && <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'add', sort: 0, icon: 'PlusOutlined', iconOnly: true, text: t('app.runtime.detailAdd', '添加明细'), disabled: locked || rows.length >= 40},
      ]} onButtonEvents={{add: () => {
        if (locked || rows.length >= 40) return;
        const definition = detailRowSchema(detail, undefined, false);
        const values = registry.schemaUtils.getDefaultFormState(definition, {}) as Record<string, unknown>;
        const key = crypto.randomUUID();
        write([{values}, ...rows], [key, ...keys]); setEditing(key);
      }}}/>}</>}
      columns={[...detail.fields.filter(field => viewOnly || !fieldOperationHidden(field, undefined) || !fieldOperationHidden(field, options.recordState)).map(field => ({key: field.fieldId, title: field.label,
        width: field.type === 'TEXT' ? 260 : field.type === 'DATETIME' ? 200 : 180,
        render: (_: unknown, row: Row & {rowKey: string; rowIndex: number}) => cell(field, row, row.rowIndex)})),
        ...(!viewOnly ? [{key: 'actions', title: t('app.designer.actions', '操作'), width: 160, fixed: 'right' as const,
          render: (_: unknown, row: Row & {rowKey: string; rowIndex: number}) => <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
            {key: 'edit', sort: 0, icon: editing === row.rowKey ? 'CheckOutlined' : 'EditOutlined',
              text: editing === row.rowKey ? t('app.runtime.detailDone', '完成') : t('table.button.edit', '编辑'), disabled: locked},
            {key: 'remove', sort: 1, icon: 'DeleteOutlined', text: t('table.button.delete', '删除'), danger: true, disabled: locked},
          ]} onButtonEvents={{edit: () => {if (!locked) setEditing(editing === row.rowKey ? undefined : row.rowKey);},
            remove: () => {
              if (locked) return;
              write(rows.filter((_, index) => index !== row.rowIndex), keys.filter(key => key !== row.rowKey));
              setEditing(current => current === row.rowKey ? undefined : current);
            }}}/>}] : []),
      ]}/>
  </div>;
}
