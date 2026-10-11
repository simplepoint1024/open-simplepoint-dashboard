import {Alert, Modal, Spin} from 'antd';
import {useData} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import {workflowApi} from '@simplepoint/shared/workflow/api';
import TableActions from '../Table/Actions';
import StartProcess from './StartProcess';

/** The authorized snapshot is loaded once; refetch must never replace an in-progress edit. */
export default function ResubmitProcess({id, onClose}: {id: string; onClose: () => void}) {
  const {t} = useI18n(), selected = usePageSelected();
  const query = useData(['workflow', 'resubmission-form', id], ({signal}) => workflowApi.resubmissionForm(id, signal), {
    retry: false, staleTime: Infinity, gcTime: 0, refetchOnWindowFocus: false, refetchOnReconnect: false,
  });
  if (query.data) return <StartProcess key={id} resubmission={query.data} onClose={onClose}/>;
  return <Modal open={selected} title={t('workflow.action.resubmit', '修改后重新发起')} footer={null} onCancel={onClose}>
    {query.isFetching ? <Spin/> : <Alert showIcon type="error"
      title={resolveApiErrorMessage(query.error, t('workflow.form.loadFailed', '流程表单加载失败，请关闭后重试'))}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 1,
        text: t('workflow.retry', '重试'), onClick: () => void query.refetch()}]}/>}/>}
  </Modal>;
}
