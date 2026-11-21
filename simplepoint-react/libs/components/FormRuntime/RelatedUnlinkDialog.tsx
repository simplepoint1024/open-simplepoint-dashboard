import {useRecordReferences} from './useRecordReferences';
import {mappingEntry, applyMapping, mappingIntent, type MappingIntent} from './referenceMapping/model';
import {Alert} from 'antd';
import {useMemo, useState} from 'react';
import {post} from '@simplepoint/shared/api/methods';
import {appendQueryParams, isHttpError, resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import ResourceDrawer from '../ResourceDrawer';
import DataTable from '../DataTable';
import TableActions from '../Table/Actions';
import PublishedFormRuntime from './Published';
import type {ApplicationFormRuntimeView} from './contracts';
import {runtimeIdentity, type FormRecord} from './recordTableModel';
import {unlinkCandidates, unlinkValues, type InverseUnlink} from './inverseUnlink';

type Props = {descriptor: ApplicationFormRuntimeView; record: FormRecord; relation: InverseUnlink;
  url: string; onClose: () => void; onCompleted: () => void};

/** A single immutable unlink intent; uncertain transport results retry the identical command. */
export default function RelatedUnlinkDialog({descriptor, record, relation, url, onClose, onCompleted}: Props) {
  const {t} = useI18n(), operation = useOperationScope(url);
  const references = useRecordReferences(descriptor, `${record.id}:${record.version}`, record);
  const candidates = useMemo(() => {
    try {return {rows: unlinkCandidates(record, relation), valid: true};}
    catch {return {rows: [], valid: false};}
  }, [record, relation]);
  const [rowId, setRowId] = useState<string | undefined>(() => candidates.rows.length === 1 ? candidates.rows[0].id : undefined);
  const [error, setError] = useState('');
  const [pending, setPending] = useState<{url: string; body: {
    runtime: ReturnType<typeof runtimeIdentity>; idempotencyKey: string; expectedVersion: number;
    state: FormRecord['state']; referenceMappings?: MappingIntent; values: Record<string, unknown>;
  }}>();
  const locked = operation.busy || !!pending;
  const send = async () => {
    const request = operation.begin(); if (!request) return;
    let intent = pending;
    setError('');
    try {
      if (!intent) {
        let referenceMappings: MappingIntent | undefined;
        const metadata = relation.field.reference;
        if (metadata?.mappings?.some(mapping => mapping.mode !== 'LIVE')) {
          const load = references(metadata.relationId).mapping;
          if (!load || metadata.mappingWritable === false) throw new Error('Mapping is not writable');
          const location = {fieldId: relation.field.fieldId, ...(relation.detailId ? {detailId: relation.detailId, rowKey: rowId} : {})};
          const response = await load(null, [], request.signal, relation.detailId ? {sourceRowId: rowId} : undefined);
          if (!operation.current(request.signal) || !operation.isSelected()) return;
          const entry = mappingEntry(descriptor.document, location, null, [], response);
          const cleared = applyMapping(record.values, [], entry, []);
          referenceMappings = mappingIntent(cleared.draft, cleared.values);
        }
        intent = {url: appendQueryParams(url, rowId ? {sourceRowId: rowId} : undefined), body: {
        runtime: runtimeIdentity(descriptor), idempotencyKey: crypto.randomUUID(), expectedVersion: record.version,
        state: record.state, ...(referenceMappings ? {referenceMappings} : {}), values: unlinkValues(record, relation, rowId),
      }};
      }
      await post(intent.url, intent.body, {signal: request.signal, notifyOnError: false});
      if (operation.current(request.signal)) {setPending(undefined); onCompleted();}
    } catch (cause) {
      if (!operation.current(request.signal)) return;
      const uncertain = !isHttpError(cause) || cause.status === 0 || cause.status >= 500;
      setPending(uncertain ? intent : undefined);
      setError(resolveApiErrorMessage(cause, t('app.runtime.relation.unlinkFailed', '解除关联失败，请刷新记录后重试')));
    } finally {operation.finish(request);}
  };
  return <ResourceDrawer rootClassName="app-record-unlink" title={t('app.runtime.relation.unlink', '解除关联')}
    placement="right" defaultWidth={960} open locked={locked} onClose={onClose}>
    <div style={{display: 'grid', gap: 16, minWidth: 0}}>
      <Alert type="info" showIcon title={t('app.runtime.relation.unlinkConfirm', '解除与当前记录的关联，保留业务数据。')}/>
      <PublishedFormRuntime descriptor={descriptor} record={record} value={record.values}
        systemValues={record.systemValues} systemLabels={record.systemLabels} readOnly/>
      {relation.detailId && <DataTable rowKey="id" dataSource={candidates.rows} defaultOrder="provided"
        settings={false} cards={false} pagination={false} scroll={{y: 240, x: 260}}
        columns={[{key: 'ordinal', dataIndex: 'ordinal', width: 180,
          title: t('app.runtime.relation.detailRow', '选择要解除关联的明细行（序号）')}]}
        rowSelection={{type: 'radio', selectedRowKeys: rowId ? [rowId] : [],
          getCheckboxProps: () => ({disabled: locked}), onChange: keys => {if (!locked) setRowId(keys[0] as string | undefined);}}}/> }
      {(!candidates.valid || error) && <Alert type="error" showIcon
        title={error || t('app.runtime.relation.unlinkFailed', '解除关联失败，请刷新记录后重试')}
        description={pending ? t('app.submission.uncertain', '提交结果正在确认，请重试原请求。') : undefined}/>}
      <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'confirm', sort: 0, type: 'primary', icon: pending ? 'ReloadOutlined' : 'DisconnectOutlined',
          disabled: operation.busy || !candidates.valid || !!relation.detailId && !rowId,
          text: pending ? t('app.retry', '重试') : t('app.runtime.relation.unlink', '解除关联')},
      ]} onButtonEvents={{confirm: () => void send()}}/>
    </div>
  </ResourceDrawer>;
}
