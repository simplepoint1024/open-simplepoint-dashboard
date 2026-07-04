import SimpleTable from "@simplepoint/components/SimpleTable";
import api from '@/api/index';
import React, {lazy, Suspense, useCallback, useEffect, useState} from 'react';
import {Drawer, Spin} from "antd";
import {useI18n} from '@simplepoint/shared/hooks/useI18n';

const ResourceConfig = lazy(() => import('./config/resource'));

// 获取基础表格配置
const baseConfig = api['rbac-roles'];
const resourceConfigNamespaces = [...baseConfig.i18nNamespaces, 'data-scopes', 'field-scopes', 'table', 'common'];

const getDefaultDrawerHeight = () => {
    if (typeof window === 'undefined') return 620;
    return Math.min(Math.max(560, Math.round(window.innerHeight * 0.72)), window.innerHeight - 64);
};

const App = () => {
    const [openRoleConfig, setOpenRoleConfig] = useState(false);
    const [roleId, setRoleId] = useState<string>('');
    // 支持拖拽高度
    const [drawerHeight, setDrawerHeight] = useState<number>(() => getDefaultDrawerHeight());

    // 国际化
    const {t, ensure, locale} = useI18n();
    // 确保本页所需命名空间加载（roles），语言切换后也会自动增量加载
    useEffect(() => {
        void ensure(resourceConfigNamespaces);
    }, [ensure, locale]);

    // 关闭时重置高度，避免下次打开异常
    useEffect(() => {
        if (!openRoleConfig) {
            setDrawerHeight(getDefaultDrawerHeight());
        }
    }, [openRoleConfig]);

    // 拖拽句柄事件（bottom 抽屉，向上拖动增加高度）
    const startResize = useCallback((e: React.MouseEvent) => {
        e.preventDefault();
        const startY = e.clientY;
        const startHeight = drawerHeight;
        const minHeight = 420;
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

    // 自定义按钮事件
    const customButtonEvents = {
        'config.resource': (_keys: React.Key[], rows: any[]) => {
            if (!rows[0]?.id) return;
            setOpenRoleConfig(true);
            setRoleId(rows[0].id);
        },
    };

    return (
        <div>
            <SimpleTable
                {...baseConfig}
                customButtonEvents={customButtonEvents}
                beforeSubmit={({formData}) => {
                    const next = {...formData};
                    ['name', 'authority', 'description'].forEach((key) => {
                        if (typeof next[key] === 'string') {
                            next[key] = next[key].trim();
                        }
                    });
                    return next;
                }}
                submitRefreshTargets={{page: true, schema: false}}
                deleteRefreshTargets={{page: true, schema: false}}
            />
            <Drawer
                className="role-resource-drawer"
                title={t("roles.config.resource", "资源授权")}
                open={openRoleConfig}
                onClose={() => {
                    setOpenRoleConfig(false);
                    setRoleId('');
                }}
                placement={"bottom"}
                maskClosable={false}
                // width 对 bottom 抽屉无效，使用 height 控制高度
                height={drawerHeight}
                destroyOnHidden
                styles={{body: {position: 'relative', padding: 0}}}
            >
                {/* 顶部拖拽条 */}
                <div
                    style={{
                        position: 'absolute',
                        top: 0,
                        left: 0,
                        right: 0,
                        height: 8,
                        cursor: 'ns-resize',
                        zIndex: 10
                    }}
                    onMouseDown={startResize}
                />
                {/* 不使用 key 强制重建，避免闪退；组件内部通过 useEffect([roleId]) 重置状态 */}
                {openRoleConfig && roleId ? (
                    <Suspense fallback={<div style={{display: 'flex', justifyContent: 'center', padding: 24}}><Spin/></div>}>
                        <ResourceConfig roleId={roleId}/>
                    </Suspense>
                ) : null}
            </Drawer>
        </div>
    );
};

export default App;
