import React, {useEffect, useMemo, useState} from 'react';
import {TreeSelect} from 'antd';
import type {WidgetProps} from '@rjsf/utils';
import {get} from '@simplepoint/shared/api/methods';

type OrgNode = {
  id: string;
  name: string;
  parentId?: string | null;
};

const contextPath = '/common';

async function fetchOrgs(): Promise<OrgNode[]> {
  try {
    const data = await get<{content: OrgNode[]}>(`${contextPath}/platform/organizations`, {size: '1000'});
    return data?.content ?? [];
  } catch {
    return [];
  }
}

function buildTreeData(nodes: OrgNode[]): any[] {
  const map = new Map<string, any>();
  nodes.forEach(n => map.set(n.id, {value: n.id, title: n.name, children: []}));
  const roots: any[] = [];
  nodes.forEach(n => {
    if (n.parentId && map.has(n.parentId)) {
      map.get(n.parentId)!.children.push(map.get(n.id));
    } else {
      roots.push(map.get(n.id));
    }
  });
  return roots;
}

/**
 * RJSF custom widget that renders an organisation multi-select tree.
 * Loads organisations from /common/platform/organizations and builds a tree
 * from the parentId relation. Selected values are stored as an array of IDs.
 */
const OrgTreeMultiSelect: React.FC<WidgetProps> = ({id, value, disabled, readonly, onChange, rawErrors}) => {
  const [orgs, setOrgs] = useState<OrgNode[]>([]);

  useEffect(() => {
    void fetchOrgs().then(setOrgs);
  }, []);

  const treeData = useMemo(() => buildTreeData(orgs), [orgs]);

  const status = rawErrors && rawErrors.length > 0 ? 'error' : undefined;

  const selectedValues: string[] = Array.isArray(value) ? value : [];

  return (
    <TreeSelect
      id={id}
      treeData={treeData}
      value={selectedValues}
      multiple
      treeCheckable
      showCheckedStrategy={TreeSelect.SHOW_CHILD}
      disabled={disabled || readonly}
      status={status}
      style={{width: '100%'}}
      treeNodeFilterProp="title"
      showSearch
      placeholder="选择部门"
      onChange={(vals: string[]) => onChange(vals.length > 0 ? vals : undefined)}
    />
  );
};

export default OrgTreeMultiSelect;
