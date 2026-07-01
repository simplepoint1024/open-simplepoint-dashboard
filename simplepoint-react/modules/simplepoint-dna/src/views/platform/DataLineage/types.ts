export type DataSourceOption = {
  id: string;
  code?: string;
  name?: string;
  enabled?: boolean;
};

export type LineageNode = {
  id: string;
  name: string;
  catalogId: string;
  catalogName?: string;
  nodeType: string;
  schemaName?: string;
  tableName: string;
  columnName?: string;
  tags?: string;
  description?: string;
};

export type LineageEdge = {
  id: string;
  sourceNodeId: string;
  sourceNodeName?: string;
  targetNodeId: string;
  targetNodeName?: string;
  edgeType: string;
  transformDescription?: string;
};

export type LineageGraph = {
  nodes: LineageNode[];
  edges: LineageEdge[];
  rootNodeId: string;
};

export const NODE_TYPE_KEYS = ['TABLE', 'VIEW', 'COLUMN', 'ETL', 'STREAM', 'API', 'FILE'] as const;
export const EDGE_TYPE_KEYS = ['DIRECT', 'ETL', 'DERIVED', 'COPY', 'AGGREGATION', 'FILTER', 'JOIN'] as const;

export const NODE_COLORS: Record<string, string> = {
  TABLE: '#1677ff',
  VIEW: '#722ed1',
  COLUMN: '#13c2c2',
  ETL: '#fa8c16',
  STREAM: '#52c41a',
  API: '#2f54eb',
  FILE: '#d48806',
};

export const NODE_TAG_COLORS: Record<string, string> = {
  TABLE: 'blue',
  VIEW: 'purple',
  COLUMN: 'cyan',
  ETL: 'orange',
  STREAM: 'green',
  API: 'geekblue',
  FILE: 'gold',
};
