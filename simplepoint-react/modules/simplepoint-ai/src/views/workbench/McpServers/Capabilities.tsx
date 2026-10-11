import TableActions from "@simplepoint/components/Table/Actions";
import api from "@/api";
import DataTable from "@simplepoint/components/DataTable";
import {useRemoteList} from '@simplepoint/components/DataTable/useRemoteList';
import SForm from "@simplepoint/components/SForm/Lazy";
import EntitySelect from "@simplepoint/components/EntitySelect";
import { readyServerFilters } from "./Runtime/runtimeQueries";
import {parseJsonObject} from '@simplepoint/components/JsonObjectEditor/model';
import type {SFormHandle} from '@simplepoint/components/SForm';
import type { RJSFSchema } from "@rjsf/utils";
import validator from "@rjsf/validator-ajv8";
import { resolveApiErrorMessage } from "@simplepoint/shared/api/client";
import { get, post, useData } from "@simplepoint/shared/api/methods";
import { useQueryScope } from "@simplepoint/shared/hooks/useQueryScope";
import {usePageRetention} from "@simplepoint/shared/page/hooks";
import {useOperationScope} from "./Runtime/useOperationScope";
import { useI18n } from "@simplepoint/shared/hooks/useI18n";
import { normalizePage, type RawPage } from "@simplepoint/shared/types/request";
import {
  Alert,
  App,
  Modal,
  Space,
  Spin,
  Switch,
  Tabs,
  Tag,
  Typography,
} from "antd";
import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { mcpCapabilityTypeLabel } from "./labels";

const { Paragraph, Text } = Typography;
const JsonObjectEditor = lazy(() => import('@simplepoint/components/JsonObjectEditor'));

type McpServer = {
  id: string;
  name?: string;
  code?: string;
  status?: string;
  enabled?: boolean;
};

type McpTool = {
  name: string;
  title?: string;
  description?: string;
  inputSchema?: Record<string, unknown>;
  outputSchema?: Record<string, unknown>;
};

type McpResource = {
  uri: string;
  name: string;
  title?: string;
  description?: string;
  mimeType?: string;
};

type McpResourceTemplate = {
  uriTemplate: string;
  name: string;
  title?: string;
  description?: string;
  mimeType?: string;
};

type McpPrompt = {
  name: string;
  title?: string;
  description?: string;
  arguments?: Array<{
    name: string;
    title?: string;
    description?: string;
    required?: boolean;
  }>;
};

type ToolResult = {
  content?: Array<Record<string, unknown>>;
  error?: boolean;
  structuredContent?: unknown;
  meta?: Record<string, unknown>;
};

type McpCapabilitySnapshotSummary = {
  id: string;
  protocolVersion: string;
  remoteServerName: string;
  remoteServerVersion: string;
  schemaHash: string;
  discoveredAt: string;
  active: boolean;
  toolCount: number;
  resourceCount: number;
  resourceTemplateCount: number;
  promptCount: number;
};

type McpCapabilitySnapshotDetails = McpCapabilitySnapshotSummary & {
  capabilities?: Record<string, unknown>;
  tools: McpTool[];
  resources: McpResource[];
  resourceTemplates: McpResourceTemplate[];
  prompts: McpPrompt[];
};

type SnapshotComparison = {
  current: McpCapabilitySnapshotDetails;
  previous?: McpCapabilitySnapshotDetails;
};

type McpCapabilityPermissions = {
  callTool: boolean;
  readResource: boolean;
  getPrompt: boolean;
  viewCapabilities: boolean;
};

type McpCapabilitiesProps = {
  initialServerId?: string;
};

const McpCapabilities = ({ initialServerId }: McpCapabilitiesProps = {}) => {
  const config = api["ai-workbench.mcp-servers"];
  const { t, ensure, locale } = useI18n();
  const { message } = App.useApp();
  const operation = useOperationScope();
  const [serverId, setServerId] = useState<string | undefined>(initialServerId);
  const [callingTool, setCallingTool] = useState<McpTool>();
  const [toolArguments, setToolArguments] = useState<Record<string, unknown>>(
    {},
  );
  const [advancedArguments, setAdvancedArguments] = useState(false);
  const [argumentError, setArgumentError] = useState('');
  const resourceFormRef = useRef<SFormHandle>(null);
  const [argumentsJson, setArgumentsJson] = useState("{}");
  const [result, setResult] = useState<ToolResult>();
  const [calling, setCalling] = useState(false);
  const [readingResource, setReadingResource] = useState<string>();
  const [resourceOpen, setResourceOpen] = useState(false);
  const [gettingPrompt, setGettingPrompt] = useState<McpPrompt>();
  const [capabilityResult, setCapabilityResult] = useState<unknown>();
  const [snapshotComparison, setSnapshotComparison] =
    useState<SnapshotComparison>();
  const [snapshotLoading, setSnapshotLoading] = useState(false);
  const snapshotRequest = useRef<AbortController | undefined>(undefined);
  const mutationLock = useRef(false);
  const permissionsQuery = useData([config.baseUrl, "workbench-permissions"], ({signal}) =>
    get<McpCapabilityPermissions>(config.baseUrl + "/workbench-permissions", {}, {signal}), {retry: false, staleTime: 30_000, gcTime: 120_000});
  const permissions = !permissionsQuery.error && permissionsQuery.data ? permissionsQuery.data : {
    callTool: false, readResource: false, getPrompt: false, viewCapabilities: false,
  };
  const directory = useData(['mcp-capability-directory', config.baseUrl], async ({signal}) => {
    const page = await get<RawPage<McpServer>>(config.baseUrl, {page: 0, size: 1, ...readyServerFilters}, {signal});
    return {firstId: normalizePage(page).content[0]?.id};
  }, {enabled: permissions.viewCapabilities, retry: false});
  const serverLoadError = directory.error || permissionsQuery.error ? resolveApiErrorMessage(directory.error || permissionsQuery.error,
    t('ai.mcp.tools.error.loadServers', 'MCP Server 列表加载失败')) : undefined;
  usePageRetention(false, calling || !!callingTool || resourceOpen || !!gettingPrompt || !!snapshotComparison);
  const capabilities = useData(['mcp-capabilities', config.baseUrl, serverId], async ({signal}) => {
    const [tools, resources, resourceTemplates, prompts] = await Promise.all([
      get<McpTool[]>(`${config.baseUrl}/${serverId}/tools`, undefined, {signal}),
      get<McpResource[]>(`${config.baseUrl}/${serverId}/resources`, undefined, {signal}),
      get<McpResourceTemplate[]>(`${config.baseUrl}/${serverId}/resource-templates`, undefined, {signal}),
      get<McpPrompt[]>(`${config.baseUrl}/${serverId}/prompts`, undefined, {signal}),
    ]);
    return {tools, resources, resourceTemplates, prompts};
  }, {enabled: Boolean(serverId) && permissions.viewCapabilities, retry: false});
  const history = useRemoteList<McpCapabilitySnapshotSummary>(`${config.baseUrl}/${encodeURIComponent(serverId ?? '')}/snapshots`,
    Boolean(serverId) && permissions.viewCapabilities && operation.selected);
  const tools = capabilities.data?.tools ?? [];
  const resources = capabilities.data?.resources ?? [];
  const resourceTemplates = capabilities.data?.resourceTemplates ?? [];
  const prompts = capabilities.data?.prompts ?? [];
  const loading = capabilities.isFetching;
  const capabilityLoadError = capabilities.error || history.error
    ? resolveApiErrorMessage(capabilities.error || history.error, t('ai.mcp.tools.error.load', 'MCP 能力快照加载失败'))
    : undefined;
  const loadCapabilities = () => {void capabilities.refetch(); history.retry();};
  const argumentSchema = useMemo<RJSFSchema>(() => callingTool?.inputSchema as RJSFSchema
    ?? {type: 'object', additionalProperties: false,
      properties: Object.fromEntries((gettingPrompt?.arguments ?? []).map(argument => [argument.name,
        {type: 'string', title: argument.title || argument.name, description: argument.description}])),
      required: (gettingPrompt?.arguments ?? []).filter(argument => argument.required).map(argument => argument.name)},
  [callingTool, gettingPrompt]);

  const switchArgumentsMode = (advanced: boolean) => {
    if (!operation.isSelected() || mutationLock.current) return;
    if (advanced) {setArgumentsJson(JSON.stringify(toolArguments, null, 2)); setArgumentError(''); setAdvancedArguments(true); return;}
    try {
      const parsed = parseJsonObject(argumentsJson);
      const errors = validator.validateFormData(parsed, argumentSchema).errors;
      if (errors.length) throw Error(errors[0].stack);
      setToolArguments(parsed); setAdvancedArguments(false); setArgumentError('');
    } catch (error) {setArgumentError((error as Error).message);}
  };

  useEffect(() => {
    void ensure(config.i18nNamespaces);
  }, [config.i18nNamespaces, ensure, locale]);

  useEffect(() => {
    if (initialServerId) setServerId(initialServerId);
  }, [initialServerId]);
  useEffect(() => {
    setServerId(current => current ?? directory.data?.firstId);
  }, [directory.data?.firstId]);
  useEffect(() => {
    history.reset();
    snapshotRequest.current?.abort();
    setSnapshotComparison(undefined); setSnapshotLoading(false);
    setCallingTool(undefined); setReadingResource(undefined); setGettingPrompt(undefined);
    setResourceOpen(false);
    setResult(undefined); setCapabilityResult(undefined);
    return () => snapshotRequest.current?.abort();
  }, [serverId]);

  const loadSnapshotDetails = useCallback(
    async (summary: McpCapabilitySnapshotSummary) => {
      if (!serverId || !operation.isSelected() || !operation.current() || snapshotLoading) return;
      snapshotRequest.current?.abort();
      const request = new AbortController();
      snapshotRequest.current = request;
      setSnapshotLoading(true);
      try {
        const [current, previous] = await Promise.all([
          get<McpCapabilitySnapshotDetails>(
            `${config.baseUrl}/${encodeURIComponent(serverId)}/snapshots/${encodeURIComponent(summary.id)}`,
            undefined, {signal: request.signal},
          ),
          get<McpCapabilitySnapshotDetails | null>(
                `${config.baseUrl}/${encodeURIComponent(serverId)}/snapshots/${encodeURIComponent(summary.id)}/previous`,
                undefined, {signal: request.signal},
              ),
        ]);
        if (operation.current(request.signal)) setSnapshotComparison({ current, previous: previous ?? undefined });
      } catch (error) {
        if (!operation.current(request.signal)) return;
        message.error(
          resolveApiErrorMessage(
            error,
            t("ai.mcp.snapshots.error.load", "能力快照详情加载失败"),
          ),
        );
      } finally {
        if (snapshotRequest.current === request && operation.current()) setSnapshotLoading(false);
      }
    },
    [config.baseUrl, message, serverId, snapshotLoading, operation.current, operation.isSelected, t],
  );

  const snapshotDiff = useMemo(() => {
    if (!snapshotComparison) return [];
    const { current, previous } = snapshotComparison;
    const groups = [
      {
        type: "TOOL",
        current: current.tools.map((item) => item.name),
        previous: previous?.tools.map((item) => item.name) ?? [],
      },
      {
        type: "RESOURCE",
        current: current.resources.map((item) => item.uri),
        previous: previous?.resources.map((item) => item.uri) ?? [],
      },
      {
        type: "RESOURCE_TEMPLATE",
        current: current.resourceTemplates.map((item) => item.uriTemplate),
        previous:
          previous?.resourceTemplates.map((item) => item.uriTemplate) ?? [],
      },
      {
        type: "PROMPT",
        current: current.prompts.map((item) => item.name),
        previous: previous?.prompts.map((item) => item.name) ?? [],
      },
    ];
    return groups.map((group) => {
      const currentNames = new Set(group.current);
      const previousNames = new Set(group.previous);
      return {
        type: group.type,
        added: group.current.filter((name) => !previousNames.has(name)),
        removed: group.previous.filter((name) => !currentNames.has(name)),
      };
    });
  }, [snapshotComparison]);

  const columns = useMemo(
    () => [
      {
        title: t("ai.mcp.tools.name", "工具名称"),
        dataIndex: "name",
        width: 240,
        render: (value: string) => <Text code>{value}</Text>,
      },
      {
        title: t("ai.mcp.tools.title", "标题"),
        dataIndex: "title",
        width: 200,
      },
      {
        title: t("ai.mcp.tools.description", "说明"),
        dataIndex: "description",
      },
      {
        title: t("ai.mcp.tools.schema", "输入 Schema"),
        dataIndex: "inputSchema",
        width: 140,
        render: (value: Record<string, unknown>) => (
          <Paragraph
            copyable={{ text: JSON.stringify(value ?? {}, null, 2) }}
            ellipsis={{ rows: 1, expandable: false }}
            style={{ marginBottom: 0 }}
          >
            {JSON.stringify(value ?? {})}
          </Paragraph>
        ),
      },
      ...(permissions.callTool
        ? [
            {
              title: t("ai.mcp.tools.action", "操作"),
              key: "action",
              width: 110,
              render: (_value: unknown, tool: McpTool) => (
                <TableActions
                  selectedKeys={[]}
                  selectedRows={[]}
                  buttons={[
                    {
                      key: "callTool",
                      sort: 10,
                      text: t("ai.mcp.tools.call", "调用"),
                      type: "link",
                      onClick: () => {
                        if (!operation.isSelected() || mutationLock.current) return;
                        setToolArguments({});
                        setArgumentsJson("{}");
                        setAdvancedArguments(false);
                        setArgumentError('');
                        setResult(undefined);
                        setCallingTool(tool);
                      },
                    },
                  ]}
                />
              ),
            },
          ]
        : []),
    ],
    [permissions.callTool, t],
  );

  const callTool = useCallback(async () => {
    if (!serverId || !callingTool || !permissions.callTool || mutationLock.current) return;
    let args = toolArguments;
    if (advancedArguments) {
      try {
        args = parseJsonObject(argumentsJson);
      } catch {
        message.error(
          t("ai.mcp.tools.error.arguments", "参数必须是有效的 JSON 对象"),
        );
        return;
      }
    }
    {
      const schema = (callingTool.inputSchema ?? {
        type: "object",
      }) as RJSFSchema;
      const validation = validator.validateFormData(args, schema);
      if (validation.errors.length > 0) {
        message.error(
          validation.errors[0]?.stack ||
            t("ai.mcp.tools.error.schema", "工具参数不符合输入 Schema"),
        );
        return;
      }
    }
    const request = operation.begin(); if (!request) return;
    mutationLock.current = true;
    setCalling(true);
    try {
      const response = await post<ToolResult>(`${config.baseUrl}/${serverId}/tools/call`, {
          toolName: callingTool.name,
          arguments: args,
        }, {signal: request.signal});
      if (operation.current(request.signal)) setResult(response);
    } catch (error) {
      if (!operation.current(request.signal)) return;
      message.error(
        resolveApiErrorMessage(
          error,
          t("ai.mcp.tools.error.call", "工具调用失败"),
        ),
      );
    } finally {
      mutationLock.current = false; operation.finish(request); if (operation.current()) setCalling(false);
    }
  }, [
    advancedArguments,
    argumentsJson,
    callingTool,
    config.baseUrl,
    message,
    serverId,
    t,
    toolArguments,
    permissions.callTool,
  ]);

  const readResource = useCallback(async () => {
    if (!serverId || !readingResource || !permissions.readResource || mutationLock.current) return;
    const request = operation.begin(); if (!request) return;
    mutationLock.current = true;
    setCalling(true);
    try {
      const response = await post(`${config.baseUrl}/${serverId}/resources/read`, {
          uri: readingResource,
        }, {signal: request.signal});
      if (operation.current(request.signal)) setCapabilityResult(response);
    } catch (error) {
      if (!operation.current(request.signal)) return;
      message.error(
        resolveApiErrorMessage(
          error,
          t("ai.mcp.resources.error.read", "Resource 读取失败"),
        ),
      );
    } finally {
      mutationLock.current = false; operation.finish(request); if (operation.current()) setCalling(false);
    }
  }, [config.baseUrl, message, readingResource, serverId, t, permissions.readResource]);

  const getPrompt = useCallback(async () => {
    if (!serverId || !gettingPrompt || !permissions.getPrompt || mutationLock.current) return;
    let args: Record<string, unknown>;
    try {
      args = advancedArguments ? parseJsonObject(argumentsJson) : toolArguments;
      const errors = validator.validateFormData(args, argumentSchema).errors;
      if (errors.length) throw Error(errors[0].stack);
    } catch {
      message.error(
        t("ai.mcp.tools.error.arguments", "参数必须是有效的 JSON 对象"),
      );
      return;
    }
    const request = operation.begin(); if (!request) return;
    mutationLock.current = true;
    setCalling(true);
    try {
      const response = await post(`${config.baseUrl}/${serverId}/prompts/get`, {
          name: gettingPrompt.name,
          arguments: args,
        }, {signal: request.signal});
      if (operation.current(request.signal)) setCapabilityResult(response);
    } catch (error) {
      if (!operation.current(request.signal)) return;
      message.error(
        resolveApiErrorMessage(
          error,
          t("ai.mcp.prompts.error.get", "Prompt 获取失败"),
        ),
      );
    } finally {
      mutationLock.current = false; operation.finish(request); if (operation.current()) setCalling(false);
    }
  }, [argumentsJson, advancedArguments, toolArguments, argumentSchema, config.baseUrl, gettingPrompt, message, serverId, t, permissions.getPrompt]);

  const resourceColumns = useMemo(
    () => [
      {
        title: t("ai.mcp.resources.uri", "Resource URI"),
        dataIndex: "uri",
        render: (value: string) => <Text code>{value}</Text>,
      },
      {
        title: t("ai.mcp.resources.name", "名称"),
        dataIndex: "name",
        width: 180,
      },
      {
        title: t("ai.mcp.tools.description", "说明"),
        dataIndex: "description",
      },
      {
        title: t("ai.mcp.common.mime", "MIME"),
        dataIndex: "mimeType",
        width: 160,
      },
      ...(permissions.readResource
        ? [
            {
              title: t("ai.mcp.tools.action", "操作"),
              key: "action",
              width: 100,
              render: (_value: unknown, resource: McpResource) => (
                <TableActions
                  selectedKeys={[]}
                  selectedRows={[]}
                  buttons={[
                    {
                      key: "readResource",
                      sort: 20,
                      text: t("ai.mcp.resources.read", "读取"),
                      type: "link",
                      onClick: () => {
                        if (!operation.isSelected() || mutationLock.current) return;
                        setCapabilityResult(undefined);
                        setReadingResource(resource.uri);
                        setResourceOpen(true);
                      },
                    },
                  ]}
                />
              ),
            },
          ]
        : []),
    ],
    [permissions.readResource, t],
  );

  const promptColumns = useMemo(
    () => [
      {
        title: t("ai.mcp.prompts.name", "Prompt 名称"),
        dataIndex: "name",
        width: 240,
        render: (value: string) => <Text code>{value}</Text>,
      },
      {
        title: t("ai.mcp.tools.title", "标题"),
        dataIndex: "title",
        width: 180,
      },
      {
        title: t("ai.mcp.tools.description", "说明"),
        dataIndex: "description",
      },
      {
        title: t("ai.mcp.prompts.arguments", "参数"),
        dataIndex: "arguments",
        width: 220,
        render: (value: McpPrompt["arguments"]) => (
          <Text>
            {(value ?? [])
              .map(
                (argument) => `${argument.name}${argument.required ? "*" : ""}`,
              )
              .join(", ") || "-"}
          </Text>
        ),
      },
      ...(permissions.getPrompt
        ? [
            {
              title: t("ai.mcp.tools.action", "操作"),
              key: "action",
              width: 100,
              render: (_value: unknown, prompt: McpPrompt) => (
                <TableActions
                  selectedKeys={[]}
                  selectedRows={[]}
                  buttons={[
                    {
                      key: "getPrompt",
                      sort: 30,
                      text: t("ai.mcp.prompts.get", "获取"),
                      type: "link",
                      onClick: () => {
                        if (!operation.isSelected() || mutationLock.current) return;
                        setArgumentsJson("{}");
                        setToolArguments({}); setAdvancedArguments(false); setArgumentError('');
                        setCapabilityResult(undefined);
                        setGettingPrompt(prompt);
                      },
                    },
                  ]}
                />
              ),
            },
          ]
        : []),
    ],
    [permissions.getPrompt, t],
  );

  const argumentsEditor = <div style={{display: 'grid', gap: 12}}>
    <Space>
      <Text>{t('ai.mcp.tools.arguments', '调用参数')}</Text>
      <Switch disabled={calling} size="small" checked={advancedArguments} onChange={switchArgumentsMode}/>
      <Text type="secondary">{t('ai.mcp.tools.arguments.advanced', '高级 JSON')}</Text>
    </Space>
    {argumentError && <Alert type="warning" showIcon title={argumentError}/>}
    {advancedArguments ? <Suspense fallback={<Spin size="small"/>}>
      <JsonObjectEditor disabled={calling} value={argumentsJson}
        onChange={next => {setArgumentsJson(next); setArgumentError('');}}/>
    </Suspense> : <SForm disabled={calling} schema={argumentSchema} formData={toolArguments}
      hideSubmit liveValidate onChange={event => setToolArguments(event.formData ?? {})}/>}
  </div>;

  return (
    <>
      <div style={{height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column'}}>
          <Space style={{marginBottom: 16, flexShrink: 0}}>
            <div style={{width: 320}}><EntitySelect
              endpoint={config.baseUrl}
              filters={readyServerFilters}
              disabled={calling}
              allowClear={false}
              placeholder={t(
                "ai.mcp.tools.selectServer",
                "选择 READY MCP Server",
              )}
              value={serverId}
              onChange={setServerId}
            /></div>
            <TableActions
              selectedKeys={[]}
              selectedRows={[]}
              buttons={[
                {
                  key: "refreshCapabilities",
                  sort: 40,
                  text: t("ai.mcp.action.refresh", "刷新"),
                  disabled: !serverId || !permissions.viewCapabilities || calling,
                  loading,
                  onClick: () => void loadCapabilities(),
                },
              ]}
            />
          </Space>
        {serverLoadError && (
          <Alert
            showIcon
            type="error"
            message={t(
              "ai.mcp.tools.error.loadServers",
              "MCP Server 列表加载失败",
            )}
            description={serverLoadError}
            action={
              <TableActions
                selectedKeys={[]}
                selectedRows={[]}
                buttons={[
                  {
                    key: "retryDirectory",
                    sort: 50,
                    text: t("ai.mcp.action.retry", "重试"),
                    size: "small",
                    onClick: () => {void permissionsQuery.refetch(); void directory.refetch();},
                  },
                ]}
              />
            }
            style={{ marginBottom: 16 }}
          />
        )}
        {!serverLoadError && !serverId && (
          <Alert
            showIcon
            type="info"
            closable
            message={t(
              "ai.mcp.tools.emptyServer",
              "请先注册并发现一个 READY MCP Server",
            )}
            style={{ marginBottom: 16 }}
          />
        )}
        {capabilityLoadError && (
          <Alert
            showIcon
            type="error"
            message={t("ai.mcp.tools.error.load", "MCP 能力快照加载失败")}
            description={capabilityLoadError}
            action={
              <TableActions
                selectedKeys={[]}
                selectedRows={[]}
                buttons={[
                  {
                    key: "retryCapabilities",
                    sort: 60,
                    text: t("ai.mcp.action.retry", "重试"),
                    size: "small",
                    onClick: () => void loadCapabilities(),
                  },
                ]}
              />
            }
            style={{ marginBottom: 16 }}
          />
        )}
        {serverId &&
          !permissions.callTool &&
          !permissions.readResource &&
          !permissions.getPrompt && (
            <Alert
              showIcon
              type="info"
              closable
              message={t("ai.mcp.tools.readOnly", "当前以只读方式浏览能力")}
              description={t(
                "ai.mcp.tools.readOnly.description",
                "如需调用 Tool、读取 Resource 或获取 Prompt，请联系管理员授予相应操作权限。",
              )}
              style={{ marginBottom: 16 }}
            />
          )}
        <Tabs
          style={{flex: 1, minHeight: 0, overflow: 'auto'}}
          items={[
            {
              key: "tools",
              label: `${t("ai.mcp.capabilities.tools", "Tools")} (${tools.length})`,
              children: (
                <DataTable
                  rowKey="name"
                  loading={loading}
                  columns={columns}
                  storageKey="ai.mcp.capabilities.tools"
                  query={{
                    mode: "local",
                    scope: "result",
                    contextKey: serverId,
                    fields: {
                      name: { type: "string" },
                      title: { type: "string" },
                      description: { type: "string" },
                    },
                  }}
                  dataSource={tools}
                  pagination={{ pageSize: 20, hideOnSinglePage: true }}
                />
              ),
            },
            {
              key: "resources",
              label: `${t("ai.mcp.capabilities.resources", "Resources")} (${resources.length})`,
              children: (
                <DataTable
                  rowKey="uri"
                  storageKey="ai.mcp.capabilities.resources"
                  query={{
                    mode: "local",
                    scope: "result",
                    contextKey: serverId,
                    fields: {
                      uri: { type: "string" },
                      name: { type: "string" },
                      description: { type: "string" },
                      mimeType: { type: "string" },
                    },
                  }}
                  loading={loading}
                  columns={resourceColumns}
                  dataSource={resources}
                  pagination={{ pageSize: 20, hideOnSinglePage: true }}
                />
              ),
            },
            {
              key: "resource-templates",
              label: `${t("ai.mcp.capabilities.templates", "Templates")} (${resourceTemplates.length})`,
              children: (
                <DataTable
                  rowKey="uriTemplate"
                  storageKey="ai.mcp.capabilities.templates"
                  query={{
                    mode: "local",
                    scope: "result",
                    contextKey: serverId,
                    fields: {
                      uriTemplate: { type: "string" },
                      name: { type: "string" },
                      description: { type: "string" },
                      mimeType: { type: "string" },
                    },
                  }}
                  loading={loading}
                  dataSource={resourceTemplates}
                  pagination={{ pageSize: 20, hideOnSinglePage: true }}
                  columns={[
                    {
                      title: t("ai.mcp.resources.template", "URI Template"),
                      dataIndex: "uriTemplate",
                      render: (value: string) => <Text code>{value}</Text>,
                    },
                    {
                      title: t("ai.mcp.resources.name", "名称"),
                      dataIndex: "name",
                      width: 180,
                    },
                    {
                      title: t("ai.mcp.tools.description", "说明"),
                      dataIndex: "description",
                    },
                    {
                      title: t("ai.mcp.common.mime", "MIME"),
                      dataIndex: "mimeType",
                      width: 160,
                    },
                  ]}
                />
              ),
            },
            {
              key: "prompts",
              label: `${t("ai.mcp.capabilities.prompts", "Prompts")} (${prompts.length})`,
              children: (
                <DataTable
                  rowKey="name"
                  loading={loading}
                  columns={promptColumns}
                  storageKey="ai.mcp.capabilities.prompts"
                  query={{
                    mode: "local",
                    scope: "result",
                    contextKey: serverId,
                    fields: {
                      name: { type: "string" },
                      title: { type: "string" },
                      description: { type: "string" },
                    },
                  }}
                  dataSource={prompts}
                  pagination={{ pageSize: 20, hideOnSinglePage: true }}
                />
              ),
            },
            {
              key: "snapshots",
              label: `${t("ai.mcp.snapshots.title", "发现历史")} (${history.page.page.totalElements})`,
              children: (
                <DataTable
                  {...history.tableProps}
                  rowKey="id"
                  loading={!!history.tableProps.loading || snapshotLoading}
                  storageKey="ai.mcp.capabilities.snapshots"
                  columns={history.columns([
                    {
                      title: t("ai.mcp.snapshots.discoveredAt", "发现时间"),
                      dataIndex: "discoveredAt",
                      width: 190,
                      render: (value: string) =>
                        new Date(value).toLocaleString(),
                    },
                    {
                      title: t("ai.mcp.snapshots.remoteServerName", "服务名称"),
                      dataIndex: "remoteServerName", width: 160,
                    },
                    {title: t("ai.mcp.snapshots.remoteServerVersion", "服务版本"), dataIndex: "remoteServerVersion", width: 120},
                    {
                      title: t("ai.mcp.gateway.protocol", "协议版本"),
                      dataIndex: "protocolVersion",
                      width: 150,
                    },
                    {
                      title: t("ai.mcp.snapshots.capabilityCounts", "能力数量"),
                      key: "counts",
                      render: (
                        _value: unknown,
                        snapshot: McpCapabilitySnapshotSummary,
                      ) => (
                        <Space wrap>
                          <Tag>
                            {t(
                              "ai.mcp.capabilities.toolsCount",
                              "Tools（{count}）",
                              {
                                count: snapshot.toolCount,
                              },
                            )}
                          </Tag>
                          <Tag>
                            {t(
                              "ai.mcp.capabilities.resourcesCount",
                              "Resources（{count}）",
                              {
                                count: snapshot.resourceCount,
                              },
                            )}
                          </Tag>
                          <Tag>
                            {t(
                              "ai.mcp.capabilities.templatesCount",
                              "资源模板（{count}）",
                              {
                                count: snapshot.resourceTemplateCount,
                              },
                            )}
                          </Tag>
                          <Tag>
                            {t(
                              "ai.mcp.capabilities.promptsCount",
                              "Prompts（{count}）",
                              {
                                count: snapshot.promptCount,
                              },
                            )}
                          </Tag>
                        </Space>
                      ),
                    },
                    {
                      title: t("ai.mcp.snapshots.schemaHash", "Schema Hash"),
                      dataIndex: "schemaHash",
                      width: 190,
                      ellipsis: true,
                      render: (value: string) => (
                        <Text code copyable={{ text: value }}>
                          {value.slice(0, 16)}…
                        </Text>
                      ),
                    },
                    {
                      title: t("ai.mcp.snapshots.active", "生效状态"),
                      dataIndex: "active",
                      width: 100,
                      render: (value: boolean) =>
                        value ? (
                          <Tag color="green">
                            {t("ai.mcp.snapshots.current", "当前生效")}
                          </Tag>
                        ) : (
                          <Tag>{t("ai.mcp.snapshots.history", "历史")}</Tag>
                        ),
                    },
                    {
                      title: t("ai.mcp.tools.action", "操作"),
                      key: "action",
                      width: 110,
                      render: (
                        _value: unknown,
                        snapshot: McpCapabilitySnapshotSummary,
                      ) => (
                        <TableActions
                          selectedKeys={[]}
                          selectedRows={[]}
                          buttons={[
                            {
                              key: "compareSnapshot",
                              sort: 70,
                              text: t("ai.mcp.snapshots.compare", "查看差异"),
                              type: "link",
                              disabled: snapshotLoading || !!history.error,
                              onClick: () => void loadSnapshotDetails(snapshot),
                            },
                          ]}
                        />
                      ),
                    },
                  ])}
                />
              ),
            },
          ]}
        />
      </div>
      <Modal
        wrapClassName="mcp-capability-dialog mcp-tool-dialog"
        open={Boolean(callingTool) && operation.selected}
        destroyOnHidden={!callingTool}
        title={`${t("ai.mcp.tools.call", "调用")} · ${callingTool?.name || ""}`}
        okText={t("ai.mcp.tools.call", "调用")}
        confirmLoading={calling}
        width={760}
        onOk={() => void callTool()}
        closable={!calling} maskClosable={!calling} keyboard={!calling}
        cancelButtonProps={{disabled: calling}}
        onCancel={() => {if (!mutationLock.current) setCallingTool(undefined);}}
      >
        <div style={{display: 'grid', gap: 16}}>
          {argumentsEditor}
          {result && (
            <div><Text>{t("ai.mcp.tools.result", "调用结果")}</Text>
              <Tag color={result.error ? "red" : "green"}>
                {result.error
                  ? t("ai.mcp.tools.result.error", "调用失败")
                  : t("ai.mcp.tools.result.success", "调用成功")}
              </Tag>
              <Paragraph>
                <pre
                  style={{
                    whiteSpace: "pre-wrap",
                    maxHeight: 320,
                    overflow: "auto",
                  }}
                >
                  {JSON.stringify(result, null, 2)}
                </pre>
              </Paragraph>
            </div>
          )}
        </div>
      </Modal>
      <Modal
        wrapClassName="mcp-capability-dialog mcp-resource-dialog"
        open={resourceOpen && operation.selected}
        destroyOnHidden={!resourceOpen}
        title={t("ai.mcp.resources.read", "读取 Resource")}
        okText={t("ai.mcp.resources.read", "读取")}
        confirmLoading={calling}
        width={760}
        onOk={() => resourceFormRef.current?.submit()}
        closable={!calling} maskClosable={!calling} keyboard={!calling}
        cancelButtonProps={{disabled: calling}}
        onCancel={() => {if (!mutationLock.current) {setResourceOpen(false); setReadingResource(undefined);}}}
      >
        <SForm ref={resourceFormRef} hideSubmit disabled={calling}
          schema={{type: 'object', required: ['uri'], properties: {
            uri: {type: 'string', title: t('ai.mcp.resources.uri', 'Resource URI'), minLength: 1, pattern: '\\S'},
          }}} formData={{uri: readingResource}}
          onChange={event => setReadingResource(event.formData?.uri ?? '')}
          onSubmit={() => void readResource()}/>
          {capabilityResult !== undefined && (
            <Paragraph>
              <pre
                style={{
                  whiteSpace: "pre-wrap",
                  maxHeight: 360,
                  overflow: "auto",
                }}
              >
                {JSON.stringify(capabilityResult, null, 2)}
              </pre>
            </Paragraph>
          )}
      </Modal>
      <Modal
        wrapClassName="mcp-capability-dialog mcp-prompt-dialog"
        open={Boolean(gettingPrompt) && operation.selected}
        destroyOnHidden={!gettingPrompt}
        title={`${t("ai.mcp.prompts.get", "获取 Prompt")} · ${gettingPrompt?.name || ""}`}
        okText={t("ai.mcp.prompts.get", "获取")}
        confirmLoading={calling}
        width={760}
        onOk={() => void getPrompt()}
        closable={!calling} maskClosable={!calling} keyboard={!calling}
        cancelButtonProps={{disabled: calling}}
        onCancel={() => {if (!mutationLock.current) setGettingPrompt(undefined);}}
      >
        <div style={{display: 'grid', gap: 16}}>
          {argumentsEditor}
          {capabilityResult !== undefined && (
            <Paragraph>
              <pre
                style={{
                  whiteSpace: "pre-wrap",
                  maxHeight: 360,
                  overflow: "auto",
                }}
              >
                {JSON.stringify(capabilityResult, null, 2)}
              </pre>
            </Paragraph>
          )}
        </div>
      </Modal>
      <Modal
        wrapClassName="mcp-capability-dialog mcp-snapshot-dialog"
        open={Boolean(snapshotComparison) && operation.selected}
        destroyOnHidden={!snapshotComparison}
        title={t("ai.mcp.snapshots.compareTitle", "能力快照与上次发现差异")}
        width={960}
        footer={null}
        onCancel={() => {snapshotRequest.current?.abort(); setSnapshotComparison(undefined);}}
      >
        {snapshotComparison && (
          <>
            <Alert
              showIcon
              type={snapshotComparison.previous ? "info" : "success"}
              style={{ marginBottom: 16 }}
              message={
                snapshotComparison.previous
                  ? `${new Date(snapshotComparison.current.discoveredAt).toLocaleString()} ↔ ${new Date(snapshotComparison.previous.discoveredAt).toLocaleString()}`
                  : t(
                      "ai.mcp.snapshots.first",
                      "这是该 MCP Server 的第一份能力快照",
                    )
              }
              description={
                snapshotComparison.current.schemaHash ===
                snapshotComparison.previous?.schemaHash
                  ? t("ai.mcp.snapshots.schemaUnchanged", "Schema Hash 未变化")
                  : t("ai.mcp.snapshots.schemaChanged", "Schema Hash 已变化")
              }
            />
            <DataTable
              size="small"
              rowKey="type"
              storageKey="ai.mcp.capabilities.diff"
              query={{
                mode: "local",
                scope: "complete",
                contextKey: snapshotComparison.current.id,
                fields: {
                  type: { type: "string" },
                },
              }}
              dataSource={snapshotDiff}
              pagination={false}
              columns={[
                {
                  title: t("ai.mcp.snapshots.capability", "能力类型"),
                  dataIndex: "type",
                  width: 140,
                  render: (value: string) => mcpCapabilityTypeLabel(t, value),
                },
                {
                  title: t("ai.mcp.snapshots.added", "新增"),
                  dataIndex: "added",
                  render: (values: string[]) =>
                    values.length ? (
                      <Space wrap>
                        {values.map((value) => (
                          <Tag color="green" key={value}>
                            {value}
                          </Tag>
                        ))}
                      </Space>
                    ) : (
                      "-"
                    ),
                },
                {
                  title: t("ai.mcp.snapshots.removed", "移除"),
                  dataIndex: "removed",
                  render: (values: string[]) =>
                    values.length ? (
                      <Space wrap>
                        {values.map((value) => (
                          <Tag color="red" key={value}>
                            {value}
                          </Tag>
                        ))}
                      </Space>
                    ) : (
                      "-"
                    ),
                },
              ]}
            />
            <div style={{ marginTop: 16 }}>
              <Text>{t("ai.mcp.snapshots.capabilities", "协商能力")}</Text>
                <Paragraph>
                  <pre
                    style={{
                      whiteSpace: "pre-wrap",
                      maxHeight: 260,
                      overflow: "auto",
                    }}
                  >
                    {JSON.stringify(
                      snapshotComparison.current.capabilities ?? {},
                      null,
                      2,
                    )}
                  </pre>
                </Paragraph>
            </div>
          </>
        )}
      </Modal>
    </>
  );
};

export default function ScopedMcpCapabilities(props: McpCapabilitiesProps) {
  const scope = useQueryScope();
  return <McpCapabilities key={JSON.stringify(scope)} {...props}/>;
}
