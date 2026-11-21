import {useEffect, useState} from 'react';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useData} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {useOwnedData} from '@simplepoint/shared/hooks/useOwnedData';
import {workflowApi, type WorkflowMatter, type WorkflowRemovalBatch} from '@simplepoint/shared/workflow/api';
import type {TableButtonProps} from '../Table';
import {useWorkflowRefresh} from './useWorkflowRefresh';

const isTerminal = (row: WorkflowMatter) => !!row.endedAt &&
  ['COMPLETED', 'REJECTED', 'WITHDRAWN', 'CANCELED'].includes(row.status) && !row.nextInstanceId;

export function useWorkflowHistoryRemoval(enabled: boolean, available: boolean) {
  const {t} = useI18n();
  const operation = useOperationScope('workflow-history-removal');
  const refresh = useWorkflowRefresh();
  const capability = useData(['workflow', 'history-removal-capability'],
    ({signal}) => workflowApi.historyRemovalCapability(signal), {enabled, retry: false});
  const max = capability.data?.maxSelection ?? 0;
  const [rows, setRows] = useState<WorkflowMatter[]>([]);
  const [target, setTarget] = useState<{id: string; instances: {id: string; endedAt: string}[]}>();
  const [error, setError] = useState('');
  const [accepted, setAccepted] = useState<WorkflowRemovalBatch>();
  const progress = useOwnedData(['workflow', 'history-removal-progress', accepted?.id],
    ({signal}) => workflowApi.historyRemovalProgress(accepted!.id, signal),
    {enabled: !!accepted && operation.selected, retry: false,
      refetchInterval: query => query.state.data?.completed === accepted?.total ? false : 2000});
  const batch = progress.data ?? accepted;
  const complete = batch != null && batch.completed === batch.total;
  useEffect(() => {if (complete) void refresh();}, [complete, refresh]);

  const actions: TableButtonProps[] = enabled && max > 0 ? [{
    key: 'historyRemoval', sort: 60, icon: 'DeleteOutlined', danger: true,
    text: t('workflow.historyRemoval.action', '永久删除流程历史'), argumentMinSize: 1, argumentMaxSize: max,
  }] : [];
  const disabled = (selected: WorkflowMatter[]) =>
    !available || !!target || operation.busy || !selected.length || !selected.every(isTerminal);
  const choose = (selected: WorkflowMatter[]) => {
    if (!operation.isSelected() || disabled(selected) || selected.length > max) return;
    setError('');
    setTarget({id: crypto.randomUUID(), instances: selected.map(row => ({id: row.id, endedAt: row.endedAt!}))});
  };
  const submit = async () => {
    if (!target || !enabled || max === 0) return;
    const flight = operation.begin();
    if (!flight) return;
    setError('');
    try {
      const result = await workflowApi.requestHistoryRemoval(target.id, target.instances, flight.signal);
      if (!operation.current(flight.signal)) return;
      setAccepted(result); setTarget(undefined); setRows([]);
    } catch (cause) {
      if (operation.current(flight.signal)) {
        setError(resolveApiErrorMessage(cause, t('workflow.matter.actionFailed', '操作失败')));
      }
    } finally {
      operation.finish(flight);
    }
  };
  return {actions, rows, target, batch, complete, error, busy: operation.busy, choose, disabled, submit,
    close: () => {if (!operation.isBusy()) setTarget(undefined);},
    rowSelection: {
      selectedRowKeys: rows.map(row => row.id),
      onChange: (_keys: React.Key[], selection: WorkflowMatter[]) => setRows(selection),
      getCheckboxProps: (row: WorkflowMatter) => ({disabled: !isTerminal(row)}),
    }};
}
