import {useEffect} from 'react';
import {
  ReactFlow,
  Background,
  BackgroundVariant,
  Controls,
  MiniMap,
  Handle,
  Position,
  EdgeLabelRenderer,
  BaseEdge,
  getBezierPath,
  useNodesState,
  useEdgesState,
  useReactFlow,
  ReactFlowProvider,
  type Node,
  type Edge,
  type NodeProps,
  type EdgeProps,
  type NodeTypes,
  type EdgeTypes,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import {graphlib, layout as dagreLayout} from '@dagrejs/dagre';
import {Tag, Tooltip} from 'antd';
import {DeleteOutlined} from '@ant-design/icons';
import type {LineageGraph, LineageNode} from './types';
import {NODE_COLORS, NODE_TAG_COLORS} from './types';

// ─── Constants ────────────────────────────────────────────────────────────────

const NODE_W = 230;
const NODE_H = 88;

// ─── React Flow node / edge data shapes ──────────────────────────────────────

type LineageNodeData = {
  node: LineageNode;
  isRoot: boolean;
  onNavigate: (id: string) => void;
};

type LineageEdgeData = {
  edgeType: string;
  transformDescription?: string;
  onDelete: (id: string) => void;
};

type FlowNode = Node<LineageNodeData, 'lineage'>;
type FlowEdge = Edge<LineageEdgeData, 'deletable'>;

// ─── Dagre auto-layout ────────────────────────────────────────────────────────

function applyDagreLayout(nodes: FlowNode[], edges: FlowEdge[]): {nodes: FlowNode[]; edges: FlowEdge[]} {
  const g = new graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({rankdir: 'LR', nodesep: 60, ranksep: 110, marginx: 20, marginy: 20});

  nodes.forEach((n) => g.setNode(n.id, {width: NODE_W, height: NODE_H}));
  edges.forEach((e) => g.setEdge(e.source, e.target));
  dagreLayout(g);

  const layoutedNodes: FlowNode[] = nodes.map((n) => {
    const pos = g.node(n.id);
    return {
      ...n,
      position: {x: pos.x - NODE_W / 2, y: pos.y - NODE_H / 2},
      targetPosition: Position.Left,
      sourcePosition: Position.Right,
    };
  });

  return {nodes: layoutedNodes, edges};
}

// ─── Custom Node ─────────────────────────────────────────────────────────────

function LineageNodeCard({data}: NodeProps<FlowNode>) {
  const {node, isRoot, onNavigate} = data;
  const borderColor = NODE_COLORS[node.nodeType] ?? '#d9d9d9';

  return (
    <Tooltip
      title={node.description ?? `${node.schemaName ? node.schemaName + '.' : ''}${node.tableName}${node.columnName ? '.' + node.columnName : ''}`}
      placement="top"
    >
      <div
        onClick={() => onNavigate(node.id)}
        style={{
          width: NODE_W,
          minHeight: NODE_H,
          padding: '8px 12px',
          borderRadius: 8,
          border: `2px solid ${isRoot ? borderColor : '#e8e8e8'}`,
          background: isRoot ? `${borderColor}14` : '#fff',
          cursor: 'pointer',
          boxShadow: isRoot
            ? `0 0 0 3px ${borderColor}33, 0 2px 8px rgba(0,0,0,0.12)`
            : '0 2px 6px rgba(0,0,0,0.08)',
          boxSizing: 'border-box',
        }}
      >
        <Handle type="target" position={Position.Left} style={{background: '#bbb', width: 8, height: 8}} />

        <div style={{display: 'flex', alignItems: 'center', gap: 4, marginBottom: 5, flexWrap: 'wrap'}}>
          <Tag
            color={NODE_TAG_COLORS[node.nodeType] ?? 'default'}
            style={{margin: 0, fontSize: 10, lineHeight: '16px', padding: '0 5px'}}
          >
            {node.nodeType}
          </Tag>
          {isRoot && (
            <Tag color="blue" style={{margin: 0, fontSize: 10, lineHeight: '16px', padding: '0 5px'}}>
              ROOT
            </Tag>
          )}
        </div>

        <div
          style={{
            fontWeight: 600,
            fontSize: 13,
            color: '#1a1a1a',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
            marginBottom: 3,
          }}
          title={node.name}
        >
          {node.name}
        </div>

        <div
          style={{fontSize: 11, color: '#888', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap'}}
          title={`${node.catalogName ?? ''}  ${node.tableName}`}
        >
          {node.catalogName ? `${node.catalogName} · ` : ''}
          {node.tableName}
          {node.columnName ? ` · ${node.columnName}` : ''}
        </div>

        <Handle type="source" position={Position.Right} style={{background: '#bbb', width: 8, height: 8}} />
      </div>
    </Tooltip>
  );
}

// ─── Custom Edge with inline delete button ───────────────────────────────────

function DeletableEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  data,
}: EdgeProps<FlowEdge>) {
  const [edgePath, labelX, labelY] = getBezierPath({sourceX, sourceY, sourcePosition, targetX, targetY, targetPosition});

  return (
    <>
      <BaseEdge
        id={id}
        path={edgePath}
        style={{stroke: '#b0b0b0', strokeWidth: 1.5}}
        markerEnd="url(#arrow)"
      />
      <EdgeLabelRenderer>
        <div
          style={{
            position: 'absolute',
            transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`,
            pointerEvents: 'all',
            display: 'flex',
            alignItems: 'center',
            gap: 3,
            background: 'rgba(255,255,255,0.95)',
            padding: '1px 6px',
            borderRadius: 10,
            border: '1px solid #e0e0e0',
            fontSize: 10,
            color: '#555',
            boxShadow: '0 1px 3px rgba(0,0,0,0.08)',
            zIndex: 10,
          }}
          className="nodrag nopan"
        >
          <span>{data?.edgeType}</span>
          {data?.onDelete && (
            <DeleteOutlined
              style={{color: '#ff4d4f', cursor: 'pointer', fontSize: 9, marginLeft: 2}}
              onClick={(e) => {
                e.stopPropagation();
                data.onDelete(id);
              }}
            />
          )}
        </div>
      </EdgeLabelRenderer>
    </>
  );
}

// ─── Static node/edge type maps (defined outside component to avoid re-renders) ──

const NODE_TYPES: NodeTypes = {lineage: LineageNodeCard};
const EDGE_TYPES: EdgeTypes = {deletable: DeletableEdge};

// ─── Inner flow component (must be inside ReactFlowProvider) ─────────────────

type Props = {
  graph: LineageGraph;
  onNavigate: (nodeId: string) => void;
  onDeleteEdge: (edgeId: string) => void;
};

function LineageFlowInner({graph, onNavigate, onDeleteEdge}: Props) {
  const {fitView} = useReactFlow();
  const [nodes, setNodes, onNodesChange] = useNodesState<FlowNode>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<FlowEdge>([]);

  useEffect(() => {
    const rawNodes: FlowNode[] = graph.nodes.map((n) => ({
      id: n.id,
      type: 'lineage' as const,
      position: {x: 0, y: 0},
      data: {node: n, isRoot: n.id === graph.rootNodeId, onNavigate},
    }));

    const rawEdges: FlowEdge[] = graph.edges.map((e) => ({
      id: e.id,
      type: 'deletable' as const,
      source: e.sourceNodeId,
      target: e.targetNodeId,
      data: {edgeType: e.edgeType, transformDescription: e.transformDescription, onDelete: onDeleteEdge},
    }));

    const {nodes: ln, edges: le} = applyDagreLayout(rawNodes, rawEdges);
    setNodes(ln);
    setEdges(le);

    const timer = setTimeout(() => {
      void fitView({padding: 0.15, duration: 400});
    }, 50);
    return () => clearTimeout(timer);
  }, [graph, onNavigate, onDeleteEdge, setNodes, setEdges, fitView]);

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      onNodesChange={onNodesChange}
      onEdgesChange={onEdgesChange}
      nodeTypes={NODE_TYPES}
      edgeTypes={EDGE_TYPES}
      fitView
      fitViewOptions={{padding: 0.15}}
      minZoom={0.2}
      maxZoom={2}
      nodesDraggable
      nodesConnectable={false}
      elementsSelectable
      proOptions={{hideAttribution: true}}
    >
      <Background variant={BackgroundVariant.Dots} gap={20} size={1} color="#e0e0e0" />
      <Controls showInteractive={false} />
      <MiniMap
        nodeColor={(n) => {
          const nd = (n as FlowNode).data;
          return nd?.isRoot
            ? (NODE_COLORS[nd.node.nodeType] ?? '#1677ff')
            : '#e8e8e8';
        }}
        maskColor="rgba(240,240,240,0.6)"
        style={{borderRadius: 8}}
      />
    </ReactFlow>
  );
}

// ─── Public component (wraps with ReactFlowProvider) ─────────────────────────

export function LineageGraphView(props: Props) {
  return (
    <ReactFlowProvider>
      <LineageFlowInner {...props} />
    </ReactFlowProvider>
  );
}
