import {DeleteOutlined, DownloadOutlined, LoadingOutlined, UploadOutlined} from '@ant-design/icons';
import {Button, Space, Upload, message} from 'antd';
import {useState} from 'react';
import {request} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';

type StoredObject = {
  id: string;
  originalFileName?: string;
};

export type OssFileUploadProps = {
  value?: string | null;
  onChange: (value?: string) => void;
  disabled?: boolean;
  readOnly?: boolean;
  directory?: string;
  sourceServiceName?: string;
  maxSizeMb?: number;
  accept?: string;
};

/** Uploads a file to shared object storage and persists its authenticated download path. */
const OssFileUpload = ({
  value,
  onChange,
  disabled,
  readOnly,
  directory = 'files/forms',
  sourceServiceName = 'json-schema-form',
  maxSizeMb = 10,
  accept,
}: OssFileUploadProps) => {
  const {t} = useI18n();
  const [uploading, setUploading] = useState(false);
  const [fileName, setFileName] = useState<string>();
  const blocked = disabled || readOnly || uploading;

  const uploadFile = async (file: File) => {
    if (file.size > maxSizeMb * 1024 * 1024) {
      message.error(t('form.file.sizeError', '文件大小不能超过 {size} MB', {size: maxSizeMb}));
      return;
    }
    const formData = new FormData();
    formData.append('file', file);
    formData.append('directory', directory);
    formData.append('sourceServiceName', sourceServiceName);
    setUploading(true);
    try {
      const stored = await request<StoredObject>('/common/object-storage/upload', {
        method: 'POST',
        body: formData,
      });
      if (!stored?.id) {
        throw new Error(t('form.file.invalidResponse', '文件上传响应无效'));
      }
      setFileName(stored.originalFileName || file.name);
      onChange(`/common/object-storage/objects/${encodeURIComponent(stored.id)}/content`);
      message.success(t('form.file.uploaded', '文件上传成功'));
    } finally {
      setUploading(false);
    }
  };

  return (
    <Space size={8} wrap>
      <Upload
        accept={accept}
        showUploadList={false}
        disabled={blocked}
        beforeUpload={(file) => {
          void uploadFile(file as File).catch(() => undefined);
          return Upload.LIST_IGNORE;
        }}
      >
        <Button
          icon={uploading ? <LoadingOutlined/> : <UploadOutlined/>}
          loading={uploading}
          disabled={disabled || readOnly}
        >
          {value ? t('form.file.replace', '更换文件') : t('form.file.upload', '上传文件')}
        </Button>
      </Upload>
      {value ? (
        <Button
          type="link"
          icon={<DownloadOutlined/>}
          href={value}
          target="_blank"
          rel="noreferrer"
        >
          {fileName || t('form.file.download', '下载文件')}
        </Button>
      ) : null}
      {value && !readOnly ? (
        <Button
          danger
          icon={<DeleteOutlined/>}
          disabled={disabled || uploading}
          onClick={() => {
            setFileName(undefined);
            onChange(undefined);
          }}
        >
          {t('action.remove', '移除')}
        </Button>
      ) : null}
    </Space>
  );
};

export default OssFileUpload;
