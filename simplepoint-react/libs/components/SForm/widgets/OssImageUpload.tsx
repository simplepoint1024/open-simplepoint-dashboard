import {DeleteOutlined, EyeOutlined, LoadingOutlined, UploadOutlined, UserOutlined} from '@ant-design/icons';
import {Avatar, Button, Image, Space, Upload, message} from 'antd';
import type {UploadFile} from 'antd';
import {useMemo, useState} from 'react';
import {request} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import '../index.css';

type StoredObject = {
  id: string;
};

export type OssImageUploadProps = {
  value?: string | null;
  onChange: (value?: string) => void;
  disabled?: boolean;
  readOnly?: boolean;
  directory?: string;
  sourceServiceName?: string;
  maxSizeMb?: number;
  shape?: 'circle' | 'square';
};

/** Uploads an image to the shared OSS service and stores only its stable image URL. */
export const OssImageUpload = ({
  value,
  onChange,
  disabled,
  readOnly,
  directory = 'images/forms',
  sourceServiceName = 'json-schema-form',
  maxSizeMb = 5,
  shape = 'square',
}: OssImageUploadProps) => {
  const {t} = useI18n();
  const [uploading, setUploading] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const blocked = disabled || readOnly || uploading;

  const fileList = useMemo<UploadFile[]>(() => value ? [{
    uid: value,
    name: t('form.image.current', '当前图片'),
    status: 'done',
    url: value,
  }] : [], [t, value]);

  const uploadImage = async (file: File) => {
    if (!file.type.toLowerCase().startsWith('image/')) {
      message.error(t('form.image.typeError', '请选择图片文件'));
      return;
    }
    if (file.size > maxSizeMb * 1024 * 1024) {
      message.error(t('form.image.sizeError', '图片大小不能超过 {size} MB', {size: maxSizeMb}));
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
        throw new Error(t('form.image.invalidResponse', '图片上传响应无效'));
      }
      onChange(`/common/object-storage/images/${encodeURIComponent(stored.id)}`);
      message.success(t('form.image.uploaded', '图片上传成功'));
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className={`oss-image-upload oss-image-upload-${shape}`}>
      <div className="oss-image-preview">
        {shape === 'circle' ? (
          <Avatar size={88} src={value || undefined} icon={!value ? <UserOutlined/> : undefined}/>
        ) : value ? (
          <Image
            src={value}
            width={120}
            height={88}
            style={{objectFit: 'cover', borderRadius: 8}}
            preview={{open: previewOpen, onOpenChange: setPreviewOpen}}
          />
        ) : (
          <div className="oss-image-empty"><UploadOutlined/></div>
        )}
      </div>
      <Space size={8} wrap>
        <Upload
          accept="image/png,image/jpeg,image/webp,image/gif,image/svg+xml"
          fileList={fileList}
          showUploadList={false}
          disabled={blocked}
          beforeUpload={(file) => {
            void uploadImage(file as File).catch(() => undefined);
            return Upload.LIST_IGNORE;
          }}
        >
          <Button
            icon={uploading ? <LoadingOutlined/> : <UploadOutlined/>}
            loading={uploading}
            disabled={disabled || readOnly}
          >
            {value ? t('form.image.replace', '更换图片') : t('form.image.upload', '上传图片')}
          </Button>
        </Upload>
        {value && shape === 'circle' ? (
          <Button icon={<EyeOutlined/>} onClick={() => setPreviewOpen(true)}>
            {t('form.image.preview', '预览')}
          </Button>
        ) : null}
        {value && !readOnly ? (
          <Button danger icon={<DeleteOutlined/>} disabled={disabled || uploading} onClick={() => onChange(undefined)}>
            {t('action.remove', '移除')}
          </Button>
        ) : null}
      </Space>
      {shape === 'circle' && value ? (
        <Image
          src={value}
          style={{display: 'none'}}
          preview={{open: previewOpen, onOpenChange: setPreviewOpen}}
        />
      ) : null}
      <div className="oss-image-hint">
        {t('form.image.hint', '支持 PNG、JPG、WebP、GIF，最大 {size} MB；文件将保存到 OSS。', {size: maxSizeMb})}
      </div>
    </div>
  );
};

export default OssImageUpload;
