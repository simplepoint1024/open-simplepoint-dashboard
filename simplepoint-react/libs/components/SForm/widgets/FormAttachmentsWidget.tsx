import {useRef, useState} from 'react';
import type {WidgetProps} from '@rjsf/utils';
import {App, Space, Typography} from 'antd';
import {request, resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import TableActions from '../../Table/Actions';
import {parseFormAttachments, type FormAttachment} from './formAttachments.ts';

/** Form-engine JSON attachment contract; generic OssFile URL fields retain their own contract. */
export default function FormAttachmentsWidget({id, value, onChange, disabled, readonly}: WidgetProps) {
  const input = useRef<HTMLInputElement>(null);
  const uploading = useRef(false);
  const [busy, setBusy] = useState(false);
  const {message} = App.useApp();
  let files: FormAttachment[] = [], invalid = false;
  try {files = parseFormAttachments(value);} catch {invalid = true;}
  const report = (error: unknown) => message.error(resolveApiErrorMessage(error, '附件操作失败，请重试'));
  const upload = async (selected: FileList | null) => {
    if (!selected?.length || disabled || readonly || uploading.current) return;
    const pending = Array.from(selected);
    if (files.length + pending.length > 10) {message.error('最多上传 10 个附件'); return;}
    if (pending.some(file => file.size > 10 * 1024 * 1024 || file.size === 0)) {
      message.error('每个附件须大于 0 字节且不超过 10 MB'); return;
    }
    uploading.current = true; setBusy(true);
    const next = [...files];
    try {
      // Commit each completed reference so a later failed upload never hides a successfully stored file.
      for (const file of pending) {
        const body = new FormData();
        body.append('file', file); body.append('directory', 'files/app-forms');
        body.append('sourceServiceName', 'simplepoint-app-form');
        const stored = await request<{id: string; originalFileName?: string; contentLength?: number}>(
          '/common/object-storage/upload', {method: 'POST', body});
        if (!stored?.id) throw new Error('附件上传响应无效');
        next.push({id: stored.id, name: stored.originalFileName || file.name, size: stored.contentLength ?? file.size});
        onChange(JSON.stringify(next));
      }
    } catch (error) {report(error);} finally {
      uploading.current = false; setBusy(false); if (input.current) input.current.value = '';
    }
  };
  const download = async (file: FormAttachment) => {
    try {
      const blob = await request<Blob>(`/common/object-storage/objects/${encodeURIComponent(file.id)}/content`, {responseType: 'blob'});
      const url = URL.createObjectURL(blob), link = document.createElement('a');
      link.href = url; link.download = file.name; link.click();
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (error) {report(error);}
  };
  return <Space orientation="vertical" size={8} style={{width: '100%'}}>
    <input ref={input} id={id} type="file" multiple hidden onChange={event => void upload(event.target.files)}/>
    {!readonly && <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{
      key: 'upload', sort: 0, text: '上传附件', icon: 'UploadOutlined', loading: busy,
      disabled: disabled || busy || invalid || files.length >= 10, argumentMinSize: 0, argumentMaxSize: 0,
    }]} onButtonEvents={{upload: () => input.current?.click()}}/>}
    {files.map(file => <Space key={file.id} wrap>
      <Typography.Text>{file.name}（{Math.ceil(file.size / 1024)} KB）</Typography.Text>
      <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'download', sort: 0, text: '下载附件', icon: 'DownloadOutlined', iconOnly: true, argumentMinSize: 0, argumentMaxSize: 0},
        ...(!readonly ? [{key: 'remove', sort: 1, text: '移除附件', icon: 'DeleteOutlined', iconOnly: true,
          disabled: disabled || busy, argumentMinSize: 0, argumentMaxSize: 0}] : []),
      ]} onButtonEvents={{download: () => void download(file), remove: () => {
        const remaining = files.filter(item => item.id !== file.id);
        onChange(remaining.length ? JSON.stringify(remaining) : undefined);
      }}}/>
    </Space>)}
    {invalid && <Typography.Text type="danger" role="alert">附件数据格式无效，请联系管理员</Typography.Text>}
  </Space>;
}
