import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import type {RJSFSchema} from '@rjsf/utils';
import {
  App as AntdApp,
  Alert,
  Avatar,
  Card,
  Col,
  Descriptions,
  Drawer,
  Empty,
  Modal,
  Row,
  Skeleton,
  Space,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import {ApartmentOutlined, MailOutlined, PhoneOutlined, UserOutlined} from '@simplepoint/shared/types/icon';
import {useQueryClient} from '@tanstack/react-query';
import {useNavigate} from 'react-router';
import SForm from '@simplepoint/components/SForm/Lazy';
import type {SFormHandle} from '@simplepoint/components/SForm';
import TableActions from '@simplepoint/components/Table/Actions';
import {usePageLocation, usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import {put} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {type CurrentTenantProfile, useCurrentTenantProfile, useCurrentTenants} from '@/fetches/tenants.ts';
import {useI18n} from '@/layouts/i18n/useI18n.ts';
import './index.css';
import {TenantResourceManager} from './TenantResourceManager.tsx';

type TenantProfileForm = Pick<CurrentTenantProfile, 'name' | 'description' | 'logo' | 'backgroundImage'>;

const displayValue = (value?: string | null) => value?.trim() || '-';

export const TenantHome: React.FC = () => {
  const scope = useQueryScope();
  return <TenantHomeSession key={JSON.stringify(scope)}/>;
};
const TenantHomeSession: React.FC = () => {
  const {message} = AntdApp.useApp();
  const {t} = useI18n();
  const pageLocation = usePageLocation(), selected = usePageSelected();
  const location = useMemo(() => new URL(pageLocation, window.location.origin), [pageLocation]);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const queryScope = useQueryScope();
  const tenantId = queryScope[0] ?? undefined;
  const [editOpen, setEditOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [section, setSection] = useState('overview');
  const [resourceBlocked, setResourceBlocked] = useState(false);
  const resourceBlockedRef = useRef(false), flight = useRef(false), alive = useRef(true), modeOpenRef = useRef(false);
  const onResourceBlocked = useCallback((value: boolean) => {resourceBlockedRef.current = value; setResourceBlocked(value);}, []);
  const [modeOpen, setModeOpen] = useState(false);
  const [modeTarget, setModeTarget] = useState<'PLATFORM' | 'CUSTOM'>('PLATFORM');
  const [modeError, setModeError] = useState<string>(), [profileError, setProfileError] = useState<string>();
  const editWarningShown = useRef(false);
  const form = useRef<SFormHandle>(null);
  const [draft, setDraft] = useState<TenantProfileForm>({name: ''});
  usePageRetention(!editOpen && !saving && !modeOpen && !resourceBlocked, editOpen || saving || modeOpen || resourceBlocked);
  useEffect(() => {alive.current = true; return () => {alive.current = false;};}, []);
  const {data: currentTenants, isLoading: tenantsLoading} = useCurrentTenants();
  const activeTenant = currentTenants?.find(tenant => tenant.tenantId === tenantId);
  const profileEnabled = !!tenantId && !!activeTenant && activeTenant.tenantType !== 'PLATFORM';
  const {data, isLoading, isError, refetch} = useCurrentTenantProfile(tenantId, profileEnabled);
  const wantsEdit = useMemo(
    () => new URLSearchParams(location.search).get('edit') === '1',
    [location.search],
  );

  useEffect(() => {
    setEditOpen(false);
    const requested = new URLSearchParams(location.search).get('section')
      ?? (location.pathname === '/tenant/settings' ? 'settings'
        : location.pathname === '/tenant/resources' ? 'resources' : null);
    setSection(requested === 'settings' || requested === 'resources' ? requested : 'overview');
    editWarningShown.current = false;
  }, [queryScope, location.pathname, location.search]);

  useEffect(() => {
    if (!data || !wantsEdit || editWarningShown.current) return;
    if (data.profileEditable) {
      editWarningShown.current = true;
      setDraft({
        name: data.name,
        description: data.description,
        logo: data.logo,
        backgroundImage: data.backgroundImage,
      });
      setEditOpen(true);
      return;
    }
    if (!editWarningShown.current) {
      editWarningShown.current = true;
      message.warning(t('tenant.profile.noPermission', '当前账号无权修改租户资料'));
    }
    navigate('/tenant', {replace: true});
  }, [data, message, navigate, t, wantsEdit]);

  const openEditor = () => {
    if (!data?.profileEditable || flight.current || modeOpenRef.current || resourceBlockedRef.current) return;
    setProfileError(undefined); setDraft({
      name: data.name,
      description: data.description,
      logo: data.logo,
      backgroundImage: data.backgroundImage,
    });
    setEditOpen(true);
  };

  const closeEditor = () => {
    if (flight.current) return;
    setEditOpen(false);
    if (wantsEdit) navigate('/tenant', {replace: true});
  };

  const saveProfile = async (values: TenantProfileForm) => {
    if (!data?.profileEditable || flight.current || modeOpenRef.current || resourceBlockedRef.current) return;
    flight.current = true; setSaving(true);
    setProfileError(undefined);
    try {
      const updated = await put<CurrentTenantProfile>(
        '/common/tenants/current-profile',
        values,
        {headers: tenantId ? {'X-Tenant-Id': tenantId} : undefined},
      );
      queryClient.setQueryData(scopedQueryKey(queryScope, ['common', 'tenants', 'current-profile', tenantId]), updated);
      await queryClient.invalidateQueries({queryKey: ['common', 'tenants', 'current']});
      if (!alive.current) return;
      try {
        Object.keys(sessionStorage)
          .filter(key => key.startsWith('sp.currentTenants'))
          .forEach(key => sessionStorage.removeItem(key));
      } catch {
        // Session cache is best-effort only.
      }
      window.dispatchEvent(new CustomEvent('sp-tenant-profile-updated', {detail: updated}));
      message.success(t('tenant.profile.saved', '租户资料已保存'));
      setEditOpen(false);
      navigate('/tenant', {replace: true});
    } catch (error) {
      if (alive.current) setProfileError(resolveApiErrorMessage(error, t('tenant.profile.saveFailed', '租户资料保存失败')));
    } finally {
      flight.current = false; if (alive.current) setSaving(false);
    }
  };

  const selectResourceMode = (mode: 'PLATFORM' | 'CUSTOM') => {
    if (!data?.profileEditable || flight.current || modeOpenRef.current || resourceBlockedRef.current || editOpen
        || mode === (data?.resourceMode ?? 'PLATFORM')) return;
    modeOpenRef.current = true; setModeTarget(mode); setModeError(undefined); setModeOpen(true);
  };
  const closeMode = () => {if (!flight.current) {modeOpenRef.current = false; setModeOpen(false);}};
  const saveMode = async () => {
    if (!alive.current || flight.current || !modeOpenRef.current || !data?.profileEditable) return;
    flight.current = true; setSaving(true); setModeError(undefined);
    try {
      await put('/common/tenants/current-resources/mode', {mode: modeTarget},
        {headers: tenantId ? {'X-Tenant-Id': tenantId} : undefined});
      await refetch();
      if (!alive.current) return;
      message.success(t('tenant.settings.modeSaved', '资源加载类型已更新')); window.location.reload();
    } catch (error) {if (alive.current) setModeError(resolveApiErrorMessage(error, t('tenant.settings.modeFailed', '资源加载类型切换失败')));}
    finally {flight.current = false; if (alive.current) setSaving(false);}
  };
  const blocked = saving || editOpen || modeOpen || resourceBlocked;
  const actions = (buttons: React.ComponentProps<typeof TableActions>['buttons']) =>
    <TableActions selectedKeys={[]} selectedRows={[]} buttons={buttons}/>;
  const editActions = () => actions([{key: 'editTenant', sort: 0, type: 'primary', icon: 'EditOutlined', disabled: blocked,
    text: t('tenant.profile.edit', '编辑租户资料'), onClick: openEditor}]);
  const schema = useMemo<RJSFSchema>(() => ({type: 'object', additionalProperties: false, required: ['name'], properties: {
    name: {type: 'string' as const, title: t('tenant.profile.name', '租户名称'), minLength: 1, maxLength: 128, pattern: '\\S'},
    description: {type: ['string', 'null'], title: t('tenant.profile.description', '租户简介'), maxLength: 512},
    logo: {type: ['string', 'null'], title: t('tenant.profile.logo', '租户 Logo'), maxLength: 1024},
    backgroundImage: {type: ['string', 'null'], title: t('tenant.profile.background', '租户背景图片'), maxLength: 1024},
  }}), [t]);

  if (activeTenant?.tenantType === 'PLATFORM') {
    return (
      <div className="tenant-home-page tenant-home-empty">
        <Empty description={t('tenant.profile.platformUnavailable', '平台工作空间不提供租户资料，请切换到具体租户')}/>
      </div>
    );
  }

  if (tenantsLoading || isLoading || (!!tenantId && !activeTenant)) {
    return <div className="tenant-home-page"><Skeleton active avatar paragraph={{rows: 8}}/></div>;
  }

  if (isError || !data) {
    return (
      <div className="tenant-home-page tenant-home-empty">
        <Empty description={t('tenant.profile.unavailable', '当前工作空间没有可访问的租户资料')}>
          {actions([{key: 'retry', sort: 0, icon: 'ReloadOutlined', text: t('action.retry', '重试'), onClick: () => void refetch()}])}
        </Empty>
      </div>
    );
  }

  const tenantTypeLabel = data.tenantType === 'PERSONAL'
    ? t('tenant.type.personal', '个人租户')
    : t('tenant.type.organization', '组织租户');

  return (
    <div className="tenant-home-page">
      <section
        className={`tenant-home-hero${data.backgroundImage ? ' has-background' : ''}`}
        style={data.backgroundImage ? {backgroundImage: `linear-gradient(100deg, rgba(6,18,38,.86), rgba(13,78,120,.58)), url(${JSON.stringify(data.backgroundImage)})`} : undefined}
      >
        <div className="tenant-home-identity">
          <Avatar
            shape="square"
            size={76}
            src={data.logo || '/svg.svg'}
            icon={!data.logo ? <ApartmentOutlined/> : undefined}
            className="tenant-home-logo"
          />
          <div className="tenant-home-copy">
            <Space size={8} wrap>
              <Typography.Title level={2}>{data.name}</Typography.Title>
              <Tag color={data.tenantType === 'PERSONAL' ? 'blue' : 'green'}>{tenantTypeLabel}</Tag>
            </Space>
            <Typography.Paragraph>{data.description || t('tenant.profile.noDescription', '暂未填写租户简介')}</Typography.Paragraph>
          </div>
        </div>
        <Space className="tenant-home-actions" wrap>
          {actions([{key: 'refresh', sort: 0, icon: 'ReloadOutlined', text: t('action.refresh', '刷新'), disabled: blocked, onClick: () => void refetch()}])}
          {data.profileEditable ? editActions() : null}
        </Space>
      </section>

      <Tabs activeKey={section} onChange={next => {
        if (blocked || resourceBlockedRef.current || flight.current) return;
        setSection(next);
        navigate(next === 'settings' ? '/tenant/settings'
          : next === 'resources' ? '/tenant/resources' : '/tenant');
      }} items={[
        {key: 'overview', disabled: blocked, label: t('tenant.settings.overview', '空间概览')},
        ...(data.profileEditable ? [{key: 'settings', disabled: blocked, label: t('tenant.settings.title', '空间设置')}] : []),
        ...(data.profileEditable && data.tenantType === 'ORGANIZATION' ? [{key: 'resources',
          disabled: blocked,
          label: t('tenant.resources.title', '租户资源管理')}] : []),
      ]}/>

      {section === 'overview' && <Row gutter={[16, 16]}>
        <Col xs={24} xl={16}>
          <Card className="tenant-home-card" title={t('tenant.profile.about', '租户信息')}>
            <Descriptions column={{xs: 1, sm: 2}}>
              <Descriptions.Item label={t('tenant.profile.name', '租户名称')}>{data.name}</Descriptions.Item>
              <Descriptions.Item label={t('tenant.profile.type', '租户类型')}>{tenantTypeLabel}</Descriptions.Item>
              <Descriptions.Item label={t('tenant.profile.description', '租户简介')} span={2}>
                {displayValue(data.description)}
              </Descriptions.Item>
            </Descriptions>
          </Card>
        </Col>
        <Col xs={24} xl={8}>
          <Card className="tenant-home-card" title={t('tenant.profile.owner', '租户管理员')}>
            <div className="tenant-owner-card">
              <Avatar size={58} src={data.ownerPicture} icon={!data.ownerPicture ? <UserOutlined/> : undefined}/>
              <div className="tenant-owner-primary">
                <Typography.Text strong>{displayValue(data.ownerName)}</Typography.Text>
                <Typography.Text type="secondary">{displayValue(data.ownerGender)}</Typography.Text>
              </div>
            </div>
            <div className="tenant-owner-contact"><PhoneOutlined/> {displayValue(data.ownerPhoneNumber)}</div>
            <div className="tenant-owner-contact"><MailOutlined/> {displayValue(data.ownerEmail)}</div>
          </Card>
        </Col>
      </Row>}

      {section === 'settings' && data.profileEditable && <Card className="tenant-home-card" title={t('tenant.settings.title', '空间设置')}>
        <Descriptions column={{xs: 1, sm: 2}}>
          <Descriptions.Item label={t('tenant.profile.name', '租户名称')}>{data.name}</Descriptions.Item>
          <Descriptions.Item label={t('tenant.profile.type', '租户类型')}>{tenantTypeLabel}</Descriptions.Item>
          <Descriptions.Item label={t('tenant.profile.description', '租户简介')} span={2}>
            {displayValue(data.description)}
          </Descriptions.Item>
        </Descriptions>
        {data.profileEditable && editActions()}
        {data.tenantType === 'ORGANIZATION' && <div className="tenant-resource-mode">
          <Typography.Title level={5}>{t('tenant.settings.resourceMode', '资源加载类型')}</Typography.Title>
          <Typography.Paragraph type="secondary">
            {t('tenant.settings.modeDescription', '默认使用平台内置资源；启用自定义后，本空间的菜单和角色授权以租户目录为准。')}
          </Typography.Paragraph>
          {actions([{key: 'platformMode', sort: 0, type: (data.resourceMode ?? 'PLATFORM') === 'PLATFORM' ? 'primary' : 'default',
            text: t('tenant.settings.platform', '平台内置'), disabled: blocked, onClick: () => selectResourceMode('PLATFORM')},
            {key: 'customMode', sort: 1, type: data.resourceMode === 'CUSTOM' ? 'primary' : 'default',
              text: t('tenant.settings.custom', '租户自定义'), disabled: blocked, onClick: () => selectResourceMode('CUSTOM')}])}
        </div>}
      </Card>}

      {section === 'resources' && data.profileEditable && data.tenantType === 'ORGANIZATION' && (data.resourceMode === 'CUSTOM'
        ? <TenantResourceManager tenantId={tenantId!} onBlockedChange={onResourceBlocked}/>
        : <Card><Empty description={t('tenant.resources.disabled', '请先在空间设置中启用租户自定义资源')}>
          {data.profileEditable && actions([{key: 'openSettings', sort: 0, type: 'primary', text: t('tenant.settings.title', '空间设置'),
            onClick: () => navigate('/tenant/settings')}])}
        </Empty></Card>)}

      <Drawer
        title={t('tenant.profile.edit', '编辑租户资料')}
        open={editOpen && selected}
        width={560}
        destroyOnHidden
        maskClosable={!saving}
        closable={!saving}
        keyboard={!saving}
        onClose={closeEditor}
        extra={actions([{key: 'saveTenant', sort: 0, type: 'primary', loading: saving, disabled: saving,
          text: t('action.save', '保存'), onClick: () => {if (!flight.current) form.current?.submit();}}])}
      >
        {profileError && <Alert type="error" showIcon title={profileError}/>}
        <SForm ref={form} hideSubmit schema={schema} disabled={saving} formData={draft}
          uiSchema={{description: {'ui:widget': 'textarea'}, logo: {'ui:widget': 'OssImage', 'ui:options': {
            directory: 'tenants/logos', sourceServiceName: 'tenant-branding', shape: 'square', maxSizeMb: 5}},
            backgroundImage: {'ui:widget': 'OssImage', 'ui:options': {directory: 'tenants/backgrounds',
              sourceServiceName: 'tenant-branding', shape: 'square', maxSizeMb: 10}}}}
          onChange={event => setDraft(event.formData ?? {name: ''})} onSubmit={event => void saveProfile(event.formData ?? {name: ''})}/>
      </Drawer>
      <Modal wrapClassName="tenant-mode-confirmation" open={modeOpen && selected} onCancel={closeMode} onOk={() => void saveMode()}
        destroyOnHidden closable={!saving} maskClosable={!saving} keyboard={!saving} okButtonProps={{loading: saving, disabled: saving}}
        title={modeTarget === 'CUSTOM' ? t('tenant.settings.enableCustom', '启用租户自定义资源') : t('tenant.settings.usePlatform', '改用平台内置资源')}>
        {modeError && <Alert type="error" showIcon title={modeError}/>}
        {modeTarget === 'CUSTOM'
          ? t('tenant.settings.enableHint', '首次开启会自动拉取当前租户获授权的平台资源。之后角色授权与菜单会使用租户资源目录。')
          : t('tenant.settings.platformHint', '租户自定义目录及角色授权会保留，切回平台模式后暂不生效。')}
      </Modal>
    </div>
  );
};

export default TenantHome;
