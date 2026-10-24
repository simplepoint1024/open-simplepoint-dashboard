import {useEffect, useMemo, useRef, useState} from 'react';
import {Alert, Input, Select, Tag} from 'antd';
import DataTable from '../DataTable';
import TableActions from '../Table/Actions';
import ResourceDrawer from '../ResourceDrawer';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {canonicalLocale, editLocalizedText, inheritLocalizedText, localizedTextValid, resolveLocalizedText,
  type LocalizedText as Value, type LocalizedSource} from '@simplepoint/shared/i18n/localizedText';
import './style.css';

export type LanguageOption = {code: string; name: string};
export type LocalizedTextProps = {
  value: Value; onChange: (value: Value) => void; locale: string; title?: string; id?: string;
  scope?: 'PLATFORM' | 'TENANT'; languages: LanguageOption[]; inherited?: Record<string, string>;
  readOnly?: boolean; disabled?: boolean; multiline?: boolean; maxLength?: number; maxLanguages?: number;
  loading?: boolean; error?: string; onRetry?: () => void;
  open?: boolean; onOpenChange?: (open: boolean) => void; onLanguagesChange?: (locales: string[]) => void;
};

/** Controlled field editor. All drafts belong to its caller; cancel restores only the opening snapshot. */
export default function LocalizedText({value, onChange, locale, title, id, scope = 'TENANT', languages, inherited = {},
  readOnly, disabled, multiline, maxLength = 255, maxLanguages = 32, loading, error, onRetry,
  open: controlledOpen, onOpenChange, onLanguagesChange}: LocalizedTextProps) {
  const {t} = useI18n();
  const [localOpen, setOpen] = useState(false), [added, setAdded] = useState<string[]>([]);
  const open = (controlledOpen ?? localOpen) && !disabled;
  const snapshot = useRef<Value | undefined>(undefined);
  const currentLocale = canonicalLocale(locale), immutable = !!readOnly || !!disabled;
  const defaults = useMemo(() => new Map(languages.map(language => [canonicalLocale(language.code), language.name])), [languages]);
  const codes = [...new Set([currentLocale, value.defaultLocale, ...Object.keys(value.translations), ...added])].sort((a, b) =>
    a === currentLocale ? -1 : b === currentLocale ? 1 : a.localeCompare(b));
  const codesKey = JSON.stringify(codes);
  useEffect(() => {onLanguagesChange?.(codes);}, [codesKey, onLanguagesChange]);
  const close = (cancel: boolean) => {
    if (cancel && snapshot.current && !immutable) onChange(snapshot.current);
    snapshot.current = undefined; setOpen(false); onOpenChange?.(false);
  };
  const begin = () => {
    snapshot.current = value; setOpen(true); onOpenChange?.(true);
  };
  const sourceLabels: Record<LocalizedSource, string> = {
    TENANT_CUSTOM: t('form.localized.tenantCustom', '租户自定义'),
    PLATFORM_CUSTOM: t('form.localized.platformCustom', '平台自定义'),
    PLATFORM_INHERITED: t('form.localized.inherited', '平台继承'),
    DEFAULT: t('form.localized.default', '默认名称'), MISSING: t('form.localized.missing', '未配置'),
  };
  const resolution = (code: string) => resolveLocalizedText(value, code, inherited[code], scope);
  const input = (code: string, inputId?: string) => {
    const props = {id: inputId, value: resolution(code).text, readOnly: immutable,
      'aria-label': `${title ?? t('form.localized.value', '名称')} · ${defaults.get(code) ?? code}`,
      onChange: (event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
        if (!immutable) onChange(editLocalizedText(value, code, event.target.value));
      }};
    // Specialized multilingual editor; ordinary form fields continue through SForm.
    return multiline ? <Input.TextArea {...props} autoSize={{minRows: 2, maxRows: 6}}/> : <Input {...props}/>;
  };
  return <div className="sp-localized-field">
    <div className="sp-localized-input">{input(currentLocale, id)}
      <Tag>{defaults.get(currentLocale) ?? currentLocale}</Tag>
      <span className={open && JSON.stringify(snapshot.current) !== JSON.stringify(value) ? 'sp-localized-dirty' : undefined}>
        <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'translate', sort: 0, icon: 'GlobalOutlined',
          iconOnly: true, type: 'text', title: t('form.localized.settings', '多语言设置'), disabled: !!disabled, onClick: begin}]}/>
      </span>
    </div>
    <ResourceDrawer open={open} placement="right" defaultWidth={780} minWidth={520}
      title={`${t('form.localized.settings', '多语言设置')}${title ? ' · '+title : ''}`} onClose={() => close(true)}>
      <div className="sp-localized-toolbar">
        {/* Language tags are a finite caller-provided catalog, not directory entity IDs. */}
        {!immutable && <Select value={undefined} showSearch optionFilterProp="label"
          aria-label={t('form.localized.addLanguage', '添加语言')} placeholder={t('form.localized.addLanguage', '添加语言')}
          disabled={codes.length >= maxLanguages}
          options={languages.filter(item => !codes.includes(canonicalLocale(item.code)))
            .map(item => ({value: canonicalLocale(item.code), label: `${item.name} (${item.code})`}))}
          onChange={(code: string) => setAdded(previous => [...previous, code])}/>}

        {error && <Alert type="error" showIcon title={error} action={<TableActions selectedKeys={[]} selectedRows={[]}
          buttons={[{key: 'retry', sort: 0, icon: 'ReloadOutlined', text: t('table.retry', '重试'), onClick: onRetry}]}/>}/>}
      </div>
      <DataTable rowKey="code" dataSource={codes.map(code => ({code}))} pagination={false} selectable={false}
        settings={false} cards={false} defaultOrder="provided" loading={loading}
        scroll={{x: 650, y: 420}} columns={[
          {key: 'language', title: t('form.localized.language', '语言'), width: 150, render: (_, row) => defaults.get(row.code) ?? row.code},
          {key: 'value', title: t('form.localized.value', '名称'), width: 260, render: (_, row) => input(row.code)},
          {key: 'source', title: t('form.localized.source', '来源'), width: 110, render: (_, row) => sourceLabels[resolution(row.code).source]},
          {key: 'actions', title: t('table.actions', '操作'), width: 130, render: (_, row) =>
            <TableActions appearance="row" selectedKeys={[row.code]} selectedRows={[row]} buttons={immutable ? [] : [{key: 'inherit', sort: 0,
              icon: 'UndoOutlined', text: inherited[row.code] ? t('form.localized.restore', '恢复继承') : t('form.localized.useDefault', '使用默认名称'),
              disabled: !Object.prototype.hasOwnProperty.call(value.translations, row.code),
              onClick: () => onChange(inheritLocalizedText(value, row.code, inherited[row.code]))}]}/>},
        ]}/>
      {!immutable && !localizedTextValid(value, maxLength, maxLanguages) && <Alert type="error" showIcon
        title={t('form.localized.invalid', '请填写有效的默认名称与译文，并检查字符长度和语言数量')}/>}
      <div className="sp-localized-footer"><TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'cancel', sort: 0, icon: 'CloseOutlined', text: t('cancel', '取消'), onClick: () => close(true)},
        {key: 'done', sort: 1, icon: 'CheckOutlined', text: t('form.localized.done', '完成'), color: 'primary',
          disabled: !immutable && !localizedTextValid(value, maxLength, maxLanguages), onClick: () => close(false)},
      ]}/></div>
    </ResourceDrawer>
  </div>;
}
