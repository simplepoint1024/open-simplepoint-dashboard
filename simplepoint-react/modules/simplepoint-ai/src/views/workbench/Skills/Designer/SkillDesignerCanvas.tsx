import ELK from 'elkjs/lib/elk.bundled.js';
import {
  Background,
  Controls,
  MiniMap,
  ReactFlow,
  type Connection,
  type Edge as FlowEdge,
  type Node as FlowNode,
  type ReactFlowInstance,
} from '@xyflow/react';
import {App} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useCallback, useMemo, useState, type DragEvent} from 'react';
import {
  addDesignerNode,
  connectDesignerNodes,
  type DesignerConnectionErrorCode,
  moveNode,
  removeNode,
} from './document';
import SkillNodeCard, {type SkillNodeData} from './SkillNodeCard';
import type {SkillDebugNodeStatus, SkillDesignerDiagnostic, SkillDesignerDocument} from './types';
import {DESIGNER_DRAG_TYPE, INPUT_NODE_ID, OUTPUT_NODE_ID} from './types';

const nodeTypes = {skillNode: SkillNodeCard};
const elk = new ELK();
const NODE_WIDTH = 196;
const NODE_HEIGHT = 88;

type Props = {
  document: SkillDesignerDocument;
  diagnostics: SkillDesignerDiagnostic[];
  readOnly?: boolean;
  selectedNodeId?: string;
  debugNodeStatuses?: Record<string, SkillDebugNodeStatus>;
  onChange: (document: SkillDesignerDocument) => void;
  onSelect: (nodeId?: string) => void;
};

export const autoLayoutDocument = async (
  document: SkillDesignerDocument,
): Promise<SkillDesignerDocument> => {
  const graph = await elk.layout({
    id: 'root',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': 'RIGHT',
      'elk.spacing.nodeNode': '70',
      'elk.layered.spacing.nodeNodeBetweenLayers': '110',
      'elk.edgeRouting': 'ORTHOGONAL',
    },
    children: document.nodes.map((node) => ({id: node.id, width: NODE_WIDTH, height: NODE_HEIGHT})),
    edges: document.edges.map((edge) => ({
      id: edge.id,
      sources: [edge.sourceNodeId],
      targets: [edge.targetNodeId],
    })),
  });
  const positions = new Map(graph.children?.map((node) => [
    node.id,
    {x: node.x ?? 0, y: node.y ?? 0},
  ]));
  return {
    ...document,
    nodes: document.nodes.map((node) => ({
      ...node,
      position: positions.get(node.id) ?? node.position,
    })),
  };
};

const SkillDesignerCanvas = ({
  document,
  diagnostics,
  readOnly = false,
  selectedNodeId,
  debugNodeStatuses = {},
  onChange,
  onSelect,
}: Props) => {
  const {t} = useI18n();
  const {message} = App.useApp();
  const [instance, setInstance] = useState<ReactFlowInstance | undefined>();
  const connectionErrorMessages = useMemo<Record<DesignerConnectionErrorCode, string>>(() => ({
    endpointMissing: t('ai.skills.designer.connection.error.endpointMissing', '连接端点不存在，请刷新画布后重试'),
    selfConnection: t('ai.skills.designer.connection.error.selfConnection', '节点不能连接到自身'),
    outputAsSource: t('ai.skills.designer.connection.error.outputAsSource', 'Skill Output 不能作为连接起点'),
    inputAsTarget: t('ai.skills.designer.connection.error.inputAsTarget', 'Skill Input 不能作为连接终点'),
    directIoNotEmpty: t('ai.skills.designer.connection.error.directIoNotEmpty', '工作流已有节点，不能直接连接 Input 与 Output'),
    inputTopLevelOnly: t('ai.skills.designer.connection.error.inputTopLevelOnly', 'Skill Input 只能连接顶层工作流节点'),
    controlToOutput: t('ai.skills.designer.connection.error.controlToOutput', '控制节点的分支汇合由画布自动生成，不能直接连接到 Skill Output'),
    branchToOutput: t('ai.skills.designer.connection.error.branchToOutput', '分支节点会自动汇合到后继节点，不能跨分支连接 Skill Output'),
    invalidBranchPort: t('ai.skills.designer.connection.error.invalidBranchPort', '请选择控制节点的有效分支端口'),
    branchMismatch: t('ai.skills.designer.connection.error.branchMismatch', '控制节点的分支端口只能连接该分支内的节点'),
    crossSequence: t('ai.skills.designer.connection.error.crossSequence', '只能调整同一顶层序列或同一分支内的节点顺序'),
    sourceNotExecutable: t('ai.skills.designer.connection.error.sourceNotExecutable', '连接起点不在可执行序列中'),
  }), [t]);
  const invalidNodeIds = useMemo(() => new Set(
    diagnostics.filter((item) => item.severity === 'ERROR' && item.nodeId).map((item) => item.nodeId as string),
  ), [diagnostics]);
  const outputHandlesByNode = useMemo(() => {
    const result = new Map<string, Array<{id: string; label: string}>>();
    document.ports.forEach((port) => {
      if (port.direction !== 'OUTPUT') return;
      const handles = result.get(port.nodeId) ?? [];
      const owner = document.nodes.find((node) => node.id === port.nodeId);
      const label = owner?.type === 'CONDITION' && port.id === 'then'
        ? t('ai.skills.designer.condition.trueBranch', 'True 分支')
        : owner?.type === 'CONDITION' && port.id === 'else'
          ? t('ai.skills.designer.condition.falseBranch', 'False 分支')
          : port.label ?? port.id;
      handles.push({id: port.id, label});
      result.set(port.nodeId, handles);
    });
    return result;
  }, [document.nodes, document.ports, t]);

  const nodes = useMemo<FlowNode<SkillNodeData>[]>(() => document.nodes.map((node) => ({
    id: node.id,
    type: 'skillNode',
    deletable: !readOnly && node.id !== INPUT_NODE_ID && node.id !== OUTPUT_NODE_ID,
    position: node.position,
    selected: node.id === selectedNodeId,
    data: {
      label: node.type === 'INPUT'
        ? t('ai.skills.designer.node.input', 'Skill 输入')
        : node.type === 'OUTPUT'
          ? t('ai.skills.designer.node.output', 'Skill 输出')
          : String(node.configuration.tool ?? node.configuration.prompt ?? node.configuration.resource ?? node.id),
      nodeType: node.type,
      sequenceLabel: node.type === 'INPUT' || node.type === 'OUTPUT'
        ? undefined
        : node.parentNodeId
          ? `${node.branchId ?? t('ai.skills.designer.common.branch', '分支')} · #${node.order + 1}`
          : `#${node.order + 1}`,
      removable: !readOnly && node.id !== INPUT_NODE_ID && node.id !== OUTPUT_NODE_ID,
      invalid: invalidNodeIds.has(node.id),
      debugStatus: debugNodeStatuses[node.id],
      outputHandles: outputHandlesByNode.get(node.id) ?? [],
      onRemove: (nodeId) => {
        onChange(removeNode(document, nodeId));
        if (selectedNodeId === nodeId) onSelect(undefined);
      },
    },
  })), [debugNodeStatuses, document, invalidNodeIds, onChange, onSelect, outputHandlesByNode, readOnly, selectedNodeId, t]);

  const edges = useMemo<FlowEdge[]>(() => document.edges.map((edge) => ({
    id: edge.id,
    source: edge.sourceNodeId,
    sourceHandle: edge.sourceHandle,
    target: edge.targetNodeId,
    targetHandle: edge.targetHandle,
    animated: debugNodeStatuses[edge.sourceNodeId] === 'RUNNING',
    style: {strokeWidth: 2},
  })), [debugNodeStatuses, document.edges]);

  const onDrop = useCallback((event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    if (readOnly || !instance) return;
    const type = event.dataTransfer.getData(DESIGNER_DRAG_TYPE);
    if (!['TOOL', 'PROMPT', 'RESOURCE', 'CONDITION', 'PARALLEL'].includes(type)) return;
    const position = instance.screenToFlowPosition({x: event.clientX, y: event.clientY});
    const added = addDesignerNode(
      document,
      type as 'TOOL' | 'PROMPT' | 'RESOURCE' | 'CONDITION' | 'PARALLEL',
      position,
    );
    onChange(added.document);
    onSelect(added.nodeId);
  }, [document, instance, onChange, onSelect, readOnly]);

  const onConnect = useCallback((connection: Connection) => {
    if (readOnly) return;
    const result = connectDesignerNodes(
      document,
      connection.source,
      connection.sourceHandle,
      connection.target,
    );
    if (result.errorCode) {
      message.warning(connectionErrorMessages[result.errorCode]);
      return;
    }
    if (result.document) onChange(result.document);
  }, [connectionErrorMessages, document, message, onChange, readOnly]);

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      nodeTypes={nodeTypes}
      onInit={setInstance}
      onDrop={onDrop}
      onDragOver={(event) => {
        event.preventDefault();
        event.dataTransfer.dropEffect = 'move';
      }}
      onNodeClick={(_, node) => onSelect(node.id)}
      onPaneClick={() => onSelect(undefined)}
      onNodeDragStop={(_, node) => {
        if (!readOnly) onChange(moveNode(document, node.id, node.position));
      }}
      onNodesDelete={(deleted) => {
        if (readOnly || deleted.length === 0) return;
        const next = deleted.reduce(
          (current, node) => removeNode(current, node.id),
          document,
        );
        onChange(next);
        onSelect(undefined);
      }}
      onConnect={onConnect}
      nodesConnectable={!readOnly}
      nodesDraggable={!readOnly}
      deleteKeyCode={readOnly ? null : ['Backspace', 'Delete']}
      nodesFocusable
      edgesFocusable
      fitView
      minZoom={0.25}
      maxZoom={2}
      proOptions={{hideAttribution: true}}
    >
      <Background gap={20} size={1} />
      <MiniMap pannable zoomable />
      <Controls />
    </ReactFlow>
  );
};

export default SkillDesignerCanvas;
