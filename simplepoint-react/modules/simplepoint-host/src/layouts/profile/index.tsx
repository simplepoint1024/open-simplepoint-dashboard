import {formatDateTime} from '@simplepoint/shared/format/temporal';
import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {App, Avatar, Descriptions, Divider, Empty, Input, Skeleton, Space, Tag, Typography} from 'antd';
import type {WidgetProps} from '@rjsf/utils';
import {ApartmentOutlined, IdcardOutlined, LockOutlined, LinkOutlined, RightOutlined,
  SafetyCertificateOutlined, UserOutlined} from '@simplepoint/shared/types/icon';
import {useQueryClient} from '@tanstack/react-query';
import SForm from '@simplepoint/components/SForm/Lazy';
import type {SFormHandle} from '@simplepoint/components/SForm';
import TableActions from '@simplepoint/components/Table/Actions';
import {OssImageUpload} from '@simplepoint/components/SForm/widgets/OssImageUpload';
import {post, put} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {userDisplayName, userFullName} from '@simplepoint/shared/utils/userIdentity';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {useI18n} from '@/layouts/i18n/useI18n';
import {type CurrentProfile, useCurrentProfile, useUserInfo} from '@/fetches/user';
import {ProfileAccessSection, ProfileOrganizationSection} from './ProfileSections';
import {passwordSchema, profileDraft, profilePayload, profileSchema, type PasswordDraft, type ProfileDraft} from './forms';
import './index.css';

// The theme widget omits autocomplete and maxLength; retain both in a specialized platform-form widget.
function ProfilePasswordWidget({id, value, disabled, readonly, schema, options, onChange, onBlur, onFocus}: WidgetProps) {
  return <Input.Password id={id} name={id} value={value ?? ''} disabled={disabled || readonly}
    maxLength={schema.maxLength} autoComplete={options.autocomplete as string}
    onChange={event => onChange(event.target.value)} onBlur={event => onBlur(id, event.target.value)}
    onFocus={event => onFocus(id, event.target.value)}/>;
}
const passwordWidgets = {ProfilePassword: ProfilePasswordWidget};
const passwordUi = {
  currentPassword: {'ui:widget': 'ProfilePassword', 'ui:options': {autocomplete: 'current-password'}},
  newPassword: {'ui:widget': 'ProfilePassword', 'ui:options': {autocomplete: 'new-password'}},
  confirmPassword: {'ui:widget': 'ProfilePassword', 'ui:options': {autocomplete: 'new-password'}},
};
const detailUi = {birthdate: {'ui:widget': 'date'}, profile: {'ui:widget': 'textarea'}};
const formatDate = (value?: string | null) => value ? formatDateTime(value) : '-';

export const Profile: React.FC = () => {
  const {message} = App.useApp();
  const {t, ensure, locale, languages} = useI18n();
  const queryClient = useQueryClient();
  const {data, isLoading, refetch} = useCurrentProfile();
  const {data: userInfo, refetch: refetchUserInfo} = useUserInfo();
  const [section, setSection] = useState<'about' | 'access' | 'organization' | 'security'>('about');
  const [draft, setDraft] = useState<ProfileDraft | null>(null);
  const [detailDirty, setDetailDirty] = useState(false);
  const [password, setPassword] = useState<PasswordDraft>({});
  const [busy, setBusy] = useState<'details' | 'avatar' | 'password' | null>(null);
  const busyRef = useRef<typeof busy>(null), dirtyRef = useRef(false), alive = useRef(true);
  const detailForm = useRef<SFormHandle>(null), pwdForm = useRef<SFormHandle>(null);
  const [securityLinks, setSecurityLinks] = useState<{twoFactor: string; externalIdentities: string} | null>(null);
  usePageRetention(false, detailDirty || Object.values(password).some(Boolean) || !!busy);
  useEffect(() => {alive.current = true; return () => {alive.current = false;};}, []);
  useEffect(() => {void ensure(['profile', 'resources']);}, [ensure, locale]);
  useEffect(() => {if (data && !dirtyRef.current) setDraft(profileDraft(data));}, [data]);
  useEffect(() => {
    if (section !== 'security') return;
    const controller = new AbortController();
    void fetch('/account/security-links', {credentials: 'same-origin', signal: controller.signal})
      .then(response => {if (!response.ok) throw new Error('security links unavailable'); return response.json();})
      .then(links => {if (!controller.signal.aborted) setSecurityLinks(links);})
      .catch(error => {if (error.name !== 'AbortError') setSecurityLinks(null);});
    return () => controller.abort();
  }, [section]);
  const syncProfile = useCallback((updated: CurrentProfile) => {
    queryClient.setQueryData(['current-profile'], updated);
    queryClient.setQueryData(['userinfo'], (current: any) => ({...(current || {}), ...updated,
      phone: updated.phoneNumber ?? current?.phone}));
    window.dispatchEvent(new CustomEvent('sp-user-profile-updated', {detail: updated}));
  }, [queryClient]);
  useEffect(() => {
    const handleFocus = () => {void refetch(); void refetchUserInfo();};
    const handleVisibility = () => {if (document.visibilityState === 'visible') handleFocus();};
    window.addEventListener('focus', handleFocus); document.addEventListener('visibilitychange', handleVisibility);
    return () => {window.removeEventListener('focus', handleFocus); document.removeEventListener('visibilitychange', handleVisibility);};
  }, [refetch, refetchUserInfo]);
  const roles = useMemo(() => Array.isArray(userInfo?.roles)
    ? userInfo.roles.map((role: any) => typeof role === 'string' ? role : role?.name).filter(Boolean) as string[] : [], [userInfo]);
  const detailsSchema = useMemo(() => profileSchema(t, draft ?? {}, languages), [t, draft?.gender, draft?.locale, draft?.zoneinfo, languages]);
  const pwdSchema = useMemo(() => passwordSchema(t), [t]);
  const displayName = userDisplayName(data) || t('user.defaultName', '用户');
  const start = (kind: NonNullable<typeof busy>) => {if (busyRef.current) return false; busyRef.current = kind; setBusy(kind); return true;};
  const finish = () => {busyRef.current = null; if (alive.current) setBusy(null);};
  const saveDetails = async (values: ProfileDraft) => {
    if (!data || !start('details')) return;
    try {
      const updated = await put<CurrentProfile>('/common/users/me', profilePayload(values, data)); syncProfile(updated);
      if (alive.current) {dirtyRef.current = false; setDetailDirty(false); setDraft(profileDraft(updated));
        message.success(t('profile.saveSuccess', '个人资料已保存'));}
    } catch (error) {if (alive.current) message.error(resolveApiErrorMessage(error, t('profile.saveFailed', '个人资料保存失败')));}
    finally {finish();}
  };
  const saveAvatar = async (picture?: string) => {
    if (!data || !start('avatar')) throw new Error(t('error.operationFailed', '操作失败，请重试'));
    try {syncProfile(await put<CurrentProfile>('/common/users/me/picture', {picture: picture || null}));}
    finally {finish();}
  };
  const changePassword = async (values: PasswordDraft) => {
    if (!start('password')) return;
    try {await post('/common/users/change-password', values);
      if (alive.current) {setPassword({}); message.success(t('profile.passwordChanged', '密码修改成功'));}
    } catch (error) {if (alive.current) message.error(resolveApiErrorMessage(error, t('profile.passwordChangeFailed', '密码修改失败')));}
    finally {finish();}
  };
  const actions = (buttons: React.ComponentProps<typeof TableActions>['buttons']) =>
    <TableActions selectedKeys={[]} selectedRows={[]} buttons={buttons}/>;
  return <div className="profile-page"><div className="profile-shell">
    <aside className="profile-navigation" aria-label={t('profile.nav.title', '个人资料目录')}>
      <div className="profile-nav-identity">
        <Avatar size={72} src={data?.picture || undefined} icon={!data?.picture ? <UserOutlined/> : undefined}/>
        <div className="profile-nav-info"><strong>{displayName}</strong><small>{data?.email || t('profile.noEmail', '未绑定邮箱')}</small>
          <div className="profile-nav-avatar-actions"><OssImageUpload value={data?.picture} onChange={saveAvatar}
            disabled={!!busy || !data} directory="avatars/users" sourceServiceName="rbac-avatar" shape="circle" maxSizeMb={5}
            replaceLabel={t('profile.avatarChange', '更换头像')}/></div>
        </div>
      </div>
      <div className="profile-nav-bio">{data?.profile || t('profile.about.noBio', '还没有填写个人简介。')}</div>
      {!!roles.length && <div className="profile-nav-roles">{roles.map(role => <Tag key={role} color="blue">{role}</Tag>)}</div>}
      <div className="profile-nav-label">{t('profile.nav.title', '个人资料目录')}</div>
      <nav className="profile-nav-links">{([
        ['about', <UserOutlined/>, t('profile.nav.about', '关于我')], ['access', <SafetyCertificateOutlined/>, t('profile.nav.access', '资源权限')],
        ['organization', <ApartmentOutlined/>, t('profile.nav.organization', '所在组织')], ['security', <LockOutlined/>, t('profile.nav.security', '账户安全')],
      ] as const).map(([key, icon, label]) => <button key={key} type="button" className={section === key ? 'active' : ''}
        aria-current={section === key ? 'page' : undefined} onClick={() => setSection(key)}><span>{icon}</span>{label}<RightOutlined/></button>)}</nav>
    </aside>
    <main className="profile-main" aria-label={t('profile.nav.title', '个人资料目录')}>
      {section === 'about' && <section className="profile-content-section" aria-label={t('profile.nav.about', '关于我')}>
        <div className="profile-content-group">
          <div className="profile-group-heading"><h3><IdcardOutlined/> {t('profile.basic', '基本信息')}</h3>
            {actions([{key: 'refresh', sort: 0, icon: 'ReloadOutlined', text: t('action.refresh', '刷新'), disabled: !!busy, onClick: () => void refetch()}])}</div>
          {isLoading ? <Skeleton active paragraph={{rows: 7}}/> : !data ? <Empty/> :
            <Descriptions column={{xs: 1, sm: 2}} size="middle" className="profile-descriptions">
              <Descriptions.Item label={t('field.nickname', '昵称')}>{data.nickname || '-'}</Descriptions.Item>
              <Descriptions.Item label={t('field.fullName', '姓名')}>{userFullName(data) || '-'}</Descriptions.Item>
              <Descriptions.Item label={t('field.email', '邮箱')}>{data.email || '-'}</Descriptions.Item>
              <Descriptions.Item label={t('field.phone', '手机号')}>{data.phoneNumber || '-'}</Descriptions.Item>
              <Descriptions.Item label={t('field.profile', '个人简介')} span={2}>{data.profile || '-'}</Descriptions.Item>
              <Descriptions.Item label={t('field.joinedAt', '加入时间')}>{formatDate(data.createdAt)}</Descriptions.Item>
              <Descriptions.Item label={t('field.updatedAt', '最近更新')}>{formatDate(data.updatedAt)}</Descriptions.Item>
            </Descriptions>}
        </div><Divider/>
        <div className="profile-content-group"><h3 className="profile-group-title"><IdcardOutlined/> {t('profile.details.title', '详细资料')}</h3>
          {draft && data ? <SForm ref={detailForm} className="profile-detail-form" hideSubmit schema={detailsSchema} uiSchema={detailUi}
            disabled={!!busy || !data} formData={draft} onChange={event => {
              const next = event.formData ?? {}; setDraft(next);
              dirtyRef.current = !!data && JSON.stringify(profilePayload(next, data)) !== JSON.stringify(profilePayload(profileDraft(data), data));
              setDetailDirty(dirtyRef.current);
            }} onSubmit={event => void saveDetails(event.formData ?? {})}/> : <Skeleton active paragraph={{rows: 6}}/>}
          <div className="profile-detail-actions">{actions([{key: 'saveDetails', sort: 0, type: 'primary', icon: 'SaveOutlined',
            text: t('action.save', '保存'), disabled: !detailDirty || !data || !!busy, loading: busy === 'details',
            onClick: () => {if (!busyRef.current) detailForm.current?.submit();}}])}</div>
        </div>
      </section>}
      {section === 'access' && <ProfileAccessSection/>}
      {section === 'organization' && <ProfileOrganizationSection/>}
      {section === 'security' && <section className="profile-content-section" aria-label={t('profile.nav.security', '账户安全')}>
        <div className="profile-content-group"><h3 className="profile-group-title">{t('profile.security.methods', '登录与验证方式')}</h3>
          <div className="profile-security-row"><div className={`profile-security-icon ${data?.twoFactorEnabled ? 'enabled' : ''}`}><SafetyCertificateOutlined/></div>
            <div className="profile-security-copy"><Space size={8} wrap><Typography.Title level={5}>{t('field.twoFactorEnabled', '两步认证')}</Typography.Title>
              <Tag color={data?.twoFactorEnabled ? 'success' : 'default'}>{data?.twoFactorEnabled ? t('profile.twoFactor.enabled', '已开启') : t('profile.twoFactor.disabled', '未开启')}</Tag>
            </Space><Typography.Paragraph>{t('profile.twoFactor.description', '使用动态验证码为账号增加一层登录保护。')}</Typography.Paragraph></div>
            {actions([{key: 'twoFactor', sort: 0, type: 'link', icon: 'RightOutlined', iconPosition: 'end', disabled: !securityLinks,
              href: securityLinks?.twoFactor, target: '_blank', rel: 'noopener noreferrer', onClick: () => {},
              text: data?.twoFactorEnabled ? t('action.manageTwoFactor', '管理两步认证') : t('action.enableTwoFactor', '开启两步认证')}])}
          </div><Divider className="profile-security-divider"/>
          <div className="profile-security-row"><div className="profile-security-icon external"><LinkOutlined/></div>
            <div className="profile-security-copy"><Typography.Title level={5}>{t('profile.externalAccounts.title', '第三方账号绑定')}</Typography.Title>
              <Typography.Paragraph>{t('profile.externalAccounts.description', '绑定 GitHub、Google 等身份提供商，用于安全快捷登录。')}</Typography.Paragraph></div>
            {actions([{key: 'externalIdentities', sort: 0, type: 'link', icon: 'RightOutlined', iconPosition: 'end', disabled: !securityLinks,
              href: securityLinks?.externalIdentities, target: '_blank', rel: 'noopener noreferrer', onClick: () => {},
              text: t('profile.externalAccounts.manage', '管理第三方账号')}])}
          </div>
        </div><Divider/>
        <div className="profile-content-group"><h3 className="profile-group-title"><LockOutlined/> {t('profile.changePassword', '修改密码')}</h3>
          <SForm ref={pwdForm} className="profile-password-form" hideSubmit schema={pwdSchema} uiSchema={passwordUi}
            widgets={passwordWidgets} disabled={!!busy} formData={password} onChange={event => setPassword(event.formData ?? {})}
            customValidate={(values, errors) => {if (values.newPassword !== values.confirmPassword)
              errors.confirmPassword?.addError(t('rule.passwordMismatch', '两次输入的密码不一致')); return errors;}}
            onSubmit={event => void changePassword(event.formData ?? {})}/>
          {actions([{key: 'changePassword', sort: 0, type: 'primary', icon: 'LockOutlined', text: t('action.changePassword', '修改密码'),
            loading: busy === 'password', disabled: !!busy, onClick: () => {if (!busyRef.current) pwdForm.current?.submit();}}])}
        </div>
      </section>}
    </main>
  </div></div>;
};
