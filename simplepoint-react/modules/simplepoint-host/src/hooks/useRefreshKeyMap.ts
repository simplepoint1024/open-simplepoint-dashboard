import {useEffect, useState} from "react";

/**
 * 监听 sp-refresh-route 事件，返回一个路径到刷新次数的映射表
 */
export function useRefreshKeyMap() {
    const [map, setMap] = useState<Record<string, number>>({});

    useEffect(() => {
        const handler = (e: any) => {
            const path =
                e?.detail?.path ||
                window.location.hash.replace(/^#/, '') ||
                window.location.pathname ||
                '/';

            setMap(prev => ({
                ...prev,
                [path]: (prev[path] || 0) + 1
            }));
        };

        window.addEventListener('sp-refresh-route', handler);
        return () => window.removeEventListener('sp-refresh-route', handler);
    }, []);

    return map;
}
