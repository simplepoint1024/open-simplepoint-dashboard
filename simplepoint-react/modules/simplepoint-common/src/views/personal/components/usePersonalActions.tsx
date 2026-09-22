import {useEffect, useRef, useState} from 'react';
import {App} from 'antd';
import type {TableButtonProps} from '@simplepoint/components/Table';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {post, put} from '@simplepoint/shared/api/methods';
import {personalApi} from '@simplepoint/shared/personal/models';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import {canPersonalAction, type PersonalKind, type PersonalRecord} from './actions';
import {usePersonalLabels, usePersonalWrite} from './shared';

/** Schema permissions and versioned operations use the same confirmation path in every view. */
export function usePersonalActions(kind: PersonalKind, callbacks: {
  view?: (record: PersonalRecord) => void;
  edit?: (record: PersonalRecord) => void;
  changed?: (action: string) => void;
} = {}) {
  const {t} = usePersonalLabels();
  const {modal, message} = App.useApp();
  const schema = useSchema(`${personalApi}/${kind}`, {staleTime: 60_000});
  const write = usePersonalWrite();
  const selected = usePageSelected();
  const [confirming, setConfirming] = useState(false);
  const confirmation = useRef<ReturnType<typeof modal.confirm> | null>(null);
  usePageRetention(!write.busy && !confirming, write.busy || confirming);
  useEffect(() => {
    if (!selected) {confirmation.current?.destroy(); confirmation.current = null; setConfirming(false);}
    return () => {confirmation.current?.destroy();};
  }, [selected]);
  const buttons = schema.data?.buttons ?? [];
  const events = Object.fromEntries(buttons.map(button => [button.key, (_keys: React.Key[], rows: PersonalRecord[]) => {
    const record = rows[0];
    if (write.busy || confirmation.current || !canPersonalAction(kind, button.key, record)) return;
    if (button.key === 'view') {if (record) callbacks.view?.(record); return;}
    if (button.key === 'edit') {if (record) callbacks.edit?.(record); return;}
    if (button.key === 'add') return;
    setConfirming(true);
    confirmation.current = modal.confirm({
      title: button.key === 'readAll' ? t('personal.confirmReadAll', '将当前工作空间的全部收件箱消息标为已读？') : t('personal.confirmAction', '确认执行此操作？'),
      content: record?.title,
      afterClose: () => {confirmation.current = null; setConfirming(false);},
      onOk: async () => {
        const result = await write.run(signal => button.key === 'readAll'
          ? put(`${personalApi}/messages/read-all`, {}, {signal})
          : post(`${personalApi}/${kind}/${encodeURIComponent(record.id)}/actions`,
              {version: record.version, action: button.key === 'delete' ? 'DELETE' : button.key}, {signal}), true);
        if (result !== undefined) {message.success(t('personal.saved', '已保存')); callbacks.changed?.(button.key);}
      },
    });
  }]));
  const disabled = (button: TableButtonProps, _keys: React.Key[], rows: PersonalRecord[]) =>
    write.busy || confirming || schema.isFetching || !canPersonalAction(kind, button.key, rows[0]);
  return {buttons, events, disabled, error: write.error, schema, busy: write.busy || confirming};
}
