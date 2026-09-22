import {resolveResourceLabel} from '@simplepoint/shared/i18n/resourceLabel';
import {useCallback, useEffect, useMemo, useState} from 'react';
import {ApiOutlined, AppstoreOutlined, DownOutlined, FileOutlined, FolderOutlined, ReloadOutlined, RightOutlined, SearchOutlined} from '@ant-design/icons';
import {Alert, App, Button, Checkbox, Empty, Input, Modal, Pagination, Select, Space, Spin, Tag, Typography} from 'antd';
import {useData} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {createIcon} from '@simplepoint/shared/types/icon';
import {fetchRoleResources, fetchRoleResourceSubtreeRows, saveRoleResourceChanges} from '@/api/system/role-resource-configuration';
import type {RoleResourceRow} from '@/api/system/role-resource-configuration';
import {displayedGrant, MAX_RESOURCE_CHANGES, resourceChanges, setResourceGrant} from './resourceDraft';
import type {ResourceDraft} from './resourceDraft';
import './index.css';

export interface EditorState {dirty: boolean; saving: boolean}
export interface RoleResourceConfigProps {
  roleId: string;
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

function ResourceBranch({roleId, parentId, selectedId, draft, revision, onChoose, displayName}: {
  roleId: string; parentId?: string; selectedId?: string; draft: ResourceDraft; revision: number;
  onChoose: (row: RoleResourceRow) => void; displayName: (row: RoleResourceRow) => string;
}) {
  const {t} = useI18n();
  const [page, setPage] = useState(1);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const list = useData(['roleResourceBranch', roleId, parentId ?? 'root', page, revision],
    ({signal}) => fetchRoleResources(roleId, {parentId, rootsOnly: !parentId,
      navigationOnly: true, page: page - 1, size: 20}, signal));
  const toggleExpanded = (id: string) => setExpanded(previous => {
    const next = new Set(previous);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });
  if (list.error) return <Alert type="error" title={t('roles.resourceConfig.loadFailed', '资源加载失败')}
    action={<Button size="small" onClick={() => void list.refetch()}>{t('table.retry', '重试')}</Button>}/>;
  return <div className="role-resource-branch" data-parent-id={parentId ?? 'root'}>
    {list.isFetching && !list.data && <div className="role-resource-loading"><Spin size="small"/></div>}
    {list.data?.content.map(row => <div key={row.id}>
      <div className={`role-resource-node${selectedId === row.id ? ' is-selected' : ''}`}
        data-resource-id={row.id} style={{paddingLeft: parentId ? 10 : 4}}>
        {row.hasNavigationChildren ? <button type="button" className="role-resource-expander"
          aria-label={`${expanded.has(row.id) ? t('roles.resourceConfig.collapse', '收起') : t('roles.resourceConfig.expand', '展开')} ${displayName(row)}`}
          aria-expanded={expanded.has(row.id)} onClick={() => toggleExpanded(row.id)}>
          {expanded.has(row.id) ? <DownOutlined/> : <RightOutlined/>}</button>
          : <span className="role-resource-expander"/>}
        <button type="button" className="role-resource-node-label" onClick={() => onChoose(row)}>
          <span className="role-resource-icon">{resourceIcon(row)}</span>
          <span className="role-resource-node-text"><span>{displayName(row)}</span><Tag className="role-resource-code-tag">{row.code}</Tag></span>
          {draft[row.id] && <span className="role-resource-draft-dot" aria-label={t('roles.resourceConfig.pending', '待保存')}/>}
        </button>
      </div>
      {row.hasNavigationChildren && expanded.has(row.id) && <div className="role-resource-subtree">
        <ResourceBranch roleId={roleId} parentId={row.id} selectedId={selectedId} draft={draft}
          revision={revision} onChoose={onChoose} displayName={displayName}/>
      </div>}
    </div>)}
    {list.data && list.data.content.length === 0 && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
      description={t('roles.resourceConfig.emptyLevel', '暂无资源')}/>}
    {(list.data?.page.totalElements ?? 0) > 20 && <Pagination size="small" current={page} pageSize={20}
      total={list.data?.page.totalElements ?? 0} showSizeChanger={false} onChange={setPage}
      className="role-resource-pagination"/>}
  </div>;
}

function ResourceEditor({roleId, onStateChange, onSaved}: RoleResourceConfigProps) {
  const {t, ensure, locale} = useI18n();
  const {message, modal} = App.useApp();
  const [searchInput, setSearchInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [state, setState] = useState('all');
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState<RoleResourceRow>();
  const [directoryRows, setDirectoryRows] = useState<Record<string, RoleResourceRow[]>>({});
  const [directoryLoading, setDirectoryLoading] = useState<string>();
  const [directoryError, setDirectoryError] = useState<{id: string; text: string}>();
  const [searchOpen, setSearchOpen] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewRows, setPreviewRows] = useState<RoleResourceRow[]>([]);
  const [previewPage, setPreviewPage] = useState(1);
  const [expandedChildren, setExpandedChildren] = useState<Set<string>>(new Set());
  const [draft, setDraft] = useState<ResourceDraft>({});
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string>();
  const [revision, setRevision] = useState(0);
  const changes = useMemo(() => resourceChanges(draft), [draft]);
  const dirty = changes.length > 0;
  usePageRetention(!dirty && !saving, dirty || saving);
  useEffect(() => onStateChange?.({dirty, saving}), [dirty, saving, onStateChange]);
  useEffect(() => {void ensure(['roles', 'resources', 'resources', 'table', 'common']);}, [ensure, locale]);
  const treeMode = !keyword && state === 'all';
  const results = useData(['roleResourceResults', roleId, keyword, state, page, revision],
    ({signal}) => fetchRoleResources(roleId, {keyword, state: state === 'pending' ? 'all' : state,
      page: page - 1, size: 20}, signal), {enabled: !treeMode && state !== 'pending'});
  const selectedRow = selected && (draft[selected.id]?.baseline ?? selected);
  const selectedSubtree = selectedRow?.hasChildren ? directoryRows[selectedRow.id] : undefined;
  const grantableSubtree = selectedSubtree?.filter(row => row.grantable);
  const selectedSubtreeGranted = grantableSubtree?.filter(row => displayedGrant(draft, row)).length ?? 0;
  const selectedSubtreeChecked = selectedSubtree
    ? !!grantableSubtree?.length && selectedSubtreeGranted === grantableSubtree.length
    : selectedRow ? displayedGrant(draft, selectedRow) : false;
  const selectedSubtreePartial = !!selectedSubtree && selectedSubtreeGranted > 0
    && selectedSubtreeGranted < (grantableSubtree?.length ?? 0);
  const subtreeChildren = useMemo(() => {
    const childrenByParent = new Map<string, RoleResourceRow[]>();
    for (const row of selectedSubtree ?? []) {
      if (row.id === selectedRow?.id || !row.parentId) continue;
      const siblings = childrenByParent.get(row.parentId) ?? [];
      siblings.push(row);
      childrenByParent.set(row.parentId, siblings);
    }
    return childrenByParent;
  }, [selectedSubtree, selectedRow?.id]);
  const displayName = useCallback((row: RoleResourceRow) => {
    const label = row.label || row.code;
    return resolveResourceLabel(label, row.code, t);
  }, [t]);
  const toggle = (row: RoleResourceRow, checked: boolean) => {
    if (saving) return;
    const next = setResourceGrant(draft, row, checked);
    if (Object.keys(next).length > MAX_RESOURCE_CHANGES) {
      message.warning(t('roles.resourceConfig.changeLimit', '每次最多修改 200 项，请先保存当前修改')); return;
    }
    setDraft(next);
  };
  const choose = (row: RoleResourceRow) => {
    setSelected(row); setExpandedChildren(new Set()); setDirectoryError(undefined);
    if (row.hasChildren && !directoryRows[row.id]) {
      setDirectoryLoading(row.id);
      void fetchRoleResourceSubtreeRows(roleId, row.id).then(rows => {
        setDirectoryRows(previous => ({...previous, [row.id]: rows}));
      }).catch(error => setDirectoryError({id: row.id, text: resolveApiErrorMessage(error,
        t('roles.resourceConfig.subtreeLoadFailed', '目录资源加载失败'))}))
        .finally(() => setDirectoryLoading(current => current === row.id ? undefined : current));
    }
  };
  const toggleSelected = (checked: boolean) => {
    if (!selectedRow || saving) return;
    if (!selectedRow.hasChildren) {toggle(selectedRow, checked); return;}
    if (!selectedSubtree) return;
    let next = draft;
    for (const row of selectedSubtree) next = setResourceGrant(next, row, checked);
    if (Object.keys(next).length > MAX_RESOURCE_CHANGES) {
      message.warning(t('roles.resourceConfig.changeLimit', '每次最多修改 5000 项，请先保存当前修改')); return;
    }
    setDraft(next);
  };
  const toggleBranch = (row: RoleResourceRow, checked: boolean) => {
    if (saving) return;
    let next = draft;
    const pending = [row];
    while (pending.length) {
      const current = pending.pop()!;
      next = setResourceGrant(next, current, checked);
      pending.push(...(subtreeChildren.get(current.id) ?? []));
    }
    if (Object.keys(next).length > MAX_RESOURCE_CHANGES) {
      message.warning(t('roles.resourceConfig.changeLimit', '每次最多修改 5000 项，请先保存当前修改')); return;
    }
    setDraft(next);
  };
  const renderChildBranch = (rows: RoleResourceRow[], depth = 0): React.ReactNode => rows.map(row => {
    const nested = subtreeChildren.get(row.id) ?? [];
    const branch = [row];
    for (let index = 0; index < branch.length; index++) branch.push(...(subtreeChildren.get(branch[index].id) ?? []));
    const grantable = branch.filter(item => item.grantable);
    const granted = grantable.filter(item => displayedGrant(draft, item)).length;
    const expanded = expandedChildren.has(row.id);
    return <div key={row.id} className="role-resource-child-branch">
      <div className="role-resource-child-row" data-resource-id={row.id} style={{paddingLeft: depth * 18}}>
        {nested.length ? <button type="button" className="role-resource-expander"
          aria-label={`${expanded ? t('roles.resourceConfig.collapse', '收起') : t('roles.resourceConfig.expand', '展开')} ${displayName(row)}`}
          aria-expanded={expanded} onClick={() => setExpandedChildren(previous => {
            const next = new Set(previous); if (next.has(row.id)) next.delete(row.id); else next.add(row.id); return next;
          })}>{expanded ? <DownOutlined/> : <RightOutlined/>}</button>
          : <span className="role-resource-expander"/>}
        <Checkbox aria-label={`${t('roles.resourceConfig.grantSelected', '授权当前资源')} ${row.code}`}
          disabled={saving || !grantable.length} checked={!!grantable.length && granted === grantable.length}
          indeterminate={granted > 0 && granted < grantable.length}
          onChange={event => toggleBranch(row, event.target.checked)}/>
        <span className="role-resource-icon">{resourceIcon(row)}</span>
        <button type="button" className="role-resource-child-name" onClick={() => choose(row)}>{displayName(row)}</button>
        <Tag className="role-resource-code-tag">{row.code}</Tag>
        {draft[row.id] && <span className="role-resource-draft-dot" aria-label={t('roles.resourceConfig.pending', '待保存')}/>}
      </div>
      {expanded && nested.length > 0 && renderChildBranch(nested, depth + 1)}
    </div>;
  });
  const refresh = () => {setSelected(undefined); setDirectoryRows({}); setRevision(value => value + 1);};
  const save = async () => {
    if (!changes.length || saving) return;
    setSaving(true); setSaveError(undefined);
    try {
      await saveRoleResourceChanges(roleId, changes);
      setDraft({}); setSelected(undefined); setPreviewOpen(false); refresh(); onSaved?.();
      message.success(t('roles.resourceConfig.resourceUpdateSuccess', '资源授权已更新'));
    } catch (error) {setSaveError(resolveApiErrorMessage(error, t('roles.resourceConfig.resourceUpdateFailed', '资源授权更新失败')));}
    finally {setSaving(false);}
  };
  const previewSave = async () => {
    if (!changes.length || saving || previewLoading) return;
    setPreviewLoading(true); setSaveError(undefined);
    try {
      const granted = new Map<string, RoleResourceRow>();
      let pageIndex = 0;
      let totalPages = 1;
      while (pageIndex < totalPages) {
        const page = await fetchRoleResources(roleId, {state: 'granted', page: pageIndex, size: 100});
        for (const row of page.content) granted.set(row.id, row);
        totalPages = page.page.totalPages;
        pageIndex++;
      }
      for (const entry of Object.values(draft)) {
        if (entry.granted) granted.set(entry.baseline.id, entry.baseline);
        else granted.delete(entry.baseline.id);
      }
      setPreviewRows([...granted.values()].sort((a, b) =>
        displayName(a).localeCompare(displayName(b)) || a.code.localeCompare(b.code)));
      setPreviewPage(1); setPreviewOpen(true);
    } catch (error) {
      setSaveError(resolveApiErrorMessage(error,
        t('roles.resourceConfig.previewFailed', '授权预览加载失败，请重试')));
    } finally {setPreviewLoading(false);}
  };
  const pendingRows = Object.values(draft).map(value => value.baseline);
  const resultRows = state === 'pending' ? pendingRows : (results.error ? [] : results.data?.content ?? []);
  return <div className="role-resource-config" data-role-id={roleId}>
    {saveError && <Alert type="error" showIcon title={t('roles.resourceConfig.saveFailed', '保存未成功，所有修改仍保留')}
      description={<><div>{saveError}</div>{t('roles.resourceConfig.conflictHelp', '若授权已被他人修改，请在“待保存”中撤销对应项，刷新资源后重新确认。')}</>}/>}
    <div className="role-resource-workspace">
      <section className="role-resource-list" aria-label={t('roles.resourceConfig.resourceTree', '资源目录')}>
        <div className="role-resource-list-heading">
          <div className="role-resource-panel-title">{treeMode ? t('roles.resourceConfig.resourceTree', '资源目录')
            : state === 'pending' ? t('roles.resourceConfig.pending', '待保存') : t('roles.resourceConfig.searchResults', '筛选结果')}</div>
          <Space size={2}>
            <Button type="text" size="small" icon={<SearchOutlined/>}
              aria-label={t('roles.resourceConfig.searchAction', '搜索资源')}
              onClick={() => setSearchOpen(value => !value)}/>
            <Button type="text" size="small" icon={<ReloadOutlined/>} disabled={saving}
              aria-label={t('roles.resourceConfig.refresh', '刷新资源')} onClick={refresh}/>
          </Space>
        </div>
        {searchOpen && <Input.Search autoFocus allowClear value={searchInput}
          placeholder={t('roles.resourceConfig.search', '搜索资源名称、编码或路径')}
          onChange={event => {setSearchInput(event.target.value); if (!event.target.value) {setKeyword(''); setPage(1);}}}
          onSearch={value => {setKeyword(value.trim()); setPage(1);}} disabled={state === 'pending'}/>}
        <Select className="role-resource-status-filter" aria-label={t('roles.resourceConfig.status', '授权状态')} value={state}
          onChange={value => {setState(value); setPage(1);}} options={[
            {value: 'all', label: t('roles.resourceConfig.all', '全部资源')},
            {value: 'granted', label: t('roles.resourceConfig.granted', '已授权')},
            {value: 'ungranted', label: t('roles.resourceConfig.ungranted', '未授权')},
            {value: 'pending', label: `${t('roles.resourceConfig.pending', '待保存')} (${changes.length})`},
          ]}/>
        <div hidden={!treeMode}><ResourceBranch roleId={roleId} selectedId={selected?.id} draft={draft}
          revision={revision} onChoose={choose} displayName={displayName}/></div>
        {!treeMode && <>
          {results.error && state !== 'pending' && <Alert type="error" title={t('roles.resourceConfig.loadFailed', '资源加载失败')}
            action={<Button onClick={() => void results.refetch()}>{t('table.retry', '重试')}</Button>}/>}
          {results.isFetching && !results.data && <div className="role-resource-loading"><Spin size="small"/></div>}
          {resultRows.map(row => <button type="button" key={row.id} data-resource-id={row.id}
            className={`role-resource-result${selected?.id === row.id ? ' is-selected' : ''}`} onClick={() => choose(row)}>
            <span className="role-resource-icon">{resourceIcon(row)}</span>
            <span className="role-resource-node-text"><span>{displayName(row)}</span><Tag className="role-resource-code-tag">{row.code}</Tag></span>
            {draft[row.id] && <span className="role-resource-draft-dot"/>}</button>)}
          {!resultRows.length && !results.isFetching && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}/>}
          {state !== 'pending' && (results.data?.page.totalElements ?? 0) > 20 && <Pagination size="small" current={page} pageSize={20}
            total={results.data?.page.totalElements ?? 0} showSizeChanger={false} onChange={setPage}
            className="role-resource-pagination"/>}
        </>}
      </section>
      <aside className="role-resource-detail">
        {selectedRow ? <>
          <div className="role-resource-detail-heading"><span className="role-resource-icon">{resourceIcon(selectedRow)}</span>
            <div className="role-resource-detail-identity"><div className="role-resource-detail-title">
              <Typography.Title level={5}>{displayName(selectedRow)}</Typography.Title>
              <Checkbox aria-label={`${t('roles.resourceConfig.grantSelected', '授权当前资源')} ${selectedRow.code}`}
                disabled={saving || (!selectedRow.grantable && !selectedRow.hasChildren)
                  || (selectedRow.hasChildren && !grantableSubtree?.length)}
                checked={selectedSubtreeChecked} indeterminate={selectedSubtreePartial}
                onChange={event => toggleSelected(event.target.checked)}>
                {selectedRow.hasChildren
                  ? t('roles.resourceConfig.grantSubtree', '全部')
                  : selectedRow.grantable ? t('roles.resourceConfig.grantSelected', '授权当前资源')
                    : t('resources.boolean.notGrantable', '仅结构')}
              </Checkbox></div>
              <Typography.Text type="secondary" code>{selectedRow.code}</Typography.Text></div></div>
          {directoryLoading === selectedRow.id && <Spin size="small"/>}
          {directoryError?.id === selectedRow.id && <Alert type="error" title={directoryError.text}
            action={<Button size="small" onClick={() => choose(selectedRow)}>{t('table.retry', '重试')}</Button>}/>}
          <div className="role-resource-settings">
          <section className="role-resource-setting">
            <div className="role-resource-section-title">{t('roles.resourceConfig.resourceGrant', '资源授权')}</div>
            <Typography.Text type="secondary">{selectedRow.hasChildren
              ? t('roles.resourceConfig.childrenHint', '勾选目录会暂存全部下级资源的授权修改。')
              : t('roles.resourceConfig.singleHint', '在资源名称旁勾选或取消授权。')}</Typography.Text>
            {selectedRow.hasChildren && <div className="role-resource-children">
              {selectedSubtree && renderChildBranch(subtreeChildren.get(selectedRow.id) ?? [])}
            </div>}
          </section>
          <section className="role-resource-setting role-resource-reserved"><span className="role-resource-section-title">{t('roles.resourceConfig.dataScope', '数据权限')} <Tag>{t('roles.resourceConfig.reserved', '预留')}</Tag></span>
            <Typography.Text type="secondary">{t('roles.resourceConfig.dataReservedDetail', '后续在此选择适用于当前资源的数据规则。当前入口不会改变数据访问范围。')}</Typography.Text></section>
          <section className="role-resource-setting role-resource-reserved"><span className="role-resource-section-title">{t('roles.resourceConfig.fieldScope', '字段权限')} <Tag>{t('roles.resourceConfig.reserved', '预留')}</Tag></span>
            <Typography.Text type="secondary">{t('roles.resourceConfig.fieldReservedDetail', '后续在此配置当前资源的字段可见性与可编辑性。当前入口不会改变字段访问范围。')}</Typography.Text></section>
          </div>
        </> : <Empty description={t('roles.resourceConfig.selectTreeResource', '从左侧选择资源，在右侧配置授权及权限范围')}/>}
      </aside>
    </div>
    <div className="role-resource-footer">
      <Typography.Text>{t('roles.resourceConfig.pendingCount', '待保存资源变更：{count}', {count: changes.length})}</Typography.Text>
      <Space><Button disabled={!changes.length || saving} onClick={async () => {
        if (await modal.confirm({title: t('roles.resourceConfig.discardResources', '放弃本次资源修改并重新载入？')})) {
          setDraft({}); setSaveError(undefined); setSelected(undefined); setPreviewOpen(false); refresh();
        }
      }}>{t('roles.resourceConfig.discardResourcesButton', '放弃修改')}</Button>
        <Button type="primary" loading={previewLoading} disabled={!changes.length || saving}
          onClick={() => void previewSave()}>
          {t('roles.resourceConfig.saveResources', '预览保存')}</Button></Space>
    </div>
    <Modal className="role-resource-preview" width={760} open={previewOpen}
      title={t('roles.resourceConfig.previewTitle', '资源授权预览')}
      onCancel={() => !saving && setPreviewOpen(false)}
      footer={<Space><Button disabled={saving} onClick={() => setPreviewOpen(false)}>{t('action.cancel', '取消')}</Button>
        <Button type="primary" loading={saving} onClick={() => void save()}>
          {t('roles.resourceConfig.confirmSave', '确认提交')}</Button></Space>}>
      <Typography.Paragraph type="secondary">{t('roles.resourceConfig.previewHint',
        '以下是保存后当前可管理资源中的授权清单；共 {count} 项授权，本次变更 {changed} 项。',
        {count: previewRows.length, changed: changes.length})}</Typography.Paragraph>
      {saveError && <Alert type="error" showIcon title={saveError}/>}
      {previewRows.length === 0 ? <Empty description={t('roles.resourceConfig.previewEmpty', '保存后没有已授权资源')}/>
        : <div className="role-resource-preview-list">{previewRows.slice((previewPage - 1) * 50, previewPage * 50)
          .map(row => <div className="role-resource-preview-item" key={row.id}>
            <span className="role-resource-icon">{resourceIcon(row)}</span><strong>{displayName(row)}</strong>
            <Tag className="role-resource-code-tag">{row.code}</Tag>
            {draft[row.id]?.granted && <Tag color="green">{t('roles.resourceConfig.previewNew', '本次新增')}</Tag>}
          </div>)}</div>}
      {previewRows.length > 50 && <Pagination className="role-resource-preview-pagination" current={previewPage}
        pageSize={50} total={previewRows.length} showSizeChanger={false} onChange={setPreviewPage}/>}
    </Modal>
  </div>;
}

export default function RoleResourceConfig(props: RoleResourceConfigProps) {
  const scope = useQueryScope();
  return <ResourceEditor key={`${JSON.stringify(scope)}:${props.roleId}`} {...props}/>;
}
