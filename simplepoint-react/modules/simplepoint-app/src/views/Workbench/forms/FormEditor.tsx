import {useEffect, useState} from 'react';
import {Alert, Modal, Spin, Tag} from 'antd';
import FormDesigner, {type FormDraft, type FormSummary, type DesignerContract} from '@simplepoint/components/FormDesigner';
import TableActions from '@simplepoint/components/Table/Actions';
import {get, useData} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import useFormDraft from './useFormDraft';
import FormPublication from './FormPublication';
import PublishedFormData from './PublishedFormData';
import ReleaseHistory from './ReleaseHistory';
import type {DesignPage} from './designPage';

type Props = {applicationId: string; form: FormSummary; contract: DesignerContract; page?: DesignPage; backToPages?: boolean; onClose: () => void};
export default function FormEditor(props: Props) {
  const {t} = useI18n();
  const endpoint = `/app/applications/${encodeURIComponent(props.applicationId)}/forms/${encodeURIComponent(props.form.id)}/draft`;
  const [loaded, setLoaded] = useState<FormDraft>();
  const query = useData(['app', 'form-draft', props.applicationId, props.form.id],
    ({signal}) => get<FormDraft>(endpoint, undefined, {signal}), {retry: false, refetchOnWindowFocus: false, refetchOnMount: 'always'});
  useEffect(() => {
    if (!loaded && query.data && !query.isFetching && !query.error) setLoaded(query.data);
  }, [loaded, query.data, query.isFetching, query.error]);
  if (!loaded) return <>
    <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'back', type: 'text', iconOnly: true, icon: 'ArrowLeftOutlined', sort: 0, text: t('app.designer.actionBack', '返回')}]}
      onButtonEvents={{back: props.onClose}}/>
    {query.error ? <Alert type="error" showIcon title={t('app.designer.loadFailed', '草稿加载失败')}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', icon: 'ReloadOutlined', sort: 0, text: t('app.retry', '重试')}]}
        onButtonEvents={{retry: () => void query.refetch()}}/>}/> : <Spin/>}
  </>;
  return <EditorSession key={endpoint} {...props} endpoint={endpoint} initial={loaded}/>;
}
function EditorSession({form, contract, page, onClose, endpoint, initial}: Props & {endpoint: string; initial: FormDraft}) {
  const {t} = useI18n(), selected = usePageSelected();
  const [confirm, setConfirm] = useState<'close' | 'reload'>();
  const [publishing, setPublishing] = useState(false), [publishOpen, setPublishOpen] = useState(false);
  const [dataPageId, setDataPageId] = useState<string>();
  const [historyOpen, setHistoryOpen] = useState(false);
  const editor = useFormDraft(endpoint, initial, contract.canEdit && form.status !== 'ARCHIVED', Boolean(confirm) || publishOpen || historyOpen || Boolean(dataPageId));
  const close = () => {if (editor.busy) return; if (editor.dirty || editor.retry) setConfirm('close'); else onClose();};
  const reload = () => {if (editor.busy) return; if (editor.dirty || editor.retry) setConfirm('reload'); else void editor.reload();};
  return <div className="app-form-editor">
    {editor.error && <Alert type="error" showIcon title={editor.error}
      action={editor.retry && <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', icon: 'ReloadOutlined', sort: 0,
        text: t('app.retry', '重试'), disabled: editor.busy || !selected}]} onButtonEvents={{retry: () => void editor.save()}}/>}/>}
    <FormDesigner key={editor.reset} document={editor.document} components={contract.components}
      toolbar={{
        title: <div className="app-form-editor-identity">
          <span className="app-form-editor-name" title={form.name}>{form.name}</span>
          <Tag>{editor.busy ? t('app.designer.saving', '正在保存') : editor.dirty
            ? t('app.designer.unsaved', '未保存') : t('app.designer.saved', '已保存')}</Tag>
        </div>,
        beforePreview: <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
          {key: 'back', type: 'text', iconOnly: true, icon: 'ArrowLeftOutlined', sort: 0, text: t('app.designer.actionBack', '返回'), disabled: editor.busy || !selected},
        ]} onButtonEvents={{back: close}}/>,
        renderActions: ({buttons, onButtonEvents, active}) => (
          <TableActions selectedKeys={[]} selectedRows={[]}
            menu={{trigger: ['hover', 'click'], label: t('app.designer.actions', '操作'),
              disabled: editor.busy || !active || Boolean(confirm) || publishOpen || historyOpen || Boolean(dataPageId)}}
            buttons={[
              ...buttons,
              {key: 'reload', icon: 'ReloadOutlined', sort: 2, text: t('app.designer.actionReload', '重载'), disabled: editor.busy || !selected},
              ...(contract.canEdit ? [{key: 'save', icon: 'SaveOutlined', sort: 3, text: t('app.designer.actionSave', '保存'),
                disabled: editor.busy || editor.conflict || !selected || (!editor.dirty && !editor.retry)}] : []),
              ...(contract.canEdit && contract.canPublish ? [{key: 'publish', icon: 'CloudUploadOutlined', sort: 4,
                text: t('app.designer.actionPublish', '发布'),
                disabled: editor.busy || editor.dirty || editor.conflict || Boolean(editor.retry) || publishing || !selected || form.status === 'ARCHIVED'}] : []),
              {key: 'history', icon: 'HistoryOutlined', sort: 5, text: t('app.designer.actionHistory', '历史'), disabled: editor.busy || !selected},
            ]} onButtonEvents={{...onButtonEvents, reload, save: () => void editor.save(), publish: () => setPublishOpen(true), history: () => setHistoryOpen(true)}}/>
        ),
      }}
      writePolicies={contract.writePolicies}
      disabled={editor.disabled || Boolean(confirm) || publishOpen || historyOpen || Boolean(dataPageId)} onChange={editor.change}/>
    <FormPublication applicationId={form.applicationId} formId={form.id} revision={editor.revision} checksum={editor.checksum}
      fields={editor.document.fields} initialPage={page}
      open={publishOpen} enabled={contract.canEdit && contract.canPublish && form.status !== 'ARCHIVED'}
      onClose={() => setPublishOpen(false)} onViewData={setDataPageId} onBusyChange={setPublishing}/>
    {historyOpen && <ReleaseHistory applicationId={form.applicationId} formId={form.id} onClose={() => setHistoryOpen(false)} onViewData={setDataPageId}/>}
    {dataPageId && <PublishedFormData applicationId={form.applicationId} formId={form.id} pageId={dataPageId} onClose={() => setDataPageId(undefined)}/>}
    <Modal open={Boolean(confirm) && selected} title={t('app.draft.discardTitle', '放弃未保存的修改？')}
      onCancel={() => setConfirm(undefined)} footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'continue', icon: 'EditOutlined', sort: 0, text: t('app.draft.continue', '继续编辑')},
        {key: 'discard', icon: 'DeleteOutlined', sort: 1, text: t('app.draft.discard', '放弃修改'), danger: true},
      ]} onButtonEvents={{continue: () => setConfirm(undefined), discard: () => {
        const action = confirm; setConfirm(undefined); if (action === 'close') onClose(); else void editor.reload();
      }}}/>}/>
  </div>;
}
