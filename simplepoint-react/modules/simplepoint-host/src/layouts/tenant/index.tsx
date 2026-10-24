import {useEffect, useMemo, useRef, useState} from 'react';
import {Alert, App, Avatar, Descriptions, Divider} from 'antd';
import {ApartmentOutlined, IdcardOutlined, MailOutlined, PhoneOutlined, SkinOutlined, SettingOutlined, UserOutlined} from '@simplepoint/shared/types/icon';
import {Navigate} from 'react-router';
import {legacyTenantTarget} from './legacyLocation';
import {useQueryClient} from '@tanstack/react-query';
import SettingsLayout from '@simplepoint/components/SettingsLayout';
import SForm from '@simplepoint/components/SForm/Lazy';
import type {SFormHandle} from '@simplepoint/components/SForm';
import TableActions from '@simplepoint/components/Table/Actions';
import {TenantMark, TenantTypeTag} from '@simplepoint/components/TenantSelect/Identity';
import {usePageLocation, usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {put} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {CurrentTenantProfile} from '@/fetches/tenants';
import TenantPage from './TenantPage';
import {profileDraft, profileSchema, type TenantProfileDraft} from './forms';
import './index.css';

type Section = 'overview' | 'details' | 'appearance' | 'owner';
const appearanceUi = {logo: {'ui:widget': 'OssImage', 'ui:options': {directory: 'tenants/logos', sourceServiceName: 'tenant-branding', shape: 'square', maxSizeMb: 5}},
  backgroundImage: {'ui:widget': 'OssImage', 'ui:options': {directory: 'tenants/backgrounds', sourceServiceName: 'tenant-branding', shape: 'square', maxSizeMb: 10}}};

/** Workspace profile/settings share the account settings layout, never a second set of business tabs. */
export function TenantHome() {
  const scope = useQueryScope(), location = usePageLocation(), selected = usePageSelected();
  const legacy = legacyTenantTarget(location);
  if (legacy) return selected ? <Navigate replace to={legacy}/> : null;
  return <TenantPage>{profile => <TenantProfile key={JSON.stringify(scope)} profile={profile}/>}</TenantPage>;
}

function TenantProfile({profile}: {profile: CurrentTenantProfile}) {
  const {t} = useI18n(), {message, modal} = App.useApp();
  const location = usePageLocation(), scope = useQueryScope();
  const queryClient = useQueryClient(), operation = useOperationScope(profile.id);
  const [section, setSection] = useState<Section>(() => profile.profileEditable && location.includes('/tenant/settings') ? 'details' : 'overview');
  const [draft, setDraft] = useState<TenantProfileDraft>(() => profileDraft(profile));
  const [error, setError] = useState('');
  const form = useRef<SFormHandle>(null), feedback = useRef<HTMLDivElement>(null);
  const dirty = JSON.stringify(draft) !== JSON.stringify(profileDraft(profile));
  usePageRetention(!dirty && !operation.busy, dirty || operation.busy);
  useEffect(() => {
    // A route restored from a legacy tab is resolved by the router. Local settings navigation retains its draft.
    if (!dirty && !operation.busy) setDraft(profileDraft(profile));
  }, [profile]);
  useEffect(() => {if (error && operation.selected) feedback.current?.scrollIntoView({block: 'nearest'});}, [error, operation.selected]);
  const appearance = section === 'appearance';
  const schema = useMemo(() => profileSchema(t, appearance), [t, appearance]);
  const values = appearance ? {logo: draft.logo, backgroundImage: draft.backgroundImage} : {name: draft.name, description: draft.description};
  const canWrite = profile.profileEditable && operation.selected && !operation.busy;
  const save = async (values: Partial<TenantProfileDraft>) => {
    if (!profile.profileEditable) return;
    const request = operation.begin(); if (!request) return;
    setError('');
    try {
      const updated = await put<CurrentTenantProfile>('/common/tenants/current-profile', structuredClone({...draft, ...values}), {signal: request.signal});
      if (!operation.current(request.signal)) return;
      setDraft(profileDraft(updated));
      queryClient.setQueryData(scopedQueryKey(scope, ['common', 'tenants', 'current-profile', profile.id]), updated);
      // Invalidate the account's workspace directory after a name/logo change without losing unsaved state on failure.
      try {Object.keys(sessionStorage).filter(key => key.startsWith('sp.currentTenants')).forEach(key => sessionStorage.removeItem(key));} catch { /* Optional cache. */ }
      await queryClient.invalidateQueries({queryKey: ['common', 'tenants', 'current']});
      if (operation.current(request.signal)) {
        window.dispatchEvent(new CustomEvent('sp-tenant-profile-updated', {detail: updated}));
        void message.success(t('tenant.profile.saved', '空间资料已保存'));
      }
    } catch (failure) {
      if (operation.current(request.signal)) setError(resolveApiErrorMessage(failure, t('tenant.profile.saveFailed', '空间资料保存失败')));
    } finally {operation.finish(request);}
  };
  const refresh = () => {
    if (operation.isBusy() || !operation.isSelected()) return;
    void queryClient.invalidateQueries({queryKey: scopedQueryKey(scope, ['common', 'tenants', 'current-profile', profile.id])});
  };
  const reset = () => {
    if (!canWrite) return;
    modal.confirm({title: t('tenant.settings.discard', '放弃未保存的空间资料修改？'), onOk: () => {
      if (!operation.current() || operation.isBusy()) return;
      setDraft(profileDraft(profile)); setError('');
    }});
  };
  const sections = [
    {key: 'overview' as const, label: t('tenant.settings.overview', '空间概览'), icon: <ApartmentOutlined/>},
    ...(profile.profileEditable ? [
      {key: 'details' as const, label: t('tenant.settings.basic', '基本资料'), icon: <SettingOutlined/>},
      {key: 'appearance' as const, label: t('tenant.settings.appearance', '标识与外观'), icon: <SkinOutlined/>},
    ] : []),
    {key: 'owner' as const, label: t('tenant.profile.owner', '空间负责人'), icon: <UserOutlined/>},
  ];
  const active = sections.some(item => item.key === section) ? section : 'overview';
  return <SettingsLayout label={t('tenant.settings.title', '空间设置')} className="tenant-settings-page" activeKey={active}
    sections={sections.map(item => ({...item, disabled: operation.busy}))} onChange={next => {
      if (!operation.isSelected() || operation.isBusy()) return;
      setError(''); setSection(next);
    }} identity={<div className="tenant-settings-identity">
      <TenantMark type={profile.tenantType} logo={profile.logo} size={64}/>
      <strong>{profile.name}</strong><TenantTypeTag type={profile.tenantType}/>
      <p>{profile.description || t('tenant.profile.noDescription', '暂未填写空间简介')}</p>
    </div>}>
    <div className="tenant-settings-heading"><h3>{sections.find(item => item.key === active)?.icon}{sections.find(item => item.key === active)?.label}</h3>
      <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'refresh', sort: 0, icon: 'ReloadOutlined',
        text: t('action.refresh', '刷新'), disabled: dirty || operation.busy, onClick: refresh}]}/>
    </div>
    {active === 'overview' && <><Descriptions column={{xs: 1, md: 2}}>
      <Descriptions.Item label={t('tenant.profile.name', '空间名称')}>{profile.name}</Descriptions.Item>
      <Descriptions.Item label={t('tenant.profile.type', '空间类型')}><TenantTypeTag type={profile.tenantType}/></Descriptions.Item>
      <Descriptions.Item label={t('tenant.profile.description', '空间简介')} span={2}>{profile.description || '—'}</Descriptions.Item>
    </Descriptions><Divider/>
      {profile.profileEditable && <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'edit', sort: 0, icon: 'EditOutlined',
        text: t('tenant.profile.edit', '编辑空间资料'), onClick: () => setSection('details')}]}/>}
    </>}
    {(active === 'details' || active === 'appearance') && profile.profileEditable && <>
      <SForm key={active} ref={form} hideSubmit schema={schema} uiSchema={appearance ? appearanceUi : {description: {'ui:widget': 'textarea'}}}
        disabled={!canWrite} formData={values} onChange={event => {if (canWrite) setDraft(current => ({...current, ...event.formData}));}}
        onError={() => setError(t('tenant.settings.validationFailed', '请检查标记的字段，修正后再保存。'))} onSubmit={event => void save(event.formData ?? {})}/>
      {error && <div ref={feedback} role="alert"><Alert type="error" showIcon title={error}/></div>}
      <div className="tenant-settings-actions"><TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'reset', sort: 0, icon: 'UndoOutlined', disabled: !dirty || !canWrite, text: t('action.reset', '重置'), onClick: reset},
        {key: 'save', sort: 1, icon: 'SaveOutlined', type: 'primary', disabled: !dirty || !canWrite, loading: operation.busy,
          text: t('action.save', '保存'), onClick: () => {if (canWrite && !form.current?.submit()) setError(t('tenant.settings.formPending', '请等待图片上传或表单加载完成后再保存。'));}},
      ]}/></div>
    </>}
    {active === 'owner' && <div className="tenant-owner-details">
      <Avatar size={64} src={profile.ownerPicture} icon={<UserOutlined/>}/>
      <Descriptions column={1}>
        <Descriptions.Item label={<><IdcardOutlined/> {t('tenant.profile.owner', '空间负责人')}</>}>{profile.ownerName || '—'}</Descriptions.Item>
        <Descriptions.Item label={<><PhoneOutlined/> {t('field.phone', '手机号')}</>}>{profile.ownerPhoneNumber || '—'}</Descriptions.Item>
        <Descriptions.Item label={<><MailOutlined/> {t('field.email', '邮箱')}</>}>{profile.ownerEmail || '—'}</Descriptions.Item>
      </Descriptions>
    </div>}
  </SettingsLayout>;
}

export default TenantHome;
