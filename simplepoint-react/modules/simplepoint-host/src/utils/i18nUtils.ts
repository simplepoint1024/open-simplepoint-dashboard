// utils/i18nUtils.ts
import dayjs from 'dayjs';
import localizedFormat from 'dayjs/plugin/localizedFormat';
import relativeTime from 'dayjs/plugin/relativeTime';
import calendar from 'dayjs/plugin/calendar';

dayjs.extend(localizedFormat);
dayjs.extend(relativeTime);
dayjs.extend(calendar);

export const isRTL = (lng: string) => /^(ar|he|fa|ur)(-|$)/i.test(lng);

export const mapDayjsLocale = (lng: string) => {
  const raw = (lng || 'en-US').toLowerCase();
  const [lang, region] = raw.split(/[-_]/);
  const key = region ? `${lang}-${region}` : lang;
  const overrides: Record<string, string> = {
    'zh-cn': 'zh-cn',
    'zh-tw': 'zh-tw',
    'en-gb': 'en-gb',
    'pt-br': 'pt-br',
  };
  return overrides[key] || lang || 'en';
};

export const normalizeLocale = (code?: string): string => {
  const raw = (code || '').trim();
  if (!raw) return 'zh-CN';
  const map: Record<string, string> = {
    en: 'en-US', 'en-us': 'en-US',
    ja: 'ja-JP', 'ja-jp': 'ja-JP',
    zh: 'zh-CN', 'zh-cn': 'zh-CN',
    'zh-tw': 'zh-TW',
  };
  const low = raw.toLowerCase();
  if (map[low]) return map[low];
  const m = low.split(/[-_]/);
  const lang = (m[0] || 'en').toLowerCase();
  const region = (m[1] || '').toUpperCase();
  return region ? `${lang}-${region}` : (lang === 'zh' ? 'zh-CN' : lang === 'ja' ? 'ja-JP' : lang === 'en' ? 'en-US' : `${lang}-US`);
};

export const interpolate = (tpl: string, params?: Record<string, any>) =>
  params ? tpl.replace(/{(\w+)}/g, (_: any, k: string) => (params[k] !== undefined ? String(params[k]) : `{${k}}`)) : tpl;
