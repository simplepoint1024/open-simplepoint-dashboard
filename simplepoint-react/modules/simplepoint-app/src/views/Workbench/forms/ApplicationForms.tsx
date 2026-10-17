import {lazy, Suspense, useState} from 'react';
import {Alert, Space, Spin, Typography} from 'antd';
import {useQueryClient} from '@tanstack/react-query';
import SimpleTable from '@simplepoint/components/SimpleTable';
import TableActions from '@simplepoint/components/Table/Actions';
import type {DesignerContract, FormSummary} from '@simplepoint/components/FormDesigner';
import {get, post, useData} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import type {DesignPage} from './designPage';
import './style.css';

const FormEditor = lazy(() => import('./FormEditor'));

/** Application-owned directory; the platform table owns filtering, pagination and creation. */
export default function ApplicationForms({application, page, onClose}: {application: {id: string; name: string}; page?: DesignPage; onClose: () => void}) {
  const {t} = useI18n(), selected = usePageSelected(), queries = useQueryClient(), scope = useQueryScope();
  const [form, setForm] = useState<FormSummary>();
  const [locked, setLocked] = useState(false);
  const baseUrl = `/app/applications/${encodeURIComponent(application.id)}/forms`;
  const contractQuery = useData(['app', 'form-designer-contract', application.id],
    ({signal}) => get<DesignerContract>(baseUrl + '/designer-contract', undefined, {signal}), {retry: false});
  const open = (record?: FormSummary) => {
    if (!selected || locked || !record || !contractQuery.data || contractQuery.error) return;
    setForm(record);
  };
  const back = () => {
    setForm(undefined);
    void queries.invalidateQueries({queryKey: scopedQueryKey(scope, [baseUrl + '/catalog'])});
  };
  const capability = contractQuery.data && {...contractQuery.data,
    canCreate: contractQuery.data.canCreate && !contractQuery.error,
    canEdit: contractQuery.data.canEdit && !contractQuery.error,
    canPublish: contractQuery.data.canPublish && !contractQuery.error};
  return <section className="app-form-workspace">
    {contractQuery.error && <Alert type="error" showIcon title={t('app.designer.contractFailed', '设计权限和组件目录加载失败')}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0, text: t('app.retry', '重试')}]}
        onButtonEvents={{retry: () => void contractQuery.refetch()}}/>}/>}
    {form && capability ? <Suspense fallback={<Spin/>}><FormEditor key={form.id} applicationId={application.id} form={form} contract={capability} page={page} onClose={back}/></Suspense> : <>
      <Space wrap className="app-form-workspace-heading">
        <Typography.Title level={4} style={{margin: 0}}>{page?.name ?? application.name}</Typography.Title>
        <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'back', sort: 0,
          icon: 'ArrowLeftOutlined', text: page ? t('app.designer.backPages', '返回页面列表') : t('app.designer.backApplications', '返回应用列表'), disabled: locked || !selected}]}
          onButtonEvents={{back: onClose}}/>
      </Space>
      {!capability && !contractQuery.error && <Spin/>}
      <SimpleTable<FormSummary> name={`app.forms.${application.id}`} storageKey="app.workbench.forms"
        baseUrl={baseUrl + '/catalog'} i18nNamespaces={['app-workbench']} confirmDiscard
        onInteractionLockChange={setLocked}
        isButtonDisabled={button => !capability || Boolean(contractQuery.error) || button.key === 'add' && !capability.canCreate}
        customButtonEvents={{design: (_, rows) => open(rows[0])}} onRowDoubleClick={record => open(record)}
        formSchemaTransform={schema => ({...schema, properties: Object.fromEntries(Object.entries(schema.properties ?? {})
          .filter(([key]) => ['code', 'name', 'description'].includes(key)))})}
        onSubmit={(_, data, __, context) => post<FormSummary>(baseUrl,
          {code: data.code, name: data.name, description: data.description ?? null}, {signal: context.signal, notifyOnError: false})}
        afterSubmit={({result}) => {if (selected && result && typeof result === 'object' && 'id' in result) setForm(result as FormSummary);}}/>
    </>}
  </section>;
}
