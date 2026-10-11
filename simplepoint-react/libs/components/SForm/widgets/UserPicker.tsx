import type {WidgetProps} from '@rjsf/utils';
import {useMemo} from 'react';
import EntitySelect, {type EntitySelectSource} from '../../EntitySelect';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {userPickerConfiguration, userPickerIds, userPickerLookupBatches, userPickerOption} from './userPickerModel';

/** Legacy schema fields adapt their real directory protocol to the platform's scope-keyed selector. */
export default function UserPicker({id, value, required, disabled, readonly, onChange, onBlur, onFocus,
  options, placeholder, rawErrors, schema}: WidgetProps) {
  const {t} = useI18n();
  const config = userPickerConfiguration(options ?? {});
  const multiple = options?.selectionMode === 'multiple'
    || (Array.isArray(schema.type) ? schema.type.includes('array') : schema.type === 'array');
  const ids = userPickerIds(value, multiple);
  const source = useMemo<EntitySelectSource>(() => ({
    key: config.key, pageSize: config.pageSize, debounceMs: config.debounceMs, minSearchLength: config.minSearchLength,
    searchParams: (keyword, page, size) => ({...config.staticParams, [config.pageParam]: page,
      [config.sizeParam]: size, [config.searchParam]: keyword.trim()}),
    option: userPickerOption,
    resolve: async (requested, signal) => {
      const selected = [];
      const expected = new Set(requested);
      for (const batch of userPickerLookupBatches(requested, config.staticParams, config.resolveParam)) {
        const result = await get<Record<string, unknown>[]>(config.resolveEndpoint,
          {...config.staticParams, [config.resolveParam]: batch.join(',')}, {signal, notifyOnError: false});
        selected.push(...(Array.isArray(result) ? result : []).flatMap(row => {
          const option = userPickerOption(row);
          return option && expected.has(option.value) ? [option] : [];
        }));
      }
      return selected;
    },
  }), [config.key]);
  const common = {id, endpoint: config.searchEndpoint, source, disabled: disabled || readonly, allowClear: !required,
    placeholder: placeholder || t('form.userPicker.placeholder', '输入邮箱或手机号搜索用户'), maxTagCount: 'responsive' as const,
    searchHint: t('form.userPicker.minSearch', '请输入至少 {count} 位邮箱或手机号', {count: config.minSearchLength}),
    status: rawErrors?.length ? 'error' as const : undefined,
    onBlur: () => onBlur?.(id, value), onFocus: () => onFocus?.(id, value)};
  return multiple
    ? <EntitySelect {...common} multiple value={ids} maxCount={typeof schema.maxItems === 'number' ? schema.maxItems : undefined} onChange={next => onChange(next)}/>
    : <EntitySelect {...common} multiple={false} value={ids[0]} onChange={next => onChange(next)}/>;
}
