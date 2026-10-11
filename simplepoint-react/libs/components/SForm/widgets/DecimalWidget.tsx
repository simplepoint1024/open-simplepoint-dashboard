import type {WidgetProps} from '@rjsf/utils';
import {Input} from 'antd';

/** Real form-engine widget: preserve the entered decimal string; never clamp, round or parse as Number. */
export default function DecimalWidget({id, value, required, disabled, readonly, onChange, onBlur, onFocus, schema, placeholder}: WidgetProps) {
  const nullable = Array.isArray(schema.type) && schema.type.includes('null');
  return <Input id={id} value={value == null ? '' : String(value)} required={required} disabled={disabled}
    readOnly={readonly} inputMode="decimal" maxLength={schema.maxLength ?? 2000} placeholder={placeholder}
    onChange={event => onChange(event.target.value === '' ? nullable ? null : undefined : event.target.value)}
    onBlur={event => onBlur?.(id, event.target.value)} onFocus={event => onFocus?.(id, event.target.value)}/>;
}
