import api from '@/api';
import {get, post} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {
  Alert,
  Button,
  Card,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import type {TableProps} from 'antd';
import {useCallback, useEffect, useMemo, useState} from 'react';

const {Link, Text} = Typography;

type CatalogSource = 'INTERNAL' | 'OFFICIAL_MCP';
type CatalogKind = 'MCP_SERVER' | 'SKILL';

type CatalogItem = {
  id: string;
  source: CatalogSource;
  kind: CatalogKind;
  registryName?: string;
  code?: string;
  version?: string;
  title?: string;
  description?: string;
  status?: string;
  scopeType?: string;
  endpointUrl?: string;
  repositoryUrl?: string;
  websiteUrl?: string;
  importable: boolean;
  updatedAt?: string;
};

type CatalogPage = {
  content: CatalogItem[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

type SyncState = {
  syncMode?: string;
  status?: string;
  cursor?: string;
  lastCompletedAt?: string;
  lastError?: string;
  syncedCount?: number;
};

const resolveError = (error: unknown, fallback: string) => {
  if (error instanceof Error && error.message) return error.message;
  if (typeof error === 'string' && error) return error;
  return fallback;
};

const formatTime = (value?: string) => {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
};

const Catalog = () => {
  const config = api['ai-workbench.catalog'];
  const {t, ensure, locale} = useI18n();
  const [data, setData] = useState<CatalogPage>({
    content: [],
    number: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
  });
  const [query, setQuery] = useState('');
  const [source, setSource] = useState<CatalogSource>();
  const [kind, setKind] = useState<CatalogKind>();
  const [loading, setLoading] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [syncState, setSyncState] = useState<SyncState>();

  useEffect(() => {
    void ensure(config.i18nNamespaces);
  }, [config.i18nNamespaces, ensure, locale]);

  const load = useCallback(async (
    page = data.number,
    size = data.size,
    search = query,
  ) => {
    setLoading(true);
    try {
      const params: Record<string, string | number> = {page, size};
      if (search.trim()) params.query = search.trim();
      if (source) params.source = source;
      if (kind) params.kind = kind;
      setData(await get<CatalogPage>(config.baseUrl, params));
    } catch (error) {
      message.error(resolveError(
        error,
        t('ai.catalog.error.load', '扩展市场加载失败'),
      ));
    } finally {
      setLoading(false);
    }
  }, [config.baseUrl, data.number, data.size, kind, query, source, t]);

  const loadSyncState = useCallback(async () => {
    try {
      setSyncState(await get<SyncState>(config.syncStatusUrl));
    } catch (error) {
      message.error(resolveError(
        error,
        t('ai.catalog.error.syncStatus', '同步状态加载失败'),
      ));
    }
  }, [config.syncStatusUrl, t]);

  useEffect(() => {
    void load(0, data.size);
  // Filters explicitly trigger the request; page data does not.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [source, kind]);

  useEffect(() => {
    void loadSyncState();
  }, [loadSyncState]);

  const synchronize = useCallback(async () => {
    setSyncing(true);
    try {
      const result = await post<{status?: string; error?: string}>(
        config.syncUrl,
        {},
      );
      if (result.error) {
        message.warning(result.error);
      } else {
        message.success(t(
          'ai.catalog.success.sync',
          '官方 MCP Registry 同步完成',
        ));
      }
      await Promise.all([load(0, data.size), loadSyncState()]);
    } catch (error) {
      message.error(resolveError(
        error,
        t('ai.catalog.error.sync', '官方 MCP Registry 同步失败'),
      ));
    } finally {
      setSyncing(false);
    }
  }, [config.syncUrl, data.size, load, loadSyncState, t]);

  const importServer = useCallback((item: CatalogItem) => {
    Modal.confirm({
      title: t('ai.catalog.import.title', '导入 MCP Server'),
      content: t(
        'ai.catalog.import.description',
        '将创建无凭据的远程 MCP Server 草稿；如服务要求 OAuth，请在 MCP Servers 页面继续授权。',
      ),
      okText: t('ai.catalog.action.import', '导入'),
      cancelText: t('ai.catalog.action.cancel', '取消'),
      onOk: async () => {
        try {
          await post(`${config.baseUrl}/${item.id}/import`, {});
          message.success(t(
            'ai.catalog.success.import',
            'MCP Server 已导入当前工作空间',
          ));
          await load();
        } catch (error) {
          message.error(resolveError(
            error,
            t('ai.catalog.error.import', 'MCP Server 导入失败'),
          ));
          throw error;
        }
      },
    });
  }, [config.baseUrl, load, t]);

  const columns = useMemo<TableProps<CatalogItem>['columns']>(() => [
    {
      title: t('ai.catalog.column.package', '扩展'),
      key: 'package',
      render: (_, item) => (
        <Space direction="vertical" size={2}>
          <Text strong>{item.title || item.code || item.registryName}</Text>
          <Text type="secondary" ellipsis style={{maxWidth: 480}}>
            {item.description || '-'}
          </Text>
        </Space>
      ),
    },
    {
      title: t('ai.catalog.column.source', '来源'),
      dataIndex: 'source',
      width: 150,
      render: (value: CatalogSource) => (
        <Tag color={value === 'OFFICIAL_MCP' ? 'blue' : 'default'}>
          {value === 'OFFICIAL_MCP'
            ? t('ai.catalog.source.official', '官方 MCP Registry')
            : t('ai.catalog.source.internal', '内部')}
        </Tag>
      ),
    },
    {
      title: t('ai.catalog.column.kind', '类型'),
      dataIndex: 'kind',
      width: 120,
      render: (value: CatalogKind) => (
        <Tag color={value === 'SKILL' ? 'purple' : 'cyan'}>
          {value === 'SKILL' ? 'Skill' : 'MCP Server'}
        </Tag>
      ),
    },
    {
      title: t('ai.catalog.column.version', '版本'),
      dataIndex: 'version',
      width: 120,
      render: (value?: string) => value || '-',
    },
    {
      title: t('ai.catalog.column.status', '状态'),
      dataIndex: 'status',
      width: 110,
      render: (value?: string) => (
        <Tag color={value === 'ACTIVE' || value === 'READY' ? 'green' : 'gold'}>
          {value || '-'}
        </Tag>
      ),
    },
    {
      title: t('ai.catalog.column.updatedAt', '更新时间'),
      dataIndex: 'updatedAt',
      width: 180,
      render: formatTime,
    },
    {
      title: t('ai.catalog.column.action', '操作'),
      key: 'action',
      fixed: 'right',
      width: 170,
      render: (_, item) => (
        <Space>
          {item.repositoryUrl && (
            <Link href={item.repositoryUrl} target="_blank" rel="noreferrer">
              {t('ai.catalog.action.source', '源码')}
            </Link>
          )}
          {item.importable && (
            <Button type="link" onClick={() => importServer(item)}>
              {t('ai.catalog.action.import', '导入')}
            </Button>
          )}
        </Space>
      ),
    },
  ], [importServer, t]);

  return (
    <Space direction="vertical" size={16} style={{display: 'flex'}}>
      <Alert
        type={syncState?.status === 'FAILED' ? 'error' : 'info'}
        showIcon
        message={t('ai.catalog.notice.title', '隔离式 AI 扩展市场')}
        description={syncState?.lastError || t(
          'ai.catalog.notice.description',
          '内部扩展按当前工作空间实时展示；官方 Registry 仅同步元数据，导入后仍使用现有安全校验与 OAuth 流程。',
        )}
        action={(
          <Space>
            <Tag>{`${syncState?.status || 'NEVER'} · ${syncState?.syncMode || 'FULL'}`}</Tag>
            <Text type="secondary">
              {t('ai.catalog.sync.last', '最近完成')}：{formatTime(syncState?.lastCompletedAt)}
            </Text>
            <Button loading={syncing} onClick={() => void synchronize()}>
              {t('ai.catalog.action.sync', '同步官方 Registry')}
            </Button>
          </Space>
        )}
      />
      <Card>
        <Space wrap style={{marginBottom: 16}}>
          <Input.Search
            allowClear
            value={query}
            placeholder={t('ai.catalog.search.placeholder', '搜索名称、编码或说明')}
            onChange={(event) => setQuery(event.target.value)}
            onSearch={(value) => void load(0, data.size, value)}
            style={{width: 320}}
          />
          <Select
            allowClear
            value={source}
            placeholder={t('ai.catalog.filter.source', '全部来源')}
            onChange={setSource}
            style={{width: 190}}
            options={[
              {
                value: 'INTERNAL',
                label: t('ai.catalog.source.internal', '内部'),
              },
              {
                value: 'OFFICIAL_MCP',
                label: t('ai.catalog.source.official', '官方 MCP Registry'),
              },
            ]}
          />
          <Select
            allowClear
            value={kind}
            placeholder={t('ai.catalog.filter.kind', '全部类型')}
            onChange={setKind}
            style={{width: 160}}
            options={[
              {value: 'MCP_SERVER', label: 'MCP Server'},
              {value: 'SKILL', label: 'Skill'},
            ]}
          />
          <Button onClick={() => void load(0, data.size)}>
            {t('ai.catalog.action.refresh', '刷新')}
          </Button>
        </Space>
        <Table<CatalogItem>
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={data.content}
          scroll={{x: 1100}}
          pagination={{
            current: data.number + 1,
            pageSize: data.size,
            total: data.totalElements,
            showSizeChanger: true,
            onChange: (nextPage, nextSize) =>
              void load(nextPage - 1, nextSize),
          }}
        />
      </Card>
    </Space>
  );
};

export default Catalog;
