import {useCallback, useContext, useEffect, useRef, useState} from 'react';
import {Alert, Modal} from 'antd';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {isHttpError, resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {ReferenceRuntimeContext} from '../../FormComponents/Reference/RuntimeContext';
import DataTable from '../../DataTable';
import TableActions from '../../Table/Actions';
import {displayValue} from '../../SForm/displayOnly';
import {sameFormValues, type FormValues} from '../sceneState';
import type {ApplicationFormDocument} from '../contracts';
import type {RecordState} from '../recordTableModel';
import {applyMapping, mappingDefaultIds, mappingEntry, mappingFields, mappingReview, mappingRow,
  type MappingDraft, type MappingEntry, type MappingReviewRow} from './model';
import type {MappingEditor} from './Context';

type Props = {document: ApplicationFormDocument; values: FormValues; draft: MappingDraft; state?: RecordState;
  disabled: boolean; onChange: (values: FormValues, draft: MappingDraft) => void};
type Pending = {entry: MappingEntry; original: FormValues; selected: string[]; rows: MappingReviewRow[]; request: AbortController};

/** One scene owns preparation and confirmation. A target read never creates a second form buffer. */
export function useMappingEditor(props: Props) {
  const {t} = useI18n(), sources = useContext(ReferenceRuntimeContext), operation = useOperationScope();
  const current = useRef(props); current.current = props;
  const [pending, setPending] = useState<Pending>(), [error, setError] = useState('');
  const lease = useRef<{request: AbortController; release?: () => void} | undefined>(undefined);
  const close = useCallback(() => {
    const active = lease.current; lease.current = undefined;
    if (active) {active.request.abort(); active.release?.(); operation.finish(active.request);}
    setPending(undefined);
  }, [operation.finish]);
  useEffect(() => {close(); setError(''); return close;}, [sources, props.document, operation.current, close]);
  useEffect(() => {if (!operation.selected) close();}, [operation.selected, close]);
  const failed = (cause: unknown) => {
    const fallback = t('app.relation.mappingFailed', '无法确认关联带出值，请刷新后重试。');
    setError(isHttpError(cause) ? resolveApiErrorMessage(cause, fallback) : fallback);
  };
  const change: MappingEditor['change'] = (location, target, prepare) => {
    if (current.current.disabled || !sources) return;
    const request = operation.beginRead(); if (!request) return;
    const release = prepare?.();
    if (prepare && !release) {operation.finish(request); return;}
    lease.current = {request, release}; setError('');
    void (async () => {
      try {
        const {document, values, state} = current.current;
        const {source} = mappingFields(document, location);
        if (source.reference!.mappingWritable === false) throw new Error('Mapping is not writable');
        const load = sources(source.reference!.relationId).mapping;
        if (!load) throw new Error('Mapping adapter is unavailable');
        const row = mappingRow(values, location);
        const original = structuredClone(row.values);
        const defaults = mappingDefaultIds(document, values, location, state);
        const response = await load(target, defaults, request.signal, location.detailId ? row.id ? {sourceRowId: row.id} : {newDetailRow: true} : undefined);
        if (!operation.current(request.signal) || !operation.isSelected()) return;
        const entry = mappingEntry(document, location, target, defaults, response);
        if (!sameFormValues(original, mappingRow(current.current.values, location).values)) throw new Error('Mapping source changed');
        const selected = entry.defaults.filter(item => original[item.fieldId] == null || original[item.fieldId] === '').map(item => item.mappingId);
        setPending({entry, original, selected, request, rows: mappingReview(document, values, entry)});
      } catch (cause) {
        if (operation.current(request.signal)) {failed(cause); close();}
      }
    })();
  };
  const confirm = () => {
    if (!pending || current.current.disabled || !operation.isSelected() || !operation.current(pending.request.signal)) return;
    try {
      const {values, draft, onChange} = current.current;
      if (!sameFormValues(pending.original, mappingRow(values, pending.entry.location).values)) throw new Error('Mapping source changed');
      const next = applyMapping(values, draft, pending.entry, pending.selected);
      onChange(next.values, next.draft); close();
    } catch (cause) {failed(cause); close();}
  };
  const rows = pending?.rows ?? [];
  return {editor: {busy: operation.busy, change} satisfies MappingEditor,
    notice: error ? <Alert type="error" showIcon title={error}/> : operation.busy && !pending ? <Alert type="info" showIcon
      title={t('app.relation.mappingLoading', '正在读取关联带出值…')}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'cancel', sort: 0, icon: 'CloseOutlined', text: t('app.relation.mappingCancel', '取消')},
      ]} onButtonEvents={{cancel: close}}/>}/> : null,
    // Modal is an overlay container; comparison, selection and actions use platform components.
    dialog: <Modal open={!!pending && operation.selected} title={t('app.relation.mappingConfirm', '确认关联带出值')}
      width={760} wrapClassName="sp-form-runtime-mapping" maskClosable={false} onCancel={close}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'cancel', sort: 0, icon: 'CloseOutlined', text: t('app.relation.mappingCancel', '取消')},
        {key: 'confirm', sort: 1, icon: 'CheckOutlined', type: 'primary', text: t('app.relation.mappingApply', '确认带入')},
      ]} onButtonEvents={{cancel: close, confirm}}/>}>
      <Alert type="info" showIcon title={t('app.relation.mappingReviewHint', '快照字段由系统写入；勾选默认值会替换对应输入，未勾选的字段保持原值。')}/>
      <DataTable rowKey="key" dataSource={rows} cards={false} settings={false} defaultOrder="provided"
        pagination={false} scroll={{x: 620, y: 320}} rowSelection={{selectedRowKeys: pending?.selected ?? [],
          getCheckboxProps: row => ({disabled: row.snapshot}), onChange: keys => setPending(value => value
            ? {...value, selected: keys.map(String).filter(id => value.entry.defaults.some(item => item.mappingId === id))} : value)}}
        columns={[
          {key: 'field', title: t('app.relation.mappingField', '字段'), width: 180, render: (_, row) => row.field.label},
          {key: 'current', title: t('app.relation.mappingCurrent', '当前值'), width: 210, render: (_, row) => displayValue(row.current)},
          {key: 'suggested', title: t('app.relation.mappingSuggested', '带出值'), width: 210, render: (_, row) => displayValue(row.suggested)},
        ]}/>
    </Modal>};
}
