import type {WidgetProps} from '@rjsf/utils';
import OssImageUpload from './OssImageUpload';

const OssImageWidget = ({value, onChange, disabled, readonly, options}: WidgetProps) => (
  <OssImageUpload
    value={typeof value === 'string' ? value : undefined}
    onChange={onChange}
    disabled={disabled}
    readOnly={readonly}
    directory={typeof options?.directory === 'string' ? options.directory : undefined}
    sourceServiceName={typeof options?.sourceServiceName === 'string' ? options.sourceServiceName : undefined}
    maxSizeMb={typeof options?.maxSizeMb === 'number' ? options.maxSizeMb : undefined}
    shape={options?.shape === 'circle' ? 'circle' : 'square'}
  />
);

export default OssImageWidget;
