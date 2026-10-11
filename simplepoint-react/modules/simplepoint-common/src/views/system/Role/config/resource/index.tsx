import {resolveResourceLabel} from '@simplepoint/shared/i18n/resourceLabel';
import {useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState} from 'react';
import {ApiOutlined, AppstoreOutlined, DownOutlined, FileOutlined, FolderOutlined, ReloadOutlined, RightOutlined, SearchOutlined} from '@ant-design/icons';
import {Alert, Checkbox, Empty, Input, Modal, Pagination, Select, Spin, Typography} from 'antd';
import TableActions from '@simplepoint/components/Table/Actions';
import {useOwnedData} from '@simplepoint/shared/hooks/useOwnedData';
import {useOwnedReads} from '@simplepoint/shared/hooks/useOwnedReads';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {usePageActive} from '@simplepoint/shared/page/hooks';
import {reachableServerPage} from '@simplepoint/components/SimpleTable/pagination';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {createIcon} from '@simplepoint/shared/types/icon';
import {fetchRoleResourceContext, fetchRoleResources, fetchRoleResourceSubtreeRows, saveRoleResourceChanges} from '@/api/system/role-resource-configuration';
import type {RoleResourceRow} from '@/api/system/role-resource-configuration';
import {displayedGrant, displayedPolicy, MAX_RESOURCE_CHANGES, resourceChanges, setResourceGrant, setResourceGrants, setResourcePolicy} from './resourceDraft';
import type {ResourceDraft} from './resourceDraft';
import {ResourcePreview} from './ResourcePreview';
import PolicySelect from './PolicySelect';
import ResourceChildren from './ResourceChildren';
import {loadResourcePreview, PreviewChangedError} from './previewLoader';
import './index.css';

export interface EditorState {dirty: boolean; saving: boolean; locked: boolean}
export interface RoleResourceConfigProps {
  roleId: string;
  disabled?: boolean;
  canInteract?: () => boolean;
  onStateChange?: (state: EditorState) => void;
  onSaved?: () => void;
}

function resourceIcon(row: RoleResourceRow) {
  const configured = row.icon && createIcon(row.icon);
  if (configured) return configured;
  if (row.type === 'API') return <ApiOutlined/>;
  if (row.type === 'ACTION') return <AppstoreOutlined/>;
  if (row.hasChildren || row.type === 'GROUP' || row.type === 'MODULE') return <FolderOutlined/>;
  return <FileOutlined/>;
}

function ResourceBranch({roleId, parentId, selectedId, draft, revision, onChoose, displayName, disabled, visible, canInteract}: {
  roleId: string; parentId?: string; selectedId?: string; draft: ResourceDraft; revision: number;
  onChoose: (row: RoleResourceRow) => void; displayName: (row: RoleResourceRow) => string;
  disabled: boolean; visible: boolean; canInteract: () => boolean;
}) {
  const {t} = useI18n();
  const [page, setPage] = useState(1);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const list = useOwnedData(['roleResourceBranch', roleId, parentId ?? 'root', page, revision],
    ({signal}) => fetchRoleResources(roleId, {parentId, rootsOnly: !parentId,
      navigationOnly: true, page: page - 1, size: 20}, signal), {enabled: visible && !disabled, retry: false, staleTime: 30_000, gcTime: 120_000});
  useEffect(() => {if (!list.isFetching && !list.error) setPage(previous => reachableServerPage(previous, list.data?.page));}, [list.data, list.isFetching, list.error]);
  const toggleExpanded = (id: string) => {if (!canInteract()) return; setExpanded(previous => {
    const next = new Set(previous);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });};
  if (list.error) return <Alert type="error" title={t('roles.resourceConfig.loadFailed', '资源加载失败')}
    action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0, size: 'small',
      disabled, text: t('table.retry', '重试'), onClick: () => {if (canInteract()) void list.refetch();}}]}/>}/>;
  return <div className="role-resource-branch" data-parent-id={parentId ?? 'root'}>
    {list.isFetching && !list.data && <div className="role-resource-loading"><Spin size="small"/></div>}
    {list.data?.content.map(row => <div key={row.id}>
      <div className={`role-resource-node${selectedId === row.id ? ' is-selected' : ''}`}
        data-resource-id={row.id} style={{paddingLeft: parentId ? 10 : 4}}>
        {row.hasNavigationChildren ? <button type="button" className="role-resource-expander"
          aria-label={`${expanded.has(row.id) ? t('roles.resourceConfig.collapse', '收起') : t('roles.resourceConfig.expand', '展开')} ${displayName(row)}`}
          disabled={disabled} aria-expanded={expanded.has(row.id)} onClick={() => toggleExpanded(row.id)}>
          {expanded.has(row.id) ? <DownOutlined/> : <RightOutlined/>}</button>
          : <span className="role-resource-expander"/>}
        <button type="button" className="role-resource-node-label" disabled={disabled} onClick={() => onChoose(row)}>
          <span className="role-resource-icon">{resourceIcon(row)}</span>
          <span className="role-resource-node-text"><span>{displayName(row)}</span></span>
          {draft[row.id] && <span className="role-resource-draft-dot" aria-label={t('roles.resourceConfig.pending', '待保存')}/>}
        </button>
      </div>
      {row.hasNavigationChildren && expanded.has(row.id) && <div className="role-resource-subtree">
        <ResourceBranch roleId={roleId} parentId={row.id} selectedId={selectedId} draft={draft}
          revision={revision} onChoose={onChoose} displayName={displayName} disabled={disabled} visible={visible} canInteract={canInteract}/>
      </div>}
    </div>)}
    {list.data && list.data.content.length === 0 && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
      description={t('roles.resourceConfig.emptyLevel', '暂无资源')}/>}
    {(list.data?.page.totalElements ?? 0) > 20 && <Pagination size="small" current={page} pageSize={20}
      total={list.data?.page.totalElements ?? 0} showSizeChanger={false} disabled={disabled} onChange={next => {if (canInteract()) setPage(next);}}
      className="role-resource-pagination"/>}
  </div>;
}

function ResourceEditor({roleId, disabled = false, canInteract, onStateChange, onSaved}: RoleResourceConfigProps) {
  const {t, ensure, locale} = useI18n();
  const operation = useOperationScope(roleId), active = usePageActive();
  const [searchInput, setSearchInput] = useState(''), [keyword, setKeyword] = useState('');
  const [state, setState] = useState('all'), [page, setPage] = useState(1);
  const [selected, setSelected] = useState<RoleResourceRow>();
  const [directory, setDirectory] = useState<{id: string; rows: RoleResourceRow[]}>();
  const [searchOpen, setSearchOpen] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false), previewOpenRef = useRef(false);
  const [discardOpen, setDiscardOpen] = useState(false), discardOpenRef = useRef(false);
  const [previewRows, setPreviewRows] = useState<RoleResourceRow[]>([]);
  const [previewDraft, setPreviewDraft] = useState<ResourceDraft>({}), previewDraftRef = useRef<ResourceDraft>({});
  const [expandedChildren, setExpandedChildren] = useState<React.Key[]>([]);
  const [draft, setDraft] = useState<ResourceDraft>({}), draftRef = useRef<ResourceDraft>({});
  const [phase, setPhase] = useState<'preview' | 'save'>(), phaseRef = useRef<'preview' | 'save' | undefined>(undefined);
  const requestRef = useRef<AbortController | undefined>(undefined);
  const [saveError, setSaveError] = useState<string>();
  const [revision, setRevision] = useState(0);
  const saving = operation.busy && phase === 'save', previewLoading = operation.busy && phase === 'preview';
  const changes = useMemo(() => resourceChanges(draft), [draft]), dirty = changes.length > 0;
  const locked = disabled || operation.busy || previewOpen || discardOpen;
  const reads = useOwnedReads(operation, !locked);
  const report = useCallback((extraLock = false) => onStateChange?.({
    dirty: Object.keys(draftRef.current).length > 0, saving: operation.isBusy() && phaseRef.current === 'save',
    locked: disabled || extraLock || operation.isBusy() || previewOpenRef.current || discardOpenRef.current,
  }), [onStateChange, operation.isBusy, disabled]);
  const commitDraft = (next: ResourceDraft) => {draftRef.current = next; setDraft(next); report();};
  const mayEdit = () => !disabled && (!canInteract || canInteract()) && operation.current() && operation.isSelected()
    && !operation.isBusy() && !previewOpenRef.current && !discardOpenRef.current;
  usePageRetention(!dirty && !locked, dirty || locked);
  useLayoutEffect(() => {report(); return () => onStateChange?.({dirty: false, saving: false, locked: false});},
    [dirty, locked, saving, report, onStateChange]);
  useEffect(() => {void ensure(['roles', 'resources', 'table', 'common']);}, [ensure, locale]);
  useLayoutEffect(() => {
    if (!active && phaseRef.current === 'preview' && requestRef.current) {
      const request = requestRef.current; requestRef.current = undefined; request.abort();
      phaseRef.current = undefined; setPhase(undefined); operation.finish(request); report();
    }
  }, [active, operation.finish, report]);
  const treeMode = !keyword && state === 'all';
  const results = useOwnedData(['roleResourceResults', roleId, keyword, state, page, revision],
    ({signal}) => fetchRoleResources(roleId, {keyword, state: state === 'pending' ? 'all' : state,
      page: page - 1, size: 20}, signal), {enabled: !treeMode && state !== 'pending' && !locked, retry: false,
      staleTime: 30_000, gcTime: 120_000});
  const selectedRow = selected && (draft[selected.id]?.baseline ?? selected);
  const directoryId = selectedRow?.hasChildren ? selectedRow.id : undefined;
  const loadDirectory = useCallback(() => {
    if (!directoryId) return Promise.resolve();
    return reads.run('directory', signal => fetchRoleResourceSubtreeRows(roleId, directoryId, {signal, notifyOnError: false}),
      rows => setDirectory({id: directoryId, rows}));
  }, [directoryId, roleId, reads.run]);
  useEffect(() => {if (directoryId) void loadDirectory();}, [loadDirectory, directoryId, revision]);
  const directoryLoading = reads.loading.includes('directory');
  const directoryError = reads.errors.directory ? resolveApiErrorMessage(reads.errors.directory,
    t('roles.resourceConfig.subtreeLoadFailed', '目录资源加载失败')) : undefined;
  const selectedSubtree = directory && directory.id === directoryId ? directory.rows : undefined;
  const grantableSubtree = selectedSubtree?.filter(row => row.grantable === true);
  const selectedSubtreeGranted = grantableSubtree?.filter(row => displayedGrant(draft, row)).length ?? 0;
  const selectedSubtreeChecked = selectedSubtree ? !!grantableSubtree?.length && selectedSubtreeGranted === grantableSubtree.length
    : selectedRow ? displayedGrant(draft, selectedRow) : false;
  const selectedSubtreePartial = !!selectedSubtree && selectedSubtreeGranted > 0
    && selectedSubtreeGranted < (grantableSubtree?.length ?? 0);
  const displayName = useCallback((row: RoleResourceRow) => resolveResourceLabel(row.label === row.code ? undefined : row.label,
    t('permissions.unavailable', '名称暂不可用'), t), [t]);
  const stage = (next: ResourceDraft) => {
    if (Object.keys(next).length > MAX_RESOURCE_CHANGES) {
      setSaveError(t('roles.resourceConfig.changeLimit', '每次最多修改 5000 项，请先保存当前修改')); return;
    }
    commitDraft(next); setSaveError(undefined);
  };
  const toggle = (row: RoleResourceRow, checked: boolean) => {if (mayEdit()) stage(setResourceGrant(draftRef.current, row, checked));};
  const toggleMany = (rows: RoleResourceRow[], checked: boolean) => {
    if (mayEdit() && !directoryLoading && !directoryError) stage(setResourceGrants(draftRef.current, rows, checked));
  };
  const choose = (row: RoleResourceRow) => {
    if (!mayEdit()) return;
    if (selected?.id === row.id && selected.revision === row.revision) return;
    reads.cancel('directory'); reads.clearErrors('directory');
    setSelected(row); setDirectory(undefined); setExpandedChildren([]);
  };
  const toggleSelected = (checked: boolean) => {
    if (!selectedRow || !mayEdit()) return;
    if (!selectedRow.hasChildren) {toggle(selectedRow, checked); return;}
    if (selectedSubtree) toggleMany(selectedSubtree, checked);
  };
  const refresh = (confirmed = false) => {
    if (!confirmed && !mayEdit()) return;
    reads.cancel(); reads.clearErrors();
    setSelected(undefined); setDirectory(undefined); setRevision(value => value + 1);
  };
  const cancelDiscard = () => {
    if (!operation.current() || !operation.isSelected() || operation.isBusy()) return;
    discardOpenRef.current = false; setDiscardOpen(false); report();
  };
  const cancelPreview = () => {
    if (!operation.current() || !operation.isSelected() || operation.isBusy()) return;
    previewOpenRef.current = false; setPreviewOpen(false); report();
  };
  const finish = (request: AbortController) => {
    operation.finish(request);
    if (requestRef.current !== request || !operation.current()) return;
    requestRef.current = undefined; phaseRef.current = undefined; setPhase(undefined); report();
  };
  const save = async () => {
    if (disabled || canInteract && !canInteract() || !previewOpenRef.current || !Object.keys(previewDraftRef.current).length) return;
    const request = operation.begin(); if (!request) return;
    requestRef.current = request; phaseRef.current = 'save'; setPhase('save'); report(true); setSaveError(undefined);
    const captured = resourceChanges(previewDraftRef.current);
    try {
      await saveRoleResourceChanges(roleId, captured, {signal: request.signal, notifyOnError: false});
      if (!operation.current(request.signal)) return;
      previewOpenRef.current = false; setPreviewOpen(false); commitDraft({}); setSaveError(undefined); refresh(true); onSaved?.();
    } catch (error) {
      if (operation.current(request.signal)) setSaveError(resolveApiErrorMessage(error, t('roles.resourceConfig.resourceUpdateFailed', '资源授权更新失败')));
    } finally {finish(request);}
  };
  const previewSave = async () => {
    if (!mayEdit() || !Object.keys(draftRef.current).length || !active) return;
    const request = operation.begin(); if (!request) return;
    requestRef.current = request; phaseRef.current = 'preview'; setPhase('preview'); report(true); setSaveError(undefined);
    reads.cancel();
    const captured = structuredClone(draftRef.current);
    try {
      const rows = await loadResourcePreview(captured, request.signal,
        () => operation.current(request.signal) && operation.isSelected(),
        (index, signal) => fetchRoleResources(roleId, {state: 'granted', page: index, size: 100}, signal),
        (ids, signal) => fetchRoleResourceContext(roleId, ids, {signal, notifyOnError: false}));
      if (!operation.current(request.signal) || !operation.isSelected()) return;
      previewDraftRef.current = captured; setPreviewDraft(captured); setPreviewRows(rows);
      previewOpenRef.current = true; setPreviewOpen(true); report(true);
    } catch (error) {
      if (operation.current(request.signal) && operation.isSelected()) setSaveError(error instanceof PreviewChangedError
        ? t('roles.resourceConfig.previewChanged', '资源目录在预览期间发生变化，请重新预览')
        : resolveApiErrorMessage(error, t('roles.resourceConfig.previewFailed', '授权预览加载失败，请重试')));
    } finally {finish(request);}
  };
  const pendingRows = Object.values(draft).map(value => value.baseline);
  const resultRows = state === 'pending' ? pendingRows.slice((page - 1) * 20, page * 20) : (results.error ? [] : results.data?.content ?? []);
  const resultTotal = state === 'pending' ? pendingRows.length : results.data?.page.totalElements ?? 0;
  useEffect(() => {
    if (state === 'pending') setPage(previous => Math.min(previous, Math.max(1, Math.ceil(pendingRows.length / 20))));
    else if (!results.isFetching && !results.error) setPage(previous => reachableServerPage(previous, results.data?.page));
  }, [state, pendingRows.length, results.data, results.isFetching, results.error]);
  return <div className="role-resource-config" data-role-id={roleId}>
    {saveError && <Alert type="error" showIcon title={t('roles.resourceConfig.saveFailed', '保存未成功，所有修改仍保留')}
      description={<><div>{saveError}</div>{t('roles.resourceConfig.conflictHelp', '若授权已被他人修改，请在“待保存”中撤销对应项，刷新资源后重新确认。')}</>}/>}
    <div className="role-resource-workspace" inert={locked}>
      <section className="role-resource-list" aria-label={t('roles.resourceConfig.resourceTree', '资源目录')}>
        <div className="role-resource-list-heading">
          <div className="role-resource-panel-title">{treeMode ? t('roles.resourceConfig.resourceTree', '资源目录')
            : state === 'pending' ? t('roles.resourceConfig.pending', '待保存') : t('roles.resourceConfig.searchResults', '筛选结果')}</div>
          <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
            {key: 'search', sort: 0, type: 'text', size: 'small', icon: <SearchOutlined/>, iconOnly: true,
              text: t('roles.resourceConfig.searchAction', '搜索资源'), disabled: locked, onClick: () => {if (mayEdit()) setSearchOpen(value => !value);}},
            {key: 'refresh', sort: 1, type: 'text', size: 'small', icon: <ReloadOutlined/>, iconOnly: true,
              text: t('roles.resourceConfig.refresh', '刷新资源'), disabled: locked, onClick: () => refresh()},
          ]}/>
        </div>
        {searchOpen && <Input.Search autoFocus allowClear value={searchInput}
          placeholder={t('roles.resourceConfig.search', '搜索资源名称、编码或路径')}
          onChange={event => {if (!mayEdit()) return; setSearchInput(event.target.value); if (!event.target.value) {setKeyword(''); setPage(1);}}}
          onSearch={value => {if (mayEdit()) {setKeyword(value.trim()); setPage(1);}}} disabled={locked || state === 'pending'}/>}
        <Select className="role-resource-status-filter" aria-label={t('roles.resourceConfig.status', '授权状态')} value={state} disabled={locked}
          onChange={value => {if (mayEdit()) {setState(value); setPage(1);}}} options={[
            {value: 'all', label: t('roles.resourceConfig.all', '全部资源')},
            {value: 'granted', label: t('roles.resourceConfig.granted', '已授权')},
            {value: 'ungranted', label: t('roles.resourceConfig.ungranted', '未授权')},
            {value: 'pending', label: `${t('roles.resourceConfig.pending', '待保存')} (${changes.length})`},
          ]}/>
        <div hidden={!treeMode}><ResourceBranch roleId={roleId} selectedId={selected?.id} draft={draft}
          revision={revision} onChoose={choose} displayName={displayName} disabled={locked} visible={treeMode} canInteract={mayEdit}/></div>
        {!treeMode && <>
          {results.error && state !== 'pending' && <Alert type="error" title={t('roles.resourceConfig.loadFailed', '资源加载失败')}
            action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0,
              disabled: locked, text: t('table.retry', '重试'), onClick: () => {if (mayEdit()) void results.refetch();}}]}/>}/>}
          {results.isFetching && !results.data && <div className="role-resource-loading"><Spin size="small"/></div>}
          {resultRows.map(row => <button type="button" key={row.id} data-resource-id={row.id}
            className={`role-resource-result${selected?.id === row.id ? ' is-selected' : ''}`} disabled={locked} onClick={() => choose(row)}>
            <span className="role-resource-icon">{resourceIcon(row)}</span>
            <span className="role-resource-node-text"><span>{displayName(row)}</span></span>
            {draft[row.id] && <span className="role-resource-draft-dot"/>}</button>)}
          {!resultRows.length && !results.isFetching && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}/>}
          {resultTotal > 20 && <Pagination size="small" current={page} pageSize={20}
            total={resultTotal} showSizeChanger={false} disabled={locked} onChange={next => {if (mayEdit()) setPage(next);}}
            className="role-resource-pagination"/>}
        </>}
      </section>
      <aside className="role-resource-detail">
        {selectedRow ? <>
          <div className="role-resource-detail-heading"><span className="role-resource-icon">{resourceIcon(selectedRow)}</span>
            <div className="role-resource-detail-identity"><div className="role-resource-detail-title">
              <Typography.Title level={5}>{displayName(selectedRow)}</Typography.Title>
              <Checkbox aria-label={`${t('roles.resourceConfig.grantSelected', '授权当前资源')} ${displayName(selectedRow)}`}
                disabled={locked || (!selectedRow.grantable && (directoryLoading || !!directoryError || !grantableSubtree?.length))}
                checked={selectedRow.grantable ? displayedGrant(draft, selectedRow) : selectedSubtreeChecked}
                indeterminate={!selectedRow.grantable && selectedSubtreePartial}
                onChange={event => selectedRow.grantable ? toggle(selectedRow, event.target.checked) : toggleSelected(event.target.checked)}>
                {selectedRow.grantable ? t('roles.resourceConfig.grantSelected', '授权当前资源')
                  : selectedRow.hasChildren ? t('roles.resourceConfig.grantSubtree', '全部')
                    : t('resources.boolean.notGrantable', '仅结构')}
              </Checkbox></div>
              </div></div>
          {directoryLoading && <Spin size="small"/>}
          {directoryError && <Alert className="role-resource-directory-error" type="error" title={directoryError}
            action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0, size: 'small',
              disabled: locked || directoryLoading, text: t('table.retry', '重试'), onClick: () => {if (mayEdit()) void loadDirectory();}}]}/>}/>}
          <div className="role-resource-settings">
          <section className="role-resource-setting">
            <div className="role-resource-section-title role-resource-grant-heading">{t('roles.resourceConfig.resourceGrant', '资源授权')}
              {selectedRow.grantable && selectedRow.hasChildren && <Checkbox
                checked={selectedSubtreeChecked} indeterminate={selectedSubtreePartial}
                disabled={locked || directoryLoading || !!directoryError || !grantableSubtree?.length} onChange={event => toggleSelected(event.target.checked)}>
                {t('roles.resourceConfig.grantSubtree', '全部')}</Checkbox>}
            </div>
            <Typography.Text type="secondary">{selectedRow.grantable && selectedRow.hasChildren
              ? t('roles.resourceConfig.pageGrantHint', '页面访问可单独授权，下级操作按需勾选；“全部”包含当前资源及全部下级。')
              : selectedRow.hasChildren
              ? t('roles.resourceConfig.childrenHint', '勾选目录会暂存全部下级资源的授权修改。')
              : t('roles.resourceConfig.singleHint', '在资源名称旁勾选或取消授权。')}</Typography.Text>
            {selectedRow.hasChildren && <div className="role-resource-children">
              {selectedSubtree && <ResourceChildren rows={selectedSubtree} rootId={selectedRow.id} draft={draft}
                disabled={locked || directoryLoading || !!directoryError} expanded={expandedChildren}
                onExpanded={keys => {if (mayEdit()) setExpandedChildren(keys);}} onChoose={choose}
                onToggle={toggleMany} displayName={displayName} icon={resourceIcon}/>}
            </div>}
          </section>
          <section className="role-resource-setting"><span className="role-resource-section-title">{t('roles.resourceConfig.dataScope', '数据权限')}</span>
            {selectedRow.dataPermission.supported === true ? <>
              <Typography.Text type="secondary">{t('roles.resourceConfig.dataRuleHint', '选择已创建的数据范围规则；不选择则沿用角色默认范围。')}</Typography.Text>
              <PolicySelect roleId={roleId} resourceCode={selectedRow.code} kind="data" revision={revision}
                disabled={locked || !displayedGrant(draft, selectedRow)}
                value={displayedPolicy(draft, selectedRow, 'data') ?? undefined}
                onChange={value => {if (mayEdit()) stage(setResourcePolicy(draftRef.current, selectedRow, 'data', value ?? null));}}/>
            </> : <Typography.Text type="secondary">{t('roles.resourceConfig.dataUnsupported', '此资源暂不支持数据权限配置。')}</Typography.Text>}
          </section>
          <section className="role-resource-setting"><span className="role-resource-section-title">{t('roles.resourceConfig.fieldScope', '字段权限')}</span>
            {selectedRow.fieldPermission.supported === true ? <>
              <Typography.Text type="secondary">{t('roles.resourceConfig.fieldRuleHint', '选择已创建的字段规则；不选择则沿用角色默认规则。')}</Typography.Text>
              <PolicySelect roleId={roleId} resourceCode={selectedRow.code} kind="field" revision={revision}
                disabled={locked || !displayedGrant(draft, selectedRow)}
                value={displayedPolicy(draft, selectedRow, 'field') ?? undefined}
                onChange={value => {if (mayEdit()) stage(setResourcePolicy(draftRef.current, selectedRow, 'field', value ?? null));}}/>
            </> : <Typography.Text type="secondary">{t('roles.resourceConfig.fieldUnsupported', '此页面尚未接入字段权限执行链路。')}</Typography.Text>}
          </section>
          </div>
        </> : <Empty description={t('roles.resourceConfig.selectTreeResource', '从左侧选择资源，在右侧配置授权及权限范围')}/>}
      </aside>
    </div>
    <div className="role-resource-footer" inert={locked}>
      <Typography.Text>{t('roles.resourceConfig.pendingCount', '待保存资源变更：{count}', {count: changes.length})}</Typography.Text>
      <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'discard', sort: 0, disabled: !changes.length || locked,
        text: t('roles.resourceConfig.discardResourcesButton', '放弃修改'), onClick: () => {
        if (!mayEdit() || !Object.keys(draftRef.current).length) return;
        discardOpenRef.current = true; setDiscardOpen(true); report(true);
      }}, {key: 'previewSave', sort: 1, type: 'primary', loading: previewLoading, disabled: !changes.length || locked,
        text: t('roles.resourceConfig.saveResources', '预览保存'), onClick: () => void previewSave()}]}/>
    </div>
    {discardOpen && operation.selected && <Modal rootClassName="role-resource-discard" open destroyOnHidden maskClosable={false}
      title={t('roles.resourceConfig.discardResources', '放弃本次资源修改并重新载入？')} onCancel={cancelDiscard}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'cancel', sort: 0, text: t('action.cancel', '取消'), onClick: cancelDiscard},
        {key: 'discard', sort: 1, danger: true, text: t('roles.resourceConfig.discardResourcesButton', '放弃修改'), onClick: () => {
          if (!operation.current() || !operation.isSelected() || operation.isBusy() || !discardOpenRef.current) return;
          discardOpenRef.current = false; setDiscardOpen(false); commitDraft({}); setSaveError(undefined); refresh(true);
        }},
      ]}/>}/>}
    {previewOpen && operation.selected && <Modal className="role-resource-preview" width={960} destroyOnHidden open
      title={t('roles.resourceConfig.previewTitle', '资源授权预览')}
      closable={!saving} maskClosable={false} keyboard={!saving} onCancel={cancelPreview}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'cancel', sort: 0, disabled: saving, text: t('action.cancel', '取消'), onClick: cancelPreview},
        {key: 'save', sort: 1, type: 'primary', loading: saving, disabled: saving || disabled,
          text: t('roles.resourceConfig.confirmSave', '确认提交'), onClick: () => void save()},
      ]}/>}>
      {saveError && <Alert type="error" showIcon title={saveError}/>}
      <ResourcePreview rows={previewRows} draft={previewDraft} displayName={displayName} icon={resourceIcon}/>
    </Modal>}
  </div>;
}

export default function RoleResourceConfig(props: RoleResourceConfigProps) {
  const scope = useQueryScope();
  return <ResourceEditor key={`${JSON.stringify(scope)}:${props.roleId}`} {...props}/>;
}
