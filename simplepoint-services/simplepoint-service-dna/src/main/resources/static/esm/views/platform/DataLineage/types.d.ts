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
export declare const NODE_TYPE_KEYS: readonly ["TABLE", "VIEW", "COLUMN", "ETL", "STREAM", "API", "FILE"];
export declare const EDGE_TYPE_KEYS: readonly ["DIRECT", "ETL", "DERIVED", "COPY", "AGGREGATION", "FILTER", "JOIN"];
export declare const NODE_COLORS: Record<string, string>;
export declare const NODE_TAG_COLORS: Record<string, string>;
