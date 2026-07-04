import {useCallback, useEffect, useMemo, useState} from 'react';
import type {Dispatch, Key, SetStateAction} from 'react';
import {Alert, Button, Empty, Input, Pagination, Space, Spin, Tag, Tree, Typography, message} from 'antd';
import {DeleteOutlined, ReloadOutlined, SearchOutlined} from '@ant-design/icons';
import type {DataNode} from 'antd/es/tree';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {fetchByCodes, fetchChildren, ResourceRelevantVo} from '@/api/system/resource';

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
const SELECTED_PAGE_SIZE = 12;
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

function resourceDisplayName(resource?: Partial<ResourceRelevantVo>) {
  if (!resource) return '-';
  return resource.alias || resource.name || resource.label || resource.title || resource.code || resource.id || '-';
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
  return {
    key,
    isLeaf: !resource.hasChildren,
    disableCheckbox: resource.grantable === false || !resource.code,
    resource,
    title: (
      <Space size={8} wrap>
        <Typography.Text>{resourceDisplayName(resource)}</Typography.Text>
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

function toChildTreeNodes(
  page: {content?: ResourceRelevantVo[]; page?: {number?: number; totalPages?: number}},
  parent: ResourceTreeNode,
  t: Translate,
): ResourceTreeNode[] {
  const content = page.content ?? [];
  const nodes = content.map((item) => toTreeNode(item, t));
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

const ResourceTreeTransfer = ({
  enabled = true,
  reloadKey,
  fetchAssignedCodes,
  onAssign,
  onUnassign,
}: ResourceTreeTransferProps) => {
  const {t, ensure, locale} = useI18n();
  const [treeData, setTreeData] = useState<ResourceTreeNode[]>([]);
  const [resourceByCode, setResourceByCode] = useState<Map<string, ResourceRelevantVo>>(new Map());
  const [assignedCodes, setAssignedCodes] = useState<string[]>([]);
  const [rootPage, setRootPage] = useState(1);
  const [rootTotal, setRootTotal] = useState(0);
  const [selectedPage, setSelectedPage] = useState(1);
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [treeLoading, setTreeLoading] = useState(false);
  const [assignedLoading, setAssignedLoading] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [savingCodes, setSavingCodes] = useState<Set<string>>(new Set());
  const [loadError, setLoadError] = useState(false);

  useEffect(() => {
    void ensure(['resources', 'table', 'common', 'applications', 'access-center']);
  }, [ensure, locale]);

  const loadAssignedCodes = useCallback(async () => {
    if (!enabled) {
      setAssignedCodes([]);
      return;
    }
    setAssignedLoading(true);
    try {
      const codes = await fetchAssignedCodes();
      setAssignedCodes(Array.from(new Set((codes ?? []).filter(Boolean))));
      setSelectedPage(1);
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

  const selectedPageCodes = useMemo(() => {
    const start = (selectedPage - 1) * SELECTED_PAGE_SIZE;
    return assignedCodes.slice(start, start + SELECTED_PAGE_SIZE);
  }, [assignedCodes, selectedPage]);

  useEffect(() => {
    const missingCodes = selectedPageCodes.filter((code) => !resourceByCode.has(code));
    if (!missingCodes.length) return;
    let cancelled = false;
    setDetailLoading(true);
    fetchByCodes(missingCodes)
      .then((items) => {
        if (!cancelled) {
          rememberResources(items ?? [], setResourceByCode);
        }
      })
      .catch(() => message.error(t('applications.resourceConfig.loadFailed', '应用资源加载失败')))
      .finally(() => {
        if (!cancelled) {
          setDetailLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [resourceByCode, selectedPageCodes, t]);

  useEffect(() => {
    const totalPages = Math.max(1, Math.ceil(assignedCodes.length / SELECTED_PAGE_SIZE));
    if (selectedPage > totalPages) {
      setSelectedPage(totalPages);
    }
  }, [assignedCodes.length, selectedPage]);

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

  const updateAssignment = useCallback(async (resource: ResourceRelevantVo, checked: boolean) => {
    if (!resource.code || resource.grantable === false) return;
    const code = resource.code;
    const previous = assignedCodes;
    const next = checked
      ? Array.from(new Set([...assignedCodes, code]))
      : assignedCodes.filter((item) => item !== code);

    setAssignedCodes(next);
    setSavingCodes((current) => new Set(current).add(code));
    try {
      if (checked) {
        await onAssign([code]);
      } else {
        await onUnassign([code]);
      }
      setLoadError(false);
      message.success(t('applications.resourceConfig.updateSuccess', '应用资源已更新'));
    } catch {
      setAssignedCodes(previous);
      message.error(t('applications.resourceConfig.updateFailed', '应用资源更新失败'));
    } finally {
      setSavingCodes((current) => {
        const nextSaving = new Set(current);
        nextSaving.delete(code);
        return nextSaving;
      });
    }
  }, [assignedCodes, onAssign, onUnassign, t]);

  const handleCheck = useCallback((_: unknown, info: {checked?: boolean; node?: ResourceTreeNode}) => {
    const resource = info.node?.resource;
    if (!resource) return;
    void updateAssignment(resource, info.checked !== false);
  }, [updateAssignment]);

  const handleSelect = useCallback((_: Key[], info: {node?: ResourceTreeNode}) => {
    if (info.node?.loadMore) {
      void loadMoreChildren(info.node);
    }
  }, [loadMoreChildren]);

  const handleRemove = useCallback((code: string) => {
    const resource = resourceByCode.get(code) ?? ({id: code, code, type: 'ACTION'} as ResourceRelevantVo);
    void updateAssignment(resource, false);
  }, [resourceByCode, updateAssignment]);

  const selectedRows = selectedPageCodes.map((code) => resourceByCode.get(code) ?? ({id: code, code} as Partial<ResourceRelevantVo>));
  const loading = treeLoading || assignedLoading;

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
          loading={loading}
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
              onChange={(event) => setKeywordInput(event.target.value)}
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
                checkStrictly
                blockNode
                treeData={treeData}
                checkedKeys={{checked: assignedCodes, halfChecked: []}}
                disabled={assignedLoading}
                loadData={(node) => loadChildren(node as ResourceTreeNode)}
                onCheck={handleCheck}
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
            {assignedLoading || detailLoading ? <Spin/> : null}
            {selectedRows.length ? (
              <Space direction="vertical" size={8} style={{width: '100%'}}>
                {selectedRows.map((item) => (
                  <div
                    key={item.code || item.id}
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      gap: 8,
                      padding: '8px 10px',
                      border: '1px solid var(--ant-color-border-secondary)',
                      borderRadius: 6,
                    }}
                  >
                    <div style={{minWidth: 0}}>
                      <div style={{display: 'flex', alignItems: 'center', gap: 8, minWidth: 0}}>
                        <Typography.Text ellipsis style={{maxWidth: 210}}>{resourceDisplayName(item)}</Typography.Text>
                        {item.type ? (
                          <Tag color={TYPE_COLOR[item.type] ?? 'default'}>{resourceTypeLabel(item, t)}</Tag>
                        ) : null}
                      </div>
                      {item.code ? <Typography.Text type="secondary" code>{item.code}</Typography.Text> : null}
                    </div>
                    {item.code ? (
                      <Button
                        size="small"
                        danger
                        type="text"
                        icon={<DeleteOutlined />}
                        title={t('common.delete', '删除')}
                        aria-label={t('common.delete', '删除')}
                        loading={savingCodes.has(item.code)}
                        onClick={() => handleRemove(item.code!)}
                      />
                    ) : null}
                  </div>
                ))}
              </Space>
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('accessCenter.resource.emptyResources', '暂无可授权资源')}/>
            )}
          </div>
          <div style={{padding: 12, borderTop: '1px solid var(--ant-color-border-secondary)', display: 'flex', justifyContent: 'flex-end'}}>
            <Pagination
              size="small"
              current={selectedPage}
              pageSize={SELECTED_PAGE_SIZE}
              total={assignedCodes.length}
              showSizeChanger={false}
              onChange={setSelectedPage}
            />
          </div>
        </section>
      </div>
    </div>
  );
};

export default ResourceTreeTransfer;
