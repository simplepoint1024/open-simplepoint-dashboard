import {useEffect, useMemo, useState} from 'react';
import type {ReactNode} from 'react';
import {Empty, Input, Segmented, Space, Tag, Tree, Typography} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePermissionLabels} from '@simplepoint/shared/hooks/usePermissionLabels';
import type {RoleResourceRow} from '@/api/system/role-resource-configuration';
import {displayedGrant, displayedPolicy} from './resourceDraft';
import type {ResourceDraft} from './resourceDraft';
import {previewStatus, previewTree} from './resourcePreview';

export function ResourcePreview({rows, draft, displayName, icon}: {
  rows: RoleResourceRow[]; draft: ResourceDraft;
  displayName: (row: RoleResourceRow) => string; icon: (row: RoleResourceRow) => ReactNode;
}) {
  const {t} = useI18n();
  const {labels} = usePermissionLabels();
  const [mode, setMode] = useState('changes');
  const [search, setSearch] = useState('');
  const [height, setHeight] = useState(() => Math.max(200, Math.min(480, window.innerHeight * .5)));
  useEffect(() => {
    const resize = () => setHeight(Math.max(200, Math.min(480, window.innerHeight * .5)));
    window.addEventListener('resize', resize);
    return () => window.removeEventListener('resize', resize);
  }, []);
  const counts = useMemo(() => {
    const result = {added: 0, removed: 0, updated: 0, unchanged: 0};
    for (const entry of Object.values(draft)) result[previewStatus(entry)]++;
    return result;
  }, [draft]);
  const total = rows.filter(row => row.grantable && displayedGrant(draft, row)).length;
  const rowIds = useMemo(() => new Set(rows.map(row => row.id)), [rows]);
  const tree = useMemo(() => {
    const keyword = search.trim().toLocaleLowerCase();
    const selected = new Set(rows.filter(row => (mode === 'changes' ? !!draft[row.id]
      : row.grantable && displayedGrant(draft, row)) && (!keyword || displayName(row).toLocaleLowerCase().includes(keyword)))
      .map(row => row.id));
    return previewTree(rows, selected, displayName);
  }, [rows, draft, mode, search, displayName]);
  const expanded = useMemo(() => {
    const keys: string[] = [], pending = [...tree];
    while (pending.length) {const node = pending.pop()!; if (node.children.length) keys.push(node.key); pending.push(...node.children);}
    return keys;
  }, [tree]);
  const [expandedKeys, setExpandedKeys] = useState<React.Key[]>(expanded);
  useEffect(() => setExpandedKeys(expanded), [expanded]);
  const statusLabel = {
    added: t('roles.resourceConfig.previewAdded', '新增授权'),
    removed: t('roles.resourceConfig.previewRemoved', '取消授权'),
    updated: t('roles.resourceConfig.previewUpdated', '权限调整'),
    unchanged: t('roles.resourceConfig.previewUnchanged', '保持授权'),
  };
  const colors = {added: 'green', removed: 'red', updated: 'blue', unchanged: undefined};
  const typeLabels = {GROUP: t('resources.type.GROUP', '分组'), MODULE: t('resources.type.MODULE', '模块'),
    PAGE: t('resources.type.PAGE', '页面'), FEATURE: t('resources.type.FEATURE', '功能点'),
    ACTION: t('resources.type.ACTION', '操作'), API: t('resources.type.API', '接口')};
  const policyText = (id: string | null, kind: 'data' | 'field') => id ? labels.rule(id, kind)
    : t('roles.resourceConfig.previewDefaultPolicy', '沿用角色默认规则');
  return <div className="role-resource-preview-content">
    <Typography.Paragraph type="secondary">{t('roles.resourceConfig.previewTreeHint',
      '按目录和页面核对权限，确认后才会保存。保存后共 {count} 项授权。', {count: total})}</Typography.Paragraph>
    <Space wrap className="role-resource-preview-summary">
      <Tag color="green">{statusLabel.added} {counts.added}</Tag>
      <Tag color="red">{statusLabel.removed} {counts.removed}</Tag>
      <Tag color="blue">{statusLabel.updated} {counts.updated}</Tag>
    </Space>
    <div className="role-resource-preview-toolbar">
      <Segmented aria-label={t('roles.resourceConfig.previewMode', '预览范围')} value={mode} onChange={setMode} options={[
        {value: 'changes', label: t('roles.resourceConfig.previewChanges', '本次变更')},
        {value: 'result', label: t('roles.resourceConfig.previewResult', '保存后全部授权')},
      ]}/>
      <Input.Search allowClear value={search} onChange={event => setSearch(event.target.value)}
        placeholder={t('roles.resourceConfig.previewSearch', '搜索资源名称')}/>
    </div>
    <div className="role-resource-preview-tree">
      {tree.length ? <Tree blockNode selectable={false} virtual height={height} treeData={tree}
        expandedKeys={expandedKeys} onExpand={keys => setExpandedKeys(keys)}
        titleRender={node => {
          const row = node.row, entry = draft[row.id], status = previewStatus(entry);
          const granted = displayedGrant(draft, row);
          return <div className="role-resource-preview-node" data-resource-id={row.id} data-change={status}>
            <div className="role-resource-preview-node-heading">
              <span className="role-resource-icon">{icon(row)}</span>
              <strong>{displayName(row)}</strong>
              <span className="role-resource-preview-type">{row.type && typeLabels[row.type]}</span>
              {(entry || row.grantable && granted) && <Tag color={colors[status]}>{statusLabel[status]}</Tag>}
            </div>
            {row.parentId && !rowIds.has(row.parentId) && <div className="role-resource-preview-policy">
              {t('roles.resourceConfig.previewMissingParent', '上级目录暂不可用')}</div>}
            {(['data', 'field'] as const).map(kind => {
              const before = kind === 'data' ? row.dataPermission.policyId : row.fieldPermission.policyId;
              const after = displayedPolicy(draft, row, kind);
              const changed = granted && before !== after;
              if (!before && !after) return null;
              return <div className="role-resource-preview-policy" key={kind}>
                <span>{kind === 'data' ? t('roles.resourceConfig.dataScope', '数据权限') : t('roles.resourceConfig.fieldScope', '字段权限')}：</span>
                {changed ? <><del>{policyText(before, kind)}</del><span aria-hidden="true"> → </span><strong>{policyText(after, kind)}</strong></>
                  : <span>{policyText(granted ? after : before, kind)}</span>}
                {!granted && <span> · {t('roles.resourceConfig.previewPolicyRemoved', '随资源授权取消')}</span>}
              </div>;
            })}
          </div>;
        }}/>
        : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={search
          ? t('roles.resourceConfig.previewNoMatch', '没有匹配的资源')
          : t('roles.resourceConfig.previewEmpty', '保存后没有已授权资源')}/>}
    </div>
  </div>;
}
