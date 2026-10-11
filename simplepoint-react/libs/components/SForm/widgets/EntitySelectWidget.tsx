import type {WidgetProps} from '@rjsf/utils';
import {Alert} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import EntitySelect from '../../EntitySelect';
import {isEntitySelectSource} from '../../EntitySelect/source';

export default function EntitySelectWidget({id, value, disabled, readonly, required,
  onChange, options, schema, placeholder}: WidgetProps) {
  const {t} = useI18n();
  const endpoint = typeof options?.endpoint === 'string' ? options.endpoint : '';
  if (!endpoint) return <Alert type="error" showIcon title={t('form.remoteSelect.invalidEndpoint', '远程选择器未配置有效的数据地址')}/>;
  const filters = options.filters && typeof options.filters === 'object' && !Array.isArray(options.filters)
    ? Object.fromEntries(Object.entries(options.filters).filter((entry): entry is [string, string] => typeof entry[1] === 'string'))
    : undefined;
  const lookupFilters = options.lookupFilters && typeof options.lookupFilters === 'object' && !Array.isArray(options.lookupFilters)
    ? Object.fromEntries(Object.entries(options.lookupFilters).filter((entry): entry is [string, string] => typeof entry[1] === 'string'))
    : undefined;
  const source = isEntitySelectSource(options.source) ? options.source : undefined;
  if (options.source !== undefined && !source) return <Alert type="error" showIcon title={t('form.remoteSelect.invalidSource', '远程选择器未配置有效的数据适配器')}/>;
  const common = {id, endpoint, filters, lookupFilters, source, disabled: disabled || readonly, allowClear: !required, placeholder};
  return (Array.isArray(schema.type) ? schema.type.includes('array') : schema.type === 'array') || options.multiple === true
    ? <EntitySelect {...common} multiple value={Array.isArray(value) ? value : []}
      maxCount={typeof schema.maxItems === 'number' ? schema.maxItems : undefined} onChange={onChange}/>
    : <EntitySelect {...common} multiple={false} value={typeof value === 'string' ? value : undefined}
      onChange={next => onChange(next)}/>;
}
