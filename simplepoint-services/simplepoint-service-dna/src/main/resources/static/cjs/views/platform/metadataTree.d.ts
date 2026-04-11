import type { Key, ReactNode } from 'react';
export type MetadataNodeType = 'DATA_SOURCE' | 'ROOT' | 'DATABASE' | 'CATALOG' | 'SCHEMA' | 'TABLE' | 'VIEW' | 'COLUMN';
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
export declare const renderMetadataTreeTitle: (type: MetadataNodeType, title: string, resolveNodeTypeLabel: MetadataTreeLabelResolver, typeLabel?: string | null) => import("react/jsx-runtime").JSX.Element;
export declare const normalizeMetadataTreeNodes: (nodes: MetadataTreeNode[], resolveNodeTypeLabel: MetadataTreeLabelResolver, options?: MetadataTreeOptions) => MetadataTreeNode[];
export declare const replaceMetadataTreeChildren: (nodes: MetadataTreeNode[], targetKey: Key, children: MetadataTreeNode[]) => MetadataTreeNode[];
export declare const findMetadataTreeNodeByKey: (nodes: MetadataTreeNode[], targetKey: Key) => MetadataTreeNode | null;
export {};
