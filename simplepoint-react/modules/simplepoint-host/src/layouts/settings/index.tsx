import {useEffect, useMemo, useState} from 'react';
import {App, Descriptions, Divider, Space, Tag, Typography, version as antdVersion} from 'antd';
import SForm from '@simplepoint/components/SForm/Lazy';
import TableActions from '@simplepoint/components/Table/Actions';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {useI18n} from '@/layouts/i18n/useI18n';
import hostPackage from '../../../package.json';
import './index.css';

type ThemeMode = 'light' | 'dark' | 'system';
type SizeMode = 'small' | 'middle' | 'large';
const themeModes: ThemeMode[] = ['light', 'dark', 'system'];
const sizeModes: SizeMode[] = ['small', 'middle', 'large'];
const THEME_KEY = 'sp.theme', SIZE_KEY = 'sp.globalSize';
const stored = <T extends string,>(key: string, values: readonly T[], fallback: T): T => {
  try {const value = localStorage.getItem(key) as T; return values.includes(value) ? value : fallback;}
  catch {return fallback;}
};
function apply(key: string, value: string, event: string) {
  localStorage.setItem(key, value);
  window.dispatchEvent(new CustomEvent(event, {detail: value}));
}

export function Settings() {
  const {t, languages = [], locale, setLocale, ensure} = useI18n();
  const {message} = App.useApp();
  const [themeMode, setThemeMode] = useState(() => stored(THEME_KEY, themeModes, 'light'));
  const [sizeMode, setSizeMode] = useState(() => stored(SIZE_KEY, sizeModes, 'middle'));
  const [systemTheme, setSystemTheme] = useState<'light' | 'dark'>(() =>
    window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  const resolvedTheme = themeMode === 'system' ? systemTheme : themeMode;
  usePageRetention(true, false);
  useEffect(() => {void ensure(['settings', 'table']);}, [ensure, locale]);
  useEffect(() => {
    const theme = (event: Event) => {const value = (event as CustomEvent<ThemeMode>).detail;
      if (themeModes.includes(value)) setThemeMode(value);};
    const size = (event: Event) => {const value = (event as CustomEvent<SizeMode>).detail;
      if (sizeModes.includes(value)) setSizeMode(value);};
    const media = window.matchMedia('(prefers-color-scheme: dark)');
    const system = () => setSystemTheme(media.matches ? 'dark' : 'light');
    window.addEventListener('sp-set-theme', theme); window.addEventListener('sp-set-size', size);
    media.addEventListener('change', system);
    return () => {window.removeEventListener('sp-set-theme', theme); window.removeEventListener('sp-set-size', size);
      media.removeEventListener('change', system);};
  }, []);
  const appearanceSchema = useMemo(() => ({type: 'object' as const, properties: {
    theme: {type: 'string' as const, title: t('settings.theme'), oneOf: [
      {const: 'light', title: t('settings.light')}, {const: 'dark', title: t('settings.dark')},
      {const: 'system', title: t('settings.system')},
    ]},
    size: {type: 'string' as const, title: t('settings.size'), oneOf: [
      {const: 'small', title: t('size.small')}, {const: 'middle', title: t('size.middle')}, {const: 'large', title: t('size.large')},
    ]},
  }}), [t]);
  const languageSchema = useMemo(() => ({type: 'object' as const, properties: {
    language: {type: 'string' as const, title: t('settings.language'),
      oneOf: languages.map(language => ({const: language.code, title: language.name}))},
  }}), [languages, t]);
  const updateAppearance = (next: {theme?: ThemeMode; size?: SizeMode}) => {
    try {
      if (next.theme && themeModes.includes(next.theme) && next.theme !== themeMode) {
        apply(THEME_KEY, next.theme, 'sp-set-theme'); setThemeMode(next.theme); message.success(t('settings.themeUpdated'));
      }
      if (next.size && sizeModes.includes(next.size) && next.size !== sizeMode) {
        apply(SIZE_KEY, next.size, 'sp-set-size'); setSizeMode(next.size); message.success(t('settings.sizeUpdated'));
      }
    } catch {message.error(t('error.operationFailed', '操作失败，请重试'));}
  };
  return <div className="settings-page">
    <section className="settings-form"><Typography.Title level={5}>{t('settings.appearance')}</Typography.Title>
      <SForm hideSubmit schema={appearanceSchema} formData={{theme: themeMode, size: sizeMode}}
        onChange={event => updateAppearance(event.formData ?? {})}/>
      <Space wrap>
        <Tag color="blue">{t('settings.theme')}: {t(`settings.${themeMode}`)}</Tag>
        <Tag>{t('settings.preview')}: {t(`settings.${resolvedTheme}`)}</Tag>
      </Space>
      <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'resetAppearance', sort: 0,
        icon: 'ReloadOutlined', text: t('action.reset'), onClick: () => {
          try {apply(THEME_KEY, 'light', 'sp-set-theme'); apply(SIZE_KEY, 'middle', 'sp-set-size');
            setThemeMode('light'); setSizeMode('middle'); message.success(t('settings.resetApplied'));}
          catch {message.error(t('error.operationFailed', '操作失败，请重试'));}
        }}]}/>
    </section>
    <Divider/>
    <section className="settings-form"><Typography.Title level={5}>{t('settings.i18n')}</Typography.Title>
      <SForm hideSubmit schema={languageSchema} formData={{language: locale}} onChange={event => {
        const next = event.formData?.language;
        if (next && next !== locale && languages.some(language => language.code === next)) {
          setLocale(next); message.success(t('settings.langUpdated'));
        }
      }}/>
      <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'resetLanguage', sort: 0,
        text: t('action.resetLang'), onClick: () => {setLocale('zh-CN'); message.success(t('settings.resetLangApplied'));}}]}/>
    </section>
    <Divider/>
    <section><Typography.Title level={5}>{t('settings.about')}</Typography.Title>
      <Descriptions size="small" column={2}>
        <Descriptions.Item label={t('about.version')}>{hostPackage.version}</Descriptions.Item>
        <Descriptions.Item label={t('about.license')}>Apache-2.0</Descriptions.Item>
        <Descriptions.Item label={t('about.ui')}>Ant Design {antdVersion}</Descriptions.Item>
      </Descriptions>
    </section>
  </div>;
}
