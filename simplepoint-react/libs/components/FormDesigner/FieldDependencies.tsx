import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, Empty, Tabs} from 'antd';
import {createIcon} from '@simplepoint/shared/types/icon';
import DataTable from '../DataTable';
import TableActions from '../Table/Actions';
import {affectedFields, fieldDependencies, fieldKey, fieldUsages, type AnalyzedField, type FieldTarget} from './fieldAnalysis';
import type {DesignDocument} from './contracts';

/** Identity-based references and transitive impact are shared by editing, diagnostics and navigation. */
export default function FieldDependencies({document, target, onLocate}: {
  document: DesignDocument; target: FieldTarget; onLocate?: (target: FieldTarget) => void;
}) {
  const {t} = useI18n(), graph = fieldDependencies(document), key = fieldKey(target);
  const inputs = graph.fields.filter(item => graph.edges.get(key)?.includes(item.key));
  const outputs = affectedFields(document, target), usages = fieldUsages(document, target);
  const table = (fields: AnalyzedField[]) => <DataTable<AnalyzedField> rowKey="key" dataSource={fields} settings={false} cards={false} selectable={false}
    pagination={fields.length > 10 ? {defaultPageSize: 50} : false} scroll={{x: 350, y: 250}} columns={[
      {key: 'label', dataIndex: 'label', title: t('app.designer.fieldLabel', '字段名称')},
      {key: 'type', title: t('app.designer.fieldType', '数据类型'), render: (_, row) => row.field.type},
      ...(onLocate ? [{key: 'actions', title: t('app.designer.actions', '操作'), render: (_: unknown, row: AnalyzedField) =>
        <TableActions appearance="row" selectedKeys={[]} selectedRows={[]} buttons={[
          {key: 'locate', sort: 0, icon: 'AimOutlined', text: t('app.designer.locate', '定位')},
        ]} onButtonEvents={{locate: () => onLocate(row)}}/>}] : []),
    ]}/>;
  return <div className="sp-form-field-dependencies">
    {graph.cyclic.has(key) && <Alert type="error" showIcon title={t('app.calculation.errorCycle', '字段之间存在循环依赖')}/>}
    <Tabs items={[
      {key: 'inputs', icon: createIcon('ImportOutlined'), label: t('app.calculation.inputs', '依赖字段'), children: table(inputs)},
      {key: 'outputs', icon: createIcon('ExportOutlined'), label: t('app.calculation.outputs', '影响字段'), children: table(outputs)},
      {key: 'scenes', icon: createIcon('LayoutOutlined'), label: t('app.calculation.scenes', '引用场景'), children: usages.length
        ? <ul>{usages.map(usage => <li key={usage.componentId}>{usage.sceneName} · {usage.label}</li>)}</ul> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}/>},
    ]}/>
  </div>;
}
