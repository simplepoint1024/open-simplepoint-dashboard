import type {WidgetProps} from '@rjsf/utils';
import UserSelect from '../../UserSelect';
import {userPickerIds} from '../../UserSelect/model';

/** Backward-compatible annotation adapter for global account fields. */
export default function UserPicker({id, value, required, disabled, readonly, onChange, onBlur, onFocus,
  options, placeholder, rawErrors, schema}: WidgetProps) {
  const multiple = options?.selectionMode === 'multiple'
    || (Array.isArray(schema.type) ? schema.type.includes('array') : schema.type === 'array');
  const ids = userPickerIds(value, multiple);
  const common = {id, directory: 'accounts' as const, accountOptions: options, disabled: disabled || readonly,
    allowClear: !required, placeholder, maxTagCount: 'responsive' as const,
    status: rawErrors?.length ? 'error' as const : undefined,
    onBlur: () => onBlur?.(id, value), onFocus: () => onFocus?.(id, value)};
  return multiple
    ? <UserSelect {...common} multiple value={ids} maxCount={typeof schema.maxItems === 'number' ? schema.maxItems : undefined} onChange={onChange}/>
    : <UserSelect {...common} multiple={false} value={ids[0]} onChange={next => onChange(next)}/>;
}
