import StatusTag from '../StatusTag';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';

export function WorkflowStatus({value}: {value: string}) {
  const {t} = useI18n();
  const labels: Record<string, string> = {
    APPROVED: t('workflow.status.APPROVED', '通过'), REJECTED: t('workflow.status.REJECTED', '驳回'),
    WITHDRAWN: t('workflow.status.WITHDRAWN', '撤回'), CANCELED: t('workflow.status.CANCELED', '取消'),
    COMPLETED: t('workflow.status.COMPLETED', '已完成'), RUNNING: t('workflow.status.RUNNING', '进行中'),
    PENDING: t('workflow.status.PENDING', '待处理'),
    ACTIVE: t('workflow.status.ACTIVE', '当前处理'),
    STARTED: t('workflow.status.STARTED', '发起'), CLAIMED: t('workflow.status.CLAIMED', '认领'),
    RESUBMITTED: t('workflow.status.RESUBMITTED', '重新发起'),
  };
  return <StatusTag label={labels[value] ?? value} tone={value === 'APPROVED' || value === 'COMPLETED' ? 'success'
    : value === 'REJECTED' ? 'error' : value === 'RUNNING' || value === 'ACTIVE' ? 'processing' : 'neutral'}/>;
}
