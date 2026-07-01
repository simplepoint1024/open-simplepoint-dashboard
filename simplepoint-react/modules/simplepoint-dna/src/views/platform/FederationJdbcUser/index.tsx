import {Button, Drawer, Space, Table, Tag, message} from 'antd';
import type {ColumnsType, TablePaginationConfig} from 'antd/es/table';
import {useEffect, useMemo, useState} from 'react';
import {usePage} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {Page} from '@simplepoint/shared/types/request';
import {get} from '@simplepoint/shared/api/methods';
import DataSourceConfig from './config/dataSource';
import {resolveErrorMessage} from '../shared';

const commonUsersBaseUrl = '/common/users';

type UserOption = {
  id: string;
  nickname?: string;
  name?: string;
  email?: string;
  phoneNumber?: string;
  enabled?: boolean;
};

const resolveUserDisplayName = (user: UserOption) => {
  const primary = user.nickname || user.name || user.email || user.phoneNumber || user.id;
  const secondary = [user.email, user.phoneNumber]
    .filter((value) => value && value !== primary)
    .join(' / ');
  return secondary ? `${primary} (${secondary})` : primary;
};

const App = () => {
  const {t, ensure, locale, messages} = useI18n();
  const [pageState, setPageState] = useState({current: 1, pageSize: 10});
  const [selectedUser, setSelectedUser] = useState<UserOption | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);

  useEffect(() => {
    void ensure(['dna-federation-jdbc-users']);
  }, [ensure, locale]);

  const {data, isFetching, error} = usePage<UserOption>(
    ['dna-jdbc-users-page', pageState.current, pageState.pageSize],
    () => get<Page<UserOption>>(commonUsersBaseUrl, {
      page: String(pageState.current - 1),
      size: String(pageState.pageSize),
      enabled: 'equals:true',
    }),
  );

  useEffect(() => {
    if (error) {
      message.error(resolveErrorMessage(error, t('dna.federation.jdbcUsers.page.error.loadUsers', '系统用户列表加载失败')));
    }
  }, [error, t]);

  const columns: ColumnsType<UserOption> = useMemo(() => [
    {
      key: 'user',
      dataIndex: 'nickname',
      title: t('dna.federation.jdbcUsers.page.column.user', '系统用户'),
      render: (_value: string | undefined, record: UserOption) => resolveUserDisplayName(record),
    },
    {
      key: 'email',
      dataIndex: 'email',
      title: t('dna.federation.jdbcUsers.page.column.email', '邮箱'),
      render: (value?: string) => value || '-',
    },
    {
      key: 'phoneNumber',
      dataIndex: 'phoneNumber',
      title: t('dna.federation.jdbcUsers.page.column.phoneNumber', '手机号'),
      render: (value?: string) => value || '-',
    },
    {
      key: 'enabled',
      dataIndex: 'enabled',
      title: t('dna.federation.jdbcUsers.page.column.enabled', '状态'),
      render: (value?: boolean) => (
        <Tag color={value === false ? 'default' : 'green'}>
          {value === false
            ? t('dna.federation.jdbcUsers.page.state.disabled', '已禁用')
            : t('dna.federation.jdbcUsers.page.state.enabled', '已启用')}
        </Tag>
      ),
    },
    {
      key: 'action',
      title: t('dna.federation.jdbcUsers.page.column.action', '操作'),
      width: 180,
      render: (_value: unknown, record: UserOption) => (
        <Button
          type="link"
          onClick={() => {
            setSelectedUser(record);
            setDrawerOpen(true);
          }}
        >
          {t('dna.federation.jdbcUsers.page.button.assignDataSources', '分配数据源')}
        </Button>
      ),
    },
  ], [messages, t]);

  const pagination: TablePaginationConfig = useMemo(() => ({
    current: pageState.current,
    pageSize: pageState.pageSize,
    total: data?.page.totalElements ?? 0,
    showSizeChanger: true,
    showQuickJumper: true,
    onChange: (current, pageSize) => {
      setPageState({current, pageSize});
    },
  }), [data?.page.totalElements, pageState.current, pageState.pageSize]);

  return (
    <div>
      <Space direction="vertical" size={16} style={{width: '100%'}}>
        <Table<UserOption>
          rowKey="id"
          size="small"
          loading={isFetching}
          columns={columns}
          dataSource={data?.content ?? []}
          pagination={pagination}
        />
      </Space>

      <Drawer
        title={selectedUser
          ? t(
              'dna.federation.jdbcUsers.page.drawer.title',
              '分配数据源 - {user}',
              {user: resolveUserDisplayName(selectedUser)},
            )
          : t('dna.federation.jdbcUsers.page.drawer.emptyTitle', '分配数据源')}
        open={drawerOpen}
        onClose={() => {
          setDrawerOpen(false);
          setSelectedUser(null);
        }}
        placement="right"
        width={900}
        destroyOnHidden
      >
        <DataSourceConfig userId={selectedUser?.id ?? null} />
      </Drawer>
    </div>
  );
};

export default App;
