import {lazy, Suspense, useEffect, useMemo, useState, type CSSProperties} from 'react';
import {Alert, Badge, Empty, Spin, theme} from 'antd';
import {AppstoreOutlined, BellOutlined} from '@ant-design/icons';
import TableActions from '@simplepoint/components/Table/Actions';
import {useNavigate} from 'react-router';
import {usePageActive, usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import {createIcon} from '@simplepoint/shared/types/icon';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {userDisplayName} from '@simplepoint/shared/utils/userIdentity';
import {useI18n} from '@/layouts/i18n/useI18n';
import {useUserInfo} from '@/fetches/user';
import {useCurrentTenants} from '@/fetches/tenants';
import type {RouteInfo} from '@/store/routes';
import {NotificationList} from '@/notifications/NotificationList';
import {useNotificationInbox, useNotifications} from '@/notifications/NotificationProvider';
import {cancelPrefetchLazyComponent, prefetchLazyComponent} from '@/utils/lazyComponent';
import {availableApps} from './preferences';
import {useHomePreferences} from './useHomePreferences';
import {LocalContext} from './LocalContext';
import {PersonalOverview} from './PersonalOverview';
import './index.css';
const HomeEditor = lazy(() => import('./HomeEditor'));

type HomeProps = {routes: RouteInfo[]; tenantType?: string; remoteRegistryKey?: string};
export function Home(props: HomeProps) {
  const scope = useQueryScope(), user = useUserInfo();
  return <HomeSession key={JSON.stringify([scope, user.data?.sub || user.data?.id])} {...props}/>;
}
function HomeSession({routes, tenantType, remoteRegistryKey}: HomeProps) {
  const {t, ensure, locale} = useI18n();
  const {token} = theme.useToken();
  const navigate = useNavigate();
  const active = usePageActive();
  const selected = usePageSelected();
  const scope = useQueryScope();
  const user = useUserInfo();
  const tenants = useCurrentTenants();
  const prefs = useHomePreferences(user.data?.sub || user.data?.id);
  const {unread, setOpen, busy, markRead} = useNotifications();
  const inbox = useNotificationInbox(active);
  const [editing, setEditing] = useState(false);
  usePageRetention(!editing && !prefs.saving);
  useEffect(() => { void ensure(['host-dashboard', 'menu', 'common', 'notifications', 'personal']); }, [ensure, locale]);
  const apps = useMemo(() => availableApps(routes, tenantType), [routes, tenantType]);
  const appMap = useMemo(() => new Map(apps.map(app => [app.path, app])), [apps]);
  const appLabel = (app: RouteInfo) => t(app.title || '', app.label || app.path || '');
  const groups = prefs.data?.groups || [];
  const favorites = new Set(groups.flatMap(group => group.paths.filter(path => appMap.has(path)))).size;
  const workspace = tenants.data?.find(tenant => tenant.tenantId === scope[0])?.tenantName || '—';
  const username = userDisplayName(user.data) || '';
  const style = {'--home-bg': token.colorBgContainer, '--home-border': token.colorBorderSecondary,
    '--home-muted': token.colorTextSecondary, '--home-primary': token.colorPrimary, '--home-tint': token.colorPrimaryBg,
    color: token.colorText} as CSSProperties;
  const actions = (buttons: React.ComponentProps<typeof TableActions>['buttons']) => <TableActions selectedKeys={[]} selectedRows={[]} buttons={buttons}/>;
  return <main className="sp-home" style={style} tabIndex={0} aria-label={t('menu.home', '首页')}>
    <header className="sp-home-welcome">
      <div><div className="sp-home-eyebrow">{t('home.workspace', '工作空间')} · {workspace}</div>
        <h1>{t('home.welcome', '欢迎回来')}{username ? `，${username}` : ''}</h1>
        <p>{t('home.subtitle', '从这里开始今天的工作，重要消息与常用应用触手可及。')}</p></div>
      {actions([{key: 'customize', sort: 0, icon: 'EditOutlined', text: t('home.customize', '自定义首页'),
        onClick: () => setEditing(true), disabled: !prefs.data || prefs.isError || prefs.isLoading}])}
    </header>
    <div className="sp-home-layout">
      <div className="sp-home-main">
        <PersonalOverview />
        <section className="sp-home-panel" aria-label={t('home.apps', '常用应用')}>
          <div className="sp-home-section-heading"><h2><AppstoreOutlined /> {t('home.apps', '常用应用')}</h2><span className="sp-home-muted">{favorites} / {apps.length}</span></div>
          {prefs.isError && <Alert type="error" showIcon title={t('home.loadError', '首页设置加载失败，为避免覆盖已有设置，请先重试。')}
            action={actions([{key: 'retryPreferences', sort: 0, size: 'small', text: t('action.retry', '重试'), onClick: () => void prefs.refetch()}])} />}
          {user.isError && <Alert type="error" title={t('home.userError', '账号信息加载失败')}
            action={actions([{key: 'retryUser', sort: 0, text: t('action.retry', '重试'), onClick: () => void user.refetch()}])} />}
          <Spin spinning={prefs.isLoading || user.isLoading} delay={200}>
            {!groups.length && !prefs.isError && !prefs.isLoading && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('home.empty', '把常用应用放在这里，按工作习惯自由分组。')}>
              {actions([{key: 'addApps', sort: 0, type: 'primary', disabled: !prefs.data,
                text: t('home.addApps', '添加常用应用'), onClick: () => setEditing(true)}])}</Empty>}
            {groups.map(group => <section className="sp-home-group" key={group.id}>
              <h3>{group.name}</h3>
              <div className="sp-home-apps">{group.paths.filter(path => appMap.has(path)).map(path => {
                const app = appMap.get(path)!;
                return <a key={path} href={`#${path}`} className="sp-home-app" onClick={event => {
                  if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
                  event.preventDefault(); navigate(path);
                }} onMouseEnter={() => prefetchLazyComponent(app.component, remoteRegistryKey)}
                  onFocus={() => prefetchLazyComponent(app.component, remoteRegistryKey)}
                  onMouseLeave={() => cancelPrefetchLazyComponent(app.component, remoteRegistryKey)}
                  onBlur={() => cancelPrefetchLazyComponent(app.component, remoteRegistryKey)}>
                  <span className="sp-home-app-icon">{createIcon(app.icon || 'AppstoreOutlined')}</span><span>{appLabel(app)}</span>
                </a>;
              })}</div>
              {!group.paths.some(path => appMap.has(path)) && <p className="sp-home-muted">{t('home.groupEmpty', '此分组暂无当前角色可访问的应用，请在自定义首页中添加。')}</p>}
            </section>)}
          </Spin>
        </section>
        <section className="sp-home-panel" aria-label={t('home.notifications', '通知与公告')}>
          <div className="sp-home-section-heading"><h2><BellOutlined /> {t('home.notifications', '通知与公告')} <Badge count={unread} /></h2>
            {actions([{key: 'readAll', sort: 0, size: 'small', disabled: !unread || busy, loading: busy,
              text: t('nav.markAllRead', '全部已读'), onClick: () => void markRead()},
              {key: 'allNotifications', sort: 1, size: 'small', type: 'link', text: t('home.viewAll', '查看全部'), onClick: () => setOpen(true)}])}</div>
          <NotificationList items={inbox.data?.content.slice(0, 5) || []} loading={inbox.isLoading} error={inbox.isError} active={selected}
            onRetry={() => void inbox.refetch()} />
        </section>
      </div>
      <aside className="sp-home-aside">
        <LocalContext region={prefs.data?.region} timeZone={prefs.data?.timeZone} />
        <section className="sp-home-panel"><h2>{t('home.quickActions', '快捷操作')}</h2>
          <div className="sp-home-actions">
            {actions([{key: 'search', sort: 0, icon: 'SearchOutlined', text: t('home.search', '搜索应用'), onClick: () => window.dispatchEvent(new Event('sp-open-search'))},
              {key: 'profile', sort: 1, icon: 'UserOutlined', text: t('menu.profile', '个人资料'), onClick: () => navigate('/profile')},
              {key: 'settings', sort: 2, icon: 'SettingOutlined', text: t('menu.settings', '系统设置'), onClick: () => navigate('/settings')}])}
          </div>
        </section>
      </aside>
    </div>
    {editing && prefs.data && <Suspense fallback={<Spin/>}><HomeEditor initial={prefs.data} options={apps.map(app => ({label: appLabel(app), value: app.path}))}
      saving={prefs.saving} onSave={prefs.save} onClose={() => setEditing(false)}/></Suspense>}
  </main>;
}
