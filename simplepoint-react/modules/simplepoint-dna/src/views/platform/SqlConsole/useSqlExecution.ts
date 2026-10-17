import api from '@/api';
import {App as AntApp} from 'antd';
import {useCallback, useRef, useState} from 'react';
import type {RefObject} from 'react';
import {post} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {assertConsoleResponse, sqlStatements} from './sqlConsoleUtils';
import {resolveErrorMessage} from '../shared';
import type {SqlEditorRef} from './SqlEditor';
import type {
  BuildSqlConsolePayloadOptions,
  ResultTabKey,
  SqlConsolePayload,
  SqlExecuteResult,
  SqlExplainResult,
  SqlQueryResult,
  SqlUpdateResult,
  StatementExecutionRecord,
} from './types';

const sqlConsoleConfig = api['platform.dna-federation-sql-console'];

const toExplainSnapshot = (result: SqlExplainResult | SqlQueryResult): SqlExplainResult => ({
  catalogCode: result.catalogCode,
  policyCode: result.policyCode,
  maxRows: result.maxRows,
  timeoutMs: result.timeoutMs,
  allowCrossSourceJoin: result.allowCrossSourceJoin,
  crossSourceJoin: result.crossSourceJoin,
  dataSources: result.dataSources ?? [],
  planText: result.planText,
  pushedSqls: result.pushedSqls ?? [],
  pushdownSummary: result.pushdownSummary,
  schemaCacheHit: result.schemaCacheHit ?? false,
  schemaAssemblyTimeMs: result.schemaAssemblyTimeMs ?? 0,
  mountedDataSourceCount: result.mountedDataSourceCount ?? (result.dataSources ?? []).length,
  pushedDownOperators: result.pushedDownOperators ?? [],
  platformJoin: result.platformJoin ?? false,
});

const isFlushCacheSql = (value: string) => /^\s*FLUSH\s+CACHE\s*;?\s*$/i.test(value);

type UseSqlExecutionOptions = {
  aliveRef: RefObject<boolean>;
  canExecute: boolean;
  canExplain: boolean;
  catalogCode?: string;
  continueOnError: boolean;
  defaultSchema: string;
  editorRef: RefObject<SqlEditorRef | null>;
  maxRows: number | null;
  onInvalidateMetadata: () => void;
  parametersText: string;
  sql: string;
};

export const useSqlExecution = ({
  aliveRef,
  canExecute,
  canExplain,
  catalogCode,
  continueOnError,
  defaultSchema,
  editorRef,
  maxRows,
  onInvalidateMetadata,
  parametersText,
  sql,
}: UseSqlExecutionOptions) => {
  const {t} = useI18n();
  const {message} = AntApp.useApp();
  const busyRef = useRef(false);
  const [loadingMode, setLoadingMode] = useState<'explain' | 'query' | null>(null);
  const [activeTabKey, setActiveTabKey] = useState<ResultTabKey>('result');
  const [explainResult, setExplainResult] = useState<SqlExplainResult | null>(null);
  const [queryResult, setQueryResult] = useState<SqlQueryResult | null>(null);
  const [updateResult, setUpdateResult] = useState<SqlUpdateResult | null>(null);
  const [executeType, setExecuteType] = useState<SqlExecuteResult['type'] | null>(null);
  const [executeMessage, setExecuteMessage] = useState<string | null>(null);
  const [statementExecutions, setStatementExecutions] = useState<StatementExecutionRecord[]>([]);
  const [selectedStatementKey, setSelectedStatementKey] = useState<number | null>(null);
  const [statementProgress, setStatementProgress] = useState<{current: number; total: number} | null>(null);
  const [executionError, setExecutionError] = useState<string | null>(null);
  const [resultRevision, setResultRevision] = useState(0);

  const buildPayload = useCallback((options?: BuildSqlConsolePayloadOptions): SqlConsolePayload | null => {
    const normalizedSql = (options?.executionSql ?? sql).trim();
    if (!normalizedSql) {
      message.warning(t('dna.federation.sqlConsole.page.warning.enterSql', '请输入要执行的 SQL'));
      return null;
    }
    const effectiveCatalogCode = options?.catalogCode ?? catalogCode;
    const globalFlush = options?.allowGlobalFlush === true && isFlushCacheSql(normalizedSql);
    if (!effectiveCatalogCode && !globalFlush) {
      message.warning(t('dna.federation.sqlConsole.page.warning.selectCatalog', '请选择数据源'));
      return null;
    }

    const payload: SqlConsolePayload = {sql: normalizedSql};
    if (effectiveCatalogCode) payload.catalogCode = effectiveCatalogCode;
    if (globalFlush) return payload;

    let parameters = options?.parameters;
    const normalizedParameters = options?.ignoreParameters || parameters !== undefined ? '' : parametersText.trim();
    if (normalizedParameters) {
      let parsed: unknown;
      try {
        parsed = JSON.parse(normalizedParameters);
      } catch {
        message.warning(t('dna.federation.sqlConsole.page.warning.invalidParametersJson', '参数必须是合法的 JSON 数组'));
        return null;
      }
      if (!Array.isArray(parsed)) {
        message.warning(t('dna.federation.sqlConsole.page.warning.parametersMustBeArray', '参数必须是 JSON 数组'));
        return null;
      }
      parameters = parsed;
    }

    const normalizedDefaultSchema = defaultSchema.trim();
    if (normalizedDefaultSchema) payload.defaultSchema = normalizedDefaultSchema;
    if (maxRows && maxRows > 0) payload.maxRows = maxRows;
    if (parameters) payload.parameters = parameters;
    return payload;
  }, [catalogCode, defaultSchema, maxRows, message, parametersText, sql, t]);

  const clearExecutionDetail = useCallback(() => {
    setExecutionError(null);
    setExplainResult(null);
    setQueryResult(null);
    setUpdateResult(null);
    setExecuteType(null);
    setExecuteMessage(null);
    setResultRevision(value => value + 1);
  }, []);

  const applyExecuteResult = useCallback((result: SqlExecuteResult) => {
    setExecutionError(null);
    setExecuteType(result.type);
    setExecuteMessage(result.message ?? null);
    setResultRevision(value => value + 1);
    if (result.type === 'QUERY' && result.queryResult) {
      setExplainResult(toExplainSnapshot(result.queryResult));
      setQueryResult(result.queryResult);
      setUpdateResult(null);
    } else if ((result.type === 'DML' || result.type === 'DDL') && result.updateResult) {
      if (result.type === 'DDL') onInvalidateMetadata();
      setExplainResult(null);
      setQueryResult(null);
      setUpdateResult(result.updateResult);
    } else if (result.type === 'FLUSH_CACHE') {
      onInvalidateMetadata();
      setExplainResult(null);
      setQueryResult(null);
      setUpdateResult(null);
    } else {
      throw new Error(t('dna.federation.sqlConsole.page.error.unsupportedExecuteResult', '不支持的执行结果类型'));
    }
    setActiveTabKey('result');
  }, [onInvalidateMetadata, t]);

  const selectStatementExecution = useCallback((record: StatementExecutionRecord) => {
    setSelectedStatementKey(record.key);
    if (record.result) {
      applyExecuteResult(record.result);
      return;
    }
    clearExecutionDetail();
    setExecutionError(record.error ?? null);
    setActiveTabKey('result');
  }, [applyExecuteResult, clearExecutionDetail]);

  const resolveStatementParameters = useCallback((statementCount: number): Array<unknown[] | undefined> | null => {
    const normalized = parametersText.trim();
    if (!normalized) return Array.from({length: statementCount}, () => undefined);
    let parsed: unknown;
    try {
      parsed = JSON.parse(normalized);
    } catch {
      message.warning(t('dna.federation.sqlConsole.page.warning.invalidParametersJson', '参数必须是合法的 JSON 数组'));
      return null;
    }
    if (!Array.isArray(parsed) || parsed.length !== statementCount || !parsed.every(Array.isArray)) {
      message.warning(t(
        'dna.federation.sqlConsole.page.warning.batchParameters',
        '执行多条 SQL 时，参数必须是与语句数量一致的二维 JSON 数组',
      ));
      return null;
    }
    return parsed as unknown[][];
  }, [message, parametersText, t]);

  const summarizeExecution = useCallback((result: SqlExecuteResult) => {
    if (result.type === 'QUERY' && result.queryResult) {
      return t('dna.federation.sqlConsole.page.batch.returnedRows', '返回 {rows} 行')
        .replace('{rows}', String(result.queryResult.returnedRows));
    }
    if ((result.type === 'DML' || result.type === 'DDL') && result.updateResult) {
      return t('dna.federation.sqlConsole.page.batch.affectedRows', '影响 {rows} 行')
        .replace('{rows}', String(result.updateResult.affectedRows));
    }
    return result.message ?? '-';
  }, [t]);

  const submit = useCallback(async (mode: 'explain' | 'query', options?: BuildSqlConsolePayloadOptions) => {
    if (busyRef.current || !(mode === 'explain' ? canExplain : canExecute)) return;
    const requestedSql = options?.executionSql ?? editorRef.current?.getExecutionSql() ?? '';
    const statements = sqlStatements(requestedSql);
    if (statements.length === 0) {
      message.warning(t('dna.federation.sqlConsole.page.warning.enterSql', '请输入要执行的 SQL'));
      return;
    }
    if (mode === 'explain' && statements.length > 1) {
      message.warning(t('dna.federation.sqlConsole.page.warning.explainSingle', '执行计划一次只能分析一条 SQL'));
      return;
    }

    const isBatch = mode === 'query' && statements.length > 1;
    const parameterSets = isBatch ? resolveStatementParameters(statements.length) : null;
    if (isBatch && !parameterSets) return;
    const firstPayload = buildPayload({
      ...options,
      allowGlobalFlush: mode === 'query',
      executionSql: statements[0],
      ...(isBatch ? {ignoreParameters: true, parameters: parameterSets?.[0]} : {}),
    });
    if (!firstPayload) return;

    busyRef.current = true;
    setLoadingMode(mode);
    clearExecutionDetail();
    if (!isBatch) {
      setStatementExecutions([]);
      setSelectedStatementKey(null);
    }
    try {
      if (mode === 'explain') {
        const result = await post<SqlExplainResult>(`${sqlConsoleConfig.baseUrl}/explain`, firstPayload);
        if (!aliveRef.current) return;
        assertConsoleResponse(result, t('dna.federation.sqlConsole.page.error.explain', '执行计划生成失败'));
        setExplainResult(result);
        setActiveTabKey('analysis');
        return;
      }

      if (!isBatch) {
        const result = await post<SqlExecuteResult>(`${sqlConsoleConfig.baseUrl}/execute`, firstPayload);
        if (!aliveRef.current) return;
        assertConsoleResponse(result, t('dna.federation.sqlConsole.page.error.query', 'SQL 执行失败'));
        applyExecuteResult(result);
        return;
      }

      let records: StatementExecutionRecord[] = statements.map((statement, index) => ({
        key: index + 1,
        sequence: index + 1,
        sql: statement,
        status: 'PENDING',
      }));
      setStatementExecutions(records);
      setSelectedStatementKey(records[0]?.key ?? null);
      setActiveTabKey('result');

      for (let index = 0; index < records.length; index += 1) {
        setStatementProgress({current: index + 1, total: records.length});
        records = records.map((record, recordIndex) => recordIndex === index
          ? {...record, status: 'RUNNING'}
          : record);
        setStatementExecutions(records);
        const startedAt = Date.now();
        try {
          const payload = index === 0 ? firstPayload : buildPayload({
            ...options,
            allowGlobalFlush: true,
            executionSql: statements[index],
            ignoreParameters: true,
            parameters: parameterSets?.[index],
          });
          if (!payload) throw new Error(t('dna.federation.sqlConsole.page.error.query', 'SQL 执行失败'));
          const result = await post<SqlExecuteResult>(`${sqlConsoleConfig.baseUrl}/execute`, payload);
          if (!aliveRef.current) return;
          assertConsoleResponse(result, t('dna.federation.sqlConsole.page.error.query', 'SQL 执行失败'));
          if (!(result.type === 'QUERY' && result.queryResult)
            && !((result.type === 'DML' || result.type === 'DDL') && result.updateResult)
            && result.type !== 'FLUSH_CACHE') {
            throw new Error(t('dna.federation.sqlConsole.page.error.unsupportedExecuteResult', '不支持的执行结果类型'));
          }
          if (result.type === 'DDL' || result.type === 'FLUSH_CACHE') onInvalidateMetadata();
          const durationMs = result.queryResult?.executionTimeMs
            ?? result.updateResult?.executionTimeMs
            ?? Date.now() - startedAt;
          records = records.map((record, recordIndex) => recordIndex === index ? {
            ...record,
            status: 'SUCCESS',
            type: result.type,
            durationMs,
            summary: summarizeExecution(result),
            result,
          } : record);
        } catch (error) {
          const detail = resolveErrorMessage(error, t('dna.federation.sqlConsole.page.error.query', 'SQL 执行失败'));
          records = records.map((record, recordIndex) => recordIndex === index ? {
            ...record,
            status: 'FAILED',
            durationMs: Date.now() - startedAt,
            summary: detail,
            error: detail,
          } : !continueOnError && recordIndex > index ? {...record, status: 'SKIPPED'} : record);
          setStatementExecutions(records);
          if (!continueOnError) break;
        }
        setStatementExecutions(records);
      }

      const failed = records.filter(record => record.status === 'FAILED').length;
      const skipped = records.filter(record => record.status === 'SKIPPED').length;
      const focus = records.find(record => record.status === 'FAILED')
        ?? [...records].reverse().find(record => record.status === 'SUCCESS');
      if (focus) selectStatementExecution(focus);
      const completion = t(
        'dna.federation.sqlConsole.page.batch.completed',
        '脚本执行完成：{success} 成功，{failed} 失败，{skipped} 跳过',
      )
        .replace('{success}', String(records.filter(record => record.status === 'SUCCESS').length))
        .replace('{failed}', String(failed))
        .replace('{skipped}', String(skipped));
      if (failed > 0) message.warning(completion);
      else message.success(completion);
    } catch (error) {
      if (!aliveRef.current) return;
      setExecutionError(resolveErrorMessage(
        error,
        mode === 'explain'
          ? t('dna.federation.sqlConsole.page.error.explain', '执行计划生成失败')
          : t('dna.federation.sqlConsole.page.error.query', 'SQL 执行失败'),
      ));
    } finally {
      busyRef.current = false;
      if (aliveRef.current) {
        setLoadingMode(null);
        setStatementProgress(null);
      }
    }
  }, [aliveRef, applyExecuteResult, buildPayload, canExecute, canExplain, clearExecutionDetail,
    continueOnError, editorRef, message, onInvalidateMetadata, resolveStatementParameters,
    selectStatementExecution, summarizeExecution, t]);

  return {
    activeTabKey,
    busyRef,
    executeMessage,
    executeType,
    executionError,
    explainResult,
    loadingMode,
    queryResult,
    resultRevision,
    selectedStatementKey,
    selectStatementExecution,
    setActiveTabKey,
    statementExecutions,
    statementProgress,
    submit,
    updateResult,
  };
};
