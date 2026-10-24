import {useCallback, useEffect, useRef, useState} from 'react';
import type {FieldProps} from '@rjsf/utils';
import LocalizedText, {type LanguageOption} from '../../LocalizedText';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useOwnedData} from '@simplepoint/shared/hooks/useOwnedData';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {get} from '@simplepoint/shared/api/methods';
import {canonicalLocale, type LocalizedText as Value} from '@simplepoint/shared/i18n/localizedText';

/** Platform IO adapter; the controlled editor itself has no business endpoint or tenant dependency. */
export default function LocalizedTextField(props: FieldProps) {
  // Masked projections are scalars and must never open a translation/source reader.
  if (props.schema['x-field-access'] === 'MASKED' || (props.formData != null && typeof props.formData !== 'object')) {
    return <span>{typeof props.formData === 'string' ? props.formData : '***'}</span>;
  }
  return <LocalizedTextEditor {...props}/>;
}

function LocalizedTextEditor(props: FieldProps) {
  const {locale, t} = useI18n();
  const options = props.uiSchema?.['ui:options'] ?? {};
  const context = props.registry.formContext ?? {};
  const source = (context.localizedFields?.[props.name] ?? {}) as {
    owner?: string; scope?: 'PLATFORM' | 'TENANT'; inheritedKey?: string; namespace?: string; fallback?: string;
  };
  const operation = useOperationScope(source.owner);
  const [open, setOpen] = useState(false), [codes, setCodes] = useState<string[]>([canonicalLocale(locale)]);
  const release = useRef<(() => void) | undefined>(undefined);
  useEffect(() => () => release.current?.(), []);
  useEffect(() => {
    if (!operation.selected) {setOpen(false); release.current?.(); release.current = undefined;}
  }, [operation.selected]);
  useEffect(() => {setOpen(false); release.current?.(); release.current = undefined;}, [source.owner, operation.current]);
  const draft = (props.formData ?? {}) as Partial<Value>;
  const value: Value = {defaultValue: draft.defaultValue ?? '', defaultLocale: draft.defaultLocale || canonicalLocale(locale),
    translations: draft.translations ?? {}};
  const rule = props.schema['x-localized'] ?? {};
  const change = (next: Value) => {
    if (operation.current() && operation.isSelected() && !props.disabled && !props.readonly) props.onChange(next, props.fieldPathId.path);
  };
  const onOpenChange = (next: boolean) => {
    setOpen(next); release.current?.(); release.current = undefined;
    if (next && !props.readonly) release.current = context.beginPreparation?.();
  };
  const onLanguagesChange = useCallback((next: string[]) => setCodes(previous => JSON.stringify(previous) === JSON.stringify(next) ? previous : next), []);
  const languages = useOwnedData<Record<string, string>>(['localized-field', 'languages'],
    ({signal}) => get('/common/i18n/languages/mapping', undefined, {signal}), {enabled: open && operation.selected, retry: false});
  const key = source.inheritedKey?.replace(/^i18n:/, '');
  const inherited = useOwnedData<Record<string, string>>(['localized-field', source.owner, key, source.namespace, codes], async ({signal}) => {
    if (!key) return {};
    const entries = await Promise.all(codes.map(async code => {
      const messages = await get<Record<string, string>>('/common/i18n/messages/mapping', {locale: code, ns: source.namespace ?? 'common'}, {signal});
      return [code, messages[key]] as const;
    }));
    return Object.fromEntries(entries.filter((entry): entry is readonly [string, string] => !!entry[1]));
  }, {enabled: open && operation.selected && !!key && !props.disabled, retry: false});
  const available: LanguageOption[] = Object.entries(languages.data ?? {}).map(([code, name]) => ({code, name}));
  const all = new Map(available.map(item => [canonicalLocale(item.code), item]));
  for (const code of [locale, value.defaultLocale, 'zh-CN', 'en-US', 'ja-JP', ...Object.keys(value.translations)]) {
    if (!all.has(canonicalLocale(code))) all.set(canonicalLocale(code), {code, name: new Intl.DisplayNames([locale], {type: 'language'}).of(canonicalLocale(code)) ?? code});
  }
  const currentInherited = key ? t(key, '') : '';
  return <LocalizedText key={source.owner} open={open} id={props.fieldPathId.$id} value={value} onChange={change} locale={locale}
    title={props.schema.title ?? props.name} scope={source.scope ?? 'TENANT'} languages={[...all.values()]}
    inherited={{...(currentInherited ? {[canonicalLocale(locale)]: currentInherited} : {}), ...inherited.data}}
    readOnly={props.readonly || props.schema.readOnly} disabled={props.disabled || !operation.selected}
    multiline={rule.multiline === true || options.multiline === true} maxLength={rule.maxLength ?? 255}
    maxLanguages={rule.maxLanguages ?? 32} loading={inherited.isFetching || languages.isFetching}
    error={inherited.error || languages.error ? t('form.localized.loadFailed', '翻译或语言列表加载失败，请重试') : undefined}
    onRetry={() => {void inherited.refetch(); void languages.refetch();}}
    onOpenChange={onOpenChange} onLanguagesChange={onLanguagesChange}/>
}
