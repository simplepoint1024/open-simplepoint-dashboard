import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useEffect, useMemo, useState} from 'react';
import {Alert, Button, Select, Space, Spin, Tag, Tree, Typography, message} from 'antd';
import type {DataNode} from 'antd/es/tree';
import {useData} from '@simplepoint/shared/api/methods';
import {
  AccessCenterResourceNode,
  fetchResourceTree,
  saveRoleAuthorization,
} from '@/api/system/access-center';
import {
  fetchScopeAssignment,
  updateScopeAssignment,
} from '@/api/system/role';
import {fetchItems as fetchDataScopeItems, DataScopeRelevantVo} from '@/api/system/data-scope';
import {fetchItems as fetchFieldScopeItems, FieldScopeRelevantVo} from '@/api/system/field-scope';
import './index.css';

export interface RoleResourceConfigProps {
  roleId: string;
}

const TYPE_COLOR: Record<string, string> = {
  GROUP: 'default',
  MODULE: 'blue',
  PAGE: 'green',
  FEATURE: 'cyan',
  ACTION: 'orange',
  API: 'purple',
};

const nodeKey = (node: AccessCenterResourceNode) => node.resourceCode || node.code || node.id;

const nodeDisplayName = (node: AccessCenterResourceNode) => (
  node.alias || node.label || node.code || node.resourceCode || node.id
);

const flattenNodes = (nodes: AccessCenterResourceNode[]) => {
  const result: AccessCenterResourceNode[] = [];
  const visit = (node: AccessCenterResourceNode) => {
    result.push(node);
    node.children?.forEach(visit);
  };
  nodes.forEach(visit);
  return result;
};

const checkedCodesFromKeys = (
  keys: React.Key[],
  nodeByKey: Map<string, AccessCenterResourceNode>,
) => {
  const codes = new Set<string>();
  keys.forEach((key) => {
    const node = nodeByKey.get(String(key));
    const code = node?.resourceCode || node?.code;
    if (code && node?.grantable !== false) {
      codes.add(code);
    }
  });
  return Array.from(codes);
};

const App = ({roleId}: RoleResourceConfigProps) => {
  const {t, ensure, locale} = useI18n();
  const [checkedKeys, setCheckedKeys] = useState<React.Key[]>([]);
  const [dataScopeId, setDataScopeId] = useState<string | null>(null);
  const [fieldScopeId, setFieldScopeId] = useState<string | null>(null);
  const [savingResources, setSavingResources] = useState(false);
  const [scopeSaving, setScopeSaving] = useState(false);

  useEffect(() => {
    void ensure(['roles', 'resources', 'access-center', 'data-scopes', 'field-scopes', 'table', 'common']);
  }, [ensure, locale]);

  useEffect(() => {
    setCheckedKeys([]);
    setDataScopeId(null);
    setFieldScopeId(null);
  }, [roleId]);

  const {
    data: resourceTree,
    isFetching: resourceTreeLoading,
    error: resourceTreeError,
    refetch: refetchResourceTree,
  } = useData<AccessCenterResourceNode[]>(
    roleId ? ['roleResourceTree', roleId] : '',
    () => fetchResourceTree(roleId),
    {enabled: !!roleId},
  );

  const {data: scopeAssignment, isFetching: scopeAssignmentLoading, error: scopeAssignmentError} = useData(
    roleId ? ['roleScopeAssignment', roleId] : '',
    () => fetchScopeAssignment(roleId),
    {enabled: !!roleId},
  );

  const {data: dataScopePage, isFetching: dataScopeLoading} = useData(
    ['roleDataScopeItems'],
    () => fetchDataScopeItems(),
  );

  const {data: fieldScopePage, isFetching: fieldScopeLoading} = useData(
    ['roleFieldScopeItems'],
    () => fetchFieldScopeItems(),
  );

  const flatNodes = useMemo(() => flattenNodes(resourceTree ?? []), [resourceTree]);
  const nodeByKey = useMemo(
    () => new Map(flatNodes.map((node) => [String(nodeKey(node)), node])),
    [flatNodes],
  );

  const treeData = useMemo<DataNode[]>(() => {
    const toTreeNode = (node: AccessCenterResourceNode): DataNode => {
      const key = nodeKey(node);
      const code = node.resourceCode || node.code;
      return {
        key,
        title: (
          <Space size={8}>
            <Typography.Text>{nodeDisplayName(node)}</Typography.Text>
            <Tag color={TYPE_COLOR[node.type] ?? 'default'}>
              {t(`resources.type.${node.type}`, node.type)}
            </Tag>
            {code ? <Typography.Text type="secondary" code>{code}</Typography.Text> : null}
            {node.grantable === false ? (
              <Tag color="default">{t('resources.boolean.notGrantable', '仅结构')}</Tag>
            ) : null}
          </Space>
        ),
        disableCheckbox: node.grantable === false,
        children: node.children?.map(toTreeNode),
      };
    };
    return (resourceTree ?? []).map(toTreeNode);
  }, [resourceTree, t]);

  useEffect(() => {
    if (!resourceTree) return;
    setCheckedKeys(flatNodes.filter((node) => node.checked).map(nodeKey));
  }, [resourceTree, flatNodes]);

  useEffect(() => {
    if (!scopeAssignment) return;
    setDataScopeId(scopeAssignment.dataScopeId ?? null);
    setFieldScopeId(scopeAssignment.fieldScopeId ?? null);
  }, [scopeAssignment]);

  const dataScopeOptions = useMemo<{label: string; value: string}[]>(
    () => (dataScopePage?.content ?? []).map((item: DataScopeRelevantVo) => ({
      label: item.name,
      value: item.id,
    })),
    [dataScopePage],
  );

  const fieldScopeOptions = useMemo<{label: string; value: string}[]>(
    () => (fieldScopePage?.content ?? []).map((item: FieldScopeRelevantVo) => ({
      label: item.name,
      value: item.id,
    })),
    [fieldScopePage],
  );

  const selectedResourceCodes = useMemo(
    () => checkedCodesFromKeys(checkedKeys, nodeByKey),
    [checkedKeys, nodeByKey],
  );

  const saveResources = async (nextCheckedKeys: React.Key[]) => {
    if (!roleId) return;
    const previous = checkedKeys;
    setCheckedKeys(nextCheckedKeys);
    try {
      setSavingResources(true);
      await saveRoleAuthorization({
        roleId,
        resourceCodes: checkedCodesFromKeys(nextCheckedKeys, nodeByKey),
        dataScopeId,
        fieldScopeId,
      });
      message.success(t('roles.resourceConfig.resourceUpdateSuccess', '资源授权已更新'));
      void refetchResourceTree?.();
    } catch {
      setCheckedKeys(previous);
      message.error(t('roles.resourceConfig.resourceUpdateFailed', '资源授权更新失败'));
    } finally {
      setSavingResources(false);
    }
  };

  const handleDataScopeChange = async (value?: string | null) => {
    const nextValue = value ?? null;
    const previous = dataScopeId;
    setDataScopeId(nextValue);
    if (!roleId) return;
    try {
      setScopeSaving(true);
      await updateScopeAssignment({roleId, dataScopeId: nextValue, fieldScopeId});
      message.success(t('roles.resourceConfig.scopeUpdateSuccess', '范围配置已保存'));
    } catch {
      setDataScopeId(previous);
      message.error(t('roles.resourceConfig.scopeUpdateFailed', '范围配置保存失败'));
    } finally {
      setScopeSaving(false);
    }
  };

  const handleFieldScopeChange = async (value?: string | null) => {
    const nextValue = value ?? null;
    const previous = fieldScopeId;
    setFieldScopeId(nextValue);
    if (!roleId) return;
    try {
      setScopeSaving(true);
      await updateScopeAssignment({roleId, dataScopeId, fieldScopeId: nextValue});
      message.success(t('roles.resourceConfig.scopeUpdateSuccess', '范围配置已保存'));
    } catch {
      setFieldScopeId(previous);
      message.error(t('roles.resourceConfig.scopeUpdateFailed', '范围配置保存失败'));
    } finally {
      setScopeSaving(false);
    }
  };

  if (!roleId) {
    return <div style={{flex: 1, minHeight: 0}}/>;
  }

  const loadError = resourceTreeError || scopeAssignmentError;
  const loading = resourceTreeLoading || savingResources;

  return (
    <div className="role-resource-config">
      <section className="role-resource-scope">
        <div className="role-resource-section-head">
          <div>
            <Typography.Text strong>{t('roles.resourceConfig.scopeTitle', '授权范围')}</Typography.Text>
            <div className="role-resource-section-subtitle">
              {scopeSaving
                ? t('roles.resourceConfig.saving', '保存中...')
                : t('roles.resourceConfig.scopeSummary', '数据范围与字段范围')}
            </div>
          </div>
        </div>
        <div className="role-resource-scope-grid">
          <label className="role-resource-field">
            <span>{t('roles.resourceConfig.dataScope', '数据权限')}</span>
            <Select
              allowClear
              className="role-resource-select"
              placeholder={t('roles.resourceConfig.noDataScope', '不限制数据范围')}
              value={dataScopeId}
              options={dataScopeOptions}
              loading={dataScopeLoading || scopeAssignmentLoading}
              disabled={scopeSaving}
              notFoundContent={t('table.emptyText', '暂无数据')}
              onChange={handleDataScopeChange}
            />
          </label>
          <label className="role-resource-field">
            <span>{t('roles.resourceConfig.fieldScope', '字段权限')}</span>
            <Select
              allowClear
              className="role-resource-select"
              placeholder={t('roles.resourceConfig.noFieldScope', '不限制字段范围')}
              value={fieldScopeId}
              options={fieldScopeOptions}
              loading={fieldScopeLoading || scopeAssignmentLoading}
              disabled={scopeSaving}
              notFoundContent={t('table.emptyText', '暂无数据')}
              onChange={handleFieldScopeChange}
            />
          </label>
        </div>
      </section>

      {loadError ? (
        <Alert
          className="role-resource-alert"
          type="error"
          showIcon
          message={t('roles.resourceConfig.loadFailed', '资源授权加载失败')}
          action={
            <Button size="small" onClick={() => void refetchResourceTree?.()}>
              {t('table.retry', '重试')}
            </Button>
          }
        />
      ) : null}

      <section className="role-resource-tree-section">
        <div className="role-resource-section-head">
          <div>
            <Typography.Text strong>{t('roles.resourceConfig.resourceTitle', '资源授权')}</Typography.Text>
            <div className="role-resource-section-subtitle">
              {t('roles.resourceConfig.selectedCount', '已授权 {count} 项资源', {count: selectedResourceCodes.length})}
            </div>
          </div>
        </div>
        <div className="role-resource-tree-wrap">
          {resourceTreeLoading && !resourceTree ? (
            <div className="role-resource-spin"><Spin/></div>
          ) : (
            <Tree
              checkable
              blockNode
              defaultExpandAll
              treeData={treeData}
              checkedKeys={checkedKeys}
              disabled={loading}
              onCheck={(keys) => {
                const nextKeys = Array.isArray(keys) ? keys : keys.checked;
                void saveResources(nextKeys);
              }}
            />
          )}
        </div>
      </section>
    </div>
  );
};

export default App;
