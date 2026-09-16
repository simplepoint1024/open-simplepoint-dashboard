import api from '@/api';
import DataTable from '@simplepoint/components/DataTable';
import SForm from '@simplepoint/components/SForm';
import type {RJSFSchema} from '@rjsf/utils';
import validator from '@rjsf/validator-ajv8';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {get, post} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {Page} from '@simplepoint/shared/types/request';
import {
  Alert,
  App,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Switch,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import {useCallback, useEffect, useMemo, useState} from 'react';
import {mcpCapabilityTypeLabel} from './labels';

const {Paragraph, Text} = Typography;
const {TextArea} = Input;

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

const McpCapabilities = ({initialServerId}: McpCapabilitiesProps = {}) => {
  const config = api['ai-workbench.mcp-servers'];
  const {t, ensure, locale} = useI18n();
  const {message} = App.useApp();
  const [servers, setServers] = useState<McpServer[]>([]);
  const [serverId, setServerId] = useState<string>();
  const [tools, setTools] = useState<McpTool[]>([]);
  const [resources, setResources] = useState<McpResource[]>([]);
  const [resourceTemplates, setResourceTemplates] = useState<McpResourceTemplate[]>([]);
  const [prompts, setPrompts] = useState<McpPrompt[]>([]);
  const [snapshots, setSnapshots] = useState<McpCapabilitySnapshotSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [callingTool, setCallingTool] = useState<McpTool>();
  const [toolArguments, setToolArguments] = useState<Record<string, unknown>>({});
  const [advancedArguments, setAdvancedArguments] = useState(false);
  const [argumentsJson, setArgumentsJson] = useState('{}');
  const [result, setResult] = useState<ToolResult>();
  const [calling, setCalling] = useState(false);
  const [readingResource, setReadingResource] = useState<string>();
  const [gettingPrompt, setGettingPrompt] = useState<McpPrompt>();
  const [capabilityResult, setCapabilityResult] = useState<unknown>();
  const [snapshotComparison, setSnapshotComparison] = useState<SnapshotComparison>();
  const [snapshotLoading, setSnapshotLoading] = useState(false);
  const [serverLoadError, setServerLoadError] = useState<string>();
  const [capabilityLoadError, setCapabilityLoadError] = useState<string>();
  const [permissions, setPermissions] = useState<McpCapabilityPermissions>({
    callTool: false,
    readResource: false,
    getPrompt: false,
    viewCapabilities: false,
  });

  useEffect(() => {
    void ensure(config.i18nNamespaces);
  }, [config.i18nNamespaces, ensure, locale]);

  const loadServers = useCallback(async () => {
    setServerLoadError(undefined);
    try {
      const [page, nextPermissions] = await Promise.all([
        get<Page<McpServer>>(config.baseUrl, {page: 0, size: 500}),
        get<McpCapabilityPermissions>(`${config.baseUrl}/workbench-permissions`),
      ]);
      const available = (page.content ?? []).filter((server) => (
        server.enabled !== false && server.status === 'READY'
      ));
      const preferred = initialServerId;
      setServers(available);
      setServerId((current) => current
        && available.some((server) => server.id === current)
        ? current
        : available.find((server) => server.id === preferred)?.id
        || available[0]?.id);
      setPermissions(nextPermissions);
    } catch (error) {
      setServers([]);
      setServerId(undefined);
      setServerLoadError(resolveApiErrorMessage(
        error,
        t('ai.mcp.tools.error.loadServers', 'MCP Server 列表加载失败'),
      ));
    }
  }, [config.baseUrl, initialServerId, t]);

  useEffect(() => {
    void loadServers();
  }, [loadServers]);

  useEffect(() => {
    if (initialServerId && servers.some((server) => server.id === initialServerId)) {
      setServerId(initialServerId);
    }
  }, [initialServerId, servers]);

  const loadCapabilities = useCallback(async () => {
    if (!serverId) {
      setTools([]);
      setResources([]);
      setResourceTemplates([]);
      setPrompts([]);
      setSnapshots([]);
      return;
    }
    setLoading(true);
    setCapabilityLoadError(undefined);
    try {
      const [
        nextTools,
        nextResources,
        nextTemplates,
        nextPrompts,
        snapshotPage,
      ] = await Promise.all([
        get<McpTool[]>(`${config.baseUrl}/${serverId}/tools`),
        get<McpResource[]>(`${config.baseUrl}/${serverId}/resources`),
        get<McpResourceTemplate[]>(`${config.baseUrl}/${serverId}/resource-templates`),
        get<McpPrompt[]>(`${config.baseUrl}/${serverId}/prompts`),
        get<Page<McpCapabilitySnapshotSummary>>(
          `${config.baseUrl}/${serverId}/snapshots`,
          {page: 0, size: 100},
        ),
      ]);
      setTools(nextTools);
      setResources(nextResources);
      setResourceTemplates(nextTemplates);
      setPrompts(nextPrompts);
      setSnapshots(snapshotPage.content ?? []);
    } catch (error) {
      setTools([]);
      setResources([]);
      setResourceTemplates([]);
      setPrompts([]);
      setSnapshots([]);
      setCapabilityLoadError(resolveApiErrorMessage(
        error,
        t('ai.mcp.tools.error.load', 'MCP 能力快照加载失败'),
      ));
    } finally {
      setLoading(false);
    }
  }, [config.baseUrl, serverId, t]);

  const loadSnapshotDetails = useCallback(async (
    summary: McpCapabilitySnapshotSummary,
  ) => {
    if (!serverId) return;
    setSnapshotLoading(true);
    try {
      const index = snapshots.findIndex((snapshot) => snapshot.id === summary.id);
      const previousSummary = index >= 0 ? snapshots[index + 1] : undefined;
      const [current, previous] = await Promise.all([
        get<McpCapabilitySnapshotDetails>(
          `${config.baseUrl}/${serverId}/snapshots/${summary.id}`,
        ),
        previousSummary
          ? get<McpCapabilitySnapshotDetails>(
            `${config.baseUrl}/${serverId}/snapshots/${previousSummary.id}`,
          )
          : Promise.resolve(undefined),
      ]);
      setSnapshotComparison({current, previous});
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.mcp.snapshots.error.load', '能力快照详情加载失败'),
      ));
    } finally {
      setSnapshotLoading(false);
    }
  }, [config.baseUrl, message, serverId, snapshots, t]);

  const snapshotDiff = useMemo(() => {
    if (!snapshotComparison) return [];
    const {current, previous} = snapshotComparison;
    const groups = [
      {
        type: 'TOOL',
        current: current.tools.map((item) => item.name),
        previous: previous?.tools.map((item) => item.name) ?? [],
      },
      {
        type: 'RESOURCE',
        current: current.resources.map((item) => item.uri),
        previous: previous?.resources.map((item) => item.uri) ?? [],
      },
      {
        type: 'RESOURCE_TEMPLATE',
        current: current.resourceTemplates.map((item) => item.uriTemplate),
        previous: previous?.resourceTemplates.map((item) => item.uriTemplate) ?? [],
      },
      {
        type: 'PROMPT',
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

  useEffect(() => {
    void loadCapabilities();
  }, [loadCapabilities]);

  const columns = useMemo(() => [
    {
      title: t('ai.mcp.tools.name', '工具名称'),
      dataIndex: 'name',
      width: 240,
      render: (value: string) => <Text code>{value}</Text>,
    },
    {
      title: t('ai.mcp.tools.title', '标题'),
      dataIndex: 'title',
      width: 200,
    },
    {
      title: t('ai.mcp.tools.description', '说明'),
      dataIndex: 'description',
    },
    {
      title: t('ai.mcp.tools.schema', '输入 Schema'),
      dataIndex: 'inputSchema',
      width: 140,
      render: (value: Record<string, unknown>) => (
        <Paragraph
          copyable={{text: JSON.stringify(value ?? {}, null, 2)}}
          ellipsis={{rows: 1, expandable: false}}
          style={{marginBottom: 0}}
        >
          {JSON.stringify(value ?? {})}
        </Paragraph>
      ),
    },
    ...(permissions.callTool ? [{
      title: t('ai.mcp.tools.action', '操作'),
      key: 'action',
      width: 110,
      render: (_value: unknown, tool: McpTool) => (
        <Button
          type="link"
          onClick={() => {
            setToolArguments({});
            setArgumentsJson('{}');
            setAdvancedArguments(false);
            setResult(undefined);
            setCallingTool(tool);
          }}
        >
          {t('ai.mcp.tools.call', '调用')}
        </Button>
      ),
    }] : []),
  ], [permissions.callTool, t]);

  const callTool = useCallback(async () => {
    if (!serverId || !callingTool) return;
    let args = toolArguments;
    if (advancedArguments) {
      try {
        const parsed: unknown = JSON.parse(argumentsJson);
        if (parsed === null || Array.isArray(parsed) || typeof parsed !== 'object') {
          throw new Error('not object');
        }
        args = parsed as Record<string, unknown>;
      } catch {
        message.error(t('ai.mcp.tools.error.arguments', '参数必须是有效的 JSON 对象'));
        return;
      }
    } else {
      const schema = (callingTool.inputSchema ?? {
        type: 'object',
      }) as RJSFSchema;
      const validation = validator.validateFormData(args, schema);
      if (validation.errors.length > 0) {
        message.error(validation.errors[0]?.stack
          || t('ai.mcp.tools.error.schema', '工具参数不符合输入 Schema'));
        return;
      }
    }
    setCalling(true);
    try {
      setResult(await post<ToolResult>(`${config.baseUrl}/${serverId}/tools/call`, {
        toolName: callingTool.name,
        arguments: args,
      }));
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.mcp.tools.error.call', '工具调用失败'),
      ));
    } finally {
      setCalling(false);
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
  ]);

  const readResource = useCallback(async () => {
    if (!serverId || !readingResource) return;
    setCalling(true);
    try {
      setCapabilityResult(await post(
        `${config.baseUrl}/${serverId}/resources/read`,
        {uri: readingResource},
      ));
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.mcp.resources.error.read', 'Resource 读取失败'),
      ));
    } finally {
      setCalling(false);
    }
  }, [config.baseUrl, message, readingResource, serverId, t]);

  const getPrompt = useCallback(async () => {
    if (!serverId || !gettingPrompt) return;
    let args: Record<string, unknown>;
    try {
      const parsed: unknown = JSON.parse(argumentsJson);
      if (parsed === null || Array.isArray(parsed) || typeof parsed !== 'object') {
        throw new Error('not object');
      }
      args = parsed as Record<string, unknown>;
    } catch {
      message.error(t('ai.mcp.tools.error.arguments', '参数必须是有效的 JSON 对象'));
      return;
    }
    setCalling(true);
    try {
      setCapabilityResult(await post(
        `${config.baseUrl}/${serverId}/prompts/get`,
        {name: gettingPrompt.name, arguments: args},
      ));
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.mcp.prompts.error.get', 'Prompt 获取失败'),
      ));
    } finally {
      setCalling(false);
    }
  }, [argumentsJson, config.baseUrl, gettingPrompt, message, serverId, t]);

  const resourceColumns = useMemo(() => [
    {
      title: t('ai.mcp.resources.uri', 'Resource URI'),
      dataIndex: 'uri',
      render: (value: string) => <Text code>{value}</Text>,
    },
    {title: t('ai.mcp.resources.name', '名称'), dataIndex: 'name', width: 180},
    {title: t('ai.mcp.tools.description', '说明'), dataIndex: 'description'},
    {title: t('ai.mcp.common.mime', 'MIME'), dataIndex: 'mimeType', width: 160},
    ...(permissions.readResource ? [{
      title: t('ai.mcp.tools.action', '操作'),
      key: 'action',
      width: 100,
      render: (_value: unknown, resource: McpResource) => (
        <Button type="link" onClick={() => {
          setCapabilityResult(undefined);
          setReadingResource(resource.uri);
        }}>
          {t('ai.mcp.resources.read', '读取')}
        </Button>
      ),
    }] : []),
  ], [permissions.readResource, t]);

  const promptColumns = useMemo(() => [
    {
      title: t('ai.mcp.prompts.name', 'Prompt 名称'),
      dataIndex: 'name',
      width: 240,
      render: (value: string) => <Text code>{value}</Text>,
    },
    {title: t('ai.mcp.tools.title', '标题'), dataIndex: 'title', width: 180},
    {title: t('ai.mcp.tools.description', '说明'), dataIndex: 'description'},
    {
      title: t('ai.mcp.prompts.arguments', '参数'),
      dataIndex: 'arguments',
      width: 220,
      render: (value: McpPrompt['arguments']) => (
        <Text>{(value ?? []).map((argument) => (
          `${argument.name}${argument.required ? '*' : ''}`
        )).join(', ') || '-'}</Text>
      ),
    },
    ...(permissions.getPrompt ? [{
      title: t('ai.mcp.tools.action', '操作'),
      key: 'action',
      width: 100,
      render: (_value: unknown, prompt: McpPrompt) => (
        <Button type="link" onClick={() => {
          setArgumentsJson('{}');
          setCapabilityResult(undefined);
          setGettingPrompt(prompt);
        }}>
          {t('ai.mcp.prompts.get', '获取')}
        </Button>
      ),
    }] : []),
  ], [permissions.getPrompt, t]);

  return (
    <>
      <Card
        title={t('ai.mcp.tools.page.title', 'MCP 工具')}
        extra={(
          <Space>
            <Select
              style={{width: 320}}
              placeholder={t('ai.mcp.tools.selectServer', '选择 READY MCP Server')}
              value={serverId}
              onChange={setServerId}
              options={servers.map((server) => ({
                value: server.id,
                label: server.name || server.code || server.id,
              }))}
            />
            <Button onClick={() => void loadCapabilities()}>
              {t('ai.mcp.action.refresh', '刷新')}
            </Button>
          </Space>
        )}
      >
        {serverLoadError && (
          <Alert
            showIcon
            type="error"
            message={t('ai.mcp.tools.error.loadServers', 'MCP Server 列表加载失败')}
            description={serverLoadError}
            action={(
              <Button size="small" onClick={() => void loadServers()}>
                {t('ai.mcp.action.retry', '重试')}
              </Button>
            )}
            style={{marginBottom: 16}}
          />
        )}
        {!serverLoadError && !serverId && (
          <Alert
            showIcon
            type="info"
            closable
            message={t('ai.mcp.tools.emptyServer', '请先注册并发现一个 READY MCP Server')}
            style={{marginBottom: 16}}
          />
        )}
        {capabilityLoadError && (
          <Alert
            showIcon
            type="error"
            message={t('ai.mcp.tools.error.load', 'MCP 能力快照加载失败')}
            description={capabilityLoadError}
            action={(
              <Button size="small" onClick={() => void loadCapabilities()}>
                {t('ai.mcp.action.retry', '重试')}
              </Button>
            )}
            style={{marginBottom: 16}}
          />
        )}
        {serverId
          && !permissions.callTool
          && !permissions.readResource
          && !permissions.getPrompt && (
          <Alert
            showIcon
            type="info"
            closable
            message={t('ai.mcp.tools.readOnly', '当前以只读方式浏览能力')}
            description={t(
              'ai.mcp.tools.readOnly.description',
              '如需调用 Tool、读取 Resource 或获取 Prompt，请联系管理员授予相应操作权限。',
            )}
            style={{marginBottom: 16}}
          />
        )}
        <Tabs
          items={[
            {
              key: 'tools',
              label: `${t('ai.mcp.capabilities.tools', 'Tools')} (${tools.length})`,
              children: (
                <DataTable
                  rowKey="name"
                  loading={loading}
                  columns={columns}
                  dataSource={tools}
                  pagination={{pageSize: 20, hideOnSinglePage: true}}
                />
              ),
            },
            {
              key: 'resources',
              label: `${t('ai.mcp.capabilities.resources', 'Resources')} (${resources.length})`,
              children: (
                <DataTable
                  rowKey="uri"
                  loading={loading}
                  columns={resourceColumns}
                  dataSource={resources}
                  pagination={{pageSize: 20, hideOnSinglePage: true}}
                />
              ),
            },
            {
              key: 'resource-templates',
              label: `${t('ai.mcp.capabilities.templates', 'Templates')} (${resourceTemplates.length})`,
              children: (
                <DataTable
                  rowKey="uriTemplate"
                  loading={loading}
                  dataSource={resourceTemplates}
                  pagination={{pageSize: 20, hideOnSinglePage: true}}
                  columns={[
                    {
                      title: t('ai.mcp.resources.template', 'URI Template'),
                      dataIndex: 'uriTemplate',
                      render: (value: string) => <Text code>{value}</Text>,
                    },
                    {title: t('ai.mcp.resources.name', '名称'), dataIndex: 'name', width: 180},
                    {title: t('ai.mcp.tools.description', '说明'), dataIndex: 'description'},
                    {title: t('ai.mcp.common.mime', 'MIME'), dataIndex: 'mimeType', width: 160},
                  ]}
                />
              ),
            },
            {
              key: 'prompts',
              label: `${t('ai.mcp.capabilities.prompts', 'Prompts')} (${prompts.length})`,
              children: (
                <DataTable
                  rowKey="name"
                  loading={loading}
                  columns={promptColumns}
                  dataSource={prompts}
                  pagination={{pageSize: 20, hideOnSinglePage: true}}
                />
              ),
            },
            {
              key: 'snapshots',
              label: `${t('ai.mcp.snapshots.title', '发现历史')} (${snapshots.length})`,
              children: (
                <DataTable
                  rowKey="id"
                  loading={loading || snapshotLoading}
                  dataSource={snapshots}
                  pagination={{pageSize: 10, hideOnSinglePage: true}}
                  columns={[
                    {
                      title: t('ai.mcp.snapshots.discoveredAt', '发现时间'),
                      dataIndex: 'discoveredAt',
                      width: 190,
                      render: (value: string) => new Date(value).toLocaleString(),
                    },
                    {
                      title: t('ai.mcp.snapshots.server', '服务版本'),
                      key: 'server',
                      width: 230,
                      render: (_value: unknown, snapshot: McpCapabilitySnapshotSummary) => (
                        `${snapshot.remoteServerName} ${snapshot.remoteServerVersion}`
                      ),
                    },
                    {
                      title: t('ai.mcp.gateway.protocol', '协议版本'),
                      dataIndex: 'protocolVersion',
                      width: 150,
                    },
                    {
                      title: t('ai.mcp.snapshots.capabilityCounts', '能力数量'),
                      key: 'counts',
                      render: (_value: unknown, snapshot: McpCapabilitySnapshotSummary) => (
                        <Space wrap>
                          <Tag>{t('ai.mcp.capabilities.toolsCount', 'Tools（{count}）', {
                            count: snapshot.toolCount,
                          })}</Tag>
                          <Tag>{t('ai.mcp.capabilities.resourcesCount', 'Resources（{count}）', {
                            count: snapshot.resourceCount,
                          })}</Tag>
                          <Tag>{t('ai.mcp.capabilities.templatesCount', '资源模板（{count}）', {
                            count: snapshot.resourceTemplateCount,
                          })}</Tag>
                          <Tag>{t('ai.mcp.capabilities.promptsCount', 'Prompts（{count}）', {
                            count: snapshot.promptCount,
                          })}</Tag>
                        </Space>
                      ),
                    },
                    {
                      title: t('ai.mcp.snapshots.schemaHash', 'Schema Hash'),
                      dataIndex: 'schemaHash',
                      width: 190,
                      ellipsis: true,
                      render: (value: string) => (
                        <Text code copyable={{text: value}}>{value.slice(0, 16)}…</Text>
                      ),
                    },
                    {
                      title: t('ai.mcp.snapshots.active', '生效状态'),
                      dataIndex: 'active',
                      width: 100,
                      render: (value: boolean) => value
                        ? <Tag color="green">{t('ai.mcp.snapshots.current', '当前生效')}</Tag>
                        : <Tag>{t('ai.mcp.snapshots.history', '历史')}</Tag>,
                    },
                    {
                      title: t('ai.mcp.tools.action', '操作'),
                      key: 'action',
                      width: 110,
                      render: (_value: unknown, snapshot: McpCapabilitySnapshotSummary) => (
                        <Button type="link" onClick={() => void loadSnapshotDetails(snapshot)}>
                          {t('ai.mcp.snapshots.compare', '查看差异')}
                        </Button>
                      ),
                    },
                  ]}
                />
              ),
            },
          ]}
        />
      </Card>
      <Modal
        open={Boolean(callingTool)}
        title={`${t('ai.mcp.tools.call', '调用')} · ${callingTool?.name || ''}`}
        okText={t('ai.mcp.tools.call', '调用')}
        confirmLoading={calling}
        width={760}
        onOk={() => void callTool()}
        onCancel={() => setCallingTool(undefined)}
      >
        <Form layout="vertical">
          <Form.Item
            label={(
              <Space>
                <Text>{t('ai.mcp.tools.arguments', '调用参数')}</Text>
                <Switch
                  size="small"
                  checked={advancedArguments}
                  onChange={(checked) => {
                    setAdvancedArguments(checked);
                    if (checked) {
                      setArgumentsJson(JSON.stringify(toolArguments, null, 2));
                    }
                  }}
                />
                <Text type="secondary">
                  {t('ai.mcp.tools.arguments.advanced', '高级 JSON')}
                </Text>
              </Space>
            )}
          >
            {advancedArguments ? (
              <TextArea
                value={argumentsJson}
                autoSize={{minRows: 6, maxRows: 14}}
                onChange={(event) => setArgumentsJson(event.target.value)}
              />
            ) : (
              <SForm
                schema={(callingTool?.inputSchema ?? {
                  type: 'object',
                }) as RJSFSchema}
                formData={toolArguments}
                hideSubmit
                showErrorList={false}
                liveValidate
                onChange={(event) => setToolArguments(
                  (event.formData ?? {}) as Record<string, unknown>,
                )}
              />
            )}
          </Form.Item>
          {result && (
            <Form.Item label={t('ai.mcp.tools.result', '调用结果')}>
              <Tag color={result.error ? 'red' : 'green'}>
                {result.error
                  ? t('ai.mcp.tools.result.error', '调用失败')
                  : t('ai.mcp.tools.result.success', '调用成功')}
              </Tag>
              <Paragraph>
                <pre style={{whiteSpace: 'pre-wrap', maxHeight: 320, overflow: 'auto'}}>
                  {JSON.stringify(result, null, 2)}
                </pre>
              </Paragraph>
            </Form.Item>
          )}
        </Form>
      </Modal>
      <Modal
        open={Boolean(readingResource)}
        title={t('ai.mcp.resources.read', '读取 Resource')}
        okText={t('ai.mcp.resources.read', '读取')}
        confirmLoading={calling}
        width={760}
        onOk={() => void readResource()}
        onCancel={() => setReadingResource(undefined)}
      >
        <Form layout="vertical">
          <Form.Item label={t('ai.mcp.resources.uri', 'Resource URI')}>
            <Input
              value={readingResource}
              onChange={(event) => setReadingResource(event.target.value)}
            />
          </Form.Item>
          {capabilityResult !== undefined && (
            <Paragraph>
              <pre style={{whiteSpace: 'pre-wrap', maxHeight: 360, overflow: 'auto'}}>
                {JSON.stringify(capabilityResult, null, 2)}
              </pre>
            </Paragraph>
          )}
        </Form>
      </Modal>
      <Modal
        open={Boolean(gettingPrompt)}
        title={`${t('ai.mcp.prompts.get', '获取 Prompt')} · ${gettingPrompt?.name || ''}`}
        okText={t('ai.mcp.prompts.get', '获取')}
        confirmLoading={calling}
        width={760}
        onOk={() => void getPrompt()}
        onCancel={() => setGettingPrompt(undefined)}
      >
        <Form layout="vertical">
          <Form.Item label={t('ai.mcp.tools.arguments', '参数 JSON')}>
            <TextArea
              value={argumentsJson}
              autoSize={{minRows: 5, maxRows: 12}}
              onChange={(event) => setArgumentsJson(event.target.value)}
            />
          </Form.Item>
          {capabilityResult !== undefined && (
            <Paragraph>
              <pre style={{whiteSpace: 'pre-wrap', maxHeight: 360, overflow: 'auto'}}>
                {JSON.stringify(capabilityResult, null, 2)}
              </pre>
            </Paragraph>
          )}
        </Form>
      </Modal>
      <Modal
        open={Boolean(snapshotComparison)}
        title={t('ai.mcp.snapshots.compareTitle', '能力快照与上次发现差异')}
        width={960}
        footer={null}
        onCancel={() => setSnapshotComparison(undefined)}
      >
        {snapshotComparison && (
          <>
            <Alert
              showIcon
              type={snapshotComparison.previous ? 'info' : 'success'}
              style={{marginBottom: 16}}
              message={snapshotComparison.previous
                ? `${new Date(snapshotComparison.current.discoveredAt).toLocaleString()} ↔ ${new Date(snapshotComparison.previous.discoveredAt).toLocaleString()}`
                : t('ai.mcp.snapshots.first', '这是该 MCP Server 的第一份能力快照')}
              description={snapshotComparison.current.schemaHash
                === snapshotComparison.previous?.schemaHash
                ? t('ai.mcp.snapshots.schemaUnchanged', 'Schema Hash 未变化')
                : t('ai.mcp.snapshots.schemaChanged', 'Schema Hash 已变化')}
            />
            <DataTable
              size="small"
              rowKey="type"
              dataSource={snapshotDiff}
              pagination={false}
              columns={[
                {
                  title: t('ai.mcp.snapshots.capability', '能力类型'),
                  dataIndex: 'type',
                  width: 140,
                  render: (value: string) => mcpCapabilityTypeLabel(t, value),
                },
                {
                  title: t('ai.mcp.snapshots.added', '新增'),
                  dataIndex: 'added',
                  render: (values: string[]) => values.length
                    ? <Space wrap>{values.map((value) => <Tag color="green" key={value}>{value}</Tag>)}</Space>
                    : '-',
                },
                {
                  title: t('ai.mcp.snapshots.removed', '移除'),
                  dataIndex: 'removed',
                  render: (values: string[]) => values.length
                    ? <Space wrap>{values.map((value) => <Tag color="red" key={value}>{value}</Tag>)}</Space>
                    : '-',
                },
              ]}
            />
            <Form layout="vertical" style={{marginTop: 16}}>
              <Form.Item label={t('ai.mcp.snapshots.capabilities', '协商能力')}>
                <Paragraph>
                  <pre style={{whiteSpace: 'pre-wrap', maxHeight: 260, overflow: 'auto'}}>
                    {JSON.stringify(snapshotComparison.current.capabilities ?? {}, null, 2)}
                  </pre>
                </Paragraph>
              </Form.Item>
            </Form>
          </>
        )}
      </Modal>
    </>
  );
};

export default McpCapabilities;
