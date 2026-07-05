import {useCallback, useEffect, useRef, useState} from 'react';
import type {Dispatch, Key, SetStateAction} from 'react';
import {Alert, Button, Empty, Input, Pagination, Space, Spin, Tag, Tree, Typography, message} from 'antd';
import {DeleteOutlined, ReloadOutlined, SearchOutlined} from '@ant-design/icons';
import type {DataNode} from 'antd/es/tree';
import {createIcon} from '@simplepoint/shared/types/icon';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {fetchAssignedTree, fetchChildren, fetchSubtreeCodes, ResourceRelevantVo} from '@/api/system/resource';

type Translate = (key: string, fallback?: string, params?: Record<string, unknown>) => string;

type ResourceTreeNode = DataNode & {
  resource?: ResourceRelevantVo;
  loadMore?: {
    parentId: string;
    parentKey: Key;
    page: number;
  };
  children?: ResourceTreeNode[];
};

export interface ResourceTreeTransferProps {
  enabled?: boolean;
  reloadKey?: string;
  fetchAssignedCodes: () => Promise<string[]>;
  onAssign: (codes: string[]) => Promise<unknown>;
  onUnassign: (codes: string[]) => Promise<unknown>;
}

const TREE_PAGE_SIZE = 20;
const ASSIGNED_TREE_PAGE_SIZE = 20;
const LOAD_MORE_PREFIX = '__resource_load_more__';

const TYPE_COLOR: Record<string, string> = {
  GROUP: 'default',
  MODULE: 'blue',
  PAGE: 'green',
  FEATURE: 'cyan',
  ACTION: 'orange',
  API: 'purple',
};

function resourceKey(resource: ResourceRelevantVo) {
  return resource.code || resource.id;
}

function assignableCode(resource?: ResourceRelevantVo) {
  if (!resource?.code || resource.grantable === false || resource.disabled === true) return null;
  return resource.code;
}

function resolveI18nText(value: unknown, t?: Translate) {
  if (typeof value !== 'string') return String(value ?? '');
  if (!value.startsWith('i18n:')) return value;
  const key = value.slice(5);
  return t?.(key, key) ?? key;
}

function resourceDisplayName(resource?: Partial<ResourceRelevantVo>, t?: Translate) {
  if (!resource) return '-';
  return resolveI18nText(resource.alias || resource.name || resource.label || resource.title || resource.code || resource.id || '-', t);
}

function renderResourceIcon(resource?: Partial<ResourceRelevantVo>) {
  if (!resource?.icon) return null;
  const icon = createIcon(resource.icon);
  if (!icon) return null;
  return (
    <span style={{display: 'inline-flex', alignItems: 'center', color: 'var(--ant-color-text-secondary)'}}>
      {icon}
    </span>
  );
}

function resourceTypeLabel(resource: Partial<ResourceRelevantVo>, t: Translate) {
  return resource.type ? t(`resources.type.${resource.type}`, resource.type) : '-';
}

function rememberResources(
  items: ResourceRelevantVo[],
  setResourceByCode: Dispatch<SetStateAction<Map<string, ResourceRelevantVo>>>,
) {
  if (!items.length) return;
  setResourceByCode((previous) => {
    const next = new Map(previous);
    items.forEach((item) => {
      if (item.code) {
        next.set(item.code, item);
      }
    });
    return next;
  });
}

function toTreeNode(resource: ResourceRelevantVo, t: Translate): ResourceTreeNode {
  const key = resourceKey(resource);
  const icon = renderResourceIcon(resource);
  return {
    key,
    isLeaf: !resource.hasChildren,
    disableCheckbox: resource.disabled === true || ((!resource.code || resource.grantable === false) && !resource.hasChildren),
    resource,
    title: (
      <Space size={8} wrap>
        {icon}
        <Typography.Text>{resourceDisplayName(resource, t)}</Typography.Text>
        <Tag color={TYPE_COLOR[resource.type] ?? 'default'}>
          {resourceTypeLabel(resource, t)}
        </Tag>
        {resource.code ? <Typography.Text type="secondary" code>{resource.code}</Typography.Text> : null}
        {resource.grantable === false ? (
          <Tag color="default">{t('resources.boolean.notGrantable', '仅结构')}</Tag>
        ) : null}
      </Space>
    ),
  };
}

function toAssignedTreeNode(
  resource: ResourceRelevantVo,
  t: Translate,
  onRemove: (node: ResourceTreeNode) => void,
): ResourceTreeNode {
  const key = resourceKey(resource);
  const code = assignableCode(resource);
  const assigned = resource.checked === true && !!code;
  const icon = renderResourceIcon(resource);
  const node: ResourceTreeNode = {
    key,
    isLeaf: !resource.hasChildren,
    disableCheckbox: true,
    resource,
    title: (
      <Space size={8} wrap>
        {icon}
        <Typography.Text>{resourceDisplayName(resource, t)}</Typography.Text>
        <Tag color={TYPE_COLOR[resource.type] ?? 'default'}>
          {resourceTypeLabel(resource, t)}
        </Tag>
        {resource.code ? <Typography.Text type="secondary" code>{resource.code}</Typography.Text> : null}
        {assigned ? (
          <Button
            size="small"
            danger
            type="text"
            icon={<DeleteOutlined />}
            title={t('common.delete', '删除')}
            aria-label={t('common.delete', '删除')}
            onClick={(event) => {
              event.stopPropagation();
              onRemove(node);
            }}
          />
        ) : (
          <Tag color="default">{t('applications.resourceConfig.treeAncestor', '父级')}</Tag>
        )}
      </Space>
    ),
  };
  return node;
}

function toChildTreeNodes(
  page: {content?: ResourceRelevantVo[]; page?: {number?: number; totalPages?: number}},
  parent: ResourceTreeNode,
  t: Translate,
  nodeFactory: (resource: ResourceRelevantVo, t: Translate) => ResourceTreeNode = toTreeNode,
): ResourceTreeNode[] {
  const content = page.content ?? [];
  const nodes = content.map((item) => nodeFactory(item, t));
  const currentPage = page.page?.number ?? 0;
  const totalPages = page.page?.totalPages ?? 0;
  if (parent.resource?.id && currentPage + 1 < totalPages) {
    nodes.push({
      key: `${LOAD_MORE_PREFIX}:${parent.key}:${currentPage + 1}`,
      isLeaf: true,
      selectable: true,
      disableCheckbox: true,
      loadMore: {
        parentId: parent.resource.id,
        parentKey: parent.key,
        page: currentPage + 1,
      },
      title: <Typography.Link>{t('table.loadMore', '加载更多')}</Typography.Link>,
    });
  }
  return nodes;
}

function replaceNodeChildren(nodes: ResourceTreeNode[], key: Key, children: ResourceTreeNode[]): ResourceTreeNode[] {
  return nodes.map((node) => {
    if (node.key === key) {
      return {...node, children, isLeaf: children.length === 0};
    }
    if (node.children?.length) {
      return {...node, children: replaceNodeChildren(node.children as ResourceTreeNode[], key, children)};
    }
    return node;
  });
}

function appendNodeChildren(nodes: ResourceTreeNode[], key: Key, children: ResourceTreeNode[]): ResourceTreeNode[] {
  return nodes.map((node) => {
    if (node.key === key) {
      const currentChildren = ((node.children ?? []) as ResourceTreeNode[]).filter((child) => !child.loadMore);
      return {...node, children: [...currentChildren, ...children], isLeaf: false};
    }
    if (node.children?.length) {
      return {...node, children: appendNodeChildren(node.children as ResourceTreeNode[], key, children)};
    }
    return node;
  });
}

function uniqueCodes(codes: string[]) {
  return Array.from(new Set(codes.filter(Boolean)));
}

function collectLoadedSubtreeCodes(node: ResourceTreeNode) {
  const codes = new Set<string>();
  const visit = (current: ResourceTreeNode) => {
    if (current.loadMore) return;
    const code = assignableCode(current.resource);
    if (code) {
      codes.add(code);
    }
    (current.children ?? []).forEach((child) => visit(child));
  };
  visit(node);
  return Array.from(codes);
}

function findAncestorCodes(nodes: ResourceTreeNode[], targetKey: Key) {
  const target = String(targetKey);
  const visit = (items: ResourceTreeNode[], ancestors: string[]): string[] | null => {
    for (const item of items) {
      if (String(item.key) === target) {
        return ancestors;
      }
      const code = assignableCode(item.resource);
      const nextAncestors = code ? [...ancestors, code] : ancestors;
      const found = visit(item.children ?? [], nextAncestors);
      if (found) return found;
    }
    return null;
  };
  return visit(nodes, []) ?? [];
}

function findCodesByKeys(nodes: ResourceTreeNode[], keys: Key[]) {
  const targetKeys = new Set(keys.map((key) => String(key)));
  const codes = new Set<string>();
  const visit = (items: ResourceTreeNode[]) => {
    items.forEach((item) => {
      if (!item.loadMore && targetKeys.has(String(item.key))) {
        const code = assignableCode(item.resource);
        if (code) {
          codes.add(code);
        }
      }
      visit(item.children ?? []);
    });
  };
  visit(nodes);
  return Array.from(codes);
}

const ResourceTreeTransfer = ({
  enabled = true,
  reloadKey,
  fetchAssignedCodes,
  onAssign,
  onUnassign,
}: ResourceTreeTransferProps) => {
  const {t, ensure, locale} = useI18n();
  const [treeData, setTreeData] = useState<ResourceTreeNode[]>([]);
  const [assignedTreeData, setAssignedTreeData] = useState<ResourceTreeNode[]>([]);
  const treeDataRef = useRef<ResourceTreeNode[]>([]);
  const assignedTreeDataRef = useRef<ResourceTreeNode[]>([]);
  const [expandedKeys, setExpandedKeys] = useState<Key[]>([]);
  const [loadedKeys, setLoadedKeys] = useState<Key[]>([]);
  const [assignedExpandedKeys, setAssignedExpandedKeys] = useState<Key[]>([]);
  const [assignedLoadedKeys, setAssignedLoadedKeys] = useState<Key[]>([]);
  const [, setResourceByCode] = useState<Map<string, ResourceRelevantVo>>(new Map());
  const [assignedCodes, setAssignedCodes] = useState<string[]>([]);
  const [rootPage, setRootPage] = useState(1);
  const [rootTotal, setRootTotal] = useState(0);
  const [assignedRootPage, setAssignedRootPage] = useState(1);
  const [assignedRootTotal, setAssignedRootTotal] = useState(0);
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [treeLoading, setTreeLoading] = useState(false);
  const [assignedLoading, setAssignedLoading] = useState(false);
  const [assignedTreeLoading, setAssignedTreeLoading] = useState(false);
  const [treeSaving, setTreeSaving] = useState(false);
  const [loadError, setLoadError] = useState(false);

  useEffect(() => {
    void ensure(['resources', 'table', 'common', 'applications', 'access-center']);
  }, [ensure, locale]);

  useEffect(() => {
    treeDataRef.current = treeData;
  }, [treeData]);

  useEffect(() => {
    assignedTreeDataRef.current = assignedTreeData;
  }, [assignedTreeData]);

  const loadAssignedCodes = useCallback(async () => {
    if (!enabled) {
      setAssignedCodes([]);
      setAssignedTreeData([]);
      setAssignedRootTotal(0);
      setAssignedExpandedKeys([]);
      setAssignedLoadedKeys([]);
      return;
    }
    setAssignedLoading(true);
    try {
      const codes = await fetchAssignedCodes();
      setAssignedCodes(Array.from(new Set((codes ?? []).filter(Boolean))));
      setAssignedRootPage(1);
      setAssignedTreeData([]);
      setAssignedRootTotal(0);
      setAssignedExpandedKeys([]);
      setAssignedLoadedKeys([]);
      setLoadError(false);
    } catch {
      setLoadError(true);
      message.error(t('applications.resourceConfig.loadFailed', '应用资源加载失败'));
    } finally {
      setAssignedLoading(false);
    }
  }, [enabled, fetchAssignedCodes, t]);

  const loadRoot = useCallback(async () => {
    if (!enabled) {
      setTreeData([]);
      setRootTotal(0);
      setExpandedKeys([]);
      setLoadedKeys([]);
      return;
    }
    setTreeLoading(true);
    try {
      const page = await fetchChildren({
        page: String(rootPage - 1),
        size: String(TREE_PAGE_SIZE),
        sort: 'sort,asc',
        keyword,
      });
      rememberResources(page.content ?? [], setResourceByCode);
      setTreeData((page.content ?? []).map((item) => toTreeNode(item, t)));
      setRootTotal(page.page.totalElements ?? 0);
      setExpandedKeys([]);
      setLoadedKeys([]);
      setLoadError(false);
    } catch {
      setLoadError(true);
      message.error(t('applications.resourceConfig.loadFailed', '应用资源加载失败'));
    } finally {
      setTreeLoading(false);
    }
  }, [enabled, keyword, rootPage, t]);

  useEffect(() => {
    void loadAssignedCodes();
  }, [loadAssignedCodes, reloadKey]);

  useEffect(() => {
    void loadRoot();
  }, [loadRoot, reloadKey]);

  const loadChildren = useCallback(async (node: ResourceTreeNode) => {
    const resource = node.resource;
    if (!resource?.id || !resource.hasChildren || node.children?.length) return;
    setTreeLoading(true);
    try {
      const page = await fetchChildren({
        parentId: resource.id,
        page: '0',
        size: String(TREE_PAGE_SIZE),
        sort: 'sort,asc',
      });
      rememberResources(page.content ?? [], setResourceByCode);
      setTreeData((previous) => replaceNodeChildren(
        previous,
        node.key,
        toChildTreeNodes(page, node, t),
      ));
    } catch {
      message.error(t('applications.resourceConfig.loadFailed', '应用资源加载失败'));
    } finally {
      setTreeLoading(false);
    }
  }, [t]);

  const loadMoreChildren = useCallback(async (node: ResourceTreeNode) => {
    if (!node.loadMore) return;
    setTreeLoading(true);
    try {
      const page = await fetchChildren({
        parentId: node.loadMore.parentId,
        page: String(node.loadMore.page),
        size: String(TREE_PAGE_SIZE),
        sort: 'sort,asc',
      });
      rememberResources(page.content ?? [], setResourceByCode);
      const parentNode = {
        key: node.loadMore.parentKey,
        resource: {id: node.loadMore.parentId, code: node.loadMore.parentId, type: 'GROUP'} as ResourceRelevantVo,
      } as ResourceTreeNode;
      setTreeData((previous) => appendNodeChildren(
        previous,
        node.loadMore!.parentKey,
        toChildTreeNodes(page, parentNode, t),
      ));
    } catch {
      message.error(t('applications.resourceConfig.loadFailed', '应用资源加载失败'));
    } finally {
      setTreeLoading(false);
    }
  }, [t]);

  const resolveNodeCodes = useCallback(async (node: ResourceTreeNode) => {
    if (node.resource?.id && (node.resource.hasChildren || (node.children?.length ?? 0) > 0)) {
      const codes = await fetchSubtreeCodes(node.resource.id);
      return uniqueCodes(codes ?? []);
    }
    return collectLoadedSubtreeCodes(node);
  }, []);

  const updateAssignment = useCallback(async (node: ResourceTreeNode, checked: boolean, checkedTreeKeys: Key[] = []) => {
    const previous = assignedCodes;
    setTreeSaving(true);
    let codes: string[] = [];
    try {
      codes = await resolveNodeCodes(node);
      if (checked && checkedTreeKeys.length > 0) {
        codes = uniqueCodes([...codes, ...findCodesByKeys(treeDataRef.current, checkedTreeKeys)]);
      }
      if (!checked) {
        codes = uniqueCodes([...codes, ...findAncestorCodes([...treeDataRef.current, ...assignedTreeDataRef.current], node.key)]);
      }
      if (!codes.length) {
        return;
      }

      const next = checked
        ? uniqueCodes([...assignedCodes, ...codes])
        : assignedCodes.filter((item) => !codes.includes(item));

      setAssignedCodes(next);
      if (checked) {
        await onAssign(codes);
      } else {
        await onUnassign(codes);
      }
      setLoadError(false);
      message.success(t('applications.resourceConfig.updateSuccess', '应用资源已更新'));
    } catch {
      setAssignedCodes(previous);
      message.error(t('applications.resourceConfig.updateFailed', '应用资源更新失败'));
    } finally {
      setTreeSaving(false);
    }
  }, [assignedCodes, onAssign, onUnassign, resolveNodeCodes, t]);

  const handleCheck = useCallback((keys: unknown, info: {checked?: boolean; node?: ResourceTreeNode}) => {
    if (!info.node?.resource) return;
    const checkedTreeKeys = Array.isArray(keys) ? keys : [];
    void updateAssignment(info.node, info.checked !== false, checkedTreeKeys);
  }, [updateAssignment]);

  const handleSelect = useCallback((_: Key[], info: {node?: ResourceTreeNode}) => {
    if (info.node?.loadMore) {
      void loadMoreChildren(info.node);
    }
  }, [loadMoreChildren]);

  const handleAssignedRemove = useCallback((node: ResourceTreeNode) => {
    if (!node.resource) return;
    void updateAssignment(node, false);
  }, [updateAssignment]);

  const buildAssignedTreeNode = useCallback(
    (resource: ResourceRelevantVo, translate: Translate) => toAssignedTreeNode(resource, translate, handleAssignedRemove),
    [handleAssignedRemove],
  );

  const loadAssignedTreeRoot = useCallback(async () => {
    if (!enabled || assignedCodes.length === 0) {
      setAssignedTreeData([]);
      setAssignedRootTotal(0);
      setAssignedExpandedKeys([]);
      setAssignedLoadedKeys([]);
      return;
    }
    setAssignedTreeLoading(true);
    try {
      const page = await fetchAssignedTree(assignedCodes, {
        page: String(assignedRootPage - 1),
        size: String(ASSIGNED_TREE_PAGE_SIZE),
        sort: 'sort,asc',
      });
      rememberResources(page.content ?? [], setResourceByCode);
      setAssignedTreeData((page.content ?? []).map((item) => buildAssignedTreeNode(item, t)));
      setAssignedRootTotal(page.page.totalElements ?? 0);
      setAssignedExpandedKeys([]);
      setAssignedLoadedKeys([]);
      setLoadError(false);
    } catch {
      setLoadError(true);
      message.error(t('applications.resourceConfig.loadFailed', '应用资源加载失败'));
    } finally {
      setAssignedTreeLoading(false);
    }
  }, [assignedCodes, assignedRootPage, buildAssignedTreeNode, enabled, t]);

  const loadAssignedChildren = useCallback(async (node: ResourceTreeNode) => {
    const resource = node.resource;
    if (!resource?.id || !resource.hasChildren || node.children?.length || assignedCodes.length === 0) return;
    setAssignedTreeLoading(true);
    try {
      const page = await fetchAssignedTree(assignedCodes, {
        parentId: resource.id,
        page: '0',
        size: String(ASSIGNED_TREE_PAGE_SIZE),
        sort: 'sort,asc',
      });
      rememberResources(page.content ?? [], setResourceByCode);
      setAssignedTreeData((previous) => replaceNodeChildren(
        previous,
        node.key,
        toChildTreeNodes(page, node, t, buildAssignedTreeNode),
      ));
    } catch {
      message.error(t('applications.resourceConfig.loadFailed', '应用资源加载失败'));
    } finally {
      setAssignedTreeLoading(false);
    }
  }, [assignedCodes, buildAssignedTreeNode, t]);

  const loadMoreAssignedChildren = useCallback(async (node: ResourceTreeNode) => {
    if (!node.loadMore || assignedCodes.length === 0) return;
    setAssignedTreeLoading(true);
    try {
      const page = await fetchAssignedTree(assignedCodes, {
        parentId: node.loadMore.parentId,
        page: String(node.loadMore.page),
        size: String(ASSIGNED_TREE_PAGE_SIZE),
        sort: 'sort,asc',
      });
      rememberResources(page.content ?? [], setResourceByCode);
      const parentNode = {
        key: node.loadMore.parentKey,
        resource: {id: node.loadMore.parentId, code: node.loadMore.parentId, type: 'GROUP'} as ResourceRelevantVo,
      } as ResourceTreeNode;
      setAssignedTreeData((previous) => appendNodeChildren(
        previous,
        node.loadMore!.parentKey,
        toChildTreeNodes(page, parentNode, t, buildAssignedTreeNode),
      ));
    } catch {
      message.error(t('applications.resourceConfig.loadFailed', '应用资源加载失败'));
    } finally {
      setAssignedTreeLoading(false);
    }
  }, [assignedCodes, buildAssignedTreeNode, t]);

  const handleAssignedSelect = useCallback((_: Key[], info: {node?: ResourceTreeNode}) => {
    if (info.node?.loadMore) {
      void loadMoreAssignedChildren(info.node);
    }
  }, [loadMoreAssignedChildren]);

  useEffect(() => {
    void loadAssignedTreeRoot();
  }, [loadAssignedTreeRoot, reloadKey]);

  const refreshLoading = treeLoading || assignedLoading || assignedTreeLoading || treeSaving;
  const leftDisabled = treeLoading || assignedLoading || treeSaving;
  const assignedDisabled = assignedLoading || assignedTreeLoading || treeSaving;

  if (!enabled) {
    return <div style={{flex: 1, minHeight: 0}}/>;
  }

  return (
    <div style={{height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column', gap: 12}}>
      <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12}}>
        <div>
          <Typography.Text strong>{t('applications.resourceConfig.title', '应用资源')}</Typography.Text>
          <div style={{fontSize: 12, color: 'var(--ant-color-text-tertiary)'}}>
            {t('applications.resourceConfig.selectedCount', '已分配 {count} 项资源', {count: assignedCodes.length})}
          </div>
        </div>
        <Button
          icon={<ReloadOutlined />}
          loading={refreshLoading}
          onClick={() => {
            void loadAssignedCodes();
            void loadRoot();
          }}
        >
          {t('common.refresh', '刷新')}
        </Button>
      </div>

      {loadError ? (
        <Alert
          type="error"
          showIcon
          message={t('applications.resourceConfig.loadFailed', '应用资源加载失败')}
          action={
            <Button size="small" onClick={() => {
              void loadAssignedCodes();
              void loadRoot();
            }}>
              {t('table.retry', '重试')}
            </Button>
          }
        />
      ) : null}

      <div style={{display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(320px, 100%), 1fr))', gap: 12, flex: 1, minHeight: 0, overflow: 'auto'}}>
        <section style={{display: 'flex', flexDirection: 'column', minHeight: 0, border: '1px solid var(--ant-color-border-secondary)', borderRadius: 6, background: 'var(--ant-color-bg-container)'}}>
          <div style={{padding: 12, borderBottom: '1px solid var(--ant-color-border-secondary)'}}>
            <Input.Search
              allowClear
              prefix={<SearchOutlined />}
              placeholder={t('accessCenter.resource.search', '搜索资源编码、路径或名称')}
              value={keywordInput}
              onChange={(event) => {
                const value = event.target.value;
                setKeywordInput(value);
                if (!value.trim() && keyword) {
                  setKeyword('');
                  setRootPage(1);
                }
              }}
              onSearch={(value) => {
                setKeyword(value.trim());
                setRootPage(1);
              }}
            />
          </div>
          <div style={{flex: 1, minHeight: 0, overflow: 'auto', padding: 12}}>
            {treeLoading && treeData.length === 0 ? (
              <div style={{height: 180, display: 'flex', alignItems: 'center', justifyContent: 'center'}}><Spin/></div>
            ) : treeData.length ? (
              <Tree
                checkable
                blockNode
                treeData={treeData}
                checkedKeys={assignedCodes}
                expandedKeys={expandedKeys}
                loadedKeys={loadedKeys}
                disabled={leftDisabled}
                loadData={(node) => loadChildren(node as ResourceTreeNode)}
                onCheck={handleCheck}
                onExpand={(keys) => setExpandedKeys(keys)}
                onLoad={(keys) => setLoadedKeys(keys)}
                onSelect={handleSelect}
              />
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('accessCenter.resource.empty', '暂无资源')}/>
            )}
          </div>
          <div style={{padding: 12, borderTop: '1px solid var(--ant-color-border-secondary)', display: 'flex', justifyContent: 'flex-end'}}>
            <Pagination
              size="small"
              current={rootPage}
              pageSize={TREE_PAGE_SIZE}
              total={rootTotal}
              showSizeChanger={false}
              showTotal={(total) => t('table.total', '共 {total} 条', {total})}
              onChange={setRootPage}
            />
          </div>
        </section>

        <section style={{display: 'flex', flexDirection: 'column', minHeight: 0, border: '1px solid var(--ant-color-border-secondary)', borderRadius: 6, background: 'var(--ant-color-bg-container)'}}>
          <div style={{padding: 12, borderBottom: '1px solid var(--ant-color-border-secondary)'}}>
            <Typography.Text strong>{t('applications.resourceConfig.assignedTitle', '已分配资源')}</Typography.Text>
          </div>
          <div style={{flex: 1, minHeight: 0, overflow: 'auto', padding: 12}}>
            {(assignedLoading || assignedTreeLoading) && assignedTreeData.length === 0 ? (
              <div style={{height: 180, display: 'flex', alignItems: 'center', justifyContent: 'center'}}><Spin/></div>
            ) : assignedTreeData.length ? (
              <Tree
                blockNode
                virtual
                height={360}
                treeData={assignedTreeData}
                expandedKeys={assignedExpandedKeys}
                loadedKeys={assignedLoadedKeys}
                disabled={assignedDisabled}
                loadData={(node) => loadAssignedChildren(node as ResourceTreeNode)}
                onExpand={(keys) => setAssignedExpandedKeys(keys)}
                onLoad={(keys) => setAssignedLoadedKeys(keys)}
                onSelect={handleAssignedSelect}
              />
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('accessCenter.resource.emptyResources', '暂无可授权资源')}/>
            )}
          </div>
          <div style={{padding: 12, borderTop: '1px solid var(--ant-color-border-secondary)', display: 'flex', justifyContent: 'flex-end'}}>
            <Pagination
              size="small"
              current={assignedRootPage}
              pageSize={ASSIGNED_TREE_PAGE_SIZE}
              total={assignedRootTotal}
              showSizeChanger={false}
              showTotal={(total) => t('table.total', '共 {total} 条', {total})}
              onChange={setAssignedRootPage}
            />
          </div>
        </section>
      </div>
    </div>
  );
};

export default ResourceTreeTransfer;
