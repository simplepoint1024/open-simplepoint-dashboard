import type {SqlTemplateStructure} from './sqlConsoleUtils';
import type {MetadataPathSegment} from '../metadataTree';

export type JdbcDataSourceOption = {
  id: string;
  code?: string;
  name?: string;
  enabled?: boolean;
  driverName?: string;
  databaseProductName?: string;
};

export type SqlExplainResult = {
  catalogCode: string;
  policyCode: string;
  maxRows: number;
  timeoutMs: number;
  allowCrossSourceJoin: boolean;
  crossSourceJoin: boolean;
  dataSources: string[];
  planText: string;
  pushedSqls: string[];
  pushdownSummary?: string;
  schemaCacheHit: boolean;
  schemaAssemblyTimeMs: number;
  mountedDataSourceCount: number;
  pushedDownOperators?: string[];
  platformJoin?: boolean;
};

export type SqlColumn = {
  name: string;
  typeName?: string | null;
  jdbcType?: number | null;
};

export type SqlQueryResult = SqlExplainResult & {
  columns: SqlColumn[];
  rows: unknown[][];
  truncated: boolean;
  returnedRows: number;
  executionTimeMs: number;
};

export type SqlUpdateResult = {
  catalogCode: string;
  dataSourceCode: string;
  affectedRows: number;
  executionTimeMs: number;
  pushedSql?: string | null;
};

export type SqlExecuteResult = {
  type: 'QUERY' | 'DML' | 'DDL' | 'FLUSH_CACHE' | string;
  queryResult?: SqlQueryResult | null;
  updateResult?: SqlUpdateResult | null;
  message?: string | null;
};

export type SqlConsolePayload = {
  catalogCode?: string;
  sql: string;
  defaultSchema?: string;
  maxRows?: number;
  parameters?: unknown[];
};

export type BuildSqlConsolePayloadOptions = {
  allowGlobalFlush?: boolean;
  catalogCode?: string;
  executionSql?: string;
  ignoreParameters?: boolean;
  parameters?: unknown[];
};

export type MetadataTableStructure = SqlTemplateStructure & {
  tablePath?: MetadataPathSegment[];
  dialectCode?: string;
  dialectName?: string;
};

export type MetadataTableTarget = {
  catalogCode: string;
  path: string[];
};

export type ResultRow = {
  key: number;
  values: unknown[];
};

export type StatementExecutionStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED';

export type StatementExecutionRecord = {
  key: number;
  sequence: number;
  sql: string;
  status: StatementExecutionStatus;
  type?: SqlExecuteResult['type'];
  durationMs?: number;
  summary?: string;
  error?: string;
  result?: SqlExecuteResult;
};

export type ResultTabKey = 'analysis' | 'pushedSql' | 'plan' | 'result';
