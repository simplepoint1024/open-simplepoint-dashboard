import React, {useEffect, useMemo, useRef, useState} from 'react';
import {
  Avatar,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  message,
  Row,
  Skeleton,
  Space,
  Tag,
  Typography,
} from 'antd';
import {ApartmentOutlined, EditOutlined, MailOutlined, PhoneOutlined, ReloadOutlined, UserOutlined} from '@ant-design/icons';
import {useQueryClient} from '@tanstack/react-query';
import {useLocation, useNavigate} from 'react-router';
import {OssImageUpload} from '@simplepoint/components/SForm/widgets/OssImageUpload';
import {put} from '@simplepoint/shared/api/methods';
import {type CurrentTenantProfile, useCurrentTenantProfile} from '@/fetches/tenants.ts';
import {useI18n} from '@/layouts/i18n/useI18n.ts';
import {getTenantId} from '@/store/tenant.ts';
import './index.css';

type TenantProfileForm = Pick<CurrentTenantProfile, 'name' | 'description' | 'logo' | 'backgroundImage'>;

const displayValue = (value?: string | null) => value?.trim() || '-';

export const TenantHome: React.FC = () => {
  const {t} = useI18n();
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [tenantId, setTenantId] = useState<string | undefined>(() => getTenantId());
  const [editOpen, setEditOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const editWarningShown = useRef(false);
  const [form] = Form.useForm<TenantProfileForm>();
  const {data, isLoading, isError, refetch} = useCurrentTenantProfile(tenantId, !!tenantId);
  const wantsEdit = useMemo(
    () => new URLSearchParams(location.search).get('edit') === '1',
    [location.search],
  );

  useEffect(() => {
    const handler = (event: Event) => {
      setTenantId((event as CustomEvent<string | undefined>).detail ?? getTenantId());
      setEditOpen(false);
      editWarningShown.current = false;
    };
    window.addEventListener('sp-set-tenant', handler as EventListener);
    return () => window.removeEventListener('sp-set-tenant', handler as EventListener);
  }, []);

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
  }, [data, form, navigate, t, wantsEdit]);

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
      const updated = await put<CurrentTenantProfile>('/common/tenants/current-profile', values);
      queryClient.setQueryData(['common', 'tenants', 'current-profile', tenantId], updated);
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

  if (isLoading) {
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

      <Row gutter={[16, 16]}>
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
      </Row>

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
