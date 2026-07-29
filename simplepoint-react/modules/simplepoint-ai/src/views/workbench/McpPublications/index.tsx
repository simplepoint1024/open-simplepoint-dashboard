import api from '@/api';
import type {TableButtonProps} from '@simplepoint/components/Table';
import SimpleTable from '@simplepoint/components/SimpleTable';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {Page} from '@simplepoint/shared/types/request';
import {
  Alert,
  Descriptions,
  Modal,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd';
import type {Key} from 'react';
import {useCallback, useEffect, useMemo, useState} from 'react';

const {Link, Text} = Typography;

type McpServerOption = {
  id: string;
  name?: string;
  code?: string;
  status?: string;
  enabled?: boolean;
};

type McpPublicationRow = {
  id: string;
  code?: string;
  status?: string;
};

type McpCapability = {
  name?: string;
  uri?: string;
  uriTemplate?: string;
  title?: string;
  description?: string;
};

type McpPublicationManifest = {
  code: string;
  name: string;
  description?: string;
  canonicalResourceUri: string;
  authorizationServerUri: string;
  requiredScopes: string[];
  rateLimitPerMinute: number;
  upstreamServerId: string;
  snapshotId: string;
  protocolVersion: string;
  tools: McpCapability[];
  resources: McpCapability[];
  resourceTemplates: McpCapability[];
  prompts: McpCapability[];
};

const resolveErrorMessage = (error: unknown, fallback: string) => {
  if (error instanceof Error && error.message) return error.message;
  if (typeof error === 'string' && error) return error;
  return fallback;
};

const McpPublications = () => {
  const config = api['ai-workbench.mcp-publications'];
  const {t, ensure, locale} = useI18n();
  const [servers, setServers] = useState<McpServerOption[]>([]);
  const [manifest, setManifest] = useState<McpPublicationManifest>();

  useEffect(() => {
    void ensure(config.i18nNamespaces);
  }, [config.i18nNamespaces, ensure, locale]);

  useEffect(() => {
    void get<Page<McpServerOption>>(config.serversUrl, {page: 0, size: 500})
      .then((page) => setServers(
        (page.content ?? []).filter((server) =>
          server.status === 'READY' && server.enabled !== false),
      ))
      .catch((error) => message.error(resolveErrorMessage(
        error,
        t('ai.mcp.publications.error.loadServers', 'READY MCP Server 列表加载失败'),
      )));
  }, [config.serversUrl, t]);

  const serverNames = useMemo(() => new Map(
    servers.map((server) => [
      server.id,
      server.name || server.code || server.id,
    ]),
  ), [servers]);

  const formSchemaTransform = useCallback((schema: any) => {
    const nextSchema = structuredClone(schema ?? {});
    const properties = nextSchema?.properties ?? {};
    if (properties.upstreamServerId) {
      properties.upstreamServerId.oneOf = servers.map((server) => ({
        const: server.id,
        title: server.name || server.code || server.id,
      }));
    }
    if (properties.requiredScopes) {
      properties.requiredScopes.default = 'mcp.invoke';
      properties.requiredScopes.description = t(
        'ai.mcp.publications.scopes.description',
        '以空格分隔；客户端令牌必须包含全部 Scope。',
      );
    }
    if (properties.rateLimitPerMinute) {
      properties.rateLimitPerMinute.default = 60;
      properties.rateLimitPerMinute.minimum = 1;
      properties.rateLimitPerMinute.maximum = 100000;
    }
    if (properties.enabled) {
      properties.enabled.default = false;
    }
    delete properties.scopeType;
    delete properties.tenantId;
    delete properties.canonicalResourceUri;
    delete properties.authorizationServerUri;
    delete properties.status;
    return nextSchema;
  }, [servers, t]);

  const columnOverrides = useMemo(() => ({
    scopeType: {
      width: 110,
      render: (value: string) => (
        <Tag color={value === 'TENANT' ? 'blue' : 'purple'}>
          {t(`ai.scope.${value}`, value || '-')}
        </Tag>
      ),
    },
    upstreamServerId: {
      width: 220,
      render: (value: string) => serverNames.get(value) || value || '-',
    },
    canonicalResourceUri: {
      width: 360,
      ellipsis: true,
      render: (value: string) => value
        ? <Link copyable={{text: value}} href={value}>{value}</Link>
        : '-',
    },
    authorizationServerUri: {
      width: 260,
      ellipsis: true,
      render: (value: string) => value
        ? <Text copyable={{text: value}}>{value}</Text>
        : '-',
    },
    requiredScopes: {width: 180, ellipsis: true},
    rateLimitPerMinute: {width: 150},
    status: {
      width: 120,
      render: (value: string) => (
        <Tag color={value === 'PUBLISHED' ? 'green' : 'default'}>
          {value || '-'}
        </Tag>
      ),
    },
    enabled: {
      width: 100,
      render: (value: boolean) => (
        <Tag color={value ? 'green' : 'default'}>
          {value
            ? t('ai.common.enabled', '已启用')
            : t('ai.common.disabled', '已禁用')}
        </Tag>
      ),
    },
  }), [serverNames, t]);

  const loadManifest = useCallback(async (rows: McpPublicationRow[]) => {
    const publication = rows?.[0];
    if (!publication?.id) return;
    if (publication.status !== 'PUBLISHED') {
      message.warning(t(
        'ai.mcp.publications.warning.published',
        '请先启用并发布该 MCP Publication',
      ));
      return;
    }
    try {
      setManifest(await get<McpPublicationManifest>(
        `${config.baseUrl}/${publication.id}/manifest`,
      ));
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.mcp.publications.error.manifest', '发布清单加载失败'),
      ));
    }
  }, [config.baseUrl, t]);

  const customButtonEvents: Record<string, (
    selectedRowKeys: Key[],
    selectedRows: McpPublicationRow[],
    props: TableButtonProps,
  ) => void> = {
    manifest: (_keys, rows) => void loadManifest(rows),
  };

  const capabilityTable = (
    rows: McpCapability[],
    identity: 'name' | 'uri' | 'uriTemplate',
  ) => (
    <Table
      size="small"
      rowKey={(row) => row[identity] || row.name || JSON.stringify(row)}
      dataSource={rows}
      pagination={{pageSize: 10, hideOnSinglePage: true}}
      columns={[
        {
          title: identity === 'name' ? 'Name' : identity,
          dataIndex: identity,
          width: 300,
          render: (value?: string) => <Text code>{value || '-'}</Text>,
        },
        {title: t('ai.mcp.tools.title', '标题'), dataIndex: 'title', width: 200},
        {title: t('ai.mcp.tools.description', '说明'), dataIndex: 'description'},
      ]}
    />
  );

  return (
    <div style={{
      height: '100%',
      minHeight: 0,
      display: 'flex',
      flexDirection: 'column',
      overflow: 'hidden',
    }}>
      <Alert
        showIcon
        type="info"
        style={{marginBottom: 16, flexShrink: 0}}
        message={t('ai.mcp.publications.notice.title', '对外开放标准 MCP Server')}
        description={t(
          'ai.mcp.publications.notice.description',
          '每个发布项拥有稳定的 canonical resource URI；外部令牌仅在 Gateway 校验，不会透传给上游 MCP Server。',
        )}
      />
      <div style={{flex: 1, minHeight: 0}}>
        <SimpleTable
          {...config}
          customButtonEvents={customButtonEvents}
          formSchemaTransform={formSchemaTransform}
          columnOverrides={columnOverrides}
        />
      </div>
      <Modal
        open={Boolean(manifest)}
        title={`${t('ai.mcp.publications.manifest.title', '最终发布清单')} · ${manifest?.name ?? ''}`}
        width={980}
        footer={null}
        onCancel={() => setManifest(undefined)}
      >
        {manifest && (
          <>
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label={t('ai.mcp-publications.title.canonicalResourceUri', 'Canonical Resource URI')} span={2}>
                <Link copyable={{text: manifest.canonicalResourceUri}} href={manifest.canonicalResourceUri}>
                  {manifest.canonicalResourceUri}
                </Link>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.mcp.gateway.protocol', '协议版本')}>
                {manifest.protocolVersion}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.mcp-publications.title.rateLimitPerMinute', '每分钟限流')}>
                {manifest.rateLimitPerMinute}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.mcp-publications.title.authorizationServerUri', '授权服务器')}>
                {manifest.authorizationServerUri}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.mcp-publications.title.requiredScopes', '所需 Scopes')}>
                {manifest.requiredScopes.join(' ')}
              </Descriptions.Item>
              <Descriptions.Item label="Snapshot ID" span={2}>
                <Text copyable>{manifest.snapshotId}</Text>
              </Descriptions.Item>
            </Descriptions>
            <Tabs
              style={{marginTop: 16}}
              items={[
                {
                  key: 'tools',
                  label: `Tools (${manifest.tools.length})`,
                  children: capabilityTable(manifest.tools, 'name'),
                },
                {
                  key: 'resources',
                  label: `Resources (${manifest.resources.length})`,
                  children: capabilityTable(manifest.resources, 'uri'),
                },
                {
                  key: 'templates',
                  label: `Templates (${manifest.resourceTemplates.length})`,
                  children: capabilityTable(manifest.resourceTemplates, 'uriTemplate'),
                },
                {
                  key: 'prompts',
                  label: `Prompts (${manifest.prompts.length})`,
                  children: capabilityTable(manifest.prompts, 'name'),
                },
              ]}
            />
          </>
        )}
      </Modal>
    </div>
  );
};

export default McpPublications;
