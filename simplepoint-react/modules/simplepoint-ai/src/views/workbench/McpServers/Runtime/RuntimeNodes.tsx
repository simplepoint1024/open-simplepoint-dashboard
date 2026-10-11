import DataTable from '@simplepoint/components/DataTable';
import TableActions from '@simplepoint/components/Table/Actions';
import {useRemoteList} from '@simplepoint/components/DataTable/useRemoteList';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageSelected, usePageRetention} from '@simplepoint/shared/page/hooks';
import {
  Alert,
  Descriptions,
  Modal,
  Progress,
  Space,
  Tag,
  Typography,
} from 'antd';
import {useMemo, useState} from 'react';
import type {RuntimeNode} from './types';
import {runtimeErrorLabel, runtimeStatusLabel} from './runtimeLabels';
import {
  formatBytes,
  formatCpu,
  formatDateTime,
  resolveErrorMessage,
  statusColor,
} from './utils';

const {Paragraph, Text} = Typography;
const SecurityTag = ({value, inactiveColor, label}: {value: unknown; inactiveColor: string; label: string}) =>
  <Tag color={value === true ? 'green' : value === false ? inactiveColor : 'default'}>
    {label}{typeof value === 'boolean' ? '' : ' · —'}
  </Tag>;

type RuntimeNodesProps = {
  active?: boolean;
  nodesUrl: string;
};

const RuntimeNodes = ({active = true, nodesUrl}: RuntimeNodesProps) => {
  const {t} = useI18n();
  const selected = usePageSelected();
  const list = useRemoteList<RuntimeNode>(nodesUrl, active);
  const loadError = list.error ? resolveErrorMessage(list.error, t('table.loadFail', '加载失败')) : undefined;
  const [detail, setDetail] = useState<RuntimeNode>();
  usePageRetention(!detail);


  const columns = useMemo(() => [
    {
      title: t('ai.runtime.field.node', '节点'),
      dataIndex: 'displayName',
      width: 210,
      render: (value: string, node: RuntimeNode) => (
        <Space direction="vertical" size={0}>
          <Text strong>{value}</Text>
          <Text code type="secondary">{node.nodeId}</Text>
        </Space>
      ),
    },
    {
      title: t('ai.runtime.field.status', '状态'),
      dataIndex: 'status',
      width: 105,
      render: (value: string) => (
        <Tag color={statusColor(value)}>{runtimeStatusLabel(t, value)}</Tag>
      ),
    },
    {
      title: t('ai.runtime.node.capacity', 'Workload 容量'),
      key: 'capacity',
      width: 210,
      render: (_: unknown, node: RuntimeNode) => {
        if (typeof node.maxWorkloads !== 'number' || typeof node.runningWorkloads !== 'number'
          || !Number.isFinite(node.maxWorkloads) || !Number.isFinite(node.runningWorkloads)) return <Text>—</Text>;
        const percent = node.maxWorkloads > 0
          ? Math.round((node.runningWorkloads / node.maxWorkloads) * 100)
          : 0;
        return (
          <Progress
            percent={percent}
            size="small"
            format={() => `${node.runningWorkloads}/${node.maxWorkloads}`}
          />
        );
      },
    },
    {
      title: t('ai.runtime.node.resources', '节点资源'),
      key: 'resources',
      width: 210,
      render: (_: unknown, node: RuntimeNode) => (
        `${typeof node.cpuCores === 'number' && Number.isFinite(node.cpuCores) ? node.cpuCores : '—'} CPU · ${formatBytes(node.memoryBytes)}`
      ),
    },
    {
      title: t('ai.runtime.node.runtime', 'Runtime'),
      key: 'runtime',
      width: 185,
      render: (_: unknown, node: RuntimeNode) => (
        <Space direction="vertical" size={0}>
          <Text>{node.runtimeVersion}</Text>
          <Text type="secondary">{t(
            'ai.runtime.node.dockerApiVersion',
            'Docker API {version}',
            {version: node.engineApiVersion},
          )}</Text>
        </Space>
      ),
    },
    {
      title: t('ai.runtime.node.security', '安全策略'),
      key: 'security',
      width: 260,
      render: (_: unknown, node: RuntimeNode) => (
        <Space wrap size={4}>
          <SecurityTag value={node.seccompEnforced} inactiveColor="red" label={t('ai.runtime.node.security.seccomp', 'seccomp')}/>
          <SecurityTag value={node.appArmorEnforced} inactiveColor="default" label={t('ai.runtime.node.security.appArmor', 'AppArmor')}/>
          <SecurityTag value={node.requireSupplyChainAdmission} inactiveColor="gold" label={t('ai.runtime.node.security.supplyChainAdmission', '供应链准入')}/>
          <SecurityTag value={node.requireImageDigest} inactiveColor="red" label={t('ai.runtime.node.security.imageDigestRequired', '必须使用镜像 Digest')}/>
        </Space>
      ),
    },
    {
      title: t('ai.runtime.node.cachedImages', '缓存镜像'),
      dataIndex: 'cachedImageDigests',
      width: 105,
      render: (value?: string[]) => Array.isArray(value) ? value.length : '—',
    },
    {
      title: t('ai.runtime.field.lastHeartbeat', '最后心跳'),
      dataIndex: 'lastHeartbeatAt',
      width: 175,
      render: formatDateTime,
    },
    {
      title: t('ai.runtime.field.lastError', '最后错误'),
      dataIndex: 'lastError',
      width: 220,
      ellipsis: true,
      render: (value?: string) => value
        ? <Text type="danger">{runtimeErrorLabel(t, value)}</Text>
        : '-',
    },
    {
      title: t('ai.runtime.field.action', '操作'),
      key: 'action',
      fixed: 'right' as const,
      width: 90,
      render: (_: unknown, node: RuntimeNode) => (
        <TableActions selectedKeys={[node.id]} selectedRows={[node]} buttons={[
          {key: 'detail', sort: 1, text: t('ai.runtime.action.details', '详情'), type: 'link', size: 'small'},
        ]} isButtonDisabled={() => !selected || !!detail || !!loadError}
          onButtonEvents={{detail: () => {if (selected && !detail && !loadError) setDetail(structuredClone(node));}}}/>
      ),
    },
  ], [t, selected, detail, loadError]);

  return (
    <>
      <div className="runtime-list-nodes" style={{display: 'flex', flexDirection: 'column', minHeight: 0, gap: 12}}>
        <Alert
          showIcon
          type="info"
          closable
          style={{marginBottom: 16}}
          message={t(
            'ai.runtime.node.notice',
            '节点由独立 tool-runtime 进程主动注册；本页只读，且仅平台管理员可访问。',
          )}
        />
        {loadError && (
          <Alert
            showIcon
            type="error"
            style={{marginBottom: 16}}
            message={t(
              'ai.runtime.error.nodes.load',
              'Runtime Node 加载失败；节点信息仅平台管理员可查看。',
            )}
            description={loadError}
            action={(
              <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
                {key: 'retry', sort: 1, text: t('table.retry', '重试'), size: 'small'},
                {key: 'reset', sort: 2, text: t('table.query.reset', '重置筛选与排序'), size: 'small'},
              ]} onButtonEvents={{retry: list.retry, reset: list.reset}}/>
            )}
          />
        )}
        <DataTable<RuntimeNode>
          {...list.tableProps}
          query={list.tableProps.query}
          columns={list.columns(columns)}
          scroll={{x: 1800}}
        />
      </div>
      <Modal
        wrapClassName="runtime-node-details"
        open={Boolean(detail) && selected}
        destroyOnHidden={!detail}
        title={`${t('ai.runtime.node.details', 'Runtime Node 详情')} · ${detail?.displayName ?? ''}`}
        width={920}
        footer={null}
        onCancel={() => setDetail(undefined)}
      >
        {detail && (
          <>
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label={t('ai.runtime.node.id', '节点 ID')}>{detail.nodeId}</Descriptions.Item>
              <Descriptions.Item label={t('ai.runtime.node.instanceId', '实例 ID')}>{detail.instanceId}</Descriptions.Item>
              <Descriptions.Item label={t('ai.runtime.node.generation', '代次')}>{detail.generation}</Descriptions.Item>
              <Descriptions.Item label={t('ai.runtime.field.status', '状态')}>
                <Tag color={statusColor(detail.status)}>
                  {runtimeStatusLabel(t, detail.status)}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.runtime.node.advertiseUrl', '服务地址')} span={2}>
                {detail.advertiseUrl}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.runtime.node.maxMemory', '单任务内存')}>
                {formatBytes(detail.maxWorkloadMemoryBytes)}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.runtime.node.maxCpu', '单任务 CPU')}>
                {formatCpu(detail.maxWorkloadNanoCpus)}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.runtime.node.security.seccomp', 'seccomp')}>{detail.seccompProfileHash || '-'}</Descriptions.Item>
              <Descriptions.Item label={t('ai.runtime.node.security.appArmor', 'AppArmor')}>{detail.appArmorProfile || '-'}</Descriptions.Item>
              <Descriptions.Item label={t('ai.runtime.field.registeredAt', '注册时间')}>
                {formatDateTime(detail.registeredAt)}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.runtime.field.heartbeatExpiresAt', '心跳到期')}>
                {formatDateTime(detail.heartbeatExpiresAt)}
              </Descriptions.Item>
            </Descriptions>
            <Paragraph style={{marginTop: 16, marginBottom: 6}}>
              <Text strong>{t('ai.runtime.node.labels', '节点标签')}</Text>
            </Paragraph>
            <Space wrap>
              {Object.entries(detail.labels ?? {}).map(([key, value]) => (
                <Tag key={key}>{`${key}=${value}`}</Tag>
              ))}
              {Object.keys(detail.labels ?? {}).length === 0 && '-'}
            </Space>
            <Paragraph style={{marginTop: 16, marginBottom: 6}}>
              <Text strong>{t('ai.runtime.node.cachedImages', '缓存镜像')}</Text>
            </Paragraph>
            <Paragraph
              code
              copyable={{text: (detail.cachedImageDigests ?? []).join('\n')}}
              style={{maxHeight: 240, overflow: 'auto'}}
            >
              {(detail.cachedImageDigests ?? []).join('\n') || '-'}
            </Paragraph>
          </>
        )}
      </Modal>
    </>
  );
};

export default RuntimeNodes;
