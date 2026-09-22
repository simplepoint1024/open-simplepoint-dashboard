import React, {useEffect, useMemo, useRef, useState} from 'react';
import {
  App as AntdApp,
  Avatar,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  Row,
  Skeleton,
  Space,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import {ApartmentOutlined, EditOutlined, MailOutlined, PhoneOutlined, ReloadOutlined, UserOutlined} from '@simplepoint/shared/types/icon';
import {useQueryClient} from '@tanstack/react-query';
import {useLocation, useNavigate} from 'react-router';
import {OssImageUpload} from '@simplepoint/components/SForm/widgets/OssImageUpload';
import {put} from '@simplepoint/shared/api/methods';
import {scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {type CurrentTenantProfile, useCurrentTenantProfile, useCurrentTenants} from '@/fetches/tenants.ts';
import {useI18n} from '@/layouts/i18n/useI18n.ts';
import './index.css';
import {TenantResourceManager} from './TenantResourceManager.tsx';

type TenantProfileForm = Pick<CurrentTenantProfile, 'name' | 'description' | 'logo' | 'backgroundImage'>;

const displayValue = (value?: string | null) => value?.trim() || '-';

export const TenantHome: React.FC = () => {
  const {message, modal} = AntdApp.useApp();
  const {t} = useI18n();
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const queryScope = useQueryScope();
  const tenantId = queryScope[0] ?? undefined;
  const [editOpen, setEditOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [section, setSection] = useState('overview');
  const editWarningShown = useRef(false);
  const [form] = Form.useForm<TenantProfileForm>();
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
    const requested = new URLSearchParams(location.search).get('section');
    setSection(requested === 'settings' || requested === 'resources' ? requested : 'overview');
    editWarningShown.current = false;
  }, [queryScope, location.search]);

  useEffect(() => {
    if (!data || !wantsEdit) return;
    if (data.profileEditable) {
      form.setFieldsValue({
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
  }, [data, form, message, navigate, t, wantsEdit]);

  const openEditor = () => {
    if (!data?.profileEditable) return;
    form.setFieldsValue({
      name: data.name,
      description: data.description,
      logo: data.logo,
      backgroundImage: data.backgroundImage,
    });
    setEditOpen(true);
  };

  const closeEditor = () => {
    if (saving) return;
    setEditOpen(false);
    if (wantsEdit) navigate('/tenant', {replace: true});
  };

  const saveProfile = async () => {
    try {
      const values = await form.validateFields();
      setSaving(true);
      const updated = await put<CurrentTenantProfile>(
        '/common/tenants/current-profile',
        values,
        {headers: tenantId ? {'X-Tenant-Id': tenantId} : undefined},
      );
      queryClient.setQueryData(scopedQueryKey(queryScope, ['common', 'tenants', 'current-profile', tenantId]), updated);
      await queryClient.invalidateQueries({queryKey: ['common', 'tenants', 'current']});
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
    } catch (error: any) {
      if (error?.errorFields) return;
      message.error(error?.message ?? t('tenant.profile.saveFailed', '租户资料保存失败'));
    } finally {
      setSaving(false);
    }
  };

  const selectResourceMode = (mode: 'PLATFORM' | 'CUSTOM') => {
    if (mode === (data?.resourceMode ?? 'PLATFORM')) return;
    modal.confirm({
      title: mode === 'CUSTOM'
        ? t('tenant.settings.enableCustom', '启用租户自定义资源')
        : t('tenant.settings.usePlatform', '改用平台内置资源'),
      content: mode === 'CUSTOM'
        ? t('tenant.settings.enableHint', '首次开启会自动拉取当前租户获授权的平台资源。之后角色授权与菜单会使用租户资源目录。')
        : t('tenant.settings.platformHint', '租户自定义目录及角色授权会保留，切回平台模式后暂不生效。'),
      okText: t('action.confirm', '确认'),
      onOk: async () => {
        try {
          setSaving(true);
          await put('/common/tenants/current-resources/mode', {mode},
            {headers: tenantId ? {'X-Tenant-Id': tenantId} : undefined});
          await refetch();
          message.success(t('tenant.settings.modeSaved', '资源加载类型已更新'));
          window.location.reload();
        } catch (error: any) {
          message.error(error?.message ?? t('tenant.settings.modeFailed', '资源加载类型切换失败'));
        } finally {
          setSaving(false);
        }
      },
    });
  };

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
          <Button icon={<ReloadOutlined/>} onClick={() => void refetch()}>{t('action.retry', '重试')}</Button>
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
          <Button icon={<ReloadOutlined/>} onClick={() => void refetch()}>{t('action.refresh', '刷新')}</Button>
          {data.profileEditable ? (
            <Button type="primary" icon={<EditOutlined/>} onClick={openEditor}>
              {t('tenant.profile.edit', '编辑租户资料')}
            </Button>
          ) : null}
        </Space>
      </section>

      <Tabs activeKey={section} onChange={setSection} items={[
        {key: 'overview', label: t('tenant.settings.overview', '空间概览')},
        {key: 'settings', label: t('tenant.settings.title', '空间设置')},
        ...(data.tenantType === 'ORGANIZATION' ? [{key: 'resources',
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

      {section === 'settings' && <Card className="tenant-home-card" title={t('tenant.settings.title', '空间设置')}>
        <Descriptions column={{xs: 1, sm: 2}}>
          <Descriptions.Item label={t('tenant.profile.name', '租户名称')}>{data.name}</Descriptions.Item>
          <Descriptions.Item label={t('tenant.profile.type', '租户类型')}>{tenantTypeLabel}</Descriptions.Item>
          <Descriptions.Item label={t('tenant.profile.description', '租户简介')} span={2}>
            {displayValue(data.description)}
          </Descriptions.Item>
        </Descriptions>
        {data.profileEditable && <Button icon={<EditOutlined/>} onClick={openEditor}>
          {t('tenant.profile.edit', '编辑租户资料')}
        </Button>}
        {data.tenantType === 'ORGANIZATION' && <div className="tenant-resource-mode">
          <Typography.Title level={5}>{t('tenant.settings.resourceMode', '资源加载类型')}</Typography.Title>
          <Typography.Paragraph type="secondary">
            {t('tenant.settings.modeDescription', '默认使用平台内置资源；启用自定义后，本空间的菜单和角色授权以租户目录为准。')}
          </Typography.Paragraph>
          <Space wrap>
            <Button type={(data.resourceMode ?? 'PLATFORM') === 'PLATFORM' ? 'primary' : 'default'}
              disabled={!data.profileEditable || saving} onClick={() => selectResourceMode('PLATFORM')}>
              {t('tenant.settings.platform', '平台内置')}
            </Button>
            <Button type={data.resourceMode === 'CUSTOM' ? 'primary' : 'default'}
              disabled={!data.profileEditable || saving} onClick={() => selectResourceMode('CUSTOM')}>
              {t('tenant.settings.custom', '租户自定义')}
            </Button>
          </Space>
        </div>}
      </Card>}

      {section === 'resources' && data.tenantType === 'ORGANIZATION' && (data.resourceMode === 'CUSTOM'
        ? <TenantResourceManager tenantId={tenantId!}/>
        : <Card><Empty description={t('tenant.resources.disabled', '请先在空间设置中启用租户自定义资源')}>
          {data.profileEditable && <Button type="primary" onClick={() => setSection('settings')}>
            {t('tenant.settings.title', '空间设置')}
          </Button>}
        </Empty></Card>)}

      <Drawer
        title={t('tenant.profile.edit', '编辑租户资料')}
        open={editOpen}
        width={560}
        destroyOnHidden
        maskClosable={!saving}
        closable={!saving}
        onClose={closeEditor}
        extra={<Button type="primary" loading={saving} onClick={() => void saveProfile()}>{t('action.save', '保存')}</Button>}
      >
        <Form form={form} layout="vertical" disabled={saving}>
          <Form.Item name="name" label={t('tenant.profile.name', '租户名称')} rules={[{required: true}, {max: 128}]}>
            <Input maxLength={128} showCount/>
          </Form.Item>
          <Form.Item name="description" label={t('tenant.profile.description', '租户简介')} rules={[{max: 512}]}>
            <Input.TextArea rows={4} maxLength={512} showCount/>
          </Form.Item>
          <Form.Item name="logo" label={t('tenant.profile.logo', '租户 Logo')}>
            <OssImageUpload
              onChange={() => undefined}
              directory="tenants/logos"
              sourceServiceName="tenant-branding"
              shape="square"
              maxSizeMb={5}
            />
          </Form.Item>
          <Form.Item name="backgroundImage" label={t('tenant.profile.background', '租户背景图片')}>
            <OssImageUpload
              onChange={() => undefined}
              directory="tenants/backgrounds"
              sourceServiceName="tenant-branding"
              shape="square"
              maxSizeMb={10}
            />
          </Form.Item>
        </Form>
      </Drawer>
    </div>
  );
};

export default TenantHome;
