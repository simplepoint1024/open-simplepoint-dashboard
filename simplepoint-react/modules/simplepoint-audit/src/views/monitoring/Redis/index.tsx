import {useCallback, useEffect, useLayoutEffect, useRef, useState} from 'react';
import {Alert, App as AntApp, Descriptions, Input, Modal, Space, Spin, Tag, Typography} from 'antd';
import SForm from '@simplepoint/components/SForm/Lazy';
import type {SFormHandle} from '@simplepoint/components/SForm';
import DataTable, {type ColumnsType} from '@simplepoint/components/DataTable';
import TableActions from '@simplepoint/components/Table/Actions';
import {resolveSimpleTableLoadError} from '@simplepoint/components/SimpleTable/errorMessage';
import {get, post, put} from '@simplepoint/shared/api/methods';
import {request, resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import {canEditRedisDetail} from './snapshot';
import {useRedisQuery, type RedisRow} from './useRedisQuery';
import api from '@/api';
import './index.css';

const {Text} = Typography;
const baseConfig = api['monitoring-redis'];
type RedisDetail = RedisRow & {value?: string; sampled?: boolean; truncated?: boolean; valueEncoding?: string};
type Editor = {key: string; value: string; persistent: boolean; ttlSeconds?: number | null};
const colors: Record<string, string> = {STRING: 'green', HASH: 'blue', LIST: 'purple', SET: 'orange', ZSET: 'gold', STREAM: 'cyan'};

export default function RedisPage() {
  const scope = useQueryScope();
  return <ScopedRedisPage key={JSON.stringify(scope)}/>;
}

function ScopedRedisPage() {
  const {ensure, locale, t} = useI18n();
  const [pattern, setPattern] = useState('*');
  const [draft, setDraft] = useState('*');
  const [blocked, setBlocked] = useState(false);
  const selected = usePageSelected();
  useEffect(() => {void ensure(baseConfig.i18nNamespaces);}, [ensure, locale]);
  return <div className="monitoring-redis-page">
    <Space className="monitoring-redis-scope" wrap>
      <label htmlFor="redis-scan-pattern">{t('monitoring.redis.scanScope', '扫描范围（Redis glob）')}</label>
      <Input id="redis-scan-pattern" maxLength={512} value={draft} style={{width: 300}} disabled={blocked || !selected}
        onChange={event => {if (!blocked && selected) setDraft(event.target.value);}}
        onPressEnter={() => {if (!blocked && selected) setPattern(draft || '*');}} placeholder="simplepoint:*"/>
      <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'applyScope', sort: 10,
        text: t('monitoring.redis.applyScope', '应用范围'), disabled: blocked || !selected || (draft || '*') === pattern,
        onClick: () => {if (!blocked && selected) setPattern(draft || '*');}}]}/>
      <Text type="secondary">{t('monitoring.redis.queryScopeHint', '表头筛选和视图作用于当前扫描范围；值内容按需查看。')}</Text>
    </Space>
    <RedisTable key={pattern} pattern={pattern} onBlockedChange={setBlocked}/>
  </div>;
}

function RedisTable({pattern, onBlockedChange}: {pattern: string; onBlockedChange: (blocked: boolean) => void}) {
  const {locale, t} = useI18n();
  const {message} = AntApp.useApp();
  const operation = useOperationScope(), selected = operation.selected, saving = operation.busy;
  const query = useRedisQuery(baseConfig.baseUrl, pattern);
  const form = useRef<SFormHandle>(null);
  const [formData, setFormData] = useState<Partial<Editor>>({});
  const persistent = formData.persistent;
  const [editorMode, setEditorMode] = useState<'create' | 'edit'>('create');
  const [editorOpen, setEditorOpen] = useState(false);
  const [detailOpen, setDetailOpen] = useState(false);
  const [detail, setDetail] = useState<RedisDetail>();
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string>();
  const [dirty, setDirty] = useState(false);
  const [discard, setDiscard] = useState(false);
  const [deleting, setDeleting] = useState<RedisRow>();
  const [writeError, setWriteError] = useState('');
  const reader = useRef<{controller: AbortController; key: string; editing: boolean} | undefined>(undefined);
  const panel = useRef<HTMLDivElement>(null);
  const [scrollY, setScrollY] = useState(320);
  const locked = editorOpen || detailOpen || !!deleting || saving;
  usePageRetention(!locked, editorOpen || !!deleting || saving);
  useLayoutEffect(() => {onBlockedChange(locked); return () => onBlockedChange(false);}, [locked, onBlockedChange]);
  const allowed = (key: string) => !query.error && query.buttons.some(button => button.key === key);
  useEffect(() => () => reader.current?.controller.abort(), []);
  useLayoutEffect(() => {
    const update = () => setScrollY(Math.max(120, (panel.current?.clientHeight ?? 300) - 110));
    const observer = new ResizeObserver(update);
    if (panel.current) observer.observe(panel.current);
    update();
    return () => observer.disconnect();
  }, []);

  const closeDetail = () => {
    reader.current?.controller.abort();
    setDetailOpen(false);
  };
  const discardEditor = () => {
    reader.current?.controller.abort();
    setEditorOpen(false); setDirty(false); setFormData({}); setDiscard(false); setWriteError('');
  };
  const closeEditor = () => {
    if (saving || !operation.isSelected()) return;
    if (!dirty) return discardEditor();
    setDiscard(true);
  };
  const create = () => {
    if (locked || !operation.isSelected() || !allowed('add')) return;
    reader.current?.controller.abort();
    setDetail(undefined); setDetailError(undefined); setDetailLoading(false);
    setEditorMode('create'); setEditorOpen(true); setDirty(false); setWriteError('');
    setFormData({key: '', value: '', persistent: true});
  };
  const read = useCallback(async (key: string, editing: boolean, retry = false) => {
    if (!operation.isSelected() || saving || !key || query.error || !query.buttons.some(button => button.key === (editing ? 'edit' : 'view'))
      || locked && !(retry && reader.current?.key === key && reader.current.editing === editing)) return;
    reader.current?.controller.abort();
    const current = {key, editing, controller: new AbortController()};
    reader.current = current;
    setDetail(undefined); setDetailError(undefined); setDetailLoading(true); setWriteError('');
    if (editing) {
      setFormData({}); setDirty(false); setEditorMode('edit'); setEditorOpen(true);
    } else setDetailOpen(true);
    try {
      const loaded = await get<RedisDetail>(baseConfig.baseUrl + '/detail', {key}, {signal: current.controller.signal});
      if (current !== reader.current || !operation.current(current.controller.signal)) return;
      if (loaded.key !== key) throw new Error(t('monitoring.redis.identityMismatch', '返回的键名与请求不一致，已阻止操作。'));
      setDetail(loaded);
      if (editing) {
        if (!canEditRedisDetail(loaded)) {
          setDetailError(t('monitoring.redis.incompleteEdit', '当前值不可完整编辑，请查看详情；不会用摘要或脱敏值覆盖原值。'));
          return;
        }
        setFormData({key: loaded.key, value: loaded.value!, persistent: loaded.persistent as boolean,
          ttlSeconds: loaded.persistent === true ? undefined : loaded.ttlSeconds as number});
      }
    } catch (error) {
      if (current === reader.current && operation.current(current.controller.signal)) {
        setDetailError(resolveApiErrorMessage(error, t('monitoring.redis.detailFailed', '键详情加载失败，请重试。')));
      }
    } finally {
      if (current === reader.current && operation.current(current.controller.signal)) setDetailLoading(false);
    }
  }, [t, saving, locked, query.error, query.buttons, operation.isSelected, operation.current]);

  const submit = async (values: Editor) => {
    if (!editorOpen || detailLoading || detailError || !allowed(editorMode === 'create' ? 'add' : 'edit')) return;
    if (editorMode === 'edit' && (!canEditRedisDetail(detail) || detail?.key !== values.key)) {
      setWriteError(t('monitoring.redis.incompleteEdit', '当前值不可完整编辑，请查看详情；不会用摘要或脱敏值覆盖原值。')); return;
    }
    const controller = operation.begin(); if (!controller) return; setWriteError('');
    try {
      const payload = {...values, ttlSeconds: values.persistent ? null : values.ttlSeconds};
      if (editorMode === 'create') await post(baseConfig.baseUrl, payload, {signal: controller.signal, notifyOnError: false});
      else await put(baseConfig.baseUrl, payload, {signal: controller.signal, notifyOnError: false});
      if (!operation.current(controller.signal)) return;
      message.success(editorMode === 'create' ? t('table.addSuccess', '新增成功') : t('table.editSuccess', '修改成功'));
      discardEditor();
      query.refresh();
    } catch (error) {
      if (operation.current(controller.signal)) setWriteError(resolveApiErrorMessage(error, t('error.operationFailed', '操作失败，请重试')));
    } finally {operation.finish(controller);}
  };
  const remove = (row: RedisRow) => {
    if (locked || !operation.isSelected() || !allowed('delete') || !row.key) return;
    setWriteError(''); setDeleting(structuredClone(row));
  };
  const confirmDelete = async () => {
    if (!deleting?.key || !allowed('delete')) return;
    const captured = deleting.key, controller = operation.begin(); if (!controller) return; setWriteError('');
    try {
      await request(baseConfig.baseUrl, {method: 'DELETE', params: {keys: captured}, signal: controller.signal, notifyOnError: false});
      if (!operation.current(controller.signal)) return;
      setDeleting(undefined); message.success(t('table.deleteSuccess', '删除成功')); query.refresh();
    } catch (error) {
      if (operation.current(controller.signal)) setWriteError(resolveApiErrorMessage(error, t('error.operationFailed', '操作失败，请重试')));
    } finally {operation.finish(controller);}
  };
  const shownBoolean = (value: unknown) => typeof value === 'boolean'
    ? value ? t('common.yes', '是') : t('common.no', '否') : String(value ?? '--');
  const time = (value?: string | null) => value && Number.isFinite(Date.parse(value))
    ? new Date(value).toLocaleString(locale) : value ?? '--';
  const definitions: ColumnsType<RedisRow> = [
    {title: t('monitoring.redis.key', 'Key'), dataIndex: 'key', key: 'key', width: 270,
      render: value => <Text code copyable={{text: value}}>{value}</Text>},
    {title: t('monitoring.redis.type', 'Type'), dataIndex: 'type', key: 'type', width: 110,
      render: value => <Tag color={colors[value]}>{value ?? '--'}</Tag>},
    {title: t('monitoring.redis.ttlObserved', '观测 TTL（秒）'), dataIndex: 'ttlSeconds', key: 'ttlSeconds', width: 150},
    {title: t('monitoring.redis.permanentFlag', '永久'), dataIndex: 'persistent', key: 'persistent', width: 110,
      render: shownBoolean},
    {title: t('monitoring.redis.sizeObserved', '观测大小 / 元素数'), dataIndex: 'size', key: 'size', width: 180},
    {title: t('monitoring.redis.stringKey', '字符串键'), dataIndex: 'editable', key: 'editable', width: 120,
      render: shownBoolean},
    {title: t('monitoring.redis.observedAt', '观测时间'), dataIndex: 'observedAt', key: 'observedAt', width: 190,
      render: time},
    {title: t('monitoring.redis.actions', '操作'), key: 'actions', width: 300,
      render: (_, row) => <TableActions<RedisRow> selectedKeys={[row.key]} selectedRows={[row]}
        buttons={query.buttons.filter(button => button.key !== 'add')}
        isButtonDisabled={button => locked || !selected || !!query.error || button.key === 'edit' && row.editable !== true}
        onButtonEvents={{view: () => {void read(row.key, false);}, edit: () => {void read(row.key, true);},
          delete: () => remove(row)}}/>},
  ];
  const columns = definitions.filter(column => !('dataIndex' in column) || !column.dataIndex
    || !!query.schema?.properties[String(column.dataIndex)]);
  const status = query.scan?.status;
  const failed = status && !['READY', 'BUILDING', 'BUSY'].includes(status);
  const scanTitle = status === 'READY' ? t('monitoring.redis.scanReady', '扫描结果已就绪')
    : status === 'BUILDING' ? t('monitoring.redis.scanning', '正在后台扫描')
    : status === 'BUSY' ? t('monitoring.redis.scanBusy', '正在等待其他扫描完成')
    : failed ? t('monitoring.redis.scanUnavailable', '扫描结果不可用')
    : t('monitoring.redis.scanPreparing', '正在准备查询');
  const detailsError = detailError && <Alert type="error" showIcon title={detailError}
    action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retryDetail', sort: 10,
      text: t('table.retry', '重试'), onClick: () => {
      if (reader.current) void read(reader.current.key, reader.current.editing, true);
    }}]}/>}/>;
  return <>
    <Alert className="redis-scan-status" data-scan-status={status ?? 'INITIAL'} showIcon
      type={failed || query.error ? 'error' : 'info'} title={query.error ? t('table.loadFail', '加载失败') : scanTitle}
      description={query.error ? resolveSimpleTableLoadError(query.error, t('monitoring.redis.queryFailed', '查询加载失败，请重试或重置筛选条件。')) : failed ? query.scan?.message : <Space wrap>
        <span>{t('monitoring.redis.observedCount', '已观察 {count} 个键', {count: query.scan?.observedKeys ?? 0})}</span>
        {query.scan?.completedAt && <span>{t('monitoring.redis.completedAt', '完成时间')}：{time(query.scan.completedAt)}</span>}
        {query.scan?.expiresAt && <span>{t('monitoring.redis.expiresAt', '有效期至')}：{time(query.scan.expiresAt)}</span>}
        <span>{t('monitoring.redis.snapshotNotice', '这是扫描时的元数据，不是实时事务快照；刷新会重新扫描。')}</span>
      </Space>}
      action={(failed || query.error) && <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'retry', sort: 10, text: t('table.retry', '重试'), onClick: query.error ? query.retry : query.refresh},
        {key: 'reset', sort: 20, text: t('table.query.reset', '重置筛选与排序'), onClick: query.reset},
      ]}/>}/>
    <div ref={panel} className="monitoring-redis-table-panel">
      <DataTable<RedisRow> className="monitoring-redis-table" rowKey="key" storageKey="monitoring.redis"
        columns={columns} dataSource={query.error ? [] : query.page?.content ?? []}
        refresh={() => {if (!locked) query.refresh();}} loading={query.loading} includeToolbarInScroll scroll={{x: 'max-content', y: scrollY}}
        locale={!query.page ? {emptyText: t('monitoring.redis.pendingPage', '等待完整扫描结果；当前不显示分页总数。')} : undefined}
        query={query.schema ? {mode: 'remote', schema: query.schema, state: query.state,
          contextKey: pattern, onChange: next => {if (!locked) query.change(next);}} : undefined}
        pagination={query.page ? {current: query.pagination.page + 1, pageSize: query.pagination.size,
          total: query.page.page.totalElements, showSizeChanger: true, disabled: locked,
          onChange: (page, size) => {if (!locked) query.setPagination({page: page - 1, size});}} : false}
        toolbarExtra={<TableActions<RedisRow> selectedKeys={[]} selectedRows={[]}
          buttons={query.buttons.filter(button => button.key === 'add')}
          isButtonDisabled={() => locked || !selected || !!query.error}
          onButtonEvents={{add: create}}/>}/>
    </div>
    <Modal wrapClassName="redis-entry-editor" open={editorOpen && selected} destroyOnHidden={!editorOpen} onCancel={closeEditor} onOk={() => form.current?.submit()}
      title={editorMode === 'create' ? t('monitoring.redis.createTitle', '新增 Redis 键值') : t('monitoring.redis.editTitle', '编辑 Redis 键值')}
      confirmLoading={saving} closable={!saving} maskClosable={false} width={720}
      keyboard={!saving} cancelButtonProps={{disabled: saving}}
      okButtonProps={{disabled: detailLoading || !!detailError || !allowed(editorMode === 'create' ? 'add' : 'edit') || editorMode === 'edit' && !canEditRedisDetail(detail)}}>
      {detailsError}
      {writeError && <Alert type="error" showIcon title={writeError}/>}
      <Spin spinning={detailLoading}>
        <SForm ref={form} hideSubmit disabled={saving || detailLoading} schema={{type: 'object',
          required: ['key', 'value', 'persistent', ...(persistent === false ? ['ttlSeconds'] : [])], properties: {
            key: {type: 'string', minLength: 1, title: t('monitoring.redis.key', 'Key')},
            value: {type: 'string', title: t('monitoring.redis.value', 'Value')},
            persistent: {type: 'boolean', title: t('monitoring.redis.persistent', 'Permanent')},
            ...(persistent === false ? {ttlSeconds: {type: 'integer' as const, minimum: 1,
              maximum: Number.MAX_SAFE_INTEGER, title: t('monitoring.redis.ttlSeconds', 'TTL Seconds')}} : {}),
          }}} uiSchema={{key: {'ui:readonly': editorMode === 'edit'},
            value: {'ui:widget': 'textarea', 'ui:options': {emptyValue: '', autoSize: {minRows: 8, maxRows: 16}}}}}
          formData={formData} onChange={({formData}) => {if (!saving) {setFormData(formData ?? {}); setDirty(true);}}}
          onSubmit={({formData}) => {void submit(formData as Editor);}}/>
      </Spin>
    </Modal>
    <Modal wrapClassName="redis-entry-details" open={detailOpen && selected} destroyOnHidden={!detailOpen} title={t('monitoring.redis.detailTitle', 'Redis 键详情')}
      footer={null} onCancel={closeDetail} width={820}>
      {detailsError}
      <Spin spinning={detailLoading}>
        {detail && <Space orientation="vertical" style={{display: 'flex'}} size={16}>
          <Descriptions bordered column={2} size="small">
            <Descriptions.Item label={t('monitoring.redis.key', 'Key')} span={2}><Text code copyable>{detail.key}</Text></Descriptions.Item>
            <Descriptions.Item label={t('monitoring.redis.type', 'Type')}>{detail.type ?? '--'}</Descriptions.Item>
            <Descriptions.Item label={t('monitoring.redis.size', 'Size')}>{detail.size ?? '--'}</Descriptions.Item>
            <Descriptions.Item label={t('monitoring.redis.ttl', 'TTL')}>{detail.ttlSeconds ?? '--'}</Descriptions.Item>
            <Descriptions.Item label={t('monitoring.redis.permanentFlag', '永久')}>{shownBoolean(detail.persistent)}</Descriptions.Item>
            {detail.valueEncoding && <Descriptions.Item label={t('monitoring.redis.valueEncoding', '值编码')}>{detail.valueEncoding}</Descriptions.Item>}
          </Descriptions>
          {(detail.sampled || detail.truncated) && <Alert type="warning" showIcon title={t('monitoring.redis.sampledValue', '当前仅展示有界预览，不能用于覆盖编辑。')}/>}
          {detail.valueEncoding === 'base64' && <Alert type="info" showIcon title={t('monitoring.redis.binaryValue', '非完整 UTF-8 文本以 Base64 展示，只读，不会进行有损解码或写回。')}/>}
          <Input.TextArea readOnly value={detail.value ?? ''} autoSize={{minRows: 10, maxRows: 20}}/>
        </Space>}
      </Spin>
    </Modal>
    <Modal wrapClassName="redis-discard-draft" open={discard && selected} destroyOnHidden={!discard}
      title={t('monitoring.redis.unsavedChanges', '放弃未保存的修改？')} onCancel={() => setDiscard(false)}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'cancel', sort: 1, text: t('action.cancel', '取消')},
        {key: 'discard', sort: 2, text: t('monitoring.redis.discard', '放弃'), type: 'primary', danger: true},
      ]} onButtonEvents={{cancel: () => setDiscard(false), discard: discardEditor}}/>}/>
    <Modal wrapClassName="redis-entry-delete" open={!!deleting && selected} destroyOnHidden={!deleting}
      title={t('table.confirmDeleteTitle', '确认删除')} onCancel={() => {if (!saving) setDeleting(undefined);}}
      closable={!saving} maskClosable={!saving} keyboard={!saving}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'cancel', sort: 1, text: t('action.cancel', '取消'), disabled: saving},
        {key: 'delete', sort: 2, text: t('table.button.delete', '删除'), type: 'primary', danger: true, loading: saving, disabled: saving || !allowed('delete')},
      ]} onButtonEvents={{cancel: () => {if (!saving) setDeleting(undefined);}, delete: () => void confirmDelete()}}/>}>
      <Text code>{deleting?.key}</Text>{writeError && <Alert type="error" showIcon title={writeError}/>}
    </Modal>
  </>;
}
