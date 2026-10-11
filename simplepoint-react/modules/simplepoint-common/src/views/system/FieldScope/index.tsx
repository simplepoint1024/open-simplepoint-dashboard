import SimpleTable from '@simplepoint/components/SimpleTable';
import api from '@/api/index';
import {lazy, Suspense, useCallback, useEffect, useLayoutEffect, useRef, useState} from 'react';
import {Alert, App, Drawer, Modal, Spin} from 'antd';
import {useQueryClient} from '@tanstack/react-query';
import TableActions from '@simplepoint/components/Table/Actions';
import type {SFormHandle} from '@simplepoint/components/SForm';
import {prepareFormSubmission, claimFormSubmission, startFormWrite, cancelFormPreparation, cancelFormValidation,
  finishFormSubmission, type FormSubmission} from '@simplepoint/components/SimpleTable/formSubmission';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {usePageActive, usePageRetention} from '@simplepoint/shared/page/hooks';
import {saveFieldConfiguration, type FieldScopeConfiguration} from '@/api/system/field-scope';
import {canCreateFieldScope, canEditFieldScopeMetadata, fieldScopeDraft, fieldScopePayload, fieldScopeMetadataPayload,
  fieldScopeMetadataSchema, usableConfiguration, type FieldScopeDraft} from './forms';

const MatrixForm = lazy(() => import('./MatrixForm'));
const baseConfig = api['rbac-field-scopes'];
type Editor = {owner: string; action: 'add' | 'edit' | 'config.entries'; id?: string; draft: FieldScopeDraft; initial: FieldScopeDraft};
const isDirty = (editor: Editor) => JSON.stringify(editor.draft) !== JSON.stringify(editor.initial);

export default function FieldScopePage() {
  const scope = useQueryScope();
  return <FieldScopeSession key={JSON.stringify(scope)}/>;
}
function FieldScopeSession() {
  const {t, ensure, locale} = useI18n(), {message} = App.useApp(), active = usePageActive();
  const activity = useRef(active); activity.current = active;
  const client = useQueryClient(), scope = useQueryScope(), form = useRef<SFormHandle>(null);
  const metadata = useSchema(baseConfig.baseUrl, {staleTime: 30_000, retry: false});
  const [editor, setEditor] = useState<Editor | null>(null), editorRef = useRef<Editor | null>(null);
  const [metadataOpen, setMetadataOpen] = useState(false), metadataOpenRef = useRef(false);
  const [metadataRecord, setMetadataRecord] = useState<FieldScopeConfiguration | null>(null);
  const sequence = useRef(0), submission = useRef<FormSubmission<Editor> | undefined>(undefined);
  const operation = useOperationScope(editor?.owner);
  const [saving, setSaving] = useState(false), [rootLocked, setRootLocked] = useState(false), rootLock = useRef(false);
  const [discard, setDiscard] = useState(false), discardRef = useRef(false), [error, setError] = useState('');
  const dirty = !!editor && isDirty(editor), blocked = !!editor || metadataOpen || saving || discard || rootLocked;
  usePageRetention(!blocked, blocked);
  useEffect(() => {void ensure([...baseConfig.i18nNamespaces, 'permissions']);}, [ensure, locale]);
  useEffect(() => () => {submission.current?.request.abort(); submission.current = undefined;}, []);
  useLayoutEffect(() => {
    if (active && operation.selected || !editor?.owner) return;
    const pending = cancelFormPreparation(submission, editor.owner); if (!pending) return;
    operation.finish(pending.request); if (operation.current()) setSaving(false);
  }, [active, operation.selected, editor?.owner, operation.finish, operation.current]);
  const rootInteraction = useCallback((locked: boolean) => {rootLock.current = locked; setRootLocked(locked);}, []);
  const permitted = (action: Editor['action']) => !metadata.error && !!metadata.data?.buttons.some(button => button.key === action);
  const selectedOwner = (owner?: string) => !!owner && editorRef.current?.owner === owner
    && activity.current && operation.current() && operation.isSelected() && !rootLock.current;
  const canInteract = (owner?: string) => selectedOwner(owner) && !submission.current && !operation.isBusy()
    && !discardRef.current && permitted(editorRef.current!.action);
  const discardOpen = (open: boolean) => {discardRef.current = open; setDiscard(open);};
  const metadataDrawer = (open: boolean) => {metadataOpenRef.current = open; setMetadataOpen(open);};
  const edit = (action: Editor['action'], row?: FieldScopeConfiguration) => {
    if (!activity.current || !operation.current() || !operation.isSelected() || operation.isBusy() || rootLock.current || editorRef.current || metadataOpenRef.current) return;
    const source = metadata.data?.schema;
    if (!source || !permitted(action) || !row && !canCreateFieldScope(source)) return;
    const matrixAvailable = row ? usableConfiguration(row, source) : typeof source.properties?.entries === 'object'
      && source.properties.entries.type === 'array' && !['MASKED', 'HIDDEN'].includes(source.properties.entries['x-field-access']);
    if (!matrixAvailable) {
      if (action === 'config.entries' || row && !canEditFieldScopeMetadata(row, source)) return;
      setMetadataRecord(row ?? null); metadataDrawer(true); return;
    }
    const initial = fieldScopeDraft(row, source), next = {owner: String(++sequence.current), action, id: row?.id, draft: initial, initial};
    editorRef.current = next; setEditor(next); setError(''); discardOpen(false);
  };
  const abandon = (owner?: string) => {
    if (!selectedOwner(owner) || submission.current || operation.isBusy()) return;
    editorRef.current = null; setEditor(null); discardOpen(false); setError('');
  };
  const close = () => {
    const target = editorRef.current; if (!target || !selectedOwner(editor?.owner) || submission.current || operation.isBusy()) return;
    if (isDirty(target)) discardOpen(true); else abandon(target.owner);
  };
  const begin = (owner: string) => {
    if (!canInteract(owner) || !isDirty(editorRef.current!)) return false;
    const request = operation.begin(); if (!request) return false;
    if (!prepareFormSubmission(submission, owner, request, editorRef.current!)) {operation.finish(request); return false;}
    setSaving(true); setError(''); return true;
  };
  const invalid = (owner: string) => {
    const pending = cancelFormValidation(submission, owner); if (!pending) return;
    operation.finish(pending.request); if (operation.current()) setSaving(false);
  };
  const save = async (owner: string, values: FieldScopeDraft) => {
    const pending = claimFormSubmission(submission, owner); if (!pending) return;
    const {request, target} = pending;
    try {
      if (!selectedOwner(owner) || !permitted(target.action) || !operation.current(request.signal) || !startFormWrite(submission, pending)) return;
      await saveFieldConfiguration(fieldScopePayload(values, target.id, metadata.data?.schema), request.signal);
      if (!operation.current(request.signal) || editorRef.current?.owner !== owner) return;
      if (operation.isSelected()) message.success(t('field-scopes.message.entriesSaveSuccess', '保存成功'));
      editorRef.current = null; setEditor(null); discardOpen(false);
      // Refresh the table's existing controller, preserving pagination, queries and saved views.
      void client.invalidateQueries({queryKey: scopedQueryKey(scope, [baseConfig.baseUrl, baseConfig.name])});
    } catch (reason) {if (operation.current(request.signal) && editorRef.current?.owner === owner)
      setError(resolveApiErrorMessage(reason, t('field-scopes.message.entriesSaveFailed', '保存失败')));}
    finally {if (finishFormSubmission(submission, pending)) {operation.finish(request); if (operation.current()) setSaving(false);}}
  };
  return <div style={{height: '100%', width: '100%', minWidth: 0, minHeight: 0}}>
    <SimpleTable {...baseConfig} schemaStaleTime={30_000} allowTabEviction={!blocked} onInteractionLockChange={rootInteraction}
      drawerOpen={metadataOpen} onDrawerOpenChange={metadataDrawer} editingRecord={metadataRecord} onEditingRecordChange={setMetadataRecord} confirmDiscard
      formSchemaTransform={schema => ({...fieldScopeMetadataSchema(schema, t), description: t('field-scopes.editor.metadataOnly', '本表单仅保存基础信息，不修改字段规则。')})}
      defaultSubmitDataTransform={(data, {currentEditing}) => fieldScopeMetadataPayload(data, currentEditing?.id, metadata.data?.schema)}
      formUiSchema={{description: {'ui:widget': 'textarea', 'ui:options': {autoSize: {minRows: 2, maxRows: 4}}}}}
      submitRefreshTargets={{page: true, schema: false}}
      rowSelection={{isRowSelectable: () => !editorRef.current && !metadataOpenRef.current && !operation.isBusy() && !rootLock.current}}
      isButtonDisabled={(button, _keys, rows) => !operation.isSelected() || !!editorRef.current || metadataOpenRef.current || operation.isBusy() || rootLock.current || !metadata.data || !!metadata.error
      || button.key === 'config.entries' && !usableConfiguration(rows[0], metadata.data.schema)
      || button.key === 'edit' && !usableConfiguration(rows[0], metadata.data.schema) && !canEditFieldScopeMetadata(rows[0], metadata.data.schema)
      || button.key === 'add' && !canCreateFieldScope(metadata.data.schema)} columnOverrides={{entries: {
      render: value => Array.isArray(value) ? t('permissions.configuredCount', '已设置 {count} 个字段', {count: value.length}) : value === '***' ? '***' : '—',
    }}} customButtonEvents={{add: () => edit('add'), edit: (_keys, rows) => {if (rows[0]) edit('edit', rows[0]);},
      'config.entries': (_keys, rows) => {if (rows[0]) edit('config.entries', rows[0]);},
    }} deleteRefreshTargets={{page: true, schema: false}}/>
    <Drawer rootClassName="field-scope-editor" title={editor?.id ? t('field-scopes.editor.edit', '编辑字段权限') : t('field-scopes.editor.create', '新增字段权限')}
      open={!!editor && operation.selected} onClose={close} placement="bottom" height="min(760px, 90vh)" destroyOnHidden
      closable={!saving} maskClosable={!saving} keyboard={!saving} styles={{body: {overflow: 'hidden', display: 'flex', flexDirection: 'column'}}}
      footer={<div style={{display: 'flex', justifyContent: 'flex-end'}}><TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'cancel', sort: 1, text: t('field-scopes.editor.cancel', '取消'), disabled: saving || discard},
        {key: 'save', sort: 2, text: t('field-scopes.action.saveEntries', '保存'), type: 'primary', icon: 'SaveOutlined', loading: saving,
          disabled: saving || discard || !dirty || !editor || !permitted(editor.action)},
      ]} onButtonEvents={{cancel: close, save: () => {if (canInteract(editor?.owner)) form.current?.submit();}}}/></div>}>
      {error && <Alert style={{marginBottom: 16, flexShrink: 0}} type="error" showIcon title={error}/>}
      {editor && <Suspense fallback={<Spin/>}><MatrixForm ref={form} draft={editor.draft} source={metadata.data?.schema}
        disabled={saving || discard || !permitted(editor.action)} canInteract={() => canInteract(editor.owner)}
        onBeforeSubmit={() => begin(editor.owner)} onError={() => invalid(editor.owner)}
        onChange={draft => {if (!canInteract(editor.owner)) return; const next = {...editorRef.current!, draft}; editorRef.current = next; setEditor(next);}}
        onSubmit={values => void save(editor.owner, values)}/></Suspense>}
    </Drawer>
    <Modal rootClassName="field-scope-discard" open={discard && operation.selected} title={t('field-scopes.editor.discard', '放弃未保存的修改？')} onOk={() => abandon(editor?.owner)}
      onCancel={() => {if (selectedOwner(editor?.owner) && !submission.current && !operation.isBusy()) discardOpen(false);}}
      okText={t('field-scopes.editor.discardConfirm', '放弃修改')} cancelText={t('field-scopes.editor.continue', '继续编辑')} destroyOnHidden/>
  </div>;
}
