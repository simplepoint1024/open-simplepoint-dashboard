import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useEffect, useMemo, useState} from 'react';
import {Alert, Button, Space, Spin, Tag, Tree, Typography, message} from 'antd';
import type {DataNode} from 'antd/es/tree';
import {useData, usePage} from '@simplepoint/shared/api/methods';
import {fetchAuthorized, fetchAuthorize, fetchUnauthorized} from '@/api/platform/application';
import {fetchItems, ResourceRelevantVo} from '@/api/system/resource';

export interface ApplicationResourceConfigProps {
  applicationCode: string;
}

const TYPE_COLOR: Record<string, string> = {
  GROUP: 'default',
  MODULE: 'blue',
  PAGE: 'green',
  FEATURE: 'cyan',
  ACTION: 'orange',
  API: 'purple',
};

const flattenResources = (resources: ResourceRelevantVo[]) => {
  const result: ResourceRelevantVo[] = [];
  const visit = (resource: ResourceRelevantVo) => {
    result.push(resource);
    resource.children?.forEach(visit);
  };
  resources.forEach(visit);
  return result;
};

const grantableCodesFromKeys = (
  keys: React.Key[],
  resourceByKey: Map<string, ResourceRelevantVo>,
) => {
  const codes = new Set<string>();
  keys.forEach((key) => {
    const resource = resourceByKey.get(String(key));
    if (resource?.code && resource.grantable !== false) {
      codes.add(resource.code);
    }
  });
  return Array.from(codes);
};

const App = ({applicationCode}: ApplicationResourceConfigProps) => {
  const {t, ensure, locale} = useI18n();
  const [checkedKeys, setCheckedKeys] = useState<React.Key[]>([]);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    void ensure(['resources', 'applications', 'table', 'common']);
  }, [ensure, locale]);

  const {data: page, isFetching: resourcesLoading, error: resourcesError, refetch: refetchResources} = usePage<ResourceRelevantVo>(
    ['application-resource-items'],
    () => fetchItems({page: '0', size: '100000', sort: 'sort,asc'}),
  );

  const {
    data: authorized,
    isFetching: authorizedLoading,
    error: authorizedError,
    refetch: refetchAuthorized,
  } = useData<string[]>(
    applicationCode ? ['applicationAuthorizedResources', applicationCode] : '',
    () => fetchAuthorized({applicationCode}),
    {enabled: !!applicationCode},
  );

  const resources = page?.content ?? [];
  const flatResources = useMemo(() => flattenResources(resources), [resources]);
  const resourceByKey = useMemo(
    () => new Map(flatResources.map((resource) => [resource.code || resource.id, resource])),
    [flatResources],
  );

  const treeData = useMemo<DataNode[]>(() => {
    const toTreeNode = (resource: ResourceRelevantVo): DataNode => ({
      key: resource.code || resource.id,
      title: (
        <Space size={8}>
          <Typography.Text>{resource.label || resource.title || resource.name || resource.code}</Typography.Text>
          <Tag color={TYPE_COLOR[resource.type] ?? 'default'}>
            {t(`resources.type.${resource.type}`, resource.type)}
          </Tag>
          <Typography.Text type="secondary" code>{resource.code}</Typography.Text>
          {resource.grantable === false ? (
            <Tag color="default">{t('resources.boolean.notGrantable', '仅结构')}</Tag>
          ) : null}
        </Space>
      ),
      disableCheckbox: resource.grantable === false,
      children: resource.children?.map(toTreeNode),
    });
    return resources.map(toTreeNode);
  }, [resources, t]);

  useEffect(() => {
    setCheckedKeys([]);
  }, [applicationCode]);

  useEffect(() => {
    if (authorized) {
      setCheckedKeys(authorized);
    }
  }, [authorized]);

  const selectedResourceCodes = useMemo(
    () => grantableCodesFromKeys(checkedKeys, resourceByKey),
    [checkedKeys, resourceByKey],
  );

  const handleCheck = async (keys: React.Key[] | {checked: React.Key[]; halfChecked: React.Key[]}) => {
    if (!applicationCode) return;
    const nextKeys = Array.isArray(keys) ? keys : keys.checked;
    const previousKeys = checkedKeys;
    const previousCodes = new Set(grantableCodesFromKeys(previousKeys, resourceByKey));
    const nextCodes = new Set(grantableCodesFromKeys(nextKeys, resourceByKey));
    const added = Array.from(nextCodes).filter((code) => !previousCodes.has(code));
    const removed = Array.from(previousCodes).filter((code) => !nextCodes.has(code));

    setCheckedKeys(nextKeys);
    if (!added.length && !removed.length) return;

    try {
      setSaving(true);
      if (added.length) {
        await fetchAuthorize({applicationCode, resourceCodes: added});
      }
      if (removed.length) {
        await fetchUnauthorized({applicationCode, resourceCodes: removed});
      }
      message.success(t('applications.resourceConfig.updateSuccess', '应用资源已更新'));
      void refetchAuthorized?.();
    } catch {
      setCheckedKeys(previousKeys);
      message.error(t('applications.resourceConfig.updateFailed', '应用资源更新失败'));
    } finally {
      setSaving(false);
    }
  };

  if (!applicationCode) {
    return <div style={{flex: 1, minHeight: 0}}/>;
  }

  const loading = resourcesLoading || authorizedLoading || saving;
  const error = resourcesError || authorizedError;

  return (
    <div style={{height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column', gap: 12}}>
      <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12}}>
        <div>
          <Typography.Text strong>{t('applications.resourceConfig.title', '应用资源')}</Typography.Text>
          <div style={{fontSize: 12, color: 'var(--ant-color-text-tertiary)'}}>
            {t('applications.resourceConfig.selectedCount', '已分配 {count} 项资源', {count: selectedResourceCodes.length})}
          </div>
        </div>
      </div>
      {error ? (
        <Alert
          type="error"
          showIcon
          message={t('applications.resourceConfig.loadFailed', '应用资源加载失败')}
          action={
            <Button size="small" onClick={() => {
              void refetchResources?.();
              void refetchAuthorized?.();
            }}>
              {t('table.retry', '重试')}
            </Button>
          }
        />
      ) : null}
      <div style={{
        flex: 1,
        minHeight: 0,
        overflow: 'auto',
        padding: 12,
        border: '1px solid var(--ant-color-border-secondary)',
        borderRadius: 6,
        background: 'var(--ant-color-bg-container)',
      }}>
        {resourcesLoading && !resources.length ? (
          <div style={{height: 180, display: 'flex', alignItems: 'center', justifyContent: 'center'}}><Spin/></div>
        ) : (
          <Tree
            checkable
            blockNode
            defaultExpandAll
            treeData={treeData}
            checkedKeys={checkedKeys}
            disabled={loading}
            onCheck={(keys) => void handleCheck(keys as React.Key[])}
          />
        )}
      </div>
    </div>
  );
};

export default App;
