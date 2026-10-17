import {useState, type ReactNode} from 'react';
import {Modal, Segmented} from 'antd';
import {createIcon} from '@simplepoint/shared/types/icon';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';

const devices = [
  {value: 'PC', icon: 'DesktopOutlined', width: 1120},
  {value: 'iPad', icon: 'TabletOutlined', width: 768},
  {value: 'Phone', icon: 'MobileOutlined', width: 390},
] as const;
type Device = typeof devices[number]['value'];

/** Preview sizes are presentation state: changing devices keeps the same live form and values. */
export default function FormPreview({open, onClose, children}: {open: boolean; onClose: () => void; children: ReactNode}) {
  const {t} = useI18n();
  const [device, setDevice] = useState<Device>('PC');
  const width = devices.find(item => item.value === device)!.width;
  return <Modal title={t('app.designer.preview', '预览')} width="min(1240px, 96vw)" open={open}
    onCancel={onClose} footer={null} destroyOnHidden wrapClassName="sp-form-designer-preview">
    <div className="sp-form-preview-stage">
      <div className="sp-form-preview-viewport" data-device={device} style={{width}}>
        <div className="sp-form-preview-floating-controls">
          {/* A segmented viewport selector stays with the preview; it does not change the design document. */}
          <Segmented className="sp-form-preview-devices" value={device} onChange={setDevice}
            aria-label={t('app.designer.previewDevice', '预览设备')}
            options={devices.map(item => ({value: item.value, label: item.value, icon: createIcon(item.icon)}))}/>
        </div>
        <div className="sp-form-preview-content">{children}</div>
      </div>
    </div>
  </Modal>;
}
