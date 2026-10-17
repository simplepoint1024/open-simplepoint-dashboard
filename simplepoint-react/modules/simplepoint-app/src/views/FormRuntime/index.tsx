import {runtimeDescriptorKey, type ApplicationFormRuntimeView} from '@simplepoint/components/FormRuntime';
import TableActions from '@simplepoint/components/Table/Actions';
import {get, useData} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {Alert, App, Skeleton} from 'antd';
import {useEffect, useState} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {usePageLocation} from '@simplepoint/shared/page/hooks';
import PageSection from '@simplepoint/shared/page/Section';
import RecordTable from './RecordTable.tsx';

/** Local drafts and outstanding writes belong to exactly one route and authorization scope. */
export default function FormRuntime() {
  const location = usePageLocation();
  const scope = useQueryScope();
  return <RuntimePage key={JSON.stringify([scope, location])} location={location}/>;
}

function RuntimePage({location}: {location: string}) {
  const {t, ensure, locale} = useI18n();
  const route = location.split(/[?#]/)[0].split('/').filter(Boolean);
  const validRoute = route.length === 4 && route[0] === 'app' && route[1] === 'runtime';
  const applicationId = validRoute ? route[2] : undefined;
  const pageId = validRoute ? route[3] : undefined;
  const descriptor = useData<ApplicationFormRuntimeView>(
    ['app', 'runtime', applicationId, pageId],
    ({signal}) => get<ApplicationFormRuntimeView>(
      `/app/runtime/applications/${encodeURIComponent(applicationId!)}/pages/${encodeURIComponent(pageId!)}`,
      undefined, {signal, notifyOnError: false},
    ),
    {enabled: validRoute, retry: false, staleTime: 30_000},
  );
  useEffect(() => {void ensure(['app-workbench']);}, [ensure, locale]);
  if (!validRoute) {
    return <Alert type="error" showIcon title={t('app.runtime.invalidRoute', '应用页面地址无效')}/>;
  }
  return <section style={{height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column'}}>
      {descriptor.isPending && <Skeleton active paragraph={{rows: 6}}/>}
      {descriptor.isError && <Alert type="error" showIcon
        title={t('app.runtime.loadFailed', '应用页面加载失败')}
        description={resolveApiErrorMessage(descriptor.error, t('app.runtime.loadFailed', '应用页面加载失败'))}
        action={<TableActions selectedKeys={[]} selectedRows={[]}
          buttons={[{key: 'retry', sort: 0, text: t('app.retry', '重试')}]}
          onButtonEvents={{retry: () => void descriptor.refetch()}}/>}/>}
      {descriptor.data && <RuntimeWorkspace descriptor={descriptor.data}
        available={!descriptor.isError}/>}
  </section>;
}

function RuntimeWorkspace({descriptor, available}: {
  descriptor: ApplicationFormRuntimeView; available: boolean;
}) {
  const {t} = useI18n();
  const {modal} = App.useApp();
  const [pinned, setPinned] = useState(descriptor);
  const [locked, setLocked] = useState(false);
  const changed = runtimeDescriptorKey(pinned) !== runtimeDescriptorKey(descriptor);
  // Refreshes may discover a new publication, but must never discard an in-progress draft.
  const current = changed ? pinned : descriptor;
  return <>
    {changed && <Alert type="warning" showIcon title={t('app.runtime.versionChanged', '表单已更新，请加载新版本后继续')}
      action={<TableActions selectedKeys={[]} selectedRows={[]}
        buttons={[{key: 'reload', sort: 0, icon: 'ReloadOutlined', disabled: locked, text: t('app.runtime.reload', '加载新版本')}]}
        onButtonEvents={{reload: () => modal.confirm({
          title: t('app.runtime.discardForReload', '加载新版本将丢弃当前未保存内容，是否继续？'),
          onOk: () => setPinned(descriptor),
        })}}/>}/>}
    <div style={{flex: 1, minHeight: 0}}>
      <PageSection selected={available}>
        <RecordTable key={runtimeDescriptorKey(current)} descriptor={current} available={!changed} onInteractionLockChange={setLocked}/>
      </PageSection>
    </div>
  </>;
}
