// src/hooks/useLocaleLoader.ts
import {useEffect, useState} from 'react';
import dayjs from 'dayjs';
import zhCN from 'antd/locale/zh_CN';
import {antdLocaleMapping, dayjsLocaleMapping} from '@/i18n/locale';

export function useLocaleLoader(locale: string) {
    const [currentLocale, setCurrentLocale] = useState(zhCN);

    useEffect(() => {
        (async () => {
            // 加载 dayjs locale
            try {
                await import(`dayjs/locale/${dayjsLocaleMapping[locale]}`);
                dayjs.locale(locale);
            } catch {
                dayjs.locale('en');
            }

            // 加载 antd locale
            try {
                const mod = await import(`antd/locale/${antdLocaleMapping[locale]}`);
                setCurrentLocale(mod.default);
            } catch {
                setCurrentLocale(zhCN);
            }
        })();
    }, [locale]);

    return currentLocale;
}
