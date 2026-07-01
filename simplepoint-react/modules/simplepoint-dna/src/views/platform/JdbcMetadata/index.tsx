import api from '@/api';
import { DeleteOutlined, EyeOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { get, post } from '@simplepoint/shared/api/methods';
import { useI18n } from '@simplepoint/shared/hooks/useI18n';
import { Button, Card, Col, Descriptions, Empty, Form, Input, InputNumber, Modal, Row, Select, Space, Spin, Switch, Table, Tabs, Tag, Tree, Typography, message } from 'antd';
import type { ColumnsType, TablePaginationConfig } from 'antd/es/table';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  findMetadataTreeNodeByKey,
  normalizeMetadataTreeNodes,
  replaceMetadataTreeChildren,
  type MetadataNodeType,
  type MetadataPathNodeType,
  type MetadataPathSegment,
  type MetadataTreeNode,
} from '../metadataTree';
import { resolveErrorMessage } from '../shared';

const { Paragraph, Text } = Typography;
const dataSourceConfig = api['platform.dna-data-sources'];
const metadataConfig = api['platform.dna-metadata'];

type NodeType = MetadataPathNodeType;
type ConstraintType = 'PRIMARY_KEY' | 'FOREIGN_KEY' | 'UNIQUE' | 'CHECK';
type PathSegment = MetadataPathSegment;

type ColumnDefinition = {
  name: string;
  typeName: string;
  size?: number | null;
  scale?: number | null;
  nullable?: boolean | null;
  defaultValue?: string | null;
  autoIncrement?: boolean | null;
  remarks?: string | null;
};

type ConstraintReference = {
  tablePath?: PathSegment[] | null;
  columns?: string[] | null;
};

type ConstraintDefinition = {
  name?: string | null;
  type: ConstraintType;
  columns?: string[] | null;
  reference?: ConstraintReference | null;
  checkExpression?: string | null;
};

type TableStructure = {
  tablePath: PathSegment[];
  dialectCode?: string | null;
  dialectName?: string | null;
  columns: ColumnDefinition[];
  constraints: ConstraintDefinition[];
};

type DataPreviewPage = {
  columns: string[];
  content: Array<Record<string, unknown>>;
  totalElements: number;
  pageNumber: number;
  pageSize: number;
};

type JdbcDataSourceOption = {
  id: string;
  code?: string;
  name?: string;
  driverName?: string;
  databaseProductName?: string;
};

type NamespaceFormValues = {
  type?: Extract<NodeType, 'DATABASE' | 'CATALOG' | 'SCHEMA'>;
  name?: string;
};

type TableFormValues = {
  name?: string;
  columnsJson?: string;
  constraintsJson?: string;
};

type ViewFormValues = {
  name?: string;
  definitionSql?: string;
};

type ColumnFormValues = {
  currentName?: string;
  name?: string;
  typeName?: string;
  size?: number | null;
  scale?: number | null;
  nullable?: boolean;
  defaultValue?: string;
  autoIncrement?: boolean;
  remarks?: string;
};

type ConstraintFormValues = {
  name?: string;
  type?: ConstraintType;
  columns?: string;
  referenceCatalog?: string;
  referenceSchema?: string;
  referenceTable?: string;
  referenceColumns?: string;
  checkExpression?: string;
};

const buildDisplayType = (column?: ColumnDefinition | null) => {
  if (!column) {
    return '-';
  }
  if (column.size == null || column.size <= 0) {
    return column.typeName || '-';
  }
  if (column.scale == null || column.scale < 0) {
    return `${column.typeName}(${column.size})`;
  }
  return `${column.typeName}(${column.size},${column.scale})`;
};

const parseJsonArray = <T,>(
  value: string | undefined,
  label: string,
  mustBeArrayMessage: string,
  invalidFormatMessage: string,
): T[] => {
  const raw = value?.trim();
  if (!raw) {
    return [];
  }
  try {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) {
      throw new Error(`${label}${mustBeArrayMessage}`);
    }
    return parsed as T[];
  } catch (error) {
    throw new Error(error instanceof Error ? error.message : `${label}${invalidFormatMessage}`);
  }
};

const splitCommaValues = (value?: string) => value?.split(',').map((item) => item.trim()).filter(Boolean) ?? [];

const App = () => {
  const { t, ensure, locale } = useI18n();
  const [dataSources, setDataSources] = useState<JdbcDataSourceOption[]>([]);
  const [selectedDataSourceId, setSelectedDataSourceId] = useState<string>();
  const [treeData, setTreeData] = useState<MetadataTreeNode[]>([]);
  const [selectedNode, setSelectedNode] = useState<MetadataTreeNode | null>(null);
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);
  const [treeLoading, setTreeLoading] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [structure, setStructure] = useState<TableStructure | null>(null);
  const [preview, setPreview] = useState<DataPreviewPage | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewPage, setPreviewPage] = useState({ current: 1, pageSize: 20, total: 0 });
  const [namespaceModalOpen, setNamespaceModalOpen] = useState(false);
  const [tableModalOpen, setTableModalOpen] = useState(false);
  const [viewModalOpen, setViewModalOpen] = useState(false);
  const [columnModalOpen, setColumnModalOpen] = useState(false);
  const [constraintModalOpen, setConstraintModalOpen] = useState(false);
  const [columnMode, setColumnMode] = useState<'add' | 'alter'>('add');
  const [submitting, setSubmitting] = useState(false);
  const [namespaceForm] = Form.useForm<NamespaceFormValues>();
  const [tableForm] = Form.useForm<TableFormValues>();
  const [viewForm] = Form.useForm<ViewFormValues>();
  const [columnForm] = Form.useForm<ColumnFormValues>();
  const [constraintForm] = Form.useForm<ConstraintFormValues>();

  useEffect(() => {
    void ensure(metadataConfig.i18nNamespaces);
  }, [ensure, locale]);

  const resolveNodeTypeLabel = useCallback((type: MetadataNodeType, fallback?: string | null) => {
    return t(`dna.metadata.page.nodeType.${type}`, fallback || type);
  }, [t]);

  const resolveConstraintTypeLabel = useCallback((type: ConstraintType) => {
    return t(`dna.metadata.page.constraintType.${type}`, type);
  }, [t]);

  const loadDataSources = useCallback(async () => {
    try {
      const page = await get<{ content?: JdbcDataSourceOption[] }>(dataSourceConfig.baseUrl, { page: 0, size: 200 });
      const content = page.content ?? [];
      setDataSources(content);
      if (!selectedDataSourceId && content[0]?.id) {
        setSelectedDataSourceId(content[0].id);
      }
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.metadata.page.error.loadDataSources', 'Failed to load data sources.')));
    }
  }, [selectedDataSourceId, t]);

  const loadChildren = useCallback(async (path: PathSegment[]) => {
    if (!selectedDataSourceId) {
      return [];
    }
    const data = await post<MetadataTreeNode[]>(`${metadataConfig.baseUrl}/${selectedDataSourceId}/children`, { path });
    return normalizeMetadataTreeNodes(data ?? [], resolveNodeTypeLabel, {dataSourceId: selectedDataSourceId});
  }, [resolveNodeTypeLabel, selectedDataSourceId]);

  const reloadRoot = useCallback(async () => {
    if (!selectedDataSourceId) {
      setTreeData([]);
      return;
    }
    setTreeLoading(true);
    try {
      const data = await loadChildren([]);
      setTreeData(data);
      setSelectedNode(null);
      setSelectedKeys([]);
      setStructure(null);
      setPreview(null);
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.metadata.page.error.loadTree', 'Failed to load metadata tree.')));
    } finally {
      setTreeLoading(false);
    }
  }, [loadChildren, selectedDataSourceId, t]);

  useEffect(() => {
    void loadDataSources();
  }, [loadDataSources]);

  useEffect(() => {
    void reloadRoot();
  }, [reloadRoot]);

  const relationPath = useMemo<PathSegment[] | null>(() => {
    if (!selectedNode) {
      return null;
    }
    if (selectedNode.type === 'TABLE' || selectedNode.type === 'VIEW') {
      return selectedNode.path;
    }
    if (selectedNode.type === 'COLUMN') {
      return selectedNode.path.slice(0, -1);
    }
    return null;
  }, [selectedNode]);

  const selectedTablePath = useMemo<PathSegment[] | null>(() => {
    if (!relationPath || relationPath.length === 0) {
      return null;
    }
    return relationPath[relationPath.length - 1]?.type === 'TABLE' ? relationPath : null;
  }, [relationPath]);

  const rootNamespaceType = useMemo<Extract<NodeType, 'DATABASE' | 'CATALOG' | 'SCHEMA'> | undefined>(() => {
    const type = treeData[0]?.type;
    if (type === 'DATABASE' || type === 'CATALOG' || type === 'SCHEMA') {
      return type;
    }
    return undefined;
  }, [treeData]);

  const availableNamespaceTypes = useMemo<Extract<NodeType, 'DATABASE' | 'CATALOG' | 'SCHEMA'>[]>(() => {
    if (selectedNode?.type === 'DATABASE' || selectedNode?.type === 'CATALOG') {
      return ['SCHEMA'];
    }
    if (!selectedNode && rootNamespaceType) {
      return [rootNamespaceType];
    }
    return [];
  }, [rootNamespaceType, selectedNode]);

  const canCreateRelationAtRoot = useMemo(() => !rootNamespaceType, [rootNamespaceType]);
  const selectedNamespacePath = useMemo<PathSegment[] | null>(() => {
    if (!selectedNode) {
      return canCreateRelationAtRoot ? [] : null;
    }
    if (selectedNode.type === 'DATABASE' || selectedNode.type === 'CATALOG' || selectedNode.type === 'SCHEMA') {
      return selectedNode.path;
    }
    return null;
  }, [canCreateRelationAtRoot, selectedNode]);

  const loadStructure = useCallback(async (path: PathSegment[]) => {
    if (!selectedDataSourceId) {
      return;
    }
    setDetailLoading(true);
    try {
      const data = await post<TableStructure>(`${metadataConfig.baseUrl}/${selectedDataSourceId}/structure`, { path });
      setStructure(data);
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.metadata.page.error.loadStructure', 'Failed to load table structure.')));
    } finally {
      setDetailLoading(false);
    }
  }, [selectedDataSourceId, t]);

  const loadPreview = useCallback(async (path: PathSegment[], current = 1, pageSize = 20) => {
    if (!selectedDataSourceId) {
      return;
    }
    setPreviewLoading(true);
    try {
      const data = await post<DataPreviewPage>(
        `${metadataConfig.baseUrl}/${selectedDataSourceId}/preview?page=${Math.max(current - 1, 0)}&size=${pageSize}`,
        { path },
      );
      setPreview(data);
      setPreviewPage({
        current: (data.pageNumber ?? 0) + 1,
        pageSize: data.pageSize ?? pageSize,
        total: data.totalElements ?? 0,
      });
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.metadata.page.error.loadPreview', 'Failed to load data preview.')));
    } finally {
      setPreviewLoading(false);
    }
  }, [selectedDataSourceId, t]);

  useEffect(() => {
    if (!relationPath) {
      setStructure(null);
      setPreview(null);
      return;
    }
    void Promise.all([
      loadStructure(relationPath),
      loadPreview(relationPath, 1, previewPage.pageSize),
    ]);
  }, [loadPreview, loadStructure, previewPage.pageSize, relationPath]);

  const handleLoadData = useCallback(async (treeNode: any) => {
    const node = treeNode as MetadataTreeNode;
    if (node.isLeaf) {
      return;
    }
    if (node.children && node.children.length > 0) {
      return;
    }
    try {
      const children = await loadChildren(node.path);
      setTreeData((current) => replaceMetadataTreeChildren(current, node.key, children));
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.metadata.page.error.loadChildren', 'Failed to load child nodes.')));
    }
  }, [loadChildren, t]);

  const handleSelect = useCallback((keys: React.Key[]) => {
    setSelectedKeys(keys);
    const node = keys.length > 0 ? findMetadataTreeNodeByKey(treeData, keys[0]) : null;
    setSelectedNode(node);
  }, [treeData]);

  const handleRefresh = useCallback(async () => {
    await reloadRoot();
    message.success(t('dna.metadata.page.success.refresh', 'Metadata refreshed.'));
  }, [reloadRoot, t]);

  const handlePreviewTableChange = useCallback((pagination: TablePaginationConfig) => {
    if (!relationPath) {
      return;
    }
    void loadPreview(relationPath, pagination.current || 1, pagination.pageSize || 20);
  }, [loadPreview, relationPath]);

  const namespaceOptions = useMemo(() => availableNamespaceTypes.map((type) => ({
    label: t(`dna.metadata.page.option.namespace.${type}`, type),
    value: type,
  })), [availableNamespaceTypes, t]);

  const openNamespaceModal = useCallback(() => {
    namespaceForm.setFieldsValue({ type: availableNamespaceTypes[0], name: '' });
    setNamespaceModalOpen(true);
  }, [availableNamespaceTypes, namespaceForm]);

  const openTableModal = useCallback(() => {
    tableForm.setFieldsValue({
      name: '',
      columnsJson: JSON.stringify([
        { name: 'id', typeName: 'VARCHAR', size: 64, nullable: false },
        { name: 'name', typeName: 'VARCHAR', size: 128, nullable: false },
      ], null, 2),
      constraintsJson: JSON.stringify([
        { name: 'pk_demo', type: 'PRIMARY_KEY', columns: ['id'] },
      ], null, 2),
    });
    setTableModalOpen(true);
  }, [tableForm]);

  const openViewModal = useCallback(() => {
    viewForm.setFieldsValue({
      name: '',
      definitionSql: 'SELECT * FROM your_table',
    });
    setViewModalOpen(true);
  }, [viewForm]);

  const openAddColumnModal = useCallback(() => {
    setColumnMode('add');
    columnForm.setFieldsValue({
      currentName: undefined,
      name: '',
      typeName: 'VARCHAR',
      size: 255,
      scale: null,
      nullable: true,
      defaultValue: '',
      autoIncrement: false,
      remarks: '',
    });
    setColumnModalOpen(true);
  }, [columnForm]);

  const openAlterColumnModal = useCallback(() => {
    const currentName = selectedNode?.type === 'COLUMN' ? selectedNode.path[selectedNode.path.length - 1]?.name : undefined;
    const currentColumn = structure?.columns.find((item) => item.name === currentName);
    setColumnMode('alter');
    columnForm.setFieldsValue({
      currentName,
      name: currentColumn?.name,
      typeName: currentColumn?.typeName,
      size: currentColumn?.size ?? null,
      scale: currentColumn?.scale ?? null,
      nullable: currentColumn?.nullable !== false,
      defaultValue: currentColumn?.defaultValue || '',
      autoIncrement: currentColumn?.autoIncrement === true,
      remarks: currentColumn?.remarks || '',
    });
    setColumnModalOpen(true);
  }, [columnForm, selectedNode, structure?.columns]);

  const openConstraintModal = useCallback(() => {
    constraintForm.setFieldsValue({
      name: '',
      type: 'PRIMARY_KEY',
      columns: '',
      referenceCatalog: '',
      referenceSchema: '',
      referenceTable: '',
      referenceColumns: '',
      checkExpression: '',
    });
    setConstraintModalOpen(true);
  }, [constraintForm]);

  const handleCreateNamespace = useCallback(async () => {
    if (!selectedDataSourceId) {
      return;
    }
    const values = await namespaceForm.validateFields();
    setSubmitting(true);
    try {
      await post(`${metadataConfig.baseUrl}/${selectedDataSourceId}/namespaces`, {
        type: values.type,
        parentPath: selectedNamespacePath ?? [],
        name: values.name?.trim(),
      });
      message.success(t('dna.metadata.page.success.createNamespace', 'Namespace created successfully.'));
      setNamespaceModalOpen(false);
      namespaceForm.resetFields();
      await reloadRoot();
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.metadata.page.error.createNamespace', 'Failed to create namespace.')));
    } finally {
      setSubmitting(false);
    }
  }, [metadataConfig.baseUrl, namespaceForm, reloadRoot, selectedDataSourceId, selectedNamespacePath, t]);

  const handleCreateTable = useCallback(async () => {
    if (!selectedDataSourceId || selectedNamespacePath == null) {
      return;
    }
    const values = await tableForm.validateFields();
    setSubmitting(true);
    try {
      await post(`${metadataConfig.baseUrl}/${selectedDataSourceId}/tables`, {
        parentPath: selectedNamespacePath,
        name: values.name?.trim(),
        columns: parseJsonArray<ColumnDefinition>(
          values.columnsJson,
          t('dna.metadata.page.form.table.columnsJson.label', 'Column Definition JSON'),
          t('dna.metadata.page.validation.mustBeArray', ' must be an array'),
          t('dna.metadata.page.validation.invalidFormat', ' has an invalid format'),
        ),
        constraints: parseJsonArray<ConstraintDefinition>(
          values.constraintsJson,
          t('dna.metadata.page.form.table.constraintsJson.label', 'Constraint Definition JSON'),
          t('dna.metadata.page.validation.mustBeArray', ' must be an array'),
          t('dna.metadata.page.validation.invalidFormat', ' has an invalid format'),
        ),
      });
      message.success(t('dna.metadata.page.success.createTable', 'Table created successfully.'));
      setTableModalOpen(false);
      tableForm.resetFields();
      await reloadRoot();
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.metadata.page.error.createTable', 'Failed to create table.')));
    } finally {
      setSubmitting(false);
    }
  }, [metadataConfig.baseUrl, reloadRoot, selectedDataSourceId, selectedNamespacePath, t, tableForm]);

  const handleCreateView = useCallback(async () => {
    if (!selectedDataSourceId || selectedNamespacePath == null) {
      return;
    }
    const values = await viewForm.validateFields();
    setSubmitting(true);
    try {
      await post(`${metadataConfig.baseUrl}/${selectedDataSourceId}/views`, {
        parentPath: selectedNamespacePath,
        name: values.name?.trim(),
        definitionSql: values.definitionSql?.trim(),
      });
      message.success(t('dna.metadata.page.success.createView', 'View created successfully.'));
      setViewModalOpen(false);
      viewForm.resetFields();
      await reloadRoot();
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.metadata.page.error.createView', 'Failed to create view.')));
    } finally {
      setSubmitting(false);
    }
  }, [metadataConfig.baseUrl, reloadRoot, selectedDataSourceId, selectedNamespacePath, t, viewForm]);

  const handleSubmitColumn = useCallback(async () => {
    if (!selectedDataSourceId || !selectedTablePath) {
      return;
    }
    const values = await columnForm.validateFields();
    setSubmitting(true);
    try {
      const payload = {
        tablePath: selectedTablePath,
        currentName: values.currentName?.trim(),
        column: {
          name: values.name?.trim(),
          typeName: values.typeName?.trim(),
          size: values.size ?? null,
          scale: values.scale ?? null,
          nullable: values.nullable !== false,
          defaultValue: values.defaultValue?.trim() || null,
          autoIncrement: values.autoIncrement === true,
          remarks: values.remarks?.trim() || null,
        },
      };
      await post(
        `${metadataConfig.baseUrl}/${selectedDataSourceId}/${columnMode === 'add' ? 'columns' : 'columns/alter'}`,
        payload,
      );
      message.success(columnMode === 'add'
        ? t('dna.metadata.page.success.addColumn', 'Column added successfully.')
        : t('dna.metadata.page.success.alterColumn', 'Column updated successfully.'));
      setColumnModalOpen(false);
      columnForm.resetFields();
      await reloadRoot();
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        columnMode === 'add'
          ? t('dna.metadata.page.error.addColumn', 'Failed to add column.')
          : t('dna.metadata.page.error.alterColumn', 'Failed to update column.'),
      ));
    } finally {
      setSubmitting(false);
    }
  }, [columnForm, columnMode, metadataConfig.baseUrl, reloadRoot, selectedDataSourceId, selectedTablePath, t]);

  const handleDropColumn = useCallback(async () => {
    if (!selectedDataSourceId || !selectedTablePath || selectedNode?.type !== 'COLUMN') {
      return;
    }
    const columnName = selectedNode.path[selectedNode.path.length - 1]?.name;
    setSubmitting(true);
    try {
      await post(`${metadataConfig.baseUrl}/${selectedDataSourceId}/columns/drop`, {
        tablePath: selectedTablePath,
        columnName,
      });
      message.success(t('dna.metadata.page.success.dropColumn', 'Column deleted successfully.'));
      await reloadRoot();
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.metadata.page.error.dropColumn', 'Failed to delete column.')));
    } finally {
      setSubmitting(false);
    }
  }, [metadataConfig.baseUrl, reloadRoot, selectedDataSourceId, selectedNode, selectedTablePath, t]);

  const handleAddConstraint = useCallback(async () => {
    if (!selectedDataSourceId || !selectedTablePath) {
      return;
    }
    const values = await constraintForm.validateFields();
    setSubmitting(true);
    try {
      const referencePath: PathSegment[] = [];
      if (values.referenceCatalog?.trim()) {
        referencePath.push({ type: rootNamespaceType || 'DATABASE', name: values.referenceCatalog.trim() });
      }
      if (values.referenceSchema?.trim()) {
        referencePath.push({ type: 'SCHEMA', name: values.referenceSchema.trim() });
      }
      if (values.referenceTable?.trim()) {
        referencePath.push({ type: 'TABLE', name: values.referenceTable.trim() });
      }
      await post(`${metadataConfig.baseUrl}/${selectedDataSourceId}/constraints`, {
        tablePath: selectedTablePath,
        constraint: {
          name: values.name?.trim() || null,
          type: values.type,
          columns: splitCommaValues(values.columns),
          reference: values.type === 'FOREIGN_KEY'
            ? {
                tablePath: referencePath,
                columns: splitCommaValues(values.referenceColumns),
              }
            : null,
          checkExpression: values.type === 'CHECK' ? values.checkExpression?.trim() || null : null,
        },
      });
      message.success(t('dna.metadata.page.success.addConstraint', 'Constraint added successfully.'));
      setConstraintModalOpen(false);
      constraintForm.resetFields();
      await reloadRoot();
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.metadata.page.error.addConstraint', 'Failed to add constraint.')));
    } finally {
      setSubmitting(false);
    }
  }, [constraintForm, metadataConfig.baseUrl, reloadRoot, rootNamespaceType, selectedDataSourceId, selectedTablePath, t]);

  const handleDropConstraint = useCallback(async (record: ConstraintDefinition) => {
    if (!selectedDataSourceId || !selectedTablePath) {
      return;
    }
    setSubmitting(true);
    try {
      await post(`${metadataConfig.baseUrl}/${selectedDataSourceId}/constraints/drop`, {
        tablePath: selectedTablePath,
        constraintName: record.name,
        type: record.type,
      });
      message.success(t('dna.metadata.page.success.dropConstraint', 'Constraint deleted successfully.'));
      await reloadRoot();
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.metadata.page.error.dropConstraint', 'Failed to delete constraint.')));
    } finally {
      setSubmitting(false);
    }
  }, [metadataConfig.baseUrl, reloadRoot, selectedDataSourceId, selectedTablePath, t]);

  const handleDropObject = useCallback(async () => {
    if (!selectedDataSourceId || !selectedNode || selectedNode.type === 'COLUMN') {
      return;
    }
    setSubmitting(true);
    try {
      await post(`${metadataConfig.baseUrl}/${selectedDataSourceId}/drop`, {
        path: selectedNode.path,
        cascade: true,
      });
      message.success(t('dna.metadata.page.success.dropObject', 'Object deleted successfully.'));
      await reloadRoot();
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.metadata.page.error.dropObject', 'Failed to delete object.')));
    } finally {
      setSubmitting(false);
    }
  }, [metadataConfig.baseUrl, reloadRoot, selectedDataSourceId, selectedNode, t]);

  const dataSourceLabel = useCallback((item: JdbcDataSourceOption) => {
    const primary = item.name || item.code || item.id;
    const secondary = item.code && item.code !== primary ? ` (${item.code})` : '';
    return `${primary}${secondary}`;
  }, []);

  const columnColumns = useMemo<ColumnsType<ColumnDefinition>>(() => [
    {
      title: t('dna.metadata.page.table.columns.name', 'Column Name'),
      dataIndex: 'name',
      key: 'name',
      width: 180,
    },
    {
      title: t('dna.metadata.page.table.columns.type', 'Type'),
      key: 'type',
      width: 180,
      render: (_value, record) => buildDisplayType(record),
    },
    {
      title: t('dna.metadata.page.table.columns.nullable', 'Nullable'),
      dataIndex: 'nullable',
      key: 'nullable',
      width: 100,
      render: (value?: boolean | null) => (
        <Tag color={value === false ? 'red' : 'green'}>
          {value === false
            ? t('dna.metadata.page.state.no', 'No')
            : t('dna.metadata.page.state.yes', 'Yes')}
        </Tag>
      ),
    },
    {
      title: t('dna.metadata.page.table.columns.defaultValue', 'Default Value'),
      dataIndex: 'defaultValue',
      key: 'defaultValue',
      width: 160,
      ellipsis: true,
      render: (value?: string | null) => value || '-',
    },
    {
      title: t('dna.metadata.page.table.columns.autoIncrement', 'Auto Increment'),
      dataIndex: 'autoIncrement',
      key: 'autoIncrement',
      width: 100,
      render: (value?: boolean | null) => (
        <Tag color={value ? 'blue' : 'default'}>
          {value ? t('dna.metadata.page.state.yes', 'Yes') : t('dna.metadata.page.state.no', 'No')}
        </Tag>
      ),
    },
    {
      title: t('dna.metadata.page.table.columns.remarks', 'Remarks'),
      dataIndex: 'remarks',
      key: 'remarks',
      ellipsis: true,
      render: (value?: string | null) => value || '-',
    },
  ], [t]);

  const constraintColumns = useMemo<ColumnsType<ConstraintDefinition>>(() => [
    {
      title: t('dna.metadata.page.table.constraints.name', 'Constraint Name'),
      dataIndex: 'name',
      key: 'name',
      width: 180,
      render: (value?: string | null) => value || '-',
    },
    {
      title: t('dna.metadata.page.table.constraints.type', 'Type'),
      dataIndex: 'type',
      key: 'type',
      width: 120,
      render: (value: ConstraintType) => <Tag color="blue">{resolveConstraintTypeLabel(value)}</Tag>,
    },
    {
      title: t('dna.metadata.page.table.constraints.columns', 'Columns'),
      dataIndex: 'columns',
      key: 'columns',
      width: 200,
      render: (value?: string[] | null) => value && value.length > 0 ? value.join(', ') : '-',
    },
    {
      title: t('dna.metadata.page.table.constraints.reference', 'Reference'),
      dataIndex: 'reference',
      key: 'reference',
      width: 260,
      render: (value?: ConstraintReference | null) => {
        if (!value?.tablePath?.length) {
          return '-';
        }
        const target = value.tablePath.map((segment) => segment.name).join(' / ');
        const columns = value.columns?.length ? ` (${value.columns.join(', ')})` : '';
        return `${target}${columns}`;
      },
    },
    {
      title: t('dna.metadata.page.table.constraints.checkExpression', 'CHECK Expression'),
      dataIndex: 'checkExpression',
      key: 'checkExpression',
      ellipsis: true,
      render: (value?: string | null) => value || '-',
    },
    {
      title: t('dna.metadata.page.table.constraints.actions', 'Actions'),
      key: 'actions',
      width: 120,
      render: (_value, record) => (
        <Button danger size="small" onClick={() => void handleDropConstraint(record)}>
          {t('dna.metadata.page.table.constraints.drop', 'Delete')}
        </Button>
      ),
    },
  ], [handleDropConstraint, resolveConstraintTypeLabel, t]);

  const previewColumns = useMemo<ColumnsType<Record<string, unknown>>>(() => (
    preview?.columns?.map((name) => ({
      title: name,
      dataIndex: name,
      key: name,
      width: 180,
      ellipsis: true,
      render: (value: unknown) => {
        if (value == null) {
          return '-';
        }
        if (typeof value === 'object') {
          return JSON.stringify(value);
        }
        return String(value);
      },
    })) ?? []
  ), [preview?.columns]);

  const constraintType = Form.useWatch('type', constraintForm);

  return (
    <>
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0, gap: 16 }}>
      <Space style={{ flexShrink: 0 }} wrap>
        <Select
          style={{ width: 320 }}
          placeholder={t('dna.metadata.page.placeholder.selectDataSource', 'Select a data source')}
          value={selectedDataSourceId}
          options={dataSources.map((item) => ({ label: dataSourceLabel(item), value: item.id }))}
          onChange={(value) => setSelectedDataSourceId(value)}
        />
        <Button icon={<ReloadOutlined />} onClick={() => void handleRefresh()} loading={treeLoading}>
          {t('dna.metadata.page.button.refreshTree', 'Refresh Tree')}
        </Button>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          disabled={!selectedDataSourceId || availableNamespaceTypes.length === 0}
          onClick={openNamespaceModal}
        >
          {t('dna.metadata.page.button.createNamespace', 'Create Namespace')}
        </Button>
        <Button
          icon={<PlusOutlined />}
          disabled={!selectedDataSourceId || selectedNamespacePath == null}
          onClick={openTableModal}
        >
          {t('dna.metadata.page.button.createTable', 'Create Table')}
        </Button>
        <Button
          icon={<PlusOutlined />}
          disabled={!selectedDataSourceId || selectedNamespacePath == null}
          onClick={openViewModal}
        >
          {t('dna.metadata.page.button.createView', 'Create View')}
        </Button>
        <Button
          icon={<PlusOutlined />}
          disabled={!selectedDataSourceId || !selectedTablePath}
          onClick={openAddColumnModal}
        >
          {t('dna.metadata.page.button.addColumn', 'Add Column')}
        </Button>
        <Button
          icon={<PlusOutlined />}
          disabled={!selectedDataSourceId || selectedNode?.type !== 'COLUMN'}
          onClick={openAlterColumnModal}
        >
          {t('dna.metadata.page.button.alterColumn', 'Alter Column')}
        </Button>
        <Button
          icon={<PlusOutlined />}
          disabled={!selectedDataSourceId || !selectedTablePath}
          onClick={openConstraintModal}
        >
          {t('dna.metadata.page.button.addConstraint', 'Add Constraint')}
        </Button>
        <Button
          danger
          icon={<DeleteOutlined />}
          disabled={!selectedDataSourceId || !selectedNode || selectedNode.type === 'ROOT'}
          onClick={() => void handleDropObject()}
        >
          {t('dna.metadata.page.button.dropObject', 'Delete Object')}
        </Button>
        <Button
          danger
          disabled={!selectedDataSourceId || selectedNode?.type !== 'COLUMN'}
          onClick={() => void handleDropColumn()}
        >
          {t('dna.metadata.page.button.dropColumn', 'Delete Column')}
        </Button>
      </Space>

      <Row gutter={16} style={{ flex: 1, minHeight: 0 }}>
        <Col span={8} style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
          <Card title={t('dna.metadata.page.card.tree', 'Metadata Tree')} style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }} bodyStyle={{ flex: 1, overflow: 'auto', minHeight: 0 }}>
            {treeLoading ? (
              <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 80 }}>
                <Spin />
              </div>
            ) : treeData.length > 0 ? (
              <Tree
                treeData={treeData}
                loadData={handleLoadData}
                selectedKeys={selectedKeys}
                onSelect={(keys) => handleSelect(keys)}
              />
            ) : (
              <Empty
                description={selectedDataSourceId
                  ? t('dna.metadata.page.empty.noObjects', 'No objects are available for this data source.')
                  : t('dna.metadata.page.empty.selectDataSource', 'Select a data source first.')}
              />
            )}
          </Card>
        </Col>
        <Col span={16} style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
          <Card
            title={t('dna.metadata.page.card.details', 'Object Details')}
            extra={relationPath
              ? (
                <Button icon={<EyeOutlined />} onClick={() => relationPath && void loadPreview(relationPath, 1, previewPage.pageSize)}>
                  {t('dna.metadata.page.button.refreshPreview', 'Refresh Preview')}
                </Button>
              )
              : null}
            style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
            bodyStyle={{ flex: 1, overflow: 'auto', minHeight: 0 }}
          >
            {!selectedNode ? (
              <Empty description={t('dna.metadata.page.empty.selectObject', 'Select an object from the left tree.')} />
            ) : (
              <Space direction="vertical" size={16} style={{ width: '100%' }}>
                <Descriptions size="small" column={2} bordered>
                  <Descriptions.Item label={t('dna.metadata.page.detail.name', 'Name')}>
                    {selectedNode.path[selectedNode.path.length - 1]?.name || '-'}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('dna.metadata.page.detail.type', 'Type')}>
                    {resolveNodeTypeLabel(selectedNode.type, selectedNode.typeLabel)}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('dna.metadata.page.detail.path', 'Path')} span={2}>
                    {selectedNode.path.map((segment) => segment.name).join(' / ') || '/'}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('dna.metadata.page.detail.dataType', 'Data Type')}>
                    {selectedNode.dataType || '-'}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('dna.metadata.page.detail.nullable', 'Nullable')}>
                    {selectedNode.nullable == null
                      ? '-'
                      : selectedNode.nullable
                        ? t('dna.metadata.page.state.yes', 'Yes')
                        : t('dna.metadata.page.state.no', 'No')}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('dna.metadata.page.detail.defaultValue', 'Default Value')}>
                    {selectedNode.defaultValue || '-'}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('dna.metadata.page.detail.remarks', 'Remarks')}>
                    {selectedNode.remarks || '-'}
                  </Descriptions.Item>
                </Descriptions>

                {relationPath ? (
                  <Tabs
                    items={[
                      {
                        key: 'structure',
                        label: t('dna.metadata.page.tab.structure', 'Structure'),
                        children: (
                          <Spin spinning={detailLoading}>
                            <Paragraph>
                              <Text strong>{t('dna.metadata.page.detail.dialect', 'Dialect')}：</Text>
                              {structure?.dialectName || structure?.dialectCode || '-'}
                            </Paragraph>
                            <Table<ColumnDefinition>
                              rowKey={(record) => record.name}
                              columns={columnColumns}
                              dataSource={structure?.columns ?? []}
                              pagination={false}
                              size="small"
                              scroll={{ x: 960 }}
                            />
                            <div style={{ height: 16 }} />
                            <Table<ConstraintDefinition>
                              rowKey={(record) => `${record.type}-${record.name}-${record.columns?.join(',')}`}
                              columns={constraintColumns}
                              dataSource={structure?.constraints ?? []}
                              pagination={false}
                              size="small"
                              scroll={{ x: 1100 }}
                            />
                          </Spin>
                        ),
                      },
                      {
                        key: 'preview',
                        label: t('dna.metadata.page.tab.preview', 'Data Preview'),
                        children: (
                          <Table<Record<string, unknown>>
                            rowKey={(_record, index) => `${previewPage.current}-${index}`}
                            loading={previewLoading}
                            columns={previewColumns}
                            dataSource={preview?.content ?? []}
                            pagination={{
                              current: previewPage.current,
                              pageSize: previewPage.pageSize,
                              total: previewPage.total,
                              showSizeChanger: true,
                            }}
                            onChange={handlePreviewTableChange}
                            size="small"
                            scroll={{ x: 'max-content' }}
                          />
                        ),
                      },
                    ]}
                  />
                ) : null}
              </Space>
            )}
          </Card>
        </Col>
      </Row>
    </div>

      <Modal
        title={t('dna.metadata.page.modal.namespace.title', 'Create Namespace')}
        open={namespaceModalOpen}
        destroyOnHidden
        confirmLoading={submitting}
        onCancel={() => !submitting && setNamespaceModalOpen(false)}
        onOk={() => void handleCreateNamespace()}
      >
        <Form form={namespaceForm} layout="vertical">
          <Form.Item
            label={t('dna.metadata.page.form.namespace.type.label', 'Type')}
            name="type"
            rules={[{ required: true, message: t('dna.metadata.page.form.namespace.type.required', 'Select a namespace type.') }]}
          >
            <Select options={namespaceOptions} />
          </Form.Item>
          <Form.Item
            label={t('dna.metadata.page.form.namespace.name.label', 'Name')}
            name="name"
            rules={[{ required: true, message: t('dna.metadata.page.form.namespace.name.required', 'Enter a name.') }]}
          >
            <Input maxLength={128} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('dna.metadata.page.modal.table.title', 'Create Table')}
        open={tableModalOpen}
        destroyOnHidden
        confirmLoading={submitting}
        width={760}
        onCancel={() => !submitting && setTableModalOpen(false)}
        onOk={() => void handleCreateTable()}
      >
        <Paragraph type="secondary">
          {t(
            'dna.metadata.page.form.table.intro',
            'Use JSON arrays for columns and constraints so primary keys, unique constraints, foreign keys, and CHECK expressions can be expressed completely.',
          )}
        </Paragraph>
        <Form form={tableForm} layout="vertical">
          <Form.Item
            label={t('dna.metadata.page.form.table.name.label', 'Table Name')}
            name="name"
            rules={[{ required: true, message: t('dna.metadata.page.form.table.name.required', 'Enter a table name.') }]}
          >
            <Input maxLength={128} />
          </Form.Item>
          <Form.Item
            label={t('dna.metadata.page.form.table.columnsJson.label', 'Column Definition JSON')}
            name="columnsJson"
            rules={[{ required: true, message: t('dna.metadata.page.form.table.columnsJson.required', 'Enter the column JSON.') }]}
          >
            <Input.TextArea rows={10} />
          </Form.Item>
          <Form.Item label={t('dna.metadata.page.form.table.constraintsJson.label', 'Constraint Definition JSON')} name="constraintsJson">
            <Input.TextArea rows={8} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={t('dna.metadata.page.modal.view.title', 'Create View')}
        open={viewModalOpen}
        destroyOnHidden
        confirmLoading={submitting}
        width={760}
        onCancel={() => !submitting && setViewModalOpen(false)}
        onOk={() => void handleCreateView()}
      >
        <Form form={viewForm} layout="vertical">
          <Form.Item
            label={t('dna.metadata.page.form.view.name.label', 'View Name')}
            name="name"
            rules={[{ required: true, message: t('dna.metadata.page.form.view.name.required', 'Enter a view name.') }]}
          >
            <Input maxLength={128} />
          </Form.Item>
          <Form.Item
            label={t('dna.metadata.page.form.view.definitionSql.label', 'View SQL')}
            name="definitionSql"
            rules={[{ required: true, message: t('dna.metadata.page.form.view.definitionSql.required', 'Enter the view SQL.') }]}
          >
            <Input.TextArea rows={12} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={columnMode === 'add'
          ? t('dna.metadata.page.modal.column.addTitle', 'Add Column')
          : t('dna.metadata.page.modal.column.alterTitle', 'Alter Column')}
        open={columnModalOpen}
        destroyOnHidden
        confirmLoading={submitting}
        onCancel={() => !submitting && setColumnModalOpen(false)}
        onOk={() => void handleSubmitColumn()}
      >
        <Form form={columnForm} layout="vertical" initialValues={{ nullable: true, autoIncrement: false }}>
          {columnMode === 'alter' ? (
            <Form.Item
              label={t('dna.metadata.page.form.column.currentName.label', 'Current Column Name')}
              name="currentName"
              rules={[{ required: true, message: t('dna.metadata.page.form.column.currentName.required', 'Enter the current column name.') }]}
            >
              <Input maxLength={128} />
            </Form.Item>
          ) : null}
          <Form.Item
            label={t('dna.metadata.page.form.column.name.label', 'Column Name')}
            name="name"
            rules={[{ required: true, message: t('dna.metadata.page.form.column.name.required', 'Enter the column name.') }]}
          >
            <Input maxLength={128} />
          </Form.Item>
          <Form.Item
            label={t('dna.metadata.page.form.column.typeName.label', 'Type')}
            name="typeName"
            rules={[{ required: true, message: t('dna.metadata.page.form.column.typeName.required', 'Enter the column type.') }]}
          >
            <Input maxLength={128} placeholder={t('dna.metadata.page.form.column.typeName.placeholder', 'For example VARCHAR / BIGINT / NUMERIC')} />
          </Form.Item>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item label={t('dna.metadata.page.form.column.size.label', 'Length / Precision')} name="size">
                <InputNumber style={{ width: '100%' }} min={1} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item label={t('dna.metadata.page.form.column.scale.label', 'Scale')} name="scale">
                <InputNumber style={{ width: '100%' }} min={0} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item label={t('dna.metadata.page.form.column.defaultValue.label', 'Default Value')} name="defaultValue">
            <Input maxLength={256} placeholder={t('dna.metadata.page.form.column.defaultValue.placeholder', 'For example CURRENT_TIMESTAMP or 0')} />
          </Form.Item>
          <Form.Item label={t('dna.metadata.page.form.column.remarks.label', 'Remarks')} name="remarks">
            <Input.TextArea maxLength={512} rows={3} />
          </Form.Item>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item label={t('dna.metadata.page.form.column.nullable.label', 'Nullable')} name="nullable" valuePropName="checked">
                <Switch />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item label={t('dna.metadata.page.form.column.autoIncrement.label', 'Auto Increment')} name="autoIncrement" valuePropName="checked">
                <Switch />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>

      <Modal
        title={t('dna.metadata.page.modal.constraint.title', 'Add Constraint')}
        open={constraintModalOpen}
        destroyOnHidden
        confirmLoading={submitting}
        width={720}
        onCancel={() => !submitting && setConstraintModalOpen(false)}
        onOk={() => void handleAddConstraint()}
      >
        <Form form={constraintForm} layout="vertical">
          <Form.Item label={t('dna.metadata.page.form.constraint.name.label', 'Constraint Name')} name="name">
            <Input maxLength={128} />
          </Form.Item>
          <Form.Item
            label={t('dna.metadata.page.form.constraint.type.label', 'Constraint Type')}
            name="type"
            rules={[{ required: true, message: t('dna.metadata.page.form.constraint.type.required', 'Select a constraint type.') }]}
          >
            <Select
              options={[
                { label: resolveConstraintTypeLabel('PRIMARY_KEY'), value: 'PRIMARY_KEY' },
                { label: resolveConstraintTypeLabel('UNIQUE'), value: 'UNIQUE' },
                { label: resolveConstraintTypeLabel('FOREIGN_KEY'), value: 'FOREIGN_KEY' },
                { label: resolveConstraintTypeLabel('CHECK'), value: 'CHECK' },
              ]}
            />
          </Form.Item>
          <Form.Item
            label={t('dna.metadata.page.form.constraint.columns.label', 'Local Columns (comma separated)')}
            name="columns"
            rules={[{ required: true, message: t('dna.metadata.page.form.constraint.columns.required', 'Enter local columns.') }]}
          >
            <Input placeholder={t('dna.metadata.page.form.constraint.columns.placeholder', 'For example id,name')} />
          </Form.Item>
          {constraintType === 'FOREIGN_KEY' ? (
            <>
              <Form.Item label={t('dna.metadata.page.form.constraint.referenceCatalog.label', 'Reference Catalog / Database')} name="referenceCatalog">
                <Input placeholder={t('dna.metadata.page.form.constraint.referenceCatalog.placeholder', 'Optional')} />
              </Form.Item>
              <Form.Item label={t('dna.metadata.page.form.constraint.referenceSchema.label', 'Reference Schema')} name="referenceSchema">
                <Input placeholder={t('dna.metadata.page.form.constraint.referenceSchema.placeholder', 'Optional')} />
              </Form.Item>
              <Form.Item
                label={t('dna.metadata.page.form.constraint.referenceTable.label', 'Reference Table')}
                name="referenceTable"
                rules={[{ required: true, message: t('dna.metadata.page.form.constraint.referenceTable.required', 'Enter the reference table.') }]}
              >
                <Input />
              </Form.Item>
              <Form.Item
                label={t('dna.metadata.page.form.constraint.referenceColumns.label', 'Reference Columns (comma separated)')}
                name="referenceColumns"
                rules={[{ required: true, message: t('dna.metadata.page.form.constraint.referenceColumns.required', 'Enter the reference columns.') }]}
              >
                <Input placeholder={t('dna.metadata.page.form.constraint.referenceColumns.placeholder', 'For example id')} />
              </Form.Item>
            </>
          ) : null}
          {constraintType === 'CHECK' ? (
            <Form.Item
              label={t('dna.metadata.page.form.constraint.checkExpression.label', 'CHECK Expression')}
              name="checkExpression"
              rules={[{ required: true, message: t('dna.metadata.page.form.constraint.checkExpression.required', 'Enter the CHECK expression.') }]}
            >
              <Input.TextArea rows={4} placeholder={t('dna.metadata.page.form.constraint.checkExpression.placeholder', 'For example amount >= 0')} />
            </Form.Item>
          ) : null}
        </Form>
      </Modal>
    </>
  );
};

export default App;
