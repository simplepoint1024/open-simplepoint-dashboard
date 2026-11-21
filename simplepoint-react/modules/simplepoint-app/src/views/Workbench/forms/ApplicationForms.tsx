import {lazy, Suspense, useEffect, useRef, useState} from 'react';
import {Alert, Space, Spin, Typography} from 'antd';
import {useQueryClient} from '@tanstack/react-query';
import SimpleTable from '@simplepoint/components/SimpleTable';
import TableActions from '@simplepoint/components/Table/Actions';
import type {DesignerContract, FormSummary} from '@simplepoint/components/FormDesigner';
import {get, post, useData} from '@simplepoint/shared/api/methods';
import {request} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useOwnedData} from '@simplepoint/shared/hooks/useOwnedData';
import {scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import type {DesignPage} from './designPage';
import './style.css';

const FormEditor = lazy(() => import('./FormEditor'));
type Removal = {id: string; formId: string; version: number; completed: boolean};
type RemovalMember = {formId: string; version: number; draftRevision?: number};
const isDraft = (row: FormSummary) => row.status === 'DRAFT' && row.latestVersion === 0;

/** Application-owned directory; the platform table owns filtering, pagination and creation. */
export default function ApplicationForms({application, page, onClose}: {application: {id: string; name: string}; page?: DesignPage; onClose: () => void}) {
  const {t} = useI18n(), selected = usePageSelected(), queries = useQueryClient(), scope = useQueryScope();
  const [form, setForm] = useState<FormSummary>();
  const [locked, setLocked] = useState(false);
  const [removals, setRemovals] = useState<Removal[]>([]);
  const removalIntents = useRef(new Map<string, (RemovalMember & {id: string})[]>());
  const baseUrl = `/app/applications/${encodeURIComponent(application.id)}/forms`;
  const contractQuery = useData(['app', 'form-designer-contract', application.id],
    ({signal}) => get<DesignerContract>(baseUrl + '/designer-contract', undefined, {signal}), {retry: false});
  const storageRemoval = useOwnedData(['app', 'form-removal-capability', application.id],
    ({signal}) => get<{enabled: boolean}>(baseUrl + '/removals/capability', undefined, {signal, notifyOnError: false}),
    {retry: false});
  const progress = useOwnedData(['app', 'form-removals', application.id, removals.map(row => row.formId)],
    ({signal}) => get<Removal[]>(baseUrl + '/removals', {formIds: removals.map(row => row.formId)}, {signal, notifyOnError: false}),
    {enabled: removals.length > 0, retry: false,
      refetchInterval: query => query.state.data?.length === removals.length &&
        query.state.data.every(row => row.completed) ? false : 2500});
  const removed = (progress.data ?? removals).filter(row => row.completed).length;
  useEffect(() => {
    if (removed > 0) void queries.invalidateQueries({queryKey: scopedQueryKey(scope, [baseUrl + '/catalog'])});
  }, [removed, queries, scope, baseUrl]);
  const canRemoveSelection = (records: FormSummary[]) => records.every(isDraft) || storageRemoval.data?.enabled === true &&
    records.every(row => isDraft(row) || row.status !== 'ARCHIVED' && row.latestVersion > 0 && !!row.publishedVersion);
  const remove = async (records: FormSummary[], signal: AbortSignal) => {
    if (records.every(isDraft)) {
      await request<void>(baseUrl, {method: 'DELETE',
        json: records.map(row => ({id: row.id, expectedRevision: row.draftRevision})), signal, notifyOnError: false});
      return;
    }
    const members = records.map(row => ({formId: row.id, version: row.latestVersion,
      draftRevision: isDraft(row) ? row.draftRevision : undefined}))
      .sort((left, right) => left.formId.localeCompare(right.formId));
    const key = JSON.stringify(members);
    const body = removalIntents.current.get(key) ?? members.map(row => ({...row, id: crypto.randomUUID()}));
    removalIntents.current.set(key, body);
    const receipts = await post<Removal[]>(baseUrl + '/removals', body, {signal, notifyOnError: false});
    if (signal.aborted) return;
    removalIntents.current.delete(key);
    setRemovals(receipts);
  };
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
      {removals.length > 0 && <Alert showIcon type={removed === removals.length ? 'success' : 'info'}
        title={t('app.form.removalProgress', '已永久删除 {completed}/{total} 个表单',
          {completed: removed, total: removals.length})}/>}
      <SimpleTable<FormSummary> name={`app.forms.${application.id}`} storageKey="app.workbench.forms"
        baseUrl={baseUrl + '/catalog'} i18nNamespaces={['app-workbench']} confirmDiscard permanentDelete
        onInteractionLockChange={setLocked}
        isButtonDisabled={(button, _, rows) => !capability || Boolean(contractQuery.error)
          || button.key === 'add' && !capability.canCreate
          || button.key === 'delete' && !canRemoveSelection(rows)}
        deleteRows={remove}
        customButtonEvents={{design: (_, rows) => open(rows[0])}} onRowDoubleClick={record => open(record)}
        formSchemaTransform={schema => ({...schema, properties: Object.fromEntries(Object.entries(schema.properties ?? {})
          .filter(([key]) => ['code', 'name', 'description'].includes(key)))})}
        onSubmit={(_, data, __, context) => post<FormSummary>(baseUrl,
          {code: data.code, name: data.name, description: data.description ?? null}, {signal: context.signal, notifyOnError: false})}
        afterSubmit={({result}) => {if (selected && result && typeof result === 'object' && 'id' in result) setForm(result as FormSummary);}}/>
    </>}
  </section>;
}
