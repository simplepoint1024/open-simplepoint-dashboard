import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import type {SimpleTablePageRequest} from '@simplepoint/components/SimpleTable/types';
import {request} from '@simplepoint/shared/api/client';
import {get, post, put} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import {Alert, App, Descriptions, Drawer, Input, Modal, Tag} from 'antd';
import type {Key} from 'react';
import {useCallback, useEffect, useMemo, useState} from 'react';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {useOwnedReads} from '@simplepoint/shared/hooks/useOwnedReads';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {formatDateTime, resolveErrorMessage} from '../shared';

type ModelType = 'DNA_SQL' | 'NATIVE_SQL';
type Model = {
  id: string; name: string; description?: string; modelType: ModelType;
  dataSourceName?: string; status: 'DRAFT'; version: number;
  createdAt: string; updatedAt: string;
};
type Detail = {model: Model; dataSourceId?: string; sqlText?: string};
type Values = {name: string; description?: string; modelType: ModelType; dataSourceId?: string; sqlText?: string};
type EditingModel = Model & Pick<Detail, 'dataSourceId' | 'sqlText'>;

const baseConfig = api['platform.dna-data-models'];
const endpoint = baseConfig.baseUrl;
const sourceEndpoint = endpoint + '/data-sources';

export default function DataModel() {
  const {t, ensure, locale} = useI18n();
  const {message} = App.useApp();
  const operation = useOperationScope(endpoint);
  const reads = useOwnedReads(operation, true);
  const [deleting, setDeleting] = useState<Model>();
  const [tableRevision, setTableRevision] = useState(0);
  const [selectedKeys, setSelectedKeys] = useState<Key[]>([]);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingRecord, setEditingRecord] = useState<EditingModel | null>(null);
  const [viewOpen, setViewOpen] = useState(false);
  const [detail, setDetail] = useState<Detail>();

  useEffect(() => {if (!operation.selected && !detail) setViewOpen(false);}, [operation.selected, detail]);
  useEffect(() => { void ensure(baseConfig.i18nNamespaces); }, [ensure, locale]);

  const loadPage = useCallback(async ({page, size, filters, sort, signal}: SimpleTablePageRequest) =>
    normalizePage(await get<RawPage<Model>>(endpoint, {
      ...filters, page, size, ...(sort ? {sort} : {}),
    }, {signal, notifyOnError: false})), []);

  const typeLabel = useCallback((modelType: ModelType) => modelType === 'DNA_SQL'
    ? t('dna.models.type.dnaSql', 'DNA SQL 模型')
    : t('dna.models.type.nativeSql', 'Native SQL 原生模型'), [t]);

  usePageRetention(!viewOpen && !deleting && !operation.busy, !!deleting || operation.busy);
  useEffect(() => {setDetail(undefined); setViewOpen(false); setDeleting(undefined);}, [operation.current]);
  const openDetail = (row: Model) => {
    reads.cancel('detail'); reads.clearErrors('detail'); setDetail(undefined); setViewOpen(true);
    void reads.run('detail', signal => get<Detail>(endpoint + '/' + row.id, undefined,
      {signal, notifyOnError: false}), setDetail);
  };
  const deleteModel = async () => {
    if (!deleting) return;
    const command = operation.begin(); if (!command) return;
    try {
      await request<void>(endpoint + '/' + deleting.id,
        {method: 'DELETE', params: {version: deleting.version}, signal: command.signal, notifyOnError: false});
      if (operation.current(command.signal)) {
        setDeleting(undefined); setSelectedKeys([]); setTableRevision(value => value + 1);
        message.success(t('dna.models.deleted', '模型已删除'));
      }
    } catch (error) {
      if (operation.current(command.signal)) message.error(resolveErrorMessage(error, t('dna.models.deleteFailed', '删除失败，请刷新后重试')));
    } finally {operation.finish(command);}
  };

  const formSchemaTransform = useCallback((schema: any) => {
    const next = structuredClone(schema);
    const properties = next.properties ?? {};
    for (const key of ['id', 'dataSourceName', 'status', 'createdAt', 'updatedAt']) delete properties[key];
    if (properties.name) properties.name.maxLength = 120;
    if (properties.modelType) properties.modelType.default = 'DNA_SQL';
    if (properties.description) {
      properties.description.maxLength = 500;
      properties.description['x-ui'] = {...properties.description['x-ui'], widget: 'textarea'};
    }
    if (properties.dataSourceId) properties.dataSourceId['x-ui'] = {
      ...properties.dataSourceId['x-ui'], widget: 'EntitySelect',
      options: {endpoint: sourceEndpoint},
    };
    if (properties.sqlText) {
      properties.sqlText.maxLength = 100000;
      properties.sqlText['x-ui'] = {...properties.sqlText['x-ui'], widget: 'textarea'};
    }
    next.required = ['name', 'modelType'].filter(key => properties[key]);
    return next;
  }, []);

  const formUiSchema = useCallback((values: Values) => ({
    ...(values?.modelType === 'NATIVE_SQL' ? {} : {dataSourceId: {'ui:widget': 'hidden'}}),
    'ui:submitButtonOptions': {submitText: t('dna.models.save', '保存草稿')},
  }), [t]);

  const columnOverrides = useMemo(() => ({
    name: {width: 220},
    modelType: {width: 170, render: (value: ModelType) => typeLabel(value)},
    dataSourceName: {width: 180, render: (value?: string) => value || '—'},
    description: {width: 240, ellipsis: true, render: (value?: string) => value || '—'},
    status: {width: 100, render: () => <Tag color="blue">{t('dna.models.draft', '草稿')}</Tag>},
    updatedAt: {width: 180, render: (value?: string) => formatDateTime(value)},
  }), [t, typeLabel]);

  return <>
    <SimpleTable<Model> {...baseConfig} loadPage={loadPage} queryContext={String(tableRevision)}
      columnOverrides={columnOverrides} drawerWidth={700} confirmDiscard
      drawerOpen={drawerOpen} onDrawerOpenChange={open => {
        setDrawerOpen(open);
        if (!open) setEditingRecord(null);
      }}
      editingRecord={editingRecord} onEditingRecordChange={setEditingRecord}
      initialValues={{modelType: 'DNA_SQL'}}
      formSchemaTransform={formSchemaTransform} formUiSchema={formUiSchema}
      formCustomValidate={(values: Values, errors: any) => {
        if (values.modelType === 'NATIVE_SQL' && !values.dataSourceId) {
          errors.dataSourceId?.addError(t('dna.models.selectDataSource', '选择已启用的数据源'));
        }
        return errors;
      }}
      onSubmit={(action, values: Values, current: EditingModel | null) => {
        const command = {name: values.name, description: values.description,
          modelType: values.modelType,
          dataSourceId: values.modelType === 'NATIVE_SQL' ? values.dataSourceId : undefined,
          sqlText: values.sqlText};
        return action === 'edit' && current
          ? put<Detail>(endpoint + '/' + current.id, {...command, version: current.version}, {notifyOnError: false})
          : post<Detail>(endpoint, command, {notifyOnError: false});
      }}
      afterSubmit={() => {setSelectedKeys([]); message.success(t('dna.models.saved', '草稿已保存'));}}
      rowSelection={{selectedKeys}} onSelectionChange={keys => setSelectedKeys(keys)}
      isButtonDisabled={() => operation.busy || !!deleting || viewOpen}
      loadEditingRecord={async (row, signal) => {
        const value = await get<Detail>(endpoint + '/' + row.id, undefined, {signal, notifyOnError: false});
        return {...value.model, dataSourceId: value.dataSourceId, sqlText: value.sqlText};
      }}
      customButtonEvents={{
        view: (_keys, rows) => {if (rows[0]) void openDetail(rows[0]);},
        delete: (_keys, rows) => {if (rows[0]) setDeleting(rows[0]);},
      }}/>

    <Drawer rootClassName="sp-simple-table-drawer" title={detail?.model.name}
      open={viewOpen && operation.selected} width="min(700px, 94vw)" loading={reads.loading.includes('detail')} destroyOnHidden={!viewOpen}
      onClose={() => {reads.cancel('detail'); setViewOpen(false);}}>
      {!!reads.errors.detail && <Alert type="error" showIcon title={resolveErrorMessage(reads.errors.detail, t('dna.models.loadDetailFailed', '模型详情加载失败'))}/>}
      {detail && <>
        <Descriptions column={1} size="small">
          <Descriptions.Item label={t('dna.models.type', '类型')}>{typeLabel(detail.model.modelType)}</Descriptions.Item>
          <Descriptions.Item label={t('dna.models.dataSource', '数据源')}>
            {detail.model.dataSourceName || '—'}
          </Descriptions.Item>
          <Descriptions.Item label={t('dna.models.status', '状态')}>
            <Tag color="blue">{t('dna.models.draft', '草稿')}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label={t('dna.models.description', '说明')}>
            {detail.model.description || '—'}
          </Descriptions.Item>
          <Descriptions.Item label={t('dna.models.updatedAt', '更新时间')}>
            {formatDateTime(detail.model.updatedAt)}
          </Descriptions.Item>
        </Descriptions>
        <div style={{marginTop: 16, marginBottom: 8}}>{t('dna.models.sql', 'SQL 定义')}</div>
        <Input.TextArea value={detail.sqlText || ''} rows={12} readOnly/>
        <Alert type="info" showIcon style={{marginTop: 16}} message={t('dna.models.draftHint',
          '当前保存的是模型草稿。执行、校验、发布和应用引用将在后续阶段接入。')}/>
      </>}
    </Drawer>
    <Modal open={!!deleting && operation.selected} title={t('dna.models.confirmDelete', '确定删除这个数据模型？')}
      confirmLoading={operation.busy} okButtonProps={{danger: true}} closable={!operation.busy}
      maskClosable={!operation.busy} keyboard={!operation.busy} onOk={() => void deleteModel()}
      onCancel={() => {if (!operation.isBusy()) setDeleting(undefined);}}/>
  </>;
}
