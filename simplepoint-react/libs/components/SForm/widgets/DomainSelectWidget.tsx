import type {WidgetProps} from '@rjsf/utils';
import UserSelect from '../../UserSelect';
import RoleSelect from '../../RoleSelect';
import TenantSelect from '../../TenantSelect';

/** Schema/annotation adapters preserve form validation, focus and single/multiple value contracts. */
export function DomainSelectWidget({kind, id, value, onChange, options, schema, disabled, readonly, required,
  placeholder, rawErrors, onBlur, onFocus}: WidgetProps & {kind: 'user' | 'role' | 'tenant'}) {
  const multiple = (Array.isArray(schema.type) ? schema.type.includes('array') : schema.type === 'array') || options.multiple === true;
  const common = {id, disabled: disabled || readonly, allowClear: !required, placeholder,
    status: rawErrors?.length ? 'error' as const : undefined, maxTagCount: 'responsive' as const,
    onBlur: () => onBlur?.(id, value), onFocus: () => onFocus?.(id, value)};
  const selection = multiple
    ? {...common, multiple: true as const, value: Array.isArray(value) ? value : [],
      maxCount: typeof schema.maxItems === 'number' ? schema.maxItems : kind === 'tenant' ? undefined : 50, onChange}
    : {...common, multiple: false as const, value: typeof value === 'string' ? value : undefined,
      onChange: (next?: string) => onChange(next)};
  if (kind === 'tenant') return <TenantSelect {...selection} directory={options.directory === 'platform' ? 'platform' : 'current'}/>;
  if (kind === 'role') return <RoleSelect {...selection} directory={options.directory === 'current' ? 'current' : 'tenant-authorities'}/>;
  return <UserSelect {...selection} directory={options.directory === 'accounts' ? 'accounts' : 'tenant'} accountOptions={options}/>;
}
export const UserSelectWidget = (props: WidgetProps) => <DomainSelectWidget {...props} kind="user"/>;
export const RoleSelectWidget = (props: WidgetProps) => <DomainSelectWidget {...props} kind="role"/>;
export const TenantSelectWidget = (props: WidgetProps) => <DomainSelectWidget {...props} kind="tenant"/>;
