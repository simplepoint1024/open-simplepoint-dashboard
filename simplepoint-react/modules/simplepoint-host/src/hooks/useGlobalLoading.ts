// src/hooks/useGlobalLoading.ts
import {useEffect, useState} from 'react';

export function useGlobalLoading(i18nLoading: boolean, i18nReady: boolean, resLoading: boolean) {
    const [minHoldDone, setMinHoldDone] = useState(false);

    useEffect(() => {
        const timer = setTimeout(() => setMinHoldDone(true), 300);
        return () => clearTimeout(timer);
    }, []);

    const anyLoading = i18nLoading || !i18nReady || resLoading;
    return anyLoading || !minHoldDone;
}
