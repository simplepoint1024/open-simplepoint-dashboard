import {Space, Tag} from 'antd';
import type {Key, ReactNode} from 'react';

export type MetadataNodeType =
  | 'DATA_SOURCE'
  | 'ROOT'
  | 'DATABASE'
  | 'CATALOG'
  | 'SCHEMA'
  | 'TABLE'
  | 'VIEW'
  | 'COLUMN';

export type MetadataPathNodeType = Exclude<MetadataNodeType, 'DATA_SOURCE'>;

export type MetadataPathSegment = {
  type: MetadataPathNodeType;
  name: string;
};

export type MetadataTreeNode = {
  key: Key;
  title: ReactNode;
  rawTitle?: string;
  type: MetadataNodeType;
  typeLabel?: string;
  path: MetadataPathSegment[];
  leaf: boolean;
  actions?: string[];
  dataSourceId?: string;
  dataType?: string | null;
  nullable?: boolean | null;
  defaultValue?: string | null;
  remarks?: string | null;
  isLeaf?: boolean;
  loaded?: boolean;
  children?: MetadataTreeNode[];
};

type MetadataTreeOptions = {
  dataSourceId?: string;
};

type MetadataTreeLabelResolver = (type: MetadataNodeType, fallback?: string | null) => string;

const metadataNodeColorMap: Record<MetadataNodeType, string> = {
  DATA_SOURCE: 'gold',
  ROOT: 'default',
  DATABASE: 'purple',
  CATALOG: 'purple',
  SCHEMA: 'geekblue',
  TABLE: 'blue',
  VIEW: 'cyan',
  COLUMN: 'default',
};

export const renderMetadataTreeTitle = (
  type: MetadataNodeType,
  title: string,
  resolveNodeTypeLabel: MetadataTreeLabelResolver,
  typeLabel?: string | null,
) => (
  <Space size={8}>
    <Tag color={metadataNodeColorMap[type]}>{resolveNodeTypeLabel(type, typeLabel)}</Tag>
    <span>{title}</span>
  </Space>
);

export const normalizeMetadataTreeNodes = (
  nodes: MetadataTreeNode[],
  resolveNodeTypeLabel: MetadataTreeLabelResolver,
  options?: MetadataTreeOptions,
): MetadataTreeNode[] => nodes.map((node) => {
  const rawTitle = typeof node.rawTitle === 'string' ? node.rawTitle : String(node.title);
  const dataSourceId = node.dataSourceId ?? options?.dataSourceId;
  return {
    ...node,
    rawTitle,
    dataSourceId,
    title: renderMetadataTreeTitle(node.type, rawTitle, resolveNodeTypeLabel, node.typeLabel),
    isLeaf: node.leaf,
    loaded: node.loaded ?? Boolean(node.children),
    children: node.leaf || !node.children
      ? undefined
      : normalizeMetadataTreeNodes(node.children, resolveNodeTypeLabel, {dataSourceId}),
  };
});

export const replaceMetadataTreeChildren = (
  nodes: MetadataTreeNode[],
  targetKey: Key,
  children: MetadataTreeNode[],
): MetadataTreeNode[] => nodes.map((node) => {
  if (node.key === targetKey) {
    return {
      ...node,
      children,
      loaded: true,
    };
  }
  if (!node.children || node.children.length === 0) {
    return node;
  }
  return {
    ...node,
    children: replaceMetadataTreeChildren(node.children, targetKey, children),
  };
});

export const findMetadataTreeNodeByKey = (
  nodes: MetadataTreeNode[],
  targetKey: Key,
): MetadataTreeNode | null => {
  for (const node of nodes) {
    if (node.key === targetKey) {
      return node;
    }
    if (!node.children) {
      continue;
    }
    const nested = findMetadataTreeNodeByKey(node.children, targetKey);
    if (nested) {
      return nested;
    }
  }
  return null;
};
