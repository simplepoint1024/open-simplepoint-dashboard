import '@xyflow/react/dist/style.css';

import {
  Background,
  Controls,
  Handle,
  MiniMap,
  Position,
  ReactFlow,
  ReactFlowProvider,
  type Connection,
  type Edge as FlowEdge,
  type Node as FlowNode,
  type NodeProps,
} from '@xyflow/react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {
  Alert,
  App,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import DependencyOptionSelect, {
  type DependencyOptionDirectory,
} from '../../components/DependencyOptionSelect';
import JsonSchemaObjectEditor from '../../components/JsonSchemaObjectEditor';
import {
  documentFromManifest,
  hasPath,
  manifestFromDocument,
  newWorkflowNode,
  validateWorkflowDocument,
  type WorkflowDesignerDocument,
  type WorkflowDesignerEdge,
  type WorkflowDesignerNode,
  type WorkflowDesignerNodeType,
} from './model';

const {Text} = Typography;

type WorkflowNodeData = {
  label: string;
  nodeType: WorkflowDesignerNodeType;
  invalid: boolean;
};

const nodeColors: Record<WorkflowDesignerNodeType, string> = {
  agent: '#722ed1',
  skill: '#1677ff',
  human: '#d46b08',
  wait: '#08979c',
  condition: '#c41d7f',
  parallel: '#531dab',
  end: '#389e0d',
};

type Translate = (key: string, fallback?: string) => string;

const workflowNodeTypeLabel = (
  t: Translate,
  nodeType: WorkflowDesignerNodeType,
) => {
  switch (nodeType) {
    case 'agent':
      return t('ai.workflows.designer.node.agent', 'Agent');
    case 'skill':
      return t('ai.workflows.designer.node.skill', 'Skill');
    case 'human':
      return t('ai.workflows.designer.node.human', '人工任务');
    case 'wait':
      return t('ai.workflows.designer.node.wait', '等待');
    case 'condition':
      return t('ai.workflows.designer.node.condition', '条件');
    case 'parallel':
      return t('ai.workflows.designer.node.parallel', '并行');
    case 'end':
      return t('ai.workflows.designer.node.end', '结束');
  }
};

const WorkflowNodeCard = ({data, selected}: NodeProps<FlowNode<WorkflowNodeData>>) => {
  const {t} = useI18n();
  return (
    <div style={{
      minWidth: 170,
      padding: 12,
      borderRadius: 8,
      background: 'var(--ant-color-bg-container)',
      border: `2px solid ${data.invalid ? '#ff4d4f' : selected
        ? nodeColors[data.nodeType] : 'var(--ant-color-border)'}`,
      boxShadow: selected ? '0 4px 16px rgba(0,0,0,.12)' : undefined,
    }}>
      <Handle type="target" position={Position.Left} />
      <Space direction="vertical" size={4}>
        <Tag color={nodeColors[data.nodeType]}>
          {workflowNodeTypeLabel(t, data.nodeType)}
        </Tag>
        <Text strong>{data.label}</Text>
      </Space>
      {data.nodeType !== 'end' && (
        <Handle type="source" position={Position.Right} />
      )}
    </div>
  );
};

const nodeTypes = {workflowNode: WorkflowNodeCard};

const deepCopy = <T,>(value: T): T => JSON.parse(JSON.stringify(value)) as T;

const safeObject = (value: unknown): Record<string, unknown> | undefined => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  return value as Record<string, unknown>;
};

const parseObject = (value: string) => {
  try {
    return safeObject(JSON.parse(value));
  } catch {
    return undefined;
  }
};

const positionsOf = (document: WorkflowDesignerDocument) => Object.fromEntries(
  document.nodes.map((node) => [node.id, node.position]),
);

const loadPositions = (storageKey: string) => {
  try {
    const raw = globalThis.localStorage?.getItem(`${storageKey}.positions`);
    return raw ? JSON.parse(raw) as Record<string, {x: number; y: number}> : {};
  } catch {
    return {};
  }
};

type Props = {
  manifest: Record<string, unknown>;
  directory: DependencyOptionDirectory;
  storageKey: string;
  onChange: (manifest: Record<string, unknown>) => void;
};

const WorkflowDesignerContent = ({
  manifest,
  directory,
  storageKey,
  onChange,
}: Props) => {
  const {message} = App.useApp();
  const {t} = useI18n();
  const initial = useMemo(() => documentFromManifest(
    manifest,
    loadPositions(storageKey),
  ), []);
  const [history, setHistory] = useState<WorkflowDesignerDocument[]>([initial]);
  const [historyIndex, setHistoryIndex] = useState(0);
  const [selectedNodeId, setSelectedNodeId] = useState<string>();
  const [selectedEdgeId, setSelectedEdgeId] = useState<string>();
  const lastEmitted = useRef<string | undefined>(undefined);
  const document = history[historyIndex];
  const diagnostics = useMemo(() => validateWorkflowDocument(document), [document]);
  const invalidNodes = useMemo(() => new Set(
    diagnostics.map((item) => item.nodeId).filter(Boolean),
  ), [diagnostics]);

  useEffect(() => {
    const serialized = JSON.stringify(manifest);
    if (serialized === lastEmitted.current) {
      lastEmitted.current = undefined;
      return;
    }
    const next = documentFromManifest(manifest, loadPositions(storageKey));
    setHistory([next]);
    setHistoryIndex(0);
  }, [manifest, storageKey]);

  const commit = useCallback((next: WorkflowDesignerDocument) => {
    const immutable = deepCopy(next);
    setHistory((current) => [...current.slice(0, historyIndex + 1), immutable]);
    setHistoryIndex((current) => current + 1);
    const nextManifest = manifestFromDocument(immutable);
    lastEmitted.current = JSON.stringify(nextManifest);
    onChange(nextManifest);
    try {
      globalThis.localStorage?.setItem(
        `${storageKey}.positions`,
        JSON.stringify(positionsOf(immutable)),
      );
    } catch {
      // Storage is a convenience; the form remains the source of truth.
    }
  }, [historyIndex, onChange, storageKey]);

  const navigateHistory = (nextIndex: number) => {
    const next = history[nextIndex];
    if (!next) return;
    setHistoryIndex(nextIndex);
    const nextManifest = manifestFromDocument(next);
    lastEmitted.current = JSON.stringify(nextManifest);
    onChange(nextManifest);
  };

  const selectedNode = document.nodes.find((node) => node.id === selectedNodeId);
  const selectedEdge = document.edges.find((edge) => edge.id === selectedEdgeId);

  const updateNode = (node: WorkflowDesignerNode, previousId = node.id) => {
    const nodes = document.nodes.map((item) => item.id === previousId ? node : item);
    const edges = document.edges.map((edge) => ({
      ...edge,
      source: edge.source === previousId ? node.id : edge.source,
      target: edge.target === previousId ? node.id : edge.target,
    }));
    setSelectedNodeId(node.id);
    commit({...document, nodes, edges});
  };

  const updateNodeDefinition = (patch: Record<string, unknown>) => {
    if (!selectedNode) return;
    updateNode({
      ...selectedNode,
      definition: {...selectedNode.definition, ...patch},
    });
  };

  const addNode = (type: WorkflowDesignerNodeType) => {
    if (document.nodes.length >= 128) {
      message.warning(t('ai.workflows.designer.maximumNodes', '工作流最多允许 128 个节点'));
      return;
    }
    const node = newWorkflowNode(type, document.nodes, {
      humanTitle: t('ai.workflows.designer.defaults.humanTitle', '人工审核'),
    });
    commit({...document, nodes: [...document.nodes, node]});
    setSelectedNodeId(node.id);
    setSelectedEdgeId(undefined);
  };

  const removeNode = (nodeId: string) => {
    commit({
      ...document,
      nodes: document.nodes.filter((node) => node.id !== nodeId),
      edges: document.edges.filter((edge) =>
        edge.source !== nodeId && edge.target !== nodeId),
    });
    setSelectedNodeId(undefined);
  };

  const connect = (connection: Connection) => {
    if (!connection.source || !connection.target) return;
    if (connection.source === connection.target) {
      message.warning(t('ai.workflows.designer.selfEdge', '节点不能连接到自身'));
      return;
    }
    if (document.edges.some((edge) =>
      edge.source === connection.source && edge.target === connection.target)) {
      message.warning(t('ai.workflows.designer.duplicateEdge', '这条连线已经存在'));
      return;
    }
    if (hasPath(document.edges, connection.target, connection.source)) {
      message.warning(t('ai.workflows.designer.cycle', '工作流必须是无环图，不能形成回路'));
      return;
    }
    const edge: WorkflowDesignerEdge = {
      id: globalThis.crypto?.randomUUID?.()
        ?? `edge-${connection.source}-${connection.target}-${Date.now()}`,
      source: connection.source,
      target: connection.target,
    };
    commit({...document, edges: [...document.edges, edge]});
  };

  const flowNodes = useMemo<FlowNode<WorkflowNodeData>[]>(() =>
    document.nodes.map((node) => ({
      id: node.id,
      type: 'workflowNode',
      position: node.position,
      selected: node.id === selectedNodeId,
      data: {
        label: String(node.definition.name ?? node.definition.title ?? node.id),
        nodeType: node.type,
        invalid: invalidNodes.has(node.id),
      },
    })), [document.nodes, invalidNodes, selectedNodeId]);
  const flowEdges = useMemo<FlowEdge[]>(() => document.edges.map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    label: edge.condition ? t('ai.workflows.designer.conditionalEdge', '条件') : undefined,
    animated: Boolean(edge.condition),
  })), [document.edges, t]);

  const diagnosticText = (code: string) => ({
    'nodes.empty': t('ai.workflows.designer.diagnostic.empty', '至少添加一个节点'),
    'nodes.maximum': t('ai.workflows.designer.diagnostic.nodesMaximum', '节点数量超过 128'),
    'edges.maximum': t('ai.workflows.designer.diagnostic.edgesMaximum', '连线数量超过 512'),
    'node.id': t('ai.workflows.designer.diagnostic.nodeId', '节点 ID 格式不正确'),
    'node.duplicate': t('ai.workflows.designer.diagnostic.duplicateNode', '节点 ID 重复'),
    'node.agentDependency': t('ai.workflows.designer.diagnostic.agentDependency', 'Agent 节点未固定已发布版本'),
    'node.skillDependency': t('ai.workflows.designer.diagnostic.skillDependency', 'Skill 节点未固定已发布版本'),
    'node.compensationDependency': t(
      'ai.workflows.designer.diagnostic.compensationDependency',
      '补偿 Skill 未固定完整的已发布版本',
    ),
    'node.human': t('ai.workflows.designer.diagnostic.human', '人工节点缺少标题或超时'),
    'node.wait': t('ai.workflows.designer.diagnostic.wait', '等待节点缺少有效时长'),
    'node.condition': t('ai.workflows.designer.diagnostic.condition', '条件节点缺少结构化条件'),
    'edge.endpoint': t('ai.workflows.designer.diagnostic.endpoint', '连线引用了不存在的节点'),
    'edge.self': t('ai.workflows.designer.diagnostic.selfEdge', '连线不能指向自身'),
    'edge.duplicate': t('ai.workflows.designer.diagnostic.duplicateEdge', '连线重复'),
    'graph.cycle': t('ai.workflows.designer.diagnostic.cycle', '工作流中存在回路'),
  }[code] ?? code);
  const nodeTypeLabel = (type: WorkflowDesignerNodeType) => ({
    agent: t('ai.workflows.designer.node.agent', 'Agent'),
    skill: t('ai.workflows.designer.node.skill', 'Skill'),
    human: t('ai.workflows.designer.node.human', '人工任务'),
    wait: t('ai.workflows.designer.node.wait', '等待'),
    condition: t('ai.workflows.designer.node.condition', '条件'),
    parallel: t('ai.workflows.designer.node.parallel', '并行'),
    end: t('ai.workflows.designer.node.end', '结束'),
  }[type]);

  return (
    <Space direction="vertical" size={12} style={{width: '100%'}}>
      <Space wrap style={{justifyContent: 'space-between', width: '100%'}}>
        <Space wrap>
          <Button
            disabled={historyIndex === 0}
            onClick={() => navigateHistory(historyIndex - 1)}
          >
            {t('ai.workflows.designer.undo', '撤销')}
          </Button>
          <Button
            disabled={historyIndex >= history.length - 1}
            onClick={() => navigateHistory(historyIndex + 1)}
          >
            {t('ai.workflows.designer.redo', '重做')}
          </Button>
          <Button onClick={() => {
            commit({
              ...document,
              nodes: document.nodes.map((node, index) => ({
                ...node,
                position: {
                  x: 70 + (index % 4) * 230,
                  y: 70 + Math.floor(index / 4) * 150,
                },
              })),
            });
          }}>
            {t('ai.workflows.designer.autoLayout', '自动布局')}
          </Button>
        </Space>
        <Tag color={diagnostics.length ? 'error' : 'success'}>
          {diagnostics.length
            ? t('ai.workflows.designer.issueCount', '{count} 个问题', {count: diagnostics.length})
            : t('ai.workflows.designer.valid', '画布校验通过')}
        </Tag>
      </Space>
      {diagnostics.length > 0 && (
        <Alert
          showIcon
          type="error"
          message={t('ai.workflows.designer.fixIssues', '创建版本前请修复画布问题')}
          description={(
            <Space direction="vertical" size={2}>
              {diagnostics.slice(0, 8).map((item, index) => (
                <Button
                  key={`${item.code}-${item.nodeId ?? item.edgeId ?? index}`}
                  type="link"
                  size="small"
                  style={{padding: 0, height: 'auto'}}
                  onClick={() => {
                    setSelectedNodeId(item.nodeId);
                    setSelectedEdgeId(item.edgeId);
                  }}
                >
                  {item.nodeId ? `${item.nodeId}: ` : ''}{diagnosticText(item.code)}
                </Button>
              ))}
            </Space>
          )}
        />
      )}
      <div style={{
        display: 'grid',
        gridTemplateColumns: '180px minmax(360px, 1fr) 310px',
        gap: 12,
        minHeight: 620,
      }}>
        <Card size="small" title={t('ai.workflows.designer.palette', '节点面板')}>
          <Space direction="vertical" style={{width: '100%'}}>
            {(['agent', 'skill', 'human', 'wait', 'condition', 'parallel', 'end'] as const)
              .map((type) => (
                <Button key={type} block onClick={() => addNode(type)}>
                  {nodeTypeLabel(type)}
                </Button>
              ))}
          </Space>
        </Card>
        <Card size="small" styles={{body: {height: 620, padding: 0}}}>
          <ReactFlow
            nodes={flowNodes}
            edges={flowEdges}
            nodeTypes={nodeTypes}
            fitView
            deleteKeyCode={['Backspace', 'Delete']}
            onConnect={connect}
            onNodeClick={(_, node) => {
              setSelectedNodeId(node.id);
              setSelectedEdgeId(undefined);
            }}
            onEdgeClick={(_, edge) => {
              setSelectedEdgeId(edge.id);
              setSelectedNodeId(undefined);
            }}
            onPaneClick={() => {
              setSelectedNodeId(undefined);
              setSelectedEdgeId(undefined);
            }}
            onNodeDragStop={(_, flowNode) => {
              const node = document.nodes.find((item) => item.id === flowNode.id);
              if (node) updateNode({...node, position: flowNode.position});
            }}
            onNodesDelete={(nodes) => {
              const ids = new Set(nodes.map((node) => node.id));
              commit({
                ...document,
                nodes: document.nodes.filter((node) => !ids.has(node.id)),
                edges: document.edges.filter((edge) =>
                  !ids.has(edge.source) && !ids.has(edge.target)),
              });
              setSelectedNodeId(undefined);
            }}
            onEdgesDelete={(edges) => commit({
              ...document,
              edges: document.edges.filter((edge) =>
                !edges.some((deleted) => deleted.id === edge.id)),
            })}
          >
            <Background />
            <Controls />
            <MiniMap pannable zoomable />
          </ReactFlow>
        </Card>
        <Card size="small" title={t('ai.workflows.designer.inspector', '属性面板')}>
          {!selectedNode && !selectedEdge && (
            <Text type="secondary">
              {t('ai.workflows.designer.selectHint', '选择节点或连线后编辑属性')}
            </Text>
          )}
          {selectedNode && (
            <Form layout="vertical" size="small">
              <Form.Item label={t('ai.workflows.designer.field.id', '节点 ID')}>
                <Input
                  value={selectedNode.id}
                  status={!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(selectedNode.id)
                    ? 'error' : undefined}
                  onChange={(event) => {
                    const id = event.target.value;
                    updateNode({
                      ...selectedNode,
                      id,
                      definition: {...selectedNode.definition, id},
                    }, selectedNode.id);
                  }}
                />
              </Form.Item>
              <Form.Item label={t('ai.workflows.designer.field.name', '显示名称')}>
                <Input
                  value={String(selectedNode.definition.name ?? '')}
                  maxLength={128}
                  onChange={(event) => updateNodeDefinition({name: event.target.value})}
                />
              </Form.Item>
              {['agent', 'skill'].includes(selectedNode.type) && (
                <Form.Item label={selectedNode.type === 'agent'
                  ? t('ai.workflows.designer.field.agentVersion', '固定 Agent 版本')
                  : t('ai.workflows.designer.field.skillVersion', '固定 Skill 版本')}>
                  <DependencyOptionSelect
                    allowClear
                    directory={directory}
                    kind={selectedNode.type === 'agent' ? 'AGENT' : 'SKILL'}
                    value={typeof selectedNode.definition.versionId === 'string'
                      ? selectedNode.definition.versionId : undefined}
                    onChange={(versionId, option) => {
                      if (selectedNode.type === 'agent') {
                        updateNodeDefinition({
                          agentId: option?.resourceId,
                          versionId,
                        });
                        return;
                      }
                      updateNodeDefinition({
                        skillId: option?.resourceId,
                        versionId,
                      });
                    }}
                  />
                </Form.Item>
              )}
              {['agent', 'skill', 'parallel', 'end'].includes(selectedNode.type) && (
                <Form.Item label={t('ai.workflows.designer.field.inputReference', '输入引用（可选）')}>
                  <Input
                    placeholder={t(
                      'ai.workflows.designer.placeholder.inputReference',
                      'input / nodes.previous.output',
                    )}
                    value={safeObject(selectedNode.definition.input)?.$ref as string | undefined}
                    onChange={(event) => updateNodeDefinition(event.target.value.trim()
                      ? {input: {$ref: event.target.value.trim()}}
                      : {input: undefined})}
                  />
                </Form.Item>
              )}
              {selectedNode.type === 'human' && (
                <>
                  <Form.Item label={t('ai.workflows.designer.field.title', '任务标题')}>
                    <Input
                      value={String(selectedNode.definition.title ?? '')}
                      onChange={(event) => updateNodeDefinition({title: event.target.value})}
                    />
                  </Form.Item>
                  <Form.Item label={t('ai.workflows.designer.field.description', '任务说明')}>
                    <Input.TextArea
                      rows={3}
                      value={String(selectedNode.definition.description ?? '')}
                      onChange={(event) => updateNodeDefinition({description: event.target.value})}
                    />
                  </Form.Item>
                  <Form.Item label={t('ai.workflows.designer.field.timeout', '超时秒数')}>
                    <InputNumber
                      min={1}
                      max={604800}
                      value={Number(selectedNode.definition.timeoutSeconds ?? 3600)}
                      onChange={(value) => updateNodeDefinition({timeoutSeconds: value ?? 3600})}
                    />
                  </Form.Item>
                  <Form.Item label={t('ai.workflows.designer.field.timeoutAction', '超时动作')}>
                    <Select
                      value={String(selectedNode.definition.timeoutAction ?? 'FAIL')}
                      options={['FAIL', 'CONTINUE', 'CANCEL'].map((value) => ({value, label: value}))}
                      onChange={(value) => updateNodeDefinition({timeoutAction: value})}
                    />
                  </Form.Item>
                  <Form.Item label={t('ai.workflows.designer.field.inputSchema', '人工输入 Schema')}>
                    <JsonSchemaObjectEditor
                      value={JSON.stringify(selectedNode.definition.inputSchema ?? {
                        type: 'object', additionalProperties: true,
                      }, null, 2)}
                      onChange={(value) => {
                        const schema = parseObject(value);
                        if (schema) updateNodeDefinition({inputSchema: schema});
                      }}
                    />
                  </Form.Item>
                </>
              )}
              {selectedNode.type === 'wait' && (
                <Form.Item label={t('ai.workflows.designer.field.duration', '等待秒数')}>
                  <InputNumber
                    min={1}
                    max={604800}
                    value={Number(selectedNode.definition.durationSeconds ?? 60)}
                    onChange={(value) => updateNodeDefinition({durationSeconds: value ?? 60})}
                  />
                </Form.Item>
              )}
              {selectedNode.type === 'condition' && (
                <Form.Item label={t('ai.workflows.designer.field.condition', '结构化条件 JSON')}>
                  <Input.TextArea
                    rows={8}
                    key={`${selectedNode.id}-condition`}
                    defaultValue={JSON.stringify(selectedNode.definition.condition ?? {}, null, 2)}
                    onBlur={(event) => {
                      const condition = parseObject(event.target.value);
                      if (condition) {
                        updateNodeDefinition({condition});
                      } else {
                        message.error(t(
                          'ai.workflows.designer.invalidCondition',
                          '条件必须是有效的 JSON 对象',
                        ));
                      }
                    }}
                  />
                </Form.Item>
              )}
              {['agent', 'skill'].includes(selectedNode.type) && (
                <Form.Item label={t('ai.workflows.designer.field.compensation', '补偿 Skill（可选）')}>
                  <DependencyOptionSelect
                    allowClear
                    directory={directory}
                    kind="SKILL"
                    value={safeObject(selectedNode.definition.compensation)?.versionId as string | undefined}
                    onChange={(versionId, option) => {
                      updateNodeDefinition({
                        compensation: option && versionId
                          ? {skillId: option.resourceId, versionId}
                          : undefined,
                      });
                    }}
                  />
                </Form.Item>
              )}
              <Button danger block onClick={() => removeNode(selectedNode.id)}>
                {t('ai.workflows.designer.removeNode', '删除节点')}
              </Button>
            </Form>
          )}
          {selectedEdge && (
            <Form layout="vertical" size="small">
              <Form.Item label={t('ai.workflows.designer.field.edge', '连线')}>
                <Text code>{selectedEdge.source} → {selectedEdge.target}</Text>
              </Form.Item>
              <Form.Item label={t('ai.workflows.designer.field.edgeCondition', '连线条件 JSON（可选）')}>
                <Input.TextArea
                  rows={8}
                  key={`${selectedEdge.id}-condition`}
                  defaultValue={selectedEdge.condition
                    ? JSON.stringify(selectedEdge.condition, null, 2) : ''}
                  onBlur={(event) => {
                    const condition = event.target.value.trim()
                      ? parseObject(event.target.value) : undefined;
                    if (event.target.value.trim() && !condition) {
                      message.error(t(
                        'ai.workflows.designer.invalidCondition',
                        '条件必须是有效的 JSON 对象',
                      ));
                      return;
                    }
                    commit({
                      ...document,
                      edges: document.edges.map((edge) => edge.id === selectedEdge.id
                        ? {...edge, condition} : edge),
                    });
                  }}
                />
              </Form.Item>
              <Button danger block onClick={() => {
                commit({
                  ...document,
                  edges: document.edges.filter((edge) => edge.id !== selectedEdge.id),
                });
                setSelectedEdgeId(undefined);
              }}>
                {t('ai.workflows.designer.removeEdge', '删除连线')}
              </Button>
            </Form>
          )}
        </Card>
      </div>
    </Space>
  );
};

const WorkflowDesigner = (props: Props) => (
  <ReactFlowProvider>
    <WorkflowDesignerContent {...props} />
  </ReactFlowProvider>
);

export default WorkflowDesigner;
