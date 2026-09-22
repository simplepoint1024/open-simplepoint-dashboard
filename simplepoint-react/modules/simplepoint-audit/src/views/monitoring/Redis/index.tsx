import {useCallback, useEffect, useLayoutEffect, useRef, useState} from 'react';
import {Alert, App as AntApp, Button, Descriptions, Form, Input, InputNumber, Modal, Space, Spin, Switch, Tag, Typography} from 'antd';
import DataTable, {type ColumnsType} from '@simplepoint/components/DataTable';
import TableActions from '@simplepoint/components/Table/Actions';
import {resolveSimpleTableLoadError} from '@simplepoint/components/SimpleTable/errorMessage';
import {get, post, put} from '@simplepoint/shared/api/methods';
import {request, resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useActionFeedback} from '@simplepoint/shared/hooks/useActionFeedback';
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
  useEffect(() => {void ensure(baseConfig.i18nNamespaces);}, [ensure, locale]);
  return <div className="monitoring-redis-page">
    <Space className="monitoring-redis-scope" wrap>
      <label htmlFor="redis-scan-pattern">{t('monitoring.redis.scanScope', '扫描范围（Redis glob）')}</label>
      <Input id="redis-scan-pattern" maxLength={512} value={draft} style={{width: 300}}
        onChange={event => setDraft(event.target.value)}
        onPressEnter={() => setPattern(draft || '*')} placeholder="simplepoint:*"/>
      <Button onClick={() => setPattern(draft || '*')} disabled={(draft || '*') === pattern}>
        {t('monitoring.redis.applyScope', '应用范围')}
      </Button>
      <Text type="secondary">{t('monitoring.redis.queryScopeHint', '表头筛选和视图作用于当前扫描范围；值内容按需查看。')}</Text>
    </Space>
    <RedisTable key={pattern} pattern={pattern}/>
  </div>;
}

function RedisTable({pattern}: {pattern: string}) {
  const {locale, t} = useI18n();
  const {message, modal} = AntApp.useApp();
  const feedback = useActionFeedback();
  const selected = usePageSelected();
  const query = useRedisQuery(baseConfig.baseUrl, pattern);
  const [form] = Form.useForm<Editor>();
  const persistent = Form.useWatch('persistent', form);
  const [editorMode, setEditorMode] = useState<'create' | 'edit'>('create');
  const [editorOpen, setEditorOpen] = useState(false);
  const [detailOpen, setDetailOpen] = useState(false);
  const [detail, setDetail] = useState<RedisDetail>();
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string>();
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const reader = useRef<{controller: AbortController; key: string; editing: boolean} | undefined>(undefined);
  const panel = useRef<HTMLDivElement>(null);
  const [scrollY, setScrollY] = useState(320);
  usePageRetention(!editorOpen && !saving, dirty || saving);
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
    setEditorOpen(false); setDirty(false); form.resetFields();
  };
  const closeEditor = () => {
    if (saving) return;
    if (!dirty) return discardEditor();
    modal.confirm({title: t('monitoring.redis.unsavedChanges', '放弃未保存的修改？'),
      okText: t('monitoring.redis.discard', '放弃'), cancelText: t('action.cancel', '取消'), onOk: discardEditor});
  };
  const create = () => {
    reader.current?.controller.abort();
    setDetail(undefined); setDetailError(undefined); setDetailLoading(false);
    setEditorMode('create'); setEditorOpen(true); setDirty(false);
    form.setFieldsValue({key: '', value: '', persistent: true, ttlSeconds: null});
  };
  const read = useCallback(async (key: string, editing: boolean) => {
    reader.current?.controller.abort();
    const current = {key, editing, controller: new AbortController()};
    reader.current = current;
    setDetail(undefined); setDetailError(undefined); setDetailLoading(true);
    if (editing) {
      form.resetFields(); setDirty(false); setEditorMode('edit'); setEditorOpen(true);
    } else setDetailOpen(true);
    try {
      const loaded = await get<RedisDetail>(baseConfig.baseUrl + '/detail', {key}, {signal: current.controller.signal});
      if (current !== reader.current || current.controller.signal.aborted) return;
      if (loaded.key !== key) throw new Error(t('monitoring.redis.identityMismatch', '返回的键名与请求不一致，已阻止操作。'));
      setDetail(loaded);
      if (editing) {
        if (!canEditRedisDetail(loaded)) {
          setDetailError(t('monitoring.redis.incompleteEdit', '当前值不可完整编辑，请查看详情；不会用摘要或脱敏值覆盖原值。'));
          return;
        }
        form.setFieldsValue({key: loaded.key, value: loaded.value!, persistent: loaded.persistent as boolean,
          ttlSeconds: loaded.persistent === true ? null : loaded.ttlSeconds as number});
      }
    } catch (error) {
      if (current === reader.current && !current.controller.signal.aborted) {
        setDetailError(resolveApiErrorMessage(error, t('monitoring.redis.detailFailed', '键详情加载失败，请重试。')));
      }
    } finally {
      if (current === reader.current && !current.controller.signal.aborted) setDetailLoading(false);
    }
  }, [form, t]);

  const submit = () => feedback('redis.save', async () => {
    const values = await form.validateFields();
    if (editorMode === 'edit' && (!canEditRedisDetail(detail) || detail?.key !== values.key)) {
      throw new Error(t('monitoring.redis.incompleteEdit', '当前值不可完整编辑，请查看详情；不会用摘要或脱敏值覆盖原值。'));
    }
    setSaving(true);
    try {
      const payload = {...values, ttlSeconds: values.persistent ? null : values.ttlSeconds};
      if (editorMode === 'create') await post(baseConfig.baseUrl, payload);
      else await put(baseConfig.baseUrl, payload);
      message.success(editorMode === 'create' ? t('table.addSuccess', '新增成功') : t('table.editSuccess', '修改成功'));
      discardEditor();
      query.refresh();
    } finally {setSaving(false);}
  });
  const remove = (row: RedisRow) => {
    modal.confirm({title: t('table.confirmDeleteTitle', '确认删除'),
      content: <Text code>{row.key}</Text>, okButtonProps: {danger: true},
      okText: t('table.button.delete', '删除'), cancelText: t('action.cancel', '取消'),
      onOk: async () => {
        const outcome = await feedback('redis.delete:' + row.key, async () => {
          await request(baseConfig.baseUrl, {method: 'DELETE', params: {keys: row.key}});
          message.success(t('table.deleteSuccess', '删除成功'));
          query.refresh();
        });
        // Keep the confirmation open after failure; the platform feedback already explains why.
        if (outcome.status !== 'success') return Promise.reject(new Error('Redis delete not completed'));
      }});
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
        isButtonDisabled={button => button.key === 'edit' && row.editable !== true}
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
    action={<Button onClick={() => {
      if (reader.current) void read(reader.current.key, reader.current.editing);
    }}>{t('table.retry', '重试')}</Button>}/>;
  return <>
    <Alert className="redis-scan-status" data-scan-status={status ?? 'INITIAL'} showIcon
      type={failed || query.error ? 'error' : 'info'} title={query.error ? t('table.loadFail', '加载失败') : scanTitle}
      description={query.error ? resolveSimpleTableLoadError(query.error, t('monitoring.redis.queryFailed', '查询加载失败，请重试或重置筛选条件。')) : failed ? query.scan?.message : <Space wrap>
        <span>{t('monitoring.redis.observedCount', '已观察 {count} 个键', {count: query.scan?.observedKeys ?? 0})}</span>
        {query.scan?.completedAt && <span>{t('monitoring.redis.completedAt', '完成时间')}：{time(query.scan.completedAt)}</span>}
        {query.scan?.expiresAt && <span>{t('monitoring.redis.expiresAt', '有效期至')}：{time(query.scan.expiresAt)}</span>}
        <span>{t('monitoring.redis.snapshotNotice', '这是扫描时的元数据，不是实时事务快照；刷新会重新扫描。')}</span>
      </Space>}
      action={(failed || query.error) && <Space>
        <Button onClick={query.error ? query.retry : query.refresh}>{t('table.retry', '重试')}</Button>
        <Button onClick={query.reset}>{t('table.query.reset', '重置筛选与排序')}</Button>
      </Space>}/>
    <div ref={panel} className="monitoring-redis-table-panel">
      <DataTable<RedisRow> className="monitoring-redis-table" rowKey="key" storageKey="monitoring.redis"
        columns={columns} dataSource={query.error ? [] : query.page?.content ?? []}
        refresh={query.refresh} loading={query.loading} includeToolbarInScroll scroll={{x: 'max-content', y: scrollY}}
        locale={!query.page ? {emptyText: t('monitoring.redis.pendingPage', '等待完整扫描结果；当前不显示分页总数。')} : undefined}
        query={query.schema ? {mode: 'remote', schema: query.schema, state: query.state,
          contextKey: pattern, onChange: query.change} : undefined}
        pagination={query.page ? {current: query.pagination.page + 1, pageSize: query.pagination.size,
          total: query.page.page.totalElements, showSizeChanger: true,
          onChange: (page, size) => query.setPagination({page: page - 1, size})} : false}
        toolbarExtra={<TableActions<RedisRow> selectedKeys={[]} selectedRows={[]}
          buttons={query.buttons.filter(button => button.key === 'add')}
          onButtonEvents={{add: create}}/>}/>
    </div>
    <Modal open={editorOpen && selected} onCancel={closeEditor} onOk={() => {void submit();}}
      title={editorMode === 'create' ? t('monitoring.redis.createTitle', '新增 Redis 键值') : t('monitoring.redis.editTitle', '编辑 Redis 键值')}
      confirmLoading={saving} closable={!saving} maskClosable={false} width={720}
      okButtonProps={{disabled: detailLoading || !!detailError || editorMode === 'edit' && !canEditRedisDetail(detail)}}>
      {detailsError}
      <Spin spinning={detailLoading}>
        <Form form={form} layout="vertical" onValuesChange={() => setDirty(true)}>
          <Form.Item name="key" label={t('monitoring.redis.key', 'Key')}
            rules={[{required: true, message: t('form.required', '这是必填项')}]}><Input disabled={editorMode === 'edit' || saving}/></Form.Item>
          <Form.Item name="value" label={t('monitoring.redis.value', 'Value')}
            rules={[{validator: async (_, value) => {
              if (typeof value !== 'string') throw new Error(t('form.required', '这是必填项'));
            }}]}><Input.TextArea disabled={saving || detailLoading} autoSize={{minRows: 8, maxRows: 16}}/></Form.Item>
          <Space align="start">
            <Form.Item name="persistent" valuePropName="checked" label={t('monitoring.redis.persistent', 'Permanent')}>
              <Switch disabled={saving}/></Form.Item>
            {persistent === false && <Form.Item name="ttlSeconds" label={t('monitoring.redis.ttlSeconds', 'TTL Seconds')}
              rules={[{validator: async (_, value) => {
                if (!Number.isSafeInteger(value) || value <= 0) throw new Error(t('monitoring.redis.ttlRule', 'TTL 必须是大于 0 的秒数'));
              }}]}><InputNumber min={1} precision={0} disabled={saving}/></Form.Item>}
          </Space>
        </Form>
      </Spin>
    </Modal>
    <Modal open={detailOpen && selected} title={t('monitoring.redis.detailTitle', 'Redis 键详情')}
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
  </>;
}
