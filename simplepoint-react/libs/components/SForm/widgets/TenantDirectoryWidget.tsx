import type {WidgetProps} from '@rjsf/utils';
import TenantDirectorySelect from '../../TenantDirectorySelect';

export default function TenantDirectoryWidget({id, value, onChange, options, schema,
  disabled, readonly, required, placeholder}: WidgetProps) {
  const kind = options.kind === 'roles' ? 'roles' : 'users';
  const multiple = (Array.isArray(schema.type) ? schema.type.includes('array') : schema.type === 'array') || options.multiple === true;
  const common = {id, kind, disabled: disabled || readonly, allowClear: !required, placeholder} as const;
  return multiple
    ? <TenantDirectorySelect {...common} multiple value={Array.isArray(value) ? value : []}
      maxCount={typeof schema.maxItems === 'number' ? schema.maxItems : 50} onChange={onChange}/>
    : <TenantDirectorySelect {...common} multiple={false} value={typeof value === 'string' ? value : undefined}
      onChange={next => onChange(next)}/>;
}
