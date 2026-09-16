import type {WidgetProps} from '@rjsf/utils';
import OrganizationSelect from '../../OrganizationSelect';

const OrganizationSelectWidget = ({
  id,
  value,
  required,
  disabled,
  readonly,
  onChange,
  onBlur,
  onFocus,
  options,
  placeholder,
  rawErrors,
}: WidgetProps) => {
  const config = (options ?? {}) as Record<string, unknown>;
  return (
    <OrganizationSelect
      id={id}
      value={typeof value === 'string' ? value : undefined}
      endpoint={typeof config.endpoint === 'string' ? config.endpoint : undefined}
      excludeId={typeof config.excludeId === 'string' ? config.excludeId : undefined}
      pageSize={typeof config.pageSize === 'number' ? config.pageSize : undefined}
      debounceMs={typeof config.debounceMs === 'number' ? config.debounceMs : undefined}
      onlyEnabled={config.onlyEnabled === true}
      allowClear={!required}
      disabled={disabled || readonly}
      status={rawErrors && rawErrors.length > 0 ? 'error' : undefined}
      placeholder={placeholder}
      onChange={nextValue => onChange(nextValue)}
      onBlur={() => onBlur?.(id, value)}
      onFocus={() => onFocus?.(id, value)}
      style={{width: '100%'}}
    />
  );
};

export default OrganizationSelectWidget;
