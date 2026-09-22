import SimpleTable from '@simplepoint/components/SimpleTable';
import api from '@/api/index';
import {lazy, Suspense, useEffect, useState} from 'react';
import {Alert, App, Button, Drawer, Form, Input, Space, Spin} from 'antd';
import {SaveOutlined} from '@simplepoint/shared/types/icon';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {saveFieldConfiguration, type FieldScopeConfiguration} from '@/api/system/field-scope';

const EntriesConfig = lazy(() => import('./config/entries'));
const baseConfig = api['rbac-field-scopes'];

export default function FieldScopePage() {
  const {t, ensure, locale} = useI18n();
  const {message, modal} = App.useApp();
  const [form] = Form.useForm<FieldScopeConfiguration>();
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState('');
  const [tableRevision, setTableRevision] = useState(0);
  const [id, setId] = useState<string>();
  const [initial, setInitial] = useState<FieldScopeConfiguration>({name: '', entries: []});
  const [pendingFields, setPendingFields] = useState(false);
  useEffect(() => {void ensure(baseConfig.i18nNamespaces);}, [ensure, locale]);
  useEffect(() => {
    if (open) {form.resetFields(); form.setFieldsValue(initial);}
  }, [open, initial, form]);
  const edit = (row?: FieldScopeConfiguration) => {
    setId(row?.id); setError(''); setDirty(false); setPendingFields(false);
    setInitial({name: row?.name ?? '', description: row?.description ?? '', entries: row?.entries ?? []}); setOpen(true);
  };
  const close = () => {
    if (saving) return;
    if (!dirty) {setOpen(false); return;}
    modal.confirm({title: t('field-scopes.editor.discard', '放弃未保存的修改？'),
      okText: t('field-scopes.editor.discardConfirm', '放弃修改'), cancelText: t('field-scopes.editor.continue', '继续编辑'),
      onOk: () => setOpen(false)});
  };
  const save = async (values: FieldScopeConfiguration) => {
    if (pendingFields) return;
    setSaving(true); setError('');
    try {
      await saveFieldConfiguration({...values, id, entries: values.entries ?? []});
      message.success(t('field-scopes.message.entriesSaveSuccess', '保存成功'));
      setOpen(false); setDirty(false); setTableRevision(value => value + 1);
    } catch (reason) {
      setError((reason as Error).message || t('field-scopes.message.entriesSaveFailed', '保存失败'));
    } finally {setSaving(false);}
  };
  return <div style={{height: '100%', width: '100%', minWidth: 0, minHeight: 0}}>
    <SimpleTable key={tableRevision} {...baseConfig} customButtonEvents={{
      add: () => edit(), edit: (_keys, rows) => {if (rows[0]) edit(rows[0]);},
      'config.entries': (_keys, rows) => {if (rows[0]) edit(rows[0]);},
    }} deleteRefreshTargets={{page: true, schema: false}} />
    <Drawer title={id ? t('field-scopes.editor.edit', '编辑字段权限') : t('field-scopes.editor.create', '新增字段权限')}
      open={open} onClose={close} placement="bottom" height="min(760px, 90vh)" destroyOnHidden
      styles={{body: {overflow: 'auto'}}} footer={<Space style={{display: 'flex', justifyContent: 'flex-end'}}>
        <Button onClick={close} disabled={saving}>{t('field-scopes.editor.cancel', '取消')}</Button>
        <Button type="primary" icon={<SaveOutlined />} loading={saving} disabled={!dirty || pendingFields} onClick={() => form.submit()}>
          {t('field-scopes.action.saveEntries', '保存')}
        </Button>
      </Space>}>
      {open && <Form form={form} layout="vertical" disabled={saving} onValuesChange={() => setDirty(true)} onFinish={save}>
        {error && <Alert style={{marginBottom: 16}} type="error" showIcon title={error} />}
        <div style={{display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: 16}}>
          <Form.Item name="name" label={t('field-scopes.title.name', '名称')}
            rules={[{required: true, whitespace: true, max: 100, message: t('field-scopes.editor.nameRequired', '请输入名称，最多 100 个字符')}]}>
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item name="description" label={t('field-scopes.title.description', '描述')}><Input maxLength={200} /></Form.Item>
        </div>
        <Suspense fallback={<Spin />}>
          <Form.Item name="entries" label={t('field-scopes.title.entries', '字段规则')}>
            <EntriesConfig disabled={saving} onPendingChange={pending => {setPendingFields(pending); if (pending) setDirty(true);}} />
          </Form.Item>
        </Suspense>
      </Form>}
    </Drawer>
  </div>;
}
