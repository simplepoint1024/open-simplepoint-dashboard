import {
  Alert,
  Button,
  Checkbox,
  Empty,
  Input,
  message,
  Select,
  Skeleton,
  Space,
  Tag,
  Tree,
  Typography,
} from 'antd';
import {
  CheckOutlined,
  KeyOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons';
import type {DataNode} from 'antd/es/tree';
import type {Key} from 'react';
import {useCallback, useEffect, useMemo, useState} from 'react';
import {useData, usePage} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import api from '@/api';
import {
  AccessCenterResourceNode,
  AccessCenterResourceNodeType,
  AccessCenterRoleOverview,
  fetchResourceTree,
  fetchRoleDetail,
  fetchRoleOverviews,
  saveRoleAuthorization,
} from '@/api/system/access-center';
import {fetchItems as fetchDataScopeItems, DataScopeRelevantVo} from '@/api/system/data-scope';
import {fetchItems as fetchFieldScopeItems, FieldScopeRelevantVo} from '@/api/system/field-scope';
import './index.css';

type Translate = (key: string, defaultMessage?: string) => string;

type ScopeOption = {
  label: string;
  value: string;
};

type ResourceIndex = {
  nodeMap: Map<string, AccessCenterResourceNode>;
  permissionNodesByAuthority: Map<string, AccessCenterResourceNode[]>;
  permissionOrder: Map<string, number>;
};

const baseConfig = api['rbac-access-center'];
const ROLE_PAGE_SIZE = 100;
const TYPE_FALLBACK: Record<AccessCenterResourceNodeType, string> = {
  GROUP: '分组',
  MENU: '菜单',
  FEATURE: '功能',
  PERMISSION: '动作',
};
const HIGH_RISK_AUTHORITY = /(delete|remove|revoke|grant|authorize|admin|config|disable|drop)/i;

function roleTitle(item?: AccessCenterRoleOverview) {
  const role = item?.role;
  return role?.name || role?.authority || role?.id || '-';
}

function userTitle(user: {name?: string | null; email?: string | null; phoneNumber?: string | null; id?: string}) {
  return user.name || user.email || user.phoneNumber || user.id || '-';
}

function normalizeAuthorities(authorities?: readonly string[]) {
  return Array.from(new Set((authorities ?? []).map(String).filter(Boolean)));
}

function orderAuthorities(authorities: readonly string[], orderMap: Map<string, number>) {
  return normalizeAuthorities(authorities).sort((left, right) => {
    const leftOrder = orderMap.get(left) ?? Number.MAX_SAFE_INTEGER;
    const rightOrder = orderMap.get(right) ?? Number.MAX_SAFE_INTEGER;
    if (leftOrder !== rightOrder) return leftOrder - rightOrder;
    return left.localeCompare(right);
  });
}

function sameStringArray(left: readonly string[], right: readonly string[]) {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

function getNodeAuthorities(node?: AccessCenterResourceNode) {
  if (!node) return [];
  if (node.type === 'PERMISSION' && node.permissionAuthority) {
    return [node.permissionAuthority];
  }
  return node.permissionAuthorities ?? [];
}

function collectPermissionNodes(node?: AccessCenterResourceNode) {
  const result = new Map<string, AccessCenterResourceNode>();
  const visit = (current?: AccessCenterResourceNode) => {
    if (!current) return;
    if (current.type === 'PERMISSION' && current.permissionAuthority) {
      result.set(current.permissionAuthority, current);
      return;
    }
    current.children?.forEach(visit);
  };
  visit(node);
  return Array.from(result.values());
}

function buildResourceIndex(nodes: AccessCenterResourceNode[]): ResourceIndex {
  const nodeMap = new Map<string, AccessCenterResourceNode>();
  const permissionNodesByAuthority = new Map<string, AccessCenterResourceNode[]>();
  const permissionOrder = new Map<string, number>();
  let order = 0;

  const visit = (node: AccessCenterResourceNode) => {
    nodeMap.set(node.id, node);
    if (node.type === 'PERMISSION' && node.permissionAuthority) {
      const items = permissionNodesByAuthority.get(node.permissionAuthority) ?? [];
      items.push(node);
      permissionNodesByAuthority.set(node.permissionAuthority, items);
      if (!permissionOrder.has(node.permissionAuthority)) {
        permissionOrder.set(node.permissionAuthority, order);
        order += 1;
      }
    }
    node.children?.forEach(visit);
  };

  nodes.forEach(visit);
  return {nodeMap, permissionNodesByAuthority, permissionOrder};
}

function allNodeIds(nodes: AccessCenterResourceNode[]) {
  const result: string[] = [];
  const visit = (node: AccessCenterResourceNode) => {
    result.push(node.id);
    node.children?.forEach(visit);
  };
  nodes.forEach(visit);
  return result;
}

function collectAuthoritiesFromKeys(keys: readonly Key[], nodeMap: Map<string, AccessCenterResourceNode>) {
  const result = new Set<string>();
  keys.forEach((key) => {
    const node = nodeMap.get(String(key));
    getNodeAuthorities(node).forEach((authority) => result.add(authority));
  });
  return Array.from(result);
}

function resourceTypeLabel(type: AccessCenterResourceNodeType, t: Translate) {
  return t(`accessCenter.resource.type.${type}`, TYPE_FALLBACK[type]);
}

function resourceTypeColor(type: AccessCenterResourceNodeType) {
  if (type === 'MENU') return 'blue';
  if (type === 'FEATURE') return 'purple';
  if (type === 'PERMISSION') return 'green';
  return 'default';
}

function matchesKeyword(node: AccessCenterResourceNode, keyword: string) {
  if (!keyword) return true;
  return [node.label, node.code, node.path, node.description, node.permissionAuthority]
    .filter(Boolean)
    .some((value) => String(value).toLowerCase().includes(keyword));
}

function filterResourceTree(
  nodes: AccessCenterResourceNode[],
  keyword: string,
  selectedSet: Set<string>,
  changedSet: Set<string>,
  authorizedOnly: boolean,
  changedOnly: boolean,
): AccessCenterResourceNode[] {
  return nodes
    .map((node) => {
      const children: AccessCenterResourceNode[] = filterResourceTree(
        node.children ?? [],
        keyword,
        selectedSet,
        changedSet,
        authorizedOnly,
        changedOnly,
      );
      const authorities = getNodeAuthorities(node);
      const authorizedMatched = !authorizedOnly || authorities.some((authority) => selectedSet.has(authority));
      const changedMatched = !changedOnly || authorities.some((authority) => changedSet.has(authority));
      const selfMatched = matchesKeyword(node, keyword) && authorizedMatched && changedMatched;
      if (!selfMatched && children.length === 0) return null;
      return {...node, children};
    })
    .filter((node): node is AccessCenterResourceNode => !!node);
}

function buildTreeData(nodes: AccessCenterResourceNode[], selectedSet: Set<string>, t: Translate): DataNode[] {
  return nodes.map((node) => {
    const authorities = getNodeAuthorities(node);
    const selectedCount = authorities.filter((authority) => selectedSet.has(authority)).length;
    const showCount = authorities.length > 0 && node.type !== 'PERMISSION';

    return {
      key: node.id,
      title: (
        <span className="access-center-tree-title">
          <span className="access-center-tree-label">{node.label || node.code || node.permissionAuthority || '-'}</span>
          {node.type === 'PERMISSION' && node.permissionAuthority ? (
            <Typography.Text code className="access-center-tree-code">{node.permissionAuthority}</Typography.Text>
          ) : null}
          <Tag color={resourceTypeColor(node.type)}>{resourceTypeLabel(node.type, t)}</Tag>
          {showCount ? <Tag>{selectedCount}/{authorities.length}</Tag> : null}
        </span>
      ),
      children: buildTreeData(node.children ?? [], selectedSet, t),
    };
  });
}

function getScopeLabel(options: ScopeOption[], value: string | null, emptyText: string, fallback?: string | null) {
  if (!value) return emptyText;
  return options.find((option) => option.value === value)?.label ?? fallback ?? value;
}

const AccessCenter = () => {
  const {t, ensure, locale} = useI18n();
  const [roleSearch, setRoleSearch] = useState('');
  const [selectedRoleId, setSelectedRoleId] = useState<string>('');
  const [resourceSearch, setResourceSearch] = useState('');
  const [authorizedOnly, setAuthorizedOnly] = useState(false);
  const [changedOnly, setChangedOnly] = useState(false);
  const [selectedResourceId, setSelectedResourceId] = useState('');
  const [expandedKeys, setExpandedKeys] = useState<string[]>([]);
  const [baselineAuthorities, setBaselineAuthorities] = useState<string[]>([]);
  const [selectedAuthorities, setSelectedAuthorities] = useState<string[]>([]);
  const [baselineDataScopeId, setBaselineDataScopeId] = useState<string | null>(null);
  const [baselineFieldScopeId, setBaselineFieldScopeId] = useState<string | null>(null);
  const [dataScopeId, setDataScopeId] = useState<string | null>(null);
  const [fieldScopeId, setFieldScopeId] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    void ensure(baseConfig.i18nNamespaces);
  }, [ensure, locale]);

  const roleQuery = usePage<AccessCenterRoleOverview>(
    ['accessCenterRoleOverviews'],
    () => fetchRoleOverviews({page: '0', size: String(ROLE_PAGE_SIZE)}),
  );
  const roleRows = roleQuery.data?.content ?? [];

  const filteredRoles = useMemo(() => {
    const keyword = roleSearch.trim().toLowerCase();
    if (!keyword) return roleRows;
    return roleRows.filter((item) => {
      const role = item.role;
      return [role?.name, role?.authority, role?.description]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(keyword));
    });
  }, [roleRows, roleSearch]);

  useEffect(() => {
    if (!filteredRoles.length) {
      setSelectedRoleId('');
      return;
    }
    if (!selectedRoleId || !filteredRoles.some((item) => item.role.id === selectedRoleId)) {
      setSelectedRoleId(filteredRoles[0].role.id);
    }
  }, [filteredRoles, selectedRoleId]);

  const detailQuery = useData(
    selectedRoleId ? ['accessCenterRoleDetail', selectedRoleId] : ['accessCenterRoleDetail', 'empty'],
    () => fetchRoleDetail(selectedRoleId),
    {enabled: !!selectedRoleId},
  );
  const detail = detailQuery.data;

  const resourceTreeQuery = useData<AccessCenterResourceNode[]>(
    selectedRoleId ? ['accessCenterResourceTree', selectedRoleId] : ['accessCenterResourceTree', 'empty'],
    () => fetchResourceTree(selectedRoleId),
    {enabled: !!selectedRoleId},
  );
  const resourceNodes = resourceTreeQuery.data ?? [];
  const resourceIndex = useMemo(() => buildResourceIndex(resourceNodes), [resourceNodes]);

  useEffect(() => {
    setBaselineAuthorities([]);
    setSelectedAuthorities([]);
    setBaselineDataScopeId(null);
    setBaselineFieldScopeId(null);
    setDataScopeId(null);
    setFieldScopeId(null);
    setResourceSearch('');
    setAuthorizedOnly(false);
    setChangedOnly(false);
    setSelectedResourceId('');
    setExpandedKeys([]);
  }, [selectedRoleId]);

  useEffect(() => {
    if (!detail) return;
    const nextAuthorities = orderAuthorities(detail.authorizedPermissions ?? [], resourceIndex.permissionOrder);
    const nextDataScopeId = detail.scopeAssignment?.dataScopeId ?? null;
    const nextFieldScopeId = detail.scopeAssignment?.fieldScopeId ?? null;
    setBaselineAuthorities(nextAuthorities);
    setSelectedAuthorities(nextAuthorities);
    setBaselineDataScopeId(nextDataScopeId);
    setBaselineFieldScopeId(nextFieldScopeId);
    setDataScopeId(nextDataScopeId);
    setFieldScopeId(nextFieldScopeId);
  }, [detail, resourceIndex.permissionOrder]);

  useEffect(() => {
    const firstNode = resourceNodes[0];
    const nodeIds = new Set(resourceIndex.nodeMap.keys());
    setSelectedResourceId((previous) => {
      if (previous && nodeIds.has(previous)) return previous;
      return firstNode?.id ?? '';
    });
    setExpandedKeys((previous) => {
      const next = previous.length ? previous.filter((key) => nodeIds.has(key)) : resourceNodes.map((node) => node.id);
      return sameStringArray(previous, next) ? previous : next;
    });
  }, [resourceIndex.nodeMap, resourceNodes]);

  const dataScopeQuery = useData(['accessCenterDataScopes'], () => fetchDataScopeItems());
  const fieldScopeQuery = useData(['accessCenterFieldScopes'], () => fetchFieldScopeItems());

  const dataScopeOptions = useMemo(
    () =>
      (dataScopeQuery.data?.content ?? []).map((item: DataScopeRelevantVo) => ({
        label: item.name,
        value: item.id,
      })),
    [dataScopeQuery.data],
  );

  const fieldScopeOptions = useMemo(
    () =>
      (fieldScopeQuery.data?.content ?? []).map((item: FieldScopeRelevantVo) => ({
        label: item.name,
        value: item.id,
      })),
    [fieldScopeQuery.data],
  );

  const baselineSet = useMemo(() => new Set(baselineAuthorities), [baselineAuthorities]);
  const selectedSet = useMemo(() => new Set(selectedAuthorities), [selectedAuthorities]);
  const addedAuthorities = useMemo(
    () => selectedAuthorities.filter((authority) => !baselineSet.has(authority)),
    [baselineSet, selectedAuthorities],
  );
  const removedAuthorities = useMemo(
    () => baselineAuthorities.filter((authority) => !selectedSet.has(authority)),
    [baselineAuthorities, selectedSet],
  );
  const changedAuthoritySet = useMemo(
    () => new Set([...addedAuthorities, ...removedAuthorities]),
    [addedAuthorities, removedAuthorities],
  );
  const hasScopeChanges = dataScopeId !== baselineDataScopeId || fieldScopeId !== baselineFieldScopeId;
  const hasChanges = addedAuthorities.length > 0 || removedAuthorities.length > 0 || hasScopeChanges;

  const filteredResourceNodes = useMemo(
    () =>
      filterResourceTree(
        resourceNodes,
        resourceSearch.trim().toLowerCase(),
        selectedSet,
        changedAuthoritySet,
        authorizedOnly,
        changedOnly,
      ),
    [authorizedOnly, changedAuthoritySet, changedOnly, resourceNodes, resourceSearch, selectedSet],
  );

  const treeData = useMemo(
    () => buildTreeData(filteredResourceNodes, selectedSet, t),
    [filteredResourceNodes, selectedSet, t],
  );
  const checkedTreeKeys = useMemo(
    () =>
      selectedAuthorities.flatMap((authority) =>
        (resourceIndex.permissionNodesByAuthority.get(authority) ?? []).map((node) => node.id),
      ),
    [resourceIndex.permissionNodesByAuthority, selectedAuthorities],
  );
  const visibleExpandedKeys = resourceSearch.trim() ? allNodeIds(filteredResourceNodes) : expandedKeys;
  const selectedResource = resourceIndex.nodeMap.get(selectedResourceId);
  const selectedResourcePermissions = useMemo(() => collectPermissionNodes(selectedResource), [selectedResource]);
  const selectedResourceAuthorities = getNodeAuthorities(selectedResource);
  const selectedResourceSelectedCount = selectedResourceAuthorities.filter((authority) => selectedSet.has(authority)).length;
  const dataScopeLabel = getScopeLabel(
    dataScopeOptions,
    dataScopeId,
    t('roles.permissionConfig.noDataScope', '不限制数据范围'),
    detail?.dataScope?.name,
  );
  const fieldScopeLabel = getScopeLabel(
    fieldScopeOptions,
    fieldScopeId,
    t('roles.permissionConfig.noFieldScope', '不限制字段范围'),
    detail?.fieldScope?.name,
  );

  const handleTreeCheck = useCallback(
    (
      checked: Key[] | {checked: Key[]; halfChecked: Key[]},
      info?: {checked?: boolean; node?: {key?: Key}},
    ) => {
      const checkedKeys = Array.isArray(checked) ? checked : checked.checked;
      let nextAuthorities = collectAuthoritiesFromKeys(checkedKeys, resourceIndex.nodeMap);
      const toggledNode = info?.node?.key ? resourceIndex.nodeMap.get(String(info.node.key)) : undefined;
      if (info?.checked === false && toggledNode?.type === 'PERMISSION' && toggledNode.permissionAuthority) {
        nextAuthorities = nextAuthorities.filter((authority) => authority !== toggledNode.permissionAuthority);
      }
      setSelectedAuthorities(orderAuthorities(nextAuthorities, resourceIndex.permissionOrder));
    },
    [resourceIndex.nodeMap, resourceIndex.permissionOrder],
  );

  const handlePermissionToggle = useCallback(
    (authority: string, checked: boolean) => {
      setSelectedAuthorities((previous) => {
        const next = checked
          ? [...previous, authority]
          : previous.filter((current) => current !== authority);
        return orderAuthorities(next, resourceIndex.permissionOrder);
      });
    },
    [resourceIndex.permissionOrder],
  );

  const handleReset = useCallback(() => {
    setSelectedAuthorities(baselineAuthorities);
    setDataScopeId(baselineDataScopeId);
    setFieldScopeId(baselineFieldScopeId);
  }, [baselineAuthorities, baselineDataScopeId, baselineFieldScopeId]);

  const handleSave = useCallback(async () => {
    if (!selectedRoleId) return;
    setSaving(true);
    try {
      const saved = await saveRoleAuthorization({
        roleId: selectedRoleId,
        permissionAuthorities: selectedAuthorities,
        dataScopeId,
        fieldScopeId,
      });
      const savedAuthorities = orderAuthorities(saved.authorizedPermissions ?? [], resourceIndex.permissionOrder);
      const savedDataScopeId = saved.scopeAssignment?.dataScopeId ?? null;
      const savedFieldScopeId = saved.scopeAssignment?.fieldScopeId ?? null;
      setBaselineAuthorities(savedAuthorities);
      setSelectedAuthorities(savedAuthorities);
      setBaselineDataScopeId(savedDataScopeId);
      setBaselineFieldScopeId(savedFieldScopeId);
      setDataScopeId(savedDataScopeId);
      setFieldScopeId(savedFieldScopeId);
      await Promise.all([roleQuery.refetch(), detailQuery.refetch(), resourceTreeQuery.refetch()]);
      message.success(t('accessCenter.message.saved', '授权已保存'));
    } catch {
      message.error(t('accessCenter.message.saveFailed', '授权保存失败'));
    } finally {
      setSaving(false);
    }
  }, [
    dataScopeId,
    detailQuery,
    fieldScopeId,
    resourceIndex.permissionOrder,
    resourceTreeQuery,
    roleQuery,
    selectedAuthorities,
    selectedRoleId,
    t,
  ]);

  const selectedRole = roleRows.find((item) => item.role.id === selectedRoleId);
  const loading = roleQuery.isFetching || detailQuery.isFetching || resourceTreeQuery.isFetching;
  const resourceLoading = resourceTreeQuery.isFetching || saving;
  const loadFailed = detailQuery.error || resourceTreeQuery.error;

  return (
    <div className="access-center">
      <aside className="access-center-roles">
        <div className="access-center-toolbar">
          <Typography.Text strong>{t('accessCenter.roleList.title', '角色')}</Typography.Text>
          <Button
            icon={<ReloadOutlined />}
            size="small"
            title={t('common.refresh', '刷新')}
            aria-label={t('common.refresh', '刷新')}
            onClick={() => roleQuery.refetch()}
            loading={roleQuery.isFetching}
          />
        </div>
        <Input
          allowClear
          prefix={<SearchOutlined />}
          placeholder={t('accessCenter.roleList.search', '搜索角色')}
          value={roleSearch}
          onChange={(event) => setRoleSearch(event.target.value)}
        />
        <div className="access-center-role-list">
          {filteredRoles.length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('table.emptyText', '暂无数据')} />
          ) : (
            filteredRoles.map((item) => {
              const active = item.role.id === selectedRoleId;
              return (
                <button
                  key={item.role.id}
                  type="button"
                  className={`access-center-role-item${active ? ' access-center-role-item-active' : ''}`}
                  onClick={() => setSelectedRoleId(item.role.id)}
                >
                  <span className="access-center-role-name">{roleTitle(item)}</span>
                  <span className="access-center-role-code">{item.role.authority || item.role.id}</span>
                  <span className="access-center-role-meta">
                    <Tag icon={<KeyOutlined />} color="blue">{item.permissionCount}</Tag>
                    <Tag icon={<UserOutlined />} color="green">{item.assignedUserCount}</Tag>
                  </span>
                </button>
              );
            })
          )}
        </div>
      </aside>

      <main className="access-center-main">
        <div className="access-center-main-head">
          <div>
            <Typography.Title level={4}>{roleTitle(selectedRole)}</Typography.Title>
            <Typography.Text type="secondary">{selectedRole?.role.authority || '-'}</Typography.Text>
          </div>
          <Space>
            <Button
              icon={<ReloadOutlined />}
              onClick={() => {
                void detailQuery.refetch();
                void resourceTreeQuery.refetch();
              }}
              loading={loading}
              disabled={!selectedRoleId}
            >
              {t('common.refresh', '刷新')}
            </Button>
            <Button onClick={handleReset} disabled={!hasChanges || saving}>
              {t('accessCenter.action.reset', '重置')}
            </Button>
            <Button type="primary" onClick={handleSave} disabled={!hasChanges || !selectedRoleId} loading={saving}>
              {t('accessCenter.action.save', '保存授权')}
            </Button>
          </Space>
        </div>

        {loadFailed ? (
          <Alert
            type="error"
            showIcon
            message={t('accessCenter.message.loadFailed', '权限中心加载失败')}
          />
        ) : null}

        <section className="access-center-scope-band">
          <label className="access-center-field">
            <span>{t('roles.permissionConfig.dataScope', '数据权限')}</span>
            <Select
              allowClear
              value={dataScopeId}
              options={dataScopeOptions}
              loading={dataScopeQuery.isFetching}
              disabled={!selectedRoleId || saving}
              placeholder={t('roles.permissionConfig.noDataScope', '不限制数据范围')}
              onChange={(value) => setDataScopeId(value ?? null)}
            />
          </label>
          <label className="access-center-field">
            <span>{t('roles.permissionConfig.fieldScope', '字段权限')}</span>
            <Select
              allowClear
              value={fieldScopeId}
              options={fieldScopeOptions}
              loading={fieldScopeQuery.isFetching}
              disabled={!selectedRoleId || saving}
              placeholder={t('roles.permissionConfig.noFieldScope', '不限制字段范围')}
              onChange={(value) => setFieldScopeId(value ?? null)}
            />
          </label>
          <div className="access-center-save-state">
            {hasChanges ? (
              <Tag color="gold">{t('accessCenter.state.unsaved', '有未保存变更')}</Tag>
            ) : (
              <span className="access-center-status-line">
                <CheckOutlined />
                {t('accessCenter.state.synced', '已同步')}
              </span>
            )}
          </div>
        </section>

        <section className="access-center-resource">
          {selectedRoleId ? (
            <>
              <div className="access-center-resource-toolbar">
                <Input
                  allowClear
                  prefix={<SearchOutlined />}
                  placeholder={t('accessCenter.resource.search', '搜索菜单、功能或权限')}
                  value={resourceSearch}
                  onChange={(event) => setResourceSearch(event.target.value)}
                />
                <Checkbox checked={authorizedOnly} onChange={(event) => setAuthorizedOnly(event.target.checked)}>
                  {t('accessCenter.filter.authorizedOnly', '已授权')}
                </Checkbox>
                <Checkbox checked={changedOnly} onChange={(event) => setChangedOnly(event.target.checked)}>
                  {t('accessCenter.filter.changedOnly', '有变更')}
                </Checkbox>
              </div>
              <div className="access-center-resource-layout">
                <div className="access-center-resource-tree">
                  {resourceLoading && resourceNodes.length === 0 ? (
                    <Skeleton active paragraph={{rows: 8}} />
                  ) : treeData.length === 0 ? (
                    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('accessCenter.resource.empty', '暂无资源')} />
                  ) : (
                    <Tree
                      checkable
                      blockNode
                      disabled={saving}
                      treeData={treeData}
                      checkedKeys={checkedTreeKeys}
                      selectedKeys={selectedResourceId ? [selectedResourceId] : []}
                      expandedKeys={visibleExpandedKeys}
                      autoExpandParent={!!resourceSearch.trim()}
                      onCheck={handleTreeCheck}
                      onSelect={(keys) => setSelectedResourceId(keys[0] ? String(keys[0]) : '')}
                      onExpand={(keys) => setExpandedKeys(keys.map(String))}
                    />
                  )}
                </div>
                <div className="access-center-resource-detail">
                  {selectedResource ? (
                    <>
                      <div className="access-center-detail-head">
                        <div>
                          <Typography.Text strong>{selectedResource.label}</Typography.Text>
                          <Typography.Text type="secondary">{selectedResource.code || selectedResource.path || '-'}</Typography.Text>
                        </div>
                        <Tag color={resourceTypeColor(selectedResource.type)}>
                          {resourceTypeLabel(selectedResource.type, t)}
                        </Tag>
                      </div>
                      <div className="access-center-detail-meta">
                        <span>{t('accessCenter.metric.permissions', '权限')}</span>
                        <strong>{selectedResourceSelectedCount}/{selectedResourceAuthorities.length}</strong>
                      </div>
                      {selectedResource.description ? (
                        <Typography.Paragraph type="secondary" ellipsis={{rows: 2}}>
                          {selectedResource.description}
                        </Typography.Paragraph>
                      ) : null}
                      <div className="access-center-action-head">
                        <Typography.Text strong>{t('accessCenter.resource.permissions', '动作权限')}</Typography.Text>
                        <Typography.Text type="secondary">{selectedResourcePermissions.length}</Typography.Text>
                      </div>
                      <div className="access-center-action-list">
                        {selectedResourcePermissions.length === 0 ? (
                          <Empty
                            image={Empty.PRESENTED_IMAGE_SIMPLE}
                            description={t('accessCenter.resource.emptyPermissions', '暂无动作权限')}
                          />
                        ) : (
                          selectedResourcePermissions.map((permission) => {
                            const authority = permission.permissionAuthority || permission.code || '';
                            const selected = selectedSet.has(authority);
                            const baseline = baselineSet.has(authority);
                            const changed = selected !== baseline;
                            return (
                              <div
                                key={authority}
                                className={`access-center-action-row${changed ? ' access-center-action-row-changed' : ''}`}
                              >
                                <Checkbox
                                  checked={selected}
                                  disabled={saving}
                                  onChange={(event) => handlePermissionToggle(authority, event.target.checked)}
                                />
                                <span className="access-center-action-info">
                                  <strong>{permission.label || authority}</strong>
                                  <Typography.Text code>{authority}</Typography.Text>
                                  {permission.description ? <small>{permission.description}</small> : null}
                                </span>
                                <span className="access-center-action-tags">
                                  {HIGH_RISK_AUTHORITY.test(authority) ? (
                                    <Tag color="red">{t('accessCenter.resource.highRisk', '敏感')}</Tag>
                                  ) : null}
                                  {changed ? (
                                    <Tag color={selected ? 'green' : 'volcano'}>
                                      {selected
                                        ? t('accessCenter.diff.added', '新增')
                                        : t('accessCenter.diff.removed', '移除')}
                                    </Tag>
                                  ) : null}
                                </span>
                              </div>
                            );
                          })
                        )}
                      </div>
                    </>
                  ) : (
                    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('accessCenter.resource.empty', '暂无资源')} />
                  )}
                </div>
              </div>
            </>
          ) : (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('accessCenter.empty.role', '请选择角色')} />
          )}
        </section>
      </main>

      <aside className="access-center-impact">
        <div className="access-center-impact-head">
          <Typography.Text strong>{t('accessCenter.impact.title', '影响范围')}</Typography.Text>
          <SafetyCertificateOutlined />
        </div>
        <div className="access-center-metric-grid">
          <div className="access-center-metric">
            <KeyOutlined />
            <span>{selectedAuthorities.length}</span>
            <small>{t('accessCenter.metric.permissions', '权限')}</small>
          </div>
          <div className="access-center-metric">
            <TeamOutlined />
            <span>{detail?.assignedUserCount ?? 0}</span>
            <small>{t('accessCenter.metric.users', '用户')}</small>
          </div>
          <div className="access-center-metric">
            <span className="access-center-metric-added">+{addedAuthorities.length}</span>
            <small>{t('accessCenter.metric.added', '新增权限')}</small>
          </div>
          <div className="access-center-metric">
            <span className="access-center-metric-removed">-{removedAuthorities.length}</span>
            <small>{t('accessCenter.metric.removed', '移除权限')}</small>
          </div>
        </div>
        <div className="access-center-impact-section">
          <Typography.Text type="secondary">{t('roles.permissionConfig.dataScope', '数据权限')}</Typography.Text>
          <div>{dataScopeLabel}</div>
          {dataScopeId !== baselineDataScopeId ? <Tag color="gold">{t('accessCenter.diff.changed', '已变更')}</Tag> : null}
        </div>
        <div className="access-center-impact-section">
          <Typography.Text type="secondary">{t('roles.permissionConfig.fieldScope', '字段权限')}</Typography.Text>
          <div>{fieldScopeLabel}</div>
          {fieldScopeId !== baselineFieldScopeId ? <Tag color="gold">{t('accessCenter.diff.changed', '已变更')}</Tag> : null}
        </div>
        <div className="access-center-impact-section access-center-user-impact">
          <Typography.Text type="secondary">{t('accessCenter.impact.users', '已分配用户')}</Typography.Text>
          {(detail?.assignedUsers ?? []).length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('table.emptyText', '暂无数据')} />
          ) : (
            detail?.assignedUsers.map((user) => (
              <div key={user.id} className="access-center-user-row">
                <span className="access-center-user-avatar"><UserOutlined /></span>
                <span>
                  <strong>{userTitle(user)}</strong>
                  <small>{user.email || user.phoneNumber || user.id}</small>
                </span>
              </div>
            ))
          )}
        </div>
        <div className="access-center-impact-section access-center-status-line">
          <CheckOutlined />
          <span>{t('accessCenter.impact.auditReady', '变更会写入权限审计')}</span>
        </div>
      </aside>
    </div>
  );
};

export default AccessCenter;
