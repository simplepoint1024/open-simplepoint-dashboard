import {useState} from 'react';
import {Alert, Button, Descriptions, Modal, Spin, Table} from 'antd';
import {get, useData} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import api from '@/api';

interface EffectiveScope {
  userId: string;
  tenantId?: string;
  roleId?: string;
  version: number;
  dataScopeType: string;
  deptIds?: string[];
  includeSelf: boolean;
  fieldPermissions?: Record<string, string>;
}

export default function EffectiveScopePreview() {
  const [open, setOpen] = useState(false);
  const {t} = useI18n();
  const {data, error, isFetching, refetch} = useData<EffectiveScope>(
    ['effectiveDataScope'],
    () => get<EffectiveScope>(`${api['rbac-data-scopes'].baseUrl}/effective`),
    {enabled: open, staleTime: 0},
  );
  return <>
    <Button onClick={() => setOpen(true)} style={{marginBottom: 12}}>
      {t('data-scopes.preview.title', '当前生效权限')}
    </Button>
    <Modal title={t('data-scopes.preview.title', '当前生效权限')} open={open} onCancel={() => setOpen(false)}
      footer={<Button onClick={() => void refetch()} loading={isFetching}>{t('common.refresh', '刷新')}</Button>} width={800}>
      <Alert type="info" showIcon message={t('data-scopes.preview.boundary', '展示当前用户、租户和角色的已保存策略，不是未保存配置的模拟，也不代表具体业务接口已接入数据权限。ALL 仍受租户隔离约束。')} />
      {error ? <Alert type="error" message={t('data-scopes.preview.failed', '权限预览加载失败')} /> : <Spin spinning={isFetching}>
        {data && <>
          <Descriptions column={1} items={[
            {key: 'user', label: t('data-scopes.preview.user', '用户'), children: data.userId},
            {key: 'tenant', label: t('data-scopes.preview.tenant', '租户'), children: data.tenantId ?? '-'},
            {key: 'role', label: t('data-scopes.preview.role', '角色'), children: data.roleId ?? t('data-scopes.preview.allRoles', '全部有效角色合并')},
            {key: 'version', label: t('data-scopes.preview.version', '权限版本'), children: data.version},
            {key: 'scope', label: t('data-scopes.preview.scope', '行范围'), children: data.dataScopeType},
            {key: 'depts', label: t('data-scopes.preview.depts', '组织 ID'), children: data.deptIds?.join(', ') || '-'},
            {key: 'self', label: t('data-scopes.preview.self', '额外包含本人数据'), children: String(data.includeSelf)},
          ]} />
          <Table size="small" rowKey="field" pagination={{pageSize: 10}}
            dataSource={Object.entries(data.fieldPermissions ?? {}).map(([field, access]) => ({field, access}))}
            columns={[
              {title: t('data-scopes.preview.field', '资源字段'), dataIndex: 'field'},
              {title: t('data-scopes.preview.access', '访问级别'), dataIndex: 'access'},
            ]} />
        </>}
      </Spin>}
    </Modal>
  </>;
}
