import type {ItemType} from "antd/es/menu/interface";
import {App as AntdApp, Avatar, Button, Dropdown, Popover, MenuProps, Tooltip, Popconfirm} from "antd";
import {ApartmentOutlined, CreditCardOutlined, DesktopOutlined, DownOutlined, EditOutlined, FontSizeOutlined, FullscreenExitOutlined, FullscreenOutlined, GithubOutlined, GlobalOutlined, HomeOutlined, LogoutOutlined, MoonOutlined, QuestionCircleOutlined, SafetyCertificateOutlined, SearchOutlined, SettingOutlined, SunOutlined, UserOutlined} from "@simplepoint/shared/types/icon";
import React, {useEffect, useRef, useState} from 'react';
import TableActions from '@simplepoint/components/Table/Actions';
import TenantSelect from '@simplepoint/components/TenantSelect';
import {TenantMark, TenantTypeTag} from '@simplepoint/components/TenantSelect/Identity';
import RoleSelect from '@simplepoint/components/RoleSelect';
import type {EntityOption} from '@simplepoint/components/EntitySelect/query';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {userDisplayName} from '@simplepoint/shared/utils/userIdentity';
import {useI18n} from "@/layouts/i18n/useI18n.ts";
import { useUserInfo } from '@/fetches/user';
import { useCurrentRoles, useCurrentTenants, type CurrentRole, type CurrentTenant } from '@/fetches/tenants';
import { getTenantId, setTenantId } from '@/store/tenant';
import { getRoleId, setRoleId } from '@/store/role';
import { setContextId } from '@/store/contextId';
import { ensureContextId } from '@simplepoint/shared/api/contextId';
import {switchAuthorizedContext} from '@simplepoint/shared/api/contextSwitch';
import { clearClientCaches, redirectToLogout } from '@simplepoint/shared/api/session';
import {NotificationButton} from './NotificationButton';
import {useNavigationGuard} from './NavigationGuard';

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
  if (tenant?.tenantType === 'PLATFORM') return 'PLATFORM';
  return tenant?.tenantType === 'PERSONAL' ? 'PERSONAL' : 'ORGANIZATION';
}

function tenantTypeLabel(
  t: (key: string, fallback?: string) => string,
  tenant?: Pick<CurrentTenant, 'tenantType'>,
): string {
  const type = normalizedTenantType(tenant);
  if (type === 'PLATFORM') return t('tenant.type.platform', '平台');
  return type === 'PERSONAL'
      ? t('tenant.type.personal', '个人')
      : t('tenant.type.organization', '组织');
}

function tenantDisplayName(
  t: (key: string, fallback?: string) => string,
  tenant?: Pick<CurrentTenant, 'tenantName' | 'tenantType'>,
): string {
  if (!tenant) return t('tenant.unknown', '未选择');
  if (normalizedTenantType(tenant) === 'PLATFORM') {
    return tenant.tenantName || t('tenant.platformSpace', '平台工作台');
  }
  if (normalizedTenantType(tenant) === 'PERSONAL') {
    return t('tenant.mySpace', '我的空间');
  }
  return tenant.tenantName || t('tenant.unknown', '未选择');
}

function roleDisplayName(
  t: (key: string, fallback?: string) => string,
  role?: Pick<CurrentRole, 'roleName'>,
): string {
  if (!role) return t('role.allRoles', '全部角色');
  return role.roleName?.trim() || t('role.unnamed', '未命名角色');
}

/**
 * 顶部栏上下文区：工作空间切换。
 */
export const TenantSwitcherTop: React.FC = () => {
  const confirmWorkspaceChange = useNavigationGuard();
  const {message} = AntdApp.useApp();
  const { t } = useI18n();
  const { data } = useCurrentTenants();
  const [pickerOpen, setPickerOpen] = useState(false);
  const switching = useRef(false);
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

  const currentTenant = (data || []).find((x) => x.tenantId === tenantId);
  const currentName = tenantDisplayName(t, currentTenant);
  const currentType = currentTenant ? tenantTypeLabel(t, currentTenant) : t('tenant.type.unknown', '租户');

  const changeTenant = async (nextId?: string, option?: EntityOption) => {
    if (!nextId || nextId === tenantId || switching.current) return;
    switching.current = true;
    const previous = tenantId;
    try {
      const changed = await switchAuthorizedContext({confirm: confirmWorkspaceChange,
        current: () => getTenantId() === previous,
        authorize: () => ensureContextId(nextId, {force: true, roleId: '', persist: false}),
        unavailable: t('error.operationFailed', '操作失败，请重试'),
        commit: contextId => {
          setPickerOpen(false);
          setRoleId(undefined, nextId);
          setContextId(contextId, nextId);
          setTenantId(nextId);
          setTenantIdState(nextId);
        }});
      if (changed) message.success(`${t('tenant.switchDone', '已切换租户，后续请求将生效')} · ${option?.label ?? ''}`);
    } catch (error) {
      message.error(resolveApiErrorMessage(error, t('error.operationFailed', '操作失败，请重试')));
    } finally {switching.current = false;}
  };

  return (
    <Popover title={<TenantTypeTag type={currentTenant?.tenantType}/>} open={pickerOpen} onOpenChange={setPickerOpen} trigger="click" placement="bottom" destroyOnHidden
      content={pickerOpen && <div style={{width: 'min(340px, 75vw)'}}>
        <TenantSelect directory="current" value={tenantId} allowClear={false} autoFocus defaultOpen
          ariaLabel={t('label.workspace', '工作空间')} onChange={(id, option) => {void changeTenant(id, option);}}/>
      </div>}>

      <button
        type="button"
        className="nb-context-switcher nb-tenant-switcher"
        onClick={(e) => { try { e.stopPropagation(); } catch {} }}
        onMouseDown={(e) => { try { e.stopPropagation(); } catch {} }}
        title={`${t('label.tenant', '租户')} · ${currentType}: ${currentName}`}
      >
        <TenantMark type={currentTenant?.tenantType} logo={currentTenant?.tenantLogo} size={19} className="nb-tenant-current-logo" />
        <span className="nb-context-label">{t('label.workspace', '工作空间')}</span>
        <span className="nb-tenant-current-name">
          {currentName}
        </span>
        <DownOutlined className="nb-context-arrow" />
      </button>
    </Popover>
  );
};

/**
 * 顶部栏上下文区：跟随当前工作空间的角色切换。
 */
export const RoleSwitcherTop: React.FC = () => {
  const confirmWorkspaceChange = useNavigationGuard();
  const {message} = AntdApp.useApp();
  const { t } = useI18n();
  const [tenantId, setTenantIdState] = useState<string | undefined>(() => getTenantId());
  const [roleId, setRoleIdState] = useState<string | undefined>(() => getRoleId(getTenantId()));
  const { data, isFetching } = useCurrentRoles(tenantId);
  const [pickerOpen, setPickerOpen] = useState(false);
  const switching = useRef(false);

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
  const currentName = roles.length === 0
    ? (isFetching ? t('loading', '加载中...') : t('role.none', '无可用角色'))
    : roleDisplayName(t, currentRole);
  const switchable = roles.length > 1;

  const changeRole = async (nextRoleId?: string, option?: EntityOption) => {
    if (!tenantId || switching.current || (nextRoleId ?? '') === (roleId ?? '')) return;
    switching.current = true;
    const capturedTenant = tenantId, previousRole = roleId;
    try {
      const changed = await switchAuthorizedContext({confirm: confirmWorkspaceChange,
        current: () => getTenantId() === capturedTenant && getRoleId(capturedTenant) === previousRole,
        authorize: () => ensureContextId(capturedTenant, {force: true, roleId: nextRoleId ?? '', persist: false}),
        unavailable: t('error.operationFailed', '操作失败，请重试'),
        commit: contextId => {
          setPickerOpen(false);
          setRoleId(nextRoleId, capturedTenant);
          setRoleIdState(nextRoleId);
          setContextId(contextId, capturedTenant, nextRoleId);
        }});
      if (changed) message.success(`${t('role.switchDone', '已切换角色')} · ${option?.label ?? t('role.allRoles', '全部角色')}`);
    } catch (error) {
      message.error(resolveApiErrorMessage(error, t('error.operationFailed', '操作失败，请重试')));
    } finally {switching.current = false;}
  };

  if (!tenantId) {
    return null;
  }

  return (
    <Popover title={t('label.role', '角色')} open={pickerOpen && switchable} onOpenChange={setPickerOpen} trigger="click" placement="bottom" destroyOnHidden
      content={pickerOpen && <div style={{width: 'min(340px, 75vw)'}}>
        <RoleSelect directory="current" value={roleId} allowClear autoFocus defaultOpen
          placeholder={t('role.allRoles', '全部角色')} ariaLabel={t('label.role', '角色')}
          onChange={(id, option) => {void changeRole(id, option);}}/>
        <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'allRoles', sort: 0, type: 'link',
          text: t('role.allRoles', '全部角色'), disabled: !roleId, onClick: () => {void changeRole();}}]}/>
      </div>}>

      <button
        type="button"
        className={`nb-context-switcher nb-role-switcher${switchable ? '' : ' nb-role-switcher-disabled'}`}
        disabled={!switchable}
        onClick={(e) => { try { e.stopPropagation(); } catch {} }}
        onMouseDown={(e) => { try { e.stopPropagation(); } catch {} }}
        title={`${t('label.role', '角色')} · ${currentName}`}
      >
        <SafetyCertificateOutlined className="nb-context-icon" />
        <span className="nb-context-label">{t('label.role', '角色')}</span>
        <span className="nb-role-current-name">{currentName}</span>
        {switchable && <DownOutlined className="nb-context-arrow" />}
      </button>
    </Popover>
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
    onClick: () => navigate('/dashboard')
  }
}

/**
 * Header Logo 独立组件（不依赖 AntD Menu，避免溢出检测折叠）
 */
export const HeaderLogo: React.FC<{ navigate: (path: string) => void }> = ({ navigate }) => {
  const {data} = useCurrentTenants();
  const [tenantId, setTenantIdState] = useState<string | undefined>(() => getTenantId());

  useEffect(() => {
    const handler = (event: Event) => {
      setTenantIdState((event as CustomEvent<string | undefined>).detail ?? getTenantId());
    };
    window.addEventListener('sp-set-tenant', handler as EventListener);
    return () => window.removeEventListener('sp-set-tenant', handler as EventListener);
  }, []);

  const currentTenant = data?.find(tenant => tenant.tenantId === tenantId);
  const logo = currentTenant?.tenantLogo || '/svg.svg';

  return (
    <div className="nb-header-brand">
      <div
        className="nb-header-logo"
        onClick={() => navigate('/dashboard')}
        role="button"
        tabIndex={0}
        onKeyDown={e => e.key === 'Enter' && navigate('/dashboard')}
      >
        <img
          src={logo}
          alt={currentTenant?.tenantName || 'Logo'}
          className="nb-header-logo-image"
          onError={event => {
            const image = event.currentTarget;
            const fallback = new URL('/svg.svg', window.location.origin).href;
            if (image.src !== fallback) image.src = fallback;
          }}
        />
        <LogoTitle />
      </div>
    </div>
  );
};

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
  const nickname: string = userDisplayName(info) || t('user.defaultName', '用户');
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
  const {message} = AntdApp.useApp();
  const { t } = useI18n();
  const [loading, setLoading] = useState(false);
  const confirmWorkspaceChange = useNavigationGuard(), flight = useRef(false);

  const onConfirm = async () => {
    if (flight.current) return;
    flight.current = true;
    setLoading(true);
    try {
      if (!await confirmWorkspaceChange()) return;
      await clearClientCaches({ preserveSessionContext: true, rebuildContextId: true });
      message.success(t('tools.clearDone','已清理全局缓存'));
    } catch (error) {
      message.error(resolveApiErrorMessage(error, t('error.operationFailed', '操作失败，请重试')));
    } finally {
      flight.current = false;
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
      <span style={{display: 'inline-flex'}}><TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'clearCache', sort: 0, type, size: 'small',
        icon: 'DeleteOutlined', iconOnly: true, text: t('tools.clearCache','清理缓存'), loading, disabled: loading, onClick: () => {},
        style: {width:28,height:28,padding:0,borderRadius:4,margin: type==='text'?0:'0 4px'},
      }]}/></span>
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
  const {message} = AntdApp.useApp();
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
 export const aboutMeItem = (
   navigate: (path: string) => void,
   tenant?: {enabled: boolean; editable: boolean; organization?: boolean},
 ): ItemType => {
  const menu = avatarConfig(navigate, tenant);
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
export const avatarConfig = (
  navigate: (path: string) => void,
  tenant?: {enabled: boolean; editable: boolean; organization?: boolean},
): MenuProps => {
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
      ...(tenant?.enabled ? [{
        type: 'group' as const,
        label: <I18nText k='group.tenant' fallback='租户'/> as any,
        children: [
          {key: 'tenant-home', label: <I18nText k='menu.tenantHome' fallback='租户主页'/>, icon: <HomeOutlined/>, onClick: () => navigate('/tenant')},
          ...(tenant.editable ? [{key: 'tenant-profile', label: <I18nText k='menu.workspaceSettings' fallback='空间设置'/>, icon: <EditOutlined/>, onClick: () => navigate('/tenant/settings')}] : []),
          ...(tenant.editable && tenant.organization ? [{key: 'tenant-resources', label: <I18nText k='menu.tenantResources' fallback='租户资源管理'/>, icon: <ApartmentOutlined/>, onClick: () => navigate('/tenant/resources')}] : []),
        ],
      }] : []),
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
