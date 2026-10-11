import {resolveResourceLabel} from '@simplepoint/shared/i18n/resourceLabel';
import {useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState} from 'react';
import type {Key} from 'react';
import {Alert, Empty, Input, Pagination, Space, Spin, Tag, Tree, Typography} from 'antd';
import TableActions from '@simplepoint/components/Table/Actions';
import {DeleteOutlined, ReloadOutlined, SearchOutlined} from '@simplepoint/shared/types/icon';
import type {DataNode} from 'antd/es/tree';
import {createIcon} from '@simplepoint/shared/types/icon';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {fetchAssignedTree, fetchChildren, fetchSubtreeCodes, ResourceRelevantVo} from '@/api/system/resource';
import type {RequestOptions} from '@simplepoint/shared/api/client';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {reachableServerPage} from '@simplepoint/components/SimpleTable/pagination';
import {assignmentKey} from '@simplepoint/components/AssignmentTable/identity';
import {useOwnedReads} from '@simplepoint/shared/hooks/useOwnedReads';
import {assignmentDelta, loadedBranchKeys, retainRootChildren} from './resourceTreeState';

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
  fetchAssignedCodes: (options: RequestOptions) => Promise<string[]>;
  onAssign: (codes: string[], options: RequestOptions) => Promise<unknown>;
  onUnassign: (codes: string[], options: RequestOptions) => Promise<unknown>;
  onInteractionLockChange?: (locked: boolean) => void;
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

let presentationSequence = 0;
function resourceKey(resource: ResourceRelevantVo) {
  const code = assignmentKey(resource.code), id = assignmentKey(resource.id);
  return code !== undefined ? `resource:${code}` : id !== undefined ? `id:${id}` : `presentation:${++presentationSequence}`;
}

function assignableCode(resource?: ResourceRelevantVo) {
  if (!assignmentKey(resource?.code) || resource?.grantable !== true || resource?.disabled !== false) return null;
  return resource!.code;
}

function resourceDisplayName(resource?: Partial<ResourceRelevantVo>, t?: Translate) {
  if (!resource) return '-';
  return resolveResourceLabel(resource.alias || resource.name || resource.label || resource.title,
    resource.name || resource.label || resource.code || resource.id || '-', t ?? ((_key, fallback) => String(fallback ?? '-')));
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

function toTreeNode(resource: ResourceRelevantVo, t: Translate): ResourceTreeNode {
  const key = resourceKey(resource);
  const icon = renderResourceIcon(resource);
  return {
    key,
    isLeaf: !resource.hasChildren,
    disableCheckbox: resource.disabled !== false || !assignmentKey(resource.id) || ((!assignmentKey(resource.code) || resource.grantable !== true) && !resource.hasChildren),
    resource,
    title: (
      <Space size={8} wrap data-resource-id={assignmentKey(resource.id)}>
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
  disabled = false,
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
      <Space size={8} wrap data-resource-id={assignmentKey(resource.id)}>
        {icon}
        <Typography.Text>{resourceDisplayName(resource, t)}</Typography.Text>
        <Tag color={TYPE_COLOR[resource.type] ?? 'default'}>
          {resourceTypeLabel(resource, t)}
        </Tag>
        {resource.code ? <Typography.Text type="secondary" code>{resource.code}</Typography.Text> : null}
        {assigned ? (
          <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{
            key: 'remove', sort: 0, size: 'small', danger: true, disabled, type: 'text', icon: <DeleteOutlined/>, iconOnly: true,
            text: t('common.delete', '删除'), onClick: event => {
              event.stopPropagation();
              onRemove(node);
            },
          }]}/>
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
  if (assignmentKey(parent.resource?.id) && currentPage + 1 < totalPages) {
    nodes.push({
      key: `${LOAD_MORE_PREFIX}:${parent.key}:${currentPage + 1}`,
      isLeaf: true,
      selectable: true,
      disableCheckbox: true,
      loadMore: {
        parentId: parent.resource!.id,
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
  return Array.from(new Set(codes.filter(code => assignmentKey(code) !== undefined)));
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

type WriteIntent = {node: ResourceTreeNode; checked: boolean; relatedCodes: string[]; resolvedCodes?: string[]};

function ResourceTransferSession({enabled = true, reloadKey, fetchAssignedCodes, onAssign, onUnassign,
  onInteractionLockChange}: ResourceTreeTransferProps) {
  const {t, ensure, locale} = useI18n();
  const operation = useOperationScope(reloadKey);
  const reads = useOwnedReads(operation, enabled);
  const [treeData, setTreeData] = useState<ResourceTreeNode[]>([]);
  const [assignedTreeData, setAssignedTreeData] = useState<ResourceTreeNode[]>([]);
  const treeDataRef = useRef(treeData), assignedTreeDataRef = useRef(assignedTreeData);
  treeDataRef.current = treeData; assignedTreeDataRef.current = assignedTreeData;
  const [expandedKeys, setExpandedKeys] = useState<Key[]>([]);
  const [loadedKeys, setLoadedKeys] = useState<Key[]>([]);
  const [assignedExpandedKeys, setAssignedExpandedKeys] = useState<Key[]>([]);
  const [assignedLoadedKeys, setAssignedLoadedKeys] = useState<Key[]>([]);
  const [assignedCodes, setAssignedCodes] = useState<string[]>([]);
  const assignedCodesRef = useRef(assignedCodes); assignedCodesRef.current = assignedCodes;
  const [rootPage, setRootPage] = useState(1), [rootTotal, setRootTotal] = useState(0);
  const [assignedRootPage, setAssignedRootPage] = useState(1), [assignedRootTotal, setAssignedRootTotal] = useState(0);
  const [keywordInput, setKeywordInput] = useState(''), [keyword, setKeyword] = useState('');
  const [revision, setRevision] = useState(0);
  const [treeRevision, setTreeRevision] = useState(0);
  const treeContext = useRef(''), assignedContext = useRef('');
  const [writeError, setWriteError] = useState<string>(), [retryWrite, setRetryWrite] = useState<WriteIntent>();
  const inactive = !operation.selected;
  const treeSaving = operation.busy;
  const treeLoading = reads.loading.some(key => key.startsWith('tree'));
  const assignedLoading = reads.loading.includes('codes');
  const assignedTreeLoading = reads.loading.some(key => key.startsWith('assigned-tree'));
  const loadError = Object.keys(reads.errors).length > 0;
  const loadErrorRef = useRef(loadError); loadErrorRef.current = loadError;
  usePageRetention(!treeSaving, treeSaving);
  useEffect(() => {void ensure(['resources', 'table', 'common', 'applications']);}, [ensure, locale]);
  useLayoutEffect(() => {
    onInteractionLockChange?.(treeSaving);
    return () => onInteractionLockChange?.(false);
  }, [treeSaving, onInteractionLockChange]);

  const loadAssignedCodes = useCallback(() => reads.run('codes',
    signal => fetchAssignedCodes({signal, notifyOnError: false}),
    codes => {
      if (!Array.isArray(codes) || codes.some(code => assignmentKey(code) === undefined)) {
        throw new Error(t('permissions.unavailable', '资源标识暂不可用'));
      }
      const unique = uniqueCodes(codes), incoming = new Set(unique);
      setAssignedCodes(previous => previous.length === unique.length && previous.every(code => incoming.has(code)) ? previous : unique);
    }), [reads.run, fetchAssignedCodes, t]);
  const loadRoot = useCallback(() => {
    if (!enabled || !reads.active || !operation.current() || !operation.isSelected()) return Promise.resolve();
    const context = JSON.stringify([rootPage, keyword, revision, treeRevision, locale]);
    reads.cancel('tree');
    if (treeContext.current !== context) reads.clearErrors('tree');
    return reads.run('tree:root', signal => fetchChildren({
      page: String(rootPage - 1), size: String(TREE_PAGE_SIZE), sort: 'sort,asc', keyword,
    }, {signal, notifyOnError: false}), page => {
      const reachable = reachableServerPage(rootPage, page.page);
      if (page.page.number === rootPage - 1 && reachable !== rootPage) {setRootPage(reachable); return;}
      const nodes = (page.content ?? []).map(item => toTreeNode(item, t));
      const retained = treeContext.current === context;
      setTreeData(previous => retainRootChildren(previous, nodes, retained));
      treeContext.current = context;
      setRootTotal(page.page.totalElements ?? 0);
      if (!retained) {setExpandedKeys([]); setLoadedKeys([]);}
      else {
        const loaded = loadedBranchKeys(treeDataRef.current);
        setLoadedKeys(previous => previous.filter(key => loaded.has(key)));
      }
    });
  }, [enabled, reads.active, operation.current, operation.isSelected, reads.cancel, reads.clearErrors, reads.run, rootPage, keyword, revision, treeRevision, locale, t]);
  useEffect(() => {void loadAssignedCodes();}, [loadAssignedCodes, revision, operation.selected]);
  useEffect(() => {void loadRoot();}, [loadRoot, revision, operation.selected]);

  const loadChildren = useCallback((node: ResourceTreeNode) => {
    const resource = node.resource;
    if (!assignmentKey(resource?.id) || !resource?.hasChildren || node.children?.length) return Promise.resolve();
    return reads.run('tree:child:' + node.key + ':0', signal => fetchChildren({
      parentId: resource.id, page: '0', size: String(TREE_PAGE_SIZE), sort: 'sort,asc',
    }, {signal, notifyOnError: false}), page => setTreeData(previous => replaceNodeChildren(
      previous, node.key, toChildTreeNodes(page, node, t))));
  }, [reads.run, t]);
  const loadMoreChildren = useCallback((node: ResourceTreeNode) => {
    const more = node.loadMore;
    if (!more || !assignmentKey(more.parentId)) return Promise.resolve();
    return reads.run('tree:child:' + more.parentKey + ':' + more.page, signal => fetchChildren({
      parentId: more.parentId, page: String(more.page), size: String(TREE_PAGE_SIZE), sort: 'sort,asc',
    }, {signal, notifyOnError: false}), page => setTreeData(previous => appendNodeChildren(
      previous, more.parentKey, toChildTreeNodes(page, {key: more.parentKey,
        resource: {id: more.parentId, type: 'GROUP'} as ResourceRelevantVo}, t))));
  }, [reads.run, t]);

  const updateAssignment = useCallback(async (intent: WriteIntent) => {
    if (!enabled || reads.isBusy()) return;
    const request = operation.begin(); if (!request) return;
    onInteractionLockChange?.(true); setWriteError(undefined);
    reads.cancel();
    const captured: WriteIntent = {...intent, relatedCodes: [...intent.relatedCodes]};
    const baseline = [...assignedCodesRef.current];
    try {
      let codes = captured.resolvedCodes;
      if (!codes) {
        const resource = captured.node.resource;
        if (resource?.hasChildren && assignmentKey(resource.id)) {
          const resolved = await fetchSubtreeCodes(resource.id, {signal: request.signal, notifyOnError: false});
          if (!Array.isArray(resolved) || resolved.some(code => assignmentKey(code) === undefined)) {
            throw new Error(t('permissions.unavailable', '资源标识暂不可用'));
          }
          codes = uniqueCodes(resolved);
        } else codes = collectLoadedSubtreeCodes(captured.node);
        if (!operation.current(request.signal)) return;
        codes = assignmentDelta(baseline, uniqueCodes([...codes, ...captured.relatedCodes]), captured.checked);
        captured.resolvedCodes = codes;
      }
      if (!codes.length) return;
      const options = {signal: request.signal, notifyOnError: false};
      if (captured.checked) await onAssign([...codes], options); else await onUnassign([...codes], options);
      if (!operation.current(request.signal)) return;
      const affected = new Set(codes);
      setAssignedCodes(previous => captured.checked ? uniqueCodes([...previous, ...codes])
        : previous.filter(code => !affected.has(code)));
      setWriteError(undefined); setRetryWrite(undefined);
    } catch (error) {
      if (operation.current(request.signal)) {
        setRetryWrite(captured);
        setWriteError(resolveApiErrorMessage(error, t('applications.resourceConfig.updateFailed', '应用资源更新失败')));
      }
    } finally {
      if (operation.current(request.signal)) {
        operation.finish(request); onInteractionLockChange?.(false);
        // Reads remain paused in inactive owners; the next activation revalidates the same target.
        setRevision(value => value + 1);
      } else operation.finish(request);
    }
  }, [enabled, reads.isBusy, reads.cancel, operation.begin, operation.current, operation.finish,
    onAssign, onUnassign, onInteractionLockChange, t]);
  const handleCheck = useCallback((keys: unknown, info: {checked?: boolean; node?: ResourceTreeNode}) => {
    if (!info.node?.resource || loadError || reads.isBusy() || operation.isBusy()) return;
    const node = info.node, checked = info.checked !== false;
    const relatedCodes = checked ? findCodesByKeys(treeDataRef.current, Array.isArray(keys) ? keys : [])
      : findAncestorCodes([...treeDataRef.current, ...assignedTreeDataRef.current], node.key);
    void updateAssignment({node, checked, relatedCodes});
  }, [updateAssignment, loadError, reads.isBusy, operation.isBusy]);
  const handleSelect = useCallback((_: Key[], info: {node?: ResourceTreeNode}) => {
    if (info.node?.loadMore) void loadMoreChildren(info.node);
  }, [loadMoreChildren]);
  const handleAssignedRemove = useCallback((node: ResourceTreeNode) => {
    if (!node.resource || loadErrorRef.current || reads.isBusy() || operation.isBusy()) return;
    void updateAssignment({node, checked: false,
      relatedCodes: findAncestorCodes([...treeDataRef.current, ...assignedTreeDataRef.current], node.key)});
  }, [updateAssignment, reads.isBusy, operation.isBusy]);
  const buildAssignedTreeNode = useCallback(
    (resource: ResourceRelevantVo, translate: Translate) => toAssignedTreeNode(resource, translate, handleAssignedRemove),
    [handleAssignedRemove]);
  const loadAssignedTreeRoot = useCallback(() => {
    if (!enabled || !reads.active || !operation.current() || !operation.isSelected()) return Promise.resolve();
    const context = JSON.stringify([assignedCodes, assignedRootPage, revision, locale]);
    reads.cancel('assigned-tree');
    if (assignedContext.current !== context) reads.clearErrors('assigned-tree');
    if (assignedCodes.length === 0) {
      setAssignedTreeData([]); setAssignedRootTotal(0); setAssignedRootPage(1);
      setAssignedExpandedKeys([]); setAssignedLoadedKeys([]); return Promise.resolve();
    }
    return reads.run('assigned-tree:root', signal => fetchAssignedTree(assignedCodes, {
      page: String(assignedRootPage - 1), size: String(ASSIGNED_TREE_PAGE_SIZE), sort: 'sort,asc',
    }, {signal, notifyOnError: false}), page => {
      const reachable = reachableServerPage(assignedRootPage, page.page);
      if (page.page.number === assignedRootPage - 1 && reachable !== assignedRootPage) {setAssignedRootPage(reachable); return;}
      const nodes = (page.content ?? []).map(item => buildAssignedTreeNode(item, t));
      const retained = assignedContext.current === context;
      setAssignedTreeData(previous => retainRootChildren(previous, nodes, retained));
      assignedContext.current = context;
      setAssignedRootTotal(page.page.totalElements ?? 0);
      if (!retained) {setAssignedExpandedKeys([]); setAssignedLoadedKeys([]);}
      else setAssignedLoadedKeys([]);
    });
  }, [enabled, reads.active, operation.current, operation.isSelected, reads.cancel, reads.clearErrors, reads.run, assignedCodes, assignedRootPage, revision, locale, buildAssignedTreeNode, t]);
  useEffect(() => {void loadAssignedTreeRoot();}, [loadAssignedTreeRoot, revision, operation.selected]);
  const loadAssignedChildren = useCallback((node: ResourceTreeNode) => {
    const resource = node.resource;
    if (!assignmentKey(resource?.id) || !resource?.hasChildren || node.children?.length || !assignedCodes.length) return Promise.resolve();
    return reads.run('assigned-tree:child:' + node.key + ':0', signal => fetchAssignedTree(assignedCodes, {
      parentId: resource.id, page: '0', size: String(ASSIGNED_TREE_PAGE_SIZE), sort: 'sort,asc',
    }, {signal, notifyOnError: false}), page => setAssignedTreeData(previous => replaceNodeChildren(
      previous, node.key, toChildTreeNodes(page, node, t, buildAssignedTreeNode))));
  }, [assignedCodes, reads.run, buildAssignedTreeNode, t]);
  const loadMoreAssignedChildren = useCallback((node: ResourceTreeNode) => {
    const more = node.loadMore;
    if (!more || !assignmentKey(more.parentId) || !assignedCodes.length) return Promise.resolve();
    return reads.run('assigned-tree:child:' + more.parentKey + ':' + more.page, signal => fetchAssignedTree(assignedCodes, {
      parentId: more.parentId, page: String(more.page), size: String(ASSIGNED_TREE_PAGE_SIZE), sort: 'sort,asc',
    }, {signal, notifyOnError: false}), page => setAssignedTreeData(previous => appendNodeChildren(
      previous, more.parentKey, toChildTreeNodes(page, {key: more.parentKey,
        resource: {id: more.parentId, type: 'GROUP'} as ResourceRelevantVo}, t, buildAssignedTreeNode))));
  }, [assignedCodes, reads.run, buildAssignedTreeNode, t]);
  const handleAssignedSelect = useCallback((_: Key[], info: {node?: ResourceTreeNode}) => {
    if (info.node?.loadMore) void loadMoreAssignedChildren(info.node);
  }, [loadMoreAssignedChildren]);
  const refresh = () => {
    if (!operation.current() || !operation.isSelected() || operation.isBusy()) return;
    reads.cancel(); setRevision(value => value + 1);
  };
  const refreshLoading = treeLoading || assignedLoading || assignedTreeLoading || treeSaving;
  const leftDisabled = inactive || refreshLoading || loadError;
  const assignedDisabled = inactive || refreshLoading || loadError;
  const assignedDisplayTree = useMemo(() => {
    const decorate = (nodes: ResourceTreeNode[]): ResourceTreeNode[] => nodes.map(node => ({
      ...node, ...(node.resource ? {title: toAssignedTreeNode(node.resource, t, handleAssignedRemove, assignedDisabled).title} : {}),
      ...(node.children ? {children: decorate(node.children)} : {}),
    }));
    return decorate(assignedTreeData);
  }, [assignedTreeData, t, handleAssignedRemove, assignedDisabled]);

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
        <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'refresh', sort: 0,
          icon: <ReloadOutlined/>, loading: refreshLoading, disabled: inactive || treeSaving, text: t('common.refresh', '刷新'), onClick: () => {
            refresh();
          },
        }]}/>
      </div>

      {loadError ? (
        <Alert
          type="error"
          showIcon
          className="application-resource-load-error"
          title={t('applications.resourceConfig.loadFailed', '应用资源加载失败')}
          description={Object.values(reads.errors).map(error => resolveApiErrorMessage(error, t('applications.resourceConfig.loadFailed', '应用资源加载失败'))).join('；')}
          action={
            <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0, size: 'small',
              disabled: refreshLoading || inactive, text: t('table.retry', '重试'), onClick: () => {
              refresh();
            }}]}/>
          }
        />
      ) : null}

      {writeError && <Alert className="application-resource-write-error" type="error" showIcon
        title={t('applications.resourceConfig.updateFailed', '应用资源更新失败')} description={writeError}
        action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry-write', sort: 0, size: 'small',
          disabled: inactive || refreshLoading || loadError, text: t('table.retry', '重试'),
          onClick: () => {if (retryWrite) void updateAssignment(retryWrite);}}]}/>}/>}
      <div style={{display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(320px, 100%), 1fr))', gap: 12, flex: 1, minHeight: 0, overflow: 'auto'}}>
        <section style={{display: 'flex', flexDirection: 'column', minHeight: 0, border: '1px solid var(--ant-color-border-secondary)', borderRadius: 6, background: 'var(--ant-color-bg-container)'}}>
          <div style={{padding: 12, borderBottom: '1px solid var(--ant-color-border-secondary)'}}>
            <Input.Search
              disabled={inactive || treeSaving}
              allowClear
              prefix={<SearchOutlined />}
              placeholder={t('resources.tree.search', '搜索资源编码、路径或名称')}
              value={keywordInput}
              onChange={(event) => {
                const value = event.target.value;
                if (!operation.current() || !operation.isSelected() || operation.isBusy()) return;
                setKeywordInput(value);
                if (!value.trim() && keyword) {
                  reads.cancel('tree');
                  setKeyword('');
                  setRootPage(1);
                }
              }}
              onSearch={(value) => {
                if (!operation.current() || !operation.isSelected() || operation.isBusy()) return;
                reads.cancel('tree');
                setKeyword(value.trim());
                setRootPage(1);
                setTreeRevision(previous => previous + 1);
              }}
            />
          </div>
          <div style={{flex: 1, minHeight: 0, overflow: 'auto', padding: 12}}>
            {treeLoading && treeData.length === 0 ? (
              <div style={{height: 180, display: 'flex', alignItems: 'center', justifyContent: 'center'}}><Spin/></div>
            ) : treeData.length ? (
              <Tree
                checkable
                virtual
                height={360}
                blockNode
                treeData={treeData}
                checkedKeys={assignedCodes.map(code => `resource:${code}`)}
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
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('resources.tree.empty', '暂无资源')}/>
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
              disabled={inactive || treeSaving}
              onChange={next => {if (!operation.isSelected() || operation.isBusy()) return; reads.cancel('tree'); setRootPage(next);}}
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
                treeData={assignedDisplayTree}
                expandedKeys={assignedExpandedKeys}
                loadedKeys={assignedLoadedKeys}
                disabled={assignedDisabled}
                loadData={(node) => loadAssignedChildren(node as ResourceTreeNode)}
                onExpand={(keys) => setAssignedExpandedKeys(keys)}
                onLoad={(keys) => setAssignedLoadedKeys(keys)}
                onSelect={handleAssignedSelect}
              />
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('resources.tree.emptyResources', '暂无可授权资源')}/>
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
              disabled={inactive || treeSaving}
              onChange={next => {if (!operation.isSelected() || operation.isBusy()) return; reads.cancel('assigned-tree'); setAssignedRootPage(next);}}
            />
          </div>
        </section>
      </div>
    </div>
  );
};

export default function ResourceTreeTransfer(props: ResourceTreeTransferProps) {
  return <ResourceTransferSession key={JSON.stringify([useQueryScope(), props.reloadKey])} {...props}/>;
}
