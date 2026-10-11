import api from '@/api';
import type {TableButtonProps} from '@simplepoint/components/Table';
import SimpleTable from '@simplepoint/components/SimpleTable';
import DataTable from '@simplepoint/components/DataTable';
import TableActions from '@simplepoint/components/Table/Actions';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {get, useData} from '@simplepoint/shared/api/methods';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {usePageSelected, usePageRetention} from '@simplepoint/shared/page/hooks';
import {entityLookupBatches} from '@simplepoint/components/EntitySelect/query';
import {readyServerFilters} from './Runtime/runtimeQueries';
import type {SimpleTablePageRequest} from '@simplepoint/components/SimpleTable/types';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import {
  Alert,
  App,
  Descriptions,
  Modal,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import type {Key} from 'react';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {resourceScopeLabel} from '../modelLabels';
import {localizeWorkbenchOperationError} from '../workbenchErrorCodes';
import {
  mcpPublicationStatusLabel,
} from './labels';

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
  upstreamServerId?: string;
  upstreamServerName?: string;
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

const McpPublications = () => {
  const config = api['ai-workbench.mcp-servers'];
  const {t, ensure, locale} = useI18n();
  const {message} = App.useApp();
  const selected = usePageSelected();
  const [manifest, setManifest] = useState<McpPublicationManifest>();
  usePageRetention(false, !!manifest);
  const manifestRequest = useRef<AbortController | undefined>(undefined);
  useEffect(() => () => manifestRequest.current?.abort(), []);

  const tableErrorMessageResolver = useCallback((error: unknown) => (
    localizeWorkbenchOperationError(t, error, {
      key: 'ai.error.operationFailed',
      fallback: 'MCP Publication 操作失败，请稍后重试',
    })
  ), [t]);
  const eligible = useData(['mcp-publication-eligible', config.baseUrl], async ({signal}) =>
    normalizePage(await get<RawPage<McpServerOption>>(config.baseUrl,
      {page: 0, size: 1, ...readyServerFilters}, {signal})), {retry: false});
  const serverLoadError = eligible.error;
  const serverLoading = eligible.isPending;
  const hasReadyServer = (eligible.data?.page.totalElements ?? 0) > 0;

  useEffect(() => {
    void ensure(config.i18nNamespaces);
  }, [config.i18nNamespaces, ensure, locale]);

  const loadPage = useCallback(async ({page, size, filters, sort, signal}: SimpleTablePageRequest) => {
    const result = normalizePage(await get<RawPage<McpPublicationRow>>(config.publicationsUrl,
      {page, size, ...filters, ...(sort ? {sort} : {})}, {signal}));
    const names = new Map<string, string>();
    try {
      for (const params of entityLookupBatches(result.content.map(row => row.upstreamServerId ?? ''))) {
        const servers = normalizePage(await get<RawPage<McpServerOption>>(config.baseUrl, params, {signal}));
        servers.content.forEach(server => names.set(server.id, server.name || server.code || server.id));
      }
    } catch (error) {
      if (signal?.aborted) throw error;
      // Display enrichment must not make the publication list unavailable.
    }
    return {...result, content: result.content.map(row => ({...row,
      upstreamServerName: names.get(row.upstreamServerId ?? '')}))};
  }, [config.baseUrl, config.publicationsUrl]);

  const formSchemaTransform = useCallback((schema: any) => {
    const nextSchema = structuredClone(schema ?? {});
    const properties = nextSchema?.properties ?? {};
    if (properties.upstreamServerId) {
      delete properties.upstreamServerId.oneOf;
      delete properties.upstreamServerId.enum;
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
  }, [t]);
  const formUiSchema = useMemo(() => ({upstreamServerId: {
    'ui:widget': 'EntitySelect',
    'ui:options': {endpoint: config.baseUrl, filters: readyServerFilters, lookupFilters: {}},
  }}), [config.baseUrl]);

  const columnOverrides = useMemo(() => ({
    scopeType: {
      width: 110,
      render: (value: string) => (
        <Tag color={value === 'TENANT' ? 'blue' : 'purple'}>
          {resourceScopeLabel(t, value)}
        </Tag>
      ),
    },
    upstreamServerId: {
      width: 220,
      render: (value: string, row: McpPublicationRow) => row.upstreamServerName
        || (value ? t('form.remoteSelect.selectedUnavailable', '已选项不可用') : '-'),
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
          {mcpPublicationStatusLabel(t, value)}
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
  }), [t]);

  const loadManifest = useCallback(async (rows: McpPublicationRow[]) => {
    const publication = rows?.[0];
    if (!publication?.id || !selected) return;
    if (publication.status !== 'PUBLISHED') {
      message.warning(t(
        'ai.mcp.publications.warning.published',
        '请先启用并发布该 MCP Publication',
      ));
      return;
    }
    manifestRequest.current?.abort();
    const request = new AbortController();
    manifestRequest.current = request;
    try {
      const next = await get<McpPublicationManifest>(
        `${config.publicationsUrl}/${publication.id}/manifest`,
        undefined, {signal: request.signal},
      );
      if (!request.signal.aborted && manifestRequest.current === request) setManifest(next);
    } catch (error) {
      if (request.signal.aborted) return;
      message.error(resolveApiErrorMessage(
        error,
        t('ai.mcp.publications.error.manifest', '发布清单加载失败'),
      ));
    }
  }, [config.publicationsUrl, message, t]);

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
    identityTitle: string,
  ) => (
    <DataTable
      storageKey={`ai.mcp.publication-capabilities.${identity}`}
      query={{mode: 'local', scope: 'result', fields: {
        [identity]: {type: 'string'}, title: {type: 'string'}, description: {type: 'string'},
      }}}
      size="small"
      rowKey={(row) => row[identity] || row.name || JSON.stringify(row)}
      dataSource={rows}
      pagination={{pageSize: 10, hideOnSinglePage: true}}
      columns={[
        {
          title: identityTitle,
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
        closable
        style={{marginBottom: 16, flexShrink: 0}}
        message={t('ai.mcp.publications.notice.title', '对外开放标准 MCP Server')}
        description={t(
          'ai.mcp.publications.notice.description',
          '每个发布项拥有稳定的 canonical resource URI；外部令牌仅在 Gateway 校验，不会透传给上游 MCP Server。',
        )}
      />
      {serverLoadError && (
        <Alert
          showIcon
          type="error"
          style={{marginBottom: 16, flexShrink: 0}}
          message={t('ai.mcp.publications.error.loadServers', 'READY MCP Server 列表加载失败')}
          description={resolveApiErrorMessage(serverLoadError,
            t('ai.mcp.publications.error.loadServers', 'READY MCP Server 列表加载失败'))}
          action={(
            <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
              {key: 'retry', sort: 1, text: t('ai.mcp.action.retry', '重试'), size: 'small'},
            ]} onButtonEvents={{retry: () => void eligible.refetch()}}/>
          )}
        />
      )}
      {!serverLoading && !serverLoadError && !hasReadyServer && (
        <Alert
          showIcon
          type="warning"
          style={{marginBottom: 16, flexShrink: 0}}
          message={t('ai.mcp.publications.emptyServers', '暂无可发布的 MCP Server')}
          description={t(
            'ai.mcp.publications.emptyServers.description',
            '请先启用一个 MCP Server 并完成能力发现，状态变为 READY 后即可创建发布。',
          )}
        />
      )}
      <div style={{flex: 1, minHeight: 0}}>
        <SimpleTable
          {...config}
          baseUrl={config.publicationsUrl}
          name="ai-workbench-mcp-publications"
          customButtonEvents={customButtonEvents}
          errorMessageResolver={tableErrorMessageResolver}
          formSchemaTransform={formSchemaTransform}
          formUiSchema={formUiSchema}
          loadPage={loadPage}
          columnOverrides={columnOverrides}
          isButtonDisabled={(button) => (
            button.key === 'add' && (serverLoading || Boolean(serverLoadError) || !hasReadyServer)
          )}
        />
      </div>
      <Modal
        open={Boolean(manifest) && selected}
        destroyOnHidden={!manifest}
        title={`${t('ai.mcp.publications.manifest.title', '最终发布清单')} · ${manifest?.name ?? ''}`}
        width={980}
        footer={null}
        onCancel={() => {manifestRequest.current?.abort(); setManifest(undefined);}}
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
              <Descriptions.Item label={t('ai.mcp.snapshots.id', '快照 ID')} span={2}>
                <Text copyable>{manifest.snapshotId}</Text>
              </Descriptions.Item>
            </Descriptions>
            <Tabs
              style={{marginTop: 16}}
              items={[
                {
                  key: 'tools',
                  label: t('ai.mcp.capabilities.toolsCount', 'Tools（{count}）', {
                    count: manifest.tools.length,
                  }),
                  children: capabilityTable(
                    manifest.tools,
                    'name',
                    t('ai.mcp.tools.name', 'Tool 名称'),
                  ),
                },
                {
                  key: 'resources',
                  label: t('ai.mcp.capabilities.resourcesCount', 'Resources（{count}）', {
                    count: manifest.resources.length,
                  }),
                  children: capabilityTable(
                    manifest.resources,
                    'uri',
                    t('ai.mcp.resources.uri', 'Resource URI'),
                  ),
                },
                {
                  key: 'templates',
                  label: t('ai.mcp.capabilities.templatesCount', '资源模板（{count}）', {
                    count: manifest.resourceTemplates.length,
                  }),
                  children: capabilityTable(
                    manifest.resourceTemplates,
                    'uriTemplate',
                    t('ai.mcp.resources.template', 'URI 模板'),
                  ),
                },
                {
                  key: 'prompts',
                  label: t('ai.mcp.capabilities.promptsCount', 'Prompts（{count}）', {
                    count: manifest.prompts.length,
                  }),
                  children: capabilityTable(
                    manifest.prompts,
                    'name',
                    t('ai.mcp.prompts.name', 'Prompt 名称'),
                  ),
                },
              ]}
            />
          </>
        )}
      </Modal>
    </div>
  );
};

export default function ScopedMcpPublications() {
  const scope = useQueryScope();
  return <McpPublications key={JSON.stringify(scope)}/>;
}
