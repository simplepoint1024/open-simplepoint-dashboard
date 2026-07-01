import fetches from '@/fetches';
import { get } from '@simplepoint/shared/api/methods';

export type Language = {
  code: string;   // 例如 zh-CN / en-US
  name: string;   // 展示名，例如 中文（简体）/ English
};

export type Messages = Record<string, string>;
type LanguageMapping = Record<string, string>;

const FALLBACK_LANGUAGES: Language[] = [
  { code: 'zh-CN', name: 'Chinese (Simplified)' },
  { code: 'en-US', name: 'English (US)' },
];

const normalizeLanguageCode = (code: string) => {
  const [language, region] = code.trim().replace(/_/g, '-').split('-');
  if (!language) return '';
  return region ? `${language.toLowerCase()}-${region.toUpperCase()}` : language.toLowerCase();
};

const toLanguageList = (data: unknown): Language[] => {
  if (Array.isArray(data)) {
    return data
      .map((item: any) => ({
        code: normalizeLanguageCode(item?.code ?? item?.locale ?? ''),
        name: String(item?.name ?? item?.nameNative ?? item?.nameEnglish ?? item?.code ?? item?.locale ?? ''),
      }))
      .filter(item => item.code && item.name);
  }

  if (data && typeof data === 'object') {
    return Object.entries(data as LanguageMapping)
      .map(([code, name]) => ({code: normalizeLanguageCode(code), name: String(name)}))
      .filter(item => item.code && item.name);
  }

  return [];
};

// 获取可选语言列表
export async function fetchLanguages(): Promise<Language[]> {
  const { baseUrl, expansion } = fetches['i18n-languages'];
  const url = `${baseUrl}${expansion.mapping}`;

  try {
    const data = await get<unknown>(url);
    const list = toLanguageList(data);
    if (list.length > 0) return list;
  } catch (err) {
    console.warn('[fetchLanguages] Failed to fetch languages:', err);
  }

  return FALLBACK_LANGUAGES;
}

// 获取指定语言的消息键值对（支持命名空间）
export async function fetchMessages(locale: string, namespaces?: string[]): Promise<Messages> {
  const { baseUrl, expansion } = fetches['i18n-messages'];
  const url = `${baseUrl}${expansion.mapping}`;

  const params: Record<string, string> = { locale };
  if (Array.isArray(namespaces) && namespaces.length > 0) {
    params.ns = namespaces.join(',');
  }

  try {
    const data = await get<Messages>(url, params);
    if (data && typeof data === 'object') return data;
  } catch (err) {
    console.warn(`[fetchMessages] Failed to fetch messages for ${locale}:`, err);
  }

  return {};
}
