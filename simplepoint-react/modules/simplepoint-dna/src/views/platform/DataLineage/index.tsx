import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {Page} from '@simplepoint/shared/types/request';
import {Alert, Button, Card, Empty, Spin, Tag, message, Modal, Input, Select, Space, Descriptions} from 'antd';
import {ApartmentOutlined, PlusOutlined} from '@ant-design/icons';
import {useCallback, useEffect, useMemo, useState} from 'react';
import {resolveErrorMessage} from '../shared';
import {LineageGraphView} from './LineageGraphView';
import type {DataSourceOption, LineageGraph, LineageNode} from './types';
import {NODE_TYPE_KEYS, EDGE_TYPE_KEYS, NODE_TAG_COLORS} from './types';

const nodesConfig = api['platform.dna-data-lineage-nodes'];
const edgesConfig = api['platform.dna-data-lineage-edges'];
const dataSourceConfig = api['platform.dna-data-sources'];

const App = () => {
  const {t, ensure, locale} = useI18n();
  const [dataSources, setDataSources] = useState<DataSourceOption[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [activeTab, setActiveTab] = useState<'nodes' | 'graph'>('nodes');
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [graph, setGraph] = useState<LineageGraph | null>(null);
  const [graphLoading, setGraphLoading] = useState(false);

  // Edge creation state
  const [edgeModalVisible, setEdgeModalVisible] = useState(false);
  const [allNodes, setAllNodes] = useState<LineageNode[]>([]);
  const [newEdge, setNewEdge] = useState({sourceNodeId: '', targetNodeId: '', edgeType: 'DIRECT', transformDescription: ''});

  const nodeTypes = useMemo(() => NODE_TYPE_KEYS.map((key) => ({
    const: key,
    title: t(`dna.dataLineage.nodeType.${key}`, key),
  })), [t]);

  const edgeTypes = useMemo(() => EDGE_TYPE_KEYS.map((key) => ({
    const: key,
    title: t(`dna.dataLineage.edgeType.${key}`, key),
  })), [t]);

  useEffect(() => {
    void ensure([...nodesConfig.i18nNamespaces, ...edgesConfig.i18nNamespaces, ...dataSourceConfig.i18nNamespaces]);
  }, [ensure, locale]);

  const loadDataSources = useCallback(async () => {
    const page = await get<Page<DataSourceOption>>(dataSourceConfig.baseUrl, {page: 0, size: 200});
    setDataSources((page.content ?? []).filter((ds) => ds.enabled !== false));
    setLoaded(true);
  }, []);

  useEffect(() => {
    void loadDataSources().catch((error) => {
      setLoaded(true);
      message.error(resolveErrorMessage(error, t('dna.dataLineage.error.loadDataSources', 'Failed to load data source list')));
    });
  }, [loadDataSources, t]);

  const loadAllNodes = useCallback(async () => {
    const page = await get<Page<LineageNode>>(nodesConfig.baseUrl, {page: 0, size: 500});
    setAllNodes(page.content ?? []);
  }, []);

  useEffect(() => {
    void loadAllNodes().catch((err) => {
      console.warn('Failed to load lineage nodes for edge form', err);
    });
  }, [loadAllNodes]);

  const loadGraph = useCallback(async (nodeId: string) => {
    setGraphLoading(true);
    try {
      const result = await get<LineageGraph>(`${nodesConfig.baseUrl}/graph`, {nodeId, depth: 3});
      setGraph(result);
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.dataLineage.error.loadGraph', 'Failed to load lineage graph')));
    } finally {
      setGraphLoading(false);
    }
  }, []);

  const handleViewGraph = useCallback((nodeId: string) => {
    setSelectedNodeId(nodeId);
    setActiveTab('graph');
    void loadGraph(nodeId);
  }, [loadGraph]);

  const handleCreateEdge = useCallback(async () => {
    try {
      const {post: doPost} = await import('@simplepoint/shared/api/methods');
      await doPost(edgesConfig.baseUrl, newEdge);
      message.success(t('dna.dataLineage.success.createEdge', 'Lineage relationship created'));
      setEdgeModalVisible(false);
      setNewEdge({sourceNodeId: '', targetNodeId: '', edgeType: 'DIRECT', transformDescription: ''});
      if (selectedNodeId) {
        void loadGraph(selectedNodeId);
      }
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.dataLineage.error.createEdge', 'Failed to create lineage relationship')));
    }
  }, [newEdge, selectedNodeId, loadGraph]);

  const handleDeleteEdge = useCallback(async (edgeId: string) => {
    try {
      const {del} = await import('@simplepoint/shared/api/methods');
      await del(edgesConfig.baseUrl, edgeId);
      message.success(t('dna.dataLineage.success.deleteEdge', 'Lineage relationship deleted'));
      if (selectedNodeId) {
        void loadGraph(selectedNodeId);
      }
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.dataLineage.error.deleteEdge', 'Failed to delete lineage relationship')));
    }
  }, [selectedNodeId, loadGraph]);

  const formSchemaTransform = useCallback((schema: any) => {
    const nextSchema = structuredClone(schema ?? {});
    const properties = nextSchema?.properties ?? {};
    if (properties.catalogId) {
      properties.catalogId.title = t('dna.dataLineage.title.catalogId', 'Data Source');
      properties.catalogId.oneOf = dataSources.map((ds) => ({
        const: ds.id,
        title: ds.name || ds.code || ds.id,
      }));
    }
    if (properties.nodeType) {
      properties.nodeType.oneOf = nodeTypes;
    }
    delete properties.catalogName;
    return nextSchema;
  }, [dataSources, nodeTypes, t]);

  const columnOverrides = useMemo(() => ({
    catalogId: {
      title: t('dna.dataLineage.title.catalogId', 'Data Source'),
      width: 150,
      render: (_: string, record: {catalogName?: string}) =>
        record.catalogName || '-',
    },
    nodeType: {
      title: t('dna.dataLineage.title.nodeType', 'Node Type'),
      width: 110,
      render: (value?: string) => (
        <Tag color={NODE_TAG_COLORS[value ?? ''] ?? 'default'}>{value || '-'}</Tag>
      ),
    },
  }), [t]);

  const renderGraph = () => {
    if (graphLoading) {
      return (
        <Card>
          <div style={{textAlign: 'center', padding: 60}}>
            <Spin size="large" tip={t('dna.dataLineage.loading', 'Loading...')} />
          </div>
        </Card>
      );
    }
    if (!graph || graph.nodes.length === 0) {
      return <Card><Empty description={t('dna.dataLineage.empty.noData', 'No lineage data available')} /></Card>;
    }

    const rootNode = graph.nodes.find((n) => n.id === graph.rootNodeId);

    return (
      <div>
        {/* Toolbar */}
        <div style={{marginBottom: 12, display: 'flex', justifyContent: 'space-between', alignItems: 'center'}}>
          <Button onClick={() => setActiveTab('nodes')}>
            {t('dna.dataLineage.button.backToNodes', '← Back to node list')}
          </Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              void loadAllNodes();
              setEdgeModalVisible(true);
            }}
          >
            {t('dna.dataLineage.button.addEdge', 'Add Lineage Relationship')}
          </Button>
        </div>

        {/* Root node metadata */}
        {rootNode && (
          <Card size="small" style={{marginBottom: 12}}>
            <Descriptions size="small" column={3}>
              <Descriptions.Item label={t('dna.dataLineage.label.name', 'Name')}>{rootNode.name}</Descriptions.Item>
              <Descriptions.Item label={t('dna.dataLineage.label.type', 'Type')}>
                <Tag color={NODE_TAG_COLORS[rootNode.nodeType]}>{rootNode.nodeType}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('dna.dataLineage.label.dataSource', 'Data Source')}>
                {rootNode.catalogName || '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('dna.dataLineage.label.schema', 'Schema')}>
                {rootNode.schemaName || '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('dna.dataLineage.label.tableName', 'Table Name')}>
                {rootNode.tableName}
              </Descriptions.Item>
              <Descriptions.Item label={t('dna.dataLineage.label.columnName', 'Column Name')}>
                {rootNode.columnName || '-'}
              </Descriptions.Item>
            </Descriptions>
          </Card>
        )}

        {/* React Flow lineage graph */}
        <div style={{height: 520, borderRadius: 8, overflow: 'hidden', border: '1px solid #e8e8e8'}}>
          <LineageGraphView
            graph={graph}
            onNavigate={handleViewGraph}
            onDeleteEdge={handleDeleteEdge}
          />
        </div>
      </div>
    );
  };

  return (
    <div>
      {loaded && dataSources.length === 0 ? (
        <Alert type="warning" showIcon style={{marginBottom: 16}}
          message={t('dna.dataLineage.alert.noDataSources', 'No data sources available. Please add and enable a data source first.')}
        />
      ) : null}

      {activeTab === 'nodes' ? (
        <div>
          <div style={{marginBottom: 16}}>
            <Button
              type="primary"
              icon={<ApartmentOutlined />}
              disabled={allNodes.length === 0}
              onClick={() => {
                if (allNodes.length > 0) {
                  handleViewGraph(allNodes[0].id);
                }
              }}
            >
              {t('dna.dataLineage.button.viewGraph', 'View Lineage Graph')}
            </Button>
          </div>
          <SimpleTable
            {...nodesConfig}
            formSchemaTransform={formSchemaTransform}
            columnOverrides={columnOverrides}
          />
        </div>
      ) : (
        renderGraph()
      )}

      <Modal
        title={t('dna.dataLineage.modal.title', 'Add Lineage Relationship')}
        open={edgeModalVisible}
        onOk={handleCreateEdge}
        onCancel={() => setEdgeModalVisible(false)}
        okText={t('dna.dataLineage.modal.ok', 'OK')}
        cancelText={t('dna.dataLineage.modal.cancel', 'Cancel')}
      >
        <Space direction="vertical" style={{width: '100%'}}>
          <div>
            <div style={{marginBottom: 4}}>{t('dna.dataLineage.modal.sourceNode', 'Source Node')}</div>
            <Select
              style={{width: '100%'}}
              placeholder={t('dna.dataLineage.placeholder.sourceNode', 'Select source node')}
              value={newEdge.sourceNodeId || undefined}
              onChange={(v) => setNewEdge({...newEdge, sourceNodeId: v})}
              showSearch
              optionFilterProp="label"
              options={allNodes.map((n) => ({value: n.id, label: `${n.name} (${n.tableName})`}))}
            />
          </div>
          <div>
            <div style={{marginBottom: 4}}>{t('dna.dataLineage.modal.targetNode', 'Target Node')}</div>
            <Select
              style={{width: '100%'}}
              placeholder={t('dna.dataLineage.placeholder.targetNode', 'Select target node')}
              value={newEdge.targetNodeId || undefined}
              onChange={(v) => setNewEdge({...newEdge, targetNodeId: v})}
              showSearch
              optionFilterProp="label"
              options={allNodes.map((n) => ({value: n.id, label: `${n.name} (${n.tableName})`}))}
            />
          </div>
          <div>
            <div style={{marginBottom: 4}}>{t('dna.dataLineage.modal.edgeType', 'Relationship Type')}</div>
            <Select
              style={{width: '100%'}}
              value={newEdge.edgeType}
              onChange={(v) => setNewEdge({...newEdge, edgeType: v})}
              options={edgeTypes.map((et) => ({value: et.const, label: et.title}))}
            />
          </div>
          <div>
            <div style={{marginBottom: 4}}>{t('dna.dataLineage.modal.transformDesc', 'Transform Description')}</div>
            <Input.TextArea
              placeholder={t('dna.dataLineage.placeholder.transformDesc', 'Describe data transformation logic (optional)')}
              value={newEdge.transformDescription}
              onChange={(e) => setNewEdge({...newEdge, transformDescription: e.target.value})}
              rows={3}
            />
          </div>
        </Space>
      </Modal>
    </div>
  );
};

export default App;
