import SimpleTable from '@simplepoint/components/SimpleTable';
import api from '@/api/index';
import React, {lazy, Suspense, useCallback, useEffect, useState} from 'react';
import {Drawer, Spin} from 'antd';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';

const EntriesConfig = lazy(() => import('./config/entries'));

const baseConfig = api['rbac-field-scopes'];

const App = () => {
    const {t, ensure, locale} = useI18n();
    const [open, setOpen] = useState(false);
    const [selectedScope, setSelectedScope] = useState<{id: string; entries?: any[]} | null>(null);
    const [drawerHeight, setDrawerHeight] = useState(480);

    useEffect(() => {
        void ensure(baseConfig.i18nNamespaces);
    }, [ensure, locale]);

    useEffect(() => {
        if (!open) setDrawerHeight(480);
    }, [open]);

    const startResize = useCallback((e: React.MouseEvent) => {
        e.preventDefault();
        const startY = e.clientY;
        const startHeight = drawerHeight;
        const minHeight = 240;
        const maxHeight = Math.max(320, window.innerHeight - 80);
        const onMove = (me: MouseEvent) => {
            const delta = startY - me.clientY;
            let next = startHeight + delta;
            if (next < minHeight) next = minHeight;
            if (next > maxHeight) next = maxHeight;
            setDrawerHeight(next);
        };
        const onUp = () => {
            window.removeEventListener('mousemove', onMove);
            window.removeEventListener('mouseup', onUp);
        };
        window.addEventListener('mousemove', onMove);
        window.addEventListener('mouseup', onUp);
    }, [drawerHeight]);

    const customButtonEvents = {
        'config.entries': (_keys: React.Key[], rows: any[]) => {
            const row = rows?.[0];
            if (!row?.id) return;
            setSelectedScope({id: row.id, entries: row.entries ?? []});
            setOpen(true);
        },
    };

    return (
        <div>
            <SimpleTable
                {...baseConfig}
                customButtonEvents={customButtonEvents}
                submitRefreshTargets={{page: true, schema: false}}
                deleteRefreshTargets={{page: true, schema: false}}
            />
            <Drawer
                title={t('field-scopes.button.config.entries', '配置字段规则')}
                open={open}
                onClose={() => {setOpen(false); setSelectedScope(null);}}
                placement="bottom"
                height={drawerHeight}
                destroyOnHidden
                styles={{body: {position: 'relative', paddingTop: 12}}}
            >
                <div
                    style={{position: 'absolute', top: 0, left: 0, right: 0, height: 8, cursor: 'ns-resize', zIndex: 10}}
                    onMouseDown={startResize}
                />
                {open && selectedScope ? (
                    <Suspense fallback={<div style={{display: 'flex', justifyContent: 'center', padding: 24}}><Spin/></div>}>
                        <EntriesConfig
                            key={selectedScope.id}
                            fieldScopeId={selectedScope.id}
                            initialEntries={selectedScope.entries}
                        />
                    </Suspense>
                ) : null}
            </Drawer>
        </div>
    );
};

export default App;
