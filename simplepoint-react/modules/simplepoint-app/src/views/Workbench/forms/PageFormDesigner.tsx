import {lazy, Suspense, useEffect, useState} from 'react';
import {Alert, Spin} from 'antd';
import TableActions from '@simplepoint/components/Table/Actions';
import type {DesignerContract, FormSummary} from '@simplepoint/components/FormDesigner';
import {get, useData} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {DesignPage} from './designPage';
import './style.css';

const FormEditor = lazy(() => import('./FormEditor'));
const ApplicationForms = lazy(() => import('./ApplicationForms'));

/** Resolve the selected page's form by identity; never guess from names or the first form. */
export default function PageFormDesigner({application, page, onClose}: {
  application: {id: string; name: string}; page: DesignPage; onClose: () => void;
}) {
  const {t} = useI18n();
  const [loaded, setLoaded] = useState<{form: FormSummary; contract: DesignerContract}>();
  const base = `/app/applications/${encodeURIComponent(application.id)}/forms`;
  const form = useData(['app', 'page-design-form', application.id, page.formId],
    ({signal}) => get<FormSummary>(base + '/' + encodeURIComponent(page.formId!), undefined, {signal}),
    {enabled: Boolean(page.formId) && !loaded, retry: false, refetchOnMount: 'always', refetchOnWindowFocus: false});
  const contract = useData(['app', 'form-designer-contract', application.id],
    ({signal}) => get<DesignerContract>(base + '/designer-contract', undefined, {signal}),
    {enabled: Boolean(page.formId) && !loaded, retry: false, refetchOnMount: 'always', refetchOnWindowFocus: false});
  useEffect(() => {
    if (!loaded && form.data && contract.data && !form.error && !contract.error && !form.isFetching && !contract.isFetching) {
      setLoaded({form: form.data, contract: contract.data});
    }
  }, [loaded, form.data, contract.data, form.error, contract.error, form.isFetching, contract.isFetching]);
  if (!page.formId) return <Suspense fallback={<Spin/>}>
    <ApplicationForms application={application} page={page} onClose={onClose}/>
  </Suspense>;
  if (!loaded) {
    return <section className="app-form-workspace">
      <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'back', icon: 'ArrowLeftOutlined', sort: 0,
        text: t('app.designer.actionBack', '返回')}]} onButtonEvents={{back: onClose}}/>
      {form.error || contract.error ? <Alert type="error" showIcon title={t('app.designer.loadFailed', '草稿加载失败')}
        action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', icon: 'ReloadOutlined', sort: 0,
          text: t('app.retry', '重试')}]} onButtonEvents={{retry: () => {void form.refetch(); void contract.refetch();}}}/>}/> : <Spin/>}
    </section>;
  }
  return <section className="app-form-workspace"><Suspense fallback={<Spin/>}>
    <FormEditor key={loaded.form.id} applicationId={application.id} form={loaded.form} contract={loaded.contract}
      page={page} backToPages onClose={onClose}/>
  </Suspense></section>;
}
