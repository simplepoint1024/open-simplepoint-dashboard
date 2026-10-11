import {useMemo} from 'react';
import {Checkbox, Tree} from 'antd';
import TableActions from '@simplepoint/components/Table/Actions';
import type {ReactNode} from 'react';
import type {RoleResourceRow} from '@/api/system/role-resource-configuration';
import type {ResourceDraft} from './resourceDraft';
import {displayedGrant} from './resourceDraft';
import {previewTree, type PreviewNode} from './resourcePreview';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';

/** A complete local subtree editor, not a second CRUD table: virtualize display, never truncate grants. */
export default function ResourceChildren({rows, rootId, draft, disabled, expanded, onExpanded, onChoose, onToggle,
  displayName, icon}: {rows: RoleResourceRow[]; rootId: string; draft: ResourceDraft; disabled: boolean;
  expanded: React.Key[]; onExpanded: (keys: React.Key[]) => void; onChoose: (row: RoleResourceRow) => void;
  onToggle: (rows: RoleResourceRow[], checked: boolean) => void;
  displayName: (row: RoleResourceRow) => string; icon: (row: RoleResourceRow) => ReactNode}) {
  const {t} = useI18n();
  const tree = useMemo(() => previewTree(rows, new Set(rows.map(row => row.id)), displayName)
    .flatMap(node => node.key === rootId ? node.children : [node]), [rows, rootId, displayName]);
  const summaries = useMemo(() => {
    const result = new Map<string, {grantable: number; granted: number}>();
    const pending = tree.map(node => ({node, visited: false}));
    while (pending.length) {
      const {node, visited} = pending.pop()!;
      if (!visited) {pending.push({node, visited: true}, ...node.children.map(child => ({node: child, visited: false}))); continue;}
      const summary = {grantable: node.row.grantable === true ? 1 : 0, granted: node.row.grantable === true && displayedGrant(draft, node.row) ? 1 : 0};
      for (const child of node.children) {const value = result.get(child.key)!; summary.grantable += value.grantable; summary.granted += value.granted;}
      result.set(node.key, summary);
    }
    return result;
  }, [tree, draft]);
  const affected = (node: PreviewNode) => {
    if (node.row.grantable === true) return [node.row];
    const result: RoleResourceRow[] = [], pending = [node];
    while (pending.length) {const current = pending.pop()!; result.push(current.row); pending.push(...current.children);}
    return result;
  };
  return <Tree className="role-resource-child-tree" blockNode selectable={false} virtual height={280}
    treeData={tree} expandedKeys={expanded} onExpand={onExpanded} disabled={disabled}
    titleRender={node => {
      const row = node.row;
      const summary = row.grantable === true ? {grantable: 1, granted: displayedGrant(draft, row) ? 1 : 0} : summaries.get(node.key)!;
      return <div className="role-resource-child-row" data-resource-id={row.id}>
        {/* This checkbox edits the staged hierarchy; only the platform preview/save action persists it. */}
        <Checkbox aria-label={`${t('roles.resourceConfig.grantSelected', '授权当前资源')} ${displayName(row)}`}
          disabled={disabled || !summary.grantable} checked={!!summary.grantable && summary.granted === summary.grantable}
          indeterminate={summary.granted > 0 && summary.granted < summary.grantable}
          onChange={event => onToggle(affected(node), event.target.checked)}/>
        <span className="role-resource-icon">{icon(row)}</span>
        <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'choose', sort: 0, size: 'small', type: 'text',
          className: 'role-resource-child-name', disabled, text: displayName(row), onClick: () => onChoose(row)}]}/>
        {draft[row.id] && <span className="role-resource-draft-dot" aria-label={t('roles.resourceConfig.pending', '待保存')}/>}
      </div>;
    }}/>
}
