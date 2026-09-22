import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {
  App as AntdApp,
  Avatar,
  Button,
  Col,
  DatePicker,
  Descriptions,
  Divider,
  Empty,
  Form,
  Input,
  Row,
  Select,
  Skeleton,
  Space,
  Tag,
  Typography,
} from 'antd';
import {
  ApartmentOutlined,
  IdcardOutlined,
  LockOutlined,
  LinkOutlined,
  ReloadOutlined,
  RightOutlined,
  SafetyCertificateOutlined,
  SaveOutlined,
  UserOutlined,
} from '@simplepoint/shared/types/icon';
import dayjs from 'dayjs';
import {useQueryClient} from '@tanstack/react-query';
import {OssImageUpload} from '@simplepoint/components/SForm/widgets/OssImageUpload';
import {post, put} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {useI18n} from '@/layouts/i18n/useI18n.ts';
import {type CurrentProfile, useCurrentProfile, useUserInfo} from '@/fetches/user.ts';
import {ProfileAccessSection, ProfileOrganizationSection} from './ProfileSections.tsx';
import './index.css';

type ProfileFormValues = {
  nickname?: string;
  email?: string;
  phoneNumber?: string;
  picture?: string;
  familyName?: string;
  givenName?: string;
  middleName?: string;
  gender?: string;
  birthdate?: dayjs.Dayjs | null;
  address?: string;
  profile?: string;
  website?: string;
  locale?: string;
  zoneinfo?: string;
};

const toFormValues = (profile: CurrentProfile): ProfileFormValues => ({
  nickname: profile.nickname || '',
  email: profile.email || '',
  phoneNumber: profile.phoneNumber || '',
  picture: profile.picture || undefined,
  familyName: profile.familyName || '',
  givenName: profile.givenName || '',
  middleName: profile.middleName || '',
  gender: profile.gender || undefined,
  birthdate: profile.birthdate ? dayjs(profile.birthdate) : null,
  address: profile.address || '',
  profile: profile.profile || '',
  website: profile.website || '',
  locale: profile.locale || undefined,
  zoneinfo: profile.zoneinfo || undefined,
});

const toUpdatePayload = (values: ProfileFormValues) => ({
  ...values,
  birthdate: values.birthdate?.isValid() ? values.birthdate.toISOString() : null,
});

export const Profile: React.FC = () => {
  const {message} = AntdApp.useApp();
  const {t, ensure, locale} = useI18n();
  const queryClient = useQueryClient();
  const {data, isLoading, refetch} = useCurrentProfile();
  const {data: userInfo, refetch: refetchUserInfo} = useUserInfo();
  const [section, setSection] = useState<'about' | 'access' | 'organization' | 'security'>('about');
  const [detailDirty, setDetailDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [avatarSaving, setAvatarSaving] = useState(false);
  const [securityLinks, setSecurityLinks] = useState<{twoFactor: string; externalIdentities: string} | null>(null);
  const [detailForm] = Form.useForm<ProfileFormValues>();

  useEffect(() => {
    void ensure(['profile', 'resources']);
  }, [ensure, locale]);

  useEffect(() => {
    if (section !== 'security') return;
    const controller = new AbortController();
    void fetch('/account/security-links', {credentials: 'same-origin', signal: controller.signal})
      .then(response => {
        if (!response.ok) throw new Error('security links unavailable');
        return response.json() as Promise<{twoFactor: string; externalIdentities: string}>;
      })
      .then(setSecurityLinks)
      .catch(error => {if (error.name !== 'AbortError') setSecurityLinks(null);});
    return () => controller.abort();
  }, [section]);

  const syncProfile = useCallback((updated: CurrentProfile) => {
    queryClient.setQueryData(['current-profile'], updated);
    queryClient.setQueryData(['userinfo'], (current: any) => ({
      ...(current || {}),
      ...updated,
      phone: updated.phoneNumber ?? current?.phone,
    }));
    window.dispatchEvent(new CustomEvent('sp-user-profile-updated', {detail: updated}));
  }, [queryClient]);

  useEffect(() => {
    if (data && !detailDirty) {
      detailForm.setFieldsValue(toFormValues(data));
    }
  }, [data, detailForm, detailDirty]);

  useEffect(() => {
    const handleFocus = () => {
      void refetch();
      void refetchUserInfo();
    };
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') handleFocus();
    };
    window.addEventListener('focus', handleFocus);
    document.addEventListener('visibilitychange', handleVisibilityChange);
    return () => {
      window.removeEventListener('focus', handleFocus);
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, [refetch, refetchUserInfo]);

  const roles = useMemo(() => {
    const raw = userInfo?.roles;
    if (!Array.isArray(raw)) return [];
    return raw.map((role: any) => typeof role === 'string' ? role : role?.name).filter(Boolean) as string[];
  }, [userInfo]);

  const displayName = data?.nickname || data?.name || t('user.defaultName', '用户');

  const saveDetails = async () => {
    try {
      const values = await detailForm.validateFields();
      setSaving(true);
      const updated = await put<CurrentProfile>('/common/users/me', toUpdatePayload(values));
      syncProfile(updated);
      setDetailDirty(false);
      message.success(t('profile.saveSuccess', '个人资料已保存'));
    } catch (error: any) {
      if (error?.errorFields) return;
      message.error(resolveApiErrorMessage(error, t('profile.saveFailed', '个人资料保存失败')));
    } finally {
      setSaving(false);
    }
  };

  const saveAvatar = async (picture?: string) => {
    if (!data) return;
    setAvatarSaving(true);
    try {
      const updated = await put<CurrentProfile>('/common/users/me', {
        ...toUpdatePayload(toFormValues(data)),
        picture: picture || null,
      });
      syncProfile(updated);
      detailForm.setFieldValue('picture', updated.picture || undefined);
    } finally {
      setAvatarSaving(false);
    }
  };

  const [pwdSaving, setPwdSaving] = useState(false);
  usePageRetention(false, detailDirty || saving || avatarSaving || pwdSaving);
  const [pwdForm] = Form.useForm();
  const onChangePassword = async () => {
    try {
      const values = await pwdForm.validateFields();
      if (values.newPassword !== values.confirmPassword) {
        pwdForm.setFields([{name: 'confirmPassword', errors: [t('rule.passwordMismatch', '两次输入的密码不一致')]}]);
        return;
      }
      setPwdSaving(true);
      await post('/common/users/change-password', values);
      message.success(t('profile.passwordChanged', '密码修改成功'));
      pwdForm.resetFields();
    } catch (error: any) {
      if (error?.errorFields) return;
      message.error(resolveApiErrorMessage(error, t('profile.passwordChangeFailed', '密码修改失败')));
    } finally {
      setPwdSaving(false);
    }
  };

  const formatDate = (value?: string | null) => value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-';

  return (
    <div className="profile-page">
      <div className="profile-shell">
        <aside className="profile-navigation" aria-label={t('profile.nav.title', '个人资料目录')}>
          <div className="profile-nav-identity">
            <Avatar size={72} src={data?.picture || undefined} icon={!data?.picture ? <UserOutlined/> : undefined}/>
            <div className="profile-nav-info"><strong>{displayName}</strong>
              <small>{data?.email || t('profile.noEmail', '未绑定邮箱')}</small>
              <div className="profile-nav-avatar-actions"><OssImageUpload value={data?.picture}
                onChange={saveAvatar} disabled={avatarSaving || !data}
                directory="avatars/users" sourceServiceName="rbac-avatar" shape="circle" maxSizeMb={5}
                replaceLabel={t('profile.avatarChange', '更换头像')}/></div>
            </div>
          </div>
          <div className="profile-nav-bio">{data?.profile || t('profile.about.noBio', '还没有填写个人简介。')}</div>
          {!!roles.length && <div className="profile-nav-roles">{roles.map(role => <Tag key={role} color="blue">{role}</Tag>)}</div>}
          <div className="profile-nav-label">{t('profile.nav.title', '个人资料目录')}</div>
          <nav className="profile-nav-links">
            {([
              ['about', <UserOutlined/>, t('profile.nav.about', '关于我')],
              ['access', <SafetyCertificateOutlined/>, t('profile.nav.access', '资源权限')],
              ['organization', <ApartmentOutlined/>, t('profile.nav.organization', '所在组织')],
              ['security', <LockOutlined/>, t('profile.nav.security', '账户安全')],
            ] as const).map(([key, icon, label]) =>
              <button key={key} type="button" className={section === key ? 'active' : ''}
                aria-current={section === key ? 'page' : undefined} onClick={() => setSection(key)}>
                <span>{icon}</span>{label}<RightOutlined/>
              </button>)}
          </nav>
        </aside>
        <main className="profile-main" aria-label={t('profile.nav.title', '个人资料目录')}>
          {section === 'about' && <section className="profile-content-section" aria-label={t('profile.nav.about', '关于我')}>
            <div className="profile-content-group">
              <div className="profile-group-heading"><h3><IdcardOutlined/> {t('profile.basic', '基本信息')}</h3>
                <Button icon={<ReloadOutlined/>} onClick={() => void refetch()}>{t('action.refresh', '刷新')}</Button></div>
              {isLoading ? <Skeleton active paragraph={{rows: 7}}/> : !data ? <Empty/> :
                <Descriptions column={{xs: 1, sm: 2}} size="middle" className="profile-descriptions">
                  <Descriptions.Item label={t('field.nickname', '昵称')}>{data.nickname || '-'}</Descriptions.Item>
                  <Descriptions.Item label={t('field.fullName', '姓名')}>
                    {[data.familyName, data.middleName, data.givenName].filter(Boolean).join(' ') || data.name || '-'}
                  </Descriptions.Item>
                  <Descriptions.Item label={t('field.email', '邮箱')}>{data.email || '-'}</Descriptions.Item>
                  <Descriptions.Item label={t('field.phone', '手机号')}>{data.phoneNumber || '-'}</Descriptions.Item>
                  <Descriptions.Item label={t('field.profile', '个人简介')} span={2}>{data.profile || '-'}</Descriptions.Item>
                  <Descriptions.Item label={t('field.joinedAt', '加入时间')}>{formatDate(data.createdAt)}</Descriptions.Item>
                  <Descriptions.Item label={t('field.updatedAt', '最近更新')}>{formatDate(data.updatedAt)}</Descriptions.Item>
                </Descriptions>}
            </div>
            <Divider/>
            <div className="profile-content-group">
              <h3 className="profile-group-title"><IdcardOutlined/> {t('profile.details.title', '详细资料')}</h3>
              <Form layout="vertical" form={detailForm} className="profile-detail-form"
                onValuesChange={() => setDetailDirty(!!data && JSON.stringify(toUpdatePayload(detailForm.getFieldsValue(true)))
                  !== JSON.stringify(toUpdatePayload(toFormValues(data))))}>
                <Form.Item name="picture" hidden><Input/></Form.Item>
          <Row gutter={12}>
            <Col xs={24} sm={12}>
              <Form.Item
                name="nickname"
                label={t('field.nickname', '昵称')}
                rules={[{required: true, message: t('rule.nickname', '请输入昵称')}]}
              >
                <Input maxLength={50}/>
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item name="gender" label={t('field.gender', '性别')}>
                <Select allowClear options={[
                  {value: 'male', label: t('gender.male', '男')},
                  {value: 'female', label: t('gender.female', '女')},
                  {value: 'other', label: t('gender.other', '其他')},
                ]}/>
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col xs={24} sm={8}><Form.Item name="familyName" label={t('field.familyName', '姓氏')}><Input maxLength={50}/></Form.Item></Col>
            <Col xs={24} sm={8}><Form.Item name="middleName" label={t('field.middleName', '中间名')}><Input maxLength={50}/></Form.Item></Col>
            <Col xs={24} sm={8}><Form.Item name="givenName" label={t('field.givenName', '名字')}><Input maxLength={50}/></Form.Item></Col>
          </Row>
          <Form.Item name="birthdate" label={t('field.birthdate', '出生日期')}><DatePicker style={{width: '100%'}}/></Form.Item>

          <Divider titlePlacement="start">{t('profile.details.contact', '联系方式')}</Divider>
          <Row gutter={12}>
            <Col xs={24} sm={12}>
              <Form.Item
                name="email"
                label={t('field.email', '邮箱')}
                rules={[{type: 'email', message: t('rule.email', '邮箱格式不正确')}]}
              >
                <Input maxLength={64}/>
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}><Form.Item name="phoneNumber" label={t('field.phone', '手机号')}><Input maxLength={18}/></Form.Item></Col>
          </Row>
          <Form.Item name="address" label={t('field.address', '联系地址')}><Input maxLength={255}/></Form.Item>
          <Form.Item name="website" label={t('field.website', '个人网站')} rules={[{type: 'url', warningOnly: true}]}><Input maxLength={255}/></Form.Item>

          <Divider titlePlacement="start">{t('profile.details.preferences', '个人偏好')}</Divider>
          <Form.Item name="profile" label={t('field.profile', '个人简介')}><Input.TextArea maxLength={500} showCount autoSize={{minRows: 3, maxRows: 6}}/></Form.Item>
          <Row gutter={12}>
            <Col xs={24} sm={12}>
              <Form.Item name="locale" label={t('field.locale', '语言区域')}>
                <Select allowClear options={[
                  {value: 'zh-CN', label: '简体中文 (zh-CN)'},
                  {value: 'en-US', label: 'English (en-US)'},
                ]}/>
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item name="zoneinfo" label={t('field.zoneinfo', '时区')}>
                <Select showSearch allowClear options={[
                  {value: 'Asia/Shanghai', label: 'Asia/Shanghai'},
                  {value: 'Asia/Hong_Kong', label: 'Asia/Hong_Kong'},
                  {value: 'Asia/Tokyo', label: 'Asia/Tokyo'},
                  {value: 'Europe/London', label: 'Europe/London'},
                  {value: 'America/New_York', label: 'America/New_York'},
                ]}/>
              </Form.Item>
            </Col>
          </Row>
              </Form>
              <div className="profile-detail-actions"><Button type="primary" icon={<SaveOutlined/>}
                disabled={!detailDirty || !data} loading={saving} onClick={() => void saveDetails()}>
                {t('action.save', '保存')}</Button></div>
            </div>
          </section>}
          {section === 'access' && <ProfileAccessSection/>}
          {section === 'organization' && <ProfileOrganizationSection/>}
          {section === 'security' && <section className="profile-content-section" aria-label={t('profile.nav.security', '账户安全')}>
            <div className="profile-content-group"><h3 className="profile-group-title">{t('profile.security.methods', '登录与验证方式')}</h3>
              <div className="profile-security-row">
                <div className={`profile-security-icon ${data?.twoFactorEnabled ? 'enabled' : ''}`}><SafetyCertificateOutlined/></div>
                <div className="profile-security-copy"><Space size={8} wrap>
                  <Typography.Title level={5}>{t('field.twoFactorEnabled', '两步认证')}</Typography.Title>
                  <Tag color={data?.twoFactorEnabled ? 'success' : 'default'}>{data?.twoFactorEnabled
                    ? t('profile.twoFactor.enabled', '已开启') : t('profile.twoFactor.disabled', '未开启')}</Tag>
                </Space><Typography.Paragraph>{t('profile.twoFactor.description', '使用动态验证码为账号增加一层登录保护。')}</Typography.Paragraph></div>
                <Button type="link" icon={<RightOutlined/>} iconPosition="end" disabled={!securityLinks}
                  href={securityLinks?.twoFactor} target="_blank" rel="noopener noreferrer">
                  {data?.twoFactorEnabled ? t('action.manageTwoFactor', '管理两步认证') : t('action.enableTwoFactor', '开启两步认证')}
                </Button>
              </div>
              <Divider className="profile-security-divider"/>
              <div className="profile-security-row">
                <div className="profile-security-icon external"><LinkOutlined/></div>
                <div className="profile-security-copy"><Typography.Title level={5}>{t('profile.externalAccounts.title', '第三方账号绑定')}</Typography.Title>
                  <Typography.Paragraph>{t('profile.externalAccounts.description', '绑定 GitHub、Google 等身份提供商，用于安全快捷登录。')}</Typography.Paragraph></div>
                <Button type="link" icon={<RightOutlined/>} iconPosition="end" disabled={!securityLinks}
                  href={securityLinks?.externalIdentities} target="_blank" rel="noopener noreferrer">
                  {t('profile.externalAccounts.manage', '管理第三方账号')}</Button>
              </div>
            </div>
            <Divider/>
            <div className="profile-content-group"><h3 className="profile-group-title"><LockOutlined/> {t('profile.changePassword', '修改密码')}</h3>
              <Form layout="vertical" form={pwdForm} className="profile-password-form">
                <Form.Item name="currentPassword" label={t('field.currentPassword', '当前密码')}
                  rules={[{required: true, message: t('rule.currentPassword', '请输入当前密码')}]}
                ><Input.Password maxLength={64} autoComplete="current-password"/></Form.Item>
                <Row gutter={12}>
                  <Col xs={24} sm={12}><Form.Item name="newPassword" label={t('field.newPassword', '新密码')}
                    rules={[{required: true, message: t('rule.newPassword', '请输入新密码')},
                      {min: 6, message: t('rule.passwordLength', '密码长度至少 6 位')}]}
                  ><Input.Password maxLength={64} autoComplete="new-password"/></Form.Item></Col>
                  <Col xs={24} sm={12}><Form.Item name="confirmPassword" label={t('field.confirmPassword', '确认新密码')}
                    rules={[{required: true, message: t('rule.confirmPassword', '请再次输入新密码')}]}
                  ><Input.Password maxLength={64} autoComplete="new-password"/></Form.Item></Col>
                </Row>
                <Button type="primary" icon={<LockOutlined/>} loading={pwdSaving} onClick={onChangePassword}>
                  {t('action.changePassword', '修改密码')}</Button>
              </Form>
            </div>
          </section>}
        </main>
      </div>

    </div>
  );
};
