import {useState} from 'react';
import {App} from 'antd';
import {request, resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {TABLE_PAGE_SIZES} from '@simplepoint/shared/types/pagination';
import type {TableButtonProps} from '../Table';
import {useWorkflowRefresh} from './useWorkflowRefresh';

/** Fences private archive writes to the exact retained list and workspace. */
export function useWorkflowArchive<T extends {id: string}>(path: string, archived: boolean, available: boolean) {
  const {t} = useI18n();
  const {message} = App.useApp();
  const operation = useOperationScope(path);
  const refresh = useWorkflowRefresh();
  const list = JSON.stringify([path, archived]);
  const [selection, setSelection] = useState<{list: string; rows: T[]}>();
  const selectedRows = selection?.list === list ? selection.rows : [];
  const selectedKeys = selectedRows.map(row => row.id);
  const action: TableButtonProps = {
    key: 'personalArchive', sort: 50, argumentMinSize: 1,
    argumentMaxSize: TABLE_PAGE_SIZES[TABLE_PAGE_SIZES.length - 1],
    icon: archived ? 'ExportOutlined' : 'InboxOutlined',
    text: archived ? t('workflow.personalArchive.restore', '移回列表') : t('workflow.personalArchive.archive', '移入归档'),
  };
  const change = async (rows: {id: string}[]) => {
    if (!available || !rows.length) return;
    const flight = operation.begin();
    if (!flight) return;
    try {
      await request<void>(path + '/archived', {method: archived ? 'DELETE' : 'PUT', json: rows.map(row => row.id),
        signal: flight.signal, notifyOnError: false});
      if (!operation.current(flight.signal)) return;
      setSelection(undefined);
      await refresh();
    } catch (error) {
      if (operation.current(flight.signal)) {
        message.error(resolveApiErrorMessage(error, t('workflow.matter.actionFailed', '操作失败')));
      }
    } finally {
      operation.finish(flight);
    }
  };
  return {actions: available ? [action] : [], change, busy: operation.busy, selectedRows, selectedKeys,
    rowSelection: {
      selectedRowKeys: selectedKeys,
      onChange: (_keys: React.Key[], rows: T[]) => setSelection({list, rows}),
    }};
}
