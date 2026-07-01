import {useData, usePage} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Button, Card, Checkbox, Empty, Input, Space, Table, Tag, Tooltip, message} from 'antd';
import type {ColumnsType, TablePaginationConfig} from 'antd/es/table';
import {
  CheckCircleOutlined,
  DatabaseOutlined,
  DeleteOutlined,
  PlusOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import {useCallback, useEffect, useMemo, useState} from 'react';
import {
  type JdbcUserDataSourceItem,
  type JdbcUserGrant,
  fetchAuthorize,
  fetchAuthorized,
  fetchGrants,
  fetchItems,
  fetchSelectedItems,
  fetchUnauthorized,
  updateGrantPermissions,
} from '@/api/jdbcUsers';
import {resolveErrorMessage} from '../../../shared';

export interface JdbcUserDataSourceConfigProps {
  userId: string | null;
}

const ALL_OPERATIONS = ['METADATA', 'QUERY', 'EXPLAIN', 'DML', 'DDL'] as const;
type OperationType = (typeof ALL_OPERATIONS)[number];

const OPERATION_COLORS: Record<OperationType, string> = {
  METADATA: 'blue',
  QUERY: 'green',
  EXPLAIN: 'cyan',
  DML: 'orange',
  DDL: 'red',
};

const App = ({userId}: JdbcUserDataSourceConfigProps) => {
  const {t} = useI18n();
  const [leftPage, setLeftPage] = useState({current: 1, pageSize: 8});
  const [leftSearch, setLeftSearch] = useState('');
  const [targetKeys, setTargetKeys] = useState<string[]>([]);
  const [grants, setGrants] = useState<JdbcUserGrant[]>([]);
  const [authorizedItems, setAuthorizedItems] = useState<JdbcUserDataSourceItem[]>([]);
  const [authorizing, setAuthorizing] = useState<Set<string>>(new Set());

  const {data: page} = usePage<JdbcUserDataSourceItem>(
    ['dna-jdbc-user-items', leftPage.current, leftPage.pageSize],
    () => fetchItems({page: String(leftPage.current - 1), size: String(leftPage.pageSize)}),
  );
  const allItems = page?.content ?? [];

  const {data: authorized} = useData<string[]>(
    userId ? ['dna-jdbc-user-authorized', userId] : ['dna-jdbc-user-authorized', 'pending'],
    () => fetchAuthorized(userId as string),
    {enabled: !!userId},
  );

  const {data: selectedDetails} = useData<JdbcUserDataSourceItem[]>(
    userId && targetKeys.length > 0
      ? ['dna-jdbc-user-selected-items', ...targetKeys]
      : ['dna-jdbc-user-selected-items', userId ?? 'empty'],
    () => fetchSelectedItems(targetKeys),
    {enabled: !!userId && targetKeys.length > 0},
  );

  useEffect(() => {
    setTargetKeys([]);
    setAuthorizedItems([]);
    setGrants([]);
    setLeftPage({current: 1, pageSize: 8});
    setLeftSearch('');
  }, [userId]);

  useEffect(() => {
    if (userId) {
      void fetchGrants(userId).then(setGrants).catch((err) => {
        console.warn('Failed to fetch user grants', err);
      });
    }
  }, [userId]);

  useEffect(() => {
    if (authorized) setTargetKeys(authorized);
  }, [authorized]);

  useEffect(() => {
    if (selectedDetails) {
      setAuthorizedItems(selectedDetails);
    } else if (!targetKeys.length) {
      setAuthorizedItems([]);
    }
  }, [selectedDetails, targetKeys.length]);

  const authorizedSet = useMemo(() => new Set(targetKeys), [targetKeys]);

  // Available items = all items NOT yet authorized
  const availableItems = useMemo(
    () => allItems.filter((item) => !authorizedSet.has(item.id)),
    [allItems, authorizedSet],
  );

  const handleAuthorize = useCallback(async (item: JdbcUserDataSourceItem) => {
    if (!userId) return;
    setAuthorizing((prev) => new Set(prev).add(item.id));
    try {
      await fetchAuthorize({userId, dataSourceIds: [item.id]});
      setTargetKeys((prev) => [...prev, item.id]);
      setAuthorizedItems((prev) => [...prev, item]);
      const updatedGrants = await fetchGrants(userId);
      setGrants(updatedGrants);
    } catch (error: unknown) {
      message.error(resolveErrorMessage(error, t('dna.federation.jdbcUsers.page.error.authorize', '数据源授权失败')));
    } finally {
      setAuthorizing((prev) => {
        const next = new Set(prev);
        next.delete(item.id);
        return next;
      });
    }
  }, [userId, t]);

  const handleUnauthorize = useCallback(async (itemId: string) => {
    if (!userId) return;
    const prevKeys = targetKeys;
    const prevItems = authorizedItems;
    const prevGrants = grants;
    setTargetKeys((prev) => prev.filter((k) => k !== itemId));
    setAuthorizedItems((prev) => prev.filter((i) => i.id !== itemId));
    setGrants((prev) => prev.filter((g) => g.catalogId !== itemId));
    try {
      await fetchUnauthorized({userId, dataSourceIds: [itemId]});
    } catch (error: unknown) {
      setTargetKeys(prevKeys);
      setAuthorizedItems(prevItems);
      setGrants(prevGrants);
      message.error(resolveErrorMessage(error, t('dna.federation.jdbcUsers.page.error.unauthorized', '取消数据源授权失败')));
    }
  }, [userId, targetKeys, authorizedItems, grants, t]);

  const handlePermissionChange = useCallback((grant: JdbcUserGrant, operation: OperationType, checked: boolean) => {
    const current = new Set(grant.operationPermissions ?? []);
    if (checked) {
      current.add(operation);
    } else {
      current.delete(operation);
    }
    const next = Array.from(current);
    const prev = grant.operationPermissions;
    setGrants((g) => g.map((gr) => gr.id === grant.id ? {...gr, operationPermissions: next} : gr));
    void updateGrantPermissions(grant.id, next).catch((error: unknown) => {
      setGrants((g) => g.map((gr) => gr.id === grant.id ? {...gr, operationPermissions: prev} : gr));
      message.error(resolveErrorMessage(error, t('dna.federation.jdbcUsers.page.error.updatePermissions', '操作权限更新失败')));
    });
  }, [t]);

  if (!userId) {
    return (
      <div style={{display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', minHeight: 200}}>
        <Empty description={t('dna.federation.jdbcUsers.page.empty.selectUser', '请先选择一个系统用户')} />
      </div>
    );
  }

  const leftPagination: TablePaginationConfig = {
    current: leftPage.current,
    pageSize: leftPage.pageSize,
    total: page?.page.totalElements ?? 0,
    size: 'small',
    showSizeChanger: false,
    showQuickJumper: false,
    onChange: (current, pageSize) => setLeftPage({current, pageSize}),
  };

  const availableColumns: ColumnsType<JdbcUserDataSourceItem> = [
    {
      key: 'name',
      dataIndex: 'name',
      title: t('dna.federation.jdbcUsers.page.column.catalog', '数据源'),
      ellipsis: true,
      render: (_: unknown, record: JdbcUserDataSourceItem) => (
        <Space size={6}>
          <DatabaseOutlined style={{color: '#1677ff'}} />
          <span>{record.name || record.code || record.id}</span>
        </Space>
      ),
    },
    {
      key: 'type',
      dataIndex: 'databaseProductName',
      title: t('dna.federation.jdbcUsers.page.column.databaseProductName', '类型'),
      width: 100,
      render: (value?: string) => value ? <Tag>{value}</Tag> : '-',
    },
    {
      key: 'action',
      width: 60,
      align: 'center',
      render: (_: unknown, record: JdbcUserDataSourceItem) => (
        <Tooltip title={t('dna.federation.jdbcUsers.page.button.authorize', '授权')}>
          <Button
            type="text"
            size="small"
            icon={<PlusOutlined />}
            loading={authorizing.has(record.id)}
            onClick={() => handleAuthorize(record)}
            style={{color: '#52c41a'}}
          />
        </Tooltip>
      ),
    },
  ];

  const grantMap = new Map(grants.map((g) => [g.catalogId, g]));

  const authorizedColumns: ColumnsType<JdbcUserDataSourceItem> = [
    {
      key: 'name',
      dataIndex: 'name',
      title: t('dna.federation.jdbcUsers.page.column.catalog', '数据源'),
      ellipsis: true,
      render: (_: unknown, record: JdbcUserDataSourceItem) => (
        <Space size={6}>
          <CheckCircleOutlined style={{color: '#52c41a'}} />
          <span>{record.name || record.code || record.id}</span>
        </Space>
      ),
    },
    {
      key: 'permissions',
      title: t('dna.federation.jdbcUsers.page.label.operationPermissions', '操作权限'),
      width: 320,
      render: (_: unknown, record: JdbcUserDataSourceItem) => {
        const grant = grantMap.get(record.id);
        if (!grant) return '-';
        const perms = new Set(grant.operationPermissions ?? []);
        return (
          <Space size={4} wrap>
            {ALL_OPERATIONS.map((op) => (
              <Checkbox
                key={op}
                checked={perms.has(op)}
                onChange={(e) => handlePermissionChange(grant, op, e.target.checked)}
              >
                <Tag color={perms.has(op) ? OPERATION_COLORS[op] : 'default'} style={{margin: 0, cursor: 'pointer'}}>
                  {op}
                </Tag>
              </Checkbox>
            ))}
          </Space>
        );
      },
    },
    {
      key: 'action',
      width: 50,
      align: 'center',
      render: (_: unknown, record: JdbcUserDataSourceItem) => (
        <Tooltip title={t('dna.federation.jdbcUsers.page.button.unauthorize', '取消授权')}>
          <Button
            type="text"
            size="small"
            danger
            icon={<DeleteOutlined />}
            onClick={() => handleUnauthorize(record.id)}
          />
        </Tooltip>
      ),
    },
  ];

  return (
    <div style={{display: 'flex', gap: 16, height: '100%', minHeight: 0}}>
      {/* Left: Available datasources */}
      <Card
        title={
          <Space>
            <DatabaseOutlined />
            {t('dna.federation.jdbcUsers.page.card.available', '可用数据源')}
            <Tag>{page?.page.totalElements ?? 0}</Tag>
          </Space>
        }
        size="small"
        style={{flex: '0 0 360px', display: 'flex', flexDirection: 'column', overflow: 'hidden'}}
        styles={{body: {flex: 1, overflow: 'auto', padding: '0 12px 12px'}}}
        extra={
          <Input
            placeholder={t('dna.federation.jdbcUsers.page.search.placeholder', '搜索数据源')}
            prefix={<SearchOutlined />}
            size="small"
            allowClear
            style={{width: 150}}
            value={leftSearch}
            onChange={(e) => setLeftSearch(e.target.value)}
          />
        }
      >
        <Table<JdbcUserDataSourceItem>
          rowKey="id"
          size="small"
          columns={availableColumns}
          dataSource={availableItems}
          pagination={leftPagination}
          showHeader={false}
          scroll={{y: 320}}
        />
      </Card>

      {/* Right: Authorized datasources with inline permissions */}
      <Card
        title={
          <Space>
            <CheckCircleOutlined style={{color: '#52c41a'}} />
            {t('dna.federation.jdbcUsers.page.card.authorized', '已授权数据源')}
            <Tag color="green">{authorizedItems.length}</Tag>
          </Space>
        }
        size="small"
        style={{flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden'}}
        styles={{body: {flex: 1, overflow: 'auto', padding: '0 12px 12px'}}}
      >
        {authorizedItems.length === 0 ? (
          <div style={{display: 'flex', alignItems: 'center', justifyContent: 'center', height: 200}}>
            <Empty description={t('dna.federation.jdbcUsers.page.empty.noAuthorized', '尚未授权任何数据源')} />
          </div>
        ) : (
          <Table<JdbcUserDataSourceItem>
            rowKey="id"
            size="small"
            columns={authorizedColumns}
            dataSource={authorizedItems}
            pagination={false}
            scroll={{y: 380}}
          />
        )}
      </Card>
    </div>
  );
};

export default App;
