import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useEffect, useMemo, useState} from 'react';
import {Alert, App as AntdApp, Button, Select, Space, Spin, Tag, Tree, Typography} from 'antd';
import type {DataNode} from 'antd/es/tree';
import {useData} from '@simplepoint/shared/api/methods';
import {createIcon} from '@simplepoint/shared/types/icon';
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

type Translate = (key: string, fallback?: string) => string;

const resolveI18nText = (value: unknown, t?: Translate) => {
  if (typeof value !== 'string') return String(value ?? '');
  if (!value.startsWith('i18n:')) return value;
  const key = value.slice(5);
  return t?.(key, key) ?? key;
};

const nodeDisplayName = (node: AccessCenterResourceNode, t?: Translate) => (
  resolveI18nText(node.alias || node.label || node.code || node.resourceCode || node.id, t)
);

const renderResourceIcon = (node: AccessCenterResourceNode) => {
  if (!node.icon) return null;
  const icon = createIcon(node.icon);
  if (!icon) return null;
  return (
    <span style={{display: 'inline-flex', alignItems: 'center', color: 'var(--ant-color-text-secondary)'}}>
      {icon}
    </span>
  );
};

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
  const {message, modal} = AntdApp.useApp();
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

  const {data: scopeAssignment, isFetching: scopeAssignmentLoading, error: scopeAssignmentError, refetch: refetchScopeAssignment} = useData(
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
            {renderResourceIcon(node)}
            <Typography.Text>{nodeDisplayName(node, t)}</Typography.Text>
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
        updateScope: false,
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

  const saveScope = async () => {
    if (!roleId || scopeAssignmentLoading || scopeAssignmentError) return;
    if (scopeAssignment?.legacyConflict) {
      const confirmed = await modal.confirm({
        title: t('roles.resourceConfig.legacyConflict', '旧授权存在多个范围，是否用当前选择替换全部旧范围？'),
        content: t('roles.resourceConfig.legacyReplacement', '清空数据范围将恢复为默认仅本人；清空字段范围将取消字段限制。请确认两个选择。'),
      });
      if (!confirmed) return;
    }
    setScopeSaving(true);
    try {
      await updateScopeAssignment({
        roleId, dataScopeId, fieldScopeId,
        revision: scopeAssignment?.revision,
        confirmLegacyReplacement: !!scopeAssignment?.legacyConflict,
      });
      await refetchScopeAssignment();
      message.success(t('roles.resourceConfig.scopeUpdateSuccess', '范围配置已保存'));
    } catch {
      await refetchScopeAssignment();
      message.error(t('roles.resourceConfig.scopeUpdateFailed', '范围配置保存失败，请刷新后重试'));
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
        {scopeAssignment?.legacyConflict && <Alert type="warning" showIcon message={t('roles.resourceConfig.legacyConflict', '旧授权存在多个范围，请确认后重新保存')} />}
        <div className="role-resource-scope-grid">
          <label className="role-resource-field">
            <span>{t('roles.resourceConfig.dataScope', '数据权限')}</span>
            <Select
              allowClear
              className="role-resource-select"
              placeholder={t('roles.resourceConfig.noDataScope', '默认仅本人')}
              value={dataScopeId}
              options={dataScopeOptions}
              loading={dataScopeLoading || scopeAssignmentLoading}
              disabled={scopeSaving || scopeAssignmentLoading || !!scopeAssignmentError}
              notFoundContent={t('table.emptyText', '暂无数据')}
              onChange={(value) => setDataScopeId(value ?? null)}
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
              disabled={scopeSaving || scopeAssignmentLoading || !!scopeAssignmentError}
              notFoundContent={t('table.emptyText', '暂无数据')}
              onChange={(value) => setFieldScopeId(value ?? null)}
            />
          </label>
        </div>
        <Button onClick={saveScope} loading={scopeSaving} disabled={scopeAssignmentLoading || !!scopeAssignmentError}>
          {t('roles.resourceConfig.saveScope', '保存范围')}
        </Button>
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
