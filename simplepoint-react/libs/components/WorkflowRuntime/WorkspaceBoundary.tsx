import {Alert, Spin} from 'antd';
import {useEffect, type ReactNode} from 'react';
import {get, useData} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {WorkflowOverview} from '@simplepoint/shared/workflow/api';
import TableActions from '../Table/Actions';

/** The domain classifies organization/platform/personal scopes; an arbitrary stored tenant ID is not proof. */
export default function WorkspaceBoundary({kind, children}: {kind: 'todos' | 'matters'; children: ReactNode}) {
  const {t, ensure, locale} = useI18n();
  useEffect(() => {void ensure(['workflow']);}, [ensure, locale]);
  const query = useData(['workflow', 'overview'], ({signal}) => get<WorkflowOverview>('/workflow/overview', undefined, {signal}), {
    retry: false, staleTime: 30_000,
  });
  const retry = <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{
    key: 'retry', sort: 1, text: t('workflow.retry', '重试'), onClick: () => void query.refetch(),
  }]}/>;
  if (!query.data) return query.error ? <Alert type="error" showIcon title={t('workflow.loadFailed', '数据加载失败')} action={retry}/> : <Spin/>;
  if (!query.data.tenantWorkspace) return <Alert type="info" showIcon title={kind === 'todos'
    ? t('workflow.personal.tenantRequired', '请切换到组织工作空间查看工作流待办')
    : t('workflow.personal.mattersTenantRequired', '请切换到组织工作空间查看流程事项')}/>;
  if (!(kind === 'todos' ? query.data.canViewTodos : query.data.canViewMatters)) return <Alert type="info" showIcon
    title={t('workflow.personal.permissionRequired', '尚未开通此工作流功能，请联系管理员授权')}/>;
  // A transient summary refresh failure must not unmount a user's typed form. Each actual operation remains server-authorized.
  return <div className="workflow-workspace-boundary">
    {query.error && <Alert type="warning" showIcon title={t('workflow.loadFailed', '数据加载失败')} action={retry}/>}
    {children}
  </div>;
}
