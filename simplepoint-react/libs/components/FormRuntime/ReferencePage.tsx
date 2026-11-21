import {Alert, Skeleton} from 'antd';
import {useState} from 'react';
import {useOwnedData} from '@simplepoint/shared/hooks/useOwnedData';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageActive} from '@simplepoint/shared/page/hooks';
import PageSection from '@simplepoint/shared/page/Section';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import TableActions from '../Table/Actions';
import RecordTable from './RecordTable';
import {useRecordReferences} from './useRecordReferences';
import type {ReferenceLocation} from './referenceLocation';

/** A restored URL carries only a relation identity. The server resolves and authorizes its target anew. */
export default function ReferencePage({reference}: {reference: ReferenceLocation}) {
  const {t} = useI18n(), active = usePageActive();
  const [locked, setLocked] = useState(false);
  const sources = useRecordReferences(reference), binding = sources(reference.relationId);
  const query = useOwnedData(['app', 'reference-page', reference],
    ({signal}) => binding.detail!(reference.recordId, signal),
    {retry: false, staleTime: 0, gcTime: 0, enabled: !locked, refetchOnWindowFocus: false, refetchOnMount: 'always'});
  const usable = active && !query.isFetching && !query.isError;
  return <section style={{height: '100%', minHeight: 0, overflow: 'auto'}}>
    {active && (query.isPending || query.isFetching) && <Skeleton active paragraph={{rows: 6}}/>}
    {active && !query.isFetching && query.isError && <Alert type="error" showIcon title={t('app.runtime.loadFailed', '应用页面加载失败')}
      description={resolveApiErrorMessage(query.error, t('app.runtime.loadFailed', '应用页面加载失败'))}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'retry', sort: 0, icon: 'ReloadOutlined', text: t('table.retry', '重试')},
      ]} onButtonEvents={{retry: () => {if (active) void query.refetch();}}}/>}/>}
    {query.data && !query.isError && <div hidden={!usable}>
      <PageSection selected={usable}>
      <RecordTable key={JSON.stringify([query.data.form.formId, query.data.form.formVersion, query.data.record.id])}
        descriptor={query.data.form} recordId={query.data.record.id} available={usable} onInteractionLockChange={setLocked}/>
      </PageSection>
    </div>}
  </section>;
}
