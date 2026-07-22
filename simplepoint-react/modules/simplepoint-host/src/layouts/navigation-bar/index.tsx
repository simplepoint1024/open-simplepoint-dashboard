import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {
  CloseCircleOutlined,
  DeleteOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  ReloadOutlined,
  VerticalLeftOutlined,
  VerticalRightOutlined
} from '@ant-design/icons';
import {Breadcrumb, Button, Dropdown, Layout, Menu, Skeleton, Tabs} from 'antd';
import type {TabsProps} from 'antd';
import {DndContext, PointerSensor, useSensor, closestCenter} from '@dnd-kit/core';
import type {DragEndEvent} from '@dnd-kit/core';
import {SortableContext, horizontalListSortingStrategy, useSortable, arrayMove} from '@dnd-kit/sortable';
import {CSS} from '@dnd-kit/utilities';
import {createIcon} from '@simplepoint/shared/types/icon.ts';
import {useSideNavigation} from "@/hooks/routes";
import {useLocation, useNavigate} from "react-router";
import {findRouteChainByPath, flattenRoutes, getRouteKey, RouteInfo} from "@/store/routes";
import {aboutMeItem, HeaderLogo, HeaderSearchBar, RoleSwitcherTop, TenantSwitcherTop, toolsSwitcherGroupItem} from "@/layouts/navigation-bar/top-bar.tsx";
import {useI18n} from "@/layouts/i18n/useI18n.ts";
import MenuSearchModal from "@/layouts/navigation-bar/menu-search-modal.tsx";
import {useCurrentTenantProfile, useCurrentTenants} from '@/fetches/tenants.ts';
import {getTenantId} from '@/store/tenant.ts';

const {Header, Content, Footer, Sider} = Layout;

interface DraggableTabNodeProps extends React.HTMLAttributes<HTMLDivElement> {
  'data-node-key': string;
}

const DraggableTabNode: React.FC<DraggableTabNodeProps> = ({className, ...props}) => {
  const {attributes, listeners, setNodeRef, transform, transition, isDragging} = useSortable({
    id: props['data-node-key'],
  });
  const style: React.CSSProperties = {
    ...props.style,
    transform: CSS.Translate.toString(transform),
    transition,
    zIndex: isDragging ? 1 : 0,
    cursor: isDragging ? 'grabbing' : 'grab',
  };
  return <div ref={setNodeRef} style={style} {...attributes} {...listeners} {...props} className={className} />;
};

const NavigateBar: React.FC<{ children?: React.ReactElement, data: Array<RouteInfo> }> = ({children, data}) => {
  const [collapsed, setCollapsed] = useState(() => window.innerWidth < 768);
  const navigate = useNavigate();
  const location = useLocation();
  const {t} = useI18n();
  const [activeTenantId, setActiveTenantId] = useState<string | undefined>(() => getTenantId());
  const {data: currentTenants} = useCurrentTenants();
  const activeTenant = currentTenants?.find(tenant => tenant.tenantId === activeTenantId);
  const tenantMenuEnabled = !!activeTenant && activeTenant.tenantType !== 'PLATFORM';
  const {data: currentTenantProfile} = useCurrentTenantProfile(activeTenantId, tenantMenuEnabled);

  useEffect(() => {
    const handler = (event: Event) => {
      setActiveTenantId((event as CustomEvent<string | undefined>).detail ?? getTenantId());
    };
    window.addEventListener('sp-set-tenant', handler as EventListener);
    return () => window.removeEventListener('sp-set-tenant', handler as EventListener);
  }, []);

  // 侧边栏宽度拖动调整，按用户隔离持久化
  const SIDER_MIN = 140;
  const SIDER_MAX = 480;
  const SIDER_DEFAULT = 180;
  const getSiderStorageKey = () => {
    try {
      const raw = sessionStorage.getItem('sp.userinfo');
      if (raw) {
        const info = JSON.parse(raw) as Record<string, unknown>;
        const id = info?.sub ?? info?.id ?? info?.username ?? info?.preferred_username;
        if (id != null) return `sp.sider.width.${String(id)}`;
      }
    } catch { /* ignore */ }
    return 'sp.sider.width.anonymous';
  };
  const [siderWidth, setSiderWidth] = useState<number>(() => {
    try {
      const v = Number(localStorage.getItem(getSiderStorageKey()));
      if (v >= SIDER_MIN && v <= SIDER_MAX) return v;
    } catch { /* ignore */ }
    return SIDER_DEFAULT;
  });
  const isResizingRef = useRef(false);
  const startXRef = useRef(0);
  const startWidthRef = useRef(siderWidth);

  const onResizeHandleMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    isResizingRef.current = true;
    startXRef.current = e.clientX;
    startWidthRef.current = siderWidth;

    const onMouseMove = (ev: MouseEvent) => {
      if (!isResizingRef.current) return;
      const next = Math.min(SIDER_MAX, Math.max(SIDER_MIN, startWidthRef.current + ev.clientX - startXRef.current));
      setSiderWidth(next);
    };
    const onMouseUp = (ev: MouseEvent) => {
      if (!isResizingRef.current) return;
      isResizingRef.current = false;
      const final = Math.min(SIDER_MAX, Math.max(SIDER_MIN, startWidthRef.current + ev.clientX - startXRef.current));
      setSiderWidth(final);
      try { localStorage.setItem(getSiderStorageKey(), String(final)); } catch { /* ignore */ }
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
  }, [siderWidth]);

  // 监听全局主题模式，驱动侧边菜单明暗样式
  const [themeMode, setThemeMode] = useState<'light' | 'dark'>(() => (localStorage.getItem('sp.theme') as 'light' | 'dark') || 'light');
  useEffect(() => {
    const handler = (e: Event) => setThemeMode(((e as CustomEvent<string>).detail as 'light' | 'dark') || 'light');
    window.addEventListener('sp-set-theme', handler);
    return () => window.removeEventListener('sp-set-theme', handler);
  }, []);

  // Ctrl+K 菜单搜索
  const [searchOpen, setSearchOpen] = useState(false);
  useEffect(() => {
    const onKeydown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        setSearchOpen(prev => !prev);
      }
    };
    const onOpenSearch = () => setSearchOpen(true);
    window.addEventListener('keydown', onKeydown);
    window.addEventListener('sp-open-search', onOpenSearch);
    return () => {
      window.removeEventListener('keydown', onKeydown);
      window.removeEventListener('sp-open-search', onOpenSearch);
    };
  }, []);

  const STORAGE_KEY = 'sp.nav.tabs';
  const DASHBOARD_PATH = '/dashboard';
  // 统一拍平叶子菜单，供后续映射复用
  const leafNodes = useMemo(() => flattenRoutes(data || []), [data]);

  // 补充：对未在菜单中的内部路由，提供固定的名称与图标
  const extraTabs = useMemo(() => ([
    { path: '/profile', label: t('menu.profile', '个人资料'), icon: 'UserOutlined' },
    { path: '/settings', label: t('menu.settings', '系统设置'), icon: 'SettingOutlined' },
    { path: '/tenant', label: t('menu.tenantHome', '租户主页'), icon: 'HomeOutlined' },
  ]), [t]);

  // 根据菜单构建 path -> label 映射（复用 leafNodes + extras）
  const pathLabelMap = useMemo(() => {
    const map = new Map<string, string>();
    leafNodes.forEach((n) => {
      if (n.path) map.set(n.path, t(n.title || '', n.label || n.title || n.path!));
    });
    extraTabs.forEach(it => map.set(it.path, it.label));
    return map;
  }, [leafNodes, extraTabs, t]);

  // 构建 path -> 图标 的映射（复用 leafNodes + extras）
  const pathIconMap = useMemo(() => {
    const map = new Map<string, React.ReactNode>();
    leafNodes.forEach((n) => {
      if (n?.path && n?.icon) {
        map.set(n.path, createIcon(n.icon));
      }
    });
    extraTabs.forEach(it => map.set(it.path, createIcon(it.icon)));
    return map;
  }, [leafNodes, extraTabs]);

  // 构建 path -> 图标名 的映射（用于持久化）（复用 leafNodes + extras）
  const pathIconNameMap = useMemo(() => {
    const map = new Map<string, string>();
    leafNodes.forEach((n) => {
      if (n?.path && typeof n?.icon === 'string') {
        map.set(n.path, n.icon);
      }
    });
    extraTabs.forEach(it => map.set(it.path, it.icon));
    return map;
  }, [leafNodes, extraTabs]);

  // 从本地存储读取上次的标签文本，作为名称降级来源
  const storedLabelMap = useMemo(() => {
    const map = new Map<string, string>();
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        const parsed = JSON.parse(raw) as Array<{ key: string; label: string; icon?: string }>;
        if (Array.isArray(parsed)) {
          parsed.forEach(it => {
            if (it?.key) map.set(it.key, it.label || it.key);
          });
        }
      }
    } catch (e) { console.warn('[nav] Failed to read stored tab labels:', e);
    }
    return map;
  }, []);

  // 从本地存储读取上次的图标名，作为图标降级来源
  const storedIconMap = useMemo(() => {
    const map = new Map<string, string>();
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        const parsed = JSON.parse(raw) as Array<{ key: string; icon?: string }>;
        if (Array.isArray(parsed)) {
          parsed.forEach(it => {
            if (it?.key && it?.icon) map.set(it.key, it.icon);
          });
        }
      }
    } catch (e) { console.warn('[nav] Failed to read stored tab icons:', e);
    }
    return map;
  }, []);

  // 生成包含图标的标签
  const getTabLabel = useCallback((path: string): React.ReactNode => {
    const icon = pathIconMap.get(path) ?? (storedIconMap.get(path) ? createIcon(storedIconMap.get(path)!) : undefined);
    const text = pathLabelMap.get(path) ?? storedLabelMap.get(path) ?? path;
    return (
      <span className="nb-tab-label">
        {icon ? <span className="anticon nb-tab-label-icon">{icon}</span> : null}
        <span>{text}</span>
      </span>
    );
  }, [pathIconMap, pathLabelMap, storedLabelMap, storedIconMap]);

  const getDashboardTab = useCallback(() => ({
    key: DASHBOARD_PATH,
    label: getTabLabel(DASHBOARD_PATH),
    closable: false
  }), [getTabLabel]);

  // 统一持久化 tabs 到本地存储（debounced to reduce writes during rapid navigation）
  const persistTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const persistTabs = useCallback((arr: Array<{ key: string; label: React.ReactNode; closable?: boolean }>) => {
    clearTimeout(persistTimerRef.current);
    persistTimerRef.current = setTimeout(() => {
      try {
        const simple = arr.map(t => {
          const labelText = (
            pathLabelMap.get(t.key) ??
            storedLabelMap.get(t.key) ??
            (typeof t.label === 'string' ? t.label : undefined) ??
            t.key
          );
          const iconName = pathIconNameMap.get(t.key) ?? storedIconMap.get(t.key);
          return {
            key: t.key,
            label: labelText,
            icon: iconName,
            closable: t.closable !== false
          };
        });
        localStorage.setItem(STORAGE_KEY, JSON.stringify(simple));
      } catch (e) { console.warn('[nav] Failed to persist tabs:', e); }
    }, 300);
  }, [pathLabelMap, storedLabelMap, pathIconNameMap, storedIconMap]);
  useEffect(() => () => clearTimeout(persistTimerRef.current), []);

  // 规范化 tabs：去重、dashboard 固定在首位且不可关闭
  const normalizeTabs = useCallback((input: Array<{ key: string; label: React.ReactNode; closable?: boolean }>) => {
    const seen = new Set<string>();
    const out: Array<{ key: string; label: React.ReactNode; closable?: boolean }> = [];
    input.forEach(t => {
      if (!seen.has(t.key)) {
        seen.add(t.key);
        out.push(t);
      }
    });
    const dashboardTab = getDashboardTab();
    const filtered = out.filter(t => t.key !== DASHBOARD_PATH);
    return [dashboardTab, ...filtered];
  }, [DASHBOARD_PATH, getDashboardTab]);

  // 统一获取当前路由（HashRouter 优先 hash）
  const getCurrentPath = useCallback(() => {
    const rawHash = typeof window !== 'undefined' ? window.location.hash : '';
    const fromHash = rawHash ? decodeURI(rawHash.replace(/^#/, '')) : undefined;
    return fromHash || location.pathname || '/';
  }, [location.pathname]);

  // 页签状态：key 使用路由 path
  const [tabs, setTabs] = useState<Array<{ key: string; label: React.ReactNode; closable?: boolean }>>(() => {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        const parsed = JSON.parse(raw) as Array<{ key: string; label: string; closable?: boolean }>;
        if (Array.isArray(parsed)) {
          const base = parsed.map(t => ({key: t.key, label: getTabLabel(t.key), closable: t.closable ?? true}));
          return normalizeTabs(base);
        }
      }
    } catch (e) { console.warn('[nav] Failed to restore tabs from storage:', e);
    }
    return normalizeTabs([getDashboardTab()]);
  });

  // 首次加载：如果当前 URL 不在持久化的 tabs 中，不再将其加入，而是跳到第一个（dashboard）
  const initialSynced = useRef(false);
  useEffect(() => {
    if (initialSynced.current) return;
    const current = getCurrentPath();
    const exists = tabs.some(t => t.key === current);
    if (!exists) {
      const target = tabs[0]?.key || DASHBOARD_PATH;
      if (target && target !== current) navigate(target, {replace: true});
    }
    initialSynced.current = true;
  }, [tabs, getCurrentPath, navigate]);

  // 菜单变化时，用最新映射更新已有标签文字，并保持 dashboard 固定首位
  useEffect(() => {
    setTabs(prev => {
      const next = normalizeTabs(prev.map(t => ({...t, label: getTabLabel(t.key)})));
      persistTabs(next);
      return next;
    });
  }, [getTabLabel, normalizeTabs, persistTabs]);

  // 路由变化时，自动把当前路由加入页签，并持久化
  useEffect(() => {
    const path = getCurrentPath();
    if (!path) return;

    setTabs(prev => {
      // 首次加载且当前路径不在 tabs 中：不新增，等待上面的初始跳转
      if (!initialSynced.current && !prev.some(t => t.key === path)) {
        return prev;
      }
      const exists = prev.some(t => t.key === path);
      const next = exists ? prev : [...prev, {key: path, label: getTabLabel(path), closable: path !== DASHBOARD_PATH}];
      const normalized = normalizeTabs(next);
      persistTabs(normalized);
      return normalized;
    });
  }, [getCurrentPath, getTabLabel, normalizeTabs, persistTabs]);

  const activeKey = getCurrentPath();
  const activeMenuChain = useMemo(() => findRouteChainByPath(data || [], activeKey), [data, activeKey]);

  // 面包屑 items：放在 Header Logo 后面，只显示菜单路径链（无首页图标）
  const breadcrumbItems = useMemo(() => {
    if (!activeMenuChain || activeMenuChain.length === 0) return [];
    return activeMenuChain.map(menu => ({
      title: t(menu.title || '', menu.label || menu.title || ''),
    }));
  }, [activeMenuChain, t]);

  // Tab 拖拽排序
  const tabDndSensor = useSensor(PointerSensor, {activationConstraint: {distance: 8}});
  const handleTabDragEnd = useCallback(({active, over}: DragEndEvent) => {
    if (!over || active.id === over.id) return;
    setTabs(prev => {
      const oldIdx = prev.findIndex(t => t.key === String(active.id));
      const newIdx = prev.findIndex(t => t.key === String(over.id));
      if (oldIdx < 0 || newIdx < 0) return prev;
      // dashboard 固定在首位，不允许拖到其前面
      if (newIdx === 0) return prev;
      const next = arrayMove(prev, oldIdx, newIdx);
      persistTabs(next);
      return next;
    });
  }, [persistTabs]);

  const renderTabBar: TabsProps['renderTabBar'] = useCallback(
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (tabBarProps: any, DefaultTabBar: any) => (
      <DndContext sensors={[tabDndSensor]} onDragEnd={handleTabDragEnd} collisionDetection={closestCenter}>
        <SortableContext items={tabs.map(t => t.key)} strategy={horizontalListSortingStrategy}>
          <DefaultTabBar {...tabBarProps}>
            {(node: React.ReactElement) => (
              <DraggableTabNode {...(node.props as DraggableTabNodeProps)} key={node.key ?? undefined} />
            )}
          </DefaultTabBar>
        </SortableContext>
      </DndContext>
    ),
    [tabDndSensor, handleTabDragEnd, tabs]
  );
  const selectedMenuKeys = useMemo(() => {
    const current = activeMenuChain[activeMenuChain.length - 1];
    const key = getRouteKey(current);
    return key ? [key] : [];
  }, [activeMenuChain]);

  // 侧边菜单 items（must be declared before onMenuOpenChange which references it）
  const sideMenuItems = useMemo(() => useSideNavigation(navigate, data).items, [navigate, data]);

  // Menu open keys: fully user-controlled after initial sync from active route
  const [openMenuKeys, setOpenMenuKeys] = useState<string[]>([]);
  const lastSyncedPath = useRef<string>('');

  // When active route changes (tab switch, navigation), sync open keys to match new route
  useEffect(() => {
    if (activeKey !== lastSyncedPath.current) {
      lastSyncedPath.current = activeKey;
      const chainKeys = activeMenuChain.slice(0, -1).map(menu => getRouteKey(menu)).filter((k): k is string => !!k);
      setOpenMenuKeys(chainKeys);
    }
  }, [activeKey, activeMenuChain]);

  const onMenuOpenChange = useCallback((keys: string[]) => {
    // Accordion: when a new top-level key is opened, close other top-level keys
    const topLevelKeys = (sideMenuItems ?? []).map((item) => item?.key).filter((k): k is string => !!k);
    const prevTopLevel = openMenuKeys.filter(k => topLevelKeys.includes(k));
    const newTopLevel = keys.filter(k => topLevelKeys.includes(k) && !prevTopLevel.includes(k));
    if (newTopLevel.length > 0) {
      const keepTopLevel = newTopLevel[newTopLevel.length - 1];
      setOpenMenuKeys(keys.filter(k => !topLevelKeys.includes(k) || k === keepTopLevel));
    } else {
      setOpenMenuKeys(keys);
    }
  }, [sideMenuItems, openMenuKeys]);

  const onTabChange = useCallback((key: string) => {
    if (key) {
      navigate(key);
    }
  }, [navigate]);

  const onTabEdit = useCallback((targetKey: React.MouseEvent | React.KeyboardEvent | string, action: 'add' | 'remove') => {
    if (action !== 'remove') return;
    // 基于当前 tabs 计算下一状态与应跳转的 fallbackKey，避免在 setState 回调内直接 navigate
    const prevTabs = tabs;
    const idx = prevTabs.findIndex(t => t.key === targetKey);
    if (idx === -1) return;
    const nextTabs = prevTabs.filter(t => t.key !== targetKey);
    const fallbackKey = targetKey === activeKey
      ? (nextTabs[idx - 1]?.key || nextTabs[idx]?.key || nextTabs[0]?.key || DASHBOARD_PATH)
      : undefined;
    const normalized = normalizeTabs(nextTabs);
    setTabs(normalized);
    persistTabs(normalized);
    if (fallbackKey) {
      // 延后到提交后再跳转，避免在渲染其他组件时更新 HashRouter
      window.setTimeout(() => navigate(fallbackKey), 0);
    }
  }, [tabs, activeKey, navigate, normalizeTabs, persistTabs]);

  // 右键菜单：清除缓存 / 关闭全部（保留 dashboard）

  const onContextMenuClick = useCallback(({key}: { key: string }) => {
    if (key === 'refresh') {
      const path = getCurrentPath();
      try {
        // 异步派发，避免同步事件引起的链式更新
        window.setTimeout(() => {
          try { window.dispatchEvent(new CustomEvent('sp-refresh-route', {detail: {path}})); } catch {}
        }, 0);
      } catch (_) {
      }
      return;
    }
    if (key === 'closeLeft' || key === 'closeRight') {
      setTabs(prev => {
        const current = getCurrentPath();
        const idx = prev.findIndex(t => t.key === current);
        if (idx === -1) return prev;
        const filtered = prev.filter((t, i) => {
          if (t.key === current) return true; // always keep current active
          if (t.closable === false) return true; // respect non-closable (dashboard)
          return key === 'closeLeft' ? i >= idx : i <= idx;
        });
        const normalized = normalizeTabs(filtered);
        persistTabs(normalized);
        return normalized;
      });
      return;
    }
    if (key === 'clear' || key === 'closeAll') {
      try {
        localStorage.removeItem(STORAGE_KEY);
      } catch (e) {
        console.warn('[nav] Failed to clear tab storage:', e);
      }
      const onlyDashboard = [getDashboardTab()];
      setTabs(onlyDashboard);
      persistTabs(onlyDashboard);
      // 延后跳转，避免在当前渲染过程中触发 HashRouter 同步更新
      window.setTimeout(() => navigate(DASHBOARD_PATH), 0);
    }
  }, [getCurrentPath, normalizeTabs, persistTabs, getDashboardTab, navigate]);

  const contextMenu = useMemo(() => ({
    items: [
      {key: 'refresh', label: t('nav.refresh', '刷新当前页'), icon: <ReloadOutlined/>},
      {key: 'closeLeft', label: t('nav.closeLeft', '关闭左侧全部'), icon: <VerticalLeftOutlined/>},
      {key: 'closeRight', label: t('nav.closeRight', '关闭右侧全部'), icon: <VerticalRightOutlined/>},
      {type: 'divider' as const},
      {key: 'clear', label: t('nav.clear', '清除缓存'), icon: <DeleteOutlined/>},
      {key: 'closeAll', label: t('nav.closeAll', '关闭全部'), icon: <CloseCircleOutlined/>},
    ],
    onClick: onContextMenuClick,
  }), [onContextMenuClick, t]);

  // 顶部菜单 items 缓存（右：工具+头像）
  const topRightItems = useMemo(() => [
    toolsSwitcherGroupItem(),
    aboutMeItem(navigate, {
      enabled: tenantMenuEnabled,
      editable: currentTenantProfile?.profileEditable === true,
    }),
  ], [currentTenantProfile?.profileEditable, navigate, tenantMenuEnabled]);

  return (
    <Layout className={`nb-root ${themeMode === 'dark' ? 'theme-dark' : 'theme-light'}`} style={{ minHeight: '100vh' }}>
      <Header className="nb-header">
        {/* 左：Logo（独立组件，不受 AntD Menu overflow 检测影响）*/}
        <HeaderLogo navigate={navigate} />
        {/* 全局工作空间上下文紧邻品牌区，与搜索和个人工具保持分离 */}
        <div className="nb-header-context">
          <TenantSwitcherTop />
          <RoleSwitcherTop />
        </div>
        {/* Logo 后面的面包屑，显示当前菜单路径 */}
        {breadcrumbItems.length > 0 && (
          <div className="nb-header-breadcrumb">
            <Breadcrumb items={breadcrumbItems} />
          </div>
        )}
        {/* 中：搜索条，flex: 1 自动撑开 */}
        <div className="nb-header-search">
          <HeaderSearchBar onOpen={() => setSearchOpen(true)} />
        </div>
        {/* 右：工具组 + 头像 */}
        <Menu
          mode="horizontal"
          items={topRightItems}
          className="nb-top-menu nb-top-menu-right"
        />
      </Header>
      <Layout style={{ flex: 1, minHeight: 0, overflow: 'hidden' }}>
        <Sider width={collapsed ? 80 : siderWidth} trigger={null} collapsible collapsed={collapsed} style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden', position: 'relative' }}>
          {(!sideMenuItems || sideMenuItems.length === 0) ? (
            <div style={{ padding: collapsed ? 8 : 16 }}>
              <Skeleton active paragraph={{ rows: 6 }} title={false} />
            </div>
          ) : (
            <Menu
              mode="inline"
              theme={themeMode}
              className="nb-sider-menu"
              items={sideMenuItems}
              selectedKeys={selectedMenuKeys}
              openKeys={collapsed ? undefined : openMenuKeys}
              onOpenChange={(keys) => onMenuOpenChange(keys as string[])}
            />
          )}
          <Button
            type="text"
            icon={collapsed ? <MenuUnfoldOutlined/> : <MenuFoldOutlined/>}
            onClick={() => setCollapsed(!collapsed)}
            className="nb-sider-toggle"
            style={collapsed ? {justifyContent: 'center', padding: '0'} : undefined}
          >
            {!collapsed && <span>{t('nav.collapse', '收起')}</span>}
          </Button>
          {!collapsed && (
            <div className="nb-sider-resize-handle" onMouseDown={onResizeHandleMouseDown} />
          )}
        </Sider>
        <Layout className="nb-inner-layout">
          <Content className="nb-content-wrapper">
            <Dropdown trigger={["contextMenu"]} menu={contextMenu as any}>
              <div className="nb-tabs-bar">
                <Tabs
                  hideAdd
                  type="editable-card"
                  size="small"
                  items={tabs}
                  activeKey={activeKey}
                  onChange={onTabChange}
                  onEdit={onTabEdit as any}
                  tabBarGutter={6}
                  renderTabBar={renderTabBar}
                />
              </div>
            </Dropdown>
            <div className="nb-inner-content">
              {children}
            </div>
          </Content>
          <Footer className="nb-footer">
            Simplepoint ©{new Date().getFullYear()} Created by Ymsl UED
          </Footer>
        </Layout>
      </Layout>
      <MenuSearchModal
        open={searchOpen}
        onClose={() => setSearchOpen(false)}
        menus={data}
        onNavigate={navigate}
        t={t}
      />
    </Layout>
  );
};

export default NavigateBar;
