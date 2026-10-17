import {useMemo} from 'react';
import {Alert, Descriptions, Empty, Tabs} from 'antd';
import DataTable from '@simplepoint/components/DataTable';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {ApartmentOutlined, KeyOutlined, TableOutlined} from '@simplepoint/shared/types/icon';
import {formatDateTime} from '../shared';
import {parseModelDefinition, type ModelField, type ModelIndex} from './modelDefinition';

/** Read-only model metadata uses platform tables and keeps the schema's declared order. */
export default function ModelDefinition({source}: {source?: string}) {
  const {t} = useI18n();
  const parsed = useMemo(() => {
    try {return {schema: parseModelDefinition(source), invalid: false};}
    catch {return {schema: undefined, invalid: true};}
  }, [source]);
  if (parsed.invalid) return <Alert type="error" showIcon title={t('dna.runtime.definition.invalid')}/>;
  const schema = parsed.schema;
  if (!schema) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('dna.runtime.definition.empty')}/>;
  const label = (key: string) => t('dna.runtime.definition.' + key);
  const yesNo = (value: boolean) => label(value ? 'yes' : 'no');
  const names = new Map(schema.fields.map(field => [field.code, field.displayName]));
  const defaults = (value: ModelField['defaultValue']) => {
    if (!value) return '—';
    if (value.kind === 'NULL') return 'NULL';
    if (value.kind === 'DATETIME') return formatDateTime(value.canonicalValue ?? undefined);
    if (value.kind === 'BOOLEAN') return yesNo(value.canonicalValue === 'true');
    return value.canonicalValue === '' ? label('emptyText') : value.canonicalValue;
  };
  return <section className="dna-model-definition" aria-label={label('title')}>
    <Tabs items={[
      {key: 'fields', icon: <TableOutlined/>, label: `${label('fields')} (${schema.fields.length})`, children:
        <DataTable<ModelField> rowKey="fieldId" storageKey="dna.runtime.definition.fields" cards={false}
          defaultOrder="provided" pagination={false} scroll={{x: 1060, y: 360}}
          dataSource={schema.fields} columns={[
            {title: label('name'), dataIndex: 'displayName', width: 160, fixed: 'left'},
            {title: label('code'), dataIndex: 'code', width: 180},
            {title: label('type'), dataIndex: 'type', width: 140,
              render: (value: string) => t('dna.runtime.definition.type.' + value, value)},
            {title: label('required'), dataIndex: 'nullable', width: 90, render: (value: boolean) => yesNo(!value)},
            {title: label('length'), dataIndex: 'length', width: 90, render: (value?: number) => value ?? '—'},
            {title: label('precision'), key: 'precision', width: 100,
              render: (_: unknown, field: ModelField) => field.precision == null ? '—' : `${field.precision}, ${field.scale ?? 0}`},
            {title: label('default'), dataIndex: 'defaultValue', width: 180, ellipsis: true, render: defaults},
            {title: label('identity'), dataIndex: 'fieldId', width: 200},
          ]}/>},
      {key: 'indexes', icon: <KeyOutlined/>, label: `${label('indexes')} (${schema.indexes.length})`, children:
        <DataTable<ModelIndex> rowKey="code" storageKey="dna.runtime.definition.indexes" cards={false}
          defaultOrder="provided" pagination={false} scroll={{x: 640, y: 360}}
          dataSource={schema.indexes} columns={[
            {title: label('code'), dataIndex: 'code', width: 200},
            {title: label('indexFields'), dataIndex: 'fieldCodes', width: 340,
              render: (codes: string[]) => codes.map(code => `${names.get(code)} (${code})`).join(' → ')},
            {title: label('unique'), dataIndex: 'unique', width: 100, render: yesNo},
          ]}/>},
      ...(schema.owner ? [{key: 'owner', icon: <ApartmentOutlined/>, label: label('owner'), children:
        <Descriptions size="small" column={1} items={[
          {key: 'model', label: label('parentModel'), children: schema.owner.parentModelId},
          {key: 'field', label: label('parentField'), children: schema.fields.find(field => field.fieldId === schema.owner?.parentFieldId)?.displayName},
        ]}/>,
      }] : []),
    ]}/>
  </section>;
}
