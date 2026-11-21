import {Alert, Modal} from 'antd';
import type {ReactNode} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageSelected} from '@simplepoint/shared/page/hooks';

export default function DeleteConfirmation({open, pending, error, children, onConfirm, onCancel, className, title}: {
  open: boolean; pending: boolean; error?: string; children: ReactNode;
  onConfirm: () => void; onCancel: () => void; className?: string; title?: ReactNode;
}) {
  const {t} = useI18n();
  const selected = usePageSelected();
  return <Modal wrapClassName={className} open={open && selected} destroyOnHidden={!open}
    title={title ?? t('table.confirmDeleteTitle', '确认删除')}
    confirmLoading={pending} closable={!pending} maskClosable={!pending} keyboard={!pending}
    cancelButtonProps={{disabled: pending}} okButtonProps={{danger: true}}
    onOk={onConfirm} onCancel={onCancel}>
    {children}
    {error && <Alert type="error" showIcon title={error}/>}
  </Modal>;
}
