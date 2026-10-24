import {useEffect, useMemo, useState} from 'react';
import {Drawer, Tabs, Tree} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import {createIcon} from '@simplepoint/shared/types/icon';
import type {DataNode} from 'antd/es/tree';
import SForm from '../SForm/Lazy';
import DataTable from '../DataTable';
import TableActions from '../Table/Actions';
import {componentIcon, designerFormContext} from './presentation';
import {designFields, type AnalyzedField, type FieldTarget} from './fieldAnalysis';
import DesignerDiagnostics from './DesignerDiagnostics';
import type {DesignDocument} from './contracts';
import type {FormComponentDefinition} from '../FormRuntime/contracts';
import type {DesignerCalculations} from './CalculationFieldEditor';

type Props = {document: DesignDocument; selectedId: string; open?: 'tree' | 'fields' | 'checks'; catalog: DesignerCalculations;
  onClose: () => void; onSelect: (componentId: string) => void; onField: (target: FieldTarget) => void};

/** Ant Tree owns hierarchical keyboard navigation; no platform table/selector provides a canvas outline. */
export default function DesignerNavigator({document, selectedId, open, catalog, onClose, onSelect, onField}: Props) {
  const {t} = useI18n(), selected = usePageSelected();
  const [tab, setTab] = useState(open), [search, setSearch] = useState(''), [expanded, setExpanded] = useState<React.Key[]>([]);
  useEffect(() => {if (open) setTab(open);}, [open]);
  const fields = useMemo(() => designFields(document), [document]);
  const {nodes, ancestors, allKeys} = useMemo(() => {
    const ancestors: string[] = [], allKeys: string[] = [], term = search.trim().toLocaleLowerCase();
    const build = (node: FormComponentDefinition, parents: string[], sceneName: string): DataNode | undefined => {
      allKeys.push(node.componentId);
      if (node.componentId === selectedId) ancestors.push(...parents);
      const field = document.fields.find(item => item.fieldId === node.fieldId);
      const label = node.label || field?.label || node.componentType;
      const children = node.children.map(child => build(child, [...parents, node.componentId], sceneName)).filter((item): item is DataNode => Boolean(item));
      const matches = [label, field?.label, field?.storageCode, sceneName].some(value => value?.toLocaleLowerCase().includes(term));
      if (term && !matches && !children.length) return undefined;
      return {key: node.componentId, title: parents.length ? label : `${sceneName} · ${label}`, icon: createIcon(componentIcon(node.componentType)), children};
    };
    return {nodes: document.scenes.map(scene => build(scene.root, [], scene.name)).filter((item): item is DataNode => Boolean(item)), ancestors, allKeys};
  }, [document, selectedId, search]);
  useEffect(() => {setExpanded(current => [...new Set([...current, ...ancestors])]);}, [ancestors]);
  const locateField = (field: FieldTarget) => {onClose(); onField(field);};
  return <Drawer className="sp-form-designer-navigation" placement="left" size={520} open={Boolean(open) && selected} onClose={onClose}
    title={t('app.designer.navigator', '结构导航')} destroyOnHidden>
    <Tabs activeKey={tab} onChange={key => setTab(key as typeof tab)} items={[
      {key: 'tree', icon: createIcon('ApartmentOutlined'), label: t('app.designer.outline', '组件树'), children: <>
        <SForm formContext={designerFormContext} idPrefix="designer_navigation" hideSubmit schema={{type: 'object', properties: {
          search: {type: 'string', title: t('app.designer.search', '搜索'), maxLength: 120},
        }}} formData={{search}} onChange={({formData}, changed) => {if (changed) setSearch(String(formData?.search ?? ''));}}/>
        <Tree showIcon blockNode treeData={nodes} selectedKeys={[selectedId]} expandedKeys={search ? allKeys : expanded}
          onExpand={keys => setExpanded(keys)} onSelect={keys => {if (keys.length) {onClose(); onSelect(String(keys[0]));}}}/>
      </>},
      {key: 'fields', icon: createIcon('UnorderedListOutlined'), label: t('app.designer.fields', '字段'), children:
        <DataTable<AnalyzedField> rowKey="key" dataSource={fields} cards={false} settings={false} selectable={false} pagination={{pageSize: 15}}
          query={{mode: 'local', scope: 'complete', contextKey: 'design-fields', fields: {label: {type: 'string'}}}}
          scroll={{x: 380, y: 500}} columns={[
            {key: 'label', dataIndex: 'label', title: t('app.designer.fieldLabel', '字段名称')},
            {key: 'actions', title: t('app.designer.actions', '操作'), width: 90, render: (_, row) =>
              <TableActions appearance="row" selectedKeys={[]} selectedRows={[]} buttons={[
                {key: 'locate', sort: 0, icon: 'AimOutlined', text: t('app.designer.locate', '定位')},
              ]} onButtonEvents={{locate: () => locateField(row)}}/>},
          ]}/>},
      {key: 'checks', icon: createIcon('CheckCircleOutlined'), label: t('app.designer.check', '配置检查'), children:
        <DesignerDiagnostics document={document} catalog={catalog} onLocate={locateField}/>},
    ]}/>
  </Drawer>;
}
