import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

export type Messages = Record<string, string>;

export type I18nLike = {
    t: (
        key: string,
        fallbackOrParams?: string | Record<string, unknown>,
        maybeParams?: Record<string, unknown>
    ) => string;
    locale: string;
    setLocale: (code: string) => void;
    messages: Messages;
    ensure: (ns: string[]) => Promise<void> | void;
};

const isBrowser = typeof window !== 'undefined';

declare global {
    interface Window {
        spI18n?: I18nLike;
    }
}

/** 通用插值：支持 {a.b}、{0}、任意 key */
const interpolate = (tpl: string, params?: Record<string, unknown>) =>
    params
        ? tpl.replace(/\{([^}]+)}/g, (_, k: string) =>
            params[k] !== undefined ? String(params[k]) : `{${k}}`
        )
        : tpl;

/** 生成 t 函数 */
export const mkT = (messages: Messages): I18nLike['t'] => (
    key,
    fallbackOrParams,
    maybeParams
) => {
    const fallback = typeof fallbackOrParams === 'string' ? fallbackOrParams : undefined;
    const params =
        typeof fallbackOrParams === 'object'
            ? fallbackOrParams
            : typeof maybeParams === 'object'
                ? maybeParams
                : undefined;

    const raw = messages[key] ?? fallback ?? key;
    return interpolate(raw, params);
};

function getGlobal(): I18nLike | undefined {
    return isBrowser ? window.spI18n : undefined;
}

/** 命名空间加载缓存与锁（按 locale 隔离） */
const nsLoadedCache = new Set<string>();
const nsLoadingMap = new Map<string, Promise<void>>();

export function useI18n() {
    /** 单一 state，减少渲染次数 */
    const [state, setState] = useState(() => ({
        locale: 'zh-CN',
        messages: {} as Messages,
        t: mkT({})
    }));

    const { locale, messages, t } = state;

    /** 避免重复 setState */
    const lastMessagesRef = useRef(messages);

    /** 从全局同步状态 */
    const refreshFromGlobal = useCallback(() => {
        const g = getGlobal();
        if (!g) return;

        const nextMessages = g.messages || {};

        // 避免 messages 内容相同却重复渲染
        if (lastMessagesRef.current !== nextMessages) {
            lastMessagesRef.current = nextMessages;
            setState({
                locale: g.locale,
                messages: nextMessages,
                t: g.t ?? mkT(nextMessages)
            });
        }
    }, []);

    /** 设置语言（避免重复设置） */
    const setLocale = useCallback((code: string) => {
        const g = getGlobal();
        if (g?.locale !== code) {
            try {
                g?.setLocale?.(code);
            } catch {
                setState(s => ({ ...s, locale: code }));
            }
        }
    }, []);

    /** 命名空间加载（最终稳定版） */
    const ensure = useCallback(
        async (ns: string[]) => {
            const g = getGlobal();
            if (!g?.ensure) return;

            const currentLocale = g.locale;

            // 规范化命名空间
            const merged = Array.from(new Set(ns)).sort();

            // 使用 JSON 作为 key，避免冲突
            const key = `${currentLocale}::${JSON.stringify(merged)}`;

            // 已加载
            if (nsLoadedCache.has(key)) return;

            // 正在加载
            if (nsLoadingMap.has(key)) {
                await nsLoadingMap.get(key);
                return;
            }

            // 开始加载
            const loadPromise = (async () => {
                try {
                    await g.ensure(merged);
                    nsLoadedCache.add(key);
                } catch (e) {
                    console.warn('[i18n] ensure failed:', e);
                } finally {
                    nsLoadingMap.delete(key);
                    refreshFromGlobal();
                }
            })();

            nsLoadingMap.set(key, loadPromise);
            await loadPromise;
        },
        [refreshFromGlobal]
    );

    /** 初始化 + 监听全局事件 */
    useEffect(() => {
        if (!isBrowser) return;

        refreshFromGlobal();

        const onUpdated = () => refreshFromGlobal();
        window.addEventListener('sp-i18n-updated', onUpdated);

        return () => window.removeEventListener('sp-i18n-updated', onUpdated);
    }, [refreshFromGlobal]);

    /** ready 状态 */
    const ready = useMemo(() => !!messages, [messages]);

    return {
        t,
        locale,
        setLocale,
        messages,
        ensure,
        ready
    } as const;
}
