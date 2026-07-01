// utils/i18nCache.ts
import type { Messages } from '@/fetches/i18n';

const CACHE_TTL_MS = 6 * 60 * 60 * 1000; // 6 小时
const VERSION_KEY = 'sp.i18n.version';
const APP_I18N_VERSION = '1';

export const cacheKey = (lng: string) => `sp.i18n.messages.${lng}`;
export const cacheTsKey = (lng: string) => `sp.i18n.cachedAt.${lng}`;

export const readStoredMessages = (lng: string): Messages | undefined => {
  try {
    const tsRaw = localStorage.getItem(cacheTsKey(lng));
    if (!tsRaw) return undefined;
    const ts = Number(tsRaw);
    if (!Number.isFinite(ts) || Date.now() - ts > CACHE_TTL_MS) return undefined;
    const raw = localStorage.getItem(cacheKey(lng));
    if (raw) return JSON.parse(raw) as Messages;
  } catch (err) {
    console.warn('[i18n] Failed to read cache:', err);
  }
  return undefined;
};

export const writeStoredMessages = (lng: string, data: Messages) => {
  try {
    localStorage.setItem(cacheKey(lng), JSON.stringify(data));
    localStorage.setItem(cacheTsKey(lng), String(Date.now()));
  } catch (err) {
    console.warn('[i18n] Failed to write cache:', err);
  }
};

export const clearI18nCaches = () => {
  try {
    Object.keys(localStorage)
      .filter(k => k.startsWith('sp.i18n.messages.') || k.startsWith('sp.i18n.cachedAt.'))
      .forEach(k => localStorage.removeItem(k));
  } catch (err) {
    console.warn('[i18n] Failed to clear i18n caches:', err);
  }
};

export const checkAndUpgradeCacheVersion = () => {
  try {
    const storedV = localStorage.getItem(VERSION_KEY);
    if (storedV !== APP_I18N_VERSION) {
      clearI18nCaches();
      localStorage.setItem(VERSION_KEY, APP_I18N_VERSION);
    }
  } catch (err) {
    console.warn('[i18n] Failed to check cache version:', err);
  }
};
