import '@/App.css';
import '@simplepoint/components/Simplepoint.css';
import 'antd/dist/reset.css';

import React, {useEffect, useMemo, useRef, useState} from 'react';
import {HashRouter, Routes} from 'react-router';
import {App as AntApp, Button, ConfigProvider, Modal, Result, Table as AntTable, theme} from 'antd';
import {QuestionCircleOutlined} from '@ant-design/icons';

import NavigateBar from '@/layouts/navigation-bar';

import {useI18n} from '@/layouts/i18n/useI18n';
import {isHttpError} from '@simplepoint/shared/api/client';
import {useData} from '@simplepoint/shared/api/methods';
import {fetchServiceRoutes, ServiceResourceRouteResult} from '@/fetches/routes';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useCurrentTenants} from '@/fetches/tenants';
import {getTenantId, setTenantId} from '@/store/tenant';
import {getRoleId, setRoleId} from '@/store/role';
import {setContextId} from '@/store/contextId';
import {ensureContextId} from '@simplepoint/shared/api/contextId';
import {registerFeedbackBridge} from '@simplepoint/shared/api/feedbackBridge';

import {useLocaleLoader} from '@/hooks/useLocaleLoader';
import {useRegisterRemotes} from '@/hooks/useRegisterRemotes';
import {useLeafRoutes} from '@/hooks/useLeafRoutes';
import {useRefreshKeyMap} from '@/hooks/useRefreshKeyMap';
import {useGlobalLoading} from '@/hooks/useGlobalLoading';
import {useGlobalSize} from '@/hooks/useGlobalSize';
import {useThemeMode} from '@/hooks/useThemeMode';

import {GlobalLoading} from '@/components/GlobalLoading';
import {TitleSync} from '@/components/TitleSync';
import {renderRoutes} from "@/components/RouteRenderer.tsx";

export type RuntimeScopeContext = {
    scopeType?: string;
    actorRole?: string;
    tenantId?: string;
    userId?: string;
};

const RUNTIME_SCOPE_EVENT = 'sp-runtime-scope';

const FeedbackBridgeRegistrar: React.FC = () => {
    const {message, modal} = AntApp.useApp();

    useEffect(() => registerFeedbackBridge({message, modal}), [message, modal]);

    return null;
};

const App: React.FC = () => {
    const {globalSize} = useGlobalSize();
    const {resolvedTheme} = useThemeMode();
    const {t, locale, ready: i18nReady, loading: i18nLoading} = useI18n();
    const currentLocale = useLocaleLoader(locale);

    const [shortcutsOpen, setShortcutsOpen] = useState(false);

    useEffect(() => {
        const handler = (e: KeyboardEvent) => {
            if (e.key === '?' && !e.ctrlKey && !e.metaKey && !(e.target instanceof HTMLInputElement) && !(e.target instanceof HTMLTextAreaElement)) {
                setShortcutsOpen(prev => !prev);
            }
        };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, []);

    useEffect(() => {
        const handler = () => setShortcutsOpen(prev => !prev);
        window.addEventListener('sp-open-shortcuts', handler);
        return () => window.removeEventListener('sp-open-shortcuts', handler);
    }, []);

    const shortcuts = useMemo(() => [
        {key: 'Ctrl + K', desc: t('shortcuts.openMenuSearch', '打开菜单搜索')},
        {key: '?', desc: t('shortcuts.toggleHelp', '显示/隐藏快捷键')},
        {key: 'Ctrl + B', desc: t('shortcuts.toggleSidebar', '折叠/展开侧边栏（预留）')},
        {key: 'Ctrl + D', desc: t('shortcuts.toggleTheme', '切换深色/浅色模式（预留）')},
        {key: 'Ctrl + W', desc: t('shortcuts.closeCurrentTab', '关闭当前标签页（预留）')},
        {key: 'F11', desc: t('shortcuts.toggleFullscreen', '全屏/退出全屏')},
        {key: 'Alt + ←', desc: t('shortcuts.browserBack', '返回上一页（浏览器）')},
    ], [t]);

    // 1) 租户优先：先取已存租户；没有则拉取 currentTenants 选第一个
    const [tenantId, setTenantIdState] = useState<string | undefined>(() => getTenantId());
    const [roleId, setRoleIdState] = useState<string | undefined>(() => getRoleId(getTenantId()));
    const [contextReady, setContextReady] = useState(false);
    const queryScope = useQueryScope();
    const [scopeTenantId, scopeRoleId, scopeContextId] = queryScope;
    const contextId = scopeContextId ?? undefined;
    const contextRequestSeq = useRef(0);
    const {
        data: currentTenants,
        isLoading: tenantsLoading,
        isError: tenantsFailed,
        error: tenantsError,
        refetch: refetchTenants,
    } = useCurrentTenants();
    const selectedTenantExists = useMemo(() => {
        if (!tenantId || !currentTenants) return false;
        return currentTenants.some((tenant) => tenant.tenantId === tenantId);
    }, [tenantId, currentTenants]);

    // 当前选中租户的类型（用于路由鉴权：PERSONAL 租户访问 requireOrgTenant 路由时显示错误页）
    const currentTenantType = useMemo(() => {
        if (!tenantId || !currentTenants) return undefined;
        return currentTenants.find(t => t.tenantId === tenantId)?.tenantType;
    }, [tenantId, currentTenants]);

    useEffect(() => {
        // Use the same active storage snapshot as query keys, including changes
        // made in another tab. Ignore event payloads for inactive workspaces.
        setTenantIdState(scopeTenantId ?? undefined);
        setRoleIdState(scopeRoleId ?? undefined);
        setContextReady(false);
    }, [scopeTenantId, scopeRoleId]);

    useEffect(() => {
        if (!currentTenants) return;

        if (selectedTenantExists) return;

        if (tenantId) {
            setContextId(undefined, tenantId);
            setRoleId(undefined, tenantId);
        }

        const first = currentTenants[0]?.tenantId;
        if (first) {
            setTenantId(first);
            setTenantIdState(first);
            return;
        }

        if (tenantId) {
            setTenantId(undefined);
            setTenantIdState(undefined);
            setContextId(undefined);
        }
    }, [tenantId, currentTenants, selectedTenantExists]);

    // 2) 上下文其次：tenant 确定后，优先加载/刷新 contextId
    useEffect(() => {
        let cancelled = false;
        const requestSeq = ++contextRequestSeq.current;
        const activeTenantId = selectedTenantExists ? tenantId : undefined;
        const run = async () => {
            setContextReady(false);

            if (!activeTenantId) return;

            const activeRoleId = roleId;
            const ctxId = await ensureContextId(activeTenantId, {force: true, roleId: activeRoleId});
            if (cancelled || contextRequestSeq.current !== requestSeq) return;
            if (getTenantId() !== activeTenantId) return;

            const storedRoleId = getRoleId(activeTenantId);
            if ((storedRoleId ?? '') !== (activeRoleId ?? '')) {
                // 角色存储与 React 状态在同一次工作空间切换中可能短暂错位。
                // 主动同步状态并触发下一轮上下文加载，避免 contextReady 永久为 false。
                setRoleIdState(storedRoleId);
                return;
            }

            setContextId(ctxId, activeTenantId, activeRoleId);
            setContextReady(true);
        };
        void run();
        return () => {
            cancelled = true;
        };
    }, [tenantId, roleId, selectedTenantExists]);

    // 3) 路由/菜单最后：必须在 contextId ready 后再加载
    const routesEnabled = Boolean(selectedTenantExists && contextReady
        && (tenantId ?? null) === scopeTenantId
        && (roleId ?? null) === scopeRoleId);
    const {
        data: res,
        isLoading,
        isError: routesFailed,
        error: routesError,
        refetch: refetchRoutes,
    } = useData<ServiceResourceRouteResult>(
        useMemo(() => ['fetchServiceRoutes', tenantId, roleId, contextId] as const, [tenantId, roleId, contextId]),
        ({signal}) => {
            if (!routesEnabled) return Promise.resolve(undefined as any);
            return fetchServiceRoutes(signal);
        },
        {
            enabled: routesEnabled,
        } as any
    );

    // 远程模块注册
    const {remoteRegistryKey, remotesReady} = useRegisterRemotes(res, isLoading);

    useEffect(() => {
        const detail: RuntimeScopeContext = res?.authorizationContext || {};
        try {
            window.dispatchEvent(new CustomEvent(RUNTIME_SCOPE_EVENT, {detail}));
        } catch {}
    }, [res?.authorizationContext]);

    // 展平后的叶子路由
    const leafRoutes = useLeafRoutes(res?.routes);
    // 每个 path 对应的刷新 key
    const refreshKeyMap = useRefreshKeyMap();

    const bootstrapError = tenantsError ?? routesError;
    const redirectingToLogin = isHttpError(bootstrapError)
        && bootstrapError.status === 401
        && !bootstrapError.sessionActive;
    const bootstrapFailed = (tenantsFailed || routesFailed) && !redirectingToLogin;

    // 全局 loading 状态：租户、上下文、路由任一未就绪都保持 loading
    const showLoading = useGlobalLoading(
        i18nLoading,
        i18nReady,
        !bootstrapFailed && (isLoading || tenantsLoading || !routesEnabled || !remotesReady),
    );

    const retryBootstrap = () => {
        void refetchTenants();
        if (routesEnabled) {
            void refetchRoutes();
        }
    };

    return (
        <div className="content" style={{position: 'relative'}}>
            <GlobalLoading visible={showLoading} text={t('loading.resources', '正在加载资源...')}/>
            <ConfigProvider
                locale={currentLocale}
                componentSize={globalSize}
                theme={{
                    algorithm: resolvedTheme === 'dark' ? theme.darkAlgorithm : theme.defaultAlgorithm,
                    token: {
                        colorPrimary: '#1677FF',
                        borderRadius: 4,
                        colorLink: '#1677FF',
                        colorLinkHover: '#4096ff',
                        fontFamily: "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif",
                    },
                    components: {
                        Table: {
                            cellFontSize: 13,
                            cellPaddingBlock: 10,
                            cellPaddingInline: 12,
                            cellPaddingBlockSM: 6,
                            cellPaddingInlineSM: 8,
                            headerBorderRadius: 0,
                        },
                    }
                }}>
                <AntApp>
                    <FeedbackBridgeRegistrar/>
                    <HashRouter>
                        <TitleSync leafRoutes={leafRoutes} t={t}/>
                        {bootstrapFailed ? (
                            <div style={{minHeight: '100vh', display: 'grid', placeItems: 'center', padding: 24}}>
                                <Result
                                    status="error"
                                    title={t('error.resourcesLoadFailed', '工作空间资源加载失败')}
                                    subTitle={
                                        bootstrapError instanceof Error
                                            ? bootstrapError.message
                                            : t('error.requestFailed', '请求失败')
                                    }
                                    extra={(
                                        <Button type="primary" onClick={retryBootstrap}>
                                            {t('action.retry', '重试')}
                                        </Button>
                                    )}
                                />
                            </div>
                        ) : (
                            <NavigateBar data={res?.routes}>
                                <Routes key={JSON.stringify(queryScope)}>
                                    {routesEnabled && remotesReady ? renderRoutes(leafRoutes, refreshKeyMap, t, currentTenantType, remoteRegistryKey) : null}
                                </Routes>
                            </NavigateBar>
                        )}
                    </HashRouter>
                    <Modal
                        title={<><QuestionCircleOutlined style={{marginRight: 6}}/>{t('shortcuts.title', '快捷键')}</>}
                        open={shortcutsOpen}
                        onCancel={() => setShortcutsOpen(false)}
                        footer={null}
                        width={400}
                    >
                        <AntTable
                            dataSource={shortcuts}
                            pagination={false}
                            size="small"
                            rowKey="key"
                            columns={[
                                {title: t('shortcuts.column.key', '快捷键'), dataIndex: 'key', width: 160, render: (v: string) => <kbd style={{background:'rgba(0,0,0,0.06)',border:'1px solid rgba(0,0,0,0.12)',borderRadius:4,padding:'2px 8px',fontFamily:'monospace',fontSize:12}}>{v}</kbd>},
                                {title: t('shortcuts.column.description', '说明'), dataIndex: 'desc'},
                            ]}
                        />
                    </Modal>
                </AntApp>
            </ConfigProvider>
        </div>
    );
};

export default App;
