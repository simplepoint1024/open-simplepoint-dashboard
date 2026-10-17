import {Alert, Drawer, Spin} from 'antd';
import {get, useData} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import PageSection from '@simplepoint/shared/page/Section';
import type {ApplicationFormRuntimeView} from '@simplepoint/components/FormRuntime';
import TableActions from '@simplepoint/components/Table/Actions';
import RecordTable from '../../FormRuntime/RecordTable';
import {useState} from 'react';

export default function PublishedFormData({applicationId, formId, pageId, onClose}: {
  applicationId: string; formId: string; pageId: string; onClose: () => void;
}) {
  const {t} = useI18n(), selected = usePageSelected();
  const [locked, setLocked] = useState(false);
  const query = useData(['app', 'published-form-data', applicationId, pageId], ({signal}) =>
    get<ApplicationFormRuntimeView>(`/app/runtime/applications/${encodeURIComponent(applicationId)}/pages/${encodeURIComponent(pageId)}`,
      undefined, {signal, notifyOnError: false}), {retry: false});
  return <Drawer rootClassName="app-published-form-data" title={query.data?.pageName ?? t('app.publication.viewData', '查看数据')}
    open={selected} width="85%" onClose={() => {if (!locked) onClose();}} closable={!locked} maskClosable={!locked} keyboard={!locked}>
    {query.isPending && <Spin/>}
    {(query.error || query.data && query.data.formId !== formId) && <Alert type="error" showIcon title={t('app.publication.dataUnavailable', '数据页面暂不可用，请确认已获得该页面的数据权限。')}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0, text: t('app.retry', '重试')}]}
        onButtonEvents={{retry: () => void query.refetch()}}/>}/>}
    {query.data && query.data.formId === formId && !query.error && <PageSection selected={selected}>
      <RecordTable descriptor={query.data} onInteractionLockChange={setLocked}/>
    </PageSection>}
  </Drawer>;
}
