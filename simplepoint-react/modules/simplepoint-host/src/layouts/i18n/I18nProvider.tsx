// context/I18nProvider.tsx
import React, {createContext, useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {fetchLanguages, fetchMessages, Language, Messages} from '@/fetches/i18n';
import {createTranslator, normalizeNamespaces} from '@simplepoint/shared/i18n/translator';
import {isRTL, mapDayjsLocale, normalizeLocale} from '@/utils/i18nUtils';
import {
    cacheKey,
    cacheTsKey,
    checkAndUpgradeCacheVersion,
    readStoredMessages,
    writeStoredMessages
} from '@/utils/i18nCache';
import {emitAsync} from '@/utils/i18nEvents';
import dayjs from 'dayjs';

export type I18nContextValue = {
    locale: string;
    setLocale: (code: string) => void;
    languages: Language[];
    messages: Messages;
    t: (key: string, fallbackOrParams?: string | Record<string, any>, maybeParams?: Record<string, any>) => string;
    loading: boolean;
    refresh: () => Promise<void>;
    ready: boolean;
    ensure: (ns: string[]) => Promise<void>;
};

export const I18nContext = createContext<I18nContextValue | undefined>(undefined);

export const I18nProvider: React.FC<{ children?: React.ReactNode }> = ({children}) => {
    const initialLocale = normalizeLocale(localStorage.getItem('sp.locale') || undefined);
    const [locale, setLocaleState] = useState<string>(initialLocale);
    const [languages, setLanguages] = useState<Language[]>([]);
    const [messages, setMessages] = useState<Messages>(readStoredMessages(initialLocale) || {});
    const [loading, setLoading] = useState<boolean>(false);
    const [initialLoadSettled, setInitialLoadSettled] = useState<boolean>(
        () => Object.keys(readStoredMessages(initialLocale) || {}).length > 0
    );

    const cache = useRef(new Map<string, Messages>());
    const loadSeqRef = useRef(0);
    const hasCompletedInitialLoadRef = useRef(false);
    const missingKeysRef = useRef<Set<string>>(new Set());
    const missingDebounceRef = useRef<number | null>(null);

    const loadedNsRef = useRef<Map<string, Set<string>>>(new Map());
    const ensurePendingNsRef = useRef<Set<string>>(new Set());
    const ensureTimerRef = useRef<number | null>(null);
    const ensureWaitersRef = useRef<Array<() => void>>([]);
    const ensureInflightRef = useRef<Promise<void> | null>(null);
    const localeRef = useRef(locale);
    localeRef.current = locale;

    const reportMissingKey = useCallback((key: string, lng = localeRef.current) => {
        if (process.env.NODE_ENV === 'production') return;

        const mk = `${lng}::${key}`;
        if (missingKeysRef.current.has(mk)) return;

        missingKeysRef.current.add(mk);
        if (missingDebounceRef.current) window.clearTimeout(missingDebounceRef.current);
        missingDebounceRef.current = window.setTimeout(() => {
            const list = Array.from(missingKeysRef.current.values());
            if (list.length > 0) {
                console.warn('[i18n] Missing keys:', list);
                emitAsync('sp-i18n-missing-batch', {keys: list});
            }
        }, 1200);
    }, []);

    const makeTranslator = useCallback((data: Messages, lng: string) => createTranslator(data, {
        onMissing: (key) => reportMissingKey(key, lng),
    }), [reportMissingKey]);

    useEffect(() => {
        checkAndUpgradeCacheVersion();
    }, []);

    useEffect(() => {
        const initial = readStoredMessages(initialLocale);
        if (initial && Object.keys(initial).length > 0) {
            cache.current.set(initialLocale, initial);
            setInitialLoadSettled(true);
            window.spI18n = {t: makeTranslator(initial, initialLocale), locale: initialLocale, setLocale, messages: initial, ensure};
        }
    }, []);

    const getLoadedNs = useCallback((lng: string): Set<string> => {
        let set = loadedNsRef.current.get(lng);
        if (!set) {
            set = new Set();
            loadedNsRef.current.set(lng, set);
        }
        return set;
    }, []);

    const applyLocale = useCallback((code: string) => {
        const norm = normalizeLocale(code);
        localStorage.setItem('sp.locale', norm);
        const schedule = typeof queueMicrotask === 'function' ? queueMicrotask : (fn: () => void) => Promise.resolve().then(fn);
        schedule(() => {
            setLocaleState(norm);
            emitAsync('sp-set-locale', norm);
        });
    }, []);

    const setLocale = useCallback((code: string) => applyLocale(code), [applyLocale]);

    useEffect(() => {
        const schedule = typeof queueMicrotask === 'function' ? queueMicrotask : (fn: () => void) => Promise.resolve().then(fn);
        const handler = (e: Event) => {
            const next = normalizeLocale(((e as CustomEvent<string>).detail) || 'zh-CN');
            if (next !== locale) schedule(() => setLocaleState(next));
        };
        window.addEventListener('sp-set-locale', handler);
        return () => window.removeEventListener('sp-set-locale', handler);
    }, [locale]);

    useEffect(() => {
        void (async () => {
            const list = await fetchLanguages();
            setLanguages(list);
        })();
    }, []);

    const updateGlobalI18n = (data: Messages, lng: string) => {
        window.spI18n = {t: makeTranslator(data, lng), locale: lng, setLocale, messages: data, ensure};
        emitAsync('sp-i18n-updated', {locale: lng});
    };

    const loadMessages = useCallback(async (lng: string) => {
        const lang = normalizeLocale(lng);
        const mySeq = ++loadSeqRef.current;

        const cached = cache.current.get(lang);
        if (cached && Object.keys(cached).length > 0) {
            if (mySeq !== loadSeqRef.current) return;
            setMessages(cached);
            updateGlobalI18n(cached, lang);
            return;
        }

        const stored = readStoredMessages(lang);
        if (stored && Object.keys(stored).length > 0) {
            cache.current.set(lang, stored);
            if (mySeq !== loadSeqRef.current) return;
            setMessages(stored);
            updateGlobalI18n(stored, lang);
            return;
        }

        setLoading(true);
        try {
            const data = await fetchMessages(lang);
            if (mySeq !== loadSeqRef.current) return;
            cache.current.set(lang, data);
            setMessages(data);
            writeStoredMessages(lang, data);
            updateGlobalI18n(data, lang);
            loadedNsRef.current.delete(lang);
        } catch (err) {
            console.warn(`[i18n] Failed to load messages for ${lang}`, err);
        } finally {
            setLoading(false);
        }
    }, [setLocale]);

    const ensure = useCallback(async (ns: string[]) => {
        if (!Array.isArray(ns) || ns.length === 0) return;

        const normalized = normalizeNamespaces(ns);
        if (normalized.length === 0) return;

        const loadedNs = getLoadedNs(locale);
        const missing = normalized.filter((key) => !loadedNs.has(key));
        if (missing.length === 0 && !ensureInflightRef.current && ensurePendingNsRef.current.size === 0) {
            return;
        }

        normalized.forEach(k => {
            if (k && !loadedNs.has(k)) {
                ensurePendingNsRef.current.add(k);
            }
        });

        const p = new Promise<void>(resolve => {
            ensureWaitersRef.current.push(resolve);
        });

        const flush = async () => {
            if (ensureInflightRef.current) return;
            const list = Array.from(ensurePendingNsRef.current.values());
            if (list.length === 0) {
                const waiters = ensureWaitersRef.current.splice(0, ensureWaitersRef.current.length);
                waiters.forEach(fn => {
                    try {
                        fn();
                    } catch {
                    }
                });
                return;
            }

            ensurePendingNsRef.current.clear();
            const lng = locale;

            ensureInflightRef.current = fetchMessages(lng, list)
                .then((data) => {
                    const validData = data && typeof data === 'object' ? data : {};
                    const hasData = Object.keys(validData).length > 0;

                    list.forEach(k => getLoadedNs(lng).add(k));

                    if (hasData) {
                        // 无论 locale 是否已切换，都缓存到正确的 locale
                        const cachedForLng = cache.current.get(lng) || {};
                        const merged = {...cachedForLng, ...validData};
                        cache.current.set(lng, merged);
                        writeStoredMessages(lng, merged);

                        // 仅当 locale 未切换时才更新 state
                        if (lng === localeRef.current) {
                            setMessages(prev => {
                                const mergedState = {...prev, ...validData};
                                updateGlobalI18n(mergedState, lng);
                                return mergedState;
                            });
                        }
                    }
                })
                .catch(err => {
                    console.warn('[i18n] ensure failed:', err);
                })
                .finally(() => {
                    ensureInflightRef.current = null;

                    if (ensurePendingNsRef.current.size > 0) {
                        if (ensureTimerRef.current) window.clearTimeout(ensureTimerRef.current);
                        ensureTimerRef.current = window.setTimeout(() => {
                            ensureTimerRef.current = null;
                            void flush();
                        }, 30);
                        return;
                    }

                    const waiters = ensureWaitersRef.current.splice(0, ensureWaitersRef.current.length);
                    waiters.forEach(fn => {
                        try {
                            fn();
                        } catch {
                        }
                    });
                });

            await ensureInflightRef.current;
        };

        if (ensureTimerRef.current) window.clearTimeout(ensureTimerRef.current);
        ensureTimerRef.current = window.setTimeout(() => {
            ensureTimerRef.current = null;
            void flush();
        }, 30);

        return p;
    }, [locale, getLoadedNs]);

    const t = useMemo<I18nContextValue['t']>(() => makeTranslator(messages, locale), [makeTranslator, messages, locale]);

    const refresh = useCallback(async () => {
        cache.current.delete(locale);
        localStorage.removeItem(cacheKey(locale));
        localStorage.removeItem(cacheTsKey(locale));
        await loadMessages(locale);
    }, [locale, loadMessages]);

    const value = useMemo<I18nContextValue>(() => ({
        locale,
        setLocale,
        languages,
        messages,
        t,
        loading,
        refresh,
        ready: !loading && (initialLoadSettled || Object.keys(messages || {}).length > 0),
        ensure,
    }), [locale, setLocale, languages, messages, t, loading, refresh, initialLoadSettled, ensure]);

    useEffect(() => {
        window.spI18n = {t, locale, setLocale, messages, ensure};
    }, [messages, locale, setLocale, ensure, t]);

    useEffect(() => {
        void loadMessages(locale).then(() => {
            setInitialLoadSettled(true);
            if (!hasCompletedInitialLoadRef.current) {
                hasCompletedInitialLoadRef.current = true;
                return;
            }
            const shouldRemount = localStorage.getItem('sp.i18n.forceRemount');
            if (shouldRemount === 'false') return;
            const path = window.location.hash?.replace(/^#/, '') || window.location.pathname || '/';
            emitAsync('sp-refresh-route', {path});
        });
        return () => {
            ensurePendingNsRef.current.clear();
            if (ensureTimerRef.current) {
                window.clearTimeout(ensureTimerRef.current);
                ensureTimerRef.current = null;
            }
        };
    }, [locale]);

    useEffect(() => {
        const el = document.documentElement;
        el.setAttribute('lang', locale);
        el.setAttribute('dir', isRTL(locale) ? 'rtl' : 'ltr');
        void import(`dayjs/locale/${mapDayjsLocale(locale)}.js`).then(() => {
            dayjs.locale(mapDayjsLocale(locale));
        }).catch(() => {
        });
    }, [locale]);

    return (
        <I18nContext.Provider value={value}>{children}</I18nContext.Provider>
    );
};
