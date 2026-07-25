import type {WidgetProps} from '@rjsf/utils';
import OssFileUpload from './OssFileUpload';

const OssFileWidget = ({value, onChange, disabled, readonly, options}: WidgetProps) => (
  <OssFileUpload
    value={typeof value === 'string' ? value : undefined}
    onChange={onChange}
    disabled={disabled}
    readOnly={readonly}
    directory={typeof options?.directory === 'string' ? options.directory : undefined}
    sourceServiceName={typeof options?.sourceServiceName === 'string' ? options.sourceServiceName : undefined}
    maxSizeMb={typeof options?.maxSizeMb === 'number' ? options.maxSizeMb : undefined}
    accept={typeof options?.accept === 'string' ? options.accept : undefined}
  />
);

export default OssFileWidget;
