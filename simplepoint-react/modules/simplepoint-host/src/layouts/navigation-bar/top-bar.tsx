import type {ItemType} from "antd/es/menu/interface";
import {Avatar, Badge, Button, Drawer, Dropdown, List, MenuProps, Tooltip, Popconfirm, Tag, message} from "antd";
import {BellOutlined, CreditCardOutlined, FontSizeOutlined, GithubOutlined, GlobalOutlined, LogoutOutlined, QuestionCircleOutlined, SearchOutlined, SettingOutlined, UserOutlined, MoonOutlined, SunOutlined, DesktopOutlined, DeleteOutlined, FullscreenOutlined, FullscreenExitOutlined, SwapOutlined, SafetyCertificateOutlined} from "@ant-design/icons";
import React, {useEffect, useRef, useState} from 'react';
import {useI18n} from "@/layouts/i18n/useI18n.ts";
import { useUserInfo } from '@/fetches/user';
import { useCurrentRoles, useCurrentTenants, type CurrentRole, type CurrentTenant } from '@/fetches/tenants';
import { getTenantId, setTenantId } from '@/store/tenant';
import { getRoleId, setRoleId } from '@/store/role';
import { ensureContextId } from '@simplepoint/shared/api/contextId';
import { clearClientCaches, redirectToLogout } from '@simplepoint/shared/api/session';

// 为主题切换提供短暂的全局颜色过渡动画
function startThemeTransition(duration = 240) {
  try {
    const el = document.documentElement;
    if (!el || el.classList.contains('theme-transition')) return;
    el.classList.add('theme-transition');
    window.setTimeout(() => el.classList.remove('theme-transition'), duration + 60);
  } catch {}
}

// 简单的文本组件，便于在任意位置渲染 t()
const I18nText: React.FC<{ k: string; fallback?: string }> = ({ k, fallback }) => {
  const { t } = useI18n();
  return <>{t(k, fallback)}</>;
};

const LogoTitle: React.FC = () => {
  const { t } = useI18n();
  return (
    <span style={{ paddingLeft: '10px' }}>{t('app.title', 'Simple·Point')}</span>
  );
};

function normalizedTenantType(tenant?: Pick<CurrentTenant, 'tenantType'>): CurrentTenant['tenantType'] {
  return tenant?.tenantType === 'PERSONAL' ? 'PERSONAL' : 'ORGANIZATION';
}

function tenantTypeLabel(
  t: (key: string, fallback?: string) => string,
  tenant?: Pick<CurrentTenant, 'tenantType'>,
): string {
  return normalizedTenantType(tenant) === 'PERSONAL'
    ? t('tenant.type.personal', '个人')
    : t('tenant.type.organization', '组织');
}

function tenantDisplayName(
  t: (key: string, fallback?: string) => string,
  tenant?: Pick<CurrentTenant, 'tenantName' | 'tenantType'>,
): string {
  if (!tenant) return t('tenant.unknown', '未选择');
  if (normalizedTenantType(tenant) === 'PERSONAL') {
    return t('tenant.personalSpace', '个人空间');
  }
  return tenant.tenantName || t('tenant.unknown', '未选择');
}

function TenantTypeTag({
  tenant,
  t,
}: {
  tenant?: Pick<CurrentTenant, 'tenantType'>;
  t: (key: string, fallback?: string) => string;
}) {
  const type = normalizedTenantType(tenant);
  return (
    <Tag
      color={type === 'PERSONAL' ? 'blue' : 'green'}
      className="nb-tenant-type-tag"
    >
      {tenantTypeLabel(t, tenant)}
    </Tag>
  );
}

function roleDisplayName(
  t: (key: string, fallback?: string) => string,
  role?: Pick<CurrentRole, 'roleId' | 'roleName' | 'authority'>,
): string {
  if (!role) return t('role.allRoles', '全部角色');
  return role.roleName || role.authority || role.roleId || t('role.unknown', '未知角色');
}

/**
 * 顶部栏左侧：租户切换（显示在“平台”旁边）
 */
export const TenantSwitcherTop: React.FC = () => {
  const { t } = useI18n();
  const { data, isFetching, refetch } = useCurrentTenants();
  const [tenantId, setTenantIdState] = useState<string | undefined>(() => getTenantId());

  // 同步外部切换
  useEffect(() => {
    const handler = (e: Event) => setTenantIdState(((e as CustomEvent<string>).detail) || undefined);
    try {
      window.addEventListener('sp-set-tenant', handler);
      return () => window.removeEventListener('sp-set-tenant', handler);
    } catch {
      return;
    }
  }, []);

  // 第一次登录默认选第一个
  useEffect(() => {
    if (tenantId) return;
    const first = data?.[0];
    if (first?.tenantId) {
      setTenantId(first.tenantId);
      setTenantIdState(first.tenantId);
    }
  }, [data, tenantId]);

  const currentTenant = (data || []).find((x) => x.tenantId === tenantId);
  const currentName = tenantDisplayName(t, currentTenant);
  const currentType = currentTenant ? tenantTypeLabel(t, currentTenant) : t('tenant.type.unknown', '租户');

  const menu: MenuProps = {
    items: isFetching
      ? [{ key: 'loading', disabled: true, label: t('loading', '加载中...') }]
      : (data || []).map((it) => ({
          key: it.tenantId,
          label: (
            <div className="nb-tenant-menu-item">
              <div className="nb-tenant-menu-main">
                <span className="nb-tenant-menu-name">{tenantDisplayName(t, it)}</span>
                <TenantTypeTag tenant={it} t={t} />
              </div>
            </div>
          ),
        })),
    onClick: async (info: any) => {
      const nextId = String(info?.key || '');
      if (!nextId || nextId === tenantId) return;
      const nextTenant = (data || []).find((x) => x.tenantId === nextId);
      const nextType = tenantTypeLabel(t, nextTenant);
      const nextName = tenantDisplayName(t, nextTenant);

      // 先切租户：保证后续请求头 X-Tenant-Id 立即生效
      setTenantId(nextId);
      setTenantIdState(nextId);
      setRoleId(undefined, nextId);

      // 预热权限上下文（best-effort，不阻断切换；真正的当前态由 App 内部按最新 tenant 决定）
      await ensureContextId(nextId, { force: true });

      message.success(`${t('tenant.switchDone', '已切换租户，后续请求将生效')} · ${nextType}: ${nextName}`).then(_ => {});
    },
  };

  return (
    <Dropdown
      menu={menu}
      trigger={['click']}
      placement="bottomRight"
      destroyOnHidden
      onOpenChange={(open) => {
        if (open) {
          // 用户手动点击打开时强制刷新一次租户列表
          try { void refetch(); } catch {}
        }
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          cursor: 'pointer',
          userSelect: 'none',
        }}
        onClick={(e) => { try { e.stopPropagation(); } catch {} }}
        onMouseDown={(e) => { try { e.stopPropagation(); } catch {} }}
        title={`${t('label.tenant', '租户')} · ${currentType}: ${currentName}`}
      >
        <SwapOutlined style={{ marginRight: 6, opacity: 0.75 }} />
        {currentTenant && <TenantTypeTag tenant={currentTenant} t={t} />}
        <span className="nb-tenant-current-name">
          {currentName}
        </span>
      </div>
    </Dropdown>
  );
};

/**
 * 顶部栏左侧：角色切换（跟随当前租户）
 */
export const RoleSwitcherTop: React.FC = () => {
  const { t } = useI18n();
  const [tenantId, setTenantIdState] = useState<string | undefined>(() => getTenantId());
  const [roleId, setRoleIdState] = useState<string | undefined>(() => getRoleId(getTenantId()));
  const { data, isFetching, refetch } = useCurrentRoles(tenantId);

  useEffect(() => {
    const handleTenantChange = (event: Event) => {
      const nextTenantId = (event as CustomEvent<string | undefined>).detail ?? getTenantId();
      setTenantIdState(nextTenantId);
      setRoleIdState(getRoleId(nextTenantId));
    };
    const handleRoleChange = (event: Event) => {
      const detail = (event as CustomEvent<{ tenantId?: string; roleId?: string }>).detail;
      if (detail && typeof detail === 'object') {
        if ((detail.tenantId ?? '') !== (tenantId ?? '')) return;
        setRoleIdState(detail.roleId);
        return;
      }
      setRoleIdState(getRoleId(tenantId));
    };
    window.addEventListener('sp-set-tenant', handleTenantChange as EventListener);
    window.addEventListener('sp-set-role', handleRoleChange as EventListener);
    return () => {
      window.removeEventListener('sp-set-tenant', handleTenantChange as EventListener);
      window.removeEventListener('sp-set-role', handleRoleChange as EventListener);
    };
  }, [tenantId]);

  useEffect(() => {
    const roles = data || [];
    if (!tenantId || roles.length === 0) {
      if (roleId) {
        setRoleId(undefined, tenantId);
        setRoleIdState(undefined);
      }
      return;
    }
    if (roleId && roles.some((role) => role.roleId === roleId)) {
      return;
    }
    if (roles.length === 1) {
      const onlyRoleId = roles[0]?.roleId;
      setRoleId(onlyRoleId, tenantId);
      setRoleIdState(onlyRoleId);
      return;
    }
    setRoleId(undefined, tenantId);
    setRoleIdState(undefined);
  }, [data, roleId, tenantId]);

  const roles = data || [];
  const currentRole = roles.find((role) => role.roleId === roleId);
  const currentName = roleDisplayName(t, currentRole);
  const switchable = roles.length > 1;

  const menu: MenuProps = {
    items: isFetching
      ? [{ key: 'loading', disabled: true, label: t('loading', '加载中...') }]
      : [
          {
            key: '__all__',
            label: t('role.allRoles', '全部角色'),
          },
          ...roles.map((role) => ({
            key: role.roleId,
            label: (
              <div className="nb-role-menu-item">
                <span className="nb-role-menu-name">{roleDisplayName(t, role)}</span>
                {role.authority && <span className="nb-role-menu-code">{role.authority}</span>}
              </div>
            ),
          })),
        ],
    onClick: async (info: any) => {
      if (!tenantId) return;
      const nextRoleId = info?.key === '__all__' ? undefined : String(info?.key || '');
      if ((nextRoleId ?? '') === (roleId ?? '')) return;

      setRoleId(nextRoleId, tenantId);
      setRoleIdState(nextRoleId);
      const nextContextId = await ensureContextId(tenantId, { force: true, roleId: nextRoleId });
      window.dispatchEvent(new CustomEvent('sp-set-context-id', {
        detail: { tenantId, roleId: nextRoleId, contextId: nextContextId },
      }));

      const nextRole = roles.find((role) => role.roleId === nextRoleId);
      message.success(`${t('role.switchDone', '已切换角色')} · ${roleDisplayName(t, nextRole)}`).then(_ => {});
    },
  };

  if (!tenantId || roles.length === 0) {
    return null;
  }

  return (
    <Dropdown
      menu={menu}
      trigger={['click']}
      placement="bottomRight"
      destroyOnHidden
      disabled={!switchable}
      onOpenChange={(open) => {
        if (open) {
          try { void refetch(); } catch {}
        }
      }}
    >
      <div
        className={`nb-role-switcher${switchable ? '' : ' nb-role-switcher-disabled'}`}
        onClick={(e) => { try { e.stopPropagation(); } catch {} }}
        onMouseDown={(e) => { try { e.stopPropagation(); } catch {} }}
        title={`${t('label.role', '角色')} · ${currentName}`}
      >
        <SafetyCertificateOutlined style={{ marginRight: 6, opacity: 0.75 }} />
        <span className="nb-role-current-name">{currentName}</span>
      </div>
    </Dropdown>
  );
};

/**
 * logo配置
 * @param navigate 用于路由跳转的回调函数
 */
export const logoItem = (navigate: (path: string) => void): ItemType => {
  return {
    key: 'logo',
    label: (
      <div style={{
        // 防止在收缩时把 logo 压缩掉
        flexShrink: 0,
        display: 'flex',
        // 保证 logo 在容器内垂直居中
        alignItems: 'center',
        justifyContent: 'center', // 保证在容器内水平居中
        padding: '0 16px', // 根据设计需求控制左右间距
      }}>
        <img src="/svg.svg" alt="Logo" style={{ height: '32px', display: 'block' }} />
        <LogoTitle/>
        <TenantSwitcherTop />
        <RoleSwitcherTop />
      </div>
    ),
    onClick: () => navigate('/')
  }
}

/**
 * Header Logo 独立组件（不依赖 AntD Menu，避免溢出检测折叠）
 */
export const HeaderLogo: React.FC<{ navigate: (path: string) => void }> = ({ navigate }) => (
  <div className="nb-header-brand">
    <div
      className="nb-header-logo"
      onClick={() => navigate('/')}
      role="button"
      tabIndex={0}
      onKeyDown={e => e.key === 'Enter' && navigate('/')}
    >
      <img src="/svg.svg" alt="Logo" style={{ height: '32px', display: 'block', flexShrink: 0 }} />
      <LogoTitle />
    </div>
  </div>
);

function getGreetingKeyByTime() {
  const hour = new Date().getHours();
  if (hour < 6) return 'greeting.early';
  if (hour < 12) return 'greeting.morning';
  if (hour < 18) return 'greeting.afternoon';
  return 'greeting.evening';
}

// 从 /userinfo 获取头像与昵称（带会话缓存与卸载保护）
const HeaderUser: React.FC = () => {
  const { t } = useI18n();
  const { data: info } = useUserInfo();
  const nickname: string = info?.nickname || info?.name || t('user.defaultName', '用户');
  const picture: string | undefined = info?.picture || undefined;
  const isMock = typeof window !== 'undefined' && /localhost|127\.0\.0\.1/.test(window.location.hostname);
  const greeting = t(getGreetingKeyByTime());
  return (
    <div className="nb-header-user">
      <Avatar size='default' alt={nickname} icon={!picture ? <UserOutlined/> : undefined} src={picture}/>
      <span className="nb-header-user-text">
        <span className="nb-greeting">{greeting}</span>
        <span className="nb-name"> {nickname}</span>
      </span>
      {isMock && <span style={{marginLeft: 4, opacity: 0.65}}></span>}
    </div>
  );
};

/**
 * 在顶部导航右侧：切换全局尺寸按钮（按钮本体组件，内部使用 hook）
 */
const SizeButton = React.memo<{ type?: 'text'|'default' }>(({ type = 'default' }) => {
  const { t } = useI18n();
  const order: Array<'small'|'middle'|'large'> = ['small','middle','large'];
  const getNext = (cur: 'small'|'middle'|'large') => order[(order.indexOf(cur)+1)%order.length];
  const onToggleSize = () => {
    const cur = (localStorage.getItem('sp.globalSize') as 'small'|'middle'|'large') || 'middle';
    const next = getNext(cur);
    try { localStorage.setItem('sp.globalSize', next); } catch (e) { console.warn('[nav] Failed to save size:', e); }
    window.dispatchEvent(new CustomEvent('sp-set-size', { detail: next }));
  };
  return (
    <Tooltip title={<span>{t('tooltip.size','切换全局尺寸(小/中/大)')} · <a href="https://github.com/simplepoint1024/open-simplepoint-dashboard-react" target="_blank" rel="noopener noreferrer">GitHub</a></span>}>
      <Button type={type} size="small" icon={<FontSizeOutlined/>} onClick={onToggleSize}
              style={{width:28,height:28,padding:0,borderRadius:4,margin: type==='text'?0:'0 8px'}}/>
    </Tooltip>
  );
});

/**
 * 清理全局缓存按钮
 */
const ClearCacheButton = React.memo<{ type?: 'text'|'default' }>(({ type = 'default' }) => {
  const { t } = useI18n();
  const [loading, setLoading] = useState(false);

  const onConfirm = async () => {
    setLoading(true);
    try {
      await clearClientCaches({ preserveSessionContext: true, rebuildContextId: true });
      message.success(t('tools.clearDone','已清理全局缓存'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <Popconfirm
      placement="bottomRight"
      title={t('tools.clearCache','清理缓存')}
      description={t('tools.clearCacheConfirm','确认清理全局缓存？')}
      okText={t('ok','确定')}
      cancelText={t('cancel','取消')}
      onConfirm={onConfirm}
      disabled={loading}
    >
      <Button type={type} size="small" icon={<DeleteOutlined/>} loading={loading}
              style={{width:28,height:28,padding:0,borderRadius:4,margin: type==='text'?0:'0 4px'}}/>
    </Popconfirm>
  );
});

/**
 * 主题模式切换（亮/暗/跟随系统）
 */
const ThemeButton = React.memo<{ compact?: boolean }>(({ compact }) => {
  const { t } = useI18n();
  const [mode, setMode] = useState<'light'|'dark'|'system'>(() => (localStorage.getItem('sp.theme') as 'light'|'dark'|'system') || 'light');
  useEffect(() => {
    const handler = (e: Event) => setMode(((e as CustomEvent<string>).detail as 'light'|'dark'|'system') || 'light');
    window.addEventListener('sp-set-theme', handler);
    return () => window.removeEventListener('sp-set-theme', handler);
  }, []);
  const nextOf = (m: 'light'|'dark'|'system'): 'light'|'dark'|'system' => (m === 'light' ? 'dark' : m === 'dark' ? 'system' : 'light');
  const toggle = () => {
    const next = nextOf(mode);
    startThemeTransition(240);
    try { localStorage.setItem('sp.theme', next); } catch (e) { console.warn('[nav] Failed to save theme:', e); }
    window.dispatchEvent(new CustomEvent('sp-set-theme', { detail: next }));
    setMode(next);
  };
  const tip = `${t('settings.theme','主题模式')}: ${mode === 'system' ? t('settings.system','跟随系统') : (mode === 'dark' ? t('settings.dark','深色') : t('settings.light','浅色'))}`;
  const Icon = mode === 'system' ? DesktopOutlined : (mode === 'dark' ? SunOutlined : MoonOutlined);
  return (
    <Tooltip title={tip}>
      <Button aria-label="toggle-theme" type={compact ? 'text' : 'default'} size="small" icon={<Icon/>} onClick={toggle}
              style={{width:28,height:28,padding:0,borderRadius:4,margin: compact ? 0 : '0 4px'}}/>
    </Tooltip>
  );
});

/**
 * 全屏切换按钮
 */
const FullscreenButton = React.memo<{ type?: 'text'|'default' }>(({ type = 'default' }) => {
  const { t } = useI18n();
  const [isFull, setIsFull] = useState<boolean>(() => {
    try { return !!document.fullscreenElement; } catch { return false; }
  });

  useEffect(() => {
    const onChange = () => {
      try { setIsFull(!!document.fullscreenElement); } catch {}
    };
    document.addEventListener('fullscreenchange', onChange);
    return () => document.removeEventListener('fullscreenchange', onChange);
  }, []);

  const toggle = async () => {
    try {
      if (!isFull) {
        await document.documentElement.requestFullscreen?.();
      } else {
        await document.exitFullscreen?.();
      }
    } catch {
      message.warning(t('tools.fullscreen.notAllowed','当前环境不支持全屏或被浏览器拦截'));
    }
  };

  const tip = isFull ? t('tools.fullscreen.exit','退出全屏') : t('tools.fullscreen.enter','进入全屏');
  const Icon = isFull ? FullscreenExitOutlined : FullscreenOutlined;
  return (
    <Tooltip title={tip}>
      <Button type={type} size="small" icon={<Icon/>} onClick={toggle}
              style={{width:28,height:28,padding:0,borderRadius:4,margin: type==='text'?0:'0 4px'}}/>
    </Tooltip>
  );
});

/**
 * 顶部导航右侧：语言切换（动态从后端获取语言列表）
 */
const LanguageButton: React.FC<{ compact?: boolean }> = ({ compact }) => {
  const { languages, locale, setLocale, t, ensure } = useI18n();
  useEffect(() => { try { void ensure(['common','menu']); } catch {}}, [ensure, locale]);
  const [open, setOpen] = useState(false);
  const [switching, setSwitching] = useState(false);
  const closingRef = useRef(false);
  const mountedRef = useRef(true);
  useEffect(() => { mountedRef.current = true; return () => { mountedRef.current = false; }; }, []);
  const hasLanguages = (languages || []).length > 0;
  const onSelect = (lng: string) => {
    if (!lng || lng === locale) { setOpen(false); return; }
    setSwitching(true);
    closingRef.current = true;
    setOpen(false);

     // 监听一次 i18n 更新事件，切换完成后关闭 loading
     const handler = () => {
       try {
         if (window.spI18n?.locale === lng) {
           window.removeEventListener('sp-i18n-updated', handler);
           if (mountedRef.current) setSwitching(false);
         }
       } catch {}
     };
     try { window.addEventListener('sp-i18n-updated', handler, { once: true }); } catch { /* older browsers */ }
     // 兜底超时，避免极端情况下 loading 不消失
     const tm = window.setTimeout(() => { if (mountedRef.current) setSwitching(false); try { window.removeEventListener('sp-i18n-updated', handler); } catch {} }, 3000);
     // 当事件到了也清除兜底
     const clearFallback = () => { try { window.clearTimeout(tm); } catch {} };
     try { window.addEventListener('sp-i18n-updated', clearFallback, { once: true }); } catch {}
     setLocale(lng);
    // 下一帧允许下次打开
    window.setTimeout(() => { if (mountedRef.current) closingRef.current = false; }, 120);
   };
  const menu: MenuProps = {
    items: (languages || []).map(l => ({ key: l.code, label: l.name })),
    onClick: (info: any) => {
       try { info?.domEvent?.stopPropagation(); info?.domEvent?.preventDefault(); } catch {}
       onSelect(String(info?.key));
      // 这里不再重复 setOpen(false)，交给 onSelect 控制 + closingRef
     },
  };
  const current = languages.find(l => l.code === locale);
  const tip = `${t('label.language','语言')}：${current ? `${current.name}(${current.code})` : locale}`;
  // 语言变化时强制关闭下拉并结束 loading（多一层保险）
  useEffect(() => { setOpen(false); setSwitching(false); }, [locale]);
  return (
    <Tooltip title={tip}>
      <Dropdown
        menu={menu}
        trigger={["click"]}
        placement="bottomRight"
        destroyOnHidden
        open={open}
        onOpenChange={(next) => {
          // 如果刚刚因选择而关闭，忽略一帧内的反向打开
          if (next && closingRef.current) return;
          setOpen(next);
        }}
      >
         <Button type={compact ? 'text' : 'default'} size="small" icon={<GlobalOutlined/>}
                 disabled={!hasLanguages}
                 loading={switching}
                 style={{width: 28, height: 28, padding: 0, borderRadius: 4, margin: compact ? 0 : '0 4px'}}/>
      </Dropdown>
    </Tooltip>
  );
};

/**
 * Header 搜索条（独立组件，直接挂到 Header flex 布局中）
 */
export const HeaderSearchBar: React.FC<{ onOpen: () => void }> = ({ onOpen }) => {
  const { t } = useI18n();
  const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/i.test(navigator.platform);
  return (
    <div className="nb-search-bar" onClick={onOpen} role="button" tabIndex={0}
      onKeyDown={e => e.key === 'Enter' && onOpen()}
      aria-label={t('menu.search.placeholder', '搜索菜单…')}
    >
      <SearchOutlined className="nb-search-bar-icon" />
      <span className="nb-search-bar-placeholder">{t('menu.search.placeholder', '搜索菜单…')}</span>
      <kbd className="nb-search-bar-kbd">{isMac ? '⌘K' : 'Ctrl+K'}</kbd>
    </div>
  );
};

const mockNotifications = [
  {
    id: 1,
    titleKey: 'nav.notification.systemUpdate.title',
    titleFallback: '系统更新',
    descKey: 'nav.notification.systemUpdate.desc',
    descFallback: 'SimplePoint v2.1.0 已发布',
    timeKey: 'nav.notification.systemUpdate.time',
    timeFallback: '5分钟前',
    read: false,
  },
  {
    id: 2,
    titleKey: 'nav.notification.newUsers.title',
    titleFallback: '新用户注册',
    descKey: 'nav.notification.newUsers.desc',
    descFallback: '有3位新用户待审批',
    timeKey: 'nav.notification.newUsers.time',
    timeFallback: '1小时前',
    read: false,
  },
  {
    id: 3,
    titleKey: 'nav.notification.taskDone.title',
    titleFallback: '任务完成',
    descKey: 'nav.notification.taskDone.desc',
    descFallback: '数据同步任务已完成',
    timeKey: 'nav.notification.taskDone.time',
    timeFallback: '2小时前',
    read: true,
  },
];

const NotificationButton: React.FC = () => {
  const {t} = useI18n();
  const [open, setOpen] = useState(false);
  const [notifications, setNotifications] = useState(mockNotifications);
  const unread = notifications.filter(n => !n.read).length;

  const markAllRead = () => setNotifications(prev => prev.map(n => ({...n, read: true})));

  return (
    <>
      <Tooltip title={t('nav.notifications', '通知')}>
        <Badge count={unread} size="small" offset={[-2, 2]}>
          <Button type="text" icon={<BellOutlined />} onClick={() => setOpen(true)} />
        </Badge>
      </Tooltip>
      <Drawer
        title={
          <div style={{display:'flex', justifyContent:'space-between', alignItems:'center'}}>
            <span>{t('nav.notifications','通知中心')}</span>
            {unread > 0 && (
              <Button type="link" size="small" onClick={markAllRead}>
                {t('nav.markAllRead','全部已读')}
              </Button>
            )}
          </div>
        }
        open={open}
        onClose={() => setOpen(false)}
        width={360}
        styles={{body: {padding: 0}}}
      >
        <List
          dataSource={notifications}
          renderItem={item => (
            <List.Item
              style={{
                padding: '12px 16px',
                background: item.read ? 'transparent' : 'rgba(22,119,255,0.04)',
                borderLeft: item.read ? '3px solid transparent' : '3px solid #1677ff',
                cursor: 'pointer',
              }}
              onClick={() => setNotifications(prev => prev.map(n => n.id === item.id ? {...n, read: true} : n))}
            >
              <List.Item.Meta
                title={<span style={{fontSize:13, fontWeight: item.read ? 400 : 600}}>{t(item.titleKey, item.titleFallback)}</span>}
                description={
                  <div>
                    <div style={{fontSize:12, color:'rgba(0,0,0,0.65)'}}>{t(item.descKey, item.descFallback)}</div>
                    <div style={{fontSize:11, color:'rgba(0,0,0,0.35)', marginTop:2}}>{t(item.timeKey, item.timeFallback)}</div>
                  </div>
                }
              />
            </List.Item>
          )}
        />
      </Drawer>
    </>
  );
};

const ShortcutsButton: React.FC = () => {
  const {t} = useI18n();
  return (
    <Tooltip title={t('nav.shortcuts', '快捷键 (?)')}>
      <Button type="text" icon={<QuestionCircleOutlined />} onClick={() => window.dispatchEvent(new CustomEvent('sp-open-shortcuts'))} />
    </Tooltip>
  );
};

/**
 * 紧凑工具组：语言 + 尺寸 + 主题
 */
export const toolsSwitcherGroupItem = (): ItemType => {
  return {
    key: 'tools-switcher',
    style: { paddingInline: 4, minWidth: 120, textAlign: 'center', display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%' },
    label: (
      <div className="nb-tools-item">
        <NotificationButton />
        <ShortcutsButton />
        <Tooltip title="GitHub">
          <Button
            type="text"
            icon={<GithubOutlined />}
            onClick={() => window.open('https://github.com/simplepoint1024/open-simplepoint-dashboard', '_blank', 'noopener,noreferrer')}
          />
        </Tooltip>
        <LanguageButton compact/>
        <ThemeButton compact/>
        <SizeButton type='text'/>
        <FullscreenButton type='text'/>
        <ClearCacheButton type='text'/>
      </div>
    )
  };
};

/**
 * 关于我配置
 * @param navigate 路由跳转配置项
 */
 export const aboutMeItem = (navigate: (path: string) => void): ItemType => {
  const menu = avatarConfig(navigate);
  return {
    key: 'me',
    label: <HeaderUser/>,
    children: menu.items as any,
    style: {marginRight: '24px', paddingRight: '0'}
  }
}

/**
 * 头像配置项
 * @param navigate 路由跳转回调函数
 */
export const avatarConfig = (navigate: (path: string) => void): MenuProps => {
  return {
    items: [
      {
        type: 'group',
        label: <I18nText k='group.account' fallback='账户'/> as any,
        children: [
          { key: 'profile', label: <I18nText k='menu.profile' fallback='个人资料'/>, icon: <UserOutlined/>, onClick: () => navigate('/profile') },
          { key: 'billing', label: <I18nText k='menu.billing' fallback='账单信息'/>, icon: <CreditCardOutlined/>, onClick: () => navigate('/billing'), disabled: true },
        ]
      },
      {
        type: 'group',
        label: <I18nText k='group.app' fallback='应用'/> as any,
        children: [
          { key: 'settings', label: <I18nText k='menu.settings' fallback='系统设置'/>, icon: <SettingOutlined/>, onClick: () => navigate('/settings') },
        ]
      },
      { type: 'divider' },
      {
        key: 'logout',
        label: <I18nText k='menu.logout' fallback='退出登录'/> as any,
        icon: <LogoutOutlined/>,
        onClick: async () => {
          await redirectToLogout();
        }
      },
    ]
  };
}
